package tv.dumbtv.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * The library banner: a wide picture of a title the owner picked, cross-fading to the next one
 * every few seconds with a slow zoom. OK opens the title in its app, left and right step
 * through the titles. Only the picture on screen and the one fading in are kept in memory.
 */
final class Hero extends FrameLayout {
    static final long INTERVAL_MS = 8000, AFTER_KEY_MS = 15000, FADE_MS = 900;
    private static final float ZOOM = 1.08f;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler ui;
    private final ExecutorService io;
    private final ImageView[] images = new ImageView[2];
    private final ObjectAnimator[] zooms = new ObjectAnimator[2];
    private final TextView title, sub;
    private final LinearLayout dots;
    private final LinearLayout caption;
    private final GradientDrawable ring = new GradientDrawable();
    private List<Library.Entry> entries = new ArrayList<>();
    private String entriesKey = "";
    private int index, top = -1, generation;
    private boolean running;
    private ObjectAnimator fade;

    private final Runnable advance = new Runnable() {
        @Override
        public void run() {
            if (!running || entries.size() < 2) return;
            show(index + 1, true);
            ui.postDelayed(this, INTERVAL_MS);
        }
    };

    Hero(Context c, Handler ui, ExecutorService io) {
        super(c);
        this.ui = ui;
        this.io = io;
        setFocusable(true);
        setClickable(true);
        setBackgroundColor(Ui.SURFACE);
        final float radius = dp(14);
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        setClipToOutline(true);

        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(c);
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            v.setAlpha(0f);
            images[i] = v;
            addView(v, new LayoutParams(MATCH, MATCH));
        }

        View side = new View(c);
        side.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xD0000000, 0x60000000, 0x00000000}));
        addView(side, new LayoutParams(MATCH, MATCH));
        View bottom = new View(c);
        bottom.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[] {0xB0000000, 0x00000000}));
        addView(bottom, new LayoutParams(MATCH, MATCH));

        caption = new LinearLayout(c);
        caption.setOrientation(LinearLayout.VERTICAL);
        title = Ui.text(c, 28, Ui.TEXT, Ui.MEDIUM);
        title.setSingleLine();
        title.setShadowLayer(dp(6), 0, 0, 0x99000000);
        sub = Ui.text(c, 13, Ui.MUTED, Ui.MEDIUM);
        sub.setLetterSpacing(0.06f);
        sub.setPadding(0, dp(8), 0, 0);
        caption.addView(title, new LinearLayout.LayoutParams(WRAP, WRAP));
        caption.addView(sub, new LinearLayout.LayoutParams(WRAP, WRAP));
        LayoutParams capLp = new LayoutParams(dp(520), WRAP, Gravity.BOTTOM | Gravity.START);
        capLp.leftMargin = dp(28);
        capLp.bottomMargin = dp(22);
        addView(caption, capLp);

        dots = new LinearLayout(c);
        LayoutParams dotLp = new LayoutParams(WRAP, WRAP, Gravity.BOTTOM | Gravity.END);
        dotLp.rightMargin = dp(24);
        dotLp.bottomMargin = dp(26);
        addView(dots, dotLp);

        ring.setColor(0x00000000);
        ring.setCornerRadius(radius);
        setVisibility(GONE);
    }

    void setAccent(int accent) {
        ring.setStroke(dp(3), accent);
        refreshDots();
        invalidate();
    }

    /** Swaps in a new list; nothing reloads when the titles and pictures are the same. */
    void setEntries(List<Library.Entry> list) {
        StringBuilder k = new StringBuilder();
        for (Library.Entry e : list) k.append(e.key()).append('\n');
        String key = k.toString();
        if (key.equals(entriesKey)) return;
        entriesKey = key;
        entries = list;
        generation++;
        rebuildDots();
        if (list.isEmpty()) {
            setVisibility(GONE);
            for (ImageView v : images) v.setImageDrawable(null);
            top = -1;
            return;
        }
        setVisibility(VISIBLE);
        index = 0;
        show(0, false);
        reschedule(INTERVAL_MS);
    }

    boolean hasEntries() {
        return !entries.isEmpty();
    }

    void start() {
        running = true;
        reschedule(INTERVAL_MS);
        if (top >= 0) zoom(top);
    }

    void stop() {
        running = false;
        ui.removeCallbacks(advance);
        for (ObjectAnimator z : zooms) if (z != null) z.cancel();
    }

    void openCurrent() {
        if (entries.isEmpty()) return;
        Library.open(getContext(), entries.get(index));
    }

    private void reschedule(long delay) {
        ui.removeCallbacks(advance);
        if (running && entries.size() > 1) ui.postDelayed(advance, delay);
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent e) {
        if (entries.size() > 1
                && (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            show(index + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1), true);
            reschedule(AFTER_KEY_MS);
            return true;
        }
        return super.onKeyDown(code, e);
    }

    @Override
    protected void onFocusChanged(boolean has, int direction, Rect previous) {
        super.onFocusChanged(has, direction, previous);
        setForeground(has ? ring : null);
        animate().scaleX(has ? 1.015f : 1f).scaleY(has ? 1.015f : 1f).setDuration(140).start();
        updateCaption();
    }

    private void show(int i, boolean animate) {
        int n = entries.size();
        if (n == 0) return;
        index = ((i % n) + n) % n;
        final Library.Entry e = entries.get(index);
        final int g = ++generation;
        final int w = Math.max(getWidth(), getResources().getDisplayMetrics().widthPixels - dp(112));
        final int h = Math.max(getHeight(), dp(220));
        updateCaption();
        refreshDots();
        try {
            io.execute(() -> {
                final Bitmap b = decode(e.file, w, h);
                ui.post(() -> {
                    if (g == generation) present(b, animate);
                });
            });
        } catch (RejectedExecutionException closing) {
            // the activity is going away
        }
    }

    private void present(Bitmap b, boolean animate) {
        int next = top < 0 ? 0 : 1 - top;
        final ImageView in = images[next];
        final ImageView out = top < 0 ? null : images[top];
        in.setImageBitmap(b);
        // The incoming picture is drawn over the outgoing one while it fades in.
        removeView(in);
        addView(in, 1, new LayoutParams(MATCH, MATCH));
        if (out != null && indexOfChild(out) != 0) {
            removeView(out);
            addView(out, 0, new LayoutParams(MATCH, MATCH));
        }
        top = next;
        if (fade != null) fade.cancel();
        if (!animate || out == null) {
            in.setAlpha(1f);
            if (out != null) {
                out.setAlpha(0f);
                out.setImageDrawable(null);
            }
        } else {
            // A fade cut short by a quick key press leaves the picture below half see-through.
            out.setAlpha(1f);
            in.setAlpha(0f);
            fade = ObjectAnimator.ofFloat(in, View.ALPHA, 0f, 1f);
            fade.setDuration(FADE_MS);
            fade.setInterpolator(new DecelerateInterpolator());
            fade.addListener(new AnimatorListenerAdapter() {
                private boolean cancelled;

                @Override
                public void onAnimationCancel(Animator a) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator a) {
                    if (cancelled) return;
                    out.setAlpha(0f);
                    out.setImageDrawable(null);
                    if (zooms[1 - indexOf(in)] != null) zooms[1 - indexOf(in)].cancel();
                }
            });
            fade.start();
        }
        zoom(next);
    }

    private int indexOf(ImageView v) {
        return v == images[0] ? 0 : 1;
    }

    /** The slow push-in on the picture that is showing, alternating its direction. */
    private void zoom(int i) {
        ImageView v = images[i];
        if (zooms[i] != null) zooms[i].cancel();
        v.setPivotX(index % 2 == 0 ? v.getWidth() * 0.35f : v.getWidth() * 0.65f);
        v.setPivotY(v.getHeight() * 0.5f);
        if (!running) {
            v.setScaleX(1f);
            v.setScaleY(1f);
            return;
        }
        ObjectAnimator z = ObjectAnimator.ofPropertyValuesHolder(v,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, ZOOM),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, ZOOM));
        z.setDuration(INTERVAL_MS + FADE_MS * 2);
        z.setInterpolator(new LinearInterpolator());
        zooms[i] = z;
        z.start();
    }

    private void updateCaption() {
        if (entries.isEmpty()) return;
        Library.Entry e = entries.get(index);
        title.setText(e.title);
        String app = e.appLabel == null ? e.pkg : e.appLabel.toString();
        String watch = getContext().getString(R.string.library_watch);
        String hint = isFocused() ? (entries.size() > 1
                ? "   ·   " + watch + "   ·   " + getContext().getString(R.string.library_more)
                : "   ·   " + watch) : "";
        sub.setText(app.toUpperCase(java.util.Locale.ROOT) + hint);
        caption.setAlpha(0f);
        caption.animate().alpha(1f).setDuration(350).start();
    }

    private void rebuildDots() {
        dots.removeAllViews();
        if (entries.size() < 2) return;
        for (int i = 0; i < entries.size(); i++) {
            View d = new View(getContext());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(7), dp(7));
            lp.leftMargin = dp(7);
            dots.addView(d, lp);
        }
        refreshDots();
    }

    private void refreshDots() {
        for (int i = 0; i < dots.getChildCount(); i++) {
            boolean on = i == index;
            dots.getChildAt(i).setBackground(Ui.round(on ? 0xFFFFFFFF : 0x55FFFFFF, dp(4)));
        }
    }

    /** Decodes at about the size shown, without alpha: half the memory of a full-colour bitmap. */
    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) {
            o.inSampleSize *= 2;
        }
        o.inJustDecodeBounds = false;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    private int dp(float v) {
        return Ui.dp(getContext(), v);
    }
}
