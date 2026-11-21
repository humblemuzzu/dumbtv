package tv.dumbtv.home;

/**
 * Colours for {@code background=aurora}, from the focused app's colour (FocusColor) or none.
 * Plain ints and floats so the host tests run it without Android.
 */
final class AuroraPalette {
    /** Blobs drawn by Aurora; {@link #of} returns this many colours after the base. */
    static final int BLOBS = 4;

    /** Hues (degrees) for no app colour, a grey icon, or an input: a green-teal-violet night sky. */
    private static final float[] NIGHT = {165f, 195f, 265f, 300f};
    /** Offsets from the app's hue: two neighbours and a far, quieter accent. */
    private static final float[] SPREAD = {0f, 28f, -32f, 70f};
    /** Brightness per blob: one lead, the rest support. Tuned under the default 40% dim. */
    private static final float[] VALUE = {0.62f, 0.50f, 0.46f, 0.34f};

    private AuroraPalette() {}

    /**
     * {@code [base, blob0 .. blob3]}, opaque ARGB. {@code seed} 0, or a colour with almost no
     * saturation, gives the night palette.
     */
    static int[] of(int seed) {
        float[] hsv = seed == 0 ? null : hsv(seed);
        boolean night = hsv == null || hsv[1] < 0.18f;
        int[] out = new int[BLOBS + 1];
        float lead = night ? NIGHT[0] : hsv[0];
        out[0] = rgb(lead, 0.55f, 0.07f);
        for (int i = 0; i < BLOBS; i++) {
            float hue = night ? NIGHT[i] : hsv[0] + SPREAD[i];
            out[i + 1] = rgb(hue, i == 3 ? 0.55f : 0.72f, VALUE[i]);
        }
        return out;
    }

    /** Straight per-channel blend; {@code t} 0 gives {@code a}, 1 gives {@code b}. */
    static int mix(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        int out = 0xFF000000;
        for (int shift = 0; shift <= 16; shift += 8) {
            int x = a >> shift & 255, y = b >> shift & 255;
            out |= Math.round(x + (y - x) * t) << shift;
        }
        return out;
    }

    /** Hue 0..360, saturation and value 0..1. */
    static float[] hsv(int color) {
        float r = (color >> 16 & 255) / 255f, g = (color >> 8 & 255) / 255f, b = (color & 255) / 255f;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h;
        if (d == 0f) h = 0f;
        else if (max == r) h = 60f * (((g - b) / d) % 6f);
        else if (max == g) h = 60f * ((b - r) / d + 2f);
        else h = 60f * ((r - g) / d + 4f);
        if (h < 0f) h += 360f;
        return new float[] {h, max == 0f ? 0f : d / max, max};
    }

    static int rgb(float hue, float sat, float val) {
        float h = ((hue % 360f) + 360f) % 360f / 60f;
        float c = val * sat, x = c * (1f - Math.abs(h % 2f - 1f)), m = val - c;
        float r, g, b;
        switch ((int) h) {
            case 0: r = c; g = x; b = 0; break;
            case 1: r = x; g = c; b = 0; break;
            case 2: r = 0; g = c; b = x; break;
            case 3: r = 0; g = x; b = c; break;
            case 4: r = x; g = 0; b = c; break;
            default: r = c; g = 0; b = x; break;
        }
        return 0xFF000000 | Math.round((r + m) * 255) << 16 | Math.round((g + m) * 255) << 8
                | Math.round((b + m) * 255);
    }
}
