package tv.dumbtv.quick;

import java.util.Calendar;
import java.util.TimeZone;

/** Sunrise/sunset against published almanac times (timeanddate.com), within 6 minutes. */
public final class SunTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static long at(TimeZone tz, int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(tz);
        c.clear();
        c.set(y, mo - 1, d, h, mi);
        return c.getTimeInMillis();
    }

    private static void near(double actual, long expected, String name) {
        check(!Double.isNaN(actual), name + ": NaN");
        double off = Math.abs(actual - expected) / 60000.0;
        check(off <= 6, name + ": off by " + Math.round(off) + " min");
    }

    private static void city(String zone, double lat, double lon, int y, int mo, int d,
                             int riseH, int riseM, int setH, int setM) {
        TimeZone tz = TimeZone.getTimeZone(zone);
        double[] rs = Sun.times(at(tz, y, mo, d, 12, 0), tz, lat, lon);
        near(rs[0], at(tz, y, mo, d, riseH, riseM), zone + " sunrise");
        near(rs[1], at(tz, y, mo, d, setH, setM), zone + " sunset");
    }

    public static void main(String[] args) {
        city("America/Chicago", 41.9, -87.6, 2026, 11, 19, 6, 46, 16, 24);
        city("Europe/London", 51.5, -0.1, 2026, 6, 21, 4, 43, 21, 21);
        city("Australia/Sydney", -33.9, 151.2, 2026, 12, 21, 5, 41, 20, 5);
        city("Asia/Tokyo", 35.7, 139.7, 2026, 3, 20, 5, 45, 17, 53);

        // The zone table and the fallback.
        double[] chicago = Sun.place(TimeZone.getTimeZone("America/Chicago"));
        check(chicago[0] > 41 && chicago[0] < 43 && chicago[1] < -87, "Chicago from its zone");
        double[] fallback = Sun.place(TimeZone.getTimeZone("Etc/GMT-3"));
        check(fallback[0] == 40.0 && Math.abs(fallback[1] - 45) < 0.01, "UTC+3 falls back to 45 E");

        // The night factor: 0 by day, ramping after sunset (30s), 1 deep in the night, ramping out before sunrise (30s).
        TimeZone tz = TimeZone.getTimeZone("America/Chicago");
        check(Sun.night(at(tz, 2026, 11, 19, 13, 0), tz, 41.9, -87.6) == 0f, "day is 0");
        double[] rs = Sun.times(at(tz, 2026, 11, 19, 12, 0), tz, 41.9, -87.6);
        float dusk = Sun.night((long) rs[1] + 15000, tz, 41.9, -87.6);
        check(dusk > 0.45f && dusk < 0.55f, "15s after sunset is about half: " + dusk);
        check(Sun.night(at(tz, 2026, 11, 19, 22, 0), tz, 41.9, -87.6) == 1f, "22:00 is 1");
        check(Sun.night(at(tz, 2026, 11, 19, 3, 0), tz, 41.9, -87.6) == 1f, "03:00 is 1");
        float dawn = Sun.night((long) rs[0] - 15000, tz, 41.9, -87.6);
        check(dawn > 0.45f && dawn < 0.55f, "15s before sunrise is about half: " + dawn);

        // Polar: Tromsø has no sunrise in December and no sunset in June.
        TimeZone oslo = TimeZone.getTimeZone("Europe/Oslo");
        check(Sun.night(at(oslo, 2026, 12, 21, 12, 0), oslo, 69.6, 18.9) == 1f, "polar night");
        check(Sun.night(at(oslo, 2026, 6, 21, 23, 0), oslo, 69.6, 18.9) == 0f, "midnight sun");

        // Dim blend between day and night brightness levels
        check(Sun.blend(200, 100, 0f) == 200, "blend day factor 0");
        check(Sun.blend(200, 100, 1f) == 100, "blend night factor 1");
        check(Sun.blend(200, 100, 0.5f) == 150, "blend half factor");
        check(Sun.blend(200, 100, -0.2f) == 200, "clamp lower bound");
        check(Sun.blend(200, 100, 1.5f) == 100, "clamp upper bound");

        // Next sunset calculation: during day -> today's sunset; at night -> tomorrow's sunset
        long dayTime = at(tz, 2026, 11, 19, 13, 0);
        long nextFromDay = Sun.nextSunset(dayTime, tz, 41.9, -87.6);
        near(nextFromDay, at(tz, 2026, 11, 19, 16, 24), "next sunset from daytime is today");

        long nightTime = at(tz, 2026, 11, 19, 22, 0);
        long nextFromNight = Sun.nextSunset(nightTime, tz, 41.9, -87.6);
        near(nextFromNight, at(tz, 2026, 11, 20, 16, 24), "next sunset from night is tomorrow");

        System.out.println("SunTest ok");
    }
}
