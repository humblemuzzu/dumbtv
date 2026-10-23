package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class Home extends Activity {
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    /** Fraction of the designed app-tile size, so all three steps shrink together. */
    private static final float TILE_SCALE = 0.75f;
    /** Match the pill (chips) height. */
    private static final float CLOCK_SP = 32f;
    /** Shared top inset so the clock and chips line up along their top edge. */
    private static final int HEAD_INSET = 6;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService colorIo = Executors.newSingleThreadExecutor();
    private final List<TextView> chips = new ArrayList<>();
    private Config cfg;
    private TvInputManager tv;
    private int applied = -1, appsGeneration;
    private boolean resumed, landed;
    private boolean forceFirstAppOnResume;
    /** Item id of the app tile focused last, so leaving the chips returns there. */
    private String lastAppId;
    private FrameLayout root;
    private ImageView wall;
    private View shade;
    private View tint;
    private TextView clock;
    private TextView date;
    private FrameLayout saver;
    private TextView saverClock;
    private TextView saverDate;
    private boolean saverShown;
    private boolean wakingSaver;
    private View focusBeforeSaver;
    private Row apps, inputs;
    private Tile moving;
    private String wallKey;
    private String backgroundMode;
    private ValueAnimator colorAnimation;
    private int displayedColor, targetColor;
    private boolean tintWallpaper;

    private final Runnable saverTask = () -> {
        if (resumed && !saverShown) showSaver();
    };

    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            Uri data = i.getData();
            loadApps(data == null ? null : data.getSchemeSpecificPart());
        }
    };

    private final BroadcastReceiver time = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            tick();
        }
    };

    private final TvInputManager.TvInputCallback inputWatch = new TvInputManager.TvInputCallback() {
        @Override
        public void onInputStateChanged(String id, int state) {
            loadInputs();
        }

        @Override
        public void onInputAdded(String id) {
            loadInputs();
        }

        @Override
        public void onInputRemoved(String id) {
            loadInputs();
        }

        @Override
        public void onInputUpdated(String id) {
            loadInputs();
        }
    };

    private final class Row {
        final String kind;
        /** Null when the row has no heading. */
        final TextView title;
        final HorizontalScrollView scroll;
        final LinearLayout strip;
        final Grid grid;
        List<Item> all = new ArrayList<>();

        Row(String kind, String name, LinearLayout parent) {
            this.kind = kind;
            title = name == null ? null : Ui.text(Home.this, 12, Ui.FAINT, Ui.MEDIUM);
            if (title != null) {
                title.setText(name.toUpperCase(Locale.ROOT));
                title.setLetterSpacing(0.14f);
            }
            strip = new LinearLayout(Home.this);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            scroll = new HorizontalScrollView(Home.this) {
                /** Keeps the focused tile centered, except near either end of the row. */
                @Override
                protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                    if (getChildCount() == 0) return 0;
                    int range = Math.max(0, getChildAt(0).getWidth() - (getWidth() - getPaddingLeft() - getPaddingRight()));
                    int target = Math.max(0, Math.min(range, r.centerX() - getWidth() / 2));
                    return target - getScrollX();
                }
            };
            scroll.setFocusable(false);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            scroll.setClipChildren(false);
            scroll.setClipToPadding(false);
            scroll.addView(strip, new FrameLayout.LayoutParams(WRAP, WRAP));
            grid = new Grid(Home.this);
            inset(edge());
            if (title != null) parent.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
            parent.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
            parent.addView(grid, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        /**
         * Lays the row out from {@code edge}. The bleed lets a focused tile grow past it into the
         * screen margin without being clipped.
         */
        void inset(int edge) {
            int bleed = dp(7);
            if (title != null) title.setPadding(edge, dp(6), edge, 0);
            strip.setPadding(bleed, dp(6), bleed, dp(4));
            scroll.setPadding(edge - bleed, 0, edge - bleed, 0);
            grid.setPadding(edge, dp(6), edge, dp(4));
        }

        /** Whichever container currently holds the tiles, following Options › Layout. */
        ViewGroup tiles() {
            return "grid".equals(cfg.get(Config.LAYOUT)) ? grid : strip;
        }

        void setVisible(boolean on) {
            boolean gridMode = "grid".equals(cfg.get(Config.LAYOUT));
            if (title != null) title.setVisibility(on ? View.VISIBLE : View.GONE);
            scroll.setVisibility(on && !gridMode ? View.VISIBLE : View.GONE);
            grid.setVisibility(on && gridMode ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Future<List<Item>> catalog = io.submit(() -> Catalog.apps(this));
        cfg = Config.get(this);
        tv = (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE);
        setContentView(build());

        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        registerReceiver(packages, f);
        Keeper.start(this);
        if (tv != null) tv.registerCallback(inputWatch, ui);
        firstApps(catalog);
        loadInputs();
    }

    /**
     * Waits for the app list and loads the art of the tiles that fit on screen, so the first
     * frame is complete. After a cold start that frame is what people see first: loading in the
     * background drew the Inputs row alone, focused HDMI 1 for ~0.3 s, then popped the apps and
     * their banners in.
     */
    private void firstApps(Future<List<Item>> catalog) {
        try {
            apps.all = catalog.get();
        } catch (ExecutionException e) {
            loadApps(null);
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            loadApps(null);
            return;
        }
        int fit = getResources().getDisplayMetrics().widthPixels / (cardSize()[0] + dp(18)) + 1;
        for (Item it : Catalog.visible(cfg, Catalog.APPS, apps.all)) {
            if (fit-- <= 0) break;
            Catalog.art(getPackageManager(), it);
            it.artRequested = true;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Guard.arrived(this);
        cfg.refresh();
        cfg.visited();
        if (cfg.version() != applied) apply();
        else background();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(time, f);
        tick();
        scheduleSaver();
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    /**
     * Options and Settings are activities: a focus request made in onResume lands before this
     * window regains focus, and is dropped again. Apply it here instead, once the window is
     * focused and any re-render they caused has finished.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && forceFirstAppOnResume) {
            forceFirstAppOnResume = false;
            focusFirst();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        ui.removeCallbacks(saverTask);
        hideSaver(false);
        wakingSaver = false;
        stopColorAnimation();
        targetColor = 0;
        unregisterReceiver(time);
        if (moving != null) stopMoving();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacks(saverTask);
        unregisterReceiver(packages);
        if (tv != null) tv.unregisterCallback(inputWatch);
        io.shutdownNow();
        colorIo.shutdownNow();
        super.onDestroy();
    }

    /**
     * Android 9 keeps a hidden window's GPU context (about 35 MB on this TV) unless memory is
     * critical. Dropping it while an app plays costs one context rebuild on the way back.
     */
    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level < TRIM_MEMORY_UI_HIDDEN || level >= TRIM_MEMORY_COMPLETE) return;
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal");
            Object global = wmg.getMethod("getInstance").invoke(null);
            wmg.getMethod("trimMemory", int.class).invoke(global, TRIM_MEMORY_COMPLETE);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // not reachable on this build: the context simply stays
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (moving != null) stopMoving();
        if (resumed) focusFirst();
    }

    @Override
    public void onBackPressed() {
        focusFirst();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (saverShown || wakingSaver) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (saverShown) hideSaver();
                wakingSaver = true;
            } else if (e.getAction() == KeyEvent.ACTION_UP) {
                wakingSaver = false;
            }
            return true;
        }
        if (moving == null) return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    shift(-1);
                    break;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    shift(1);
                    break;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    // In a grid, up and down move the tile a whole line; a strip has no lines.
                    if (moving.getParent() instanceof Grid) {
                        int cols = ((Grid) moving.getParent()).columns();
                        shift(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP ? -cols : cols);
                    }
                    break;
                default:
                    stopMoving();
            }
        }
        return true;
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        if (!saverShown) scheduleSaver();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Tile) menu((Tile) f);
            else { forceFirstAppOnResume = true; startActivity(new Intent(this, Options.class)); }
            return true;
        }
        return super.onKeyDown(code, e);
    }

    /**
     * The scroll position for a focused rect {@code r}: the earliest of the top, the apps row or
     * the inputs row that still shows the row holding {@code r} whole.
     */
    private int rowTarget(ScrollView page, Rect r) {
        View content = page.getChildAt(0);
        List<int[]> rows = rowBlocks(content);
        int[] at = null;
        for (int[] b : rows) {
            if (r.centerY() >= b[0]) at = b;
        }
        if (at == null) return 0;
        int range = Math.max(0, content.getHeight() - page.getHeight());
        return Math.max(0, Math.min(range, stopFor(at, rows, page.getHeight())));
    }

    /**
     * Title top and strip bottom of each visible row, in {@code content}'s coordinates. In grid
     * mode every line of tiles is its own block (the first one starting at the row title, or at
     * the grid when the row has no heading), so a grid taller than the screen still scrolls a
     * whole line at a time.
     */
    private List<int[]> rowBlocks(View content) {
        List<int[]> rows = new ArrayList<>();
        for (Row row : new Row[] {inputs, apps}) {
            if (row.scroll.getVisibility() == View.VISIBLE) {
                int start = row.title != null ? topIn(row.title, content) : topIn(row.scroll, content);
                rows.add(new int[] {start, topIn(row.scroll, content) + row.scroll.getHeight()});
            } else if (row.grid.getVisibility() == View.VISIBLE) {
                int gridTop = topIn(row.grid, content), lastTop = Integer.MIN_VALUE;
                int first = row.title != null ? topIn(row.title, content) : gridTop;
                for (int i = 0; i < row.grid.getChildCount(); i++) {
                    View t = row.grid.getChildAt(i);
                    if (t.getTop() == lastTop) continue;
                    int start = lastTop == Integer.MIN_VALUE ? first : gridTop + t.getTop() - dp(6);
                    lastTop = t.getTop();
                    rows.add(new int[] {start, gridTop + t.getBottom() + dp(4)});
                }
            }
        }
        return rows;
    }

    /** The earliest of the top or a row title that shows the whole of {@code at}. */
    private static int stopFor(int[] at, List<int[]> rows, int height) {
        if (height >= at[1]) return 0;
        for (int[] b : rows) {
            if (b[0] <= at[0] && b[0] + height >= at[1]) return b[0];
        }
        return at[0];
    }

    /**
     * When the page is taller than the screen, pads its bottom just enough that every row's stop
     * is reachable. Otherwise the last stop is clamped and the clock above it shows half cut.
     */
    private void fitBottom(ScrollView page) {
        View content = page.getChildAt(0);
        if (content == null || page.getHeight() == 0) return;
        // Keep this in step with the column padding set in build(). A tile that can carry a label
        // reserves that height itself, so measuring from the row edge keeps the same margin with
        // names on or off.
        int base = dp(19.5f), height = page.getHeight();
        int natural = content.getHeight() - content.getPaddingBottom() + base;
        int want = base;
        if (natural > height) {
            int last = 0;
            List<int[]> rows = rowBlocks(content);
            for (int[] b : rows) last = Math.max(last, stopFor(b, rows, height));
            want = base + Math.max(0, last + height - natural);
        }
        if (content.getPaddingBottom() != want) {
            content.setPadding(content.getPaddingLeft(), content.getPaddingTop(), content.getPaddingRight(), want);
        }
    }

    /** {@code v}'s top edge in {@code content}'s coordinates. */
    private static int topIn(View v, View content) {
        int y = 0;
        while (v != null && v != content) {
            y += v.getTop();
            v = (View) v.getParent();
        }
        return y;
    }

    private View build() {
        root = new FrameLayout(this);
        wall = new ImageView(this);
        wall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(wall, new FrameLayout.LayoutParams(MATCH, MATCH));
        shade = new View(this);
        root.addView(shade, new FrameLayout.LayoutParams(MATCH, MATCH));
        tint = new View(this);
        root.addView(tint, new FrameLayout.LayoutParams(MATCH, MATCH));

        ScrollView scroll = new ScrollView(this) {
            /** Scrolls in whole rows: the focused row shows complete, and a row starts at the top edge. */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                return rowTarget(this, r) - getScrollY();
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                fitBottom(this);
            }

            /**
             * Up and down only move the focus; scrolling follows it. Left to ScrollView, a press
             * with nothing focusable that way scrolls half a screen and leaves rows cut.
             */
            @Override
            public boolean executeKeyEvent(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.KEYCODE_DPAD_UP:
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                    case KeyEvent.KEYCODE_PAGE_UP:
                    case KeyEvent.KEYCODE_PAGE_DOWN:
                    case KeyEvent.KEYCODE_SPACE:
                        return false;
                    default:
                        return super.executeKeyEvent(e);
                }
            }
        };
        scroll.setFocusable(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        root.addView(scroll, new FrameLayout.LayoutParams(MATCH, MATCH));

        LinearLayout column = new LinearLayout(this) {
            @Override
            public View focusSearch(View focused, int direction) {
                if (direction == View.FOCUS_DOWN && chips.contains(focused)) {
                    View below = lastApp(apps.tiles());
                    if (below == null) below = apps.tiles().getChildAt(0);
                    if (below != null) return below;
                }
                return super.focusSearch(focused, direction);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        // The focused tile scales 1.08× and grows further sideways than down; rows start a little
        // further in than the clock so the focused first tile lines up with it, and keep a small
        // margin under the last row, whose labels (if shown) sit inside the tile.
        column.setPadding(0, dp(20), 0, 0);
        scroll.addView(column, new FrameLayout.LayoutParams(MATCH, WRAP));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.TOP);
        head.setPadding(dp(28), 0, dp(28), dp(6));
        // Chips scale up when focused; clipToPadding would cut the right-hand one at the padding edge.
        head.setClipChildren(false);
        head.setClipToPadding(false);
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.HORIZONTAL);
        clock = Ui.text(this, CLOCK_SP, Ui.MUTED, Ui.font(this));
        clock.setPadding(0, dp(HEAD_INSET), 0, 0);
        when.addView(clock);
        date = Ui.text(this, 18, Ui.MUTED, Ui.font(this));
        date.setPadding(dp(14), 0, 0, 0);
        when.addView(date);
        head.addView(when, new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(chip("\uF1DE", "Options", v -> { forceFirstAppOnResume = true; startActivity(new Intent(this, Options.class)); }));
        head.addView(chip("\uF013", "Settings", v -> { forceFirstAppOnResume = true; openSettings(); }));
        column.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        // Keeps the inputs and apps rows at the bottom of the screen, apps last.
        column.addView(new View(this), new LinearLayout.LayoutParams(MATCH, 0, 1f));
        inputs = new Row(Catalog.INPUTS, "Inputs", column);
        apps = new Row(Catalog.APPS, null, column);

        saver = new FrameLayout(this);
        saver.setBackgroundColor(Color.BLACK);
        saver.setFocusable(true);
        saver.setClickable(true);
        saver.setOnClickListener(v -> hideSaver());
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        saverClock = Ui.text(this, 64, Ui.MUTED, Ui.font(this));
        saverClock.setGravity(Gravity.CENTER);
        saverDate = Ui.text(this, 24, Ui.MUTED, Ui.font(this));
        saverDate.setGravity(Gravity.CENTER);
        saverDate.setPadding(0, dp(10), 0, 0);
        box.addView(saverClock);
        box.addView(saverDate);
        saver.addView(box, new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER));
        saver.setVisibility(View.GONE);
        root.addView(saver, new FrameLayout.LayoutParams(MATCH, MATCH));
        return root;
    }

    /**
     * The app tile the owner last focused, so leaving the chips comes back to it rather than to
     * whichever tile happens to sit under the chip.
     */
    private View lastApp(ViewGroup tiles) {
        if (lastAppId != null) {
            for (int i = 0; i < tiles.getChildCount(); i++) {
                View v = tiles.getChildAt(i);
                if (v instanceof Tile && lastAppId.equals(((Tile) v).item.id)) return v;
            }
        }
        return null;
    }

    private TextView chip(String glyph, String label, View.OnClickListener click) {
        TextView v = Ui.text(this, 18, Ui.TEXT, Ui.icons(this));
        v.setText(glyph);
        v.setContentDescription(label);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(18), dp(9), dp(18), dp(9));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) -> {
            view.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f)
                    .alpha(has ? 0.6f : 1f).setDuration(110).start();
            if (has) focusBackground(null);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(10);
        lp.topMargin = dp(HEAD_INSET);
        v.setLayoutParams(lp);
        chips.add(v);
        return v;
    }

    private void apply() {
        applied = cfg.version();
        int accent = cfg.accent();
        for (TextView c : chips) Ui.focusStyle(c, accent, 0x0AFFFFFF, dp(18));
        render(apps);
        render(inputs);
        background();
        tick();
        scheduleSaver();
    }

    /** Rebuilds the app list, keeping loaded art for every package except {@code changed}. */
    private void loadApps(String changed) {
        int generation = ++appsGeneration;
        io.execute(() -> {
            List<Item> list = Catalog.apps(this);
            ui.post(() -> {
                if (generation != appsGeneration || isDestroyed()) return;
                for (Item fresh : list) {
                    for (Item old : apps.all) {
                        if (old.id.equals(fresh.id) && !old.id.equals(changed) && old.artRequested) {
                            fresh.banner = old.banner;
                            fresh.icon = old.icon;
                            fresh.wideIcon = old.wideIcon;
                            fresh.artRequested = true;
                            fresh.backgroundColor = old.backgroundColor;
                        }
                    }
                }
                apps.all = list;
                render(apps);
            });
        });
    }

    private void loadInputs() {
        inputs.all = Catalog.inputs(this, tv);
        render(inputs);
    }

    private void render(Row row) {
        if (moving != null && moving.kind.equals(row.kind)) stopMoving();
        row.inset(edge());
        View focused = getCurrentFocus();
        String keep = focused instanceof Tile && ((Tile) focused).kind.equals(row.kind)
                ? ((Tile) focused).item.id : null;
        boolean gridMode = "grid".equals(cfg.get(Config.LAYOUT));
        ViewGroup target = gridMode ? row.grid : row.strip;
        row.strip.removeAllViews();
        row.grid.removeAllViews();

        boolean enabled = !Catalog.INPUTS.equals(row.kind) || "on".equals(cfg.get(Config.INPUTS));
        List<Item> shown = enabled ? Catalog.visible(cfg, row.kind, row.all) : new ArrayList<>();
        int[] size = cardSize();
        int w = size[0], h = Catalog.APPS.equals(row.kind) ? size[1] : Math.round(size[1] * 0.56f);
        String labels = cfg.get(Config.LABELS);
        int accent = cfg.accent();
        int index = 0, restore = -1;
        for (Item it : shown) {
            Tile t = new Tile(this, it, row.kind, Catalog.label(cfg, it), w, h, labels, accent);
            t.setOnClickListener(v -> open((Tile) v));
            t.setOnFocusChangeListener((v, has) -> {
                if (has) {
                    if (Catalog.APPS.equals(((Tile) v).kind)) {
                        lastAppId = ((Tile) v).item.id;
                    }
                    focusBackground(((Tile) v).item);
                }
            });
            t.setOnLongClickListener(v -> {
                menu((Tile) v);
                return true;
            });
            if (gridMode) {
                target.addView(t, new ViewGroup.LayoutParams(WRAP, WRAP));
            } else {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
                lp.rightMargin = dp(18);
                target.addView(t, lp);
            }
            if (it.id.equals(keep)) restore = index;
            index++;
            if (Catalog.APPS.equals(row.kind) && !it.artRequested) loadArt(it);
        }
        row.setVisible(!shown.isEmpty());
        if (restore >= 0) {
            target.getChildAt(restore).requestFocus();
        } else if (keep != null && target.getChildCount() > 0) {
            target.getChildAt(0).requestFocus();
        } else if (getCurrentFocus() == null || !landed) {
            focusFirst();
        }
    }

    private void loadArt(Item it) {
        it.artRequested = true;
        io.execute(() -> {
            Catalog.art(getPackageManager(), it);
            ui.post(() -> {
                ViewGroup tiles = apps.tiles();
                for (int i = 0; i < tiles.getChildCount(); i++) tiles.getChildAt(i).invalidate();
                if (focusedItem() == it) focusBackground(it);
            });
        });
    }

    private Item focusedItem() {
        View focused = getCurrentFocus();
        return focused instanceof Tile ? ((Tile) focused).item : null;
    }

    private void focusBackground(Item it) {
        String mode = cfg.get(Config.BACKGROUND);
        if (!resumed || !"adaptive".equals(mode) && !"adaptive-tint".equals(mode)) return;
        if ("adaptive-tint".equals(mode) && !tintWallpaper) return;
        if (it != null && it.info != null && it.backgroundColor == 0 && !it.colorRequested
                && (it.banner != null || it.icon != null)) {
            it.colorRequested = true;
            colorIo.execute(() -> {
                int color = FocusColor.from(it.banner != null ? it.banner : it.icon);
                ui.post(() -> {
                    it.backgroundColor = color;
                    if (focusedItem() == it) focusBackground(it);
                });
            });
        }
        int color = it == null || it.backgroundColor == 0 ? FocusColor.FALLBACK : it.backgroundColor;
        if ("adaptive-tint".equals(mode)) color = FocusColor.tint(color);
        if (color == targetColor) return;
        targetColor = color;
        stopColorAnimation();
        if (Settings.Global.getFloat(getContentResolver(), "animator_duration_scale", 1f) == 0f) {
            setAdaptiveColor(color);
            return;
        }
        colorAnimation = ValueAnimator.ofArgb(displayedColor, color);
        colorAnimation.setDuration(250);
        colorAnimation.addUpdateListener(a -> setAdaptiveColor((Integer) a.getAnimatedValue()));
        colorAnimation.start();
    }

    private void setAdaptiveColor(int color) {
        displayedColor = color;
        if ("adaptive-tint".equals(backgroundMode)) tint.setBackgroundColor(color);
        else root.setBackgroundColor(color);
    }

    private void stopColorAnimation() {
        if (colorAnimation != null) {
            colorAnimation.cancel();
            colorAnimation = null;
        }
    }

    private int[] cardSize() {
        // The three steps were designed at these sizes; 0.75 makes every app tile a quarter
        // smaller without changing the gaps, the labels or the grid's column maths.
        switch (cfg.get(Config.TILES)) {
            case "small":
                return new int[] {dp(144 * TILE_SCALE), dp(81 * TILE_SCALE)};
            case "large":
                return new int[] {dp(224 * TILE_SCALE), dp(126 * TILE_SCALE)};
            default:
                return new int[] {dp(180 * TILE_SCALE), dp(101 * TILE_SCALE)};
        }
    }

    /**
     * Left edge of a row. A focused tile grows by half its scale-up on each side, so starting the
     * row that much further in puts the focused first tile — the one HOME lands on — on the same
     * line as the clock. Unfocused, it clears the clock by the same small amount.
     */
    private int edge() {
        return dp(28) + Math.round(cardSize()[0] * (Tile.FOCUS_SCALE - 1f) / 2f);
    }

    private void focusFirst() {
        apps.scroll.scrollTo(0, 0);
        inputs.scroll.scrollTo(0, 0);
        View first = apps.tiles().getChildAt(0);
        if (first == null) first = inputs.tiles().getChildAt(0);
        landed = apps.tiles().getChildCount() > 0;
        if (first == null) first = chips.get(0);
        first.requestFocus();
    }

    private void tick() {
        Date now = new Date();
        showTime(clock, now);
        showTime(saverClock, now);
        String day = DateFormat.format("EEEE, d MMMM", now).toString();
        date.setText(day);
        saverDate.setText(day);
        String mode = cfg.get(Config.CLOCKDATE);
        clock.setVisibility("none".equals(mode) ? View.GONE : View.VISIBLE);
        date.setVisibility("both".equals(mode) ? View.VISIBLE : View.GONE);
        saverClock.setVisibility("none".equals(mode) ? View.GONE : View.VISIBLE);
        saverDate.setVisibility("both".equals(mode) ? View.VISIBLE : View.GONE);
        // hourly and time-of-day rotations change while the screen stays open; a no-op otherwise
        background();
    }

    /** The clock in the current format; the 12-hour suffix is smaller than the digits. */
    private void showTime(TextView view, Date now) {
        String mode = cfg.get(Config.CLOCK);
        boolean h24 = "24h".equals(mode) || "auto".equals(mode) && DateFormat.is24HourFormat(this);
        if (h24) {
            view.setText(DateFormat.format("HH:mm", now));
        } else {
            SpannableString half = new SpannableString(" " + DateFormat.format("a", now));
            half.setSpan(new RelativeSizeSpan(0.36f), 0, half.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            view.setText(DateFormat.format("h:mm", now));
            view.append(half);
        }
    }

    private int saverMinutes() {
        try {
            return Integer.parseInt(cfg.get(Config.SAVER));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void scheduleSaver() {
        ui.removeCallbacks(saverTask);
        int minutes = saverMinutes();
        if (!resumed || saverShown || minutes <= 0) return;
        ui.postDelayed(saverTask, minutes * 60_000L);
    }

    private void showSaver() {
        if (saverShown || saver == null) return;
        saverShown = true;
        focusBeforeSaver = getCurrentFocus();
        saver.setAlpha(0f);
        saver.setVisibility(View.VISIBLE);
        saver.animate().alpha(1f).setDuration(500).start();
        saver.requestFocus();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void hideSaver() {
        hideSaver(true);
        scheduleSaver();
    }

    private void hideSaver(boolean restoreFocus) {
        if (!saverShown) return;
        saverShown = false;
        saver.animate().cancel();
        saver.setAlpha(1f);
        saver.setVisibility(View.GONE);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (restoreFocus) {
            if (focusBeforeSaver != null && focusBeforeSaver.isAttachedToWindow()) {
                focusBeforeSaver.requestFocus();
            } else {
                focusFirst();
            }
        }
        focusBeforeSaver = null;
    }

    private void background() {
        String mode = cfg.get(Config.BACKGROUND);
        boolean image = "image".equals(mode) || "adaptive-tint".equals(mode);
        File file = image ? cfg.wallpaper() : null;
        tintWallpaper = "adaptive-tint".equals(mode) && file != null;
        if ("adaptive".equals(mode)) {
            if (!mode.equals(backgroundMode)) {
                stopColorAnimation();
                targetColor = 0;
                root.setBackgroundColor(FocusColor.FALLBACK);
                displayedColor = FocusColor.FALLBACK;
                tint.setBackgroundColor(Color.TRANSPARENT);
            }
            backgroundMode = mode;
            focusBackground(focusedItem());
        } else if ("adaptive-tint".equals(mode)) {
            root.setBackgroundColor(file == null ? cfg.background() : Color.BLACK);
            if (!mode.equals(backgroundMode) || file == null) {
                stopColorAnimation();
                targetColor = 0;
                tint.setBackgroundColor(Color.TRANSPARENT);
                displayedColor = Color.TRANSPARENT;
            }
            backgroundMode = mode;
            if (tintWallpaper) focusBackground(focusedItem());
        } else {
            stopColorAnimation();
            targetColor = 0;
            backgroundMode = mode;
            tint.setBackgroundColor(Color.TRANSPARENT);
            root.setBackgroundColor(cfg.background());
        }
        int dim = Integer.parseInt(cfg.get(Config.DIM));
        shade.setBackgroundColor(file == null ? Color.TRANSPARENT : Color.argb(dim * 255 / 100, 0, 0, 0));
        String key = file == null ? null : file.getPath() + ":" + file.lastModified();
        if (key == null ? wallKey == null : key.equals(wallKey)) return;
        wallKey = key;
        if (file == null) {
            wall.setImageDrawable(null);
            return;
        }
        io.execute(() -> {
            Bitmap b = decode(file, getResources().getDisplayMetrics().widthPixels,
                    getResources().getDisplayMetrics().heightPixels);
            ui.post(() -> {
                if (key.equals(wallKey)) wall.setImageBitmap(b);
            });
        });
    }

    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) {
            o.inSampleSize *= 2;
        }
        o.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /**
     * Fire OS answers the generic settings action with a stub that reopens Amazon's home, which
     * Guard then replaces with ours. The TV settings entry point opens the real settings screen.
     */
    private void openSettings() {
        Intent tvSettings = new Intent(Intent.ACTION_MAIN)
                .addCategory("android.intent.category.LEANBACK_SETTINGS");
        boolean found = tvSettings.resolveActivity(getPackageManager()) != null;
        launch(found ? tvSettings : new Intent(Settings.ACTION_SETTINGS), null);
    }

    private void open(Tile t) {
        if (Catalog.APPS.equals(t.kind)) {
            launch(new Intent(t.item.intent), t.item.id);
            return;
        }
        Intent own = Input.intent(this, cfg, t.item, Catalog.label(cfg, t.item));
        // The built-in screen goes on top of this one in the same task, so BACK returns here and
        // the HOME takeover's singleTask relaunch clears it. NEW_TASK | RESET_TASK_IF_NEEDED on an
        // activity of the task that is already in front only brings that task forward and starts
        // nothing (ActivityStarter.complyActivityFlags), so it is started plainly.
        if (own != null) startActivity(own);
        else launch(new Intent(t.item.intent), null);
    }

    private void launch(Intent i, String pkg) {
        if (Guard.AMAZON.equals(pkg)) Guard.pause();
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, "Could not open that", Toast.LENGTH_SHORT).show();
        }
    }

    private void menu(Tile t) {
        boolean app = Catalog.APPS.equals(t.kind);
        String[] actions = app
                ? new String[] {"Move", "Rename", "Hide", "App info"}
                : new String[] {"Move", "Rename", "Hide"};
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.label(cfg, t.item))
                .setItems(actions, (d, which) -> {
                    switch (which) {
                        case 0:
                            if (t.getParent() == null) break;
                            moving = t;
                            t.setMoving(true);
                            break;
                        case 1:
                            rename(t);
                            break;
                        case 2:
                            Catalog.setShown(cfg, t.kind, t.item, false);
                            render(app ? apps : inputs);
                            Toast.makeText(this, "Hidden. Bring it back from Options.", Toast.LENGTH_SHORT).show();
                            break;
                        default:
                            launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", t.item.id, null)), null);
                    }
                })
                .show();
    }

    private void rename(Tile t) {
        EditText field = new EditText(this);
        field.setSingleLine();
        field.setText(Catalog.label(cfg, t.item));
        field.setSelectAllOnFocus(true);
        FrameLayout box = new FrameLayout(this);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(field);
        String key = "name." + t.item.id;
        AlertDialog d = new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle("Rename")
                .setView(box)
                .setPositiveButton("Save", (dialog, w) -> {
                    String name = field.getText().toString().trim();
                    cfg.set(key, name.equals(t.item.label.toString()) ? null : name);
                    apply();
                })
                .setNeutralButton("Original", (dialog, w) -> {
                    cfg.set(key, null);
                    apply();
                })
                .setNegativeButton("Cancel", null)
                .create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
    }

    private void shift(int by) {
        ViewGroup strip = (ViewGroup) moving.getParent();
        int from = strip.indexOfChild(moving), to = from + by;
        if (to < 0 || to >= strip.getChildCount()) return;
        ViewGroup.LayoutParams lp = moving.getLayoutParams();
        strip.removeViewAt(from);
        strip.addView(moving, to, lp);
        moving.requestFocus();
    }

    private void stopMoving() {
        Tile t = moving;
        moving = null;
        t.setMoving(false);
        ViewGroup strip = (ViewGroup) t.getParent();
        if (strip == null) return;
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < strip.getChildCount(); i++) ids.add(((Tile) strip.getChildAt(i)).item.id);
        Catalog.saveOrder(cfg, t.kind, ids);
        applied = cfg.version();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
