package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * A Material 3 switch, drawn only: Options › an on/off choice shows its state with it. On, a
 * filled track in the accent with a large thumb at the end; off, an outlined track with a
 * smaller thumb at the start.
 */
final class Toggle extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean on;
    private final int accent, w, h, line;

    Toggle(Context c, boolean on, int accent) {
        this.on = on;
        this.accent = accent;
        w = Ui.dp(c, 44);
        h = Ui.dp(c, 26);
        line = Ui.dp(c, 2);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float top = b.exactCenterY() - h / 2f, r = h / 2f;
        if (on) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(accent);
            canvas.drawRoundRect(b.left, top, b.left + w, top + h, r, r, paint);
            paint.setColor(Ui.ink(accent));
            canvas.drawCircle(b.left + w - r, top + r, r - line * 2, paint);
        } else {
            float half = line / 2f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(line);
            paint.setColor(Ui.MUTED);
            canvas.drawRoundRect(b.left + half, top + half, b.left + w - half, top + h - half, r, r, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(b.left + r, top + r, r / 2f, paint);
        }
    }

    @Override
    public int getIntrinsicWidth() {
        return w;
    }

    @Override
    public int getIntrinsicHeight() {
        return h;
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
