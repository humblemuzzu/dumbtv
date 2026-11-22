package tv.dumbtv.quick;

/** Host-side test for LogLines: java -cp <out> tv.dumbtv.quick.LogLinesTest */
public final class LogLinesTest {
    private static void check(boolean want, boolean got, String what) {
        if (want != got) throw new AssertionError(what + ": want " + want + ", got " + got);
    }

    public static void main(String[] args) {
        // Fire OS 8.1.8.5, AFTHY7ABBA
        String cog = "I/FireTVKeyPolicyManager(  948): Starting app key service, received action down"
                + " for keycode 176 powerOn false isColdBoot false version 119 version property 119";
        String otherKey = "I/FireTVKeyPolicyManager(  948): Starting app key service, received action down"
                + " for keycode 178 powerOn false isColdBoot false version 119 version property 119";
        String stack = "W/ContextImpl(  948): Calling a method in the system process without a qualified user:"
                + " android.app.ContextImpl.sendOrderedBroadcast:1242 com.amazon.policy.keypolicymanager"
                + ".FireTVKeyPolicyManager.sendLongHomeBroadcasts:2923 com.amazon.policy.keypolicymanager"
                + ".FireTVKeyPolicyManager.handleLongPressOnHome:2863";
        String behavior = "W/WindowManager(  948): Undefined home long press behavior: 3";
        String otherStack = "W/ContextImpl(  948): Calling a method in the system process without a qualified"
                + " user: android.app.ContextImpl.sendBroadcast:1126 com.android.server.am.UserController";
        String otherWm = "W/WindowManager(  948): Failed looking up window callers=...";

        check(true, LogLines.settingsKey(cog), "cog");
        check(false, LogLines.settingsKey(otherKey), "input key is not the cog");
        check(false, LogLines.settingsKey(behavior), "long press is not the cog");
        check(true, LogLines.longHome(stack), "long press broadcast stack");
        check(true, LogLines.longHome(behavior), "long press behavior warning");
        check(false, LogLines.longHome(otherStack), "other system broadcast");
        check(false, LogLines.longHome(otherWm), "other window manager warning");
        check(false, LogLines.longHome(cog), "cog is not a long press");
        check(true, LogLines.longHomeTag(otherWm), "window manager tag");
        check(true, LogLines.longHomeTag(otherStack), "context tag");
        check(false, LogLines.longHomeTag("I/DispService( 1): realInFmt[...]"), "video tag");
        System.out.println("LogLinesTest ok");
    }
}
