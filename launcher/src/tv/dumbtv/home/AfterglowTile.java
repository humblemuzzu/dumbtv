package tv.dumbtv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.TextUtils;

/** Optional text artwork, using only installed icons and locally defined colors. */
final class AfterglowTile {
    private final Context context;
    private final String title;
    private final int[] known;
    private final int accent;
    private int[] colors;
    private int width;
    private final Paint brand = new Paint();
    private final Paint fade = new Paint();
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private CharSequence fitted;

    AfterglowTile(Context c, String packageId, CharSequence label, int accent) {
        context = c;
        title = label.toString();
        known = BrandColors.forPackage(packageId);
        this.accent = accent;
        colors = known != null ? known : new int[] {accent, accent};
        text.setColor(Ui.TEXT);
        text.setTypeface(Ui.serif(c));
        text.setTextAlign(Paint.Align.CENTER);
        ColorMatrix mono = new ColorMatrix();
        mono.setSaturation(0);
        iconPaint.setColorFilter(new ColorMatrixColorFilter(mono));
        edge.setColor(0xFF000000);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Ui.dp(c, 2));
    }

    void resize(int w, int h) {
        width = w;
        brand.setShader(new LinearGradient(0, 0, w, 0, colors, null, Shader.TileMode.CLAMP));
        fade.setShader(new LinearGradient(0, 0, 0, h,
                new int[] {0xFF101010, 0xFF101010, 0xD6101010, 0x87101010, 0x00101010},
                new float[] {0f, .25f, .5f, .75f, 1f}, Shader.TileMode.CLAMP));
        float size = Ui.dp(context, 21), room = w - Ui.dp(context, 24);
        text.setTextSize(size);
        while (text.measureText(title) > room && size > Ui.dp(context, 13)) text.setTextSize(--size);
        fitted = TextUtils.ellipsize(title, text, room, TextUtils.TruncateAt.END);
    }

    /** {@code auto} is the color read from the app's art, which arrives after the first frame. */
    void draw(Canvas canvas, Drawable icon, int auto, int w, int h) {
        if (known == null) {
            int want = auto != 0 ? auto : accent;
            if (colors[0] != want) {
                colors = new int[] {want, want};
                brand.setShader(new LinearGradient(0, 0, width, 0, colors, null, Shader.TileMode.CLAMP));
            }
        }
        canvas.drawRect(0, 0, w, h, brand);
        canvas.drawRect(0, 0, w, h, fade);
        canvas.drawText(fitted, 0, fitted.length(), w / 2f,
                h / 2f - (text.ascent() + text.descent()) / 2, text);
        if (icon != null) {
            float aspect = (float) Math.max(1, icon.getIntrinsicWidth())
                    / Math.max(1, icon.getIntrinsicHeight());
            int iw = Math.max(1, Math.min(Ui.dp(context, 28), Math.round(Ui.dp(context, 18) * aspect)));
            int ih = Math.max(1, Math.min(Ui.dp(context, 18), Math.round(iw / aspect)));
            int right = w - Ui.dp(context, 9), top = Ui.dp(context, 9);
            int layer = canvas.saveLayer(right - iw, top, right, top + ih, iconPaint);
            icon.setBounds(right - iw, top, right, top + ih);
            icon.draw(canvas);
            canvas.restoreToCount(layer);
        }
    }

    void edge(Canvas canvas, int w, int h, float radius) {
        canvas.drawRoundRect(0, 0, w, h, radius, radius, edge);
    }
}
