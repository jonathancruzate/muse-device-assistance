package com.jonathancruzate.metamusedeviceassistance;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * VoiceAccessibilityService — the accessibility half of the v1.5
 * voice-mode module, ported from Muse Voice Mode Shortcut's
 * VoiceAccessibilityService (itself lifted from Muse for Android
 * Auto's DriveAccessibilityService v2.1/v2.0). Both halves of the
 * toggle:
 *
 *   START: hatch://voice is TrustedCaller-gated to Meta-signed
 *   apps, so no outside app can deep-link into Muse voice.
 *   Instead the module opens Muse via ACTION_ASSIST and this
 *   service taps Muse's own voice button when the Muse window
 *   appears (VoiceState.awaitingVoiceTap).
 *
 *   END: same hunt against Muse's end-call control
 *   (VoiceState.awaitingEndTap), fallback Back -> Home.
 *
 * Hunt candidates (REA-derived, identical to the shortcut app and
 * Muse Drive):
 *   voice: view-ids hatch_voice_button / voice_button /
 *          voice_call_button / start_voice_button, then exact
 *          labels "Voice call" / "Start voice call" / "Voice mode"
 *          / "Voice" / "Talk"
 *   end:   view-ids end_voice_button / end_call_button /
 *          voice_end_call, then exact labels "End call" /
 *          "End voice call" / "Hang up" / "Leave call" / "End"
 * Each hunt retries for ~9s while the UI renders; a short buzz
 * confirms the tap landed. The service does nothing at all unless
 * a trigger armed a hunt, and only ever inspects the Muse window.
 *
 * This is a SEPARATE accessibility service from the shortcut app's
 * (different package): Jonathan enables this one for Device
 * Assistance in Settings; the shortcut app's grant is untouched.
 */
public class VoiceAccessibilityService extends AccessibilityService {

    public static volatile VoiceAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null
                || !"com.facebook.aura".contentEquals(event.getPackageName())) {
            return;
        }
        if (VoiceState.awaitingVoiceTap) scheduleVoiceHunt();
        if (VoiceState.awaitingEndTap) scheduleEndHunt();
        maybeScheduleSessionEndCheck();
    }

    @Override
    public void onInterrupt() {}

    // ---- START hunt ----

    private int voiceHuntAttempts = 0;
    private boolean voiceHuntRunning = false;
    private final Runnable voiceHuntStep = new Runnable() {
        @Override
        public void run() {
            if (!VoiceState.awaitingVoiceTap) {
                voiceHuntRunning = false;
                return;
            }
            if (tryTapVoiceButton()) {
                VoiceState.awaitingVoiceTap = false;
                voiceHuntRunning = false;
                VoiceLauncher.buzz(VoiceAccessibilityService.this, 80);
                return;
            }
            voiceHuntAttempts++;
            if (voiceHuntAttempts >= 10) { // ~9s of retries, then stop
                VoiceState.awaitingVoiceTap = false;
                voiceHuntRunning = false;
                // Switch stays ON: the tap armed a session start and
                // Muse is open; if the button never rendered the
                // next trigger still reads as OFF, which is the safe
                // direction (it can only try to end, never stack
                // a second start hunt).
                return;
            }
            handler.postDelayed(this, 900);
        }
    };

    private void scheduleVoiceHunt() {
        if (voiceHuntRunning) return;
        voiceHuntRunning = true;
        voiceHuntAttempts = 0;
        handler.postDelayed(voiceHuntStep, 400);
    }

    private boolean tryTapVoiceButton() {
        AccessibilityNodeInfo root = museRoot();
        if (root == null) return false;
        String[] ids = {
                "com.facebook.aura:id/hatch_voice_button",
                "com.facebook.aura:id/voice_button",
                "com.facebook.aura:id/voice_call_button",
                "com.facebook.aura:id/start_voice_button",
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty() && clickNode(nodes.get(0))) return true;
        }
        String[] labels = {"Voice call", "Start voice call", "Voice mode", "Voice", "Talk"};
        return tapByLabel(root, labels);
    }

    // ---- END hunt ----

    private int endHuntAttempts = 0;
    private boolean endHuntRunning = false;
    private final Runnable endHuntStep = new Runnable() {
        @Override
        public void run() {
            if (!VoiceState.awaitingEndTap) {
                endHuntRunning = false;
                return;
            }
            if (tryTapEndButton()) {
                VoiceState.awaitingEndTap = false;
                endHuntRunning = false;
                return;
            }
            endHuntAttempts++;
            if (endHuntAttempts >= 10) { // ~9s, then the fallback chain
                VoiceState.awaitingEndTap = false;
                endHuntRunning = false;
                performGlobalAction(GLOBAL_ACTION_BACK);
                handler.postDelayed(new Runnable() {
                    @Override public void run() { performGlobalAction(GLOBAL_ACTION_HOME); }
                }, 600);
                return;
            }
            handler.postDelayed(this, 900);
        }
    };

    private void scheduleEndHunt() {
        if (endHuntRunning) return;
        endHuntRunning = true;
        endHuntAttempts = 0;
        handler.postDelayed(endHuntStep, 400);
    }

    private boolean tryTapEndButton() {
        AccessibilityNodeInfo root = museRoot();
        if (root == null) return false;
        String[] ids = {
                "com.facebook.aura:id/end_voice_button",
                "com.facebook.aura:id/end_call_button",
                "com.facebook.aura:id/voice_end_call",
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty() && clickNode(nodes.get(0))) return true;
        }
        String[] labels = {"End call", "End voice call", "Hang up", "Leave call", "End"};
        return tapByLabel(root, labels);
    }

    // ---- session-UI observation + external-end reconciliation (v1.5.2) ----

    /** Observation outcomes for observeVoiceSessionUi(). */
    public static final int UI_PRESENT = 1;
    public static final int UI_ABSENT = 0;
    public static final int UI_UNKNOWN = -1;

    /** Session-presence markers, find-only. The id set leads with
     *  the ids this Muse build actually renders during a session
     *  (verified in a live uiautomator dump 2026-11-08:
     *  hatch-voice-end-call-button / hatch-voice-call-connected /
     *  hatch-floating-call-controls, with "End voice call" and
     *  "Mute voice call" descriptions); the hunt's REA-era id/label
     *  candidates follow as alternates. A node matching any of
     *  these exists only while a voice session is on screen — the
     *  chat screen shows none of them. */
    private static final String[] SESSION_IDS = {
            "com.facebook.aura:id/hatch-voice-end-call-button",
            "com.facebook.aura:id/hatch-voice-call-connected",
            "com.facebook.aura:id/hatch-floating-call-controls",
            "com.facebook.aura:id/end_voice_button",
            "com.facebook.aura:id/end_call_button",
            "com.facebook.aura:id/voice_end_call",
    };
    private static final String[] SESSION_LABELS = {
            "End voice call", "End call", "Hang up", "Leave call", "Mute voice call",
    };

    /** Observe whether Muse's voice-session UI is present, across
     *  the active window and every interactive window the service
     *  can retrieve. UI_PRESENT: a session control was found.
     *  UI_ABSENT: at least one Muse window was inspected and none
     *  carried a session control. UI_UNKNOWN: no Muse window is
     *  observable at all (backgrounded away / not running / screen
     *  off) — callers decide what that means for their context. */
    public int observeVoiceSessionUi() {
        boolean sawAura = false;
        AccessibilityNodeInfo active = null;
        try { active = getRootInActiveWindow(); } catch (Exception ignored) {}
        if (active != null && "com.facebook.aura".contentEquals(active.getPackageName())) {
            sawAura = true;
            if (containsSessionUi(active)) return UI_PRESENT;
        }
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = null;
                    try { r = w.getRoot(); } catch (Exception ignored) {}
                    if (r != null && "com.facebook.aura".contentEquals(r.getPackageName())) {
                        sawAura = true;
                        if (containsSessionUi(r)) return UI_PRESENT;
                    }
                }
            }
        } catch (Exception ignored) {}
        return sawAura ? UI_ABSENT : UI_UNKNOWN;
    }

    private boolean containsSessionUi(AccessibilityNodeInfo root) {
        for (String id : SESSION_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) return true;
        }
        for (String label : SESSION_LABELS) {
            if (findByLabel(root, label)) return true;
        }
        return false;
    }

    /** Find-only twin of tapByLabel: exact text/description match,
     *  never clicks. */
    private boolean findByLabel(AccessibilityNodeInfo root, String label) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
        if (nodes != null) {
            for (AccessibilityNodeInfo n : nodes) {
                CharSequence t = n.getText();
                CharSequence d = n.getContentDescription();
                if ((t != null && label.equalsIgnoreCase(t.toString()))
                        || (d != null && label.equalsIgnoreCase(d.toString()))) {
                    return true;
                }
            }
        }
        return findByDescription(root, label);
    }

    private boolean findByDescription(AccessibilityNodeInfo node, String label) {
        if (node == null) return false;
        CharSequence desc = node.getContentDescription();
        if (desc != null && label.equalsIgnoreCase(desc.toString())) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (findByDescription(node.getChild(i), label)) return true;
        }
        return false;
    }

    /** External-end watch: when the persisted belief says a session
     *  is live but no hunt is armed, keep an eye on the Muse
     *  window; if the session UI has left (Jonathan ended the
     *  session from the Muse side — hang-up button, closing the
     *  session UI) clear the belief so the report tells the truth
     *  and the next voice_mode on dispatches a real start instead
     *  of no-opping against a dead session (the 2026-11-08 23:28
     *  desync this fixes). Absence must survive two observations
     *  ~1.5 s apart, and nothing runs inside the start dispatch's
     *  grace window, so a session still rendering after its start
     *  tap is never cleared by mistake. */
    private boolean sessionEndCheckPending = false;
    private final Runnable sessionEndCheck = new Runnable() {
        @Override
        public void run() {
            if (observeVoiceSessionUi() == UI_ABSENT) {
                handler.postDelayed(sessionEndConfirm, 1500);
            } else {
                sessionEndCheckPending = false;
            }
        }
    };
    private final Runnable sessionEndConfirm = new Runnable() {
        @Override
        public void run() {
            sessionEndCheckPending = false;
            if (VoiceState.awaitingVoiceTap || VoiceState.awaitingEndTap) return;
            if (observeVoiceSessionUi() == UI_ABSENT && VoiceState.isActive(VoiceAccessibilityService.this)) {
                VoiceState.setActive(VoiceAccessibilityService.this, false);
                BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                        "session UI gone (ended on the Muse side); cleared voice_active");
            }
        }
    };

    private void maybeScheduleSessionEndCheck() {
        if (sessionEndCheckPending) return;
        if (VoiceState.awaitingVoiceTap || VoiceState.awaitingEndTap) return;
        if (!VoiceState.isActive(this)) return;
        if (System.currentTimeMillis() - VoiceState.lastStartDispatchMs
                < VoiceState.DISPATCH_GRACE_MS) return;
        sessionEndCheckPending = true;
        handler.postDelayed(sessionEndCheck, 2500);
    }

    // ---- shared helpers ----

    private AccessibilityNodeInfo museRoot() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || root.getPackageName() == null
                || !"com.facebook.aura".contentEquals(root.getPackageName())) {
            return null;
        }
        return root;
    }

    private boolean tapByLabel(AccessibilityNodeInfo root, String[] labels) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes != null) {
                for (AccessibilityNodeInfo n : nodes) {
                    CharSequence t = n.getText();
                    CharSequence d = n.getContentDescription();
                    if ((t != null && label.equalsIgnoreCase(t.toString()))
                            || (d != null && label.equalsIgnoreCase(d.toString()))) {
                        if (clickNode(n)) return true;
                    }
                }
            }
            if (searchByDescription(root, label)) return true;
        }
        return false;
    }

    private boolean searchByDescription(AccessibilityNodeInfo node, String label) {
        if (node == null) return false;
        CharSequence desc = node.getContentDescription();
        if (desc != null && label.equalsIgnoreCase(desc.toString()) && clickNode(node)) {
            return true;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (searchByDescription(node.getChild(i), label)) return true;
        }
        return false;
    }

    private boolean clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        while (n != null) {
            if (n.isClickable()) {
                return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            n = n.getParent();
        }
        return false;
    }
}
