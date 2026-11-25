package tv.dumbtv.home;

import java.util.Arrays;
import java.util.List;

/** Host-side checks for Art mode's art.txt and its Options switch, without a device. */
public final class ArtTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        List<String[]> works = ArtView.parse(Arrays.asList(
                "# file\ttitle\tartist\tdate",
                "",
                "01-wave.jpg\tThe Great Wave\tHokusai\tc. 1831",
                "  02-bare.png  ",
                "03-two.jpg\tTitle only",
                "../outside.jpg\tEscapes art/",
                "sub/inside.jpg\tA folder",
                "back\\slash.jpg\tA Windows path",
                ".hidden.jpg\tA dot file",
                "\tNo file"));
        check(works.size() == 3, "comments, blanks and unsafe names skipped, three kept");
        check("01-wave.jpg".equals(works.get(0)[0]), "file name first");
        check("The Great Wave".equals(works.get(0)[1]) && "Hokusai".equals(works.get(0)[2])
                && "c. 1831".equals(works.get(0)[3]), "title, artist, date");
        check("02-bare.png".equals(works.get(1)[0]) && works.get(1).length == 1, "a bare picture, trimmed");
        check(works.get(2).length == 2 && "Title only".equals(works.get(2)[1]), "missing fields allowed");

        check("off".equals(Config.ART.fallback), "Art mode is off until turned on");
        check(Arrays.asList(Config.CHOICES).contains(Config.ART), "Art mode shows in Options");
        System.out.println("ArtTest passed");
    }
}
