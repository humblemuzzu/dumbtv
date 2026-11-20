package tv.dumbtv.fixer;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Once per boot, after adbd has had time to start, switches ADB debugging off and on. */
public final class BootReceiver extends BroadcastReceiver {
    private static final long BOOT_SETTLE_MS = 20_000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        final Context app = context.getApplicationContext();
        final PendingResult pending = goAsync();
        new Thread(() -> {
            try {
                Thread.sleep(BOOT_SETTLE_MS);
                Fix.reviveAdb(app);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                pending.finish();
            }
        }, "revive-adb").start();
    }
}
