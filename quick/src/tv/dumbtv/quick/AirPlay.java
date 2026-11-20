package tv.dumbtv.quick;

/**
 * AirPlay status from the system event log. On Fire OS the AirPlay app runs as two processes:
 * the daemon {@code com.amazon.airplaydaemon} (mDNS adverts and the receiver, started only at
 * boot) and the player {@code com.apple.airplay.amazon.airplay} (the screen during a session).
 * The player alive means a session (or one just left); the daemon dying means Macs cannot see
 * the TV until it reboots, since nothing but BOOT_COMPLETED starts it again, and a daemon that
 * restarted itself was seen to stop answering mDNS.
 *
 * Matches the process name field only: the daemon's own start line names its service component
 * {@code com.apple.airplay.amazon.airplay/...}, which must not count as the player.
 * Pure Java, so quick/tests/AirPlayTest.java runs it on a computer.
 */
final class AirPlay {
    static final int READY = 0, IN_USE = 1, BROKEN = 2;
    static final String PLAYER = "com.apple.airplay.amazon.airplay";
    static final String DAEMON = "com.amazon.airplaydaemon";

    private AirPlay() {}

    /**
     * The state after one {@code logcat -b events -v brief} line, e.g.
     * {@code I/am_proc_start(  932): [0,2583,10110,<process>,service,{...}]} or
     * {@code I/am_proc_died(  932): [0,1950,<process>,935,5963]}.
     */
    static int next(int state, String line) {
        boolean start = line.contains("am_proc_start");
        if (!start && !line.contains("am_proc_died")) return state;
        String proc = field(line, start ? 3 : 2);
        if (PLAYER.equals(proc)) {
            if (state == BROKEN) return BROKEN;
            return start ? IN_USE : READY;
        }
        if (DAEMON.equals(proc) && !start) return BROKEN;
        return state;
    }

    private static String field(String line, int index) {
        int open = line.indexOf('[');
        if (open < 0) return null;
        String[] parts = line.substring(open + 1).split(",", index + 2);
        return parts.length > index ? parts[index].trim() : null;
    }
}
