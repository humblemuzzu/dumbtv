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
    static final String WARMTH = "warmth", AUTO = "auto", WARMTH_PAUSE = "warmth_pause";
    static final String ACTION_SETTINGS_KEY = "tv.dumbtv.home.KEY_SETTINGS";
    static final String ACTION_SHOW = "tv.dumbtv.quick.SHOW";
    static final String ACTION_CLOSE = "tv.dumbtv.quick.CLOSE";
    /** From dumbtv home when Options › Quick settings panel or Child lock changes. */
    static final String ACTION_SYNC = "tv.dumbtv.quick.SYNC";
    /** From dumbtv home's Guard: a window of Amazon's quick settings came to the front. */
    static final String ACTION_AMAZON_WINDOW = "tv.dumbtv.quick.AMAZON_WINDOW";
    /** To dumbtv home's Guard: press BACK, the last way to close Amazon's overlay. */
    static final String ACTION_AMAZON_BACK = "tv.dumbtv.home.AMAZON_QS_BACK";
    /** A press of the cog or of HOME accounts for Amazon's overlay starting this long after it. */
    private static final long ATTRIBUTE_MS = 2500;
    /** Amazon's overlay draws its window this long or more after its service starts. */
    private static final long AMAZON_DRAW_MS = 700;
    /** After the panel's window is gone, for focus to reach Amazon's overlay before the BACK. */
    private static final long BACK_AFTER_MS = 150;
    /** The lock stays off this long after a long press of HOME, for Amazon to take the press. */
    private static final long RELOCK_MS = 1500;
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
    private long lastSettingsKeyAt = -ATTRIBUTE_MS;
    /** The last cog press that went to the panel (opened or closed it). */
    private long cogAt = -ATTRIBUTE_MS;
    /** The last time Amazon's overlay was wanted: a long press of HOME, or the panel switched off. */
    private volatile long wantAmazonAt = -ATTRIBUTE_MS;
    private long longHomeAt = -ATTRIBUTE_MS;
    /** The last cog press that closed the panel: that press reaches Amazon's overlay too. */
    private long cogClosedAt = -ATTRIBUTE_MS;
    /** Amazon's overlay is up because of a cog press, not because it was asked for. */
    private boolean amazonUnder;
    private final Runnable amazonCheck = this::amazonStillUp;
    /** dumbtv home's last answer: the panel replaces Amazon's here. */
    private volatile boolean panelOn;

    private final BroadcastReceiver quickReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent == null) return;
            String act = intent.getAction();
            if (ACTION_CLOSE.equals(act)) {
                if (panel != null && panel.isShowing()) panel.close();
            } else if (ACTION_SHOW.equals(act) || "tv.dumbtv.quick.PANEL".equals(act)) {
                show(intent);
            } else if (ACTION_SYNC.equals(act)) {
                syncLock();
            } else if (ACTION_AMAZON_WINDOW.equals(act)) {
                onAmazonWindow();
            } else if (AmazonQs.VISIBILITY.equals(act)) {
                onAmazonVisibility(intent.getStringExtra("Visibility"));
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
            t.ui.post(() -> t.show(intent));
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
        filter.addAction(ACTION_CLOSE);
        filter.addAction(ACTION_SHOW);
        filter.addAction(ACTION_SYNC);
        filter.addAction(ACTION_AMAZON_WINDOW);
        filter.addAction("tv.dumbtv.quick.PANEL");
        registerReceiver(quickReceiver, filter, null, ui);
        // Amazon's overlay service announces its start and stop to the store demo, with no
        // permission; a registered receiver gets it (a manifest one would not, being implicit).
        registerReceiver(quickReceiver, new IntentFilter(AmazonQs.VISIBILITY), null, ui);
        syncLock();

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
                show(intent);
            } else if (ACTION_CLOSE.equals(act)) {
                if (panel != null && panel.isShowing()) panel.close();
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
        panel = null;
        // Without this service nothing opens the panel, so the cog goes back to Amazon's.
        AmazonQs.lock(this, false);
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
                    "WindowManager:W", "ContextImpl:W", "*:S")
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
                    } else if (LogLines.settingsKey(line)) {
                        ui.post(this::onSettingsKey);
                    } else if (LogLines.longHome(line)) {
                        // Here, not on the main thread: Amazon reads its lock moments after this
                        // line, and the main thread may be busy drawing the panel.
                        onLongHome();
                    } else if (LogLines.longHomeTag(line)) {
                        continue;
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

    private void show(Intent intent) {
        if (panel != null) panel.show(intent);
    }

    private void show(int accent, String from, String idlePause, int idleMin) {
        if (panel != null) panel.show(accent, from, idlePause, idleMin);
    }

    /**
     * The remote's settings button. With Amazon's lock on (the panel enabled) Amazon ignores the
     * press and this is all that happens; with it off, Amazon's overlay starts too, and
     * {@link #onAmazonVisibility} keeps the panel above it and has it closed after the panel.
     */
    private void onSettingsKey() {
        final long now = SystemClock.uptimeMillis();
        if (now - lastSettingsKeyAt < 350) return;
        lastSettingsKeyAt = now;

        if (panel != null && panel.isShowing()) {
            cogAt = now;
            cogClosedAt = now;
            panel.close();
            return;
        }

        askHome(false, (ok, res) -> {
            if (ok) {
                cogAt = now;
                panelOn = true;
                applyLock();
                int accent = res != null ? res.getInt("accent", 0xFF3D8BFF) : 0xFF3D8BFF;
                String from = res != null ? res.getString("from") : null;
                String idlePause = res != null ? res.getString("idle_pause") : null;
                int idleMin = res != null ? res.getInt("idle_minutes", 0) : 0;
                show(accent, from, idlePause, idleMin);
                // Amazon's overlay can have started before the answer came.
                if (AmazonQs.up() && AmazonQs.changedAt() >= now) amazonUnder();
            } else if (!isHomeInstalled(this)) {
                cogAt = now;
                panelOn = true;
                applyLock();
                show(null);
            } else {
                // Switched off, child lock, or Guard not running: Amazon's panel, as without us.
                // The log line is the key going down and Amazon acts on it coming up, so this
                // press usually reaches Amazon already; nothing outside Amazon can open its
                // overlay on Fire OS 8, so if it came up too soon, the next press does.
                panelOn = false;
                wantAmazonAt = now;
                applyLock();
            }
        });
    }

    /**
     * A long press of HOME opens Amazon's overlay, and that one is left alone. Its receiver is the
     * one the lock turns away, so the lock comes off the moment the press shows in the log, and
     * back on once Amazon has had the press. Amazon reads the lock twice for one long press: with
     * the lock on, its first receiver passes the press on to a second one, which hands it back. So
     * there is time, though no promise. Runs on the log reader's thread.
     */
    private void onLongHome() {
        long now = SystemClock.uptimeMillis();
        if (now - longHomeAt < 1000) return;
        longHomeAt = now;
        wantAmazonAt = now;
        unlockedUntil = now + RELOCK_MS;
        AmazonQs.lock(this, false);
        ui.post(() -> {
            if (panel != null && panel.isShowing()) panel.close();
            ui.removeCallbacks(relock);
            ui.postDelayed(relock, RELOCK_MS + 20);
        });
    }

    private final Runnable relock = this::applyLock;
    /** A long press of HOME keeps the lock off until then, for Amazon to take the press. */
    private volatile long unlockedUntil;

    /** Whether Amazon's overlay, just started, is one the panel replaces rather than one asked for. */
    private boolean amazonUnwanted() {
        long now = SystemClock.uptimeMillis();
        if (now - wantAmazonAt < ATTRIBUTE_MS) return false;
        return (panel != null && panel.isShowing()) || now - cogAt < ATTRIBUTE_MS;
    }

    private void onAmazonVisibility(String visibility) {
        AmazonQs.visibility(visibility);
        applyLock();
        if (!AmazonQs.up()) {
            amazonUnder = false;
            ui.removeCallbacks(amazonCheck);
            return;
        }
        if (amazonUnwanted()) amazonUnder();
    }

    /**
     * Amazon's overlay started for a cog press although the lock should have stopped it (an
     * update, or the first press after the lock was off). Its window comes later and lands above
     * the panel's; Guard's window event, or failing that this check, puts the panel back on top.
     */
    private void amazonUnder() {
        Log.i(TAG, "Amazon quick settings started for the cog" + (AmazonQs.locked(this) ? " despite its lock" : ""));
        amazonUnder = true;
        ui.removeCallbacks(amazonCheck);
        ui.postDelayed(amazonCheck, AMAZON_DRAW_MS);
    }

    /** Once Amazon's window has drawn: the panel goes above it, or, with no panel, it goes. */
    private void amazonStillUp() {
        if (!amazonUnder || !AmazonQs.up()) return;
        if (panel != null && panel.isShowing()) panel.bringToTop();
        else backToAmazon();
    }

    /** Guard saw a window of Amazon's quick settings come to the front. */
    private void onAmazonWindow() {
        if (amazonUnder && AmazonQs.up() && panel != null && panel.isShowing()) panel.bringToTop();
    }

    /**
     * From {@link Panel} once its window is gone: Amazon's overlay that started with it and is
     * still up gets one BACK. Not when the cog closed the panel: that press reached Amazon's
     * overlay as well (the lock is off while it is up) and closes it.
     */
    void panelClosed() {
        if (!amazonUnder || !AmazonQs.up()) return;
        if (SystemClock.uptimeMillis() - cogClosedAt < ATTRIBUTE_MS && !AmazonQs.locked(this)) return;
        ui.postDelayed(this::backToAmazon, BACK_AFTER_MS);
    }

    /**
     * The last way to close Amazon's overlay: BACK from Guard, which lands on it as it is the top
     * window. Only while Amazon's own broadcast says it is up, as on anything else BACK would leave
     * an app or an HDMI input.
     */
    private void backToAmazon() {
        if (!amazonUnder || !AmazonQs.up()) return;
        if (panel != null && panel.isShowing()) return;
        Log.i(TAG, "Amazon quick settings still up without the panel; asking Guard for BACK");
        sendBroadcast(new Intent(ACTION_AMAZON_BACK).setPackage("tv.dumbtv.home"));
    }

    /** Asks dumbtv home again whether the panel replaces Amazon's, and sets the lock to match. */
    private void syncLock() {
        askHome(true, (ok, res) -> {
            panelOn = ok || !isHomeInstalled(this);
            applyLock();
        });
    }

    /**
     * The lock is on while the panel replaces Amazon's and Amazon's overlay is not up. While it is
     * up (a long press of HOME opened it, or it started under the panel), the cog reaches it again
     * and closes it the way it always did, while the panel opens or closes for the same press.
     */
    private void applyLock() {
        if (SystemClock.uptimeMillis() < unlockedUntil) return;
        AmazonQs.lock(this, panelOn && !AmazonQs.up());
    }

    private interface Answer {
        void on(boolean ok, Bundle res);
    }

    /**
     * Asks dumbtv home's Guard whether the panel replaces Amazon's (installed, switched on, no
     * child lock). {@code query} asks without a press. No answer (Guard not running) is a no.
     */
    private void askHome(boolean query, Answer answer) {
        Intent i = new Intent(ACTION_SETTINGS_KEY).setPackage("tv.dumbtv.home");
        if (query) i.putExtra("query", true);
        sendOrderedBroadcast(i, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                answer.on(getResultCode() == Activity.RESULT_OK, getResultExtras(false));
            }
        }, ui, Activity.RESULT_CANCELED, null, null);
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
