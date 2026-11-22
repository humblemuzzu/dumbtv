package tv.dumbtv.quick;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Amazon's own quick settings, the overlay that com.amazon.tv.quicksettings.ui draws for the
 * remote's settings (cog) button and for a long press of HOME. Two of its own switches let the
 * panel replace it without racing its window (Fire OS 8.1, QuickSettingsUI 1.0.8428):
 *
 * <ul>
 *   <li><b>The lock.</b> Its launch receiver (QSLaunchButtonPressReceiver) returns without opening
 *       anything while {@code Settings.Secure quick_settings_lock} is 1. With the lock on, the cog
 *       never creates Amazon's overlay at all. Writing it needs WRITE_SECURE_SETTINGS, which
 *       install.sh grants over adb. The same receiver handles a long press of HOME, so
 *       {@link Tint} takes the lock off for that press.</li>
 *   <li><b>The state.</b> Its overlay service sends ACTION_OVERLAY_INTENT_VISIBILITY, an
 *       unprotected broadcast meant for the store demo, with "Hide" when it starts and "Show" when
 *       it stops, so {@link #up} says whether the overlay is on screen.</li>
 * </ul>
 *
 * Nothing outside Amazon can open or close the overlay itself: GLOBAL_BUTTON is a protected
 * broadcast, and every other way in (StartQSUIActivity, the SETTING_ID_QS and keypad receivers,
 * DISMISS_QUICKSETTINGS_UI) needs a signature permission. The launcher's QuickSettingsProxyActivity
 * would open it, but only from SDK 34.
 *
 * An update that drops the lock turns that layer off and nothing else: Amazon's overlay then
 * starts with the panel, which raises itself above it and has it closed after itself.
 */
final class AmazonQs {
    static final String PKG = "com.amazon.tv.quicksettings.ui";
    static final String LOCK = "quick_settings_lock";
    static final String VISIBILITY = "com.amazon.tv.quicksettings.demo.ACTION_OVERLAY_INTENT_VISIBILITY";
    private static final String WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS";

    private static volatile boolean up;
    private static volatile long changedAt;

    private AmazonQs() {}

    /** Whether Amazon's overlay is on screen, as its own visibility broadcast last said. */
    static boolean up() {
        return up;
    }

    /** uptimeMillis of the last visibility broadcast. */
    static long changedAt() {
        return changedAt;
    }

    /** From {@link Tint}'s receiver: "Hide" (the demo hides) means the overlay started. */
    static void visibility(String extra) {
        boolean now = "Hide".equals(extra);
        if (!now && !"Show".equals(extra)) return;
        up = now;
        changedAt = SystemClock.uptimeMillis();
    }

    static boolean canLock(Context c) {
        return c.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean locked(Context c) {
        return Settings.Secure.getInt(c.getContentResolver(), LOCK, 0) == 1;
    }

    /** Sets the lock; false where it cannot be written (no grant). */
    static boolean lock(Context c, boolean on) {
        if (locked(c) == on) return true;
        if (!canLock(c)) return false;
        try {
            boolean ok = Settings.Secure.putInt(c.getContentResolver(), LOCK, on ? 1 : 0);
            Log.i(Tint.TAG, "Amazon quick settings lock " + (on ? "on" : "off") + (ok ? "" : " failed"));
            return ok;
        } catch (SecurityException e) {
            Log.w(Tint.TAG, "Amazon quick settings lock not writable", e);
            return false;
        }
    }
}
