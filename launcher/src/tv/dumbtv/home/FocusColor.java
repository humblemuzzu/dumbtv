package tv.dumbtv.home;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

/** Samples a small, separate copy of app art on the existing artwork worker. */
final class FocusColor {
    static final int FALLBACK = 0xFF141518;

    private FocusColor() {}

    /** A 24% overlay; keep extraction independent of the configured focus accent. */
    static int tint(int solid) {
        return (solid & 0x00FFFFFF) | 0x3D000000;
    }

    static int from(Drawable art) {
        int[] pixels = pixels(art);
        return pixels == null ? FALLBACK : representative(pixels);
    }

    /** An Afterglow tile's color from the app's art, or 0 when the art has none. */
    static int tileColor(Drawable art) {
        int[] pixels = pixels(art);
        return pixels == null ? 0 : vivid(pixels);
    }

    private static int[] pixels(Drawable art) {
        if (art == null || art.getConstantState() == null) return null;
        Bitmap bitmap = null;
        try {
            Drawable copy = art.getConstantState().newDrawable().mutate();
            bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888);
            copy.setBounds(0, 0, 48, 48);
            copy.draw(new Canvas(bitmap));
            int[] pixels = new int[48 * 48];
            bitmap.getPixels(pixels, 0, 48, 0, 0, 48, 48);
            return pixels;
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }

    /** Hue groups favor useful colored regions over a large neutral icon background. */
    static int representative(int[] pixels) {
        return pick(pixels, true);
    }

    /**
     * The strongest hue group only, or 0: a black and white logo, or one whose color is a few
     * stray pixels, would otherwise tint its tile grey or a random hue.
     */
    static int vivid(int[] pixels) {
        int color = pick(pixels, false);
        return color == FALLBACK ? 0 : color;
    }

    private static int pick(int[] pixels, boolean neutral) {
        float[] weights = new float[12];
        float[] red = new float[12], green = new float[12], blue = new float[12];
        int visible = 0;
        for (int pixel : pixels) {
            int alpha = pixel >>> 24;
            if (alpha < 96) continue;
            int r = (pixel >> 16) & 255, g = (pixel >> 8) & 255, b = pixel & 255;
            int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
            float saturation = max == 0 ? 0 : (max - min) / (float) max;
            float hue;
            if (max == min) hue = 0;
            else if (max == r) hue = (g - b) / (float) (max - min);
            else if (max == g) hue = 2f + (b - r) / (float) (max - min);
            else hue = 4f + (r - g) / (float) (max - min);
            int bin = saturation < 0.12f ? 0 : 1 + ((int) ((hue + 6f) % 6f * 11f / 6f));
            float weight = (alpha / 255f) * (saturation < 0.12f ? 0.12f : 0.5f + saturation * 1.5f);
            weights[bin] += weight;
            red[bin] += r * weight;
            green[bin] += g * weight;
            blue[bin] += b * weight;
            visible++;
        }
        if (visible == 0) return FALLBACK;
        int best = neutral ? 0 : 1;
        for (int i = best + 1; i < weights.length; i++) if (weights[i] > weights[best]) best = i;
        if (weights[best] == 0) return FALLBACK;
        if (!neutral && weights[best] < 0.03f * visible) return FALLBACK;
        int r = Math.round(red[best] / weights[best]);
        int g = Math.round(green[best] / weights[best]);
        int b = Math.round(blue[best] / weights[best]);
        // Dark enough for white labels and the independent focus ring on top.
        float scale = 1f;
        while (luminance(r * scale, g * scale, b * scale) > 0.12f) scale *= 0.9f;
        return 0xFF000000 | Math.round(r * scale) << 16
                | Math.round(g * scale) << 8 | Math.round(b * scale);
    }

    private static float luminance(float r, float g, float b) {
        return 0.2126f * linear(r) + 0.7152f * linear(g) + 0.0722f * linear(b);
    }

    private static float linear(float channel) {
        float v = channel / 255f;
        return v <= 0.04045f ? v / 12.92f : (float) Math.pow((v + 0.055f) / 1.055f, 2.4);
    }
}
