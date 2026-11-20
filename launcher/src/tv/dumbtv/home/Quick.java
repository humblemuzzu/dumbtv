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

    private Quick() {}

    /**
     * Opens the panel in this activity's task, so BACK returns here and HOME clears it. {@code from}
     * is "home" or "input"; the panel shows the HDMI signal's format only for an input.
     */
    static boolean open(Activity a, int accent, String from) {
        Intent i = new Intent(ACTION).setPackage(PACKAGE)
                .putExtra("accent", accent).putExtra("from", from);
        if (i.resolveActivity(a.getPackageManager()) == null) return false;
        try {
            // The panel animates itself in; a window transition on top of that looked doubled.
            a.startActivity(i, ActivityOptions.makeCustomAnimation(a, 0, 0).toBundle());
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }
}
