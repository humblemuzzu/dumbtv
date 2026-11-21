package tv.dumbtv.quick;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.Service;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.TimeZone;

/**
 * The long-running half of quick settings, a foreground service started at boot and whenever the
 * panel opens. It holds the warmth overlay, the picture-off overlay, the in-memory quick settings
 * panel overlay, the 60-second dim tick, and follows DispService's and events' log for {@link Video}
 * and AirPlay status.
 */
public final class Tint extends Service {
    static final String TAG = "dumbtv-quick";
    static final String PREFS = "quick";
    static final String WARMTH = "warmth", AUTO = "auto";
    static final String ACTION_SETTINGS_KEY = "tv.dumbtv.home.KEY_SETTINGS";
    static final String ACTION_SHOW = "tv.dumbtv.quick.SHOW";
    static final String ACTION_CLOSE = "tv.dumbtv.quick.CLOSE";
    static final String ACTION_IGNORE_BACK = "tv.dumbtv.quick.IGNORE_BACK";
    static final String ACTION_BRING_TO_TOP = "tv.dumbtv.quick.BRING_TO_TOP";
    /** Warmth steps, 0 (off) to {@link #STEPS}. */
    static final int STEPS = Warmth.STEPS;

    private static final int TYPE_APPLICATION_OVERLAY = 2038;
    private static final int NOTIFICATION = 1;

    static final Video video = new Video();
    private static Tint running;
    private static volatile int airplayState = AirPlay.READY;
    private static volatile boolean pictureOffActive;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Panel panel;
    private View pictureOffView;
    private Backlight backlight;
    private Process logcat;
    private Thread reader;
    private BluetoothA2dp a2dp;
    private long lastSettingsKeyAt;

    private final BroadcastReceiver quickReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent == null) return;
            String act = intent.getAction();
            if (ACTION_IGNORE_BACK.equals(act)) {
                if (panel != null) panel.ignoreNextBack();
            } else if (ACTION_CLOSE.equals(act)) {
                if (panel != null && panel.isShowing()) panel.close();
            } else if (ACTION_BRING_TO_TOP.equals(act)) {
                if (panel != null && panel.isShowing()) panel.bringToTop();
            } else if (ACTION_SHOW.equals(act) || "tv.dumbtv.quick.PANEL".equals(act)) {
                if (panel != null) panel.show(intent);
            }
        }
    };

    private final BluetoothProfile.ServiceListener btListener = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            if (profile == BluetoothProfile.A2DP) a2dp = (BluetoothA2dp) proxy;
        }

        @Override
        public void onServiceDisconnected(int profile) {
            if (profile == BluetoothProfile.A2DP) a2dp = null;
        }
    };
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Warmth.tick(Tint.this);
            Backlight.tick(Tint.this);
            ui.postDelayed(this, Warmth.nextTickDelay(Tint.this));
        }
    };

    /** Starts the service, or re-applies the saved warmth and brightness if it is already running. */
    static void refresh(Context c) {
        Warmth.applyImmediate(c);
        Tint t = running;
        if (t != null) {
            Backlight.tick(c);
            return;
        }
        Compat.startForeground(c, new Intent(c, Tint.class));
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int getAirPlayState() {
        return airplayState;
    }

    static BluetoothA2dp getA2dp() {
        return running != null ? running.a2dp : null;
    }

    static void showFromIntent(Context c, Intent intent) {
        Tint t = running;
        if (t != null && t.panel != null) {
            t.ui.post(() -> t.panel.show(intent));
        } else {
            Intent s = new Intent(c, Tint.class);
            if (intent != null) s.putExtras(intent);
            s.setAction(ACTION_SHOW);
            Compat.startForeground(c, s);
        }
    }

    static void makeRoom(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(ACTIVITY_SERVICE);
        if (am == null) return;
        String[] pkgs = {
            "org.smarttube.stable",
            "com.amazon.avod",
            "com.google.android.youtube.tvunplugged",
            "com.amazon.firetv.youtube",
            "com.amazon.firetv.youtube.kids",
            "com.instagram.airwave"
        };
        for (String pkg : pkgs) {
            try {
                am.killBackgroundProcesses(pkg);
            } catch (SecurityException ignored) {}
        }
    }

    static boolean isPictureOff() {
        return pictureOffActive;
    }

    static void setPictureOff(Context c, boolean off) {
        pictureOffActive = off;
        Tint t = running;
        if (t != null) {
            t.ui.post(() -> t.updatePictureOff(off));
        } else {
            if (off) Backlight.write(c, 0);
            else Backlight.restoreLevel(c);
        }
    }

    private void updatePictureOff(boolean off) {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (wm == null) return;
        if (off) {
            if (pictureOffView == null) {
                pictureOffView = new View(this) {
                    @Override
                    public boolean dispatchKeyEvent(KeyEvent event) {
                        if (event.getAction() == KeyEvent.ACTION_DOWN) {
                            int code = event.getKeyCode();
                            if (code == KeyEvent.KEYCODE_VOLUME_UP ||
                                    code == KeyEvent.KEYCODE_VOLUME_DOWN ||
                                    code == KeyEvent.KEYCODE_VOLUME_MUTE) {
                                return false;
                            }
                            setPictureOff(Tint.this, false);
                            return true;
                        }
                        return super.dispatchKeyEvent(event);
                    }
                };
                pictureOffView.setBackgroundColor(0xFF000000);
                pictureOffView.setFocusable(true);
                pictureOffView.setFocusableInTouchMode(true);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                        Build.VERSION.SDK_INT >= 26 ? TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.OPAQUE);
                lp.setTitle("dumbtv picture off");
                try {
                    wm.addView(pictureOffView, lp);
                    pictureOffView.requestFocus();
                } catch (RuntimeException e) {
                    Log.w(TAG, "picture off: could not add overlay", e);
                    pictureOffView = null;
                }
            }
            Backlight.write(this, 0);
        } else {
            if (pictureOffView != null) {
                try {
                    wm.removeView(pictureOffView);
                } catch (RuntimeException ignored) {}
                pictureOffView = null;
            }
            Backlight.restoreLevel(this);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = this;
        foreground();
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        if (a != null && a.isEnabled()) {
            a.getProfileProxy(this, btListener, BluetoothProfile.A2DP);
        }
        backlight = new Backlight(this, ui);
        panel = new Panel(this, ui, video);

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_IGNORE_BACK);
        filter.addAction(ACTION_CLOSE);
        filter.addAction(ACTION_SHOW);
        filter.addAction(ACTION_BRING_TO_TOP);
        filter.addAction("tv.dumbtv.quick.PANEL");
        registerReceiver(quickReceiver, filter);

        PqBridge.setConnectionListener(() -> ui.post(() -> Warmth.applyImmediate(Tint.this)));
        PqBridge.bind(this);
        startReader();
        ui.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        apply();
        Backlight.tick(this);
        if (intent != null) {
            String act = intent.getAction();
            if ("tv.dumbtv.quick.PANEL".equals(act) || ACTION_SHOW.equals(act)) {
                if (panel != null) panel.show(intent);
            } else if (ACTION_CLOSE.equals(act)) {
                if (panel != null && panel.isShowing()) panel.close();
            } else if (ACTION_IGNORE_BACK.equals(act)) {
                if (panel != null) panel.ignoreNextBack();
            } else if (ACTION_BRING_TO_TOP.equals(act)) {
                if (panel != null && panel.isShowing()) panel.bringToTop();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        try {
            unregisterReceiver(quickReceiver);
        } catch (IllegalArgumentException ignored) {}
        if (panel != null && panel.isShowing()) {
            panel.close();
        }
        sendBroadcast(new Intent("tv.dumbtv.home.PANEL_CLOSED").setPackage("tv.dumbtv.home"));
        panel = null;
        Warmth.restore(this);
        if (pictureOffView != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(pictureOffView);
            } catch (RuntimeException ignored) {}
            pictureOffView = null;
        }
        if (a2dp != null) {
            BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
            if (a != null) a.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
            a2dp = null;
        }
        stopReader();
        backlight.stop();
        PqBridge.unbind(this);
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Applies hardware warmth for the active level. */
    void apply() {
        Warmth.apply(this, false);
    }

    /**
     * Follows DispService's and events' log for the rest of this service's life (-T 1).
     */
    private void startReader() {
        try {
            logcat = new ProcessBuilder("logcat", "-b", "default", "-b", "events", "-v", "brief", "-T", "1",
                    "DispService:I", "FireTVKeyPolicyManager:I", "am_proc_start:I", "am_proc_died:I",
                    "QSUI-OverlaySettingsService:I", "QSUI-OverlayMenuAdapter:I", "*:S")
                    .redirectErrorStream(true).start();
        } catch (IOException e) {
            Log.w(TAG, "logcat did not start", e);
            return;
        }
        final Process p = logcat;
        reader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                boolean wasHdmi = false;
                while ((line = r.readLine()) != null) {
                    if (line.contains("DispService")) {
                        if (!video.feed(line)) continue;
                        boolean nowHdmi = video.current(true) != null;
                        if (nowHdmi != wasHdmi) {
                            wasHdmi = nowHdmi;
                            ui.post(() -> backlight.source(nowHdmi));
                        }
                    } else if (line.contains("FireTVKeyPolicyManager") && line.contains("keycode 176")) {
                        handleSettingsKey();
                    } else if (line.contains("am_proc_start") && line.contains("com.amazon.tv.quicksettings.ui")) {
                        if (panel != null && panel.isShowing()) {
                            ui.post(panel::bringToTop);
                        }
                    } else if (line.contains("QSUI-OverlaySettingsService") || line.contains("QSUI-OverlayMenuAdapter")) {
                        if (panel != null && panel.isShowing()) {
                            ui.post(panel::bringToTop);
                        }
                    } else {
                        airplayState = AirPlay.next(airplayState, line);
                    }
                }
            } catch (IOException ignored) {
                // the process was stopped
            }
        }, "log-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void handleSettingsKey() {
        long now = SystemClock.uptimeMillis();
        if (now - lastSettingsKeyAt < 350) return;
        lastSettingsKeyAt = now;

        if (panel != null && panel.isShowing()) {
            suppressAmazonQuickSettings();
            ui.post(panel::close);
            sendBroadcast(new Intent(ACTION_SETTINGS_KEY).setPackage("tv.dumbtv.home").putExtra("close", true));
            return;
        }

        Intent i = new Intent(ACTION_SETTINGS_KEY).setPackage("tv.dumbtv.home");
        sendOrderedBroadcast(i, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (getResultCode() == Activity.RESULT_OK) {
                    suppressAmazonQuickSettings();
                    Bundle res = getResultExtras(false);
                    int accent = res != null ? res.getInt("accent", 0xFF3D8BFF) : 0xFF3D8BFF;
                    String from = res != null ? res.getString("from") : null;
                    String idlePause = res != null ? res.getString("idle_pause") : null;
                    int idleMin = res != null ? res.getInt("idle_minutes", 0) : 0;
                    ui.post(() -> {
                        if (panel != null) panel.show(accent, from, idlePause, idleMin);
                    });
                } else if (!isHomeInstalled(context)) {
                    ui.post(() -> {
                        if (panel != null) panel.show(null);
                    });
                }
            }
        }, null, Activity.RESULT_CANCELED, null, null);
    }

    static void suppressAmazonQuickSettings(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(ACTIVITY_SERVICE);
        if (am == null) return;
        try {
            am.killBackgroundProcesses("com.amazon.tv.quicksettings.ui");
            am.killBackgroundProcesses("com.amazon.tv.quicksettings");
        } catch (SecurityException ignored) {}
    }

    private void suppressAmazonQuickSettings() {
        suppressAmazonQuickSettings(this);
    }

    private static boolean isHomeInstalled(Context c) {
        Intent homeIntent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage("tv.dumbtv.home");
        return c.getPackageManager().resolveActivity(homeIntent, 0) != null;
    }

    private void stopReader() {
        if (logcat != null) logcat.destroy();
        logcat = null;
        reader = null;
        video.clear();
    }

    /** Foreground, so Android keeps the overlay and the log reader through memory pressure. */
    private void foreground() {
        Notification n = Compat.quietBuilder(this, "quick", getString(R.string.app_name))
                .setSmallIcon(R.drawable.ic_warmth).setContentTitle(getString(R.string.app_name))
                .setOngoing(true).build();
        startForeground(NOTIFICATION, n);
    }
}
