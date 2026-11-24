package tv.dumbtv.home;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Locale;

/**
 * The forecast behind the weather chip, laid out as Material 3 cards on a dark surface: now,
 * the next 12 hours, the next 7 days. The accent plays Material's primary color.
 */
public final class WeatherScreen extends Activity {
    private static final int BACKGROUND = 0xFF111216;
    private static final int ON_SURFACE = 0xFFE4E2E6;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout page;
    private int primary, card;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        primary = Config.get(this).accent();
        // Material's tonal surface: the primary laid at 8% over a dark container
        card = blend(0xFF1B1C20, primary, 0.08f);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding(dp(56), dp(36), dp(56), dp(28));
        page.setBackgroundColor(BACKGROUND);
        setContentView(page);
        show();
        Weather.refresh(Config.get(this), ui, this::show);
    }

    private void show() {
        page.removeAllViews();
        Config cfg = Config.get(this);
        JSONObject data = Weather.last();
        JSONObject now = data == null ? null : data.optJSONObject("current");
        if (now == null) {
            LinearLayout empty = card();
            empty.addView(text(cfg.get(Weather.LAT, null) == null
                    ? "Set weather.lat and weather.lon in home.conf" : "Loading the forecast…",
                    20, ON_SURFACE, false));
            page.addView(empty);
            return;
        }
        // 960x540 dp on a 1080p TV: now and the week side by side, the hours below
        LinearLayout top = new LinearLayout(this);
        LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.4f);
        left.rightMargin = dp(20);
        top.addView(now(cfg, data, now), left);
        top.addView(row("7-day forecast", days(data)),
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.6f));
        page.addView(top);
        page.addView(spaced(row("Hourly forecast", hours(data))));
    }

    /** The first card: place, a large icon, the temperature, the words and today's range. */
    private View now(Config cfg, JSONObject data, JSONObject now) {
        LinearLayout box = card();
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = icon(Weather.symbol(now.optInt("weather_code", -1), now.optInt("is_day", 1) == 1), 64);
        box.addView(icon);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(24), 0, 0, 0);
        words.addView(text(cfg.get(Weather.PLACE, "Weather"), 16, Ui.MUTED, false));
        TextView temp = text(Math.round(now.optDouble("temperature_2m")) + "°", 60, ON_SURFACE, true);
        temp.setPadding(0, dp(2), 0, dp(4));
        words.addView(temp);
        words.addView(text(Weather.describe(now.optInt("weather_code", -1)), 18, ON_SURFACE, false));
        JSONObject daily = data.optJSONObject("daily");
        JSONArray max = daily == null ? null : daily.optJSONArray("temperature_2m_max"),
                min = daily == null ? null : daily.optJSONArray("temperature_2m_min");
        if (max != null && min != null) {
            words.addView(text("High " + Math.round(max.optDouble(0)) + "°  Low "
                    + Math.round(min.optDouble(0)) + "°", 15, Ui.MUTED, false));
        }
        box.addView(words);
        return box;
    }

    private View row(String title, LinearLayout columns) {
        LinearLayout box = card();
        TextView t = text(title, 16, Ui.MUTED, false);
        t.setPadding(0, 0, 0, dp(14));
        box.addView(t);
        box.addView(columns);
        return box;
    }

    /** Twelve columns from the current hour on. */
    private LinearLayout hours(JSONObject data) {
        LinearLayout row = new LinearLayout(this);
        JSONObject hourly = data.optJSONObject("hourly");
        JSONArray time = hourly == null ? null : hourly.optJSONArray("time");
        if (time == null) return row;
        int start = Weather.firstHour(data);
        JSONArray temp = hourly.optJSONArray("temperature_2m"), code = hourly.optJSONArray("weather_code"),
                day = hourly.optJSONArray("is_day");
        for (int i = start; i < Math.min(start + 12, time.length()); i++) {
            String t = time.optString(i);
            row.addView(column(i == start ? "Now" : t.length() >= 16 ? t.substring(11, 16) : t,
                    Weather.symbol(code == null ? -1 : code.optInt(i, -1), day == null || day.optInt(i, 1) == 1),
                    temp == null ? "" : Math.round(temp.optDouble(i)) + "°", null),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private LinearLayout days(JSONObject data) {
        LinearLayout row = new LinearLayout(this);
        JSONObject daily = data.optJSONObject("daily");
        JSONArray time = daily == null ? null : daily.optJSONArray("time");
        if (time == null) return row;
        JSONArray code = daily.optJSONArray("weather_code"), max = daily.optJSONArray("temperature_2m_max"),
                min = daily.optJSONArray("temperature_2m_min");
        SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.getDefault());
        for (int i = 0; i < time.length(); i++) {
            String name;
            try {
                name = i == 0 ? "Today" : out.format(in.parse(time.optString(i)));
            } catch (ParseException e) {
                name = time.optString(i);
            }
            row.addView(column(name, Weather.symbol(code == null ? -1 : code.optInt(i, -1), true),
                    max == null ? "" : Math.round(max.optDouble(i)) + "°",
                    min == null ? null : Math.round(min.optDouble(i)) + "°"),
                    new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        }
        return row;
    }

    private LinearLayout column(String top, String symbol, String value, String low) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.addView(text(top, 15, Ui.MUTED, false));
        TextView i = icon(symbol, 30);
        i.setPadding(0, dp(8), 0, dp(6));
        col.addView(i);
        col.addView(text(value, 20, ON_SURFACE, false));
        if (low != null) col.addView(text(low, 16, Ui.MUTED, false));
        return col;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(28), dp(20), dp(28), dp(20));
        box.setBackground(Ui.round(card, dp(28)));
        return box;
    }

    private View spaced(View v) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView icon(String symbol, float sp) {
        TextView v = Ui.text(this, sp, primary, Ui.icons(this));
        v.setText(symbol);
        return v;
    }

    private TextView text(String s, float sp, int color, boolean display) {
        TextView v = Ui.text(this, sp, color, display ? Ui.serif(this) : Ui.medium(this));
        v.setText(s);
        return v;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private static int blend(int base, int over, float amount) {
        return Color.rgb(
                Math.round(Color.red(base) + (Color.red(over) - Color.red(base)) * amount),
                Math.round(Color.green(base) + (Color.green(over) - Color.green(base)) * amount),
                Math.round(Color.blue(base) + (Color.blue(over) - Color.blue(base)) * amount));
    }
}
