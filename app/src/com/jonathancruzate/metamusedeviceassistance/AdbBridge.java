package com.jonathancruzate.metamusedeviceassistance;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ADB bridge, v1.1: endpoint discovery + self-heal.
 *
 * Why: the VM-side adb channel kept dying for structural reasons, all
 * phone-side — the wireless-debugging pairing lapsed when the setting
 * toggled, the adbd port CHANGED (41531 -> 40983) so every stored-port
 * consumer dialed a dead endpoint, and each recovery needed manual
 * phone-side steps. An app that lives on the phone owns all three:
 *
 *  - DISCOVERY: wireless debugging advertises _adb-tls-connect._tcp (and
 *    _adb-tls-pairing._tcp while a pairing window is open) over mDNS.
 *    This class discovers those services continuously via NsdManager,
 *    resolves them to host:port, flags the one whose host is this
 *    phone's own Wi-Fi address, and stores/reports the fresh endpoint.
 *    An endpoint CHANGE is logged as a first-class event.
 *  - SELF-HEAL: if the app holds WRITE_SECURE_SETTINGS — grantable once,
 *    while ADB is up, via
 *      adb shell pm grant com.jonathancruzate.metamusedeviceassistance \
 *          android.permission.WRITE_SECURE_SETTINGS
 *    — it re-enables wireless debugging (Settings.Global
 *    adb_wifi_enabled) whenever it finds it off, so a reboot or toggle
 *    no longer strands the channel until a human notices. Every heal is
 *    logged and reported; the heal switch in the UI can disarm it.
 *
 * The pairing itself is NOT touched here (that is the v1.2 local-shell
 * phase). This class never opens an ADB connection.
 */
public final class AdbBridge {
    private static final String TYPE_CONNECT = "_adb-tls-connect._tcp";
    private static final String TYPE_PAIRING = "_adb-tls-pairing._tcp";
    private static final String KEY_WIFI_DEBUG = "adb_wifi_enabled";

    private static NsdManager nsd;
    private static WifiManager.MulticastLock multicastLock;
    private static boolean discovering = false;
    private static final List<NsdServiceInfo> resolveQueue = new ArrayList<>();
    private static boolean resolving = false;
    private static final Map<String, JSONObject> endpoints = new HashMap<>();

    private AdbBridge() {}

    // ---------------------------------------------------------- state reads

    public static boolean wirelessDebuggingOn(Context ctx) {
        try {
            return Settings.Global.getInt(ctx.getContentResolver(), KEY_WIFI_DEBUG, 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean secureSettingsGranted(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean selfHealArmed(Context ctx) {
        return Store.getBool(ctx, Store.K_ADB_HEAL_ON, true);
    }

    public static String wifiIp(Context ctx) {
        // Prefer a wlan interface address; fall back to WifiManager.
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName().toLowerCase();
                if (!name.startsWith("wlan") && !name.startsWith("wifi")) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getConnectionInfo() != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if (ip != 0) {
                    return (ip & 0xff) + "." + ((ip >> 8) & 0xff) + "."
                            + ((ip >> 16) & 0xff) + "." + ((ip >> 24) & 0xff);
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ------------------------------------------------------------- self-heal

    /** Called once per bridge cycle. Heals wireless debugging if armed
     *  and permitted; logs grant-need at most every 6 hours. */
    public static void tick(Context ctx) {
        boolean on = wirelessDebuggingOn(ctx);
        if (on) return;
        if (!selfHealArmed(ctx)) return;
        if (!secureSettingsGranted(ctx)) {
            long last = Store.getLong(ctx, Store.K_ADB_HEAL_LAST_LOG, 0);
            if (System.currentTimeMillis() - last > 6 * 60 * 60 * 1000) {
                Store.put(ctx, Store.K_ADB_HEAL_LAST_LOG, System.currentTimeMillis());
                BridgeLog.log(ctx, "ADB_HEAL",
                        "wireless debugging is OFF and self-heal cannot run: "
                        + "WRITE_SECURE_SETTINGS not granted (one-time grant via "
                        + "adb shell pm grant, see README)");
            }
            return;
        }
        try {
            Settings.Global.putInt(ctx.getContentResolver(), KEY_WIFI_DEBUG, 1);
            boolean now = wirelessDebuggingOn(ctx);
            BridgeLog.log(ctx, "ADB_HEAL", "wireless debugging was OFF; re-enabled via "
                    + "WRITE_SECURE_SETTINGS; now=" + now);
        } catch (SecurityException se) {
            BridgeLog.log(ctx, "ADB_HEAL", "re-enable failed: SecurityException "
                    + "(grant revoked?)");
        } catch (Exception e) {
            BridgeLog.log(ctx, "ADB_HEAL", "re-enable failed: " + e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------- discovery

    public static synchronized void startDiscovery(Context ctx) {
        if (discovering) return;
        final Context app = ctx.getApplicationContext();
        try {
            WifiManager wm = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
            if (wm != null) {
                multicastLock = wm.createMulticastLock("muse-device-assistance");
                multicastLock.setReferenceCounted(false);
                multicastLock.acquire();
            }
        } catch (Exception ignored) {}
        nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            BridgeLog.log(app, "ADB_DISC", "NsdManager unavailable");
            return;
        }
        discovering = true;
        discover(app, TYPE_CONNECT);
        discover(app, TYPE_PAIRING);
        BridgeLog.log(app, "ADB_DISC", "discovery started (" + TYPE_CONNECT + ", " + TYPE_PAIRING + ")");
    }

    private static void discover(final Context app, final String type) {
        try {
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD,
                    new NsdManager.DiscoveryListener() {
                        @Override public void onDiscoveryStarted(String t) {}
                        @Override public void onDiscoveryStopped(String t) {}
                        @Override public void onStartDiscoveryFailed(String t, int e) {
                            BridgeLog.log(app, "ADB_DISC", "start failed " + t + " err=" + e);
                        }
                        @Override public void onStopDiscoveryFailed(String t, int e) {}
                        @Override public void onServiceFound(NsdServiceInfo info) {
                            queueResolve(app, info);
                        }
                        @Override public void onServiceLost(NsdServiceInfo info) {
                            String key = info.getServiceType() + "/" + info.getServiceName();
                            JSONObject removed;
                            synchronized (endpoints) { removed = endpoints.remove(key); }
                            if (removed != null) {
                                BridgeLog.log(app, "ADB_DISC", "service lost: " + key);
                                persist(app);
                            }
                        }
                    });
        } catch (Exception e) {
            BridgeLog.log(app, "ADB_DISC", "discover " + type + " threw "
                    + e.getClass().getSimpleName());
        }
    }

    private static void queueResolve(Context app, NsdServiceInfo info) {
        synchronized (resolveQueue) {
            resolveQueue.add(info);
            if (!resolving) {
                resolving = true;
                resolveNext(app);
            }
        }
    }

    private static void resolveNext(final Context app) {
        final NsdServiceInfo next;
        synchronized (resolveQueue) {
            if (resolveQueue.isEmpty()) {
                resolving = false;
                return;
            }
            next = resolveQueue.remove(0);
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override public void onResolveFailed(NsdServiceInfo info, int e) {
                    resolveNext(app);
                }
                @Override public void onServiceResolved(NsdServiceInfo info) {
                    recordResolved(app, info);
                    resolveNext(app);
                }
            });
        } catch (Exception e) {
            resolveNext(app);
        }
    }

    private static void recordResolved(Context app, NsdServiceInfo info) {
        try {
            String host = info.getHost() == null ? "" : info.getHost().getHostAddress();
            int port = info.getPort();
            String type = info.getServiceType();
            boolean isPairing = type != null && type.contains("pairing");
            boolean own = host != null && host.equals(wifiIp(app));
            JSONObject ep = new JSONObject();
            ep.put("type", isPairing ? "pairing" : "connect");
            ep.put("name", info.getServiceName());
            ep.put("host", host);
            ep.put("port", port);
            ep.put("own", own);
            ep.put("at", System.currentTimeMillis());
            String key = type + "/" + info.getServiceName();
            synchronized (endpoints) { endpoints.put(key, ep); }
            if (!isPairing) {
                String endpoint = host + ":" + port;
                String prev = Store.get(app, Store.K_ADB_ENDPOINT, "");
                if (own || prev.isEmpty()) {
                    Store.put(app, Store.K_ADB_ENDPOINT, endpoint);
                    Store.put(app, Store.K_ADB_ENDPOINT_AT, System.currentTimeMillis());
                }
                if (!endpoint.equals(prev)) {
                    BridgeLog.log(app, "ADB_ENDPOINT", "connect endpoint "
                            + (prev.isEmpty() ? "" : prev + " -> ") + endpoint
                            + (own ? " (this device)" : " (not flagged as this device)"));
                }
            } else {
                Store.put(app, Store.K_ADB_PAIRING_OPEN, true);
                Store.put(app, Store.K_ADB_ENDPOINT_AT, System.currentTimeMillis());
                BridgeLog.log(app, "ADB_ENDPOINT", "pairing window advertised at "
                        + host + ":" + port);
            }
            persist(app);
        } catch (Exception ignored) {}
    }

    private static void persist(Context app) {
        try {
            JSONArray arr = new JSONArray();
            synchronized (endpoints) {
                for (JSONObject ep : endpoints.values()) arr.put(ep);
            }
            Store.put(app, Store.K_ADB_ENDPOINTS_JSON, arr.toString());
            boolean pairing = false;
            synchronized (endpoints) {
                for (JSONObject ep : endpoints.values()) {
                    if ("pairing".equals(ep.optString("type"))) pairing = true;
                }
            }
            Store.put(app, Store.K_ADB_PAIRING_OPEN, pairing);
            Store.put(app, Store.K_ADB_WIFI_IP, wifiIp(app));
        } catch (Exception ignored) {}
    }

    public static synchronized void stopDiscovery(Context ctx) {
        // Discovery listeners are intentionally left registered for the
        // life of the process in v1.1 (the bridge service owns the
        // lifecycle); this only drops the multicast lock for symmetry.
        if (multicastLock != null && multicastLock.isHeld()) {
            try { multicastLock.release(); } catch (Exception ignored) {}
        }
    }

    // -------------------------------------------------------------- snapshot

    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("wireless_debugging", wirelessDebuggingOn(ctx));
            o.put("secure_settings_granted", secureSettingsGranted(ctx));
            o.put("self_heal_armed", selfHealArmed(ctx));
            o.put("endpoint", Store.get(ctx, Store.K_ADB_ENDPOINT, ""));
            o.put("endpoint_at", Store.getLong(ctx, Store.K_ADB_ENDPOINT_AT, 0));
            o.put("pairing_open", Store.getBool(ctx, Store.K_ADB_PAIRING_OPEN, false));
            o.put("wifi_ip", wifiIp(ctx));
            String eps = Store.get(ctx, Store.K_ADB_ENDPOINTS_JSON, "[]");
            o.put("endpoints", new JSONArray(eps));
        } catch (Exception ignored) {}
        return o;
    }
}
