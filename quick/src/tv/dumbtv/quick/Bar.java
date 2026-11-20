package tv.dumbtv.quick;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/** A slider's track and fill. The row draws focus; the bar only follows its colours. */
final class Bar extends View {
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float value;
    private int[] gradient;
    private int fillColor;

    Bar(Context c) {
        super(c);
    }

    void setValue(float v) {
        value = Math.max(0, Math.min(1, v));
        invalidate();
    }

    /** A solid fill, or with {@link #setGradient} a fill that shows the scale (cold to warm). */
    void setColors(int trackColor, int fillColor) {
        track.setColor(trackColor);
        this.fillColor = fillColor;
        invalidate();
    }

    void setGradient(int... colors) {
        gradient = colors;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float h = getHeight(), w = getWidth(), rad = h / 2f;
        r.set(0, 0, w, h);
        c.drawRoundRect(r, rad, rad, track);
        if (value <= 0) return;
        if (gradient != null) {
            fill.setShader(new LinearGradient(0, 0, w, 0, gradient, null, Shader.TileMode.CLAMP));
        } else {
            fill.setShader(null);
            fill.setColor(fillColor);
        }
        r.set(0, 0, Math.max(h, w * value), h);
        c.drawRoundRect(r, rad, rad, fill);
    }
}
