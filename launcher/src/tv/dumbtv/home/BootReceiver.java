package tv.dumbtv.home;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;

/** Puts the home keeper back after a reboot or an update. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // LOCKED_BOOT_COMPLETED can arrive before the user is unlocked on an encrypted TV. The
        // Keeper is not direct-boot-aware, so leave that boot to BOOT_COMPLETED.
        UserManager users = (UserManager) context.getSystemService(Context.USER_SERVICE);
        if (users != null && !users.isUserUnlocked()) return;
        Keeper.start(context);
    }
}
