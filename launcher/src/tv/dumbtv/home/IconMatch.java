package tv.dumbtv.home;

import java.util.Locale;

/**
 * The text rules of icon packs, kept free of Android so the host tests can run them: reading an
 * appfilter entry, the drawable names tried for an app's name, which picked icon counts, and how
 * the picker compares names.
 */
final class IconMatch {
    private IconMatch() {}

    /**
     * {@code ComponentInfo{com.stremio.one/com.stremio.tv.MainActivity}} as {package, activity},
     * a short ".Activity" made full; null for an entry that names no app.
     */
    static String[] component(String component) {
        if (component == null) return null;
        int open = component.indexOf('{'), slash = component.indexOf('/'), close = component.indexOf('}');
        if (open < 0 || slash < open || close < slash) return null;
        String app = component.substring(open + 1, slash), activity = component.substring(slash + 1, close);
        if (app.isEmpty()) return null;
        if (activity.startsWith(".")) activity = app + activity;
        return new String[] {app, activity};
    }

    /**
     * The drawable names tried for an app called {@code label}, as packs name their pictures:
     * "Amazon Music" gives amazonmusic, then amazon_music. Either may be empty.
     */
    static String[] names(String label) {
        String lower = label.toLowerCase(Locale.ROOT);
        return new String[] {
            lower.replaceAll("[^a-z0-9]", ""),
            lower.replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", ""),
        };
    }

    /**
     * The drawable of a picked icon, saved as {@code <pack>/<drawable>}, when it came from
     * {@code pack}, the chosen one; null otherwise, so a pick from another pack does not count.
     */
    static String picked(String chosen, String pack) {
        if (chosen == null || pack == null || pack.isEmpty()) return null;
        String mine = pack + "/";
        return chosen.startsWith(mine) && chosen.length() > mine.length() ? chosen.substring(mine.length()) : null;
    }

    /** Lower-case letters and digits only, to compare names in the picker. */
    static String squash(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Squashed names: "youtubemusic" is like YouTube, "nuviotv" like Nuvio; "tv" is like neither. */
    static boolean likeApp(String icon, String app) {
        if (app.isEmpty()) return false;
        return icon.contains(app) || icon.length() >= 4 && app.contains(icon);
    }
}
