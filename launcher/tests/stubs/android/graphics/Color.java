package android.graphics;

/** The file tests do not exercise Android color parsing. */
public final class Color {
    private Color() {}
    public static int parseColor(String value) {
        throw new UnsupportedOperationException("not used by the host file tests");
    }
}
