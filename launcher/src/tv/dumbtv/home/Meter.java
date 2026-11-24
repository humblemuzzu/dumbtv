package tv.dumbtv.home;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/**
 * Options › Memory meter: free memory under the date, as a ring filled to the share in use,
 * with that share inside it and the word Memory beside it. The numbers come from ActivityManager.getMemoryInfo, which needs no
 * permission. It only shows; nothing is closed or freed.
 */
final class Meter extends View {
    /** Below this share of memory free, the ring turns amber even before Android says "low". */
    static final float LOW_SHARE = 0.15f;
    private static final int LOW_COLOR = 0xFFFFB340;

    private final ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
    private final TextPaint name = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint percent = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF ring = new RectF();
    private final int width, size, stroke, gap;
    private String label = "";
    /** The words around the numbers; Home hands in the TV's language with {@link #setWords}. */
    private String title = "Memory", free = "%1$d MB free of %2$d MB", lowNote = "running low";
    private boolean low;
    private int accent = Ui.TEXT;
    private float shown = -1;
    private ValueAnimator animation;

    Meter(Context c) {
        super(c);
        name.setTypeface(Ui.medium(c));
        name.setTextSize(Ui.dp(c, 12));
        percent.setColor(Ui.TEXT);
        percent.setTypeface(Ui.medium(c));
        percent.setTextSize(Ui.dp(c, 9));
        percent.setTextAlign(Paint.Align.CENTER);
        width = Ui.dp(c, 280);
        size = Ui.dp(c, 30);
        stroke = Ui.dp(c, 3.5f);
        gap = Ui.dp(c, 10);
        for (Paint p : new Paint[] {track, bar}) {
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(stroke);
            p.setStrokeCap(Paint.Cap.ROUND);
        }
        track.setColor(0x26FFFFFF);
    }

    /** "247 MB free of 903 MB". */
    static String label(long avail, long total) {
        return mb(avail) + " MB free of " + mb(total) + " MB";
    }

    static long mb(long bytes) {
        return bytes / (1024 * 1024);
    }

    /** Share of memory in use, 0 to 1. */
    static float used(long avail, long total) {
        if (total <= 0) return 0f;
        return Math.max(0f, Math.min(1f, 1f - (float) avail / total));
    }

    static boolean low(boolean systemLow, long avail, long total) {
        return systemLow || total > 0 && avail < total * LOW_SHARE;
    }

    /** {@code free} takes the free and total MB, as in {@link #label}. */
    void setWords(String title, String free, String lowNote) {
        this.title = title;
        this.free = free;
        this.lowNote = lowNote;
        invalidate();
    }

    void setAccent(int color) {
        accent = color;
        invalidate();
    }

    /** Reads memory now; the ring eases to the new level, except the first time. */
    void update(ActivityManager am) {
        if (am == null) return;
        am.getMemoryInfo(info);
        low = low(info.lowMemory, info.availMem, info.totalMem);
        label = String.format(free, mb(info.availMem), mb(info.totalMem)) + (low ? " · " + lowNote : "");
        float target = used(info.availMem, info.totalMem);
        if (animation != null) animation.cancel();
        if (shown < 0) {
            shown = target;
        } else if (Math.abs(target - shown) > 0.001f) {
            animation = ValueAnimator.ofFloat(shown, target);
            animation.setDuration(300);
            animation.addUpdateListener(a -> {
                shown = (Float) a.getAnimatedValue();
                invalidate();
            });
            animation.start();
        }
        setContentDescription(title + " " + label);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        setMeasuredDimension(width, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float half = stroke / 2f, mid = getHeight() / 2f;
        ring.set(half, mid - size / 2f + half, size - half, mid + size / 2f - half);
        canvas.drawOval(ring, track);
        bar.setColor(low ? LOW_COLOR : accent);
        // filled clockwise from the top, as a phone's storage ring
        canvas.drawArc(ring, -90, 360 * Math.max(0f, shown), false, bar);
        canvas.drawText(Math.round(100 * Math.max(0f, shown)) + "%", size / 2f,
                mid - (percent.ascent() + percent.descent()) / 2, percent);
        // only the name beside the ring; the free and total MB stay in the content description
        name.setColor(low ? LOW_COLOR : Ui.MUTED);
        canvas.drawText(title, size + gap, mid - (name.ascent() + name.descent()) / 2, name);
    }
}
