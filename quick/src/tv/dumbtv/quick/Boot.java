package tv.dumbtv.quick;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After a boot or an update, starts {@link Tint} again so the warmth comes back and the video
 * format is followed from the first stream. A sleep timer does not survive a reboot.
 */
public final class Boot extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        Tint.prefs(c).edit().remove(Sleep.AT).apply();
        Tint.refresh(c);
    }
}
