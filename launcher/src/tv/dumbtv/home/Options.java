package tv.dumbtv.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Every setting is a row: left/right or OK cycles it, and the change is saved at once. */
public final class Options extends Activity {
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final List<Runnable> refreshers = new ArrayList<>();
    private Config cfg;

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
        list.setPadding(dp(56), dp(40), dp(56), dp(40));
        scroll.addView(list);

        TextView title = Ui.text(this, 30, Ui.TEXT, Ui.LIGHT);
        title.setText(R.string.options);
        title.setPadding(dp(16), 0, 0, dp(20));
        list.addView(title);

        for (Config.Choice c : Config.CHOICES) list.addView(choice(c));
        LinearLayout packRow = new LinearLayout(this);
        packRow.setOrientation(LinearLayout.VERTICAL);
        list.addView(packRow);
        // Finding the packs opens every theme app's resources: keep it off the UI thread.
        new Thread(() -> {
            Config.Choice packs = IconPack.choice(IconPack.installed(getPackageManager()));
            runOnUiThread(() -> {
                if (!isDestroyed()) packRow.addView(choice(packs));
            });
        }, "icon-packs").start();
        list.addView(action(getString(R.string.next_wallpaper), () -> {
            cfg.nextWallpaper();
            refresh();
        }));
        list.addView(gap());
        list.addView(action(getString(R.string.apps_on_home), () -> pick(Catalog.APPS)));
        list.addView(action(getString(R.string.inputs_on_home), () -> pick(Catalog.INPUTS)));
        list.addView(action(getString(R.string.reset_layout), this::reset));
        list.addView(action(getString(R.string.open_amazon_home), () -> {
            Guard.amazon(this);
            finish();
        }));

        TextView about = Ui.text(this, 13, Ui.FAINT, Ui.MEDIUM);
        about.setLineSpacing(0, 1.4f);
        about.setPadding(dp(16), dp(28), 0, 0);
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
        LinearLayout row = row();
        TextView name = label(Text.title(this, c));
        TextView value = Ui.text(this, 16, Ui.MUTED, Ui.MEDIUM);
        value.setGravity(Gravity.END);
        row.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(value, new LinearLayout.LayoutParams(WRAP, WRAP));
        row.setOnClickListener(v -> step(c, 1));
        row.setOnKeyListener((v, code, e) -> {
            if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_LEFT) return step(c, -1);
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) return step(c, 1);
            return false;
        });
        Runnable show = () -> {
            boolean focused = row.isFocused();
            String text = Text.name(this, c, cfg.get(c));
            if (c == Config.ACCENT) {
                SpannableString s = new SpannableString("●  " + text);
                s.setSpan(new ForegroundColorSpan(cfg.accent()), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                value.setText(focused ? "‹  " : "");
                value.append(s);
                if (focused) value.append("  ›");
            } else {
                value.setText(focused ? "‹  " + text + "  ›" : text);
            }
            value.setTextColor(focused ? Ui.TEXT : Ui.MUTED);
            boolean off = (c == Config.DIM || c == Config.ROTATE)
                    && !"image".equals(cfg.get(Config.BACKGROUND))
                    && !"adaptive-tint".equals(cfg.get(Config.BACKGROUND))
                    || c == Config.SWITCH && "off".equals(cfg.get(Config.INPUTS))
                    || c == Config.ALL_APPS && "on".equals(cfg.get(Config.LOCK));
            row.setAlpha(off ? 0.4f : 1f);
        };
        row.setOnFocusChangeListener((v, has) -> show.run());
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

    private View action(String text, Runnable run) {
        LinearLayout row = row();
        row.addView(label(text), new LinearLayout.LayoutParams(0, WRAP, 1f));
        TextView chevron = Ui.text(this, 16, Ui.MUTED, Ui.MEDIUM);
        chevron.setText("›");
        row.addView(chevron);
        row.setOnClickListener(v -> run.run());
        refreshers.add(() -> style(row));
        return row;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(600), WRAP);
        lp.bottomMargin = dp(4);
        row.setLayoutParams(lp);
        return row;
    }

    private void style(View row) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused},
                Ui.round((cfg.accent() & 0x00FFFFFF) | 0x33000000, dp(10)));
        bg.addState(new int[0], Ui.round(0x00000000, dp(10)));
        row.setBackground(bg);
    }

    private TextView label(String text) {
        TextView v = Ui.text(this, 16, Ui.TEXT, Ui.MEDIUM);
        v.setText(text);
        return v;
    }

    private View gap() {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(18)));
        return v;
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
