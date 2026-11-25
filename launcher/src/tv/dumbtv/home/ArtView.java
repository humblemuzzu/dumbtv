package tv.dumbtv.home;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Display;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Art mode, as on a picture-frame TV: one painting filling the screen, a new one every few
 * minutes (three as the screensaver, five from the Art button) or on left/right through a fade
 * to black, and its title for a few seconds after each change. Paintings and art.txt (file,
 * title, artist, date; tab separated) live in the media folder's art/.
 * Shared by the screensaver ({@link ArtDream}) and the Art button ({@link ArtScreen}).
 *
 * Fire OS draws apps at 1920x1080 and scales them up to a 4K panel. The painting goes to a
 * SurfaceView whose buffer has the panel's own size, which the compositor shows pixel for pixel,
 * as it does video; the fade and the caption are drawn above it at the interface's size.
 */
final class ArtView extends FrameLayout implements SurfaceHolder.Callback {
    private static final long CAPTION = 6_000L;
    private final long every;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<String[]> works = new ArrayList<>();
    private final File dir;
    private final SurfaceView surface;
    private final int panelW, panelH;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint black = new Paint();
    private final TextPaint title = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint byline = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private Bitmap shown;
    private boolean ready;
    private float cover = 1f, caption;
    private int index = -1, pending = 1;
    private ValueAnimator fade, captionFade;

    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            change(1);
            ui.postDelayed(this, every);
        }
    };

    private final Runnable hideCaption = () -> captionFade = animate(caption, 0f, 1200, v -> caption = v);

    /** Left/right on the remote: the painting before or after, and the wait starts over. */
    void step(int by) {
        if (fade != null && fade.isRunning()) return;
        ui.removeCallbacks(next);
        change(by);
        ui.postDelayed(next, every);
    }

    /** {@code everyMs}: how long each painting stays before the next one. */
    ArtView(Context c, long everyMs) {
        super(c);
        every = everyMs;
        setWillNotDraw(false);
        File[] media = c.getExternalMediaDirs();
        dir = new File(media.length > 0 && media[0] != null ? media[0] : c.getFilesDir(), "art");
        Display.Mode mode = ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay().getMode();
        panelW = mode.getPhysicalWidth();
        panelH = mode.getPhysicalHeight();
        surface = new SurfaceView(c);
        surface.getHolder().setFixedSize(panelW, panelH);
        surface.getHolder().addCallback(this);
        addView(surface, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        black.setColor(0xFF000000);
        // white on the painting itself, with a soft shadow to stay readable on light areas
        title.setTypeface(Ui.LIGHT);
        title.setColor(0xFFFFFFFF);
        title.setTextSize(Ui.dp(c, 22));
        title.setTextAlign(Paint.Align.RIGHT);
        title.setShadowLayer(Ui.dp(c, 6), 0, Ui.dp(c, 1), 0xB3000000);
        byline.setTypeface(Ui.MEDIUM);
        byline.setColor(0xFFFFFFFF);
        byline.setTextSize(Ui.dp(c, 13));
        byline.setTextAlign(Paint.Align.RIGHT);
        byline.setShadowLayer(Ui.dp(c, 6), 0, Ui.dp(c, 1), 0xB3000000);
        read();
    }

    /**
     * The entries of art.txt: file, then optional title, artist and date, tab separated. Comment
     * and blank lines are skipped, and so is any file name that is not a plain name in art/.
     */
    static List<String[]> parse(List<String> lines) {
        List<String[]> out = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("#") || line.trim().isEmpty()) continue;
            String[] f = line.split("\t", -1);
            String name = f[0].trim();
            if (name.isEmpty() || name.contains("/") || name.contains("\\") || name.startsWith(".")) continue;
            f[0] = name;
            out.add(f);
        }
        return out;
    }

    private void read() {
        File list = new File(dir, "art.txt");
        if (list.isFile()) {
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(list),
                    StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) lines.add(line);
            } catch (IOException ignored) {
                // an unreadable list falls back to the pictures alone
            }
            for (String[] f : parse(lines)) if (new File(dir, f[0]).isFile()) works.add(f);
        }
        if (works.isEmpty()) {
            File[] files = dir.listFiles((d, n) -> n.matches("(?i).+\\.(jpe?g|png|webp)"));
            if (files != null) for (File f : files) works.add(new String[] {f.getName()});
        }
        Collections.shuffle(works);
    }

    /**
     * No hole in the window over the painting: the compositor would skip that part of the window,
     * caption and fade included. The window is translucent instead (see {@link #translucent}), and
     * the SurfaceView clears its pixels, so the painting shows through with the caption above it.
     */
    @Override
    public boolean gatherTransparentRegion(Region region) {
        boolean opaque = super.gatherTransparentRegion(region);
        if (region != null) {
            int[] at = new int[2];
            getLocationInWindow(at);
            region.op(at[0], at[1], at[0] + getWidth(), at[1] + getHeight(), Region.Op.DIFFERENCE);
        }
        return opaque;
    }

    /** For the window that holds this view, before it is shown. */
    static void translucent(Window w) {
        w.setFormat(PixelFormat.TRANSLUCENT);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        ready = true;
        ui.removeCallbacks(next);
        if (shown == null) ui.post(next);
        else paint();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        paint();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        ready = false;
        ui.removeCallbacks(next);
    }

    @Override
    protected void onDetachedFromWindow() {
        ui.removeCallbacksAndMessages(null);
        if (fade != null) fade.cancel();
        if (captionFade != null) captionFade.cancel();
        if (shown != null) shown.recycle();
        shown = null;
        super.onDetachedFromWindow();
    }

    /** Fades to black, swaps the painting while the screen is dark, fades back in. */
    private void change(int by) {
        if (works.isEmpty()) {
            invalidate();
            return;
        }
        pending = by;
        if (shown == null) {
            swap();
            return;
        }
        fade = animate(cover, 1f, 700, v -> cover = v);
        fade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator a) {
                if (cover >= 1f) swap();
            }
        });
    }

    private void swap() {
        if (!ready) return;
        int n = works.size();
        index = index < 0 ? 0 : ((index + pending) % n + n) % n;
        // one 4K bitmap at a time: the old one goes before the next is decoded
        if (shown != null) shown.recycle();
        shown = decode(new File(dir, works.get(index)[0]));
        paint();
        fade = animate(1f, 0f, 1200, v -> cover = v);
        // a change within six seconds of the last must not inherit that one's fade-out
        ui.removeCallbacks(hideCaption);
        if (captionFade != null) captionFade.cancel();
        captionFade = animate(0f, 1f, 600, v -> caption = v);
        ui.postDelayed(hideCaption, CAPTION);
    }

    private void paint() {
        if (!ready || shown == null) return;
        Canvas c = surface.getHolder().lockCanvas();
        if (c == null) return;
        try {
            c.drawColor(0xFF000000);
            c.drawBitmap(shown, crop(shown), new Rect(0, 0, c.getWidth(), c.getHeight()), image);
        } finally {
            surface.getHolder().unlockCanvasAndPost(c);
        }
    }

    private interface Step {
        void at(float v);
    }

    private ValueAnimator animate(float from, float to, long ms, Step step) {
        ValueAnimator a = ValueAnimator.ofFloat(from, to);
        a.setDuration(ms);
        a.addUpdateListener(x -> {
            step.at((Float) x.getAnimatedValue());
            invalidate();
        });
        a.start();
        return a;
    }

    /** Decoded at no more than the panel needs: a 4K picture whole on a 4K panel. */
    private Bitmap decode(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= panelW && o.outHeight / (sample * 2) >= panelH) sample *= 2;
        o.inJustDecodeBounds = false;
        o.inSampleSize = sample;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** The middle of the painting at the panel's proportions: 16:9 pictures are used whole. */
    private Rect crop(Bitmap b) {
        float screen = (float) panelW / panelH, art = (float) b.getWidth() / b.getHeight();
        if (art > screen) {
            int w = Math.round(b.getHeight() * screen);
            return new Rect((b.getWidth() - w) / 2, 0, (b.getWidth() + w) / 2, b.getHeight());
        }
        int h = Math.round(b.getWidth() / screen);
        return new Rect(0, (b.getHeight() - h) / 2, b.getWidth(), (b.getHeight() + h) / 2);
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (works.isEmpty()) {
            title.setTextAlign(Paint.Align.CENTER);
            canvas.drawText("Put paintings and art.txt in " + dir, getWidth() / 2f, getHeight() / 2f, title);
            title.setTextAlign(Paint.Align.RIGHT);
            return;
        }
        if (cover > 0) {
            black.setAlpha(Math.round(255 * cover));
            canvas.drawRect(0, 0, getWidth(), getHeight(), black);
        }
        if (caption > 0 && index >= 0 && works.get(index).length > 1) {
            String[] w = works.get(index);
            title.setAlpha(Math.round(255 * caption));
            byline.setAlpha(Math.round(220 * caption));
            float x = getWidth() - Ui.dp(getContext(), 48), y = getHeight() - Ui.dp(getContext(), 64);
            // a long title is cut short rather than run off the left edge
            float room = getWidth() * 0.8f;
            CharSequence name = TextUtils.ellipsize(w[1], title, room, TextUtils.TruncateAt.END);
            canvas.drawText(name, 0, name.length(), x, y, title);
            String by = w.length > 3 && !w[3].isEmpty() ? w[2] + ", " + w[3] : w.length > 2 ? w[2] : "";
            CharSequence who = TextUtils.ellipsize(by, byline, room, TextUtils.TruncateAt.END);
            canvas.drawText(who, 0, who.length(), x, y + Ui.dp(getContext(), 22), byline);
        }
    }
}
