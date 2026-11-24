package tv.dumbtv.home;

import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;

/**
 * Material Symbols Rounded glyphs for the buttons and Options, drawn with {@link Ui#icons}.
 * The font in assets is cut down to these and Weather.symbol's: a new one goes into the subset.
 */
final class Icons {
    static final String ART = "\uE40A"; // palette
    static final String OPTIONS = "\uE429"; // tune
    static final String SETTINGS = "\uE8B8"; // settings
    static final String NEXT_WALLPAPER = "\uE044"; // skip_next
    static final String APPS = "\uE5C3"; // apps
    static final String INPUTS = "\uE890"; // input
    static final String RESET = "\uF053"; // restart_alt
    static final String EXPORT = "\uE9FC"; // upload_file
    static final String AMAZON_HOME = "\uE9B2"; // home
    static final String ICON_PACK = "\uE7C8"; // interests

    private Icons() {}

    /** The icon for an Options choice, by its home.conf key. */
    static String forChoice(String key) {
        switch (key) {
            case "accent": return "\uE40A"; // palette
            case "tiles": return "\uE9B0"; // grid_view
            case "layout": return "\uE871"; // dashboard
            case "position": return "\uE258"; // vertical_align_bottom
            case "order": return "\uE164"; // sort
            case "all_apps": return APPS;
            case "labels": return "\uE893"; // label
            case "clock": return "\uEFD6"; // schedule
            case "memory": return "\uE322"; // memory
            case "inputs": return INPUTS;
            case "switch": return "\uE8D4"; // swap_horiz
            case "background": return "\uE1BC"; // wallpaper
            case "dim": return "\uE3AB"; // brightness_6
            case "rotate": return "\uE863"; // autorenew
            case "lock": return "\uE899"; // lock
            case "tile_style": return "\uE41D"; // style
            case "chips": return "\uF1C1"; // smart_button
            case "library": return "\uE413"; // photo_library
            default: return ICON_PACK;
        }
    }

    /** {@code glyph} a little larger than the text, then two spaces, then {@code text}. */
    static CharSequence lead(Context c, String glyph, CharSequence text) {
        return lead(c, glyph, text, 0);
    }

    /** As {@link #lead(Context, String, CharSequence)}, the glyph in {@code color} unless 0. */
    static CharSequence lead(Context c, String glyph, CharSequence text, int color) {
        SpannableStringBuilder s = new SpannableStringBuilder(glyph);
        s.setSpan(new Ui.GlyphSpan(Ui.icons(c), 1.35f, color), 0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s.append("  ").append(text);
    }
}
