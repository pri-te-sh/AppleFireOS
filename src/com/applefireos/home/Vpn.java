package com.applefireos.home;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a VPN is carrying the TV's traffic, and which app runs it. Android hides a VPN's owner
 * from other apps, so the owner comes from `dumpsys connectivity` (needs the DUMP grant).
 */
final class Vpn {
    private static final Pattern OWNER = Pattern.compile("OwnerUid: (\\d+)");
    private static ConnectivityManager.NetworkCallback callback;

    private Vpn() {}

    private static Network find(ConnectivityManager cm) {
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return n;
        }
        return null;
    }

    static boolean isOn(Context c) {
        return find(c.getSystemService(ConnectivityManager.class)) != null;
    }

    /** {"on":true,"pkg":"…","label":"MyVPN","iface":"tun0"} or {"on":false}. */
    static String state(Context c) {
        JSONObject out = new JSONObject();
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            Network vpn = find(cm);
            out.put("on", vpn != null);
            if (vpn == null) return out.toString();
            LinkProperties lp = cm.getLinkProperties(vpn);
            if (lp != null) out.put("iface", lp.getInterfaceName());
            String pkg = owner(c, cm.getNetworkCapabilities(vpn));
            if (pkg != null) {
                out.put("pkg", pkg);
                PackageManager pm = c.getPackageManager();
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    out.put("label", pm.getApplicationLabel(ai).toString());
                } catch (PackageManager.NameNotFoundException ignored) {
                }
                // The app's own status line names the location you picked (e.g. "India - Delhi"),
                // which can differ from where its server physically is (virtual locations).
                String[] note = RecsListener.ongoing(pkg);
                if (note != null) out.put("note", new JSONObject().put("title", note[0]).put("text", note[1]));
            }
        } catch (JSONException | RuntimeException ignored) {
        }
        return out.toString();
    }

    private static String owner(Context c, NetworkCapabilities caps) {
        int uid = -1;
        if (Build.VERSION.SDK_INT >= 29 && caps != null) uid = caps.getOwnerUid();
        if (uid <= 0) uid = ownerFromDump();
        if (uid <= 0) return null;
        String[] pkgs = c.getPackageManager().getPackagesForUid(uid);
        return pkgs != null && pkgs.length > 0 ? pkgs[0] : null;
    }

    private static int ownerFromDump() {
        try {
            java.lang.Process p = new ProcessBuilder("dumpsys", "connectivity").redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (!line.contains("type: VPN")) continue;
                    Matcher m = OWNER.matcher(line);
                    if (m.find()) return Integer.parseInt(m.group(1));
                }
            } finally {
                p.destroy();
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    static boolean alertsOn(Context c) {
        return c.getSharedPreferences("vpn", Context.MODE_PRIVATE).getBoolean("alerts", true);
    }

    static void setAlerts(Context c, boolean on) {
        c.getSharedPreferences("vpn", Context.MODE_PRIVATE).edit().putBoolean("alerts", on).apply();
    }

    /** Calls onChange whenever a VPN comes up or goes away; null stops watching. */
    static void watch(Context c, Runnable onChange) {
        ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
        if (callback != null) {
            try {
                cm.unregisterNetworkCallback(callback);
            } catch (RuntimeException ignored) {
            }
            callback = null;
        }
        if (onChange == null) return;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { onChange.run(); }
            @Override public void onLost(Network network) { onChange.run(); }
        };
        NetworkRequest vpnOnly = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        try {
            cm.registerNetworkCallback(vpnOnly, callback);
        } catch (RuntimeException e) {
            callback = null;
        }
    }
}
