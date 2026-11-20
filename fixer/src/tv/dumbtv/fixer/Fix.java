package tv.dumbtv.fixer;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Keeps network adb usable across reboots on this Fire TV.
 *
 * Seen on Fire OS 8.1.8.5 (livingroom-tv LOG.md 2026-11-19): during boot Fire OS sets
 * Settings.Global.ADB_ENABLED to 0, so port 5555 stays closed until a person toggles ADB
 * debugging in Developer Options. Earlier boots also left the port dead with the setting on.
 *
 * At BOOT_COMPLETED and again at {@link #RECHECK_MS} (Fire OS may switch ADB off after the
 * boot broadcast), this:
 * <ul>
 * <li>turns ADB back on if it is off, but only when the owner opted in: install.sh creates
 * {@code keep-adb-on} in this app's media folder;</li>
 * <li>at BOOT_COMPLETED with ADB already on, switches it off and on, which reopens the port.</li>
 * </ul>
 * It only acts in the first minutes after a boot, so turning ADB off later in Developer
 * Options sticks until the next boot. All of it runs on a receiver's thread, where Android keeps
 * the process alive; the first version used a goAsync() thread and was killed between "off" and
 * "on". Every run is appended to fixer.log in the media folder (logcat rotates within minutes).
 */
final class Fix {
    static final String TAG = "dumbtv-fixer";
    static final String MARKER = "keep-adb-on";
    static final String ACTION_RECHECK = "tv.dumbtv.fixer.action.RECHECK";
    private static final long[] RECHECK_MS = {45_000, 120_000};
    private static final long OFF_MS = 1500;

    private Fix() {
    }

    /** At BOOT_COMPLETED: check now, and schedule the re-checks. */
    static void onBoot(Context c) {
        reviveAdb(c, "boot", true);
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        for (int i = 0; i < RECHECK_MS.length; i++) {
            Intent intent = new Intent(ACTION_RECHECK).setClass(c, CheckReceiver.class);
            PendingIntent pi = PendingIntent.getBroadcast(c, i, intent, PendingIntent.FLAG_UPDATE_CURRENT);
            alarms.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + RECHECK_MS[i], pi);
        }
    }

    /**
     * @param toggleIfOn switch ADB off and on even when it is on (boot, or an explicit request);
     *                   re-checks only turn it on when Fire OS switched it off
     */
    static void reviveAdb(Context c, String why, boolean toggleIfOn) {
        boolean granted = c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
        int enabled = Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, -1);
        boolean optedIn = marker(c).isFile();
        note(c, why + ": granted=" + granted + " adb_enabled=" + enabled + " keep-adb-on=" + optedIn
                + " persist.sys.adb.enable=" + prop("persist.sys.adb.enable")
                + " tcp.port=" + prop("service.adb.tcp.port")
                + " uptime=" + SystemClock.elapsedRealtime() / 1000 + "s");
        if (!granted) {
            note(c, why + ": WRITE_SECURE_SETTINGS not granted (run fixer/install.sh)");
            return;
        }
        if (enabled != 1) {
            if (!optedIn) {
                note(c, why + ": ADB is off and keep-adb-on is absent, leaving it off");
                return;
            }
            Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
            SystemClock.sleep(1500);
            note(c, why + ": ADB was off, turned it on; tcp.port=" + prop("service.adb.tcp.port"));
            return;
        }
        if (!toggleIfOn) return;
        try {
            Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
            SystemClock.sleep(OFF_MS);
        } finally {
            Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
        }
        SystemClock.sleep(1500);
        note(c, why + ": switched off and on; tcp.port=" + prop("service.adb.tcp.port"));
    }

    private static File mediaDir(Context c) {
        File[] dirs = c.getExternalMediaDirs();
        return dirs.length == 0 ? null : dirs[0];
    }

    private static File marker(Context c) {
        File dir = mediaDir(c);
        return new File(dir == null ? c.getFilesDir() : dir, MARKER);
    }

    /** android.os.SystemProperties is hidden; reading it by reflection works for apps targeting 28. */
    private static String prop(String key) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            return (String) sp.getMethod("get", String.class).invoke(null, key);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "?";
        }
    }

    private static void note(Context c, String msg) {
        Log.i(TAG, msg);
        try {
            File dir = mediaDir(c);
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) return;
            String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " " + msg + "\n";
            try (FileOutputStream out = new FileOutputStream(new File(dir, "fixer.log"), true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            Log.w(TAG, "could not write fixer.log", e);
        }
    }
}
