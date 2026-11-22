package tv.dumbtv.quick;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.TimeZone;

/**
 * Direct hardware picture quality warmth control, driving MediaTek's CustomerPqService.
 * Replaces software window overlays: zero black-floor elevation, zero 8-bit banding or grain,
 * and pure 10-bit hardware CFD rendering with true blacks preserved.
 *
 * Full warmth range (0 to 10):
 *   Level 0:  Off / Standard (daytime neutral, D65, gains 0)
 *   Level 1:  Standard, Blue -4, Red 0
 *   Level 2:  Standard, Blue -8, Red +1
 *   Level 3:  Standard, Blue -11, Red +2
 *   Level 4:  Standard, Blue -15, Red +3
 *   Level 5:  Warm (TV native calibrated Warm preset, gains 0)
 *   Level 6:  Warm, Blue -3, Red +3
 *   Level 7:  Warm, Blue -6, Red +6
 *   Level 8:  Warm, Blue -9, Red +9
 *   Level 9:  Warm, Blue -12, Red +12
 *   Level 10: Warm, Blue -15, Red +15 (Deepest candlelight f.lux)
 *
 * Fast-ramp on sunset:
 *   When auto is on, transitioning into or out of night mode ramps smoothly across
 *   intermediate steps within 30 seconds.
 */
public final class Warmth {
    private static final String TAG = "dumbtv-warmth";
    public static final int STEPS = 10;

    public static final int MODE_ALWAYS_ON = 0;
    public static final int MODE_AFTER_SUNSET = 1;
    public static final int MODE_PAUSED = 2; // "Off till next sunset"

    private static volatile int lastApplied = -1;

    private Warmth() {}

    /** Resolves the active mode based on preferences and current timestamp. */
    public static int getMode(SharedPreferences p, long now) {
        if (!p.getBoolean(Tint.AUTO, false)) {
            return MODE_ALWAYS_ON;
        }
        long pauseUntil = p.getLong(Tint.WARMTH_PAUSE, 0L);
        if (pauseUntil > now) {
            return MODE_PAUSED;
        }
        return MODE_AFTER_SUNSET;
    }

    /** Applies the current target warmth to TV hardware. */
    public static void tick(Context c) {
        int target = targetLevel(c);
        if (target != lastApplied) {
            if (applyHardware(target)) {
                lastApplied = target;
            }
        }
    }

    /** Called on manual user adjustment from the UI: applies immediately. */
    public static void step(Context c, int dir) {
        SharedPreferences p = Tint.prefs(c);
        int level = Math.max(0, Math.min(STEPS, p.getInt(Tint.WARMTH, STEPS / 2) + dir));
        p.edit().putInt(Tint.WARMTH, level).apply();
        applyImmediate(c);
    }

    /** Cycles through modes: Always on -> After sunset -> Off till next sunset -> Always on. */
    public static void cycleMode(Context c) {
        SharedPreferences p = Tint.prefs(c);
        long now = System.currentTimeMillis();
        int current = getMode(p, now);
        SharedPreferences.Editor e = p.edit();
        if (current == MODE_ALWAYS_ON) {
            e.putBoolean(Tint.AUTO, true);
            e.remove(Tint.WARMTH_PAUSE);
            if (p.getInt(Tint.WARMTH, 0) == 0) {
                e.putInt(Tint.WARMTH, STEPS / 2);
            }
        } else if (current == MODE_AFTER_SUNSET) {
            TimeZone tz = TimeZone.getDefault();
            double[] at = Sun.place(tz);
            long nextSunset = Sun.nextSunset(now, tz, at[0], at[1]);
            e.putBoolean(Tint.AUTO, true);
            e.putLong(Tint.WARMTH_PAUSE, nextSunset);
        } else {
            e.putBoolean(Tint.AUTO, false);
            e.remove(Tint.WARMTH_PAUSE);
            if (p.getInt(Tint.WARMTH, 0) == 0) {
                e.putInt(Tint.WARMTH, STEPS / 2);
            }
        }
        e.apply();
        apply(c, false);
    }

    /** Toggles auto mode / cycles through the three modes. */
    public static void toggleAuto(Context c) {
        cycleMode(c);
    }

    /** Re-evaluates and applies warmth (immediate if specified, otherwise steps). */
    public static void apply(Context c, boolean immediate) {
        int target = targetLevel(c);
        if (immediate || target != lastApplied) {
            if (applyHardware(target)) {
                lastApplied = target;
            }
        }
    }

    /** Immediately applies the current target to hardware. */
    public static void applyImmediate(Context c) {
        int target = targetLevel(c);
        if (applyHardware(target)) {
            lastApplied = target;
        }
    }

    /** Re-asserts warmth on the newly active input after a source switch. */
    public static void sourceChanged(Context c) {
        if (lastApplied >= 0) {
            applyHardware(lastApplied);
        }
    }

    /** Restores TV hardware picture settings to default Standard / zero gains. */
    public static void restore(Context c) {
        Log.i(TAG, "Restoring hardware picture warmth to default");
        applyHardware(0);
        lastApplied = 0;
    }

    /** Calculates the target level (0 to STEPS) for the current time. */
    public static int targetLevel(Context c) {
        SharedPreferences p = Tint.prefs(c);
        int saved = p.getInt(Tint.WARMTH, STEPS / 2);
        long now = System.currentTimeMillis();
        int mode = getMode(p, now);
        if (mode == MODE_ALWAYS_ON) return saved;
        if (mode == MODE_PAUSED) return 0;

        // MODE_AFTER_SUNSET
        TimeZone tz = TimeZone.getDefault();
        double[] at = Sun.place(tz);
        float n = Sun.night(now, tz, at[0], at[1]);
        return Math.round(saved * n);
    }

    /**
     * Determines how soon the next tick should run:
     * - Every 1.5s while actively in the 30-second sunset/sunrise ramp.
     * - At the exact moment sunset or sunrise ramp begins if within 60s.
     * - At the expiration of a pause if within 60s.
     * - Every 60s at resting state.
     */
    public static long nextTickDelay(Context c) {
        SharedPreferences p = Tint.prefs(c);
        long now = System.currentTimeMillis();
        int mode = getMode(p, now);
        if (mode == MODE_ALWAYS_ON) return 60000L;

        if (mode == MODE_PAUSED) {
            long pauseUntil = p.getLong(Tint.WARMTH_PAUSE, 0L);
            long left = pauseUntil - now;
            if (left > 0 && left < 60000L) {
                return Math.max(1000L, left);
            }
            return 60000L;
        }

        // MODE_AFTER_SUNSET
        TimeZone tz = TimeZone.getDefault();
        double[] at = Sun.place(tz);
        float n = Sun.night(now, tz, at[0], at[1]);

        // Actively in the 30-second ramp
        if (n > 0.001f && n < 0.999f) {
            return 1500L;
        }

        double[] rs = Sun.times(now, tz, at[0], at[1]);
        if (!Double.isNaN(rs[0]) && !Double.isNaN(rs[1])) {
            long sunriseRampStart = (long) rs[0] - Sun.RAMP_MS;
            long sunsetRampStart = (long) rs[1];
            if (now < sunriseRampStart && sunriseRampStart - now < 60000L) {
                return Math.max(1000L, sunriseRampStart - now);
            }
            if (now < sunsetRampStart && sunsetRampStart - now < 60000L) {
                return Math.max(1000L, sunsetRampStart - now);
            }
        }
        return 60000L;
    }

    /** Maps level (0 to 10) directly into hardware picture quality registers. Returns true if applied. */
    private static boolean applyHardware(int level) {
        if (!PqBridge.isConnected()) {
            Log.w(TAG, "applyHardware: PqBridge not connected, postponing");
            return false;
        }
        level = Math.max(0, Math.min(STEPS, level));
        Log.i(TAG, "applyHardware: level " + level + "/" + STEPS);
        boolean ok = true;
        if (level <= 0) {
            ok &= PqBridge.set("Color_Temperature", "Standard");
            ok &= PqBridge.set("Color_Temperature_Blue_Gain", "0");
            ok &= PqBridge.set("Color_Temperature_Red_Gain", "0");
            return ok;
        }
        if (level < 5) {
            // Levels 1-4: Standard temperature with progressive blue reduction
            int blue = -Math.round(level * 3.75f); // -4, -8, -11, -15
            int red = Math.max(0, level - 1);       //  0,  1,   2,   3
            ok &= PqBridge.set("Color_Temperature", "Standard");
            ok &= PqBridge.set("Color_Temperature_Blue_Gain", String.valueOf(blue));
            ok &= PqBridge.set("Color_Temperature_Red_Gain", String.valueOf(red));
        } else if (level == 5) {
            // Level 5 (50%): Native calibrated "Warm" preset
            ok &= PqBridge.set("Color_Temperature", "Warm");
            ok &= PqBridge.set("Color_Temperature_Blue_Gain", "0");
            ok &= PqBridge.set("Color_Temperature_Red_Gain", "0");
        } else {
            // Levels 6-10: Warm preset with progressive blue attenuation & red boost
            int extra = level - 5; // 1 to 5
            int blue = -extra * 3; // -3, -6, -9, -12, -15
            int red = extra * 3;   // +3, +6, +9, +12, +15
            ok &= PqBridge.set("Color_Temperature", "Warm");
            ok &= PqBridge.set("Color_Temperature_Blue_Gain", String.valueOf(blue));
            ok &= PqBridge.set("Color_Temperature_Red_Gain", String.valueOf(red));
        }
        return ok;
    }
}
