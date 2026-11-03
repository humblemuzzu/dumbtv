package tv.dumbtv.home;

/** Retains one HOME request until the repeat window ends. Times are monotonic uptime. */
final class HomeRetry {
    private static final long REPEAT_MS = 1000;
    private long lastLaunch;
    private boolean pending;

    void request() {
        pending = true;
    }

    /** Milliseconds until the pending attempt, or -1 when there is no request. */
    long delay(long now) {
        return pending ? Math.max(0, lastLaunch + REPEAT_MS - now) : -1;
    }

    boolean take(long now) {
        if (delay(now) != 0) return false;
        pending = false;
        lastLaunch = now;
        return true;
    }

    void cancel() {
        pending = false;
    }
}
