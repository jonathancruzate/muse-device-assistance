package com.jonathancruzate.metamusedeviceassistance;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DuoRitual — v1.26: the Surface Duo wake ritual (design:
 * docs/v1.26-duo-companion-2026-11-14.md). DUO PROFILE ONLY:
 * BridgeService starts it solely when DeviceProfile.isDuo(), every
 * entry point re-checks the profile, and the class is never loaded
 * into any phone/TV behavior.
 *
 * The ritual, on unlock (ACTION_USER_PRESENT) with the Duo open
 * flat:
 *   1. Ensure DUO-DE's two-panel windowing prop is on
 *      (persist.debug.phh.duo.panels=true) through the loopback
 *      shell bridge's fixed templates, verified by read-back.
 *   2. Fire the voice sequence (VoiceBridge.executeDuoRitual):
 *      Muse launches into its panel (the ROM's panel policy owns
 *      placement), the session starts, and the Duo payload types
 *      as Jonathan once the session is CONNECTED — he is greeted
 *      by a session that speaks first, Muse full-panel on the
 *      left, the right panel free for the work (his screen.*
 *      verbs drive it from there).
 *
 * Discipline, mirroring the house rules everywhere else in this
 * app:
 *   - The ritual master switch (Store.K_DUO_RITUAL_ENABLED) is
 *     Jonathan's, default OFF — while off, the ritual does not
 *     exist (the Duo profile still reports and serves commands).
 *   - The voice step rides the voice module's OWN gates (module
 *     switch + accessibility grant). A closed gate is a QUIET skip
 *     (one BridgeLog line + report bookkeeping), never an error,
 *     never a half-fire.
 *   - At most one fire per unlock episode: a 2-minute debounce
 *     between dispatched fires plus an in-flight latch, and the
 *     voice step itself refuses when a session is already live —
 *     the ritual never talks over a live session or stacks prompts.
 *   - Posture: hinge sensor -> display geometry -> USER_PRESENT
 *     alone (DuoPosture). A signal that positively says "not open
 *     flat" skips the fire; NO signal falls back to firing (the
 *     unlock itself is the evidence) and records source "none".
 *   - Debug mode (v1.29): while effectively on, an evaluation
 *     stops right after the ritual-switch check with the outcome
 *     "debug_suppressed" — no voice fire, no typed payload — so
 *     Muse can wake and test the Duo without the greeting firing
 *     (Jonathan's order, 2026-11-21: every adb wake otherwise
 *     looks like an across-the-room summons). The flag auto-
 *     expires 60 minutes after it was set and is cleared at
 *     BridgeService start, so it never survives a boot.
 */
public final class DuoRitual {
    private DuoRitual() {}

    private static final long FIRE_DEBOUNCE_MS = 120_000;

    /** v1.29: debug flag TTL — 60 minutes after it was set, the
     *  flag counts as OFF everywhere (ritual, read-back, UI). */
    private static final long DEBUG_TTL_MS = 3_600_000;

    private static HandlerThread thread;
    private static Handler handler;
    private static BroadcastReceiver receiver;
    private static Context appCtx;
    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static volatile long lastFireAtMs = 0;

    /** Start the ritual watcher. Called by BridgeService.onCreate,
     *  Duo profile only; self-guards so a stray call elsewhere is
     *  inert. Idempotent. */
    public static synchronized void start(Context ctx) {
        if (!DeviceProfile.isDuo(ctx)) return;
        if (thread != null) return;
        appCtx = ctx.getApplicationContext();
        thread = new HandlerThread("duo-ritual");
        thread.start();
        handler = new Handler(thread.getLooper());
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                if (intent != null
                        && Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
                    Handler h = handler;
                    if (h != null) h.post(() -> evaluate("user_present"));
                }
            }
        };
        try {
            // ACTION_USER_PRESENT is a protected broadcast (only the
            // system can send it), so EXPORTED registration cannot be
            // spoofed by another app; the flag is required for
            // context-registered receivers on API 34+.
            appCtx.registerReceiver(receiver,
                    new IntentFilter(Intent.ACTION_USER_PRESENT),
                    Context.RECEIVER_EXPORTED);
            BridgeLog.log(appCtx, "DUO", "ritual watcher registered"
                    + " (switch=" + (ritualEnabled(appCtx) ? "on" : "off") + ")");
        } catch (Exception e) {
            BridgeLog.log(appCtx, "DUO", "ritual watcher register failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** Stop the watcher (BridgeService.onDestroy). */
    public static synchronized void stop(Context ctx) {
        if (receiver != null && appCtx != null) {
            try { appCtx.unregisterReceiver(receiver); } catch (Exception ignored) {}
            receiver = null;
        }
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
    }

    public static boolean ritualEnabled(Context ctx) {
        return Store.getBool(ctx, Store.K_DUO_RITUAL_ENABLED, false);
    }

    /** v1.29 debug gate: ON only while the flag is set AND inside
     *  its 60-minute TTL. An expired flag reads OFF everywhere;
     *  the stored value is left in place (the TTL is the
     *  authority), so no writer has to race the expiry. */
    public static boolean debugActive(Context ctx) {
        if (!Store.getBool(ctx, Store.K_DUO_DEBUG, false)) return false;
        long setAt = Store.getLong(ctx, Store.K_DUO_DEBUG_SET_AT, 0);
        return setAt > 0 && System.currentTimeMillis() - setAt < DEBUG_TTL_MS;
    }

    /** Set or clear the debug flag (bridge templates + UI toggle).
     *  Setting stamps the TTL clock; clearing zeroes it. */
    public static void setDebug(Context ctx, boolean on) {
        Store.put(ctx, Store.K_DUO_DEBUG, on);
        Store.put(ctx, Store.K_DUO_DEBUG_SET_AT,
                on ? System.currentTimeMillis() : 0);
    }

    /** BridgeService start (boot, app open, restart): debug is a
     *  live-session tool, never a persisted state — a set flag is
     *  cleared so a reboot always restores the real ritual. */
    public static void clearDebugOnStart(Context ctx) {
        if (Store.getBool(ctx, Store.K_DUO_DEBUG, false)) {
            setDebug(ctx, false);
            BridgeLog.log(ctx, "DUO", "debug flag cleared at bridge start");
        }
    }

    /** MainActivity's test button: run one evaluation now, on the
     *  ritual thread, and return its outcome string. */
    public static String testFire(Context ctx) {
        if (!DeviceProfile.isDuo(ctx)) return "not a Duo (profile gate)";
        Handler h = handler;
        if (h == null) return "ritual watcher not running (bridge service off?)";
        final String[] out = new String[]{"queued"};
        h.post(() -> out[0] = evaluate("ui_test"));
        // The evaluation involves a posture probe + possible shell
        // round-trips; give it a bounded wait for the toast.
        long deadline = System.currentTimeMillis() + 6000;
        while (System.currentTimeMillis() < deadline && "queued".equals(out[0])) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        return out[0];
    }

    /**
     * One ritual evaluation. Runs on the ritual thread. Returns the
     * outcome string (also persisted to Store for the report).
     * Quiet-skip discipline: every non-fire outcome is a normal
     * result, logged once, never an exception.
     */
    private static String evaluate(String source) {
        Context ctx = appCtx;
        if (ctx == null) return "no context";
        if (!inFlight.compareAndSet(false, true)) return "skipped: evaluation in flight";
        try {
            String outcome = evaluateInner(ctx, source);
            Store.put(ctx, Store.K_DUO_LAST_AT, System.currentTimeMillis());
            Store.put(ctx, Store.K_DUO_LAST_RESULT, outcome);
            BridgeLog.log(ctx, "DUO", "ritual [" + source + "] -> " + outcome);
            return outcome;
        } finally {
            inFlight.set(false);
        }
    }

    private static String evaluateInner(Context ctx, String source) {
        if (!ritualEnabled(ctx)) return "skipped: ritual switch off";
        // v1.29 debug gate: the evaluation happened (and is
        // recorded like any other outcome) but nothing fires —
        // no voice session, no typed payload.
        if (debugActive(ctx)) {
            Store.put(ctx, Store.K_DUO_DEBUG_SUPPRESSIONS,
                    Store.getLong(ctx, Store.K_DUO_DEBUG_SUPPRESSIONS, 0) + 1);
            return "debug_suppressed";
        }
        long now = System.currentTimeMillis();
        if (lastFireAtMs > 0 && now - lastFireAtMs < FIRE_DEBOUNCE_MS
                && !"ui_test".equals(source)) {
            return "skipped: debounce (fired " + ((now - lastFireAtMs) / 1000) + "s ago)";
        }
        // Voice gates first (they are the cheapest refusals and the
        // whole point is the greeting): closed gate = quiet skip.
        if (!VoiceModeModule.moduleEnabled(ctx)) return "skipped: voice module disabled";
        if (!VoiceModeModule.accessibilityEnabled(ctx)
                || VoiceAccessibilityService.instance == null) {
            return "skipped: accessibility service off";
        }
        if (VoiceState.isActive(ctx)
                && VoiceAccessibilityService.instance.observeVoiceSessionUi()
                        == VoiceAccessibilityService.UI_PRESENT) {
            return "skipped: voice session already live";
        }
        // Posture gate: a positive "not open flat" skips; no signal
        // falls back to the unlock itself (recorded as source none).
        DuoPosture.Reading posture = DuoPosture.probe(ctx);
        Store.put(ctx, Store.K_DUO_POSTURE_SOURCE, posture.source);
        if (posture.state == DuoPosture.NOT_OPEN) {
            return "skipped: posture not open flat (" + posture.detail + ")";
        }
        // Step 1: panels prop ensured + verified (best effort: the
        // prop is persist.* — once set it survives everything, so a
        // shell bridge that is not armed yet does not block the
        // greeting; the outcome string says so honestly).
        String panels = ensurePanelsProp(ctx);
        // Step 2: the voice fire (launches Muse into its panel +
        // starts the session + types the payload once CONNECTED).
        LoopbackAdb.Result r = VoiceBridge.executeDuoRitual(ctx);
        if (!r.ok) {
            return "skipped: voice fire refused (" + r.error + "); panels " + panels;
        }
        lastFireAtMs = System.currentTimeMillis();
        Store.put(ctx, Store.K_DUO_LAST_FIRE_AT, lastFireAtMs);
        Store.put(ctx, Store.K_DUO_FIRES,
                Store.getLong(ctx, Store.K_DUO_FIRES, 0) + 1);
        return "fired (posture=" + posture.source + ": " + posture.detail
                + "; panels " + panels + ")";
    }

    /** Ensure persist.debug.phh.duo.panels=true via the allowlist's
     *  fixed templates, verified by read-back. Returns a short
     *  state string; never throws, never blocks the ritual. */
    private static String ensurePanelsProp(Context ctx) {
        try {
            if (!Store.getBool(ctx, Store.K_SHELL_PAIRED, false)
                    || !Store.getBool(ctx, Store.K_SHELL_ON, false)) {
                Store.put(ctx, Store.K_DUO_PANELS_PROP, "unknown");
                return "unknown (shell bridge not armed)";
            }
            LoopbackAdb.Result get = ShellBridge.executeGated(ctx,
                    "duo_panels_get", ShellBridge.noArgs(), "duo-ritual");
            if (get.ok && get.stdoutTail != null && get.stdoutTail.contains("true")) {
                Store.put(ctx, Store.K_DUO_PANELS_PROP, "on");
                return "on (already)";
            }
            LoopbackAdb.Result set = ShellBridge.executeGated(ctx,
                    "duo_panels_on", ShellBridge.noArgs(), "duo-ritual");
            if (!set.ok) {
                Store.put(ctx, Store.K_DUO_PANELS_PROP, "unknown");
                return "unknown (setprop failed: " + set.error + ")";
            }
            LoopbackAdb.Result verify = ShellBridge.executeGated(ctx,
                    "duo_panels_get", ShellBridge.noArgs(), "duo-ritual");
            boolean on = verify.ok && verify.stdoutTail != null
                    && verify.stdoutTail.contains("true");
            Store.put(ctx, Store.K_DUO_PANELS_PROP, on ? "on" : "off");
            return on ? "on (set + verified)" : "off (set but read-back not true)";
        } catch (Exception e) {
            Store.put(ctx, Store.K_DUO_PANELS_PROP, "unknown");
            return "unknown (" + e.getClass().getSimpleName() + ")";
        }
    }

    /** Report object (state only): the duo section of the report,
     *  whitelisted by the relay sanitizer. */
    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("ritual_enabled", ritualEnabled(ctx));
            o.put("panels_prop", Store.get(ctx, Store.K_DUO_PANELS_PROP, "unknown"));
            o.put("posture_source", Store.get(ctx, Store.K_DUO_POSTURE_SOURCE, ""));
            o.put("last_fire_at", Store.getLong(ctx, Store.K_DUO_LAST_FIRE_AT, 0));
            o.put("last_at", Store.getLong(ctx, Store.K_DUO_LAST_AT, 0));
            o.put("last_result", Store.get(ctx, Store.K_DUO_LAST_RESULT, ""));
            o.put("fires", Store.getLong(ctx, Store.K_DUO_FIRES, 0));
            o.put("debug", debugActive(ctx));
            o.put("debug_set_at", Store.getLong(ctx, Store.K_DUO_DEBUG_SET_AT, 0));
            o.put("suppressions", Store.getLong(ctx, Store.K_DUO_DEBUG_SUPPRESSIONS, 0));
        } catch (Exception ignored) {}
        return o;
    }

    /** MainActivity Duo section status text. */
    public static String statusText(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Profile: ").append(DeviceProfile.describe(ctx)).append('\n');
        sb.append("Ritual switch: ").append(ritualEnabled(ctx) ? "ON" : "OFF").append('\n');
        sb.append("Debug mode: ").append(debugActive(ctx) ? "ON (fire suppressed)" : "off")
                .append(" · suppressions: ")
                .append(Store.getLong(ctx, Store.K_DUO_DEBUG_SUPPRESSIONS, 0))
                .append('\n');
        sb.append("Voice module: ")
                .append(VoiceModeModule.moduleEnabled(ctx) ? "enabled" : "DISABLED")
                .append(" · accessibility: ")
                .append(VoiceModeModule.accessibilityEnabled(ctx) ? "granted" : "NOT granted")
                .append('\n');
        sb.append("Shell bridge: paired=")
                .append(Store.getBool(ctx, Store.K_SHELL_PAIRED, false))
                .append(" on=").append(Store.getBool(ctx, Store.K_SHELL_ON, false))
                .append('\n');
        sb.append("Panels prop: ").append(Store.get(ctx, Store.K_DUO_PANELS_PROP, "unknown"))
                .append(" · posture source: ")
                .append(Store.get(ctx, Store.K_DUO_POSTURE_SOURCE, "(none yet)"))
                .append('\n');
        long fires = Store.getLong(ctx, Store.K_DUO_FIRES, 0);
        sb.append("Ritual fires: ").append(fires);
        long lastAt = Store.getLong(ctx, Store.K_DUO_LAST_AT, 0);
        if (lastAt > 0) {
            sb.append("\nLast evaluation: ").append(Store.get(ctx, Store.K_DUO_LAST_RESULT, ""));
        }
        return sb.toString();
    }
}
