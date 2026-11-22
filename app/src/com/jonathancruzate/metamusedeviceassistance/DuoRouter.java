package com.jonathancruzate.metamusedeviceassistance;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * DuoRouter — the host side of the Duo plugin bus (v1.31.0).
 *
 * The Duo feature set (wake ritual, Duo debug mode, posture, the
 * wake pair) was extracted from this core into the separate Muse
 * Duo Companion plugin (com.jonathancruzate.metamuseduo), the
 * Galaxy Buds pattern: this class routes the gated `duo_*` native
 * templates to the plugin by namespace and never interprets Duo
 * semantics. It owns only transport, three host duties:
 *
 *   1. Command routing (BudsRouter's shape): explicit intent to
 *      the plugin's command service with {op, arg, nonce} plus a
 *      per-command reply PendingIntent minted here; the plugin's
 *      service is guarded by its signature-level COMMANDS
 *      permission and both apps share the family cert, so the
 *      platform enforces the lane.
 *   2. One-time state migration: the legacy Duo Store keys (the
 *      pre-extraction core state) are handed to the plugin once
 *      (op duo_migrate) and the plugin applies them only while it
 *      has no state of its own.
 *   3. Report passthrough: the plugin's duo snapshot JSON is
 *      cached here and returned for the report's duo object; a
 *      stale-but-real cache beats a fabricated one, and a missing
 *      plugin reports plugin_present=false honestly.
 */
public final class DuoRouter {
    private DuoRouter() {}

    static final String PLUGIN_PKG = "com.jonathancruzate.metamuseduo";
    static final String PLUGIN_SERVICE = "com.jonathancruzate.metamuseduo.DuoService";
    static final String PLUGIN_ACTIVITY = "com.jonathancruzate.metamuseduo.DuoCommandActivity";
    static final String ACTION_COMMAND = "com.jonathancruzate.metamuseduo.action.COMMAND";

    private static final long SNAPSHOT_FRESH_MS = 30_000;
    private static volatile boolean snapshotInFlight = false;

    static final class Slot {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean ok;
        volatile String out;
        volatile String error;
    }

    private static final Map<String, Slot> SLOTS = new ConcurrentHashMap<>();

    /** Called by DuoResultReceiver when the plugin replies. */
    static void deliver(String nonce, boolean ok, String out, String error) {
        if (nonce == null) return;
        Slot s = SLOTS.get(nonce);
        if (s == null) return;
        s.ok = ok;
        s.out = out;
        s.error = error;
        s.latch.countDown();
    }

    public static boolean pluginInstalled(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(PLUGIN_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Execute one routed duo op. Never throws. */
    public static LoopbackAdb.Result execute(Context ctx, String op, String arg) {
        long t0 = System.currentTimeMillis();
        if (!pluginInstalled(ctx)) {
            return fail(t0, "plugin_not_installed: Muse Duo Companion plugin "
                    + "app is not installed");
        }
        migrateIfNeeded(ctx);
        String nonce = UUID.randomUUID().toString();
        Slot slot = new Slot();
        SLOTS.put(nonce, slot);
        try {
            Intent reply = new Intent(ctx, DuoResultReceiver.class);
            reply.putExtra("nonce", nonce);
            PendingIntent pi = PendingIntent.getBroadcast(ctx, nonce.hashCode(), reply,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);

            Intent i = new Intent(ACTION_COMMAND);
            i.putExtra("op", op);
            if (arg != null) i.putExtra("arg", arg);
            i.putExtra("nonce", nonce);
            i.putExtra("reply", pi);

            Exception serviceError = null;
            try {
                Intent svc = new Intent(i);
                svc.setComponent(new ComponentName(PLUGIN_PKG, PLUGIN_SERVICE));
                ctx.startService(svc);
            } catch (Exception e) {
                serviceError = e;
            }
            if (serviceError != null) {
                try {
                    Intent fgs = new Intent(i);
                    fgs.setComponent(new ComponentName(PLUGIN_PKG, PLUGIN_SERVICE));
                    ctx.startForegroundService(fgs);
                } catch (Exception e1) {
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
            }

            long timeoutMs = 20_000;
            boolean done = slot.latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            long ms = System.currentTimeMillis() - t0;
            if (!done) {
                return new LoopbackAdb.Result(false, "", ms,
                        "plugin_timeout: no result from the Duo plugin within "
                                + timeoutMs + "ms");
            }
            BridgeLog.log(ctx, "DUO", op + " -> ok=" + slot.ok + " " + ms + "ms"
                    + (slot.error != null ? " error=" + slot.error : ""));
            if (slot.ok) {
                if ("duo_snapshot".equals(op) && slot.out != null) {
                    cacheSnapshot(ctx, slot.out);
                }
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

    /** BridgeService start on the Duo profile: make sure the
     *  plugin service is resident (its wake watcher lives there)
     *  and run the one-time migration. Fire-and-forget; command
     *  routing re-checks everything lazily anyway. */
    public static void onBridgeStart(final Context ctx) {
        if (!pluginInstalled(ctx)) {
            BridgeLog.log(ctx, "DUO",
                    "Duo plugin NOT installed; duo_* commands will refuse typed");
            return;
        }
        new Thread(() -> {
            try {
                Intent svc = new Intent();
                svc.setComponent(new ComponentName(PLUGIN_PKG, PLUGIN_SERVICE));
                try {
                    ctx.startForegroundService(svc);
                } catch (Exception e) {
                    ctx.startService(svc);
                }
            } catch (Exception e) {
                BridgeLog.log(ctx, "DUO", "plugin service start failed: "
                        + e.getClass().getSimpleName() + " " + e.getMessage());
            }
            migrateIfNeeded(ctx);
            refreshSnapshot(ctx);
        }, "duo-router-start").start();
    }

    /** One-time host-to-plugin handover of the legacy Duo state.
     *  The payload is built from the pre-extraction Store keys;
     *  the plugin applies it only while unmigrated, and the host
     *  marks its own flag on the plugin's ok so a re-send can
     *  never clobber live plugin state. */
    public static synchronized void migrateIfNeeded(Context ctx) {
        if (Store.getBool(ctx, Store.K_DUO_MIGRATED, false)) return;
        if (!pluginInstalled(ctx)) return;
        try {
            JSONObject o = new JSONObject();
            o.put("ritual_enabled", Store.getBool(ctx, Store.K_DUO_RITUAL_ENABLED, false));
            o.put("fires", Store.getLong(ctx, Store.K_DUO_FIRES, 0));
            o.put("last_fire_at", Store.getLong(ctx, Store.K_DUO_LAST_FIRE_AT, 0));
            o.put("last_at", Store.getLong(ctx, Store.K_DUO_LAST_AT, 0));
            o.put("last_result", Store.get(ctx, Store.K_DUO_LAST_RESULT, ""));
            o.put("posture_source", Store.get(ctx, Store.K_DUO_POSTURE_SOURCE, ""));
            o.put("panels_prop", Store.get(ctx, Store.K_DUO_PANELS_PROP, "unknown"));
            o.put("suppressions", Store.getLong(ctx, Store.K_DUO_DEBUG_SUPPRESSIONS, 0));
            // Debug state is time-boxed in the plugin (TTL from
            // set_at); hand over the raw flag + stamp and let the
            // plugin's TTL judge it.
            o.put("debug", Store.getBool(ctx, Store.K_DUO_DEBUG, false));
            o.put("debug_set_at", Store.getLong(ctx, Store.K_DUO_DEBUG_SET_AT, 0));
            LoopbackAdb.Result r = executeDirect(ctx, "duo_migrate", o.toString());
            if (r.ok) {
                Store.put(ctx, Store.K_DUO_MIGRATED, true);
                BridgeLog.log(ctx, "DUO", "legacy Duo state handed to plugin: " + r.stdoutTail);
            } else {
                BridgeLog.log(ctx, "DUO", "Duo state migration not done: " + r.error);
            }
        } catch (Exception e) {
            BridgeLog.log(ctx, "DUO", "Duo state migration failed: "
                    + e.getClass().getSimpleName());
        }
    }

    /** execute() without the migration pre-check (migration itself
     *  routes through here to avoid recursion). */
    private static LoopbackAdb.Result executeDirect(Context ctx, String op, String arg) {
        // Temporarily mark migrated so execute() does not recurse;
        // the caller owns the flag's real value.
        boolean was = Store.getBool(ctx, Store.K_DUO_MIGRATED, false);
        Store.put(ctx, Store.K_DUO_MIGRATED, true);
        try {
            return execute(ctx, op, arg);
        } finally {
            Store.put(ctx, Store.K_DUO_MIGRATED, was);
        }
    }

    private static void cacheSnapshot(Context ctx, String json) {
        try {
            new JSONObject(json); // validate before caching
            Store.put(ctx, Store.K_DUO_SNAPSHOT_CACHE, json);
            Store.put(ctx, Store.K_DUO_SNAPSHOT_AT, System.currentTimeMillis());
        } catch (Exception ignored) {}
    }

    /** Refresh the cached snapshot from the plugin (blocking; call
     *  from a background thread). */
    public static void refreshSnapshot(Context ctx) {
        LoopbackAdb.Result r = execute(ctx, "duo_snapshot", null);
        if (!r.ok) {
            BridgeLog.log(ctx, "DUO", "snapshot refresh failed: " + r.error);
        }
    }

    /** The report's duo object: the plugin's snapshot, cached.
     *  Returns immediately (never blocks the report thread); a
     *  stale cache triggers a background refresh. With no plugin,
     *  an honest plugin_present=false object. */
    public static JSONObject snapshot(Context ctx) {
        JSONObject fallback = new JSONObject();
        try {
            fallback.put("ritual_enabled", false);
            fallback.put("panels_prop", "unknown");
            fallback.put("posture_source", "");
            fallback.put("last_fire_at", 0);
            fallback.put("last_at", 0);
            fallback.put("last_result", "");
            fallback.put("fires", 0);
            fallback.put("debug", false);
            fallback.put("debug_set_at", 0);
            fallback.put("suppressions", 0);
            fallback.put("plugin_version", "");
            fallback.put("plugin_present", false);
            fallback.put("wake_pair_enabled", false);
            fallback.put("wake_pair_mode", "");
            fallback.put("wake_pair_last_at", 0);
            fallback.put("wake_pair_last_result", "");
        } catch (Exception ignored) {}
        if (!pluginInstalled(ctx)) return fallback;
        String cache = Store.get(ctx, Store.K_DUO_SNAPSHOT_CACHE, "");
        long at = Store.getLong(ctx, Store.K_DUO_SNAPSHOT_AT, 0);
        if (!cache.isEmpty()) {
            try {
                JSONObject o = new JSONObject(cache);
                if (System.currentTimeMillis() - at > SNAPSHOT_FRESH_MS
                        && !snapshotInFlight) {
                    snapshotInFlight = true;
                    new Thread(() -> {
                        try { refreshSnapshot(ctx); } finally { snapshotInFlight = false; }
                    }, "duo-snapshot-refresh").start();
                }
                return o;
            } catch (Exception ignored) {}
        }
        // No cache yet: one bounded synchronous fetch (the report
        // thread can afford this once; afterwards the cache path
        // above serves).
        refreshSnapshot(ctx);
        String after = Store.get(ctx, Store.K_DUO_SNAPSHOT_CACHE, "");
        if (!after.isEmpty()) {
            try { return new JSONObject(after); } catch (Exception ignored) {}
        }
        return fallback;
    }

    private static LoopbackAdb.Result fail(long t0, String error) {
        return new LoopbackAdb.Result(false, "",
                System.currentTimeMillis() - t0, error);
    }
}
