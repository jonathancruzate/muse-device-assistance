package com.jonathancruzate.metamusedeviceassistance;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * BudsRouter — the host side of the plugin bus (v1.5.1).
 *
 * Per the plugin architecture (Jonathan, 2026-11-03; Galaxy Buds
 * design note §7.3), this host stays slim: it routes the gated
 * `buds_*` native templates to the Muse Galaxy Buds plugin app by
 * namespace and never interprets Buds semantics. The plugin owns
 * the SPP session, the protocol, and the status export; this class
 * owns only transport:
 *
 *   1. Build an explicit intent for the plugin's command service
 *      carrying {op, arg, nonce} plus a per-command reply
 *      PendingIntent minted by this app.
 *   2. Start the plugin's foreground service (falling back to its
 *      no-UI command activity if the service start is refused).
 *   3. Wait on the nonce's latch for the plugin to send the reply
 *      PendingIntent back with {ok, out, error} — delivered to
 *      BudsResultReceiver under this app's identity.
 *
 * A timeout is a typed failure, never a retry: the SPP link is
 * single-holder and the design forbids retry loops against it.
 */
public final class BudsRouter {
    private BudsRouter() {}

    static final String PLUGIN_PKG = "com.jonathancruzate.metamusebuds";
    static final String PLUGIN_SERVICE = "com.jonathancruzate.metamusebuds.BudsService";
    static final String PLUGIN_ACTIVITY = "com.jonathancruzate.metamusebuds.BudsCommandActivity";
    static final String ACTION_COMMAND = "com.jonathancruzate.metamusebuds.action.COMMAND";

    static final class Slot {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean ok;
        volatile String out;
        volatile String error;
    }

    private static final Map<String, Slot> SLOTS = new ConcurrentHashMap<>();

    /** Called by BudsResultReceiver when the plugin replies. */
    static void deliver(String nonce, boolean ok, String out, String error) {
        if (nonce == null) return;
        Slot s = SLOTS.get(nonce);
        if (s == null) return;
        s.ok = ok;
        s.out = out;
        s.error = error;
        s.latch.countDown();
    }

    /** Execute one routed buds op. Never throws. */
    public static LoopbackAdb.Result execute(Context ctx, String op, String arg) {
        long t0 = System.currentTimeMillis();
        try {
            ctx.getPackageManager().getPackageInfo(PLUGIN_PKG, 0);
        } catch (PackageManager.NameNotFoundException e) {
            return fail(t0, "plugin_not_installed: Muse Galaxy Buds plugin app is not installed");
        }
        String nonce = UUID.randomUUID().toString();
        Slot slot = new Slot();
        SLOTS.put(nonce, slot);
        try {
            Intent reply = new Intent(ctx, BudsResultReceiver.class);
            reply.putExtra("nonce", nonce);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, nonce.hashCode(), reply,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

            Intent i = new Intent(ACTION_COMMAND);
            i.putExtra("op", op);
            if (arg != null) i.putExtra("arg", arg);
            i.putExtra("nonce", nonce);
            i.putExtra("reply", pi);

            // Primary route: the plugin's foreground service.
            // Fallback: its no-UI command activity (same extras).
            Exception serviceError = null;
            try {
                Intent svc = new Intent(i);
                svc.setComponent(new ComponentName(PLUGIN_PKG, PLUGIN_SERVICE));
                ctx.startForegroundService(svc);
            } catch (Exception e) {
                serviceError = e;
            }
            if (serviceError != null) {
                try {
                    Intent act = new Intent(i);
                    act.setComponent(new ComponentName(PLUGIN_PKG, PLUGIN_ACTIVITY));
                    act.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                    ctx.startActivity(act);
                } catch (Exception e2) {
                    return fail(t0, "plugin_unreachable: service start failed ("
                            + serviceError.getClass().getSimpleName() + ": "
                            + serviceError.getMessage() + "); activity start failed ("
                            + e2.getClass().getSimpleName() + ": " + e2.getMessage() + ")");
                }
            }

            long timeoutMs = op.startsWith("buds_vol") ? 10_000 : 40_000;
            boolean done = slot.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            long ms = System.currentTimeMillis() - t0;
            if (!done) {
                return new LoopbackAdb.Result(false, "", ms,
                        "plugin_timeout: no result from the Buds plugin within "
                                + timeoutMs + "ms");
            }
            BridgeLog.log(ctx, "BUDS", op + " -> ok=" + slot.ok + " " + ms + "ms"
                    + (slot.error != null ? " error=" + slot.error : ""));
            if (slot.ok) {
                return new LoopbackAdb.Result(true,
                        slot.out == null ? "" : slot.out, ms, null);
            }
            return new LoopbackAdb.Result(false, "", ms,
                    slot.error == null ? "plugin_error" : slot.error);
        } catch (Exception e) {
            return fail(t0, e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            SLOTS.remove(nonce);
        }
    }

    private static LoopbackAdb.Result fail(long t0, String error) {
        return new LoopbackAdb.Result(false, "",
                System.currentTimeMillis() - t0, error);
    }
}
