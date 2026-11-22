package tv.dumbtv.quick;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

/**
 * Direct Binder client for MediaTek's CustomerPqService.
 * Controls hardware picture quality settings (Color Temperature, White Balance Gains, etc.)
 * with no root and no permissions required.
 */
public final class PqBridge {
    private static final String TAG = "dumbtv-pq";
    private static final String DESCRIPTOR = "com.mediatek.extservice.pqutils.ICustomerPqService";
    private static final int TRANSACTION_getFromPqCache = 1;
    private static final int TRANSACTION_setToPqCache = 2;

    public interface ConnectionListener {
        void onConnected();
    }

    private static volatile IBinder binder;
    private static volatile ConnectionListener connectionListener;
    private static boolean bound;

    private static final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = service;
            Log.i(TAG, "CustomerPqService connected");
            ConnectionListener l = connectionListener;
            if (l != null) {
                l.onConnected();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
            Log.w(TAG, "CustomerPqService disconnected");
        }
    };

    private PqBridge() {}

    public static synchronized void setConnectionListener(ConnectionListener listener) {
        connectionListener = listener;
        if (isConnected() && listener != null) {
            listener.onConnected();
        }
    }

    public static synchronized void bind(Context context) {
        if (bound && binder != null && binder.isBinderAlive()) return;
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.mediatek.customerpq", "com.mediatek.customerpq.CustomerPqService"));
            bound = context.getApplicationContext().bindService(intent, conn, Context.BIND_AUTO_CREATE);
            Log.i(TAG, "bindService returned: " + bound);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bind CustomerPqService", e);
        }
    }

    public static synchronized void unbind(Context context) {
        connectionListener = null;
        if (!bound) return;
        try {
            context.getApplicationContext().unbindService(conn);
        } catch (Exception ignored) {}
        bound = false;
        binder = null;
    }

    public static boolean isConnected() {
        return binder != null && binder.isBinderAlive();
    }

    public static String get(String key) {
        IBinder b = binder;
        if (b == null || !b.isBinderAlive() || key == null) return null;
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(key);
            b.transact(TRANSACTION_getFromPqCache, data, reply, 0);
            reply.readException();
            return reply.readString();
        } catch (RemoteException e) {
            Log.w(TAG, "get failed for " + key, e);
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    public static boolean set(String key, String val) {
        IBinder b = binder;
        if (b == null || !b.isBinderAlive() || key == null || val == null) {
            Log.w(TAG, "set failed: not connected (b=" + b + ")");
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(key);
            data.writeString(val);
            b.transact(TRANSACTION_setToPqCache, data, reply, 0);
            reply.readException();
            Log.i(TAG, "set " + key + " = " + val + " succeeded");
            return true;
        } catch (RemoteException e) {
            Log.w(TAG, "set failed for " + key + "=" + val, e);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
