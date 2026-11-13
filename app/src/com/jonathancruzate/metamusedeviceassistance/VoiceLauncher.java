package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Toast;

/**
 * VoiceLauncher — the voice toggle itself, ported verbatim in
 * behavior from Muse Voice Mode Shortcut's VoiceLauncher (the
 * proven implementation), plus the one external intent it fires:
 * ACTION_ASSIST into Muse (com.facebook.aura) — the ungated
 * surface REA verified for Muse Drive.
 *
 *   toggle() while OFF -> ON:  arm the voice-button hunt, open
 *                              Muse; the accessibility service
 *                              taps Muse's voice button, which
 *                              starts the session.
 *   toggle() while ON  -> OFF: bring Muse to the foreground, arm
 *                              the end-button hunt; the service
 *                              taps Muse's end-call button
 *                              (fallback Back -> Home).
 *
 * Callers (VoiceModeModule, the in-app test button) own gating:
 * this class assumes the module is enabled and the accessibility
 * service is the one to check for.
 */
public final class VoiceLauncher {

    private VoiceLauncher() {}

    public static void toggle(Context ctx) {
        if (VoiceState.isActive(ctx)) {
            endVoice(ctx);
        } else {
            launchVoice(ctx);
        }
    }

    public static void launchVoice(Context ctx) {
        // Locked-screen gate (v1.19): if the keyguard is locked,
        // defer into the unlock trampoline and let its success
        // path re-enter launchVoice once the phone is actually
        // unlocked. Hunting for Muse's buttons on a locked
        // screen is how attempt 9 died silently (2026-11-12
        // 00:14 EST).
        if (!VoiceState.unlockInFlight && UnlockActivity.isLocked(ctx)) {
            VoiceState.unlockInFlight = true;
            VoiceState.pendingVoiceLaunch = true;
            BridgeLog.log(ctx, "VOICE",
                    "launch deferred: screen locked - unlock attempt first");
            UnlockActivity.start(ctx.getApplicationContext());
            return;
        }
        // Record where Jonathan was BEFORE Muse opens (v1.10.0):
        // the PiP sequence returns to this app after the session
        // connects. The accessibility service reads the foreground;
        // without the service there is no recording and the PiP
        // step falls back to GLOBAL_ACTION_HOME.
        VoiceState.returnTargetPkg = null;
        VoiceAccessibilityService a11y0 = VoiceAccessibilityService.instance;
        if (a11y0 != null) {
            try { VoiceState.returnTargetPkg = a11y0.currentForegroundPackage(); }
            catch (Exception ignored) {}
        }
        // Log the capture itself (2026-11-11): a wrong return
        // target (e.g. an overlay utility recorded as "foreground")
        // was previously invisible until the PiP return acted on
        // it. Null is a legitimate outcome (launcher/aura/us in
        // front) and falls back to GLOBAL_ACTION_HOME at return.
        BridgeLog.log(ctx, "VOICE", "return target recorded: "
                + (VoiceState.returnTargetPkg == null
                        ? "(none — PiP return will use HOME)"
                        : VoiceState.returnTargetPkg));
        // Arm the hunt + flip the switch FIRST: the Muse window can
        // appear before a later set would land.
        VoiceState.awaitingEndTap = false;
        VoiceState.awaitingVoiceTap = true;
        VoiceState.setActive(ctx, true);
        VoiceState.lastStartDispatchMs = System.currentTimeMillis();
        startClaudeAssist(ctx);
    }

    public static void endVoice(Context ctx) {
        VoiceState.awaitingVoiceTap = false;
        VoiceState.returnTargetPkg = null;
        VoiceState.awaitingEndTap = true;
        VoiceState.setActive(ctx, false);
        VoiceState.lastEndDispatchMs = System.currentTimeMillis();
        buzz(ctx, 120);
        final Context appCtx = ctx.getApplicationContext();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override public void run() { buzz(appCtx, 120); }
        }, 200);

        VoiceAccessibilityService a11y = VoiceAccessibilityService.instance;
        if (a11y == null) {
            VoiceState.awaitingEndTap = false;
            Toast.makeText(ctx,
                    "Turning off needs the Muse Device Assistance accessibility service enabled",
                    Toast.LENGTH_LONG).show();
            // Still flipped OFF: without the service there is no live
            // hunt, and leaving the switch ON would invert the next tap.
            return;
        }
        // Bring Muse forward so its voice screen (and end button)
        // is the active window, then the service hunts it. If Muse
        // is already foreground this is a no-op re-launch.
        bringClaudeForward(ctx);
    }

    /** Start Muse via ACTION_ASSIST. Explicit component first,
     *  implicit ACTION_ASSIST as fallback. */
    public static void startClaudeAssist(Context ctx) {
        Intent explicit = new Intent(Intent.ACTION_ASSIST);
        explicit.setClassName("com.facebook.aura", "com.facebook.aura.main.AuraMainActivity");
        explicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(explicit);
            return;
        } catch (Exception ignored) {
            // fall through to the implicit assist intent
        }
        Intent implicit = new Intent(Intent.ACTION_ASSIST);
        implicit.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(implicit);
        } catch (Exception ignored) {
            // Muse not installed / not resolvable: undo the arming
            VoiceState.awaitingVoiceTap = false;
            VoiceState.awaitingEndTap = false;
        }
    }

    /** Bring Muse to the foreground for the OFF hunt: package
     *  launch intent first (lands on Muse's current screen, which
     *  during a live session is the voice screen), ACTION_ASSIST
     *  as fallback. */
    public static void bringClaudeForward(Context ctx) {
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage("com.facebook.aura");
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
                return;
            }
        } catch (Exception ignored) {
            // fall through
        }
        startClaudeAssist(ctx);
    }

    public static void buzz(Context ctx, long ms) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }
}
