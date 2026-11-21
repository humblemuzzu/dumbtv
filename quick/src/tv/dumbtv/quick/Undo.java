package tv.dumbtv.quick;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/**
 * Gets this app ready to be uninstalled: gives up the device admin (dpm remove-active-admin only
 * removes test admins, and Android refuses to uninstall an active admin), cancels the sleep timer
 * and removes the warmth. The manifest requires android.permission.DUMP, which adb's shell holds
 * and installed apps cannot, so only quick/install.sh --undo reaches it:
 *   adb shell am broadcast -n tv.dumbtv.quick/.Undo
 */
public final class Undo extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Sleep.set(c, 0);
        Warmth.restore(c);
        Tint.prefs(c).edit().clear().apply();
        c.stopService(new Intent(c, Tint.class));
        ((DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE))
                .removeActiveAdmin(new ComponentName(c, Admin.class));
        setResultCode(1);
    }
}
