package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.TransitionDrawable;
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

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final List<TextView> chips = new ArrayList<>();
    private Config cfg;
    private TvInputManager tv;
    private int applied = -1, appsGeneration;
    private boolean resumed, landed;
    /** Grid layout: a top shelf for the focused tile over a blurred backdrop, apps in a grid. */
    private boolean grid;
    private FrameLayout root;
    private ImageView wall, shelfArt;
    private View shade;
    private TextView clock, date, shelfName;
    private Row apps, inputs;
    private Tile moving;
    private Item featured;
    private String wallKey;

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
        final TextView title;
        /** Null for a grid row: the grid scrolls with the page. */
        final HorizontalScrollView scroll;
        /** A row of tiles, or for a grid a column of lines of tiles. */
        final LinearLayout strip;
        final View body;
        final boolean asGrid;
        List<Item> all = new ArrayList<>();

        Row(String kind, String name, LinearLayout parent, boolean asGrid) {
            this.kind = kind;
            this.asGrid = asGrid;
            int edge = grid ? dp(GRID_EDGE) : dp(56), bleed = dp(14);
            title = Ui.text(Home.this, 12, Ui.FAINT, Ui.MEDIUM);
            title.setText(name.toUpperCase(Locale.ROOT));
            title.setLetterSpacing(0.14f);
            title.setPadding(edge, dp(20), edge, dp(2));
            strip = new LinearLayout(Home.this);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            if (asGrid) {
                strip.setOrientation(LinearLayout.VERTICAL);
                strip.setPadding(edge, dp(10), edge, dp(4));
                scroll = null;
                body = strip;
                parent.addView(strip, new LinearLayout.LayoutParams(MATCH, WRAP));
                return;
            }
            strip.setPadding(bleed, dp(12), bleed, dp(8));
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
            scroll.setPadding(edge - bleed, 0, edge - bleed, 0);
            scroll.addView(strip, new FrameLayout.LayoutParams(WRAP, WRAP));
            body = scroll;
            parent.addView(title, new LinearLayout.LayoutParams(MATCH, WRAP));
            parent.addView(scroll, new LinearLayout.LayoutParams(MATCH, WRAP));
        }

        void setVisible(boolean on) {
            int v = on ? View.VISIBLE : View.GONE;
            title.setVisibility(asGrid ? View.GONE : v);
            body.setVisibility(v);
        }

        /** Every tile, in order. */
        List<Tile> tiles() {
            List<Tile> out = new ArrayList<>();
            if (!asGrid) {
                for (int i = 0; i < strip.getChildCount(); i++) out.add((Tile) strip.getChildAt(i));
                return out;
            }
            for (int l = 0; l < strip.getChildCount(); l++) {
                ViewGroup line = (ViewGroup) strip.getChildAt(l);
                for (int i = 0; i < line.getChildCount(); i++) out.add((Tile) line.getChildAt(i));
            }
            return out;
        }

        /** The tiles a press of DOWN from the header can land on. */
        LinearLayout firstLine() {
            return asGrid && strip.getChildCount() > 0 ? (LinearLayout) strip.getChildAt(0) : strip;
        }

        /** Lays {@code tiles} out again: one strip, or lines of {@link #gridCols} for a grid. */
        void place(List<Tile> tiles) {
            for (Tile t : tiles) {
                if (t.getParent() != null) ((ViewGroup) t.getParent()).removeView(t);
            }
            strip.removeAllViews();
            int gap = asGrid ? dp(GRID_GAP) : dp(18), cols = asGrid ? gridCols() : Integer.MAX_VALUE;
            LinearLayout line = strip;
            for (int i = 0; i < tiles.size(); i++) {
                if (asGrid && i % cols == 0) {
                    line = new LinearLayout(Home.this);
                    line.setClipChildren(false);
                    line.setClipToPadding(false);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
                    lp.bottomMargin = dp(12);
                    strip.addView(line, lp);
                }
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
                lp.rightMargin = gap;
                line.addView(tiles.get(i), lp);
            }
        }
    }

    private static final int GRID_EDGE = 48, GRID_GAP = 28;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Future<List<Item>> catalog = io.submit(() -> Catalog.apps(this));
        cfg = Config.get(this);
        grid = "grid".equals(cfg.get(Config.LAYOUT));
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
        int fit = grid ? gridCols() * 3
                : getResources().getDisplayMetrics().widthPixels / (cardSize()[0] + dp(18)) + 1;
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
    }

    @Override
    public void onEnterAnimationComplete() {
        Guard.shown();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        unregisterReceiver(time);
        if (moving != null) stopMoving();
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        if (tv != null) tv.unregisterCallback(inputWatch);
        io.shutdownNow();
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
                    if (rowOf(moving).asGrid) shift(-gridCols());
                    break;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (rowOf(moving).asGrid) shift(gridCols());
                    break;
                default:
                    stopMoving();
            }
        }
        return true;
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (code == KeyEvent.KEYCODE_MENU) {
            View f = getCurrentFocus();
            if (f instanceof Tile) menu((Tile) f);
            else startActivity(new Intent(this, Options.class));
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

    /** Title top and strip bottom of each visible row, in {@code content}'s coordinates. */
    private List<int[]> rowBlocks(View content) {
        List<int[]> rows = new ArrayList<>();
        for (Row row : new Row[] {apps, inputs}) {
            if (row.body.getVisibility() != View.VISIBLE) continue;
            rows.add(new int[] {topIn(row.title, content), topIn(row.body, content) + row.body.getHeight()});
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
        int base = dp(32), height = page.getHeight();
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

    /**
     * Grid layout: the shelf stays in view while the focus is on the header or the first line
     * of apps; deeper down, the focused line sits a third of the way down the screen.
     */
    private int gridTarget(ScrollView page, Rect r) {
        View content = page.getChildAt(0);
        int range = Math.max(0, content.getHeight() - page.getHeight());
        LinearLayout first = apps.firstLine();
        int firstBottom = first == apps.strip ? 0 : topIn(first, content) + first.getHeight();
        if (r.centerY() <= firstBottom) return 0;
        return Math.max(0, Math.min(range, r.top - page.getHeight() / 3));
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

        ScrollView scroll = new ScrollView(this) {
            /** Scrolls in whole rows: the focused row shows complete, and a row starts at the top edge. */
            @Override
            protected int computeScrollDeltaToGetChildRectOnScreen(Rect r) {
                return (grid ? gridTarget(this, r) : rowTarget(this, r)) - getScrollY();
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                if (!grid) fitBottom(this);
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
                    View below = nearest(apps.firstLine(), focused);
                    if (below != null) return below;
                }
                return super.focusSearch(focused, direction);
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setPadding(0, dp(grid ? 24 : 40), 0, dp(32));
        scroll.addView(column, new FrameLayout.LayoutParams(MATCH, WRAP));

        if (grid) {
            buildGrid(column);
            return root;
        }

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.TOP);
        head.setPadding(dp(56), 0, dp(56), dp(12));
        LinearLayout when = new LinearLayout(this);
        when.setOrientation(LinearLayout.VERTICAL);
        clock = Ui.text(this, 52, Ui.TEXT, Ui.LIGHT);
        date = Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM);
        date.setPadding(dp(2), dp(8), 0, 0);
        when.addView(clock);
        when.addView(date);
        head.addView(when, new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(chip("Options", v -> startActivity(new Intent(this, Options.class))));
        head.addView(chip("Settings", v -> openSettings()));
        column.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        apps = new Row(Catalog.APPS, "Apps", column, false);
        inputs = new Row(Catalog.INPUTS, "Inputs", column, false);
        return root;
    }

    /**
     * Header with the time at the top right, then the top shelf: the focused tile's art large,
     * its name beside it, over a blur of the same art. Apps in a grid below, inputs last.
     */
    private void buildGrid(LinearLayout column) {
        int edge = dp(GRID_EDGE);
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(edge, 0, edge, 0);
        clock = Ui.text(this, 24, Ui.TEXT, Ui.MEDIUM);
        date = Ui.text(this, 15, Ui.MUTED, Ui.MEDIUM);
        date.setVisibility(View.GONE);
        head.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(WRAP, WRAP);
        at.rightMargin = dp(8);
        head.addView(clock, at);
        head.addView(chip("Options", v -> startActivity(new Intent(this, Options.class))));
        head.addView(chip("Settings", v -> openSettings()));
        column.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        LinearLayout shelf = new LinearLayout(this);
        shelf.setGravity(Gravity.BOTTOM);
        shelf.setClipChildren(false);
        shelf.setClipToPadding(false);
        shelf.setPadding(edge, 0, edge, dp(18));
        int artW = screenW * 30 / 100;
        shelfArt = new ImageView(this);
        shelfArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
        shelfArt.setBackground(Ui.round(Ui.SURFACE, dp(14)));
        shelfArt.setClipToOutline(true);
        shelfArt.setElevation(dp(16));
        shelf.addView(shelfArt, new LinearLayout.LayoutParams(artW, artW * 9 / 16));
        shelfName = Ui.text(this, 34, Ui.TEXT, Ui.LIGHT);
        shelfName.setSingleLine();
        shelfName.setPadding(dp(28), 0, 0, dp(4));
        shelf.addView(shelfName, new LinearLayout.LayoutParams(0, WRAP, 1f));
        column.addView(shelf, new LinearLayout.LayoutParams(MATCH, screenH * 40 / 100));

        apps = new Row(Catalog.APPS, "Apps", column, true);
        inputs = new Row(Catalog.INPUTS, "Inputs", column, false);
    }

    /** Puts {@code t} on the top shelf and blurs its art into the backdrop. */
    private void feature(Tile t) {
        if (!grid) return;
        featured = t.item;
        shelfName.setText(Catalog.label(cfg, t.item));
        Drawable art = t.item.banner != null ? t.item.banner : t.item.icon;
        boolean banner = t.item.banner != null;
        if (art == null) {
            shelfArt.setVisibility(Catalog.APPS.equals(t.kind) ? View.INVISIBLE : View.GONE);
        } else {
            // A copy: the tile sets bounds on its own drawable at every draw.
            Drawable.ConstantState cs = art.getConstantState();
            shelfArt.setImageDrawable(cs != null ? cs.newDrawable(getResources()).mutate() : art);
            shelfArt.setScaleType(banner ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.CENTER_INSIDE);
            int pad = banner ? 0 : dp(28);
            shelfArt.setPadding(pad, pad, pad, pad);
            shelfArt.setVisibility(View.VISIBLE);
        }
        backdrop(art);
    }

    /** A 16×9 copy of {@code art} scaled up with filtering: a cheap, soft blur. */
    private void backdrop(Drawable art) {
        if (wallKey != null) return;
        Drawable next;
        if (art == null) {
            next = new ColorDrawable(cfg.background());
        } else {
            Bitmap b = Bitmap.createBitmap(16, 9, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            c.drawColor(cfg.background());
            Drawable copy = art.getConstantState() != null
                    ? art.getConstantState().newDrawable(getResources()).mutate() : art;
            Rect keep = copy.copyBounds();
            copy.setBounds(0, 0, 16, 9);
            copy.draw(c);
            copy.setBounds(keep);
            BitmapDrawable d = new BitmapDrawable(getResources(), b);
            d.setFilterBitmap(true);
            next = d;
        }
        Drawable now = wall.getDrawable();
        if (now instanceof TransitionDrawable) now = ((TransitionDrawable) now).getDrawable(1);
        if (now == null) {
            wall.setImageDrawable(next);
            return;
        }
        TransitionDrawable fade = new TransitionDrawable(new Drawable[] {now, next});
        fade.setCrossFadeEnabled(true);
        wall.setImageDrawable(fade);
        fade.startTransition(280);
    }

    /** Darkens the backdrop toward the bottom, where the grid sits. */
    private static Drawable gridShade() {
        return new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[] {0x33000000, 0x8C000000, 0xE6000000, 0xF7000000});
    }

    private int gridCols() {
        switch (cfg.get(Config.TILES)) {
            case "small":
                return 6;
            case "large":
                return 4;
            default:
                return 5;
        }
    }

    /** Card size that fits {@link #gridCols} across the screen. */
    private int[] gridCard() {
        int cols = gridCols();
        int w = (getResources().getDisplayMetrics().widthPixels - 2 * dp(GRID_EDGE)
                - (cols - 1) * dp(GRID_GAP)) / cols;
        return new int[] {w, w * 9 / 16};
    }

    /** The child of {@code strip} horizontally closest to {@code from}, or null when empty. */
    private static View nearest(LinearLayout strip, View from) {
        int[] at = new int[2];
        from.getLocationOnScreen(at);
        int x = at[0] + from.getWidth() / 2;
        View best = null;
        int bestGap = Integer.MAX_VALUE;
        for (int i = 0; i < strip.getChildCount(); i++) {
            View c = strip.getChildAt(i);
            c.getLocationOnScreen(at);
            int gap = Math.abs(at[0] + c.getWidth() / 2 - x);
            if (gap < bestGap) {
                bestGap = gap;
                best = c;
            }
        }
        return best;
    }

    private TextView chip(String label, View.OnClickListener click) {
        TextView v = Ui.text(this, 14, Ui.TEXT, Ui.MEDIUM);
        v.setText(label);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(18), dp(9), dp(18), dp(9));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        v.setOnFocusChangeListener((view, has) ->
                view.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(110).start());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(10);
        lp.topMargin = dp(6);
        v.setLayoutParams(lp);
        chips.add(v);
        return v;
    }

    private void apply() {
        if (grid != "grid".equals(cfg.get(Config.LAYOUT))) {
            recreate();
            return;
        }
        applied = cfg.version();
        int accent = cfg.accent();
        for (TextView c : chips) Ui.focusStyle(c, accent, 0x14FFFFFF, dp(18));
        render(apps);
        render(inputs);
        background();
        tick();
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
                            fresh.artRequested = true;
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
        View focused = getCurrentFocus();
        String keep = focused instanceof Tile && ((Tile) focused).kind.equals(row.kind)
                ? ((Tile) focused).item.id : null;

        boolean enabled = !Catalog.INPUTS.equals(row.kind) || "on".equals(cfg.get(Config.INPUTS));
        List<Item> shown = enabled ? Catalog.visible(cfg, row.kind, row.all) : new ArrayList<>();
        int[] size = grid ? gridCard() : cardSize();
        int w = size[0], h = Catalog.APPS.equals(row.kind) ? size[1] : Math.round(size[1] * 0.56f);
        String labels = cfg.get(Config.LABELS);
        int accent = cfg.accent();
        int index = 0, restore = -1;
        List<Tile> tiles = new ArrayList<>();
        for (Item it : shown) {
            Tile t = new Tile(this, it, row.kind, Catalog.label(cfg, it), w, h, labels, accent, grid);
            t.setOnClickListener(v -> open((Tile) v));
            t.setOnLongClickListener(v -> {
                menu((Tile) v);
                return true;
            });
            t.setOnFocusChangeListener((v, has) -> {
                if (has) feature((Tile) v);
            });
            tiles.add(t);
            if (it.id.equals(keep)) restore = index;
            index++;
            if (Catalog.APPS.equals(row.kind) && !it.artRequested) loadArt(it);
        }
        row.place(tiles);
        row.setVisible(!shown.isEmpty());
        if (restore >= 0) {
            tiles.get(restore).requestFocus();
        } else if (keep != null && !tiles.isEmpty()) {
            tiles.get(0).requestFocus();
        } else if (getCurrentFocus() == null || !landed) {
            focusFirst();
        }
    }

    private void loadArt(Item it) {
        it.artRequested = true;
        io.execute(() -> {
            Catalog.art(getPackageManager(), it);
            ui.post(() -> {
                for (Tile t : apps.tiles()) {
                    t.invalidate();
                    if (t.item == it && it == featured && t.isFocused()) feature(t);
                }
            });
        });
    }

    private int[] cardSize() {
        switch (cfg.get(Config.TILES)) {
            case "small":
                return new int[] {dp(144), dp(81)};
            case "large":
                return new int[] {dp(224), dp(126)};
            default:
                return new int[] {dp(180), dp(101)};
        }
    }

    private void focusFirst() {
        if (apps.scroll != null) apps.scroll.scrollTo(0, 0);
        inputs.scroll.scrollTo(0, 0);
        List<Tile> appTiles = apps.tiles();
        View first = appTiles.isEmpty() ? inputs.strip.getChildAt(0) : appTiles.get(0);
        landed = !appTiles.isEmpty();
        if (first == null) first = chips.get(0);
        first.requestFocus();
    }

    private void tick() {
        String mode = cfg.get(Config.CLOCK);
        boolean h24 = "24h".equals(mode) || "auto".equals(mode) && DateFormat.is24HourFormat(this);
        Date now = new Date();
        if (h24) {
            clock.setText(DateFormat.format("HH:mm", now));
        } else {
            SpannableString half = new SpannableString(" " + DateFormat.format("a", now));
            half.setSpan(new RelativeSizeSpan(0.36f), 0, half.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            clock.setText(DateFormat.format("h:mm", now));
            clock.append(half);
        }
        Locale locale = Locale.getDefault();
        date.setText(DateFormat.format(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), now));
        // hourly and time-of-day rotations change while the screen stays open; a no-op otherwise
        background();
    }

    private void background() {
        boolean image = "image".equals(cfg.get(Config.BACKGROUND));
        File file = image ? cfg.wallpaper() : null;
        root.setBackgroundColor(cfg.background());
        if (grid && file == null) {
            shade.setBackground(gridShade());
            if (wallKey != null) {
                wallKey = null;
                wall.setImageDrawable(null);
                View f = getCurrentFocus();
                if (f instanceof Tile) feature((Tile) f);
            }
            return;
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
        launch(new Intent(t.item.intent), Catalog.APPS.equals(t.kind) ? t.item.id : null);
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

    private Row rowOf(Tile t) {
        return Catalog.APPS.equals(t.kind) ? apps : inputs;
    }

    private void shift(int by) {
        Row row = rowOf(moving);
        List<Tile> tiles = row.tiles();
        int from = tiles.indexOf(moving), to = from + by;
        if (from < 0 || to < 0 || to >= tiles.size()) return;
        tiles.remove(from);
        tiles.add(to, moving);
        row.place(tiles);
        moving.requestFocus();
    }

    private void stopMoving() {
        Tile t = moving;
        moving = null;
        t.setMoving(false);
        if (t.getParent() == null) return;
        List<String> ids = new ArrayList<>();
        for (Tile each : rowOf(t).tiles()) ids.add(each.item.id);
        Catalog.saveOrder(cfg, t.kind, ids);
        applied = cfg.version();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
