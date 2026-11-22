package tv.dumbtv.home;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;

/**
 * dumbtv quick settings (quick/ in this repository), when it is installed: the panel that slides
 * in from the right with brightness, Bluetooth, warmth, a sleep timer and a status strip. It is a
 * separate app so that dumbtv home keeps its single permission; the permissions the panel needs
 * are granted to it alone by quick/install.sh. The remote's settings (cog) button opens it (the
 * panel's own service reads the press and asks {@link Guard} whether it is switched on here); the
 * Settings chip on the home screen opens the TV's own settings.
 */
final class Quick {
    static final String PACKAGE = "tv.dumbtv.quick";
    static final String ACTION = "tv.dumbtv.quick.PANEL";
    static final int REQUEST_CODE = 42;
    static final String EXTRA_ACCENT = "accent";
    static final String EXTRA_FROM = "from";
    static final String EXTRA_IDLE_PAUSE = "idle_pause";
    static final String EXTRA_IDLE_MINUTES = "idle_minutes";

    private Quick() {}

    /**
     * Opens the panel in this activity's task, so BACK returns here and HOME clears it. {@code from}
     * is "home" or "input"; the panel shows the HDMI signal's format only for an input.
     */
    static boolean open(Activity a, int accent, String from) {
        return open(a, accent, from, null, 0);
    }

    /**
     * Whether the remote's settings button and Menu on an input should open the panel: it is
     * installed, Options › Quick settings panel is on (the default), and home is not locked.
     */
    static boolean enabled(Context c, Config cfg) {
        return "on".equals(cfg.get(Config.QUICK)) && !"on".equals(cfg.get(Config.LOCK)) && installed(c);
    }

    /** Config key the panel's Auto-switch row writes; read by idle_input where home has it. */
    static final String CONF_IDLE_PAUSE = "idle_pause";

    static boolean installed(Context c) {
        return intent(0, null).resolveActivity(c.getPackageManager()) != null;
    }

    private static Intent intent(int accent, String from) {
        return new Intent(ACTION).setPackage(PACKAGE)
                .putExtra(EXTRA_ACCENT, accent)
                .putExtra(EXTRA_FROM, from);
    }

    static boolean open(Activity a, int accent, String from, String idlePause, int idleMinutes) {
        Intent i = intent(accent, from);
        if (idlePause != null) i.putExtra(EXTRA_IDLE_PAUSE, idlePause);
        if (idleMinutes > 0) i.putExtra(EXTRA_IDLE_MINUTES, idleMinutes);
        if (i.resolveActivity(a.getPackageManager()) == null) return false;
        try {
            a.sendBroadcast(new Intent("tv.dumbtv.quick.SHOW").setPackage(PACKAGE).putExtras(i));
            return true;
        } catch (Exception ignored) {}
        try {
            if ("home".equals(from)) {
                a.startActivityForResult(i, REQUEST_CODE, ActivityOptions.makeCustomAnimation(a, 0, 0).toBundle());
            } else {
                a.startActivity(i, ActivityOptions.makeCustomAnimation(a, 0, 0).toBundle());
            }
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
