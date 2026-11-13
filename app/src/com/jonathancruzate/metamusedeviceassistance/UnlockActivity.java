package com.jonathancruzate.metamusedeviceassistance;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * UnlockActivity — the locked-screen trampoline (v1.19,
 * Jonathan 2026-11-12 00:14 EST: a voice fire on a locked
 * phone should "try to unlock the phone and then lock it
 * again" at the end, the way his side-button shortcut flow
 * works in person).
 *
 * Declared show-when-locked + turn-screen-on, so starting it
 * wakes the display and shows over the keyguard. It asks the
 * system to dismiss the keyguard (instant for swipe locks and
 * Extend Unlock trusted states; a secure lock raises the
 * credential prompt, which Jonathan can clear with a
 * fingerprint — automation never touches credentials). A poll
 * decides the outcome: unlocked within 15 s → the deferred
 * voice launch continues and VoiceState.weUnlockedPhone is
 * set (the sequence re-locks at its end); still locked → the
 * fire aborts loudly in BridgeLog and any combined-call state
 * is unwound, instead of the hunts silently working a locked
 * screen the way attempt 9 died.
 */
public class UnlockActivity extends Activity {

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean done = false;
    private int polls = 0;
    private static final int MAX_POLLS = 30; // 30 x 500 ms = 15 s

    public static boolean isLocked(Context ctx) {
        try {
            KeyguardManager km = (KeyguardManager)
                    ctx.getSystemService(Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Exception ignored) {
            return false;
        }
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, UnlockActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!isLocked(this)) {
            succeed();
            return;
        }
        try {
            KeyguardManager km = (KeyguardManager)
                    getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null) {
                km.requestDismissKeyguard(this,
                        new KeyguardManager.KeyguardDismissCallback() {
                            @Override
                            public void onDismissSucceeded() {
                                succeed();
                            }
                            // Error/cancel leave the credential
                            // prompt on screen; the poll below is
                            // the decider either way.
                            @Override
                            public void onDismissError() {}
                            @Override
                            public void onDismissCancelled() {}
                        });
            }
        } catch (Exception ignored) {
            // fall through to the poll
        }
        handler.postDelayed(poll, 500);
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (done) return;
            if (!isLocked(UnlockActivity.this)) {
                succeed();
                return;
            }
            if (++polls >= MAX_POLLS) {
                fail();
                return;
            }
            handler.postDelayed(this, 500);
        }
    };

    private void succeed() {
        if (done) return;
        done = true;
        VoiceState.weUnlockedPhone = true;
        VoiceState.unlockInFlight = false;
        BridgeLog.log(this, "VOICE",
                "unlock: phone unlocked - continuing the voice launch");
        if (VoiceState.pendingVoiceLaunch) {
            VoiceState.pendingVoiceLaunch = false;
            VoiceLauncher.launchVoice(getApplicationContext());
        }
        finish();
    }

    private void fail() {
        if (done) return;
        done = true;
        VoiceState.unlockInFlight = false;
        VoiceState.pendingVoiceLaunch = false;
        if (VoiceState.combinedCallPending) {
            VoiceState.combinedCallPending = false;
            VoiceState.holdBridgeForSessionLive = false;
            VoiceState.awaitingBridgeSend = false;
            VoiceState.bridgeText = null;
        }
        BridgeLog.log(this, "VOICE",
                "unlock FAILED: phone stayed locked 15s - voice"
                + " fire aborted (secure unlock needs Jonathan)");
        finish();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        super.onDestroy();
    }
}
