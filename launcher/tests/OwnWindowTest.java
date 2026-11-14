package tv.dumbtv.home;

/** Which of the launcher's own window events Guard may read as "Amazon's home is gone". */
public final class OwnWindowTest {
    private static final String COVER = "android.view.View";
    private static final String HOME = "tv.dumbtv.home.Home";

    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static boolean shows(CharSequence cls, boolean homeInFront) {
        return OwnWindow.showsWhatIsInFront(cls, COVER, HOME, homeInFront);
    }

    public static void main(String[] args) {
        check(!shows(COVER, false), "the cover over Amazon's home says nothing");
        check(!shows(COVER, true), "the cover says nothing while Home is up either");
        check(shows(HOME, true), "Home on screen means Amazon's home is gone");
        check(!shows(HOME, false), "a Home event after Home lost the screen is stale");
        check(shows("tv.dumbtv.home.Options", false), "Options is the user leaving Amazon's home");
        check(shows("tv.dumbtv.home.Input", false), "the input screen too");
        check(shows("android.app.AlertDialog", false), "a dialog too");
        check(shows(null, false), "an event without a class keeps the old meaning");
        System.out.println("OwnWindowTest passed (cover, stale Home, own screens)");
    }
}
