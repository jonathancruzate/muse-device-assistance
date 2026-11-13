package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;

/**
 * VoiceState — state for the v1.5 voice-mode self-activation module.
 * Ported from Muse Voice Mode Shortcut's VoiceState (same switch
 * semantics, proven design): the toggle alternates OFF -> ON -> OFF,
 * and the persisted half (Store.K_VOICE_ACTIVE) survives process
 * death between triggers. Hunt flags are transient: set BEFORE Muse
 * is opened, the accessibility service taps Muse's own voice /
 * end-call button when the Muse window appears. (hatch://voice is
 * TrustedCaller-gated to Meta-signed apps; the tap is the only way
 * in — same trick as Muse for Android Auto and the shortcut app.)
 *
 * The module switch itself is Store.K_VOICE_ENABLED (default OFF).
 * This class does not consult it — gating is VoiceModeModule's job.
 */
public final class VoiceState {
    public static volatile boolean awaitingVoiceTap = false;
    public static volatile boolean awaitingEndTap = false;
    /** In-memory mirror of the persisted flag; Store is truth. */
    public static volatile boolean sessionActive = false;

    /** Wall-clock of the last start/end dispatch (VoiceLauncher).
     *  In-memory only. Reconciliation (VoiceModeModule, v1.5.2)
     *  trusts the persisted belief while a dispatch is inside its
     *  grace window — the session UI needs a moment to appear or
     *  disappear — and a process restart zeroes these stamps, which
     *  makes a persisted belief fully subject to observation. That
     *  is the safe direction: a belief nothing observable supports
     *  is exactly the stale state reconciliation exists to clear. */
    public static volatile long lastStartDispatchMs = 0;
    public static volatile long lastEndDispatchMs = 0;
    public static final long DISPATCH_GRACE_MS = 15000;

    /** The app Jonathan was using when a voice start was dispatched
     *  (v1.10.0, Jonathan's choreography 2026-11-09 23:41 EST):
     *  recorded by VoiceLauncher.launchVoice via the accessibility
     *  service BEFORE the assist launch; after the session connects
     *  the service returns to this package, and leaving aura is the
     *  user-leave that makes the voice session auto-PiP over it.
     *  Null when nothing returnable was foreground (aura itself,
     *  our own package, or the launcher) — the service then falls
     *  back to GLOBAL_ACTION_HOME. In-memory only, like the hunt
     *  flags: it is meaningful only inside one activation flow. */
    public static volatile String returnTargetPkg = null;

    /** Bridge injection (v1.14): a message Muse needs delivered
     *  into the Muse app's main conversation AS Jonathan — the
     *  voice loop only hears the conversation's own user turns, and
     *  the accessibility service is the only hands that can type one
     *  (Jonathan's rule, 2026-11-09 00:04 EST; re-taught 2026-11-11
     *  01:20 EST after two chat-side primes never reached a live
     *  session). Set by VoiceBridge from the command thread; the
     *  service's bridge hunt consumes and clears both fields.
     *  In-memory only: a bridge text is meaningful only in the
     *  moment, and a process death mid-hunt fails loud in the
     *  command's BridgeLog trail rather than replaying stale text. */
    public static volatile boolean awaitingBridgeSend = false;
    public static volatile String bridgeText = null;

    /** Combined fire sequencing (v1.15, Jonathan's design
     *  2026-11-11, order corrected by him 12:24 EST): the
     *  `voice_call` command runs the proven sequence in ONE fire —
     *  open the app, start voice, TYPE the marker while the app is
     *  open, THEN go back to the last app (the PiP return is the
     *  LAST step, never the middle one). While this flag is set,
     *  VoiceAccessibilityService defers the activation's PiP
     *  attempt until the bridge hunt resolves (sent or failed), so
     *  the return cannot yank the app away mid-typing; a watchdog
     *  forces the return if the marker stalls, so he is never
     *  stranded in Muse. The marker itself is fixed and
     *  mechanical (VoiceBridge.VOICE_CONTEXT_MARKER); the
     *  semantics live in ~/workspace/voice_context.md, which the
     *  firing Muse writes BEFORE firing and the session-side
     *  Muse reads before speaking. */
    public static volatile boolean combinedCallPending = false;

    /** The session-live hook (v1.16, Jonathan's diagnosis
     *  2026-11-11 20:15 EST — "you have to type the prompt AFTER
     *  voice mode starts" + "you need a hook that detects that
     *  mechanically then fires the prompt"): a prompt typed BEFORE
     *  the session exists lands as pre-session history and the
     *  session's opener ignores it (measured twice: marker at
     *  T+3.2s vs session start at T+3.9s, opener spoke from general
     *  chat context both times; the one proven speak-first success,
     *  2026-11-11 12:10 EST, had the prompt arrive ~23s after the
     *  voice start, INTO the live session). So a fresh combined
     *  fire HOLDS the bridge hunt: VoiceAccessibilityService's
     *  bridge step polls isVoiceSessionLive every 500 ms and only
     *  types when the session is mechanically detected live. The
     *  deadline is the fallback: if no live session is detected in
     *  time, the prompt types anyway (today's behavior) rather
     *  than never. Cleared when the hunt proceeds or the combined
     *  call resolves. */
    public static volatile boolean holdBridgeForSessionLive = false;
    public static volatile long bridgeHoldDeadlineMs = 0;

    /** Locked-screen gate (v1.19, Jonathan 2026-11-12 00:14 EST —
     *  attempt 9 died on a locked screen: "if my screen is locked
     *  it should work like muse voice mode shortcut, try to unlock
     *  the phone and then lock it again"). A voice launch that
     *  finds the keyguard locked defers into UnlockActivity (wake
     *  + requestDismissKeyguard + poll): the launch continues
     *  only once the phone is actually unlocked, and fails loud
     *  if it stays locked (a secure credential with nobody
     *  present cannot be bypassed) instead of hunting a locked
     *  screen in silence. weUnlockedPhone remembers that WE
     *  opened it, so the end of the sequence locks it again and
     *  the phone is left in the state he left it in. In-memory
     *  only, like the hunt flags. */
    public static volatile boolean unlockInFlight = false;
    public static volatile boolean pendingVoiceLaunch = false;
    public static volatile boolean weUnlockedPhone = false;

    private VoiceState() {}

    public static boolean isActive(Context ctx) {
        try {
            sessionActive = Store.getBool(ctx, Store.K_VOICE_ACTIVE, sessionActive);
        } catch (Exception ignored) {
        }
        return sessionActive;
    }

    public static void setActive(Context ctx, boolean active) {
        sessionActive = active;
        try {
            Store.put(ctx, Store.K_VOICE_ACTIVE, active);
        } catch (Exception ignored) {
        }
    }
}
