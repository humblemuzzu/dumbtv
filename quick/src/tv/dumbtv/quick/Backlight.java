package tv.dumbtv.quick;

import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Brightness that stays put across input switches. The TV keeps a backlight level per source in
 * its picture settings and writes it to system screen_brightness when the source changes: on an
 * AFTHY7ABBA, dimming HDMI to 191, going home (217) and back to HDMI gave 204 again. So the level
 * set in the panel is remembered per source here (HDMI, or apps and home) and written back just
 * after the TV restores its own. A change made in the TV's own picture settings, away from a
 * switch, is taken as the new level for that source.
 */
final class Backlight {
    private static final String HDMI = "bright_hdmi", OTHER = "bright_other";
    /** The TV's own restore lands within about a second of the stream starting; two tries cover it. */
    private static final long[] RESTORE_MS = {800, 2500};
    private static final long SWITCH_WINDOW_MS = 4000, SETTLE_MS = 1500;

    /** Whether an HDMI input is showing, from {@link Video}; false without READ_LOGS. */
    static volatile boolean hdmi;
    private static volatile int lastWritten = -1;

    private final Context c;
    private final Handler ui;
    private long switchedAt;
    private final Runnable restore = this::restore;
    private final ContentObserver observer;

    Backlight(Context c, Handler ui) {
        this.c = c;
        this.ui = ui;
        observer = new ContentObserver(ui) {
            @Override
            public void onChange(boolean self) {
                adopt();
            }
        };
        c.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false, observer);
    }

    void stop() {
        ui.removeCallbacks(restore);
        c.getContentResolver().unregisterContentObserver(observer);
    }

    /** On the UI thread, when an HDMI input starts or stops showing. */
    void source(boolean nowHdmi) {
        hdmi = nowHdmi;
        switchedAt = SystemClock.uptimeMillis();
        Log.i(Tint.TAG, "source: " + (nowHdmi ? "hdmi" : "other") + ", saved brightness " + Tint.prefs(c).getInt(key(), -1));
        ui.removeCallbacks(restore);
        for (long d : RESTORE_MS) ui.postDelayed(restore, d);
    }

    private void restore() {
        int saved = Tint.prefs(c).getInt(key(), -1);
        if (saved >= 0 && saved != get(c)) write(c, saved);
    }

    /**
     * A change nobody here made, away from a source switch, came from the TV's own settings: keep
     * it. Judged {@link #SETTLE_MS} later, because the TV's restore can land just before
     * DispService logs the new stream, and must not be mistaken for the owner's choice.
     */
    private void adopt() {
        final int v = get(c);
        if (v == lastWritten) return;
        final long changedAt = SystemClock.uptimeMillis();
        ui.postDelayed(() -> {
            if (Math.abs(changedAt - switchedAt) < SWITCH_WINDOW_MS || get(c) != v) return;
            Tint.prefs(c).edit().putInt(key(), v).apply();
        }, SETTLE_MS);
    }

    static int get(Context c) {
        return Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
    }

    /** The panel's step: applies {@code value} (0-255) and remembers it for the source showing. */
    static void set(Context c, int value) {
        write(c, value);
        Tint.prefs(c).edit().putInt(key(), value).apply();
    }

    private static void write(Context c, int value) {
        lastWritten = value;
        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
    }

    private static String key() {
        return hdmi ? HDMI : OTHER;
    }
}
