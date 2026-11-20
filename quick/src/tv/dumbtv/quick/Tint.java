package tv.dumbtv.quick;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
 * panel opens. It holds the warmth overlay, the picture-off overlay, the 60-second dim tick, and
 * follows DispService's and events' log for {@link Video} and AirPlay status.
 */
public final class Tint extends Service {
    static final String TAG = "dumbtv-quick";
    static final String PREFS = "quick";
    static final String WARMTH = "warmth", AUTO = "auto";
    /** Warmth steps, 0 (off) to {@link #STEPS}. */
    static final int STEPS = 10;

    /** The overlay at full warmth: a candle-ish orange at this opacity, about 3400 K on white. */
    private static final int COLOR = 0xE66E0A;
    private static final float MAX_ALPHA = 0.32f;
    private static final long TICK_MS = 60 * 1000L;
    private static final int TYPE_APPLICATION_OVERLAY = 2038;
    private static final int NOTIFICATION = 1;

    static final Video video = new Video();
    private static Tint running;
    private static volatile int airplayState = AirPlay.READY;
    private static volatile boolean pictureOffActive;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private View overlay;
    private View pictureOffView;
    private Backlight backlight;
    private Process logcat;
    private Thread reader;
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            apply();
            Backlight.tick(Tint.this);
            ui.postDelayed(this, TICK_MS);
        }
    };

    /** Starts the service, or re-applies the saved warmth and brightness if it is already running. */
    static void refresh(Context c) {
        Tint t = running;
        if (t != null) {
            t.apply();
            Backlight.tick(c);
            return;
        }
        Compat.startForeground(c, new Intent(c, Tint.class));
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The warmth in effect now, 0 to 1: the saved level, scaled by night when auto is on. */
    static float effective(Context c, long now) {
        SharedPreferences p = prefs(c);
        float level = p.getInt(WARMTH, 0) / (float) STEPS;
        if (level <= 0 || !p.getBoolean(AUTO, false)) return level;
        TimeZone tz = TimeZone.getDefault();
        double[] at = Sun.place(tz);
        return level * Sun.night(now, tz, at[0], at[1]);
    }

    static int getAirPlayState() {
        return airplayState;
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
        backlight = new Backlight(this, ui);
        startReader();
        ui.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        apply();
        Backlight.tick(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        removeOverlay();
        if (pictureOffView != null) {
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(pictureOffView);
            } catch (RuntimeException ignored) {}
            pictureOffView = null;
        }
        stopReader();
        backlight.stop();
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Shows, updates or removes the overlay for the warmth in effect now. */
    void apply() {
        float w = effective(this, System.currentTimeMillis());
        if (w <= 0.001f) {
            removeOverlay();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "warmth: no SYSTEM_ALERT_WINDOW; run quick/install.sh");
            return;
        }
        int argb = Color.argb(Math.round(255 * MAX_ALPHA * w), Color.red(COLOR), Color.green(COLOR), Color.blue(COLOR));
        if (overlay == null) {
            overlay = new View(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= 26 ? TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.setTitle("dumbtv warmth");
            try {
                ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(overlay, lp);
            } catch (RuntimeException e) {
                Log.w(TAG, "warmth: could not add the overlay", e);
                overlay = null;
                return;
            }
        }
        overlay.setBackgroundColor(argb);
    }

    private void removeOverlay() {
        if (overlay == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(overlay);
        } catch (RuntimeException ignored) {
            // already gone with the window manager
        }
        overlay = null;
    }

    /**
     * Follows DispService's and events' log for the rest of this service's life (-T 1).
     */
    private void startReader() {
        try {
            logcat = new ProcessBuilder("logcat", "-b", "default", "-b", "events", "-v", "brief", "-T", "1",
                    "DispService:I", "am_proc_start:I", "am_proc_died:I", "*:S")
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
