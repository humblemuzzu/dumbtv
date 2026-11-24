package tv.dumbtv.home;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.view.KeyEvent;

/**
 * Art mode as the TV's screensaver, set with settings put secure screensaver_components.
 * Left/right change the painting; any other key wakes the TV, as a screensaver should.
 *
 * While it shows, the backlight runs at art.brightness percent (home.conf, 35 by default).
 * Fire OS ignores a window's brightness, so the system setting itself is lowered and put
 * back after; that needs WRITE_SETTINGS, granted over adb (appops set ... WRITE_SETTINGS allow).
 */
public final class ArtDream extends DreamService {
    static final String BRIGHTNESS = "art.brightness";
    private static final String SAVED = "saved_brightness";
    private ArtView art;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(true);
        setFullscreen(true);
        ArtView.translucent(getWindow());
        art = new ArtView(this);
        setContentView(art);
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dim(this);
    }

    @Override
    public void onDreamingStopped() {
        restore(this);
        super.onDreamingStopped();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) art.step(code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
            return true;
        }
        finish();
        return true;
    }

    /** Percent of full brightness, from home.conf; 35 when unset or not a number in 5..100. */
    static int brightness(String value) {
        try {
            int v = Integer.parseInt(value == null ? "" : value.trim());
            return v >= 5 && v <= 100 ? v : 35;
        } catch (NumberFormatException e) {
            return 35;
        }
    }

    /** Lowers the backlight, keeping the owner's level to put back; never raises it. */
    private static void dim(Context c) {
        if (!Settings.System.canWrite(c)) return;
        ContentResolver r = c.getContentResolver();
        int now = Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, -1);
        if (now < 0) return;
        SharedPreferences p = prefs(c);
        // a level saved by a dream that never stopped is the owner's; the current one is ours
        if (!p.contains(SAVED)) p.edit().putInt(SAVED, now).commit();
        int target = Math.round(255 * brightness(Config.get(c).get(BRIGHTNESS, null)) / 100f);
        if (target < now) Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, target);
    }

    /** Puts the owner's level back. Home calls it too, in case a dream ended without stopping. */
    static void restore(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.contains(SAVED)) return;
        if (Settings.System.canWrite(c)) {
            Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, p.getInt(SAVED, 255));
        }
        p.edit().remove(SAVED).commit();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("art", Context.MODE_PRIVATE);
    }
}
