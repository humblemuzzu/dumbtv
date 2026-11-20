package tv.dumbtv.quick;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

/**
 * Filter + Wi-Fi status check, and DNS filter restart broadcast.
 *
 * VPN status is checked via ConnectivityManager (TRANSPORT_VPN).
 * Wi-Fi signal and band are read from WifiManager (ACCESS_WIFI_STATE / ACCESS_NETWORK_STATE).
 * Restarting the filter broadcasts to personalDNSfilter's BootUpReceiver, identical to dumbtv home.
 */
final class Network {
    private static final ComponentName FILTER_BOOT =
            new ComponentName("dnsfilter.android", "dnsfilter.android.BootUpReceiver");

    private Network() {}

    static boolean isFilterOn(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network[] networks = cm.getAllNetworks();
        if (networks != null) {
            for (android.net.Network n : networks) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(n);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String wifiOrEthernet(Context c) {
        WifiManager wm = (WifiManager) c.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            WifiInfo info = wm.getConnectionInfo();
            if (info != null && info.getNetworkId() != -1 && info.getRssi() > -120) {
                String band = "";
                if (Build.VERSION.SDK_INT >= 21) {
                    int freq = info.getFrequency();
                    if (freq >= 4900 && freq <= 5900) band = "5 GHz";
                    else if (freq >= 5925 && freq <= 7125) band = "6 GHz";
                    else if (freq >= 2400 && freq <= 2500) band = "2.4 GHz";
                }
                int rssi = info.getRssi();
                if (!band.isEmpty()) return band + " " + rssi + " dBm";
                return rssi + " dBm";
            }
        }
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null) {
            NetworkInfo active = cm.getActiveNetworkInfo();
            if (active != null && active.isConnected()) {
                if (active.getType() == ConnectivityManager.TYPE_ETHERNET) return "Ethernet";
                return active.getTypeName();
            }
        }
        return "No network";
    }

    static String glance(Context c) {
        String filter = isFilterOn(c) ? c.getString(R.string.filter_on) : c.getString(R.string.filter_off);
        String net = wifiOrEthernet(c);
        return filter + " · " + net;
    }

    static void restartFilter(Context c) {
        c.sendBroadcast(new Intent().setComponent(FILTER_BOOT));
    }
}
