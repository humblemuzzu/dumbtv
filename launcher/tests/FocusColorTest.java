package tv.dumbtv.home;

/** Small host-side checks for the pixel path, without a device or test framework. */
public final class FocusColorTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        int[] transparent = new int[100];
        check(FocusColor.representative(transparent) == FocusColor.FALLBACK, "transparent fallback");

        int[] icon = new int[100];
        for (int i = 0; i < icon.length; i++) icon[i] = i < 75 ? 0xFFFFFFFF : 0xFFFF3020;
        int color = FocusColor.representative(icon);
        int red = color >> 16 & 255, green = color >> 8 & 255, blue = color & 255;
        check(red > green * 2 && red > blue * 2, "colored logo beats white backdrop");
        check(red < 200, "bright art darkened");

        int[] blueIcon = new int[100];
        for (int i = 0; i < blueIcon.length; i++) blueIcon[i] = 0xFF2050E0;
        color = FocusColor.representative(blueIcon);
        check((color & 255) > (color >> 16 & 255), "blue stays blue");
        check((FocusColor.tint(color) >>> 24) == 61, "tint is 24 percent opaque");
        check((FocusColor.tint(color) & 0xFFFFFF) == (color & 0xFFFFFF), "tint keeps color");
        check(FocusColor.vivid(transparent) == 0, "tile: transparent has no color");
        int[] mono = new int[100];
        for (int i = 0; i < mono.length; i++) mono[i] = i < 50 ? 0xFFFFFFFF : 0xFF101010;
        check(FocusColor.vivid(mono) == 0, "tile: black and white logo has no color");
        int[] speck = new int[1000];
        for (int i = 0; i < speck.length; i++) speck[i] = i < 5 ? 0xFFFF3020 : 0xFFFFFFFF;
        check(FocusColor.vivid(speck) == 0, "tile: a few colored pixels are not the brand");
        color = FocusColor.vivid(icon);
        check(color != 0 && (color >> 16 & 255) > (color >> 8 & 255) * 2, "tile: red logo gives red");
        check("image".equals(Config.BACKGROUND.fallback), "default background is the bundled wallpaper");
        check("Adaptive wallpaper tint".equals(Config.BACKGROUND.name("adaptive-tint")),
                "tint is an Options choice");
        check("Amber".equals(Config.ACCENT.name("amber")), "amber accent unchanged");
        System.out.println("FocusColorTest passed");
    }
}
