package tv.dumbtv.quick;

import android.animation.TimeInterpolator;
import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The quick settings panel: slides in from the right over whatever is on screen. Rows take
 * left/right to adjust and OK to act; BACK or Menu slides it away. dumbtv home opens it from its
 * Settings chip, and with Menu on an HDMI input it shows (see Quick.java in the launcher).
 */
public final class Panel extends Activity {
    static final String ACCENT = "accent";
    private static final int DEFAULT_ACCENT = 0xFF3D8BFF;
    // dumbtv home's palette (launcher/src/tv/dumbtv/home/Ui.java).
    private static final int TEXT = 0xFFF2F2F3, MUTED = 0x99FFFFFF, IDLE = 0x14FFFFFF;
    private static final int SURFACE = 0xFF1C1D21;
    private static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final int WIDTH_DP = 330;
    private static final long TICK_MS = 15000;
    private static final int BRIGHTNESS_STEP = 5, BRIGHTNESS_MIN = 5;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Row> rows = new ArrayList<>();
    private int accent;
    private boolean fromHome, closing;
    private View scrim;
    private LinearLayout sheet;
    private Row brightness, bluetooth, warmth, sleep;
    private TextView clock, date, ram, video, videoSub;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            ui.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        overridePendingTransition(0, 0);
        SharedPreferences p = Tint.prefs(this);
        accent = getIntent().getIntExtra(ACCENT, p.getInt(ACCENT, DEFAULT_ACCENT)) | 0xFF000000;
        p.edit().putInt(ACCENT, accent).apply();
        fromHome = "home".equals(getIntent().getStringExtra("from"));
        Tint.refresh(this);
        build();
        slide(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, 0);
    }

    // ---- layout ----

    private void build() {
        FrameLayout root = new FrameLayout(this);
        scrim = new View(this);
        scrim.setBackgroundColor(0x8C000000);
        scrim.setOnClickListener(v -> close());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(24), dp(28), dp(24), dp(24));
        // A little of the accent in the surface, so the panel reads apart from a bright picture.
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(blend(SURFACE, accent, 0.14f));
        float r = dp(26);
        bg.setCornerRadii(new float[] {r, r, 0, 0, 0, 0, r, r});
        bg.setStroke(dp(1), (accent & 0x00FFFFFF) | 0x59000000);
        sheet.setBackground(bg);
        sheet.setElevation(dp(16));

        TextView title = text(22, TEXT, LIGHT);
        title.setText(R.string.title);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.bottomMargin = dp(18);
        tl.leftMargin = dp(4);
        sheet.addView(title, tl);

        brightness = row(R.drawable.ic_brightness, R.string.brightness, true);
        brightness.adjust = this::stepBrightness;
        bluetooth = row(R.drawable.ic_bluetooth, R.string.bluetooth, false);
        bluetooth.value.setText("›");
        bluetooth.press = this::openBluetooth;
        warmth = row(R.drawable.ic_warmth, R.string.warmth, true);
        warmth.bar.setGradient(0xFFCFE3FF, 0xFFFFD08A, 0xFFFF8A1F);
        warmth.adjust = this::stepWarmth;
        warmth.press = this::toggleAuto;
        sleep = row(R.drawable.ic_sleep, R.string.sleep, false);
        sleep.adjust = this::stepSleep;

        // Room for rows added later; the status strip stays at the bottom.
        sheet.addView(new View(this), new LinearLayout.LayoutParams(-1, 0, 1f));
        sheet.addView(footer(), new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(dp(WIDTH_DP), -1, Gravity.END);
        root.addView(sheet, sl);
        setContentView(root);
        for (Row row : rows) row.look(false);
        brightness.requestFocus();
    }

    private LinearLayout footer() {
        LinearLayout f = new LinearLayout(this);
        f.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] when = cell(f, 0);
        clock = when[0];
        date = when[1];
        TextView[] mem = cell(f, dp(8));
        ram = mem[0];
        mem[1].setText(R.string.ram);
        TextView[] vid = cell(f, dp(8));
        video = vid[0];
        videoSub = vid[1];
        return f;
    }

    /** A status cell: a big line over a small one. */
    private TextView[] cell(LinearLayout parent, int gap) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(6), dp(12), dp(6), dp(12));
        c.setBackground(round(IDLE, dp(14)));
        TextView big = text(17, TEXT, MEDIUM);
        big.setSingleLine();
        big.setEllipsize(TextUtils.TruncateAt.END);
        TextView small = text(11, MUTED, Typeface.DEFAULT);
        small.setSingleLine();
        small.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(big, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = dp(5);
        c.addView(small, sl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = gap;
        parent.addView(c, lp);
        return new TextView[] {big, small};
    }

    private Row row(int icon, int label, boolean withBar) {
        Row r = new Row(icon, label, withBar);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        sheet.addView(r, lp);
        rows.add(r);
        return r;
    }

    /** One setting: icon, name and value, an optional bar, and a line of status under them. */
    private final class Row extends LinearLayout {
        final ImageView icon = new ImageView(Panel.this);
        final TextView label = text(15, TEXT, MEDIUM);
        final TextView value = text(15, TEXT, MEDIUM);
        final TextView sub = text(12, MUTED, Typeface.DEFAULT);
        final Bar bar;
        Adjust adjust;
        Runnable press;

        Row(int iconRes, int labelRes, boolean withBar) {
            super(Panel.this);
            setOrientation(VERTICAL);
            setFocusable(true);
            setPadding(dp(16), dp(13), dp(16), dp(13));
            LinearLayout head = new LinearLayout(Panel.this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            icon.setImageResource(iconRes);
            head.addView(icon, new LayoutParams(dp(20), dp(20)));
            label.setText(labelRes);
            LayoutParams ll = new LayoutParams(0, -2, 1f);
            ll.leftMargin = dp(12);
            head.addView(label, ll);
            head.addView(value, new LayoutParams(-2, -2));
            addView(head, new LayoutParams(-1, -2));
            if (withBar) {
                bar = new Bar(Panel.this);
                LayoutParams bl = new LayoutParams(-1, dp(6));
                bl.topMargin = dp(11);
                addView(bar, bl);
            } else {
                bar = null;
            }
            sub.setSingleLine();
            sub.setEllipsize(TextUtils.TruncateAt.END);
            LayoutParams sl = new LayoutParams(-1, -2);
            sl.topMargin = dp(7);
            sl.leftMargin = dp(32);
            addView(sub, sl);
            setOnFocusChangeListener((v, has) -> look(has));
            setOnClickListener(v -> {
                if (press != null) press.run();
            });
        }

        /** dumbtv home's chip look: the accent fill and ink text when focused, a faint fill otherwise. */
        void look(boolean focused) {
            int ink = ink(accent);
            setBackground(round(focused ? accent : IDLE, dp(16)));
            label.setTextColor(focused ? ink : TEXT);
            value.setTextColor(focused ? ink : TEXT);
            sub.setTextColor(focused ? (ink & 0x00FFFFFF) | 0xB3000000 : MUTED);
            icon.setImageTintList(ColorStateList.valueOf(focused ? ink : accent));
            if (bar != null) {
                bar.setColors(focused ? (ink & 0x00FFFFFF) | 0x40000000 : 0x26FFFFFF, focused ? ink : accent);
            }
        }
    }

    private interface Adjust {
        void by(int direction);
    }

    // ---- keys ----

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MENU:
                if (down && e.getRepeatCount() == 0) close();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                // Left and right belong to the row; focus only moves up and down.
                Row r = focusedRow();
                if (down && r != null && r.adjust != null) {
                    r.adjust.by(code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                }
                return true;
            }
            default:
                return super.dispatchKeyEvent(e);
        }
    }

    private Row focusedRow() {
        View f = getCurrentFocus();
        return f instanceof Row ? (Row) f : null;
    }

    private void slide(boolean in) {
        float w = dp(WIDTH_DP);
        TimeInterpolator ease = in ? new DecelerateInterpolator(2f) : new AccelerateInterpolator(1.5f);
        if (in) {
            sheet.setTranslationX(w);
            scrim.setAlpha(0f);
        }
        scrim.animate().alpha(in ? 1f : 0f).setDuration(in ? 240 : 180).setInterpolator(ease).start();
        sheet.animate().translationX(in ? 0 : w).setDuration(in ? 240 : 180).setInterpolator(ease)
                .withEndAction(in ? null : this::finish).start();
    }

    private void close() {
        if (closing) return;
        closing = true;
        slide(false);
    }

    // ---- rows ----

    private void refresh() {
        showBrightness();
        showBluetooth();
        showWarmth();
        showSleep();
        showStatus();
    }

    private int brightnessPercent() {
        return Math.round(Backlight.get(this) * 100f / 255f);
    }

    private void showBrightness() {
        int p = brightnessPercent();
        brightness.value.setText(getString(R.string.percent, p));
        brightness.bar.setValue(p / 100f);
        boolean can = Settings.System.canWrite(this);
        brightness.sub.setText(can ? getString(R.string.brightness_hint) : getString(R.string.needs_install));
    }

    private void stepBrightness(int dir) {
        if (!Settings.System.canWrite(this)) {
            showBrightness();
            return;
        }
        int p = Math.round(brightnessPercent() / (float) BRIGHTNESS_STEP) * BRIGHTNESS_STEP + dir * BRIGHTNESS_STEP;
        p = Math.max(BRIGHTNESS_MIN, Math.min(100, p));
        Backlight.set(this, Math.round(p * 255f / 100f));
        showBrightness();
    }

    /** Bluetooth at a glance: the audio device playing, if any. OK opens the TV's Bluetooth screen. */
    private void showBluetooth() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) {
            bluetooth.sub.setText(R.string.bt_none);
            return;
        }
        if (!a.isEnabled()) {
            bluetooth.sub.setText(R.string.bt_off);
            return;
        }
        boolean asked = a.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
            @Override
            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                List<BluetoothDevice> on = proxy.getConnectedDevices();
                StringBuilder names = new StringBuilder();
                for (BluetoothDevice d : on) {
                    if (names.length() > 0) names.append(", ");
                    names.append(d.getName() != null ? d.getName() : d.getAddress());
                }
                a.closeProfileProxy(profile, proxy);
                bluetooth.sub.setText(names.length() > 0 ? names : getString(R.string.bt_no_audio));
            }

            @Override
            public void onServiceDisconnected(int profile) {
            }
        }, BluetoothProfile.A2DP);
        if (!asked) bluetooth.sub.setText(R.string.bt_no_audio);
    }

    private void openBluetooth() {
        // Amazon's "Controllers & Bluetooth Devices"; elsewhere Android's own Bluetooth settings.
        Intent[] tries = {
            new Intent("com.amazon.device.settings.action.CONTROLLERS"),
            new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
        };
        for (Intent i : tries) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // try the next
            }
        }
    }

    private void showWarmth() {
        SharedPreferences p = Tint.prefs(this);
        int level = p.getInt(Tint.WARMTH, 0);
        boolean auto = p.getBoolean(Tint.AUTO, false);
        warmth.value.setText(level == 0 ? getString(R.string.off) : getString(R.string.percent, level * 100 / Tint.STEPS));
        warmth.bar.setValue(level / (float) Tint.STEPS);
        String sub;
        if (!Settings.canDrawOverlays(this)) {
            sub = getString(R.string.needs_install);
        } else if (auto) {
            long now = System.currentTimeMillis();
            TimeZone tz = TimeZone.getDefault();
            double[] at = Sun.place(tz);
            double[] today = Sun.times(now, tz, at[0], at[1]);
            boolean night = Sun.night(now, tz, at[0], at[1]) > 0;
            if (Double.isNaN(today[0])) {
                sub = getString(R.string.warmth_auto);
            } else if (night) {
                double rise = now < today[0] ? today[0] : Sun.times(now + 86400000L, tz, at[0], at[1])[0];
                sub = getString(R.string.warmth_auto_until, hm((long) rise));
            } else {
                sub = getString(R.string.warmth_auto_from, hm((long) today[1]));
            }
        } else {
            sub = getString(level == 0 ? R.string.warmth_hint : R.string.warmth_always);
        }
        warmth.sub.setText(sub);
    }

    private void stepWarmth(int dir) {
        SharedPreferences p = Tint.prefs(this);
        int level = Math.max(0, Math.min(Tint.STEPS, p.getInt(Tint.WARMTH, 0) + dir));
        p.edit().putInt(Tint.WARMTH, level).commit();
        Tint.refresh(this);
        showWarmth();
    }

    /** OK on warmth: follow sunset, or stay on. Turning auto on with warmth off picks a middle level. */
    private void toggleAuto() {
        SharedPreferences p = Tint.prefs(this);
        boolean auto = !p.getBoolean(Tint.AUTO, false);
        SharedPreferences.Editor e = p.edit().putBoolean(Tint.AUTO, auto);
        if (auto && p.getInt(Tint.WARMTH, 0) == 0) e.putInt(Tint.WARMTH, Tint.STEPS / 2);
        e.commit();
        Tint.refresh(this);
        showWarmth();
    }

    private void showSleep() {
        int left = Sleep.minutesLeft(this);
        sleep.value.setText(left == 0 ? getString(R.string.off) : getString(R.string.minutes, left));
        if (!Sleep.canSleep(this)) sleep.sub.setText(R.string.needs_install);
        else if (left == 0) sleep.sub.setText(R.string.sleep_hint);
        else sleep.sub.setText(getString(R.string.sleep_at, hm(Sleep.at(this))));
    }

    private void stepSleep(int dir) {
        Sleep.set(this, Sleep.step(Sleep.minutesLeft(this), dir));
        showSleep();
    }

    // ---- status strip ----

    private void showStatus() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEE MM-dd", Locale.getDefault()).format(now));

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        ram.setText(String.format(Locale.US, "%.1f/%.1f", (mi.totalMem - mi.availMem) / 1e9, mi.totalMem / 1e9));

        // Over the home screen no input is showing (its only video is the home background), so
        // only an HDMI input's own stream is reported, and only with the panel open over it.
        Video.Format f = fromHome ? null : Tint.video.current(true);
        if (f != null) {
            video.setText(f.resolution());
            videoSub.setText(getString(f.srcType == Video.SRC_HDMI ? R.string.video_hdmi : R.string.video_app, f.range()));
        } else {
            // Nothing playing (or no log access): the panel's own mode, which is what is on screen.
            Display.Mode m = getWindowManager().getDefaultDisplay().getMode();
            int h = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
            video.setText((h >= 2100 ? "4K" : h + "p") + Math.round(m.getRefreshRate()));
            videoSub.setText(R.string.video_panel);
        }
    }

    // ---- helpers ----

    private String hm(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    private TextView text(float sp, int color, Typeface face) {
        TextView v = new TextView(this);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        return v;
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Black on light accents, white on dark ones, as dumbtv home does. */
    private static int ink(int color) {
        return Color.luminance(color) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
    }

    private static int blend(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
