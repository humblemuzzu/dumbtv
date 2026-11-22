package tv.dumbtv.quick;

/**
 * The remote's key presses as {@link Tint}'s log reader sees them (logcat -v brief). Neither key
 * reaches an app: Fire OS's key policy takes both before any window or accessibility filter does,
 * so its own log lines are the signal. Kept free of Android classes for the host test.
 */
final class LogLines {
    private LogLines() {}

    /**
     * The settings (cog) button going down: "I/FireTVKeyPolicyManager( 948): Starting app key
     * service, received action down for keycode 176 ...". Amazon acts on the key coming up.
     */
    static boolean settingsKey(String line) {
        return line.contains("FireTVKeyPolicyManager") && line.contains("keycode 176");
    }

    /**
     * A long press of HOME, from system_server: PhoneWindowManager's warning about its own
     * long-press behaviour, and the stack Fire OS logs as it sends the long-press broadcast. Either
     * is enough; both come for one press, the broadcast's first.
     */
    static boolean longHome(String line) {
        return line.contains("Undefined home long press behavior") || line.contains("sendLongHomeBroadcasts");
    }

    /** The two tags {@link #longHome} reads, which carry nothing else the reader wants. */
    static boolean longHomeTag(String line) {
        return line.startsWith("W/WindowManager") || line.startsWith("W/ContextImpl");
    }
}
