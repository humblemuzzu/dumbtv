package tv.dumbtv.quick;

import android.animation.TimeInterpolator;
import android.app.ActivityManager;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
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
 * The quick settings panel: held in memory by {@link Tint} and attached as an in-process
 * WindowManager overlay (TYPE_APPLICATION_OVERLAY) when opened. This eliminates activity
 * launch latency (opening in 2-5 ms), prevents TV inputs from pausing or dropping HDMI/HDCP,
 * and remains immune to activity-stack interruptions.
 */
public final class Panel {
    static final String ACCENT = "accent";
    private static final int DEFAULT_ACCENT = 0xFF3D8BFF;
    private static final int TEXT = 0xFFF2F2F3, MUTED = 0x99FFFFFF, IDLE = 0x14FFFFFF;
    private static final int SURFACE = 0xFF1C1D21;
    private static final Typeface LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private static final int WIDTH_DP = 330;
    private static final long TICK_MS = 15000;
    private static final int BRIGHTNESS_STEP = 5, BRIGHTNESS_MIN = 5;
    private static final long HEALTH_CACHE_TTL_MS = 60 * 1000L;
    private static final int TYPE_APPLICATION_OVERLAY = 2038;

    private static volatile Panel activeInstance;
    private static volatile List<String> cachedHealthIssues;
    private static volatile long lastHealthCheck;

    boolean isShowing() {
        return showing && !closing;
    }

    static boolean isActiveShowing() {
        Panel p = activeInstance;
        return p != null && p.isShowing();
    }

    static void closeActive() {
        Panel p = activeInstance;
        if (p != null) p.close();
    }

    private final Context context;
    private final Handler ui;
    private final Video video;
    private final WindowManager wm;
    private final List<Row> rows = new ArrayList<>();

    private FrameLayout root;
    private WindowManager.LayoutParams lp;
    private View scrim;
    private LinearLayout sheet, rowContainer;
    private ScrollView scroller;
    private Row brightness, bluetooth, airplay, pictureOff, network, health, idlePause, warmth, sleep, noise;
    private TextView clock, date, ram, videoText, videoSub;

    private int accent = DEFAULT_ACCENT;
    private boolean fromHome, showing, closing;
    private long ignoreBackUntil;
    private long lastBroughtToTopAt;
    private View activeShield;
    private Bitmap activeShieldBmp;

    // Bluetooth
    private BluetoothA2dp a2dp;
    private final List<BluetoothDevice> pairedDevices = new ArrayList<>();
    private int btTargetIndex = 0;
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
    private int idleMinutes = 0;
    private String idlePauseResult;

    // Noise meter
    private final Noise noiseSampler = new Noise();

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!showing || closing) return;
            refresh();
            ui.postDelayed(this, TICK_MS);
        }
    };

    public Panel(Context context, Handler ui, Video video) {
        this.context = context;
        this.ui = ui;
        this.video = video;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        SharedPreferences p = Tint.prefs(context);
        this.accent = p.getInt(ACCENT, DEFAULT_ACCENT) | 0xFF000000;
        build();
    }

    public void ignoreNextBack() {
        ignoreBackUntil = SystemClock.uptimeMillis() + 600;
    }

    public void show(Intent intent) {
        SharedPreferences p = Tint.prefs(context);
        int acc = p.getInt(ACCENT, DEFAULT_ACCENT);
        String from = null;
        String idlePause = null;
        int idleMin = 0;
        if (intent != null) {
            acc = intent.getIntExtra(ACCENT, acc);
            from = intent.getStringExtra("from");
            idlePause = intent.getStringExtra("idle_pause");
            idleMin = intent.getIntExtra("idle_minutes", 0);
        }
        show(acc, from, idlePause, idleMin);
    }

    public void show(int newAccent, String from, String idlePauseStr, int idleMin) {
        if (closing) return;
        activeInstance = this;
        this.accent = newAccent | 0xFF000000;
        this.fromHome = "home".equals(from);
        this.idleMinutes = idleMin;
        initIdlePauseMode(idlePauseStr);

        SharedPreferences p = Tint.prefs(context);
        p.edit().putInt(ACCENT, accent).apply();

        if (root == null) build();
        applyAccent();
        updateRowsVisibility();

        if (!showing) {
            lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= 26 ? TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("dumbtv quick settings");
            try {
                wm.addView(root, lp);
                showing = true;
                context.sendBroadcast(new Intent("tv.dumbtv.home.PANEL_OPEN").setPackage("tv.dumbtv.home"));
            } catch (RuntimeException e) {
                Log.w(Tint.TAG, "quick settings: could not add overlay window", e);
                showing = false;
                return;
            }
        }

        initBluetooth();
        refreshBluetoothDevices();
        refreshHealth();
        refresh();

        ui.removeCallbacks(tick);
        ui.post(tick);

        if (noise != null) {
            noiseSampler.start(context, (fraction, db) -> {
                if (noise != null && showing && !closing) {
                    noise.bar.setValue(fraction);
                    noise.value.setText(context.getString(R.string.noise_db, db));
                }
            });
        }

        slide(true);
    }

    public void bringToTop() {
        if (!showing || closing || root == null || !root.isAttachedToWindow() || wm == null) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastBroughtToTopAt < 1000) return;
        lastBroughtToTopAt = now;

        cleanShield();

        Row focused = focusedRow();
        final int focusedIndex = focused != null ? rows.indexOf(focused) : -1;

        Bitmap snapshot = null;
        int w = root.getWidth(), h = root.getHeight();
        if (w > 0 && h > 0) {
            try {
                snapshot = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(snapshot);
                root.draw(canvas);
            } catch (Throwable t) {
                if (snapshot != null) {
                    snapshot.recycle();
                    snapshot = null;
                }
            }
        }

        final View shield;
        if (snapshot != null) {
            ImageView iv = new ImageView(context);
            iv.setImageBitmap(snapshot);
            iv.setScaleType(ImageView.ScaleType.FIT_XY);
            shield = iv;
            activeShieldBmp = snapshot;
        } else {
            View v = new View(context);
            v.setBackgroundColor(0x8C000000);
            shield = v;
        }
        activeShield = shield;

        WindowManager.LayoutParams shieldLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26 ? TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        shieldLp.setTitle("dumbtv quick shield");

        try {
            wm.addView(shield, shieldLp);
        } catch (RuntimeException e) {
            Log.w(Tint.TAG, "quick settings: could not add shield window", e);
            cleanShield();
            return;
        }

        final boolean[] executed = new boolean[1];
        final Runnable swap = () -> {
            if (executed[0]) return;
            executed[0] = true;
            if (!showing || closing || root == null || wm == null) {
                cleanShield();
                return;
            }
            try {
                if (root.isAttachedToWindow()) {
                    wm.removeViewImmediate(root);
                }
                wm.addView(root, lp);
            } catch (RuntimeException e) {
                Log.w(Tint.TAG, "quick settings: could not re-add root", e);
                cleanShield();
                return;
            }

            if (focusedIndex >= 0 && focusedIndex < rows.size()) {
                rows.get(focusedIndex).requestFocus();
            } else if (brightness != null) {
                brightness.requestFocus();
            }

            final ViewTreeObserver rootObserver = root.getViewTreeObserver();
            rootObserver.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                    root.post(Panel.this::cleanShield);
                    return true;
                }
            });
            root.postDelayed(Panel.this::cleanShield, 150);
        };

        final ViewTreeObserver shieldObserver = shield.getViewTreeObserver();
        shieldObserver.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                shield.getViewTreeObserver().removeOnPreDrawListener(this);
                shield.post(swap);
                return true;
            }
        });
        ui.postDelayed(swap, 200);
    }

    private void cleanShield() {
        if (activeShield != null && wm != null) {
            try {
                if (activeShield.isAttachedToWindow()) {
                    wm.removeViewImmediate(activeShield);
                }
            } catch (RuntimeException ignored) {}
            activeShield = null;
        }
        if (activeShieldBmp != null) {
            if (!activeShieldBmp.isRecycled()) {
                activeShieldBmp.recycle();
            }
            activeShieldBmp = null;
        }
    }

    public void close() {
        if (!showing || closing) return;
        closing = true;
        cleanShield();
        ui.removeCallbacks(tick);
        noiseSampler.stop();
        slide(false);
    }

    // ---- layout ----

    private void build() {
        root = new FrameLayout(context) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                int code = e.getKeyCode();
                boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
                switch (code) {
                    case KeyEvent.KEYCODE_BACK:
                        if (SystemClock.uptimeMillis() < ignoreBackUntil) {
                            Log.i(Tint.TAG, "ignoring synthetic BACK during Amazon QS suppression");
                            if (wm != null && root != null && root.isAttachedToWindow()) {
                                try {
                                    wm.updateViewLayout(root, root.getLayoutParams());
                                } catch (Exception ignored) {}
                                root.requestFocus();
                            }
                            return true;
                        }
                        if (down && e.getRepeatCount() == 0) close();
                        return true;
                    case KeyEvent.KEYCODE_MENU:
                    case KeyEvent.KEYCODE_SETTINGS:
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
        };
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        scrim = new View(context);
        scrim.setBackgroundColor(0x8C000000);
        scrim.setOnClickListener(v -> close());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        sheet = new LinearLayout(context);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(24), dp(24), dp(24), dp(20));
        sheet.setElevation(dp(16));

        TextView title = text(22, TEXT, LIGHT);
        title.setText(R.string.title);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
        tl.bottomMargin = dp(14);
        tl.leftMargin = dp(4);
        sheet.addView(title, tl);

        scroller = new ScrollView(context);
        scroller.setVerticalScrollBarEnabled(false);
        scroller.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rowContainer = new LinearLayout(context);
        rowContainer.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(rowContainer, new ScrollView.LayoutParams(-1, -2));
        sheet.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1f));

        // 1. Brightness
        brightness = row(R.drawable.ic_brightness, R.string.brightness, true);
        brightness.adjust = this::stepBrightness;
        brightness.press = this::toggleBrightnessAuto;

        // 2. Bluetooth
        bluetooth = row(R.drawable.ic_bluetooth, R.string.bluetooth, false);
        bluetooth.adjust = this::stepBluetooth;
        bluetooth.press = this::pressBluetooth;

        // 3. AirPlay
        airplay = row(R.drawable.ic_airplay, R.string.airplay, false);
        airplay.press = this::makeRoom;

        // 4. Picture off
        pictureOff = row(R.drawable.ic_picture_off, R.string.picture_off, false);
        pictureOff.press = this::triggerPictureOff;

        // 5. Network
        network = row(R.drawable.ic_wifi, R.string.network, false);
        network.press = this::restartFilter;

        // 6. TV Health
        health = row(R.drawable.ic_health, R.string.health, false);
        health.press = this::cycleHealthDetails;

        // 7. Idle pause
        idlePause = row(R.drawable.ic_pause, R.string.idle_pause, false);
        idlePause.adjust = this::stepIdlePause;

        // 8. Warmth
        warmth = row(R.drawable.ic_warmth, R.string.warmth, true);
        warmth.bar.setGradient(0xFFCFE3FF, 0xFFFFD08A, 0xFFFF8A1F);
        warmth.adjust = this::stepWarmth;
        warmth.press = this::toggleWarmthAuto;

        // 9. Sleep timer
        sleep = row(R.drawable.ic_sleep, R.string.sleep, false);
        sleep.adjust = this::stepSleep;

        // 10. Noise meter
        if (Noise.hasPermission(context) && Noise.builtInMic(context) != null) {
            noise = row(R.drawable.ic_mic, R.string.noise, true);
            noise.bar.setGradient(0xFF4CAF50, 0xFFFFEB3B, 0xFFF44336);
        }

        sheet.addView(footer(), new LinearLayout.LayoutParams(-1, -2));

        FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(dp(WIDTH_DP), -1, Gravity.END);
        root.addView(sheet, sl);
        applyAccent();
    }

    private void applyAccent() {
        if (sheet == null) return;
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(blend(SURFACE, accent, 0.14f));
        float r = dp(26);
        bg.setCornerRadii(new float[] {r, r, 0, 0, 0, 0, r, r});
        bg.setStroke(dp(1), (accent & 0x00FFFFFF) | 0x59000000);
        sheet.setBackground(bg);
        for (Row row : rows) row.look(row.isFocused());
    }

    private void updateRowsVisibility() {
        if (health != null) {
            health.setVisibility(new File(Health.CONF_FILE).exists() ? View.VISIBLE : View.GONE);
        }
        if (idlePause != null) {
            idlePause.setVisibility(fromHome && idleMinutes > 0 ? View.VISIBLE : View.GONE);
        }
    }

    private LinearLayout footer() {
        LinearLayout f = new LinearLayout(context);
        f.setOrientation(LinearLayout.HORIZONTAL);
        TextView[] when = cell(f, 0);
        clock = when[0];
        date = when[1];
        TextView[] mem = cell(f, dp(8));
        ram = mem[0];
        mem[1].setText(R.string.ram);
        TextView[] vid = cell(f, dp(8));
        videoText = vid[0];
        videoSub = vid[1];
        return f;
    }

    private TextView[] cell(LinearLayout parent, int gap) {
        LinearLayout c = new LinearLayout(context);
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
        final ImageView icon = new ImageView(context);
        final TextView label = text(15, TEXT, MEDIUM);
        final TextView value = text(14, TEXT, MEDIUM);
        final TextView sub = text(12, MUTED, Typeface.DEFAULT);
        final Bar bar;
        Adjust adjust;
        Runnable press;

        Row(int iconRes, int labelRes, boolean withBar) {
            super(context);
            setOrientation(VERTICAL);
            setFocusable(true);
            setPadding(dp(16), dp(12), dp(16), dp(12));
            LinearLayout head = new LinearLayout(context);
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
                bar = new Bar(context);
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

    private Row focusedRow() {
        if (root == null) return null;
        View f = root.findFocus();
        return f instanceof Row ? (Row) f : null;
    }

    private void slide(boolean in) {
        float w = dp(WIDTH_DP);
        TimeInterpolator ease = in ? new DecelerateInterpolator(2.5f) : new AccelerateInterpolator(2f);
        if (in) {
            sheet.setTranslationX(w);
            scrim.setAlpha(0f);
        }
        scrim.animate().alpha(in ? 1f : 0f).setDuration(in ? 140 : 110).setInterpolator(ease).start();
        sheet.animate().translationX(in ? 0 : w).setDuration(in ? 140 : 110).setInterpolator(ease)
                .withEndAction(in ? () -> {
                    if (focusedRow() == null && brightness != null) brightness.requestFocus();
                } : this::onClosed).start();
    }

    private void onClosed() {
        showing = false;
        closing = false;
        cleanShield();
        if (activeInstance == this) activeInstance = null;
        if (wm != null && root != null && root.isAttachedToWindow()) {
            try {
                wm.removeView(root);
            } catch (RuntimeException ignored) {}
        }
        context.sendBroadcast(new Intent("tv.dumbtv.home.PANEL_CLOSED").setPackage("tv.dumbtv.home"));
        Tint.suppressAmazonQuickSettings(context);
        if (fromHome && idlePauseResult != null) {
            Intent res = new Intent("tv.dumbtv.home.SET_IDLE_PAUSE").setPackage("tv.dumbtv.home");
            res.putExtra("idle_pause", idlePauseResult);
            context.sendBroadcast(res);
        }
    }

    // ---- row logic ----

    private void refresh() {
        showBrightness();
        showBluetooth();
        showAirPlay();
        showPictureOff();
        showNetwork();
        showHealth();
        if (idlePause != null && idlePause.getVisibility() == View.VISIBLE) showIdlePause();
        showWarmth();
        showSleep();
        showStatus();
    }

    // 1. Brightness
    private void showBrightness() {
        int p = Math.round(Backlight.get(context) * 100f / 255f);
        brightness.value.setText(context.getString(R.string.percent, p));
        brightness.bar.setValue(p / 100f);
        boolean can = Settings.System.canWrite(context);
        if (!can) {
            brightness.sub.setText(R.string.needs_install);
        } else if (Backlight.isAuto(context)) {
            int nightP = Math.round(Backlight.nightLevel(context) * 100f / 255f);
            brightness.sub.setText(context.getString(R.string.brightness_auto, nightP));
        } else {
            brightness.sub.setText(R.string.brightness_hint);
        }
    }

    private void stepBrightness(int dir) {
        if (!Settings.System.canWrite(context)) return;
        boolean atNight = Backlight.isAuto(context) && Backlight.isNight();
        int current = atNight ? Backlight.nightLevel(context) : Backlight.dayLevel(context);
        int p = Math.round(current * 100f / 255f / BRIGHTNESS_STEP) * BRIGHTNESS_STEP + dir * BRIGHTNESS_STEP;
        p = Math.max(BRIGHTNESS_MIN, Math.min(100, p));
        Backlight.set(context, Math.round(p * 255f / 100f));
        showBrightness();
    }

    private void toggleBrightnessAuto() {
        if (!Settings.System.canWrite(context)) return;
        Backlight.setAuto(context, !Backlight.isAuto(context));
        showBrightness();
    }

    // 2. Bluetooth
    private void initBluetooth() {
        BluetoothA2dp shared = Tint.getA2dp();
        if (shared != null) {
            a2dp = shared;
            refreshBluetoothDevices();
            showBluetooth();
            return;
        }
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a != null && a.isEnabled()) {
            a.getProfileProxy(context, btListener, BluetoothProfile.A2DP);
        }
    }

    private void refreshBluetoothDevices() {
        pairedDevices.clear();
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a != null && a.isEnabled()) {
            Set<BluetoothDevice> bonded = a.getBondedDevices();
            if (bonded != null) {
                for (BluetoothDevice d : bonded) {
                    BluetoothClass k = d.getBluetoothClass();
                    if (k != null && k.getMajorDeviceClass() == BluetoothClass.Device.Major.AUDIO_VIDEO) {
                        pairedDevices.add(d);
                    }
                }
            }
        }
        if (btTargetIndex > pairedDevices.size()) btTargetIndex = pairedDevices.size();
    }

    private void showBluetooth() {
        if (bluetooth == null) return;
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
            bluetooth.sub.setText(battery >= 0 ? context.getString(R.string.bt_connected_battery, battery) : context.getString(R.string.bt_connected));
        } else if (state == BluetoothProfile.STATE_CONNECTING) {
            bluetooth.sub.setText(R.string.bt_connecting);
        } else if (state == BluetoothProfile.STATE_DISCONNECTING) {
            bluetooth.sub.setText(R.string.bt_disconnecting);
        } else {
            bluetooth.sub.setText(R.string.bt_disconnected);
        }
    }

    private void stepBluetooth(int dir) {
        int total = pairedDevices.size() + 1;
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
                context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {}
        }
    }

    // 3. AirPlay
    private void showAirPlay() {
        int state = Tint.getAirPlayState();
        if (state == AirPlay.IN_USE) {
            airplay.value.setText(R.string.airplay_in_use);
            airplay.sub.setText("OK: Make room");
        } else if (state == AirPlay.BROKEN) {
            airplay.value.setText(R.string.off);
            airplay.sub.setText(R.string.airplay_broken);
        } else {
            airplay.value.setText(R.string.airplay_ready);
            airplay.sub.setText("OK: Make room");
        }
    }

    private void makeRoom() {
        Tint.makeRoom(context);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        String free = String.format(Locale.US, "%.1f GB", mi.availMem / 1e9);
        airplay.sub.setText(context.getString(R.string.airplay_room_made, free));
    }

    // 4. Picture off
    private void showPictureOff() {
        pictureOff.value.setText("›");
        pictureOff.sub.setText(R.string.picture_off_hint);
    }

    private void triggerPictureOff() {
        Tint.setPictureOff(context, true);
        close();
    }

    // 5. Network (Filter + Wi-Fi)
    private void showNetwork() {
        network.value.setText("›");
        network.sub.setText(Network.glance(context));
    }

    private void restartFilter() {
        Network.restartFilter(context);
        ui.postDelayed(this::showNetwork, 1000);
    }

    // 6. TV Health
    private void refreshHealth() {
        if (health == null) return;
        List<String> cached = cachedHealthIssues;
        if (cached != null) {
            healthIssues = cached;
            showHealth();
        }
        long now = SystemClock.uptimeMillis();
        if (cached != null && (now - lastHealthCheck < HEALTH_CACHE_TTL_MS)) return;
        new Thread(() -> {
            File conf = new File(Health.CONF_FILE);
            if (!conf.exists()) {
                updateHealthIssues(Collections.singletonList(context.getString(R.string.health_no_conf)));
                return;
            }
            Health.Expected exp = Health.loadExpected(conf);
            if (exp == null) {
                updateHealthIssues(Collections.singletonList("Could not parse expected.conf"));
                return;
            }
            Health.Actual act = new Health.Actual();
            act.build = Build.DISPLAY != null ? Build.DISPLAY : Build.ID;
            act.accessibility = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

            PackageManager pm = context.getPackageManager();
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

            List<String> issues = Health.check(exp, act);
            updateHealthIssues(issues);
        }, "health-refresh").start();
    }

    private void updateHealthIssues(List<String> issues) {
        cachedHealthIssues = issues;
        lastHealthCheck = SystemClock.uptimeMillis();
        ui.post(() -> {
            healthIssues = issues;
            showHealth();
        });
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
            case "global": return Settings.Global.getString(context.getContentResolver(), name);
            case "system": return Settings.System.getString(context.getContentResolver(), name);
            case "secure":
            default: return Settings.Secure.getString(context.getContentResolver(), name);
        }
    }

    private void showHealth() {
        if (health == null) return;
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
    private void initIdlePauseMode(String p) {
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
        idlePauseResult = null;
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
            c.set(Calendar.HOUR_OF_DAY, 6);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            if (c.getTimeInMillis() <= System.currentTimeMillis()) c.add(Calendar.DAY_OF_YEAR, 1);
            idlePauseResult = String.valueOf(c.getTimeInMillis());
        } else {
            idlePauseResult = "off";
        }
    }

    private void showIdlePause() {
        if (idlePauseMode == 0) {
            idlePause.value.setText(context.getString(R.string.idle_pause_active, idleMinutes));
            idlePause.sub.setText("Switches to Chromecast when idle");
        } else if (idlePauseMode == 1) {
            idlePause.value.setText(R.string.idle_pause_morning);
            idlePause.sub.setText("Paused until 06:00");
        } else {
            idlePause.value.setText(R.string.idle_pause_off);
            idlePause.sub.setText("Chromecast auto-switch disabled");
        }
    }

    // 8. Warmth
    private void showWarmth() {
        SharedPreferences p = Tint.prefs(context);
        int level = p.getInt(Tint.WARMTH, 0);
        boolean auto = p.getBoolean(Tint.AUTO, false);
        warmth.value.setText(level == 0 ? context.getString(R.string.off) : context.getString(R.string.percent, level * 100 / Tint.STEPS));
        warmth.bar.setValue(level / (float) Tint.STEPS);
        String sub;
        if (auto) {
            long now = System.currentTimeMillis();
            TimeZone tz = TimeZone.getDefault();
            double[] at = Sun.place(tz);
            double[] today = Sun.times(now, tz, at[0], at[1]);
            boolean night = Sun.night(now, tz, at[0], at[1]) > 0;
            if (Double.isNaN(today[0])) {
                sub = context.getString(R.string.warmth_auto);
            } else if (night) {
                double rise = now < today[0] ? today[0] : Sun.times(now + 86400000L, tz, at[0], at[1])[0];
                sub = context.getString(R.string.warmth_auto_until, hm((long) rise));
            } else {
                sub = context.getString(R.string.warmth_auto_from, hm((long) today[1]));
            }
        } else {
            sub = context.getString(level == 0 ? R.string.warmth_hint : R.string.warmth_always);
        }
        warmth.sub.setText(sub);
    }

    private void stepWarmth(int dir) {
        Warmth.step(context, dir);
        showWarmth();
    }

    private void toggleWarmthAuto() {
        Warmth.toggleAuto(context);
        showWarmth();
    }

    // 9. Sleep
    private void showSleep() {
        int left = Sleep.minutesLeft(context);
        sleep.value.setText(left == 0 ? context.getString(R.string.off) : context.getString(R.string.minutes, left));
        if (!Sleep.canSleep(context)) sleep.sub.setText(R.string.needs_install);
        else if (left == 0) sleep.sub.setText(R.string.sleep_hint);
        else sleep.sub.setText(context.getString(R.string.sleep_at, hm(Sleep.at(context))));
    }

    private void stepSleep(int dir) {
        Sleep.set(context, Sleep.step(Sleep.minutesLeft(context), dir));
        showSleep();
    }

    // ---- status strip ----

    private void showStatus() {
        Date now = new Date();
        clock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(now));
        date.setText(new SimpleDateFormat("EEE MM-dd", Locale.getDefault()).format(now));

        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mi);
        ram.setText(String.format(Locale.US, "%.1f/%.1f", (mi.totalMem - mi.availMem) / 1e9, mi.totalMem / 1e9));

        Video.Format f = fromHome ? null : video.current(true);
        if (f != null) {
            videoText.setText(f.resolution());
            videoSub.setText(context.getString(f.srcType == Video.SRC_HDMI ? R.string.video_hdmi : R.string.video_app, f.range()));
        } else {
            Display.Mode m = wm.getDefaultDisplay().getMode();
            int h = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
            videoText.setText((h >= 2100 ? "4K" : h + "p") + Math.round(m.getRefreshRate()));
            videoSub.setText(R.string.video_panel);
        }
    }

    // ---- helpers ----

    private String hm(long millis) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(millis));
    }

    private TextView text(float sp, int color, Typeface face) {
        TextView v = new TextView(context);
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
        return Math.round(v * context.getResources().getDisplayMetrics().density);
    }
}
