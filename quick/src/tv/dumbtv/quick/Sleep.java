package tv.dumbtv.quick;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * The sleep timer. An exact alarm fires this receiver, which puts the TV to sleep with
 * DevicePolicyManager.lockNow(): the same standby as the remote's power button ("Going to sleep due
 * to device_admin"). That needs {@link Admin} to be an active device admin, which quick/install.sh
 * sets with dpm set-active-admin; Android then refuses to uninstall this app until it gives that
 * up, so install.sh --undo asks {@link Undo} to first.
 */
public final class Sleep extends BroadcastReceiver {
    static final String AT = "sleep_at";
    static final int STEP_MIN = 15, MAX_MIN = 240;

    /** When the TV goes to sleep, as epoch milliseconds, or 0 when the timer is off. */
    static long at(Context c) {
        long at = Tint.prefs(c).getLong(AT, 0);
        return at > System.currentTimeMillis() ? at : 0;
    }

    /** Minutes left, rounded up, or 0. */
    static int minutesLeft(Context c) {
        long at = at(c);
        return at == 0 ? 0 : (int) Math.ceil((at - System.currentTimeMillis()) / 60000.0);
    }

    /**
     * One step of the remote: right adds 15 minutes, left takes them away. From a running timer it
     * first rounds to the 15-minute mark, so 37 minutes left goes to 45 or 30.
     */
    static int step(int minutesLeft, int direction) {
        int next;
        if (direction > 0) next = (minutesLeft / STEP_MIN + 1) * STEP_MIN;
        else next = ((minutesLeft + STEP_MIN - 1) / STEP_MIN - 1) * STEP_MIN;
        return Math.max(0, Math.min(MAX_MIN, next));
    }

    /** Sets the timer to {@code minutes} from now, or off with 0. */
    static void set(Context c, int minutes) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = PendingIntent.getBroadcast(c, 0, new Intent(c, Sleep.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
        if (minutes <= 0) {
            am.cancel(pi);
            Tint.prefs(c).edit().remove(AT).apply();
            return;
        }
        long at = System.currentTimeMillis() + minutes * 60000L;
        Tint.prefs(c).edit().putLong(AT, at).apply();
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    static boolean canSleep(Context c) {
        DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm.isAdminActive(new ComponentName(c, Admin.class));
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        Tint.prefs(c).edit().remove(AT).apply();
        if (!canSleep(c)) {
            Log.w(Tint.TAG, "sleep timer: not a device admin; run quick/install.sh");
            return;
        }
        Log.i(Tint.TAG, "sleep timer: going to sleep");
        ((DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE)).lockNow();
    }
}
