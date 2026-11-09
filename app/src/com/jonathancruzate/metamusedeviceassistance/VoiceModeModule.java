package com.jonathancruzate.metamusedeviceassistance;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;

/**
 * VoiceModeModule — the v1.5 voice-mode self-activation module's
 * gate + Worker-command entry point (item 8 of the extension pack;
 * Jonathan's 2026-11-03 proposal, named again 2026-11-08: "allowing
 * you to turn on voice mode yourself so you can decide when to
 * talk"). The mechanics live in VoiceLauncher /
 * VoiceAccessibilityService (ported from Muse Voice Mode Shortcut);
 * this class is only capability + gates:
 *
 *   - OPTIONAL, OFF BY DEFAULT: Store.K_VOICE_ENABLED is set only by
 *     Jonathan in the app. While it is false, the voice_mode verb is
 *     REFUSED — disabled means the capability does not exist.
 *   - The accessibility-service grant is likewise Jonathan's
 *     on-device step. Without the service running, no tap can land,
 *     so the verb fails honestly instead of flipping the switch
 *     into a lie.
 *   - A dispatched toggle is NOT a proven session: per the shortcut
 *     skill's verification discipline, a fire is real only when the
 *     voice session observably starts/stops. The result text says
 *     "dispatched" for exactly that reason; the persisted switch
 *     state is the module's belief.
 *   - v1.5.2 BELIEF RECONCILIATION: the belief used to go stale
 *     when a session ended on the Muse side (Jonathan hangs up
 *     inside Muse): the flag stayed "active" and the next
 *     voice_mode on no-opped against the dead session (proven live
 *     2026-11-08 23:28). execute() now reconciles the flag against
 *     the accessibility service's observation of the Muse window
 *     before trusting it (reconcileVoiceState below), and the
 *     service additionally clears the flag when it watches the
 *     session UI leave. The manual off->on pair is now only a
 *     fallback, not the mechanism.
 *
 * The ask-first rule for WHEN Muse may self-activate is chat-side
 * behavior encoded in the Device Assistance skill — deliberately
 * not this class's concern.
 */
public final class VoiceModeModule {
    private VoiceModeModule() {}

    public static boolean moduleEnabled(Context ctx) {
        return Store.getBool(ctx, Store.K_VOICE_ENABLED, false);
    }

    /** Whether Jonathan has enabled THIS app's accessibility service
     *  (Settings-side truth, distinct from the service process being
     *  up: VoiceAccessibilityService.instance covers the latter). */
    public static boolean accessibilityEnabled(Context ctx) {
        try {
            String flat = Settings.Secure.getString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (flat == null) return false;
            ComponentName mine = new ComponentName(ctx, VoiceAccessibilityService.class);
            for (String part : flat.split(":")) {
                ComponentName cn = ComponentName.unflattenFromString(part);
                if (mine.equals(cn)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public static boolean serviceRunning() {
        return VoiceAccessibilityService.instance != null;
    }

    /** Execute one gated voice op (from ShellAllowlist.checkNative).
     *  Never throws. Result ok=true means DISPATCHED (hunt armed +
     *  intent fired), not session-proven — see class docs. */
    public static LoopbackAdb.Result execute(Context ctx, String op) {
        long t0 = System.currentTimeMillis();
        if (!moduleEnabled(ctx)) {
            BridgeLog.log(ctx, "VOICE", op + " REFUSED: module disabled in-app");
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "refused: voice module disabled");
        }
        if (!accessibilityEnabled(ctx) || !serviceRunning()) {
            BridgeLog.log(ctx, "VOICE", op + " REFUSED: accessibility service "
                    + "not enabled/running for Device Assistance");
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "refused: accessibility service not enabled");
        }
        reconcileVoiceState(ctx);
        boolean active = VoiceState.isActive(ctx);
        try {
            switch (op) {
                case "voice_on":
                    if (active) {
                        return done(t0, "voice_mode=on (already active, no-op)");
                    }
                    VoiceLauncher.launchVoice(ctx);
                    break;
                case "voice_off":
                    if (!active) {
                        return done(t0, "voice_mode=off (already off, no-op)");
                    }
                    VoiceLauncher.endVoice(ctx);
                    break;
                case "voice_toggle":
                    VoiceLauncher.toggle(ctx);
                    break;
                default:
                    return new LoopbackAdb.Result(false, "",
                            System.currentTimeMillis() - t0, "unknown voice op");
            }
        } catch (Exception e) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    e.getClass().getSimpleName() + " " + e.getMessage());
        }
        boolean now = VoiceState.isActive(ctx);
        BridgeLog.log(ctx, "VOICE", op + " dispatched -> active=" + now);
        return done(t0, "voice_mode=" + (now ? "on" : "off") + " dispatched");
    }

    /** Reconcile the persisted voice_active belief against the
     *  accessibility service's observation of the Muse window
     *  (v1.5.2). Runs before every op, after the two gates:
     *    - UI_PRESENT + belief off -> believe the UI: a session is
     *      on screen (however it got there), flag on.
     *    - UI_ABSENT + belief on -> the Muse window is right there
     *      with no session in it: the belief is the 23:28 desync;
     *      clear it, so an `on` dispatches a real start and an
     *      `off` truthfully no-ops.
     *    - UI_UNKNOWN + belief on -> no Muse window is observable
     *      at all. A live session presents UI this service could
     *      act on; a believed-live session with none is
     *      indistinguishable from the proven hang-up desync, so
     *      clear it too. The failure direction is safe: the start
     *      hunt only ever taps the START control, which a live
     *      session's screen does not show, so a wrongly dispatched
     *      start times out without tapping anything.
     *  Beliefs inside a dispatch's grace window are trusted as-is:
     *  a session just started (or just ended) may not be reflected
     *  in the window tree yet. Never throws. */
    private static void reconcileVoiceState(Context ctx) {
        try {
            VoiceAccessibilityService svc = VoiceAccessibilityService.instance;
            if (svc == null) return;
            boolean belief = VoiceState.isActive(ctx);
            long now = System.currentTimeMillis();
            if (belief && now - VoiceState.lastStartDispatchMs
                    < VoiceState.DISPATCH_GRACE_MS) return;
            if (!belief && now - VoiceState.lastEndDispatchMs
                    < VoiceState.DISPATCH_GRACE_MS) return;
            int ui = svc.observeVoiceSessionUi();
            if (ui == VoiceAccessibilityService.UI_PRESENT && !belief) {
                VoiceState.setActive(ctx, true);
                BridgeLog.log(ctx, "VOICE",
                        "reconciled: voice session UI present; voice_active -> true");
            } else if (ui != VoiceAccessibilityService.UI_PRESENT && belief) {
                VoiceState.setActive(ctx, false);
                BridgeLog.log(ctx, "VOICE",
                        "reconciled stale voice_active -> false (observation="
                                + (ui == VoiceAccessibilityService.UI_ABSENT
                                        ? "Muse window present, no session UI"
                                        : "no Muse window observable") + ")");
            }
        } catch (Exception ignored) {}
    }

    private static LoopbackAdb.Result done(long t0, String out) {
        return new LoopbackAdb.Result(true, out, System.currentTimeMillis() - t0, null);
    }
}
