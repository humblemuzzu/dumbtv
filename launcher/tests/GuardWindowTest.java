package tv.dumbtv.home;

import android.content.Context;

/** Runs Guard's real classifier against the SDK without constructing an Android service. */
public final class GuardWindowTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static void window(CharSequence cls, boolean retain) {
        HomeRetry retry = new HomeRetry();
        retry.request();
        check(retry.take(10000), "first attempt");
        retry.request();
        check(retry.cancelForWindow(Guard.homeWindow(true, cls)) != retain,
                "cancellation for " + cls);
        check(retry.delay(10100) == (retain ? 900 : -1), "deadline for " + cls);
        check(retry.take(11000) == retain, "retry after " + cls);
    }

    public static void main(String[] args) {
        window("tv.dumbtv.home.Home", true);
        window("android.view.View", true);
        window("tv.dumbtv.home.Options", false);
        window("tv.dumbtv.home.Input", false);
        window("android.app.Dialog", false);
        window(null, false);
        check(!Guard.homeWindow(false, "tv.dumbtv.home.Home"), "another app");
        check(!Guard.homeWindow(false, "android.view.View"), "another app's view");
        System.out.println("GuardWindowTest passed (cover/Home retained; Input/Options cancel)");
    }
}

// Type-only stand-ins for Guard's collaborators; these methods must never run here.
final class Home {}
final class Keeper {
    static void pause() { throw new AssertionError("unexpected Keeper call"); }
}
final class Config {
    static Config get(Context context) { throw new AssertionError("unexpected Config call"); }
    int background() { throw new AssertionError("unexpected background call"); }
}
