package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ShellBridge — the v1.4 command channel (design:
 * docs/loopback-shell-bridge-v1.4-design-2026-11-04.md).
 *
 * Each bridge cycle, after desired state is fetched, the bridge may
 * pull ONE structured command {id, template, args[]} from the Worker,
 * gate it through ShellAllowlist (the Worker is a transport, never an
 * authority — it cannot widen the allowlist), execute it over the
 * loopback ADB connection, and post the result back over the same
 * channel. Every execution lands in the on-device bridge log with its
 * full argv; the report summary carries only id/template/ok/ms.
 *
 * The executor is inert until (a) pairing has completed and (b) the
 * UI "Local shell" switch is on — the switch is set automatically
 * the first time the post-pairing self-test passes, and Jonathan can
 * disarm it at any time (same pattern as the ADB self-heal switch).
 */
public final class ShellBridge {
    private ShellBridge() {}

    /** One command per cycle, per the design. Called on the bridge thread. */
    public static void tick(Context ctx) {
        // TV profile gated off in v1.5.0: Shield debugging is LAN ADB
        // (:5555), not the phone wireless-debugging pairing flow.
        if (DeviceProfile.isTv(ctx)) return;
        if (!Store.getBool(ctx, Store.K_SHELL_ON, false)) return;
        if (!Store.getBool(ctx, Store.K_SHELL_PAIRED, false)) return;
        WorkerClient.Command cmd = WorkerClient.fetchCommand(ctx);
        if (cmd == null) return;

        if (cmd.expiresAtMs > 0 && System.currentTimeMillis() > cmd.expiresAtMs) {
            BridgeLog.log(ctx, "SHELL", cmd.id + " expired before execution; reporting");
            WorkerClient.postCommandResult(ctx, cmd.id, false, "", 0, "expired");
            record(ctx, cmd, false, 0);
            return;
        }

        LoopbackAdb.Result r = executeGated(ctx, cmd.template, cmd.args,
                cmd.id);
        WorkerClient.postCommandResult(ctx, cmd.id, r.ok, r.stdoutTail,
                r.ms, r.error);
        record(ctx, cmd, r.ok, r.ms);
    }

    /**
     * The gated execution core, shared by the Worker channel
     * (tick, above) and the v1.20 local channel (LocalChannel):
     * the SAME ShellAllowlist gates, the SAME native dispatch,
     * the SAME loopback execution. Callers own their own result
     * reporting and bookkeeping. Never throws.
     */
    public static LoopbackAdb.Result executeGated(Context ctx,
            String template, java.util.List<String> args, String logId) {
        // v1.5: native (on-device) templates are gated by the same
        // allowlist class but never become shell text.
        if (ShellAllowlist.isNativeTemplate(template)) {
            ShellAllowlist.NativeGate ng =
                    ShellAllowlist.checkNative(template, args);
            if (!ng.allowed) {
                BridgeLog.log(ctx, "SHELL", logId + " REFUSED template="
                        + template + ": " + ng.refusal);
                return new LoopbackAdb.Result(false, "", 0,
                        "refused: " + ng.refusal);
            }
            LoopbackAdb.Result r = "voice_call".equals(ng.op)
                    ? VoiceBridge.executeCombined(ctx)
                    : "voice_bridge".equals(ng.op)
                    ? VoiceBridge.execute(ctx, ng.arg)
                    : ng.op.startsWith("voice_")
                    ? VoiceModeModule.execute(ctx, ng.op)
                    : ng.op.startsWith("buds_")
                    ? BudsRouter.execute(ctx, ng.op, ng.arg)
                    : "wear_install".equals(ng.op)
                    ? WearInstallRouter.execute(ctx, ng.args)
                    : "self_update".equals(ng.op)
                    ? SelfUpdateRouter.execute(ctx, ng.args)
                    : ng.op.startsWith("files_")
                    ? FileRouter.execute(ctx, ng.op, ng.args)
                    : ng.op.startsWith("screen_")
                    ? ScreenRouter.execute(ctx, ng.op, ng.args, ng.arg)
                    : NativeCommands.execute(ctx, ng.op);
            BridgeLog.log(ctx, "SHELL", logId + " native " + template
                    + " -> ok=" + r.ok + " " + r.ms + "ms"
                    + (r.error != null ? " error=" + r.error : ""));
            return r;
        }

        ShellAllowlist.Gate gate = ShellAllowlist.check(template, args);
        if (!gate.allowed) {
            BridgeLog.log(ctx, "SHELL", logId + " REFUSED template="
                    + template + ": " + gate.refusal);
            return new LoopbackAdb.Result(false, "", 0,
                    "refused: " + gate.refusal);
        }

        LoopbackAdb.Result r = LoopbackAdb.execute(ctx, gate.argv);
        BridgeLog.log(ctx, "SHELL", logId + " " + ShellAllowlist.describe(gate)
                + " -> ok=" + r.ok + " " + r.ms + "ms"
                + (r.error != null ? " error=" + r.error : ""));
        return r;
    }

    private static void record(Context ctx, WorkerClient.Command cmd, boolean ok, long ms) {
        Store.put(ctx, Store.K_SHELL_LAST_ID, cmd.id);
        Store.put(ctx, Store.K_SHELL_LAST_TEMPLATE, cmd.template);
        Store.put(ctx, Store.K_SHELL_LAST_OK, ok);
        Store.put(ctx, Store.K_SHELL_LAST_MS, ms);
        Store.put(ctx, Store.K_SHELL_LAST_AT, System.currentTimeMillis());
    }

    /**
     * Post-pairing self-test: run the harmless `battery` template over
     * the loopback connection. On success the bridge marks the
     * self-test passed and arms the executor switch (design rule:
     * default ON only after the self-test passes).
     */
    public static LoopbackAdb.Result runSelfTest(Context ctx) {
        ShellAllowlist.Gate gate = ShellAllowlist.check("battery", new ArrayList<>());
        LoopbackAdb.Result r = LoopbackAdb.execute(ctx, gate.argv);
        BridgeLog.log(ctx, "SHELL", "self-test (battery) -> ok=" + r.ok + " " + r.ms + "ms"
                + (r.error != null ? " error=" + r.error : ""));
        if (r.ok) {
            Store.put(ctx, Store.K_SHELL_SELF_TEST_OK, true);
            Store.put(ctx, Store.K_SHELL_ON, true);
        }
        return r;
    }

    /** Report object: state only — no args, no output (design bound). */
    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("paired", Store.getBool(ctx, Store.K_SHELL_PAIRED, false));
            o.put("self_test_ok", Store.getBool(ctx, Store.K_SHELL_SELF_TEST_OK, false));
            o.put("on", Store.getBool(ctx, Store.K_SHELL_ON, false));
            // v1.5 extension pack state (additive; existing consumers
            // ignore unknown keys).
            o.put("voice_enabled", Store.getBool(ctx, Store.K_VOICE_ENABLED, false));
            o.put("voice_active", Store.getBool(ctx, Store.K_VOICE_ACTIVE, false));
            // v1.21: the report always carries the app's own version
            // + the last self-update job's state (before v1.21 the
            // report had NO version field, which repeatedly forced
            // blind diagnosis of which build the phone was running).
            o.put("self_update", SelfUpdateRouter.snapshot(ctx));
            // v1.24: phone file management state (access + last op).
            o.put("files", FileRouter.snapshot(ctx));
            // v1.25: screen sharing module state (no frame data).
            o.put("screen", ScreenRouter.snapshot(ctx));
            if (Store.getLong(ctx, Store.K_TORCH_AT, 0) > 0) {
                o.put("torch_on", Store.getBool(ctx, Store.K_TORCH_ON, false));
            }
            long at = Store.getLong(ctx, Store.K_SHELL_LAST_AT, 0);
            if (at > 0) {
                JSONObject last = new JSONObject();
                last.put("id", Store.get(ctx, Store.K_SHELL_LAST_ID, ""));
                last.put("template", Store.get(ctx, Store.K_SHELL_LAST_TEMPLATE, ""));
                last.put("ok", Store.getBool(ctx, Store.K_SHELL_LAST_OK, false));
                last.put("ms", Store.getLong(ctx, Store.K_SHELL_LAST_MS, 0));
                last.put("at", at);
                o.put("last", last);
            }
        } catch (Exception ignored) {}
        return o;
    }

    static List<String> noArgs() {
        return new ArrayList<>();
    }
}
