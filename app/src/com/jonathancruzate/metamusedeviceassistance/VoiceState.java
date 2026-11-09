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
