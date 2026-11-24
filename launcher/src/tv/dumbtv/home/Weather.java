package tv.dumbtv.home;

import android.content.Context;
import android.os.Handler;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Weather for the place in home.conf (weather.lat, weather.lon, weather.place), from Open-Meteo.
 * The only network use in the app: no place set, no request. Only the coordinates leave the TV.
 */
final class Weather {
    static final String LAT = "weather.lat", LON = "weather.lon", PLACE = "weather.place";
    private static final long FRESH = 30 * 60_000L, RETRY = 5 * 60_000L;
    private static final ExecutorService net = Executors.newSingleThreadExecutor();
    private static volatile JSONObject last;
    private static volatile long nextFetch;
    private static volatile String fetchedFor;

    private Weather() {}

    /** The last forecast, or null before the first answer or with no place set. */
    static JSONObject last() {
        return last;
    }

    /** Fetches when the forecast is missing or old, then runs {@code done} on {@code ui}. */
    static void refresh(Config cfg, Handler ui, Runnable done) {
        String url = url(cfg.get(LAT, null), cfg.get(LON, null));
        if (url == null) {
            if (last != null) {
                last = null;
                ui.post(done);
            }
            fetchedFor = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (url.equals(fetchedFor) && now < nextFetch) return;
        fetchedFor = url;
        nextFetch = now + RETRY;
        net.execute(() -> {
            JSONObject data = fetch(url);
            if (data == null) return;
            last = data;
            nextFetch = System.currentTimeMillis() + FRESH;
            ui.post(done);
        });
    }

    /** The request address, or null unless both are numbers in range. */
    static String url(String lat, String lon) {
        if (lat == null || lon == null) return null;
        double la, lo;
        try {
            la = Double.parseDouble(lat);
            lo = Double.parseDouble(lon);
        } catch (NumberFormatException e) {
            return null;
        }
        if (!(Math.abs(la) <= 90 && Math.abs(lo) <= 180)) return null;
        return String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.4f"
                + "&longitude=%.4f&current=temperature_2m,weather_code,is_day"
                + "&hourly=temperature_2m,weather_code,is_day"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=7", la, lo);
    }

    private static JSONObject fetch(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) {
                    body.write(buf, 0, n);
                    if (body.size() > 512 * 1024) return null;
                }
            }
            JSONObject data = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
            data.getJSONObject("current").getDouble("temperature_2m");
            return data;
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** The icon and "9°" for the clock, or null when the forecast lacks the current values. */
    static CharSequence brief(Context c, JSONObject data) {
        JSONObject now = data == null ? null : data.optJSONObject("current");
        if (now == null || !now.has("temperature_2m")) return null;
        SpannableStringBuilder s = new SpannableStringBuilder(
                symbol(now.optInt("weather_code", -1), now.optInt("is_day", 1) == 1));
        s.setSpan(new Ui.FontSpan(Ui.icons(c)), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new RelativeSizeSpan(1.3f), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s.append("  ").append(Math.round(now.optDouble("temperature_2m")) + "°");
    }

    /** Index of the current hour in the hourly arrays; they share the current time's zone. */
    static int firstHour(JSONObject data) {
        JSONObject now = data == null ? null : data.optJSONObject("current");
        JSONObject hourly = data == null ? null : data.optJSONObject("hourly");
        JSONArray time = hourly == null ? null : hourly.optJSONArray("time");
        if (now == null || time == null) return 0;
        String t = now.optString("time", "");
        String hour = t.length() >= 13 ? t.substring(0, 13) : t;
        int i = 0;
        while (i < time.length() && time.optString(i).compareTo(hour) < 0) i++;
        return i;
    }

    /** WMO weather interpretation codes, as Open-Meteo documents them. */
    static String describe(int code) {
        switch (code) {
            case 0: return "Clear";
            case 1: return "Mainly clear";
            case 2: return "Partly cloudy";
            case 3: return "Overcast";
            case 45: case 48: return "Fog";
            case 51: case 53: case 55: return "Drizzle";
            case 56: case 57: return "Freezing drizzle";
            case 61: case 63: case 65: return "Rain";
            case 66: case 67: return "Freezing rain";
            case 71: case 73: case 75: case 77: return "Snow";
            case 80: case 81: case 82: return "Showers";
            case 85: case 86: return "Snow showers";
            case 95: case 96: case 99: return "Thunderstorm";
            default: return "";
        }
    }

    /**
     * A Material Symbols Rounded glyph, drawn with {@link Ui#icons}. The font in assets holds
     * only these code points; adding one here means adding it to the subset too.
     */
    static String symbol(int code, boolean day) {
        if (code == 0 || code == 1) return day ? "" : ""; // sunny, clear_night
        if (code == 2) return day ? "" : ""; // partly_cloudy_day, partly_cloudy_night
        if (code == 3) return ""; // cloud
        if (code == 45 || code == 48) return ""; // foggy
        if (code >= 51 && code <= 57) return ""; // rainy_light
        if (code >= 61 && code <= 67 || code >= 80 && code <= 82) return ""; // rainy
        if (code >= 71 && code <= 77 || code == 85 || code == 86) return ""; // weather_snowy
        if (code >= 95) return ""; // thunderstorm
        return ""; // device_thermostat
    }
}
