package tv.dumbtv.home;

/** Deterministic HOME throttle checks: no Android runtime, device, or sleeps. */
public final class HomeRetryTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    /** Models the Handler's one scheduled callback; timing decisions belong to HomeRetry. */
    private static final class Queue {
        final HomeRetry retry = new HomeRetry();
        long now = 10000;
        long due = -1;
        int attempts;

        void event(long time) {
            now = time;
            retry.request();
            dispatch();
        }

        void dispatch() {
            due = -1;
            if (retry.take(now)) attempts++;
            long delay = retry.delay(now);
            if (delay >= 0) due = now + delay;
        }

        void advance(long time) {
            now = time;
            if (due >= 0 && due <= now) dispatch();
        }

        void cancel() {
            retry.cancel();
            due = -1;
        }
    }

    private static void lostFinalEvent() {
        Queue q = new Queue();
        q.event(10000);
        check(q.attempts == 1 && q.due == -1, "ordinary HOME attempts immediately");
        q.cancel(); // dumbtv appears after the first attempt
        q.event(10200); // Amazon returns inside the repeat window, then no more events
        check(q.attempts == 1 && q.due == 11000, "retain final HOME until repeat deadline");
        q.advance(11000);
        check(q.attempts == 2 && q.due == -1, "recover without another window event");
        q.advance(20000);
        check(q.attempts == 2, "no retry loop after recovery");
    }

    private static void burst() {
        Queue q = new Queue();
        q.event(10000);
        for (long time : new long[] {10200, 10500, 10999}) {
            q.event(time);
            check(q.attempts == 1 && q.due == 11000, "burst keeps one fixed deadline");
        }
        q.advance(10999);
        check(q.attempts == 1, "never retry early");
        q.advance(11000);
        check(q.attempts == 2 && q.due == -1, "one retry for burst");
    }

    private static void boundaryAndOrdering() {
        for (long time : new long[] {10999, 11000, 11001}) {
            Queue q = new Queue();
            q.event(10000);
            q.event(time);
            check(q.attempts == (time < 11000 ? 1 : 2), "deadline boundary " + time);
            check(q.due == (time < 11000 ? 11000 : -1), "boundary queue " + time);
        }
        Queue eventFirst = new Queue();
        eventFirst.event(10000);
        eventFirst.event(10200);
        eventFirst.event(11000);
        eventFirst.dispatch(); // stale callback must not launch again
        check(eventFirst.attempts == 2 && eventFirst.due == -1, "event first at deadline");

        Queue callbackFirst = new Queue();
        callbackFirst.event(10000);
        callbackFirst.event(10200);
        callbackFirst.advance(11000);
        callbackFirst.event(11000);
        check(callbackFirst.attempts == 2 && callbackFirst.due == 12000,
                "callback first: new HOME is throttled, not another immediate launch");
        callbackFirst.cancel(); // dumbtv's window arrives
        callbackFirst.advance(12000);
        check(callbackFirst.attempts == 2, "arrival cancels redundant request");
    }

    private static void lateDispatch() {
        Queue q = new Queue();
        q.event(10000);
        q.event(10200);
        q.advance(11500);
        check(q.attempts == 2 && q.due == -1, "late callback attempts once");
        q.event(11600);
        check(q.due == 12500, "next interval starts at actual attempt time");
    }

    private static void cancellation() {
        Queue q = new Queue();
        q.event(10000);
        q.event(10200);
        q.cancel();
        q.now = 11000;
        q.dispatch(); // even a callback dispatched after cancellation is harmless
        check(q.attempts == 1 && q.due == -1, "cancel prevents a late launch");

        q.event(11200);
        check(q.attempts == 2 && q.due == -1, "fresh HOME after cancellation works");
        q.event(11300);
        q.cancel();
        q.event(11400);
        check(q.due == 12200, "cancel preserves throttle history");
        q.dispatch(); // a stale callback cannot consume the fresh request early
        check(q.attempts == 2 && q.due == 12200, "stale callback respects fresh deadline");
        q.advance(12200);
        check(q.attempts == 3 && q.due == -1, "fresh deferred request still works");

        Queue restarted = new Queue();
        restarted.dispatch();
        check(restarted.attempts == 0 && restarted.due == -1, "new instance has no pending work");
    }

    public static void main(String[] args) {
        lostFinalEvent();
        burst();
        boundaryAndOrdering();
        lateDispatch();
        cancellation();
        System.out.println("HomeRetryTest passed (lost event, burst, boundaries, ordering, late dispatch, cancellation)");
    }
}
