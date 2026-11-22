package tv.dumbtv.quick;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * The few Android 8 calls this app needs, through reflection: it is compiled against Android 7.1
 * (API 25) like the rest of dumbtv, so the same APK runs on Fire OS 6, 7 and 8.
 */
final class Compat {
    private Compat() {}

    /** Context.startForegroundService on Android 8+, startService before. */
    static void startForeground(Context c, Intent service) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Context.class.getMethod("startForegroundService", Intent.class).invoke(c, service);
                return;
            } catch (ReflectiveOperationException e) {
                // fall through
            }
        }
        c.startService(service);
    }

    /**
     * A notification builder for a silent, minimum-importance channel: Android 8+ will not show
     * a foreground service's notification without a channel.
     */
    @SuppressWarnings("deprecation") // setPriority: the pre-channel way, for Fire OS 6
    static Notification.Builder quietBuilder(Context c, String id, CharSequence name) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                Class<?> channel = Class.forName("android.app.NotificationChannel");
                Object ch = channel.getConstructor(String.class, CharSequence.class, int.class)
                        .newInstance(id, name, NotificationManager.IMPORTANCE_MIN);
                channel.getMethod("setShowBadge", boolean.class).invoke(ch, false);
                NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                NotificationManager.class.getMethod("createNotificationChannel", channel).invoke(nm, ch);
                return Notification.Builder.class.getConstructor(Context.class, String.class).newInstance(c, id);
            } catch (ReflectiveOperationException e) {
                // fall through to a channel-less builder
            }
        }
        return new Notification.Builder(c).setPriority(Notification.PRIORITY_MIN);
    }
}
