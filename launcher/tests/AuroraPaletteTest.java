package tv.dumbtv.home;

/** Host-side checks for background=aurora's colours, without a device or test framework. */
public final class AuroraPaletteTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static float luminance(int c) {
        return (0.2126f * (c >> 16 & 255) + 0.7152f * (c >> 8 & 255) + 0.0722f * (c & 255)) / 255f;
    }

    public static void main(String[] args) {
        int[] night = AuroraPalette.of(0);
        check(night.length == AuroraPalette.BLOBS + 1, "base plus one colour per blob");
        for (int c : night) check(c >>> 24 == 255, "opaque");
        check(luminance(night[0]) < 0.08f, "base stays near black");
        check(java.util.Arrays.equals(AuroraPalette.of(0xFF141518), night), "grey icon gives the night sky");

        int[] red = AuroraPalette.of(0xFF5A0A0A); // a darkened Netflix red, as FocusColor gives it
        int lead = red[1];
        check((lead >> 16 & 255) > 2 * (lead & 255) && (lead >> 16 & 255) > (lead >> 8 & 255), "red app glows red");
        check(luminance(lead) > luminance(red[0]) * 3, "blobs brighter than the base");
        for (int i = 1; i < red.length; i++) check(luminance(red[i]) < 0.5f, "white labels stay readable");

        int[] blue = AuroraPalette.of(0xFF0A1E5A);
        check((blue[1] & 255) > (blue[1] >> 16 & 255), "blue app glows blue");

        check(AuroraPalette.mix(0xFF000000, 0xFFFFFFFF, 0.5f) == 0xFF808080, "mix halfway");
        check(AuroraPalette.mix(0xFF102030, 0xFFFFFFFF, 0f) == 0xFF102030, "mix start");
        check(AuroraPalette.rgb(120f, 1f, 1f) == 0xFF00FF00, "hsv green");
        check(AuroraPalette.rgb(-120f, 1f, 1f) == 0xFF0000FF, "negative hue wraps");
        float[] hsv = AuroraPalette.hsv(0xFFFF0000);
        check(hsv[0] == 0f && hsv[1] == 1f && hsv[2] == 1f, "hsv of red");
        System.out.println("AuroraPaletteTest passed");
    }
}
