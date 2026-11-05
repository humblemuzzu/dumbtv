package tv.dumbtv.home;

import android.content.Context;

/**
 * Options' row and value names in the TV's language. Config keeps the English names, so the
 * host-side tests can compile it without the generated R class; each res/values strings.xml carries
 * choice_<key> for a row and choice_<key>_<value> for a value (a - in a value becomes _). A name
 * with no string falls back to Config's English.
 */
final class Text {
    private Text() {}

    static String title(Context c, Config.Choice choice) {
        return find(c, "choice_" + choice.key, choice.title);
    }

    static String name(Context c, Config.Choice choice, String value) {
        return find(c, "choice_" + choice.key + "_" + value.replace('-', '_'), choice.name(value));
    }

    private static String find(Context c, String name, String fallback) {
        int id = c.getResources().getIdentifier(name, "string", c.getPackageName());
        return id == 0 ? fallback : c.getString(id);
    }
}
