package com.jonathancruzate.metamusephonebridge;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/**
 * Tailscale guardian, v1.3.2 — the reviver design.
 *
 * History: v1.3.1 asserted Android's legacy secure setting
 * "always_on_vpn_app" each cycle so the OS would relaunch Tailscale.
 * Live test, 2026-11-03: after ~12 minutes of assertion cycles the
 * Settings UI still showed Always-on OFF (Jonathan checked and
 * switched it on manually). Research confirmed why: on modern
 * Android the only programmatic always-on read/write is
 * DevicePolicyManager.get/setAlwaysOnVpnPackage, restricted to
 * device-owner / profile-owner (MDM) apps; the legacy secure key is
 * not a live store, and the Settings app writes through binder calls
 * into the connectivity service. A regular app cannot assert
 * always-on for another app. Any design claiming otherwise is lying.
 *
 * So the guardian IS the reviver instead:
 *  - DETECT (unchanged): the tailnet is up iff some up, non-loopback
 *    interface holds an address in 100.64.0.0/10. Checked every
 *    bridge cycle (60 s). Battery-exemption state read via
 *    PowerManager (detect/report only — granting is user/shell only).
 *  - REVIVE: on the first down cycle of an episode, immediately try
 *    launching Tailscale's main activity — Tailscale reconnects on
 *    launch when its own state says it should be connected, which is
 *    exactly the killed-by-the-system case. Retries with backoff
 *    (2 min, 5 min, 15 min, then every 30 min) while the episode
 *    lasts. Background activity starts are restricted, so every
 *    attempt is best-effort and logged, success or failure.
 *  - ESCALATE: if an episode passes 5 minutes despite launch
 *    attempts, post ONE tappable notification per episode —
 *    "Tailscale is down — tap to reopen" — whose tap launches
 *    Tailscale (a user tap is never background-restricted).
 *  - REPORT: every Worker report carries the tailscale object,
 *    including down-episode count and the last recovery time, so
 *    Muse sees presence and recovery directly.
 *
 * The OS-level always-on toggle remains valuable and is set by the
 * user in Settings (done 2026-11-03); the guardian complements it —
 * it never claims to control it.
 */
public final class TailscaleGuard {
    public static final String PKG = "com.tailscale.ipn";
    private static final String CHANNEL = "tailscale-guard";
    private static final int NOTIF_ID = 7;
    private static final long ESCALATE_AFTER_MS = 5 * 60 * 1000;
    private static final long[] BACKOFF_MS = {
            0, 2 * 60 * 1000, 5 * 60 * 1000, 15 * 60 * 1000, 30 * 60 * 1000};

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

    /** Called once per bridge cycle. */
    public static void tick(Context ctx) {
        if (!installed(ctx)) return;
        String ip = tailnetIp(ctx);
        long now = System.currentTimeMillis();
        long since = Store.getLong(ctx, Store.K_TS_DOWN_SINCE, 0);

        if (!ip.isEmpty()) {
            if (since != 0) {
                long downFor = now - since;
                Store.put(ctx, Store.K_TS_DOWN_SINCE, 0L);
                Store.put(ctx, Store.K_TS_LAST_RECOVERED, now);
                Store.put(ctx, Store.K_TS_LAUNCH_COUNT, 0L);
                Store.put(ctx, Store.K_TS_NOTIFIED, false);
                BridgeLog.log(ctx, "TS_GUARD", "tailnet recovered: " + ip
                        + " (was down " + (downFor / 1000) + "s)");
                NotificationManager nm = (NotificationManager)
                        ctx.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(NOTIF_ID);
            }
            return;
        }

        // Down.
        if (since == 0) {
            since = now;
            Store.put(ctx, Store.K_TS_DOWN_SINCE, now);
            Store.put(ctx, Store.K_TS_DOWN_COUNT,
                    Store.getLong(ctx, Store.K_TS_DOWN_COUNT, 0) + 1);
            Store.put(ctx, Store.K_TS_LAUNCH_COUNT, 0L);
            Store.put(ctx, Store.K_TS_NOTIFIED, false);
            BridgeLog.log(ctx, "TS_GUARD",
                    "tailnet interface is DOWN (no 100.64/10 address)");
        }

        // Revive attempts on the backoff ladder.
        long attempts = Store.getLong(ctx, Store.K_TS_LAUNCH_COUNT, 0);
        long lastLaunch = Store.getLong(ctx, Store.K_TS_LAST_LAUNCH, 0);
        long wait = BACKOFF_MS[(int) Math.min(attempts, BACKOFF_MS.length - 1)];
        if (now - since >= wait && now - lastLaunch >= wait) {
            Store.put(ctx, Store.K_TS_LAST_LAUNCH, now);
            Store.put(ctx, Store.K_TS_LAUNCH_COUNT, attempts + 1);
            tryLaunch(ctx, "attempt " + (attempts + 1));
        }

        // One tappable escalation per episode.
        if (now - since > ESCALATE_AFTER_MS
                && !Store.getBool(ctx, Store.K_TS_NOTIFIED, false)) {
            Store.put(ctx, Store.K_TS_NOTIFIED, true);
            postDownNotification(ctx);
        }
    }

    private static void tryLaunch(Context ctx, String why) {
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(PKG);
            if (launch == null) {
                BridgeLog.log(ctx, "TS_GUARD", "revive " + why
                        + ": no launch intent for Tailscale");
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(launch);
            BridgeLog.log(ctx, "TS_GUARD", "revive " + why
                    + ": launched Tailscale UI");
        } catch (Exception e) {
            BridgeLog.log(ctx, "TS_GUARD", "revive " + why + " failed: "
                    + e.getClass().getSimpleName());
        }
    }

    private static void postDownNotification(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Tailscale guard", NotificationManager.IMPORTANCE_HIGH));
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(PKG);
            if (launch == null) return;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, NOTIF_ID, launch,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentTitle("Tailscale is down")
                    .setContentText("Tap to reopen Tailscale — Muse's phone lane is offline.")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(NOTIF_ID, n);
            BridgeLog.log(ctx, "TS_GUARD",
                    "escalated: posted tappable Tailscale-down notification");
        } catch (Exception e) {
            BridgeLog.log(ctx, "TS_GUARD", "escalation failed: "
                    + e.getClass().getSimpleName());
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
            o.put("reviver_armed", inst);
            o.put("down_since", Store.getLong(ctx, Store.K_TS_DOWN_SINCE, 0));
            o.put("down_count", Store.getLong(ctx, Store.K_TS_DOWN_COUNT, 0));
            o.put("last_recovered_ms", Store.getLong(ctx, Store.K_TS_LAST_RECOVERED, 0));
        } catch (Exception ignored) {}
        return o;
    }
}
