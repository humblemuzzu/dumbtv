package tv.dumbtv.home;

import java.util.Arrays;

/** Icon pack text rules: no Android runtime, device, pack or network. */
public final class IconMatchTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static void component() {
        String[] c = IconMatch.component("ComponentInfo{com.stremio.one/com.stremio.tv.MainActivity}");
        check(c != null && c[0].equals("com.stremio.one") && c[1].equals("com.stremio.tv.MainActivity"),
                "full entry");
        c = IconMatch.component("ComponentInfo{ar.tvplayer.tv/.ui.MainActivity}");
        check(c != null && c[1].equals("ar.tvplayer.tv.ui.MainActivity"), "short activity made full");
        check(IconMatch.component("ComponentInfo{/com.android.contacts.Activity}") == null, "no package");
        check(IconMatch.component("ComponentInfo{com.app.MainActivity}") == null, "no slash");
        check(IconMatch.component("com.app/Main{x}") == null, "slash before the brace");
        check(IconMatch.component("ComponentInfo{com.app/Main") == null, "unclosed");
        check(IconMatch.component("") == null && IconMatch.component(null) == null, "empty");
    }

    private static void names() {
        check(Arrays.equals(IconMatch.names("YouTube"), new String[] {"youtube", "youtube"}), "one word");
        check(Arrays.equals(IconMatch.names("Amazon Music"), new String[] {"amazonmusic", "amazon_music"}),
                "two words");
        check(Arrays.equals(IconMatch.names("Disney+"), new String[] {"disney", "disney"}), "sign dropped");
        check(Arrays.equals(IconMatch.names("Paramount+ TV"), new String[] {"paramounttv", "paramount_tv"}),
                "sign between words");
        check(Arrays.equals(IconMatch.names("ARD Mediathek 2"), new String[] {"ardmediathek2", "ard_mediathek_2"}),
                "digits kept");
        check(Arrays.equals(IconMatch.names("+++"), new String[] {"", ""}), "nothing usable");
    }

    private static void picked() {
        check("stremio".equals(IconMatch.picked("com.pack.a/stremio", "com.pack.a")), "pick of the chosen pack");
        check(IconMatch.picked("com.pack.a/stremio", "com.pack.b") == null, "pick of another pack");
        check(IconMatch.picked("com.pack.ab/stremio", "com.pack.a") == null, "pack name is a prefix");
        check(IconMatch.picked("com.pack.a/stremio", "com.pack.ab") == null, "chosen pack is longer");
        check(IconMatch.picked("com.pack.a/stremio", "off") == null, "icon pack off");
        check(IconMatch.picked("own", "com.pack.a") == null, "App's own picture is not a pack pick");
        check(IconMatch.picked("com.pack.a/", "com.pack.a") == null, "no drawable");
        check(IconMatch.picked(null, "com.pack.a") == null && IconMatch.picked("com.pack.a/x", null) == null
                && IconMatch.picked("com.pack.a/x", "") == null, "missing values");
    }

    private static void picker() {
        check(IconMatch.squash("YouTube Music!").equals("youtubemusic"), "squash");
        check(IconMatch.likeApp("youtubemusic", "youtube"), "longer icon name");
        check(IconMatch.likeApp("nuviotv", "nuvio"), "pack suffix");
        check(IconMatch.likeApp("tivi", "tivimate"), "shorter icon name of four letters");
        check(!IconMatch.likeApp("tv", "nuvio") && !IconMatch.likeApp("tv", "youtubetv"), "too short");
        check(!IconMatch.likeApp("spotify", "stremio"), "unrelated");
        check(!IconMatch.likeApp("stremio", ""), "no app name");
    }

    public static void main(String[] args) {
        component();
        names();
        picked();
        picker();
        System.out.println("IconMatchTest ok");
    }
}
