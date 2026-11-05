package tv.dumbtv.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

import java.util.ArrayList;
import java.util.List;

/**
 * The Settings chip on Fire OS 6 (issue #77). Amazon's standalone settings page draws its first
 * row above the top of the screen there, so the chip opens a short list instead: the sections a
 * third-party app may start directly, then Amazon's own settings row for everything else.
 *
 * The sections are the intents Amazon's settings row sends (CardPushService in
 * com.amazon.tv.settings.v2), in its order. A section is listed only when its screen is installed,
 * enabled, exported and needs no permission this app lacks. On NS6708 that leaves the six below;
 * Display & Sounds, Preferences, Accessibility, Account, Profiles, Inputs, Live TV, Alexa, Sleep
 * Timer and Help need Amazon's signature permissions (LAUNCHER_SETTINGS and others), so they are
 * reached through Amazon's row.
 */
final class FireSettings {
    static final String MAIN_SETTINGS = "com.amazon.tv.launcher.ui.MainSettingsActivity";
    static final int ALL = R.string.fire_all_settings;

    /** Labels for {@link #SECTIONS}, in the same order. */
    private static final int[] LABELS = {
        R.string.fire_notifications, R.string.fire_network, R.string.fire_applications,
        R.string.fire_equipment_control, R.string.fire_controllers, R.string.fire_device,
    };
    private static final String[][] SECTIONS = {
        {"com.amazon.tv.action.NOTIFICATION_CENTER",
                "com.amazon.tv.notificationcenter", "com.amazon.tv.notificationcenter.NotificationCenterActivity"},
        {"com.amazon.device.settings.action.ADD_NETWORK",
                "com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.network.NetworkActivity"},
        {"com.amazon.device.settings.action.APPLICATIONS",
                "com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.applications.ApplicationsActivity"},
        {"com.amazon.tv.oobe.settings.ACTION.DEVICE_CONTROL",
                "com.amazon.tv.devicecontrolsettings",
                "com.amazon.tv.devicecontrolsettings.screens.main_menu.OzOobeSettingsActivity"},
        {"com.amazon.device.settings.action.CONTROLLERS",
                "com.amazon.tv.settings.v2",
                "com.amazon.tv.settings.v2.tv.controllers_bluetooth_devices.ControllersAndBluetoothActivity"},
        {"com.amazon.device.settings.action.DEVICE",
                "com.amazon.tv.settings.v2", "com.amazon.tv.settings.v2.tv.device.DeviceActivity"},
    };

    private FireSettings() {}

    /** True where the chip should open {@link #sections} rather than the TV's settings page. */
    static boolean applies(PackageManager pm, Intent tvSettings) {
        if (android.os.Build.VERSION.SDK_INT >= 26) return false;
        ComponentName target = tvSettings.resolveActivity(pm);
        return target != null && MAIN_SETTINGS.equals(target.getClassName());
    }

    /** The sections this TV lets us open, then Amazon's settings row (labelled {@link #ALL}). */
    static List<Item> sections(Context c) {
        PackageManager pm = c.getPackageManager();
        List<Item> out = new ArrayList<>();
        for (int k = 0; k < SECTIONS.length; k++) {
            String[] s = SECTIONS[k];
            // Most sections share com.amazon.tv.settings.v2's task. Clearing it means BACK leaves
            // to the home screen, not to a section opened earlier and left with HOME.
            Intent i = new Intent(s[0]).setClassName(s[1], s[2])
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if (startable(c, pm, i)) out.add(new Item(s[2], c.getString(LABELS[k]), i));
        }
        // The node key Amazon's own menu navigation sends to its home activity; the settings
        // row draws in full there. Opening it pauses the HOME takeover, as Options › Open
        // Amazon's home screen does.
        out.add(new Item(Guard.AMAZON, c.getString(ALL), new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME).setPackage(Guard.AMAZON)
                .putExtra("navigate_node", "l_settings")));
        return out;
    }

    private static boolean startable(Context c, PackageManager pm, Intent i) {
        ActivityInfo ai;
        try {
            ai = pm.getActivityInfo(i.getComponent(), 0);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
        if (!ai.exported || !ai.isEnabled()) return false;
        return ai.permission == null
                || pm.checkPermission(ai.permission, c.getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }
}
