package tv.dumbtv.home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.widget.TextView;

final class Ui {
    static final int TEXT = 0xFFF2F2F3;
    static final int MUTED = 0x99FFFFFF;
    static final int FAINT = 0x66FFFFFF;
    static final int SURFACE = 0xFF1C1D21;
    static final int DIALOG = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    private static Typeface serif, medium, icons;

    private Ui() {}

    /** Instrument Serif: the clock, titles and app names. */
    static Typeface serif(Context c) {
        if (serif == null) serif = font(c, "fonts/InstrumentSerif-Regular.ttf", "sans-serif-light");
        return serif;
    }

    /** Instrument Sans Medium: everything smaller. */
    static Typeface medium(Context c) {
        if (medium == null) medium = font(c, "fonts/InstrumentSans-Medium.ttf", "sans-serif-medium");
        return medium;
    }

    /** Material Symbols Rounded, filled, cut down to the glyphs in Weather.symbol and Icons. */
    static Typeface icons(Context c) {
        if (icons == null) icons = font(c, "fonts/MaterialSymbolsRounded.ttf", "sans-serif");
        return icons;
    }

    /** dumbtv's own system fonts, kept for Options: Instrument is for the home screen only. */
    static final Typeface SYSTEM_LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL);
    static final Typeface SYSTEM_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    /**
     * An icon glyph inside a line of text, centred on the text's body rather than sitting on its
     * baseline: the icon font's metrics differ from the text's, so a plain span rides high.
     */
    static final class GlyphSpan extends ReplacementSpan {
        private final Typeface face;
        private final float scale;
        private final int color;

        /** {@code color} 0 takes the text's own colour, which follows focus. */
        GlyphSpan(Typeface face, float scale, int color) {
            this.face = face;
            this.scale = scale;
            this.color = color;
        }

        private TextPaint glyph(Paint text) {
            TextPaint p = new TextPaint(text);
            p.setTypeface(face);
            p.setTextSize(text.getTextSize() * scale);
            if (color != 0) p.setColor(color);
            return p;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end, Paint.FontMetricsInt fm) {
            if (fm != null) paint.getFontMetricsInt(fm);
            return Math.round(glyph(paint).measureText(text, start, end));
        }

        @Override
        public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top, int y,
                int bottom, Paint paint) {
            TextPaint p = glyph(paint);
            float middle = y + (paint.ascent() + paint.descent()) / 2;
            canvas.drawText(text, start, end, x, middle - (p.ascent() + p.descent()) / 2, p);
        }
    }

    private static Typeface font(Context c, String asset, String fallback) {
        try {
            return Typeface.createFromAsset(c.getApplicationContext().getAssets(), asset);
        } catch (RuntimeException e) {
            return Typeface.create(fallback, Typeface.NORMAL);
        }
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** Black on light accents, white on dark ones. */
    static int ink(int color) {
        return Color.luminance(color) > 0.5f ? 0xFF000000 : 0xFFFFFFFF;
    }

    /** {@code a} moved {@code t} of the way to {@code b}, opaque. */
    static int mix(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    static TextView text(Context c, float sp, int color, Typeface face) {
        TextView v = new TextView(c);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setTextColor(color);
        v.setTypeface(face);
        v.setIncludeFontPadding(false);
        return v;
    }

    static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    /** Accent fill when focused, a faint fill otherwise. */
    static void focusStyle(TextView v, int accent, int idle, float radius) {
        StateListDrawable bg = new StateListDrawable();
        bg.addState(new int[] {android.R.attr.state_focused}, round(accent, radius));
        bg.addState(new int[0], round(idle, radius));
        v.setBackground(bg);
        v.setTextColor(focused(ink(accent), 0xD9FFFFFF));
    }

    /**
     * A chip icon to go with {@link #focusStyle}: {@code shape} in the chip's text colour and
     * {@code mark} in the accent, both turning the ink colour on the accent fill when focused.
     */
    static Drawable chipIcon(Drawable shape, Drawable mark, int accent, int size) {
        shape.mutate().setTintList(focused(ink(accent), 0xD9FFFFFF));
        mark.mutate().setTintList(focused(ink(accent), accent));
        LayerDrawable d = new LayerDrawable(new Drawable[] {shape, mark});
        d.setBounds(0, 0, size, size);
        return d;
    }

    private static ColorStateList focused(int on, int off) {
        return new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {on, off});
    }
}
