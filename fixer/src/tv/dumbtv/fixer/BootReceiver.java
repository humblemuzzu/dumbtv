package tv.dumbtv.fixer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * BOOT_COMPLETED: see {@link Fix}. The work runs inside onReceive on purpose: Android keeps a
 * process alive while it runs a receiver, but on this TV it killed the process ("empty") while a
 * goAsync() thread was between "off" and "on", which left ADB off.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        Fix.onBoot(context);
    }
}
