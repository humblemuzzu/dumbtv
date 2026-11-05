package tv.dumbtv.home;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Brings personalDNSfilter back after its process died. Android does not restart a VPN whose
 * process was killed (lmkd under memory pressure, a crash), so until something starts the app
 * again Amazon's hosts resolve normally (#162).
 *
 * The filter's BootUpReceiver starts its VPN service without showing the app, and returns at once
 * when the service is already running, so waking it is safe at any time. It accepts only senders
 * holding RECEIVE_BOOT_COMPLETED, the one permission dumbtv home asks for on Fire OS 7 and later.
 * A force-stopped filter (dnsfilter.sh replacing its files) gets no broadcast, which is wanted.
 */
final class Filter {
    private static final ComponentName BOOT =
            new ComponentName("dnsfilter.android", "dnsfilter.android.BootUpReceiver");
    static final String CHECK = "tv.dumbtv.home.CHECK_FILTER";

    /** Wakes the filter now and every 15 minutes, including after our own process was killed. */
    static void watch(Context c) {
        wake(c);
        PendingIntent check = PendingIntent.getBroadcast(c, 0,
                new Intent(CHECK).setClass(c, BootReceiver.class), PendingIntent.FLAG_UPDATE_CURRENT);
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.setInexactRepeating(AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_FIFTEEN_MINUTES,
                AlarmManager.INTERVAL_FIFTEEN_MINUTES, check);
    }

    static void wake(Context c) {
        c.sendBroadcast(new Intent().setComponent(BOOT));
    }
}
