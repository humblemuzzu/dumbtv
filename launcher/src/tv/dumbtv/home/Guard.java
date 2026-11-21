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
 * OS's key policy sends it as a GLOBAL_BUTTON broadcast to Amazon's quick settings, which draws an
 * overlay window from {@link #AMAZON_QS}. Where dumbtv quick settings is installed and enabled
 * ({@link Quick#enabled}), that window's appearance is the signal: BACK closes it, and our panel
 * opens in its place, through {@link Home} when the home screen is in front.
 */
public final class Guard extends AccessibilityService {
    static final String AMAZON = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    static final String AMAZON_QS = "com.amazon.tv.quicksettings.ui";
    static final String ACTION_SETTINGS_KEY = "tv.dumbtv.home.KEY_SETTINGS";
    static final String ACTION_DISMISS_AMAZON_QS = "tv.dumbtv.home.DISMISS_AMAZON_QS";
    static final String ACTION_PANEL_OPEN = "tv.dumbtv.home.PANEL_OPEN";
    static final String ACTION_PANEL_CLOSED = "tv.dumbtv.home.PANEL_CLOSED";
    /** After BACK has closed Amazon's overlay, so that key cannot land on our panel instead. */
    private static final long QUICK_AFTER_BACK_MS = 250;
    /** One overlay sends several window events; one BACK only, or a second one leaves an input. */
    private static final long QUICK_DEBOUNCE_MS = 1500;
    private static final long COVER_MAX_MS = 1500;
    private static final long LAUNCH_FALLBACK_MS = 150;
    private static final long SHOWN_DELAY_MS = Build.VERSION.SDK_INT >= 34 ? 300 : 0;

    private static volatile boolean paused;
    private static Guard running;
    private volatile boolean panelShowing;
    private volatile boolean amazonQsActive;
    private volatile long amazonQsOpenedAt;
    /**
     * elapsedRealtime of the last screen-on, 0 once {@link Home} has acted on it. Home reads it to
     * go on to the {@code wake_input} from home.conf; the resume and the broadcast arrive in either
     * order, so both sides check.
     */
    static volatile long wokeAt;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable uncover = this::uncover;
    private final Runnable launch = () -> home(this);
    private final HomeRetry homeRetry = new HomeRetry();
    private final Runnable retry = this::retryHome;
    private final Runnable openQuick = () -> {
        if (!(Home.inFront && Home.openQuick())) Quick.open(this, Config.get(this).accent());
    };
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
            settingsKeyAt = SystemClock.uptimeMillis();
            if (intent.getBooleanExtra("close", false)) return;
            if (!isOrderedBroadcast()) {
                handler.removeCallbacks(openQuick);
                handler.post(openQuick);
            }
        }
    };
    private long settingsKeyAt = -QUICK_DEBOUNCE_MS;
    private long quickReplacedAt = -QUICK_DEBOUNCE_MS;
    private View cover;
    private boolean onAmazonHome;

    private final BroadcastReceiver panelReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            if (intent == null) return;
            String act = intent.getAction();
            if (ACTION_PANEL_OPEN.equals(act)) {
                panelShowing = true;
            } else if (ACTION_PANEL_CLOSED.equals(act)) {
                panelShowing = false;
                dismissAmazonQsIfNeeded(50);
            } else if (ACTION_DISMISS_AMAZON_QS.equals(act)) {
                dismissAmazonQsIfNeeded(30);
            }
        }
    };

    private void dismissAmazonQsIfNeeded(long delayMs) {
        if (!amazonQsActive) return;
        long now = SystemClock.uptimeMillis();
        if (now - amazonQsOpenedAt > 45000) {
            amazonQsActive = false;
            return;
        }
        amazonQsActive = false;
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), delayMs);
    }

    private final BroadcastReceiver screenOn = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            wokeAt = SystemClock.elapsedRealtime();
            if (Home.inFront) Home.woke();
            else if (!paused && onAmazonHome) launchWhenCovered();
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
        IntentFilter panelFilter = new IntentFilter();
        panelFilter.addAction(ACTION_PANEL_OPEN);
        panelFilter.addAction(ACTION_PANEL_CLOSED);
        panelFilter.addAction(ACTION_DISMISS_AMAZON_QS);
        registerReceiver(panelReceiver, panelFilter);
        Filter.watch(this);
    }

    @Override
    public void onDestroy() {
        cancelRetry();
        handler.removeCallbacks(uncover);
        handler.removeCallbacks(launch);
        handler.removeCallbacks(openQuick);
        try {
            unregisterReceiver(screenOn);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(settingsKeyReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            unregisterReceiver(panelReceiver);
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
            amazonQsActive = true;
            amazonQsOpenedAt = SystemClock.uptimeMillis();
            if (panelShowing) {
                sendBroadcast(new Intent("tv.dumbtv.quick.BRING_TO_TOP").setPackage("tv.dumbtv.quick"));
            }
            replaceQuickSettings();
            return;
        }
        if (!AMAZON.contentEquals(pkg)) {
            if ("tv.dumbtv.quick".contentEquals(pkg) || "com.android.systemui".contentEquals(pkg) || "android".contentEquals(pkg)) return;
            boolean own = getPackageName().contentEquals(pkg);
            if (own && !OwnWindow.showsWhatIsInFront(cls, View.class.getName(),
                    Home.class.getName(), Home.inFront)) return;
            cancelRetry();
            onAmazonHome = false;
            paused = false;
            amazonQsActive = false;
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

    /** Amazon's quick settings overlay opened: put ours in its place, where it is enabled. */
    private void replaceQuickSettings() {
        Config cfg = Config.get(this);
        cfg.refresh();
        if (!Quick.enabled(this, cfg)) return;
        long now = SystemClock.uptimeMillis();
        if (now - quickReplacedAt < QUICK_DEBOUNCE_MS) return;
        quickReplacedAt = now;
        sendBroadcast(new Intent("tv.dumbtv.quick.IGNORE_BACK").setPackage("tv.dumbtv.quick"));
        handler.postDelayed(() -> performGlobalAction(GLOBAL_ACTION_BACK), 50);
        if (now - settingsKeyAt > 2000) {
            handler.removeCallbacks(openQuick);
            handler.postDelayed(openQuick, QUICK_AFTER_BACK_MS);
        }
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
