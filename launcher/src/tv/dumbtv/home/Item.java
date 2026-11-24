package tv.dumbtv.home;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.drawable.Drawable;

/** One tile: an installed app (id = package name) or a TV input (id = input id). */
final class Item {
    final String id;
    final CharSequence label;
    final Intent intent;
    ActivityInfo info;
    boolean leanback;
    int state = -1;
    volatile boolean artRequested;
    volatile Drawable banner, icon;
    volatile int backgroundColor;
    volatile boolean colorRequested;
    /** Afterglow color read from the app's art; 0 until read, or when the art has no color. */
    volatile int tileColor;

    Item(String id, CharSequence label, Intent intent) {
        this.id = id;
        this.label = label;
        this.intent = intent;
    }
}
