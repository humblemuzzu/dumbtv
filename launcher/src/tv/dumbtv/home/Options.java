package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Every setting is a card in a grid, readable from the sofa: OK steps it forward, a long press
 * steps it back, and the change is saved at once. The arrows move between cards.
 */
public final class Options extends Activity {
    private final List<Runnable> refreshers = new ArrayList<>();
    private Config cfg;
    private int cardW;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        cfg = Config.get(this);

        ScrollView scroll = new ScrollView(this);
        scroll.setFocusable(false);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setBackgroundColor(0xFF0C0C0E);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(48), dp(32), dp(48), dp(40));
        list.setClipChildren(false);
        list.setClipToPadding(false);
        scroll.setClipChildren(false);
        scroll.addView(list);
        // five cards a line: the width left by the margins and Grid's 18 dp gaps
        int columns = 5;
        cardW = (getResources().getDisplayMetrics().widthPixels - dp(96) - dp(18) * (columns - 1)) / columns - 1;

        TextView title = Ui.text(this, 30, Ui.TEXT, Ui.SYSTEM_LIGHT);
        title.setText(R.string.options);
        title.setPadding(0, 0, 0, dp(18));
        list.addView(title);

        Grid settings = new Grid(this);
        for (Config.Choice c : Config.CHOICES) settings.addView(choice(c));
        list.addView(settings);
        // Finding the packs opens every theme app's resources: keep it off the UI thread.
        new Thread(() -> {
            Config.Choice packs = IconPack.choice(IconPack.installed(getPackageManager()),
                    getString(R.string.icon_pack_off), getString(R.string.icon_pack_none));
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                settings.addView(choice(packs));
                refresh();
            });
        }, "icon-packs").start();
        Grid actions = new Grid(this);
        actions.setPadding(0, dp(28), 0, 0);
        actions.addView(action(getString(R.string.next_wallpaper), Icons.NEXT_WALLPAPER, () -> {
            cfg.nextWallpaper();
            refresh();
        }));
        actions.addView(action(getString(R.string.apps_on_home), Icons.APPS, () -> pick(Catalog.APPS)));
        actions.addView(action(getString(R.string.inputs_on_home), Icons.INPUTS, () -> pick(Catalog.INPUTS)));
        actions.addView(action(getString(R.string.reset_layout), Icons.RESET, this::reset));
        actions.addView(action(getString(R.string.export_settings), Icons.EXPORT, this::export));
        actions.addView(action(getString(R.string.open_amazon_home), Icons.AMAZON_HOME, () -> {
            Guard.amazon(this);
            finish();
        }));
        list.addView(actions);

        TextView about = Ui.text(this, 13, Ui.FAINT, Ui.SYSTEM_MEDIUM);
        about.setLineSpacing(0, 1.4f);
        about.setPadding(0, dp(32), 0, 0);
        about.setText(getString(R.string.about, cfg.dir + "/" + Config.FILE, Config.WALLPAPERS,
                "/sdcard/Android/media/" + getPackageName() + "/", Library.FILE, Library.DIR));
        list.addView(about);

        setContentView(scroll);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        cfg.refresh();
        refresh();
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    private View choice(Config.Choice c) {
        LinearLayout row = card();
        TextView name = name();
        row.addView(name);
        TextView value = Ui.text(this, 19, Ui.TEXT, Ui.SYSTEM_MEDIUM);
        fit(value);
        // on/off choices wear a Material switch at the end of their line
        boolean binary = c.values.length == 2 && Arrays.asList(c.values).contains("on")
                && Arrays.asList(c.values).contains("off");
        row.addView(value, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> step(c, 1));
        row.setOnLongClickListener(v -> step(c, -1));
        Runnable show = () -> {
            name.setText(Icons.lead(this, Icons.forChoice(c.key), Text.title(this, c), cfg.accent()));
            if (binary) {
                value.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null,
                        new Toggle(this, "on".equals(cfg.get(c)), cfg.accent()), null);
            }
            String text = Text.name(this, c, cfg.get(c));
            if (c == Config.ACCENT) {
                SpannableString s = new SpannableString("●  " + text);
                s.setSpan(new ForegroundColorSpan(cfg.accent()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                value.setText(s);
            } else {
                value.setText(text);
            }
            boolean off = (c == Config.DIM || c == Config.ROTATE)
                    && !"image".equals(cfg.get(Config.BACKGROUND))
                    && !"adaptive-tint".equals(cfg.get(Config.BACKGROUND))
                    || c == Config.SWITCH && "off".equals(cfg.get(Config.INPUTS))
                    || c == Config.ALL_APPS && "on".equals(cfg.get(Config.LOCK));
            row.setAlpha(off ? 0.4f : 1f);
        };
        refreshers.add(show);
        refreshers.add(() -> style(row));
        return row;
    }

    private boolean step(Config.Choice c, int by) {
        int n = c.values.length;
        // Nothing to switch to, as Icon pack with none installed: keep what is saved, so a pack
        // that comes back is still the chosen one.
        if (n < 2) return true;
        int i = Arrays.asList(c.values).indexOf(cfg.get(c));
        cfg.set(c.key, c.values[((i + by) % n + n) % n]);
        refresh();
        if (c == Config.LOCK && "on".equals(cfg.get(c))) {
            Toast.makeText(this, getString(R.string.lock_on, (int) (Home.UNLOCK_MS / 1000)),
                    Toast.LENGTH_LONG).show();
        }
        return true;
    }

    private View action(String text, String glyph, Runnable run) {
        LinearLayout row = card();
        TextView v = Ui.text(this, 15, Ui.TEXT, Ui.SYSTEM_MEDIUM);
        v.setMaxLines(2);
        v.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(v);
        row.setOnClickListener(x -> run.run());
        refreshers.add(() -> v.setText(Icons.lead(this, glyph, text, cfg.accent())));
        refreshers.add(() -> style(row));
        return row;
    }

    /** A card of one fixed size: Grid lays every child out at the first one's size. */
    private LinearLayout card() {
        int h = dp(78);
        LinearLayout row = new LinearLayout(this) {
            @Override
            protected void onMeasure(int ws, int hs) {
                super.onMeasure(MeasureSpec.makeMeasureSpec(cardW, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
            }
        };
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(16), dp(10), dp(16), dp(10));
        row.setOnFocusChangeListener((v, has) ->
                v.animate().scaleX(has ? 1.05f : 1f).scaleY(has ? 1.05f : 1f).setDuration(110).start());
        return row;
    }

    private void style(View row) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused},
                Ui.round((cfg.accent() & 0x00FFFFFF) | 0x59000000, dp(16)));
        bg.addState(new int[0], Ui.round(0x14FFFFFF, dp(16)));
        row.setBackground(bg);
    }

    private TextView name() {
        TextView v = Ui.text(this, 13, Ui.MUTED, Ui.SYSTEM_MEDIUM);
        fit(v);
        v.setPadding(0, 0, 0, dp(6));
        return v;
    }

    private static void fit(TextView v) {
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
    }

    private void pick(String kind) {
        List<Item> items = Catalog.APPS.equals(kind)
                ? Catalog.apps(this)
                : Catalog.inputs(this, (TvInputManager) getSystemService(Context.TV_INPUT_SERVICE));
        CharSequence[] names = new CharSequence[items.size()];
        boolean[] on = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            names[i] = Catalog.label(cfg, items.get(i));
            on[i] = Catalog.shown(cfg, kind, items.get(i));
        }
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(Catalog.APPS.equals(kind) ? R.string.apps_on_home : R.string.inputs_on_home)
                .setMultiChoiceItems(names, on, (d, which, checked) ->
                        Catalog.setShown(cfg, kind, items.get(which), checked))
                .setPositiveButton(R.string.done, null)
                .show();
    }

    private void export() {
        java.io.File copy = cfg.export();
        Toast.makeText(this, copy == null ? getString(R.string.export_failed)
                : getString(R.string.export_done, copy.getPath()), Toast.LENGTH_LONG).show();
    }

    private void reset() {
        new AlertDialog.Builder(this, Ui.DIALOG)
                .setTitle(R.string.reset_title)
                .setMessage(R.string.reset_message)
                .setPositiveButton(R.string.reset, (d, w) -> cfg.removePrefix("name.", "apps.", "inputs.", IconPack.CHOSEN))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }
}
