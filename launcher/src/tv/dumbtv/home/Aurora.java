package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.View;

/**
 * {@code background=aurora}: a few soft blobs of light drifting behind the rows, coloured after
 * the focused app (AuroraPalette). Drawn in software into a bitmap an eighth of the screen's
 * size, then scaled up with filtering: the blur is the look, and it costs next to nothing. It
 * runs at about 30 frames a second, only while the home screen is in front, and holds still
 * when animations are off in the TV's developer settings.
 */
final class Aurora extends View implements Choreographer.FrameCallback {
    private static final int SCALE = 8;
    private static final long FRAME_MS = 33;
    private static final long FADE_MS = 1400;

    /** Per blob: home x, y (fractions of the screen), drift x, y, radius (fraction of width). */
    private static final float[][] PATH = {
        {0.30f, 0.30f, 0.22f, 0.16f, 0.62f},
        {0.72f, 0.38f, 0.20f, 0.20f, 0.55f},
        {0.48f, 0.80f, 0.28f, 0.12f, 0.58f},
        {0.85f, 0.85f, 0.14f, 0.18f, 0.45f},
    };
    /** Per blob: periods (seconds) of x, y and breathing; long and coprime, so it never repeats. */
    private static final float[][] PERIOD = {
        {71f, 53f, 37f}, {59f, 83f, 41f}, {97f, 61f, 47f}, {67f, 89f, 43f},
    };

    private final Paint blob = new Paint();
    private final Paint scaled = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private final long epoch = SystemClock.uptimeMillis() - 400_000; // start mid-drift
    private Bitmap frame;
    private Canvas canvas;
    private int[] shown = AuroraPalette.of(0), from = shown, to = shown;
    private long fadeStart;
    private boolean running, posted;
    private long lastDraw;

    Aurora(Context c) {
        super(c);
        blob.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
    }

    /** The focused app's colour, or 0 for none; fades to the new palette. */
    void seed(int color) {
        int[] next = AuroraPalette.of(color);
        if (java.util.Arrays.equals(next, to)) return;
        from = shown;
        to = next;
        fadeStart = SystemClock.uptimeMillis();
        if (!animating()) shown = to;
        invalidate();
    }

    void start() {
        running = true;
        post();
        invalidate();
    }

    void stop() {
        running = false;
        if (posted) Choreographer.getInstance().removeFrameCallback(this);
        posted = false;
    }

    /** Off: also drops the bitmap, about 130 KB at 1080p. */
    void release() {
        stop();
        if (frame != null) frame.recycle();
        frame = null;
        canvas = null;
    }

    private boolean animating() {
        return Settings.Global.getFloat(getContext().getContentResolver(), "animator_duration_scale", 1f) != 0f;
    }

    private void post() {
        if (!running || posted || !animating()) return;
        posted = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        posted = false;
        if (!running) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastDraw >= FRAME_MS) invalidate();
        post();
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas out) {
        int w = Math.max(1, getWidth() / SCALE), h = Math.max(1, getHeight() / SCALE);
        if (frame == null || frame.getWidth() != w || frame.getHeight() != h) {
            if (frame != null) frame.recycle();
            frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            canvas = new Canvas(frame);
        }
        long now = SystemClock.uptimeMillis();
        lastDraw = now;
        float fade = Math.min(1f, (now - fadeStart) / (float) FADE_MS);
        fade = fade * fade * (3f - 2f * fade);
        int[] colors = new int[to.length];
        for (int i = 0; i < colors.length; i++) colors[i] = AuroraPalette.mix(from[i], to[i], fade);
        shown = colors;

        canvas.drawColor(colors[0], PorterDuff.Mode.SRC);
        float t = (now - epoch) / 1000f;
        for (int i = 0; i < PATH.length; i++) {
            float[] p = PATH[i], q = PERIOD[i];
            float x = w * (p[0] + p[2] * (float) Math.sin(2 * Math.PI * t / q[0] + i));
            float y = h * (p[1] + p[3] * (float) Math.sin(2 * Math.PI * t / q[1] + 2 * i));
            float r = w * p[4] * (1f + 0.14f * (float) Math.sin(2 * Math.PI * t / q[2] + 3 * i));
            int c = colors[i + 1];
            blob.setShader(new RadialGradient(x, y, r,
                    new int[] {c, c & 0x00FFFFFF | 0x8C000000, c & 0x00FFFFFF},
                    new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawCircle(x, y, r, blob);
        }
        dst.set(0, 0, getWidth(), getHeight());
        out.drawBitmap(frame, null, dst, scaled);
    }
}
