package tv.dumbtv.quick;

import android.animation.TimeInterpolator;
import android.app.Activity;
import android.app.ActivityManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
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
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * The quick settings panel: slides in from the right over whatever is on screen. Rows take
 * left/right to adjust and OK to act; BACK or Menu slides it away.
 */
public final class Panel extends Activity {
    static final String ACCENT = "accent";
    private static final int DEFAULT_ACCENT = 0xFF3D8BFF;
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
    private LinearLayout sheet, rowContainer;
    private ScrollView scroller;
    private Row brightness, bluetooth, airplay, pictureOff, network, health, idlePause, warmth, sleep, noise;
    private TextView clock, date, ram, video, videoSub;

    // Bluetooth
    private BluetoothA2dp a2dp;
    private final List<BluetoothDevice> pairedDevices = new ArrayList<>();
    private int btTargetIndex = 0; // 0..pairedDevices.size() - 1: devices; pairedDevices.size(): Settings
    private final BluetoothProfile.ServiceListener btListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = (BluetoothA2dp) proxy;
                refreshBluetoothDevices();
                showBluetooth();
            }
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) {
                a2dp = null;
            }
        }
    };

    // Health check
    private List<String> healthIssues = new ArrayList<>();
    private int healthDetailIndex = 0;

    // Idle pause (Chromecast auto-switch)
    private int idlePauseMode = 0; // 0: active ("after 4 min"), 1: paused until morning, 2: off
    private int idleMinutes = 4;
    private String idlePauseResult;

    // Noise meter
    private final Noise noiseSampler = new Noise();

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
        idleMinutes = getIntent().getIntExtra("idle_minutes", 4);
        initIdlePauseMode();

        Tint.refresh(this);
        initBluetooth();
        build();
        slide(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshBluetoothDevices();
        refreshHealth();
        ui.post(tick);
        if (noise != null) {
            noiseSampler.start(this, (fraction, db) -> {
                if (noise != null) {
                    noise.bar.setValue(fraction);
                    noise.value.setText(getString(R.string.noise_db, db));
                }
            });
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(tick);
        noiseSampler.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (a2dp != null) {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null) a.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
        }
        super.onDestroy();
    }

    @Override
    public void finish() {
        if (fromHome && idlePauseResult != null) {
            Intent res = new Intent();
            res.putExtra("idle_pause", idlePauseResult);
            setResult(RESULT_OK, res);
        }
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
        sheet.setPadding(dp(24), dp(24), dp(24), dp(20));
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
        tl.bottomMargin = dp(14);
        tl.leftMargin = dp(4);
        sheet.addView(title, tl);

        scroller = new ScrollView(this);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rowContainer = new LinearLayout(this);
        rowContainer.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(rowContainer, new ScrollView.LayoutParams(-1, -2));
        sheet.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 1. Brightness with Dim after sunset
        brightness = row(R.drawable.ic_brightness, R.string.brightness, true);
        brightness.adjust = this::stepBrightness;
        brightness.press = this::toggleBrightnessAuto;

        // 2. Bluetooth reworked
        bluetooth = row(R.drawable.ic_bluetooth, R.string.bluetooth, false);
        bluetooth.adjust = this::stepBluetooth;
        bluetooth.press = this::pressBluetooth;

        // 3. AirPlay status + Make room
        airplay = row(R.drawable.ic_airplay, R.string.airplay, false);
        airplay.press = this::makeRoom;

        // 4. Picture off
        pictureOff = row(R.drawable.ic_picture_off, R.string.picture_off, false);
        pictureOff.press = this::triggerPictureOff;

        // 5. Filter + Wi-Fi status
        network = row(R.drawable.ic_wifi, R.string.network, false);
        network.press = this::restartFilter;

        // 6. TV Health check
        health = row(R.drawable.ic_health, R.string.health, false);
        health.press = this::cycleHealthDetails;

        // 7. Pause Chromecast switch (home only)
        if (fromHome) {
            idlePause = row(R.drawable.ic_pause, R.string.idle_pause, false);
            idlePause.adjust = this::stepIdlePause;
        }

        // 8. Warmth
        warmth = row(R.drawable.ic_warmth, R.string.warmth, true);
        warmth.bar.setGradient(0xFFCFE3FF, 0xFFFFD08A, 0xFFFF8A1F);
        warmth.adjust = this::stepWarmth;
        warmth.press = this::toggleWarmthAuto;

        // 9. Sleep timer
        sleep = row(R.drawable.ic_sleep, R.string.sleep, false);
        sleep.adjust = this::stepSleep;

        // 10. Noise meter
        if (Noise.hasPermission(this)) {
            noise = row(R.drawable.ic_mic, R.string.noise, true);
            noise.bar.setGradient(0xFF4CAF50, 0xFFFFEB3B, 0xFFF44336);
        }

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

    private TextView[] cell(LinearLayout parent, int gap) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(6), dp(10), dp(6), dp(10));
        c.setBackground(round(IDLE, dp(14)));
        TextView big = text(16, TEXT, MEDIUM);
        big.setSingleLine();
        big.setEllipsize(TextUtils.TruncateAt.END);
        TextView small = text(11, MUTED, Typeface.DEFAULT);
        small.setSingleLine();
        small.setEllipsize(TextUtils.TruncateAt.END);
        c.addView(big, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-2, -2);
        sl.topMargin = dp(4);
        c.addView(small, sl);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
        lp.leftMargin = gap;
        parent.addView(c, lp);
        return new TextView[] {big, small};
    }

    private Row row(int icon, int label, boolean withBar) {
        Row r = new Row(icon, label, withBar);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(8);
        rowContainer.addView(r, lp);
        rows.add(r);
        return r;
    }

    private final class Row extends LinearLayout {
        final ImageView icon = new ImageView(Panel.this);
        final TextView label = text(15, TEXT, MEDIUM);
        final TextView value = text(14, TEXT, MEDIUM);
        final TextView sub = text(12, MUTED, Typeface.DEFAULT);
        final Bar bar;
        Adjust adjust;
        Runnable press;

        Row(int iconRes, int labelRes, boolean withBar) {
            super(Panel.this);
            setOrientation(VERTICAL);
            setFocusable(true);
            setPadding(dp(16), dp(12), dp(16), dp(12));
            LinearLayout head = new LinearLayout(Panel.this);
            head.setGravity(Gravity.CENTER_VERTICAL);
            icon.setImageResource(iconRes);
            head.addView(icon, new LayoutParams(dp(20), dp(20)));
            label.setText(labelRes);
            LayoutParams ll = new LayoutParams(0, -2, 1f);
            ll.leftMargin = dp(12);
            head.addView(label, ll);
            value.setSingleLine();
            value.setEllipsize(TextUtils.TruncateAt.END);
            head.addView(value, new LayoutParams(-2, -2));
            addView(head, new LayoutParams(-1, -2));
            if (withBar) {
                bar = new Bar(Panel.this);
                LayoutParams bl = new LayoutParams(-1, dp(6));
                bl.topMargin = dp(9);
                addView(bar, bl);
            } else {
                bar = null;
            }
            sub.setSingleLine();
            sub.setEllipsize(TextUtils.TruncateAt.END);
            LayoutParams sl = new LayoutParams(-1, -2);
            sl.topMargin = dp(6);
            sl.leftMargin = dp(32);
            addView(sub, sl);
            setOnFocusChangeListener((v, has) -> look(has));
            setOnClickListener(v -> {
                if (press != null) press.run();
            });
        }

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

    // ---- row logic ----

    private void refresh() {
        showBrightness();
        showBluetooth();
        showAirPlay();
        showPictureOff();
        showNetwork();
        showHealth();
        if (idlePause != null) showIdlePause();
        showWarmth();
        showSleep();
        showStatus();
    }

    // 1. Brightness
    private void showBrightness() {
        int p = Math.round(Backlight.get(this) * 100f / 255f);
        brightness.value.setText(getString(R.string.percent, p));
        brightness.bar.setValue(p / 100f);
        boolean can = Settings.System.canWrite(this);
        if (!can) {
            brightness.sub.setText(R.string.needs_install);
        } else if (Backlight.isAuto(this)) {
            int nightP = Math.round(Backlight.nightLevel(this) * 100f / 255f);
            brightness.sub.setText(getString(R.string.brightness_auto, nightP));
        } else {
            brightness.sub.setText(R.string.brightness_hint);
        }
    }

    private void stepBrightness(int dir) {
        if (!Settings.System.canWrite(this)) return;
        boolean atNight = Backlight.isAuto(this) && Backlight.isNight();
        int current = atNight ? Backlight.nightLevel(this) : Backlight.dayLevel(this);
        int p = Math.round(current * 100f / 255f / BRIGHTNESS_STEP) * BRIGHTNESS_STEP + dir * BRIGHTNESS_STEP;
        p = Math.max(BRIGHTNESS_MIN, Math.min(100, p));
        Backlight.set(this, Math.round(p * 255f / 100f));
        showBrightness();
    }

    private void toggleBrightnessAuto() {
        if (!Settings.System.canWrite(this)) return;
        Backlight.setAuto(this, !Backlight.isAuto(this));
        showBrightness();
    }

    // 2. Bluetooth reworked
    private void initBluetooth() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a != null && a.isEnabled()) {
            a.getProfileProxy(this, btListener, BluetoothProfile.A2DP);
        }
    }

    private void refreshBluetoothDevices() {
        pairedDevices.clear();
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a != null && a.isEnabled()) {
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded != null) pairedDevices.addAll(bonded);
        }
        if (btTargetIndex > pairedDevices.size()) btTargetIndex = pairedDevices.size();
    }

    private void showBluetooth() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a == null) {
            bluetooth.value.setText("");
            bluetooth.sub.setText(R.string.bt_none);
            return;
        }
        if (!a.isEnabled()) {
            bluetooth.value.setText(R.string.bt_off);
            bluetooth.sub.setText(R.string.bt_off);
            return;
        }
        if (btTargetIndex >= pairedDevices.size()) {
            bluetooth.value.setText(R.string.bt_settings);
            bluetooth.sub.setText(R.string.bt_settings_hint);
            return;
        }
        BluetoothDevice d = pairedDevices.get(btTargetIndex);
        String name = d.getName() != null ? d.getName() : d.getAddress();
        bluetooth.value.setText("‹ " + name + " ›");

        int state = a2dp != null ? a2dp.getConnectionState(d) : BluetoothProfile.STATE_DISCONNECTED;
        if (state == BluetoothProfile.STATE_CONNECTED) {
            int battery = getBatteryLevel(d);
            bluetooth.sub.setText(battery >= 0 ? getString(R.string.bt_connected_battery, battery) : getString(R.string.bt_connected));
        } else if (state == BluetoothProfile.STATE_CONNECTING) {
            bluetooth.sub.setText(R.string.bt_connecting);
        } else if (state == BluetoothProfile.STATE_DISCONNECTING) {
            bluetooth.sub.setText(R.string.bt_disconnecting);
        } else {
            bluetooth.sub.setText(R.string.bt_disconnected);
        }
    }

    private void stepBluetooth(int dir) {
        int total = pairedDevices.size() + 1; // devices + settings
        btTargetIndex = (btTargetIndex + dir + total) % total;
        showBluetooth();
    }

    private void pressBluetooth() {
        if (btTargetIndex >= pairedDevices.size()) {
            openBluetooth();
            return;
        }
        if (a2dp == null) return;
        BluetoothDevice d = pairedDevices.get(btTargetIndex);
        try {
            int state = a2dp.getConnectionState(d);
            if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_CONNECTING) {
                Method m = a2dp.getClass().getMethod("disconnect", BluetoothDevice.class);
                m.invoke(a2dp, d);
            } else {
                Method m = a2dp.getClass().getMethod("connect", BluetoothDevice.class);
                m.invoke(a2dp, d);
            }
        } catch (Exception e) {
            Log.w(Tint.TAG, "bluetooth reflection error", e);
        }
        ui.postDelayed(this::showBluetooth, 600);
    }

    private int getBatteryLevel(BluetoothDevice d) {
        try {
            Method m = d.getClass().getMethod("getBatteryLevel");
            Object r = m.invoke(d);
            if (r instanceof Integer) return (Integer) r;
        } catch (Exception ignored) {}
        return -1;
    }

    private void openBluetooth() {
        Intent[] tries = {
            new Intent("com.amazon.device.settings.action.CONTROLLERS"),
            new Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
        };
        for (Intent i : tries) {
            try {
                startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {}
        }
    }

    // 3. AirPlay
    private void showAirPlay() {
        int state = Tint.getAirPlayState();
        if (state == Tint.AIRPLAY_IN_USE) {
            airplay.value.setText(R.string.airplay_in_use);
            airplay.sub.setText("OK: Make room");
        } else if (state == Tint.AIRPLAY_BROKEN) {
            airplay.value.setText(R.string.off);
            airplay.sub.setText(R.string.airplay_broken);
        } else {
            airplay.value.setText(R.string.airplay_ready);
            airplay.sub.setText("OK: Make room");
        }
    }

    private void makeRoom() {
        Tint.makeRoom(this);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        String free = String.format(Locale.US, "%.1f GB", mi.availMem / 1e9);
        airplay.sub.setText(getString(R.string.airplay_room_made, free));
    }

    // 4. Picture off
    private void showPictureOff() {
        pictureOff.value.setText("›");
        pictureOff.sub.setText(R.string.picture_off_hint);
    }

    private void triggerPictureOff() {
        Tint.setPictureOff(this, true);
        close();
    }

    // 5. Network (Filter + Wi-Fi)
    private void showNetwork() {
        network.value.setText("›");
        network.sub.setText(Network.glance(this));
    }

    private void restartFilter() {
        Network.restartFilter(this);
        ui.postDelayed(this::showNetwork, 1000);
    }

    // 6. TV Health
    private void refreshHealth() {
        File conf = new File(Health.CONF_FILE);
        if (!conf.exists()) {
            healthIssues.clear();
            healthIssues.add(getString(R.string.health_no_conf));
            return;
        }
        Health.Expected exp = Health.loadExpected(conf);
        if (exp == null) {
            healthIssues.clear();
            healthIssues.add("Could not parse expected.conf");
            return;
        }
        Health.Actual act = new Health.Actual();
        act.build = Build.DISPLAY != null ? Build.DISPLAY : Build.ID;
        act.accessibility = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        PackageManager pm = getPackageManager();
        for (String pkg : exp.disabledPackages) {
            try {
                ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                if (info.enabled) act.enabledPackages.add(pkg);
                else act.disabledPackages.add(pkg);
            } catch (PackageManager.NameNotFoundException e) {
                act.disabledPackages.add(pkg);
            }
        }

        for (String key : exp.settings.keySet()) {
            String val = readSetting(key);
            if (val != null) act.settings.put(key, val);
        }

        healthIssues = Health.check(exp, act);
    }

    private String readSetting(String key) {
        String ns = "secure";
        String name = key;
        int sep = key.indexOf(':') >= 0 ? key.indexOf(':') : key.indexOf('.');
        if (sep > 0) {
            ns = key.substring(0, sep).toLowerCase();
            name = key.substring(sep + 1);
        }
        switch (ns) {
            case "global": return Settings.Global.getString(getContentResolver(), name);
            case "system": return Settings.System.getString(getContentResolver(), name);
            case "secure":
            default: return Settings.Secure.getString(getContentResolver(), name);
        }
    }

    private void showHealth() {
        if (healthIssues.isEmpty()) {
            health.value.setText("OK");
            health.sub.setText(R.string.health_ok);
        } else {
            health.value.setText("!");
            int idx = healthDetailIndex % healthIssues.size();
            health.sub.setText(healthIssues.get(idx));
        }
    }

    private void cycleHealthDetails() {
        if (!healthIssues.isEmpty()) {
            healthDetailIndex = (healthDetailIndex + 1) % healthIssues.size();
            showHealth();
        }
    }

    // 7. Idle Pause (Home only)
    private void initIdlePauseMode() {
        String p = getIntent().getStringExtra("idle_pause");
        if ("off".equalsIgnoreCase(p)) {
            idlePauseMode = 2;
        } else if (p != null && !p.isEmpty() && !"0".equals(p)) {
            try {
                long until = Long.parseLong(p);
                idlePauseMode = System.currentTimeMillis() < until ? 1 : 0;
            } catch (NumberFormatException ignored) {
                idlePauseMode = 0;
            }
        } else {
            idlePauseMode = 0;
        }
        updateIdlePauseResult();
    }

    private void stepIdlePause(int dir) {
        idlePauseMode = (idlePauseMode + dir + 3) % 3;
        updateIdlePauseResult();
        showIdlePause();
    }

    private void updateIdlePauseResult() {
        if (idlePauseMode == 0) {
            idlePauseResult = "0";
        } else if (idlePauseMode == 1) {
            Calendar c = Calendar.getInstance();
            c.add(Calendar.DAY_OF_YEAR, 1);
            c.set(Calendar.HOUR_OF_DAY, 6);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            idlePauseResult = String.valueOf(c.getTimeInMillis());
        } else {
            idlePauseResult = "off";
        }
    }

    private void showIdlePause() {
        if (idlePauseMode == 0) {
            idlePause.value.setText(getString(R.string.idle_pause_active, idleMinutes));
            idlePause.sub.setText("Switches to Chromecast when idle");
        } else if (idlePauseMode == 1) {
            idlePause.value.setText(R.string.idle_pause_morning);
            idlePause.sub.setText("Paused until 06:00 tomorrow");
        } else {
            idlePause.value.setText(R.string.idle_pause_off);
            idlePause.sub.setText("Chromecast auto-switch disabled");
        }
    }

    // 8. Warmth
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

    private void toggleWarmthAuto() {
        SharedPreferences p = Tint.prefs(this);
        boolean auto = !p.getBoolean(Tint.AUTO, false);
        SharedPreferences.Editor e = p.edit().putBoolean(Tint.AUTO, auto);
        if (auto && p.getInt(Tint.WARMTH, 0) == 0) e.putInt(Tint.WARMTH, Tint.STEPS / 2);
        e.commit();
        Tint.refresh(this);
        showWarmth();
    }

    // 9. Sleep
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

        Video.Format f = fromHome ? null : Tint.video.current(true);
        if (f != null) {
            video.setText(f.resolution());
            videoSub.setText(getString(f.srcType == Video.SRC_HDMI ? R.string.video_hdmi : R.string.video_app, f.range()));
        } else {
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
