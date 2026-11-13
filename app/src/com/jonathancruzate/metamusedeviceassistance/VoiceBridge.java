package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;

/**
 * VoiceBridge — v1.14: the phone half of the voice bridge.
 *
 * A self-activated voice session lives in the Muse app's MAIN
 * conversation, and its voice loop is driven by that
 * conversation's own messages. A bridge sent from any chat via
 * chat tooling arrives as an agent instruction and never reaches
 * the session — proven repeatedly (2026-11-08: submissions
 * 795b8f69 / 5415c330 / 884f25af; 2026-11-11: 81bf2998 /
 * 30b4b633). Jonathan's rule (2026-11-09 00:04 EST, re-taught
 * 2026-11-11 01:20 EST): "you have to send a message as ME to
 * yourself" — using the accessibility service.
 *
 * So: the `voice_bridge` native command (gate:
 * ShellAllowlist.checkVoiceBridge) hands its text to
 * VoiceAccessibilityService, whose bridge hunt brings Muse
 * forward, types the text into the main conversation's composer,
 * and taps Send — a genuine user turn, sent from Jonathan's own
 * app, indistinguishable from him typing it.
 *
 * The command result is a QUEUED acknowledgement: the injection
 * itself runs on the service's main-looper hunt (up to ~17 s).
 * Execution evidence is the hunt's BridgeLog lines ("bridge
 * sent" / "bridge FAILED") plus the message landing in the main
 * chat — the same dispatched + verified discipline as the voice
 * hunts. One send in flight at a time; a second command while
 * one is queued is refused, never stacked.
 */
public final class VoiceBridge {
    private VoiceBridge() {}

    /** Enqueue a bridge text for injection. Never throws. */
    public static LoopbackAdb.Result execute(Context ctx, String text) {
        long t0 = System.currentTimeMillis();
        VoiceAccessibilityService svc = VoiceAccessibilityService.instance;
        if (svc == null) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "accessibility service not running (grant lost?)");
        }
        if (VoiceState.awaitingBridgeSend) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "bridge send already in flight");
        }
        VoiceState.bridgeText = text;
        VoiceState.awaitingBridgeSend = true;
        svc.kickBridgeHunt();
        BridgeLog.log(ctx, "VOICE", "bridge queued: " + text.length()
                + " chars, hunt kicked");
        return new LoopbackAdb.Result(true,
                "voice_bridge queued (" + text.length() + " chars)",
                System.currentTimeMillis() - t0, null);
    }

    /** The fixed marker the combined `voice_call` fire types as
     *  Jonathan INTO the live session (the session-live hook holds
     *  the typing until the session is detected, v1.16), while the
     *  app is open and before the return to his last app.
     *  Mechanical by design (Jonathan, 2026-11-11):
     *  it says only that the session was fired by voice mode and
     *  where the meaning lives — ~/workspace/voice_context.md,
     *  written by the firing Muse before firing, read by the
     *  session-side Muse before it speaks. Never put call-specific
     *  content in this string; that is what the file is for. */
    public static final String VOICE_CONTEXT_MARKER =
            "This was fired by voice mode. Stay quiet until you read"
            + " the voice context, then speak first.";

    /** How long the session-live hook (v1.16) holds the prompt
     *  while it polls for the live session before typing anyway.
     *  Real sessions surface within ~5-15 s of the launch; 30 s
     *  covers slow connects without leaving a stalled fire
     *  untyped forever. */
    public static final long SESSION_LIVE_HOLD_MS = 30000;

    /**
     * The combined fire (v1.15): voice mode on + marker, ONE
     * command, executed on-device in the proven order (Jonathan's
     * sequence, order corrected by him 2026-11-11 12:24 EST) —
     *   1. queue the marker and launch voice (VoiceLauncher
     *      records the return target first, exactly like
     *      voice_mode on),
     *   2. the bridge hunt types the marker while the app is open,
     *   3. the PiP return to his last app runs LAST — the service
     *      defers it until the hunt resolves, so the return never
     *      yanks the app away mid-typing, and a watchdog forces
     *      the return if the marker stalls.
     * If a session is already observably live, this degrades to
     * the marker alone (no second start). The command result is a
     * DISPATCH acknowledgement; execution evidence is the hunt's
     * BridgeLog trail ("voice_call: ..." lines, then "bridge
     * sent") plus the marker landing in the conversation.
     * Never throws.
     */
    public static LoopbackAdb.Result executeCombined(Context ctx) {
        long t0 = System.currentTimeMillis();
        VoiceAccessibilityService svc = VoiceAccessibilityService.instance;
        if (svc == null) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "accessibility service not running (grant lost?)");
        }
        if (!VoiceModeModule.moduleEnabled(ctx)) {
            BridgeLog.log(ctx, "VOICE",
                    "voice_call REFUSED: module disabled in-app");
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "refused: voice module disabled");
        }
        if (!VoiceModeModule.accessibilityEnabled(ctx)) {
            BridgeLog.log(ctx, "VOICE",
                    "voice_call REFUSED: accessibility service not enabled");
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "refused: accessibility service not enabled");
        }
        if (VoiceState.awaitingBridgeSend) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "bridge send already in flight");
        }
        boolean live = VoiceState.isActive(ctx)
                && svc.observeVoiceSessionUi()
                        == VoiceAccessibilityService.UI_PRESENT;
        VoiceState.bridgeText = VOICE_CONTEXT_MARKER;
        VoiceState.awaitingBridgeSend = true;
        if (live) {
            VoiceState.combinedCallPending = false;
            svc.kickBridgeHunt();
            BridgeLog.log(ctx, "VOICE", "voice_call: session already"
                    + " live; marker queued");
            return new LoopbackAdb.Result(true,
                    "voice_call: session already live, marker queued",
                    System.currentTimeMillis() - t0, null);
        }
        // Queue the marker BEFORE the launch, and mark the combined
        // sequence pending: the hunt types while the app is open,
        // and the service holds the PiP return (the last step)
        // until the hunt resolves. The session-live hook (v1.16)
        // additionally holds the TYPING itself until the session
        // is detected live, so the prompt lands as a live-session
        // turn, not pre-session history (Jonathan, 2026-11-11
        // 20:15 EST).
        VoiceState.combinedCallPending = true;
        VoiceState.holdBridgeForSessionLive = true;
        VoiceState.bridgeHoldDeadlineMs =
                System.currentTimeMillis() + SESSION_LIVE_HOLD_MS;
        svc.armCombinedWatchdog();
        VoiceLauncher.launchVoice(ctx);
        BridgeLog.log(ctx, "VOICE", "voice_call dispatched: voice"
                + " starting; marker types while app is open;"
                + " return to last app is the last step");
        return new LoopbackAdb.Result(true,
                "voice_call dispatched (voice, marker, then return)",
                System.currentTimeMillis() - t0, null);
    }
}
