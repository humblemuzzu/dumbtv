package android.text;

/** The Android join used when a host test changes a list setting. */
public final class TextUtils {
    private TextUtils() {}
    public static String join(CharSequence delimiter, Iterable<?> items) {
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (Object item : items) {
            if (!first) out.append(delimiter);
            out.append(item);
            first = false;
        }
        return out.toString();
    }
}
