package tv.dumbtv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;

/** Puts the home keeper and the filter watch back after a reboot or an update, and runs the watch. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // LOCKED_BOOT_COMPLETED can arrive before the user is unlocked on an encrypted TV. The
        // Keeper is not direct-boot-aware, so leave that boot to BOOT_COMPLETED.
        UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (users != null && !users.isUserUnlocked()) return;
        if (Filter.CHECK.equals(intent.getAction())) {
            Filter.wake(context);
            return;
        }
        Keeper.start(context);
        Filter.watch(context);
    }
}
