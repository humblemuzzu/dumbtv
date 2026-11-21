package tv.dumbtv.quick;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Sunrise and sunset on the TV, with no network and no location permission: the place is
 * taken from the time zone ({@link #place}), and the times from the sunrise equation in the
 * US Naval Observatory's "Almanac for Computers" (zenith 90.833°, good to a couple of minutes).
 * Pure Java, so quick/tests/SunTest.java runs it on a computer.
 */
final class Sun {
    /** How long the warmth takes to fade in after sunset, and out before sunrise (30 seconds). */
    static final long RAMP_MS = 30 * 1000L;

    private Sun() {}

    /**
     * Rough coordinates (latitude, longitude) for the time zones most TVs are set to. A zone not
     * listed falls back to its UTC offset for the longitude and 40° north, which puts sunset
     * within about half an hour for most of the populated world.
     */
    private static final Object[][] PLACES = {
        {"America/New_York", 40.7, -74.0}, {"America/Detroit", 42.3, -83.0},
        {"America/Toronto", 43.7, -79.4}, {"America/Chicago", 41.9, -87.6},
        {"America/Winnipeg", 49.9, -97.1}, {"America/Denver", 39.7, -105.0},
        {"America/Edmonton", 53.5, -113.5}, {"America/Phoenix", 33.4, -112.1},
        {"America/Los_Angeles", 34.1, -118.2}, {"America/Vancouver", 49.3, -123.1},
        {"America/Anchorage", 61.2, -149.9}, {"Pacific/Honolulu", 21.3, -157.9},
        {"America/Halifax", 44.6, -63.6}, {"America/St_Johns", 47.6, -52.7},
        {"America/Mexico_City", 19.4, -99.1}, {"America/Bogota", 4.7, -74.1},
        {"America/Lima", -12.0, -77.0}, {"America/Santiago", -33.4, -70.7},
        {"America/Sao_Paulo", -23.5, -46.6}, {"America/Argentina/Buenos_Aires", -34.6, -58.4},
        {"Europe/London", 51.5, -0.1}, {"Europe/Dublin", 53.3, -6.3},
        {"Europe/Lisbon", 38.7, -9.1}, {"Europe/Madrid", 40.4, -3.7},
        {"Europe/Paris", 48.9, 2.4}, {"Europe/Brussels", 50.8, 4.4},
        {"Europe/Amsterdam", 52.4, 4.9}, {"Europe/Berlin", 52.5, 13.4},
        {"Europe/Rome", 41.9, 12.5}, {"Europe/Zurich", 47.4, 8.5},
        {"Europe/Vienna", 48.2, 16.4}, {"Europe/Stockholm", 59.3, 18.1},
        {"Europe/Oslo", 59.9, 10.8}, {"Europe/Copenhagen", 55.7, 12.6},
        {"Europe/Helsinki", 60.2, 24.9}, {"Europe/Warsaw", 52.2, 21.0},
        {"Europe/Prague", 50.1, 14.4}, {"Europe/Athens", 38.0, 23.7},
        {"Europe/Istanbul", 41.0, 29.0}, {"Europe/Kiev", 50.5, 30.5},
        {"Europe/Kyiv", 50.5, 30.5}, {"Europe/Moscow", 55.8, 37.6},
        {"Africa/Cairo", 30.0, 31.2}, {"Africa/Lagos", 6.5, 3.4},
        {"Africa/Johannesburg", -26.2, 28.0}, {"Africa/Nairobi", -1.3, 36.8},
        {"Asia/Dubai", 25.2, 55.3}, {"Asia/Karachi", 24.9, 67.0},
        {"Asia/Kolkata", 19.1, 72.9}, {"Asia/Calcutta", 19.1, 72.9},
        {"Asia/Dhaka", 23.8, 90.4}, {"Asia/Bangkok", 13.8, 100.5},
        {"Asia/Jakarta", -6.2, 106.8}, {"Asia/Singapore", 1.3, 103.8},
        {"Asia/Manila", 14.6, 121.0}, {"Asia/Hong_Kong", 22.3, 114.2},
        {"Asia/Shanghai", 31.2, 121.5}, {"Asia/Taipei", 25.0, 121.6},
        {"Asia/Seoul", 37.6, 127.0}, {"Asia/Tokyo", 35.7, 139.7},
        {"Australia/Perth", -31.95, 115.9}, {"Australia/Adelaide", -34.9, 138.6},
        {"Australia/Brisbane", -27.5, 153.0}, {"Australia/Sydney", -33.9, 151.2},
        {"Australia/Melbourne", -37.8, 145.0}, {"Pacific/Auckland", -36.8, 174.8},
    };

    /** {latitude, longitude} for the zone, see {@link #PLACES}. */
    static double[] place(TimeZone tz) {
        for (Object[] p : PLACES) {
            if (p[0].equals(tz.getID())) return new double[] {(Double) p[1], (Double) p[2]};
        }
        return new double[] {40.0, tz.getRawOffset() / 3600000.0 * 15.0};
    }

    /**
     * Sunrise and sunset, as epoch milliseconds, on the local calendar day that contains
     * {@code now}. Both are NaN on a polar day or night; {@link #night} handles that.
     */
    static double[] times(long now, TimeZone tz, double lat, double lon) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        int dayOfYear = c.get(Calendar.DAY_OF_YEAR);
        c.set(Calendar.HOUR_OF_DAY, 12);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long localNoon = c.getTimeInMillis();
        // UTC midnight of the same calendar date.
        Calendar u = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        u.clear();
        u.set(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH));
        long utcMidnight = u.getTimeInMillis();
        double rise = hourUtc(dayOfYear, lat, lon, true);
        double set = hourUtc(dayOfYear, lat, lon, false);
        return new double[] {nearNoon(utcMidnight, rise, localNoon), nearNoon(utcMidnight, set, localNoon)};
    }

    /** The instant of {@code hour} UTC on that date, moved by a day if that lands it nearer local noon. */
    private static double nearNoon(long utcMidnight, double hour, long localNoon) {
        if (Double.isNaN(hour)) return hour;
        double t = utcMidnight + hour * 3600000.0;
        while (t - localNoon > 12 * 3600000.0) t -= 24 * 3600000.0;
        while (localNoon - t > 12 * 3600000.0) t += 24 * 3600000.0;
        return t;
    }

    /** Hour of day (UTC) of sunrise or sunset; NaN if the sun does not cross the horizon that day. */
    static double hourUtc(int dayOfYear, double lat, double lon, boolean rising) {
        double lngHour = lon / 15.0;
        double t = dayOfYear + ((rising ? 6 : 18) - lngHour) / 24.0;
        double m = 0.9856 * t - 3.289;
        double l = norm360(m + 1.916 * sin(m) + 0.020 * sin(2 * m) + 282.634);
        double ra = norm360(Math.toDegrees(Math.atan(0.91764 * tan(l))));
        ra = (ra + Math.floor(l / 90) * 90 - Math.floor(ra / 90) * 90) / 15.0;
        double sinDec = 0.39782 * sin(l);
        double cosDec = Math.cos(Math.asin(sinDec));
        double cosH = (cos(90.833) - sinDec * sin(lat)) / (cosDec * cos(lat));
        if (cosH > 1 || cosH < -1) return Double.NaN;
        double h = (rising ? 360 - Math.toDegrees(Math.acos(cosH)) : Math.toDegrees(Math.acos(cosH))) / 15.0;
        double local = h + ra - 0.06571 * t - 6.622;
        return ((local - lngHour) % 24 + 24) % 24;
    }

    /**
     * How strongly the night warmth applies at {@code now}, 0 by day to 1 at night, fading over
     * {@link #RAMP_MS} after sunset and before sunrise.
     */
    static float night(long now, TimeZone tz, double lat, double lon) {
        double[] rs = times(now, tz, lat, lon);
        if (Double.isNaN(rs[0]) || Double.isNaN(rs[1])) {
            // No crossing today: night all day in winter at high latitude, day in summer.
            boolean winter = (lat >= 0) == isNorthernWinter(now, tz);
            return winter ? 1f : 0f;
        }
        if (now < rs[0]) return clamp((rs[0] - now) / RAMP_MS);
        if (now > rs[1]) return clamp((now - rs[1]) / RAMP_MS);
        return 0f;
    }

    /**
     * Blends between {@code day} and {@code night} brightness levels (0-255) using
     * {@code nightFactor} (0.0 by day to 1.0 at night).
     */
    static int blend(int day, int night, float nightFactor) {
        float f = Math.max(0f, Math.min(1f, nightFactor));
        return Math.round(day + (night - day) * f);
    }

    private static boolean isNorthernWinter(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        int month = c.get(Calendar.MONTH);
        return month <= Calendar.MARCH || month >= Calendar.SEPTEMBER;
    }

    private static float clamp(double v) {
        return (float) Math.max(0, Math.min(1, v));
    }

    private static double norm360(double d) {
        return (d % 360 + 360) % 360;
    }

    private static double sin(double deg) {
        return Math.sin(Math.toRadians(deg));
    }

    private static double cos(double deg) {
        return Math.cos(Math.toRadians(deg));
    }

    private static double tan(double deg) {
        return Math.tan(Math.toRadians(deg));
    }
}
