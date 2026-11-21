package tv.dumbtv.quick;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

import java.util.TimeZone;

/**
 * Brightness that stays put across input switches, with auto-dim after sunset.
 *
 * The TV keeps a backlight level per source in its picture settings and writes it to system
 * screen_brightness when the source changes: on an AFTHY7ABBA, dimming HDMI to 191, going home
 * (217) and back to HDMI gave 204 again. So the level set in the panel is remembered per source
 * here (HDMI, or apps and home) and written back just after the TV restores its own.
 *
 * Dim after sunset: per source there is a day level and a night level. When auto is on, Tint's
 * 60-second tick writes the blend with Sun.night(). All writes pass through write() so adopt()
 * never mistakes automatic blends or picture-off for the owner's choice.
 */
final class Backlight {
    private static final String HDMI = "bright_hdmi", OTHER = "bright_other";
    private static final String HDMI_NIGHT = "bright_hdmi_night", OTHER_NIGHT = "bright_other_night";
    static final String AUTO = "bright_auto";

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
        Log.i(Tint.TAG, "source: " + (nowHdmi ? "hdmi" : "other") + ", target brightness " + target(c));
        ui.removeCallbacks(restore);
        for (long d : RESTORE_MS) ui.postDelayed(restore, d);
    }

    private void restore() {
        restoreLevel(c);
        Warmth.sourceChanged(c);
    }

    static void restoreLevel(Context c) {
        if (Tint.isPictureOff()) return; // the picture-off overlay keeps the backlight at 0
        int t = target(c);
        if (t != get(c)) write(c, t);
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
            boolean night = isAuto(c) && isNight();
            Tint.prefs(c).edit().putInt(key(night), v).apply();
        }, SETTLE_MS);
    }

    static int get(Context c) {
        return Settings.System.getInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, 128);
    }

    static boolean isAuto(Context c) {
        return Tint.prefs(c).getBoolean(AUTO, false);
    }

    static void setAuto(Context c, boolean auto) {
        SharedPreferences p = Tint.prefs(c);
        p.edit().putBoolean(AUTO, auto).apply();
        if (auto) {
            // Ensure night levels are seeded if unset
            if (p.getInt(HDMI_NIGHT, -1) < 0) {
                int day = p.getInt(HDMI, get(c));
                p.edit().putInt(HDMI_NIGHT, Math.max(5, Math.round(day * 0.6f))).apply();
            }
            if (p.getInt(OTHER_NIGHT, -1) < 0) {
                int day = p.getInt(OTHER, get(c));
                p.edit().putInt(OTHER_NIGHT, Math.max(5, Math.round(day * 0.6f))).apply();
            }
        }
        restoreLevel(c);
    }

    static boolean isNight() {
        return nightFactor() > 0.001f;
    }

    static float nightFactor() {
        long now = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        double[] at = Sun.place(tz);
        return Sun.night(now, tz, at[0], at[1]);
    }

    static int dayLevel(Context c) {
        return Tint.prefs(c).getInt(key(false), get(c));
    }

    static int nightLevel(Context c) {
        int saved = Tint.prefs(c).getInt(key(true), -1);
        if (saved >= 0) return saved;
        return Math.max(5, Math.round(dayLevel(c) * 0.6f));
    }

    static int target(Context c) {
        if (!isAuto(c)) return dayLevel(c);
        return Sun.blend(dayLevel(c), nightLevel(c), nightFactor());
    }

    static void tick(Context c) {
        if (isAuto(c) && !Tint.isPictureOff()) {
            int t = target(c);
            if (t != get(c)) write(c, t);
        }
    }

    /** The panel's step: edits day or night level depending on time and auto mode. */
    static void set(Context c, int value) {
        boolean atNight = isAuto(c) && isNight();
        Tint.prefs(c).edit().putInt(key(atNight), value).apply();
        int t = target(c);
        write(c, t);
    }

    static void write(Context c, int value) {
        lastWritten = value;
        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        Settings.System.putInt(c.getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, value);
    }

    private static String key(boolean night) {
        if (hdmi) return night ? HDMI_NIGHT : HDMI;
        return night ? OTHER_NIGHT : OTHER;
    }
}
