package com.jonathancruzate.metamusephonebridge;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.PowerManager;
import android.provider.Settings;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Tailscale guardian, v1.3.1 (Jonathan's idea, 2026-11-03 02:16 EST —
 * "We can self heal tailscale with the app").
 *
 * The failure it answers: on 2026-11-03 the phone vanished from the
 * tailnet after a network change; every VM-side heal step worked
 * (fresh endpoint, fresh port, fresh forwarder) and the last hop was
 * still dead, because nothing on the phone owned Tailscale's survival.
 * The VM cannot revive a killed VPN app. The phone can:
 *
 *  - ASSERT: Android's Always-on VPN is a secure setting
 *    (Settings.Secure "always_on_vpn_app") — the same key the Settings
 *    UI writes. This app already holds WRITE_SECURE_SETTINGS (the ADB
 *    self-heal grant), so each cycle it asserts that key for Tailscale
 *    (com.tailscale.ipn) and clears lockdown if it is ever set for it
 *    (lockdown would take ALL traffic down with the VPN — never wanted
 *    here). With the key set, the OS itself relaunches Tailscale's
 *    VpnService whenever it dies; no watchdog polling is needed.
 *  - DETECT: the tailnet is up iff some up, non-loopback interface
 *    holds an address in 100.64.0.0/10 (Tailscale's CGNAT range).
 *    Tailscale's battery-exemption state is read via
 *    PowerManager.isIgnoringBatteryOptimizations (any package may be
 *    queried; the exemption itself can only be GRANTED by the user or
 *    by shell — the guardian detects and reports, never grants).
 *  - REPORT: every Worker report carries a "tailscale" object, so
 *    Muse sees tailnet presence directly instead of inferring it
 *    from failed connects minutes later.
 *  - FALLBACK: if the interface stays down for 10+ minutes despite
 *    the always-on assertion, the guardian tries launching Tailscale's
 *    main activity (best effort — background activity starts are
 *    restricted; a foreground-service context plus a user-visible app
 *    makes it worth the attempt), throttled to once per 30 minutes.
 */
public final class TailscaleGuard {
    public static final String PKG = "com.tailscale.ipn";
    private static final String KEY_ALWAYS_ON = "always_on_vpn_app";
    private static final String KEY_LOCKDOWN = "always_on_vpn_lockdown";
    private static final long DOWN_BEFORE_LAUNCH_MS = 10 * 60 * 1000;
    private static final long LAUNCH_THROTTLE_MS = 30 * 60 * 1000;

    private TailscaleGuard() {}

    public static boolean installed(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** This phone's tailnet IPv4 (100.64.0.0/10), or "" when down. */
    public static String tailnetIp(Context ctx) {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || addr.isLoopbackAddress()) continue;
                    byte[] b = addr.getAddress();
                    int o1 = b[0] & 0xff, o2 = b[1] & 0xff;
                    if (o1 == 100 && o2 >= 64 && o2 <= 127) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static boolean batteryExempt(Context ctx) {
        try {
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(PKG);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean alwaysOnAsserted(Context ctx) {
        try {
            return PKG.equals(Settings.Secure.getString(
                    ctx.getContentResolver(), KEY_ALWAYS_ON));
        } catch (Exception e) {
            return false;
        }
    }

    /** Called once per bridge cycle. */
    public static void tick(Context ctx) {
        if (!installed(ctx)) return;
        boolean granted = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;

        // ASSERT: make the OS the reviver.
        if (granted && !alwaysOnAsserted(ctx)) {
            try {
                Settings.Secure.putString(ctx.getContentResolver(), KEY_ALWAYS_ON, PKG);
                BridgeLog.log(ctx, "TS_GUARD", "asserted always_on_vpn_app="
                        + PKG + " (OS now relaunches Tailscale when it dies)");
            } catch (Exception e) {
                BridgeLog.log(ctx, "TS_GUARD", "always-on assert failed: "
                        + e.getClass().getSimpleName());
            }
        }
        if (granted && alwaysOnAsserted(ctx)) {
            try {
                String lockdown = Settings.Secure.getString(
                        ctx.getContentResolver(), KEY_LOCKDOWN);
                if ("1".equals(lockdown)) {
                    Settings.Secure.putString(ctx.getContentResolver(), KEY_LOCKDOWN, "0");
                    BridgeLog.log(ctx, "TS_GUARD",
                            "cleared always_on_vpn_lockdown (lockdown would take all "
                            + "traffic down with the VPN)");
                }
            } catch (Exception ignored) {}
        }

        // DETECT + FALLBACK: track the down window; try a launch when
        // the assertion alone has not brought the interface back.
        String ip = tailnetIp(ctx);
        long now = System.currentTimeMillis();
        if (!ip.isEmpty()) {
            if (Store.getLong(ctx, Store.K_TS_DOWN_SINCE, 0) != 0) {
                BridgeLog.log(ctx, "TS_GUARD", "tailnet is back: " + ip);
            }
            Store.put(ctx, Store.K_TS_DOWN_SINCE, 0L);
        } else {
            long since = Store.getLong(ctx, Store.K_TS_DOWN_SINCE, 0);
            if (since == 0) {
                Store.put(ctx, Store.K_TS_DOWN_SINCE, now);
                BridgeLog.log(ctx, "TS_GUARD",
                        "tailnet interface is DOWN (no 100.64/10 address)");
            } else if (now - since > DOWN_BEFORE_LAUNCH_MS
                    && now - Store.getLong(ctx, Store.K_TS_LAST_LAUNCH, 0) > LAUNCH_THROTTLE_MS) {
                Store.put(ctx, Store.K_TS_LAST_LAUNCH, now);
                try {
                    Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(PKG);
                    if (launch != null) {
                        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(launch);
                        BridgeLog.log(ctx, "TS_GUARD",
                                "tailnet down >10 min despite always-on; launched "
                                + "Tailscale UI as a fallback");
                    }
                } catch (Exception e) {
                    BridgeLog.log(ctx, "TS_GUARD", "fallback launch failed: "
                            + e.getClass().getSimpleName());
                }
            }
        }
    }

    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            boolean inst = installed(ctx);
            o.put("installed", inst);
            String ip = inst ? tailnetIp(ctx) : "";
            o.put("up", !ip.isEmpty());
            o.put("ip", ip);
            o.put("battery_exempt", inst && batteryExempt(ctx));
            o.put("always_on", inst && alwaysOnAsserted(ctx));
            o.put("down_since", Store.getLong(ctx, Store.K_TS_DOWN_SINCE, 0));
        } catch (Exception ignored) {}
        return o;
    }
}
