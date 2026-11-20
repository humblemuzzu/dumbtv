package tv.dumbtv.home;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ActivityNotFoundException;
import android.content.Intent;

/**
 * dumbtv quick settings (quick/ in this repository), when it is installed: the panel that slides
 * in from the right with brightness, Bluetooth, warmth, a sleep timer and a status strip. It is a
 * separate app so that dumbtv home keeps its single permission; the permissions the panel needs
 * are granted to it alone by quick/install.sh. Without it, the Settings chip opens the TV's
 * settings as before.
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

    static boolean open(Activity a, int accent, String from, String idlePause, int idleMinutes) {
        Intent i = new Intent(ACTION).setPackage(PACKAGE)
                .putExtra(EXTRA_ACCENT, accent)
                .putExtra(EXTRA_FROM, from);
        if (idlePause != null) i.putExtra(EXTRA_IDLE_PAUSE, idlePause);
        if (idleMinutes > 0) i.putExtra(EXTRA_IDLE_MINUTES, idleMinutes);
        if (i.resolveActivity(a.getPackageManager()) == null) return false;
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
