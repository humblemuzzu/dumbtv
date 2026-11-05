package tv.dumbtv.home;

import java.util.Arrays;
import java.util.List;

/** Host-side checks for library.conf parsing, without a device or test framework. */
public final class LibraryTest {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    public static void main(String[] args) {
        List<Library.Entry> got = Library.parse(Arrays.asList(
                "# comment",
                "",
                "st.jpg | Stranger Things | com.netflix.ninja | https://www.netflix.com/title/80057281 | source=30",
                "moana.jpg|Moana|com.disney.disneyplus",
                "  bare.png | | com.hulu.plus | ",
                "too | few",
                "../escape.jpg | Bad | com.x",
                "sub/dir.jpg | Bad | com.x",
                "nopkg.jpg | Title |  ",
                "x.jpg | Extras | com.y | app://z | a=1, b = 2,junk"));

        check(got.size() == 4, "four valid lines, got " + got.size());
        Library.Entry st = got.get(0);
        check(st.image.equals("st.jpg") && st.title.equals("Stranger Things")
                && st.pkg.equals("com.netflix.ninja")
                && st.uri.equals("https://www.netflix.com/title/80057281"), "full line");
        check("30".equals(st.extras.get("source")), "extra parsed");
        Library.Entry moana = got.get(1);
        check(moana.uri.isEmpty() && moana.extras.isEmpty(), "no link, no extras");
        check(got.get(2).title.equals("bare.png"), "empty title falls back to the file name");
        Library.Entry x = got.get(3);
        check(x.extras.size() == 2 && "2".equals(x.extras.get("b")), "extras trimmed, junk skipped");
        check(!st.key().equals(moana.key()), "keys differ");
        System.out.println("LibraryTest ok");
    }
}
