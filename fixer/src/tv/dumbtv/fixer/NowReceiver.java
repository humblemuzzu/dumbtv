package tv.dumbtv.fixer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Test hook: the boot-time check on demand. The manifest requires android.permission.DUMP,
 * which adb's shell holds and installed apps cannot, so only `adb shell am broadcast` reaches it:
 *   adb shell am broadcast -n tv.dumbtv.fixer/.NowReceiver
 * With ADB on, it switches ADB off and on, so the adb connection drops for a few seconds.
 */
public final class NowReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Fix.reviveAdb(context, "on request", true);
    }
}
