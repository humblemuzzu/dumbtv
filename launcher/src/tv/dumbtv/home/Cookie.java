package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/**
 * An icon on a scalloped "cookie", the nine-lobed shape of Material 3 Expressive, as the weather
 * button wears it. The glyph is a Material Symbols character drawn with {@link Ui#icons}.
 */
final class Cookie extends Drawable {
    private static final int LOBES = 9;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glyph = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path shape = new Path();
    private final String icon;
    private final int size;

    Cookie(Context c, String icon, int color, int ink, int sizeDp) {
        this.icon = icon;
        size = Ui.dp(c, sizeDp);
        fill.setColor(color);
        glyph.setColor(ink);
        glyph.setTypeface(Ui.icons(c));
        glyph.setTextAlign(Paint.Align.CENTER);
        glyph.setTextSize(size * 0.58f);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        shape.reset();
        float cx = b.exactCenterX(), cy = b.exactCenterY(), r = Math.min(b.width(), b.height()) / 2f;
        for (int i = 0; i <= 180; i++) {
            double a = 2 * Math.PI * i / 180;
            // a soft scallop: 92% of the radius at the dips, the full radius at the lobes
            double rr = r * (0.96 + 0.04 * Math.cos(LOBES * a));
            float x = (float) (cx + rr * Math.cos(a)), y = (float) (cy + rr * Math.sin(a));
            if (i == 0) shape.moveTo(x, y);
            else shape.lineTo(x, y);
        }
        shape.close();
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        canvas.drawPath(shape, fill);
        canvas.drawText(icon, b.exactCenterX(), b.exactCenterY() - (glyph.ascent() + glyph.descent()) / 2, glyph);
    }

    @Override
    public int getIntrinsicWidth() {
        return size;
    }

    @Override
    public int getIntrinsicHeight() {
        return size;
    }

    @Override
    public void setAlpha(int alpha) {
        fill.setAlpha(alpha);
        glyph.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        fill.setColorFilter(filter);
        glyph.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
