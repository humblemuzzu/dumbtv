package tv.dumbtv.quick;

/** Host-side test for AirPlay.next: java -cp <out> tv.dumbtv.quick.AirPlayTest */
public final class AirPlayTest {
    private static void eq(int want, int got, String what) {
        if (want != got) throw new AssertionError(what + ": want " + want + ", got " + got);
    }

    public static void main(String[] args) {
        String playerStart = "I/am_proc_start(  932): [0,5120,10192,com.apple.airplay.amazon.airplay,activity,"
                + "{com.apple.airplay.amazon.airplay/com.apple.airplay.amazon.airplay.MainActivity}]";
        String playerDied = "I/am_proc_died(  932): [0,5120,com.apple.airplay.amazon.airplay,900,17]";
        String daemonStart = "I/am_proc_start(  932): [0,4012,10192,com.amazon.airplaydaemon,service,"
                + "{com.apple.airplay.amazon.airplay/com.apple.airplay.amazon.airplay.EniraService}]";
        String daemonDied = "I/am_proc_died(  932): [0,4012,com.amazon.airplaydaemon,800,8]";
        String other = "I/am_proc_died(  932): [0,1950,com.android.providers.downloads,935,5963]";

        eq(AirPlay.IN_USE, AirPlay.next(AirPlay.READY, playerStart), "player start");
        eq(AirPlay.READY, AirPlay.next(AirPlay.IN_USE, playerDied), "player died");
        eq(AirPlay.READY, AirPlay.next(AirPlay.READY, daemonStart), "daemon start is not a session");
        eq(AirPlay.BROKEN, AirPlay.next(AirPlay.READY, daemonDied), "daemon died");
        eq(AirPlay.BROKEN, AirPlay.next(AirPlay.BROKEN, daemonStart), "restarted daemon stays broken");
        eq(AirPlay.BROKEN, AirPlay.next(AirPlay.BROKEN, playerStart), "broken until reboot");
        eq(AirPlay.IN_USE, AirPlay.next(AirPlay.IN_USE, other), "other process");
        eq(AirPlay.READY, AirPlay.next(AirPlay.READY, "I/DispService( 1): realInFmt[...]"), "other tag");
        System.out.println("AirPlayTest ok");
    }
}
