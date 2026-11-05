package tv.dumbtv.home;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Picks one app's icon from the icon pack chosen in Options. The choice is saved as
 * {@code icon.<app>=<pack>/<drawable>} in home.conf and only counts while that pack is the chosen
 * one; Automatic removes it, App's own picture keeps the app's art whatever the pack holds.
 */
public final class IconPicker extends Activity {
    static final String APP = "app", LABEL = "label";
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;

    /** One cell: a pack's picture, or Automatic / App's own picture (no pack). */
    private static final class Entry {
        final String value, title, match;
        final IconPack pack;
        final String drawable;

        Entry(String value, String title, IconPack pack, String drawable) {
            this.value = value;
            this.title = title;
            this.pack = pack;
            this.drawable = drawable;
            this.match = IconMatch.squash(title);
        }
    }

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Drawable> cache = new LruCache<>(120);
    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    private Config cfg;
    private String app, label, pack, current;
    private TextView status;
    private GridView grid;
    private BaseAdapter adapter;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);
        app = getIntent().getStringExtra(APP);
        label = getIntent().getStringExtra(LABEL);
        if (app == null) {
            finish();
            return;
        }
        if (label == null) label = app;
        pack = cfg.get(IconPack.KEY, IconPack.OFF);
        current = cfg.get(IconPack.CHOSEN + app, null);
        // A pick from another pack does not count now, so Automatic is what this tile shows.
        if (current != null && !IconPack.OWN.equals(current) && IconMatch.picked(current, pack) == null) current = null;
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF0C0C0E);
        root.setPadding(dp(56), dp(32), dp(56), 0);

        TextView title = Ui.text(this, 26, Ui.TEXT, Ui.LIGHT);
        title.setText(getString(R.string.icon_for, label));
        root.addView(title);

        EditText filter = new EditText(this);
        filter.setSingleLine();
        filter.setHint(R.string.icon_filter);
        filter.setTextColor(Ui.TEXT);
        filter.setHintTextColor(Ui.FAINT);
        filter.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void afterTextChanged(Editable s) {
                show(s.toString());
            }
        });
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(dp(420), WRAP);
        fl.topMargin = dp(12);
        root.addView(filter, fl);

        status = Ui.text(this, 13, Ui.FAINT, Ui.MEDIUM);
        status.setText(R.string.icon_reading);
        status.setPadding(0, dp(6), 0, dp(10));
        root.addView(status);

        grid = new GridView(this);
        grid.setNumColumns(GridView.AUTO_FIT);
        grid.setColumnWidth(dp(196));
        grid.setHorizontalSpacing(dp(12));
        grid.setVerticalSpacing(dp(12));
        grid.setStretchMode(GridView.STRETCH_SPACING);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(4), 0, dp(32));
        // The grid's own selector would frame the name under the picture too: each card draws
        // the ring instead, from the selected state the grid gives its cell.
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setOnFocusChangeListener((v, has) -> {
            for (int i = 0; i < grid.getChildCount(); i++) ring(grid.getChildAt(i));
        });
        adapter = new Cells();
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> choose(shown.get(position)));
        root.addView(grid, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        setContentView(root);
        grid.requestFocus();
        io.execute(this::load);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    /** Reads the chosen pack's names; pictures are only decoded as their cells appear. */
    private void load() {
        IconPack chosen = IconPack.load(getPackageManager(), pack);
        List<Entry> found = new ArrayList<>();
        if (chosen != null) {
            for (String name : chosen.names()) {
                found.add(new Entry(chosen.pkg + "/" + name, name.replace('_', ' '), chosen, name));
            }
        }
        ui.post(() -> {
            if (isDestroyed()) return;
            all.clear();
            all.add(new Entry(null, getString(R.string.icon_automatic), null, null));
            all.add(new Entry(IconPack.OWN, getString(R.string.icon_own), null, null));
            all.addAll(found);
            show("");
            for (int i = 0; i < shown.size(); i++) {
                if (TextUtils.equals(shown.get(i).value, current)) grid.setSelection(i);
            }
            grid.requestFocus();
        });
    }

    /**
     * Narrows the cells to names containing {@code query}. Without one, pictures whose name
     * matches the app's come first, after Automatic and App's own picture.
     */
    private void show(String query) {
        String q = IconMatch.squash(query);
        String name = IconMatch.squash(label);
        shown.clear();
        List<Entry> rest = new ArrayList<>();
        for (Entry e : all) {
            if (!q.isEmpty()) {
                if (e.pack != null && e.match.contains(q)) shown.add(e);
            } else if (e.pack == null || IconMatch.likeApp(e.match, name)) {
                shown.add(e);
            } else {
                rest.add(e);
            }
        }
        shown.addAll(rest);
        int pictures = all.size() - 2;
        status.setText(all.isEmpty() ? getString(R.string.icon_reading)
                : pictures <= 0 ? getString(R.string.icon_no_pack)
                : !q.isEmpty() ? getString(R.string.icon_count_of, shown.size(), pictures)
                : getString(R.string.icon_count, pictures));
        adapter.notifyDataSetChanged();
    }

    private void choose(Entry e) {
        cfg.set(IconPack.CHOSEN + app, e.value);
        finish();
    }

    /** The ring only shows while the grid has focus, not while the filter is being typed in. */
    private void ring(View cell) {
        FrameLayout card = (FrameLayout) ((LinearLayout) cell).getChildAt(0);
        card.getForeground().setAlpha(grid.hasFocus() ? 255 : 0);
        card.invalidate();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private final class Cells extends BaseAdapter {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) view = cell();
            ring(view);
            Entry e = shown.get(position);
            FrameLayout card = (FrameLayout) ((LinearLayout) view).getChildAt(0);
            ImageView image = (ImageView) card.getChildAt(0);
            TextView text = (TextView) card.getChildAt(1);
            TextView name = (TextView) ((LinearLayout) view).getChildAt(1);
            boolean picked = TextUtils.equals(e.value, current);
            name.setText(picked ? "✓ " + e.title : e.title);
            name.setTextColor(picked ? cfg.accent() : Ui.MUTED);
            image.setTag(e.value);
            if (e.pack == null) {
                image.setImageDrawable(null);
                text.setText(e.title);
                text.setVisibility(View.VISIBLE);
                return view;
            }
            text.setVisibility(View.GONE);
            Drawable d = cache.get(e.value);
            image.setImageDrawable(d);
            if (d == null) {
                io.execute(() -> {
                    Drawable loaded = e.pack.picture(e.drawable);
                    ui.post(() -> {
                        if (loaded != null) cache.put(e.value, loaded);
                        if (e.value.equals(image.getTag())) image.setImageDrawable(loaded);
                    });
                });
            }
            return view;
        }

        private View cell() {
            LinearLayout cell = new LinearLayout(IconPicker.this);
            cell.setOrientation(LinearLayout.VERTICAL);
            FrameLayout card = new FrameLayout(IconPicker.this);
            card.setBackground(Ui.round(Ui.SURFACE, dp(10)));
            card.setDuplicateParentStateEnabled(true);
            GradientDrawable ring = new GradientDrawable();
            ring.setCornerRadius(dp(10));
            ring.setStroke(dp(3), cfg.accent());
            StateListDrawable focus = new StateListDrawable();
            focus.addState(new int[] {android.R.attr.state_selected}, ring);
            card.setForeground(focus);
            ImageView image = new ImageView(IconPicker.this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(image, new FrameLayout.LayoutParams(MATCH, MATCH));
            TextView text = Ui.text(IconPicker.this, 15, Ui.TEXT, Ui.MEDIUM);
            text.setGravity(Gravity.CENTER);
            card.addView(text, new FrameLayout.LayoutParams(MATCH, MATCH));
            cell.addView(card, new LinearLayout.LayoutParams(MATCH, dp(108)));
            TextView name = Ui.text(IconPicker.this, 12, Ui.MUTED, Ui.MEDIUM);
            name.setSingleLine();
            name.setEllipsize(TextUtils.TruncateAt.END);
            name.setPadding(dp(4), dp(4), dp(4), 0);
            cell.addView(name, new LinearLayout.LayoutParams(MATCH, WRAP));
            return cell;
        }
    }
}
