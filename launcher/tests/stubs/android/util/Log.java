package android.util;

/** Expected file failures need no Android logging service on the host. */
public final class Log {
    private Log() {}
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable error) { return 0; }
}
