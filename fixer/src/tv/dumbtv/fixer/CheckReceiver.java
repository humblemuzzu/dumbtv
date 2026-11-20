package tv.dumbtv.fixer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The re-checks {@link Fix#onBoot} schedules. Not exported: only this app's alarms reach it. */
public final class CheckReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Fix.ACTION_RECHECK.equals(intent.getAction())) return;
        Fix.reviveAdb(context, "recheck", false);
    }
}
