package tv.dumbtv.home;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Fire OS keeps HOME bound to Amazon's protected launcher and handles the HOME key before any
 * accessibility filter sees it, so this service waits for Amazon's home window and replaces it.
 *
 * A plain startActivity right after a HOME press is held back by Android's five-second
 * app-switch delay. An alarm-fired PendingIntent is sent by system_server, which is exempt,
 * so {@link #home} launches through an alarm. That alarm only fires at once with
 * {@code min_futurity=0}, which install.sh sets: in the global {@code alarm_manager_constants}
 * up to Android 11, in DeviceConfig {@code alarm_manager} from Android 12.
 *
 * Amazon's home is covered with a plain overlay until ours has drawn, so it never shows.
 *
 * Android 11 refuses an activity start from an app with no visible window, even one sent
 * through system_server by an alarm ("Background activity start ... callingUidHasAnyVisibleWindow:
 * false"). The cover is such a window, so the launch waits until the cover has reached the
 * window manager: {@link #launchWhenCovered} fires it from the cover's first draw pass.
 * Android 14 counts the cover only once the window manager has shown its surface, a frame or
 * more after that draw, so there the alarm is set {@link #SHOWN_DELAY_MS} later (issue #57).
 *
 * Going to standby, Fire OS 7 brings Amazon's home to the front with the screen already off.
 * The launch alarm is a wakeup alarm because Android holds back other alarms for minutes while
 * the screen is off, so ours came back up to three minutes into standby, or not before the wake.
 * Waking resumes whatever was on screen without a new window event. If the last window was
 * Amazon's home screen, ours is brought up at once. Amazon's other launcher screens, such as
 * its settings grid, are left alone, as is a wake before any window was seen.
 *
 * The remote's settings (cog) button never reaches an app or an accessibility key filter: Fire
 * OS's key policy sends it as a GLOBAL_BUTTON broadcast to Amazon's quick settings. dumbtv quick
 * settings, where installed, reads the press from the log and asks this service, with the ordered
 * broadcast {@link #ACTION_SETTINGS_KEY}, whether its panel replaces Amazon's here
 * ({@link Quick#enabled}); it keeps Amazon's overlay from opening itself (quick/AmazonQs.java).
 * This service only passes on what it alone sees: a window of {@link #AMAZON_QS} coming to the
 * front, and, when the panel asks for it, the BACK that is the last way to close that window.
 */
public final class Guard extends AccessibilityService {
    static final String AMAZON = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    static final String AMAZON_QS = "com.amazon.tv.quicksettings.ui";
    static final String ACTION_SETTINGS_KEY = "tv.dumbtv.home.KEY_SETTINGS";
    /** From dumbtv quick settings: Amazon's overlay outlived the panel and its own toggle. */
    static final String ACTION_AMAZON_BACK = "tv.dumbtv.home.AMAZON_QS_BACK";
    private static final String QUICK_AMAZON_WINDOW = "tv.dumbtv.quick.AMAZON_WINDOW";
    /** One BACK per overlay: a second one would leave the app or input beneath it. */
    private static final long BACK_DEBOUNCE_MS = 1500;
    private static final long COVER_MAX_MS = 1500;
    private static final long LAUNCH_FALLBACK_MS = 150;
    private static final long SHOWN_DELAY_MS = Build.VERSION.SDK_INT >= 34 ? 300 : 0;

    private static volatile boolean paused;
    private static Guard running;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uncover = this::uncover;
    private final Runnable launch = () -> home(this);
    private final HomeRetry homeRetry = new HomeRetry();
    private final Runnable retry = this::retryHome;
    /**
     * The panel's question for each press, and with {@code query} for no press: OK with the accent
     * and where the press landed when the panel replaces Amazon's, no answer when it does not.
     */
    private final BroadcastReceiver settingsKeyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            Config cfg = Config.get(Guard.this);
            cfg.refresh();
            if (!Quick.enabled(Guard.this, cfg)) return;
            if (isOrderedBroadcast()) {
                setResultCode(Activity.RESULT_OK);
                android.os.Bundle res = new android.os.Bundle();
                res.putInt(Quick.EXTRA_ACCENT, cfg.accent());
                if (Home.inFront) {
                    res.putString(Quick.EXTRA_FROM, "home");
                    res.putString(Quick.EXTRA_IDLE_PAUSE, cfg.get(Quick.CONF_IDLE_PAUSE, null));
                    int idleMin = 0;
                    try {
                        idleMin = Integer.parseInt(cfg.get("idle_input", "0").trim());
                    } catch (NumberFormatException ignored) {}
                    res.putInt(Quick.EXTRA_IDLE_MINUTES, idleMin);
                } else if (Input.isOpen()) {
                    res.putString(Quick.EXTRA_FROM, "input");
                } else {
                    res.putString(Quick.EXTRA_FROM, "app");
                }
                setResultExtras(res);
            }
        }
    };
    private long amazonBackAt = -BACK_DEBOUNCE_MS;
    private View cover;
    private boolean onAmazonHome;

    /**
     * dumbtv quick settings sends this only after its panel's window is gone and while Amazon's
     * own broadcast says its overlay is up, so the BACK reaches that overlay and nothing else.
     */
    private final BroadcastReceiver amazonBackReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            long now = SystemClock.uptimeMillis();
            if (now - amazonBackAt < BACK_DEBOUNCE_MS) return;
            amazonBackAt = now;
            performGlobalAction(GLOBAL_ACTION_BACK);
        }
    };

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (!paused && onAmazonHome && !Home.inFront) launchWhenCovered();
        }
    };

    /** Lets Amazon's home stay on screen until the user leaves it. */
    static void pause() {
        paused = true;
        if (running != null) running.cancelRetry();
        Keeper.pause();
    }

    static void home(Context c) {
        home(c, 0);
    }

    private static void home(Context c, long delayMs) {
        c.sendBroadcast(new Intent("tv.dumbtv.quick.CLOSE").setPackage("tv.dumbtv.quick"));
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + delayMs,
                launch(c));
    }

    /** Drops a queued launch, so it cannot pull the user back after they opened an app. */
    static void arrived(Context c) {
        AlarmManager alarms = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        alarms.cancel(launch(c));
    }

    /** Called once our home window is on screen. */
    static void shown() {
        if (running != null) running.uncover();
    }

    private static PendingIntent launch(Context c) {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(c, Home.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return PendingIntent.getActivity(c, 0, i, PendingIntent.FLAG_UPDATE_CURRENT,
                ActivityOptions.makeCustomAnimation(c, 0, 0).toBundle());
    }

    static void amazon(Context c) {
        pause();
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(AMAZON).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            c.startActivity(i);
        } catch (ActivityNotFoundException e) {
            paused = false;
        }
    }

    @Override
    protected void onServiceConnected() {
        running = this;
        View view = new View(this);
        view.setVisibility(View.GONE);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        lp.windowAnimations = 0;
        ((WindowManager) getSystemService(WINDOW_SERVICE)).addView(view, lp);
        cover = view;
        registerReceiver(screenOn, new IntentFilter(Intent.ACTION_SCREEN_ON));
        registerReceiver(settingsKeyReceiver, new IntentFilter(ACTION_SETTINGS_KEY));
        registerReceiver(amazonBackReceiver, new IntentFilter(ACTION_AMAZON_BACK));
        Filter.watch(this);
    }

    @Override
    public void onDestroy() {
        cancelRetry();
        handler.removeCallbacks(uncover);
        handler.removeCallbacks(launch);
        try {
            unregisterReceiver(screenOn);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(settingsKeyReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(amazonBackReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        if (cover != null) ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(cover);
        if (running == this) running = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null) return;
        if (AMAZON_QS.contentEquals(pkg)) {
            // An overlay over whatever is in front: the panel decides whether it stays.
            sendBroadcast(new Intent(QUICK_AMAZON_WINDOW).setPackage(Quick.PACKAGE));
            return;
        }
        if (!AMAZON.contentEquals(pkg)) {
            if (Quick.PACKAGE.contentEquals(pkg) || "com.android.systemui".contentEquals(pkg) || "android".contentEquals(pkg)) return;
            boolean own = getPackageName().contentEquals(pkg);
            if (own && !OwnWindow.showsWhatIsInFront(cls, View.class.getName(),
                    Home.class.getName(), Home.inFront)) return;
            cancelRetry();
            onAmazonHome = false;
            paused = false;
            if (!own) arrived(this);
            return;
        }
        onAmazonHome = cls != null && AMAZON_HOME.contentEquals(cls);
        if (paused || !onAmazonHome) {
            cancelRetry();
            return;
        }
        homeRetry.request();
        retryHome();
    }

    /** Retain a throttled HOME event: Fire OS may leave Amazon Home on top after a burst. */
    private void retryHome() {
        handler.removeCallbacks(retry);
        if (running != this || paused || !onAmazonHome || Home.inFront) {
            homeRetry.cancel();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (homeRetry.take(now)) launchWhenCovered();
        long delay = homeRetry.delay(now);
        if (delay >= 0) handler.postAtTime(retry, now + delay);
    }

    private void cancelRetry() {
        handler.removeCallbacks(retry);
        homeRetry.cancel();
    }

    /**
     * Shows the cover, then starts the launch once that window is visible to the window manager.
     * A cover that is already on screen (a repeat within {@link #COVER_MAX_MS}) gets no new draw
     * pass, so the launch goes out at once; otherwise it waits for the first pre-draw, which runs
     * after the window's relayout. A short fallback covers a window that never draws.
     */
    private void launchWhenCovered() {
        handler.removeCallbacks(launch);
        if (cover == null) {
            home(this);
            return;
        }
        boolean shown = cover.getVisibility() == View.VISIBLE;
        cover();
        if (shown) {
            home(this);
            return;
        }
        final ViewTreeObserver observer = cover.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                cover.getViewTreeObserver().removeOnPreDrawListener(this);
                handler.removeCallbacks(launch);
                home(Guard.this, SHOWN_DELAY_MS);
                return true;
            }
        });
        handler.postDelayed(launch, LAUNCH_FALLBACK_MS);
    }

    /** The overlay window exists from service start; a GONE root keeps its surface hidden. */
    private void cover() {
        if (cover == null) return;
        cover.setBackgroundColor(Config.get(this).background());
        cover.setVisibility(View.VISIBLE);
        handler.removeCallbacks(uncover);
        handler.postDelayed(uncover, COVER_MAX_MS);
    }

    private void uncover() {
        handler.removeCallbacks(uncover);
        if (cover != null) cover.setVisibility(View.GONE);
    }

    @Override
    public void onInterrupt() {}
}
