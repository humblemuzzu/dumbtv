package tv.dumbtv.fixer;

import android.content.Context;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * After a reboot this TV's network adb (port 5555) can stay dead until ADB debugging is
 * switched off and on in Developer Options. This does that switch once per boot through
 * Settings.Global, which needs WRITE_SECURE_SETTINGS (granted by install.sh over adb).
 *
 * It only turns ADB off and back on; it never turns ADB on when it was off. It does not check
 * the port first: that would need the INTERNET permission, and restarting adbd at boot costs
 * nothing.
 */
final class Fix {
    static final String TAG = "dumbtv-fixer";

    private Fix() {
    }

    static void reviveAdb(Context c) throws InterruptedException {
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "reviveAdb: WRITE_SECURE_SETTINGS not granted (run fixer/install.sh)");
            return;
        }
        if (Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1) {
            Log.i(TAG, "reviveAdb: ADB debugging is off, leaving it off");
            return;
        }
        try {
            Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0);
            Thread.sleep(2000);
        } finally {
            Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
        }
        Log.i(TAG, "reviveAdb: switched ADB debugging off and on");
    }
}
