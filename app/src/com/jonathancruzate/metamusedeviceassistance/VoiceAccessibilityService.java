package com.jonathancruzate.metamusedeviceassistance;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
 *   PiP (v1.10.0): after a self-activated START tap lands and the
 *   session UI is confirmed present (+ ~900 ms settle), return to
 *   the app Jonathan was using (recorded pre-launch) — leaving
 *   aura triggers the voice session's auto-PiP (mechanism
 *   confirmed by the device owner 2026-11-09; live verification
 *   pending the joint trial) — or GLOBAL_ACTION_HOME when nothing
 *   returnable was recorded; the observed PiP result is
 *   BridgeLog'd, the dossier's minimize hunt remains as fallback
 *   only, and a full node inventory is dumped once per session.
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
        if (event == null || event.getPackageName() == null) return;
        // v1.14: the bridge hunt is not aura-gated — when a bridge
        // send is pending, any window change is a chance to run it
        // (the hunt itself launches Muse when it isn't foreground).
        if (VoiceState.awaitingBridgeSend) scheduleBridgeHunt();
        if (!"com.facebook.aura".contentEquals(event.getPackageName())) {
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
                // v1.10.0: a self-activated session gets one PiP
                // attempt once its UI is confirmed present.
                // v1.15: for a combined voice_call, the attempt is
                // deferred until the marker lands (return is last).
                schedulePipAttemptAfterTap();
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
        // A fresh self-activation re-arms the one-shot PiP attempt
        // and its once-per-session node inventory (v1.10.0).
        pipScheduled = false;
        pipAttemptDone = false;
        pipDumpDone = false;
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

    // ---- PiP confirmation (v1.10.0) ----
    //
    // Ground truth (Jonathan, device owner, 2026-11-09 23:40 EST):
    // "The app always goes into PiP if voice mode is on" — aura's
    // voice session auto-PiPs on user-leave. So after a
    // SELF-ACTIVATED start tap lands (the voice hunt above — the
    // only path that arms this), wait until the session UI is
    // confirmed present, settle ~900 ms, then run Jonathan's
    // choreography (adopted 2026-11-09 23:41 EST): return to the
    // app he was using before the activation (recorded by
    // VoiceLauncher before the assist launch; GLOBAL_ACTION_HOME
    // when nothing returnable was recorded), which floats the
    // session over that app. Then verify + log whether a PiP
    // window is actually observed. The dossier's minimize-control
    // hunt (docs/pip-investigation-2026-11-09.md, Q3) survives
    // ONLY as the fallback when the return logs "no PiP window
    // observed" — at most one tap. Before the return, the FULL
    // node inventory of the Muse voice window is dumped to
    // BridgeLog once per session (ids + labels) for the record.
    // PiP itself can never be invoked on aura from outside
    // (owner-only API); this sequence only performs the user-leave
    // and, in the fallback, taps aura's own controls.

    private static final String[] PIP_IDS = {
            "com.facebook.aura:id/hatch-voice-minimize-button",
            "com.facebook.aura:id/hatch-voice-pip-button",
            "com.facebook.aura:id/hatch-minimize-button",
            "com.facebook.aura:id/hatch-voice-collapse-button",
            "com.facebook.aura:id/minimize_button",
            "com.facebook.aura:id/pip_button",
    };
    private static final String[] PIP_LABELS = {
            "Minimize", "Picture in picture", "Collapse", "Shrink", "Floating window",
    };
    /** Dossier spec: settle ~600–1200 ms after UI_PRESENT. */
    private static final long PIP_SETTLE_MS = 900;

    private boolean pipScheduled = false;
    private boolean pipAttemptDone = false;
    private boolean pipDumpDone = false;
    private int pipWaitAttempts = 0;

    private void schedulePipAttempt() {
        // The choke point (v1.17, Jonathan 2026-11-11 20:47 EST —
        // "Prompt first then back to last app"): while a combined
        // voice_call's prompt is unsent, NO return path runs, no
        // matter which caller asks. The return is released only
        // by onMarkerResolved (which clears combinedCallPending
        // first) or the watchdog's never-strand exception. This
        // makes the order structural, not a convention each call
        // site must remember.
        if (VoiceState.combinedCallPending) {
            pipDeferredForMarker = true;
            BridgeLog.log(this, "VOICE",
                    "voice_call: return held at the gate"
                    + " (prompt not sent yet)");
            return;
        }
        if (pipScheduled || pipAttemptDone) return;
        pipScheduled = true;
        pipWaitAttempts = 0;
        handler.postDelayed(pipWaitStep, 500);
    }

    private final Runnable pipWaitStep = new Runnable() {
        @Override
        public void run() {
            if (!VoiceState.isActive(VoiceAccessibilityService.this)
                    || VoiceState.awaitingEndTap) {
                pipScheduled = false;
                maybeRelock("PiP wait moot - session gone");
                return;
            }
            if (observeVoiceSessionUi() == UI_PRESENT) {
                handler.postDelayed(pipDoStep, PIP_SETTLE_MS);
                return;
            }
            pipWaitAttempts++;
            if (pipWaitAttempts >= 14) { // ~10 s: session never rendered
                pipScheduled = false;
                pipAttemptDone = true;
                BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                        "PiP attempt aborted: session UI never confirmed present");
                maybeRelock("PiP attempt aborted");
                return;
            }
            handler.postDelayed(this, 700);
        }
    };

    private final Runnable pipDoStep = new Runnable() {
        @Override
        public void run() {
            pipScheduled = false;
            if (pipAttemptDone) return;
            pipAttemptDone = true;
            if (!VoiceState.isActive(VoiceAccessibilityService.this)) {
                BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                        "PiP step skipped: session no longer believed active");
                maybeRelock("PiP step skipped");
                return;
            }
            dumpSessionInventoryOnce();
            // Jonathan's choreography (2026-11-09 23:41 EST): go
            // back to the app he was using. Leaving aura is the
            // user-leave that triggers the voice session's auto-PiP
            // (device-owner confirmed 2026-11-09: the app always
            // goes into PiP if voice mode is on), so the session
            // floats over his app instead of dumping him on the
            // launcher. No recorded app (or it was the launcher)
            // -> GLOBAL_ACTION_HOME, the equivalent landing.
            String target = VoiceState.returnTargetPkg;
            if (target != null && relaunchPackage(target)) {
                BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                        "PiP return: relaunched " + target
                                + " (recorded foreground; leaving aura triggers auto-PiP)");
            } else {
                boolean home = performGlobalAction(GLOBAL_ACTION_HOME);
                BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                        "PiP return: GLOBAL_ACTION_HOME fired=" + home
                                + (target == null
                                        ? " (no returnable app recorded)"
                                        : " (relaunch of " + target + " failed)"));
            }
            handler.postDelayed(pipVerifyStep, 1200);
        }
    };

    private final Runnable pipVerifyStep = new Runnable() {
        @Override
        public void run() {
            boolean pip = pipWindowObserved();
            BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                    "PiP verify: pipWindowObserved=" + pip
                            + " foreground=" + activeRootPackage()
                            + " sessionUi=" + uiName(observeVoiceSessionUi()));
            if (pip) {
                maybeRelock("return complete, session floating");
                return;
            }
            // Fallback only (dossier Q3): the return did not produce
            // an observed PiP window, so try aura's own minimize
            // control — at most one tap.
            AccessibilityNodeInfo root = auraSessionRoot();
            String tapped = (root == null) ? null : tryTapPipControl(root);
            if (tapped != null) {
                BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                        "PiP fallback: minimize control tapped (" + tapped + ")");
                handler.postDelayed(pipFinalStep, 1200);
            } else {
                BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                        "PiP fallback: no PiP window observed and no minimize "
                                + "control found; session left as-is");
                maybeRelock("return settled without PiP");
            }
        }
    };

    private final Runnable pipFinalStep = new Runnable() {
        @Override
        public void run() {
            BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                    "PiP final: pipWindowObserved=" + pipWindowObserved()
                            + " foreground=" + activeRootPackage()
                            + " sessionUi=" + uiName(observeVoiceSessionUi()));
            maybeRelock("PiP final");
        }
    };

    /** The package of the active window's root, unfiltered (null
     *  when nothing is observable). */
    private String activeRootPackage() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r != null && r.getPackageName() != null) return r.getPackageName().toString();
        } catch (Exception ignored) {}
        return null;
    }

    /** Foreground package worth returning to after a voice start:
     *  the foreground APPLICATION's package, skipping aura itself,
     *  our own package, and the launcher (returning to the launcher
     *  is what GLOBAL_ACTION_HOME already does). Null when none
     *  qualifies.
     *
     *  The scan is restricted to TYPE_APPLICATION windows: the
     *  window list also carries system overlays — Samsung One Hand
     *  Operation + (Good Lock) keeps a persistent TYPE_APPLICATION_
     *  OVERLAY edge-handle window on screen — and an overlay is
     *  never the app Jonathan was using. The pre-fix scan iterated
     *  getWindows() unfiltered, so when the active root came back
     *  null / aura / us / launcher, the fallback could record the
     *  One Hand overlay's package as the "foreground app" and the
     *  PiP return then relaunched One Hand Operation + instead of
     *  his app (Jonathan's bug report, 2026-11-11 ~02:50 EST;
     *  worked before v1.10.0 only because the pre-PiP flow never
     *  relaunched a recorded package at all). The recorded package
     *  is logged at capture (VoiceLauncher) so a bad capture is
     *  visible in the BridgeLog instead of only at return time. */
    public String currentForegroundPackage() {
        String pkg = activeRootPackage();
        if (isReturnable(pkg)) return pkg;
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    if (w == null || w.getType()
                            != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    AccessibilityNodeInfo r = null;
                    try { r = w.getRoot(); } catch (Exception ignored) {}
                    if (r != null && r.getPackageName() != null
                            && isReturnable(r.getPackageName().toString())) {
                        return r.getPackageName().toString();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean isReturnable(String pkg) {
        return pkg != null
                && !"com.facebook.aura".contentEquals(pkg)
                && !getPackageName().contentEquals(pkg)
                && !isLauncherPackage(pkg)
                // Samsung One Hand Operation + (Good Lock): a
                // gesture-overlay utility, never an app Jonathan
                // was "using" — belt-and-braces alongside the
                // TYPE_APPLICATION filter above, in case a future
                // One Hand build presents an application window.
                && !"com.samsung.android.sidegesturepad".contentEquals(pkg);
    }

    private String homePackageCache = null;
    private boolean isLauncherPackage(String pkg) {
        if (pkg == null) return false;
        if (homePackageCache == null) {
            try {
                android.content.Intent home = new android.content.Intent(
                        android.content.Intent.ACTION_MAIN);
                home.addCategory(android.content.Intent.CATEGORY_HOME);
                android.content.pm.ResolveInfo ri =
                        getPackageManager().resolveActivity(home, 0);
                if (ri != null && ri.activityInfo != null) {
                    homePackageCache = ri.activityInfo.packageName;
                }
            } catch (Exception ignored) {}
            if (homePackageCache == null) homePackageCache = "";
        }
        return pkg.equals(homePackageCache)
                || "com.sec.android.app.launcher".contentEquals(pkg)
                || "com.google.android.apps.nexuslauncher".contentEquals(pkg)
                || "com.android.launcher3".contentEquals(pkg);
    }

    private boolean relaunchPackage(String pkg) {
        try {
            android.content.Intent launch =
                    getPackageManager().getLaunchIntentForPackage(pkg);
            if (launch == null) return false;
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** A floating PiP window presents as an aura window much
     *  smaller than the display while another app holds the active
     *  window. Heuristic, logged with its inputs at verify time. */
    private boolean pipWindowObserved() {
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins == null) return false;
            for (AccessibilityWindowInfo w : wins) {
                AccessibilityNodeInfo r = null;
                try { r = w.getRoot(); } catch (Exception ignored) {}
                if (r != null && "com.facebook.aura".contentEquals(r.getPackageName())) {
                    android.graphics.Rect b = new android.graphics.Rect();
                    w.getBoundsInScreen(b);
                    if (b.width() > 0 && b.height() > 0
                            && b.width() <= 0.65 * dm.widthPixels
                            && b.height() <= 0.65 * dm.heightPixels) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private String uiName(int ui) {
        return ui == UI_PRESENT ? "PRESENT" : ui == UI_ABSENT ? "ABSENT" : "UNKNOWN";
    }

    /** Returns a description of the branch + candidate that landed,
     *  or null if no candidate was found/tappable. At most one tap. */
    private String tryTapPipControl(AccessibilityNodeInfo root) {
        for (String id : PIP_IDS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty() && clickNode(nodes.get(0))) {
                return "branch=id candidate=" + id;
            }
        }
        for (String label : PIP_LABELS) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(label);
            if (nodes != null) {
                for (AccessibilityNodeInfo n : nodes) {
                    CharSequence t = n.getText();
                    CharSequence d = n.getContentDescription();
                    if ((t != null && label.equalsIgnoreCase(t.toString()))
                            || (d != null && label.equalsIgnoreCase(d.toString()))) {
                        if (clickNode(n)) return "branch=label candidate=" + label;
                    }
                }
            }
            if (searchByDescription(root, label)) {
                return "branch=label candidate=" + label;
            }
        }
        return null;
    }

    /** A Muse root that carries the session UI, searching the
     *  active window then every window (mirrors observeVoiceSessionUi's
     *  traversal). Null if no Muse window is observable. */
    private AccessibilityNodeInfo auraSessionRoot() {
        AccessibilityNodeInfo active = null;
        try { active = getRootInActiveWindow(); } catch (Exception ignored) {}
        if (active != null && "com.facebook.aura".contentEquals(active.getPackageName())
                && containsSessionUi(active)) {
            return active;
        }
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = null;
                    try { r = w.getRoot(); } catch (Exception ignored) {}
                    if (r != null && "com.facebook.aura".contentEquals(r.getPackageName())
                            && containsSessionUi(r)) {
                        return r;
                    }
                }
            }
        } catch (Exception ignored) {}
        return active != null && "com.facebook.aura".contentEquals(active.getPackageName())
                ? active : null;
    }

    /** Once per session: dump the full accessibility node inventory
     *  of the Muse voice window(s) to BridgeLog — view-ids, class,
     *  text, content-description, clickable — so the dossier's Q3
     *  minimize candidates can be confirmed or struck from real
     *  evidence after the first live trial. */
    private void dumpSessionInventoryOnce() {
        if (pipDumpDone) return;
        pipDumpDone = true;
        java.util.Set<AccessibilityNodeInfo> seen =
                new java.util.HashSet<AccessibilityNodeInfo>();
        int[] count = {0};
        BridgeLog.log(this, "VOICE", "PiP inventory dump: begin");
        AccessibilityNodeInfo active = null;
        try { active = getRootInActiveWindow(); } catch (Exception ignored) {}
        if (active != null && "com.facebook.aura".contentEquals(active.getPackageName())) {
            dumpNodeTree(active, 0, seen, count);
        }
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = null;
                    try { r = w.getRoot(); } catch (Exception ignored) {}
                    if (r != null && "com.facebook.aura".contentEquals(r.getPackageName())) {
                        dumpNodeTree(r, 0, seen, count);
                    }
                }
            }
        } catch (Exception ignored) {}
        BridgeLog.log(this, "VOICE",
                "PiP inventory dump: end (" + count[0] + " nodes)");
    }

    private void dumpNodeTree(AccessibilityNodeInfo node, int depth,
                              java.util.Set<AccessibilityNodeInfo> seen, int[] count) {
        if (node == null || count[0] >= 400 || !seen.add(node)) return;
        count[0]++;
        StringBuilder sb = new StringBuilder("PiP node d=").append(depth);
        try {
            String vid = node.getViewIdResourceName();
            sb.append(" id=").append(vid == null ? "-" : vid);
            CharSequence cls = node.getClassName();
            sb.append(" class=").append(cls == null ? "-" : cls.toString());
            CharSequence t = node.getText();
            if (t != null && t.length() > 0) sb.append(" text=\"").append(t).append("\"");
            CharSequence d = node.getContentDescription();
            if (d != null && d.length() > 0) sb.append(" desc=\"").append(d).append("\"");
            sb.append(" clickable=").append(node.isClickable());
        } catch (Exception ignored) {}
        BridgeLog.log(this, "VOICE", sb.toString());
        for (int i = 0; i < node.getChildCount(); i++) {
            dumpNodeTree(node.getChild(i), depth + 1, seen, count);
        }
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

    /** The CONNECTED-only marker (v1.17): the end-call button and
     *  the floating controls render during the CONNECTING screen
     *  too, so the generic session-UI probe trips seconds before
     *  the session is actually live (measured 2026-11-11 20:46
     *  EST: prompt typed + return released off the connecting UI
     *  while the session start landed ~5 s later — Jonathan:
     *  "it went back before the prompt was sent"). The
     *  hatch-voice-call-connected node is the build's own
     *  connected-state element; the voice_call hook (v1.16) keys
     *  on this alone from v1.17 on. */
    private static final String CONNECTED_ID =
            "com.facebook.aura:id/hatch-voice-call-connected";

    /** UI_PRESENT only when the CONNECTED marker is on screen in
     *  an aura window; same scan shape as observeVoiceSessionUi. */
    public int observeVoiceConnected() {
        AccessibilityNodeInfo active = null;
        try { active = getRootInActiveWindow(); } catch (Exception ignored) {}
        if (active != null && "com.facebook.aura".contentEquals(active.getPackageName())
                && containsConnected(active)) {
            return UI_PRESENT;
        }
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = null;
                    try { r = w.getRoot(); } catch (Exception ignored) {}
                    if (r != null && "com.facebook.aura".contentEquals(r.getPackageName())
                            && containsConnected(r)) {
                        return UI_PRESENT;
                    }
                }
            }
        } catch (Exception ignored) {}
        return UI_ABSENT;
    }

    private boolean containsConnected(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes =
                root.findAccessibilityNodeInfosByViewId(CONNECTED_ID);
        return nodes != null && !nodes.isEmpty();
    }

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

    // ---- BRIDGE hunt (v1.14) ----
    // Types a bridge message into the Muse app's main-conversation
    // composer and sends it AS Jonathan (his rule, 2026-11-09
    // 00:04 EST, re-taught 2026-11-11 01:20 EST): a voice session
    // only hears the conversation's own user turns, and this
    // service is the only hands that can produce one. Flow: bring
    // Muse forward if needed, find the editable composer,
    // ACTION_SET_TEXT, tap Send. Every outcome is BridgeLog'd;
    // a failed hunt fails loud, never silently.

    private int bridgeAttempts = 0;
    private boolean bridgeRunning = false;
    private boolean bridgeLaunchedApp = false;
    /** Consecutive CONNECTED polls for the v1.17 sustained gate. */
    private int holdConnectedStreak = 0;

    /** Entry from VoiceBridge (command thread): kick the hunt on
     *  this service's main-looper handler. */
    public void kickBridgeHunt() {
        handler.post(bridgeStep);
    }

    private void scheduleBridgeHunt() {
        if (!bridgeRunning) handler.post(bridgeStep);
    }

    // ---- Combined voice_call sequencing (v1.15) ----
    // Jonathan's order (2026-11-11 12:24 EST): type while the app
    // is open; the back-to-the-last-app return is the LAST step.
    // So for a combined fire, the activation's PiP attempt is
    // DEFERRED until the bridge hunt resolves (sent or failed) —
    // the return can never yank Muse away mid-typing — and the
    // watchdog forces the return if the marker stalls, so he is
    // never stranded in the Muse app.

    private boolean pipDeferredForMarker = false;

    /** Called from the voice hunt the moment the start tap lands:
     *  schedule the PiP attempt now, or hold it for the marker. */
    private void schedulePipAttemptAfterTap() {
        if (VoiceState.combinedCallPending) {
            pipDeferredForMarker = true;
            BridgeLog.log(this, "VOICE",
                    "voice_call: PiP return deferred until marker lands");
            return;
        }
        schedulePipAttempt();
    }

    /** Called when the bridge hunt resolves (sent or failed):
     *  release a deferred PiP return, completing the sequence. */
    /** Re-lock at the end of a sequence we had to unlock for
     *  (v1.19, Jonathan: "try to unlock the phone and then lock
     *  it again"): restores the phone to the locked state he
     *  left it in. Fires only when WE did the unlocking; a
     *  sequence on an already-unlocked phone never locks him
     *  out of his own session. */
    private void maybeRelock(String where) {
        if (!VoiceState.weUnlockedPhone) return;
        VoiceState.weUnlockedPhone = false;
        boolean ok = false;
        try {
            ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        } catch (Exception ignored) {}
        BridgeLog.log(this, "VOICE", "relock (" + where + "): "
                + (ok ? "phone locked again" : "LOCK_SCREEN action failed"));
    }

    /** How long the return waits after the prompt is sent
     *  (v1.18, Jonathan 2026-11-11 21:02 EST — "you don't wait
     *  long enough after sending the prompt for the agent to
     *  process it"). Sending the marker is not the finish line:
     *  the session-side agent still has to process the turn,
     *  gather the context, generate, and start speaking, and a
     *  PiP return inside that window yanks the UI away
     *  mid-processing (attempt 6: return landed, nothing was
     *  spoken). Measured marker-to-speech in attempt 5 was
     *  ~13-18 s; the dwell gives processing a 20 s head start
     *  so speech is underway before the float-back. */
    private static final long POST_SEND_DWELL_MS = 20000;

    private void onMarkerResolved() {
        VoiceState.combinedCallPending = false;
        VoiceState.holdBridgeForSessionLive = false;
        if (!pipDeferredForMarker) return;
        pipDeferredForMarker = false;
        BridgeLog.log(this, "VOICE",
                "voice_call: prompt sent - dwelling 20s for the"
                + " agent to process before the return");
        handler.postDelayed(() -> {
            BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                    "voice_call: dwell over - return to last app now");
            schedulePipAttempt();
        }, POST_SEND_DWELL_MS);
    }

    /** If the marker stalls (hunt retries exhausted without a
     *  terminal callback, or the voice tap never landed), never
     *  strand him in Muse: force the deferred return and clear
     *  the sequence. 50 s since v1.16: the session-live hold can
     *  legitimately hold the prompt up to 30 s before typing even
     *  starts, and the typing hunt gets its own ~17 s after that. */
    public void armCombinedWatchdog() {
        handler.postDelayed(combinedWatchdog, 50000);
    }

    private final Runnable combinedWatchdog = new Runnable() {
        @Override
        public void run() {
            if (!VoiceState.combinedCallPending) return;
            // v1.19: an unlock attempt in flight is progress, not
            // a stall — the keyguard poll gets up to 15 s and the
            // launch only starts after it. Re-arm instead of
            // forcing the return into a still-locked phone.
            if (VoiceState.unlockInFlight) {
                BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                        "voice_call: watchdog re-armed (unlock in flight)");
                handler.postDelayed(this, 20000);
                return;
            }
            BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                    "voice_call: watchdog forcing deferred return"
                    + " (marker still pending=" 
                    + VoiceState.awaitingBridgeSend + ")");
            onMarkerResolved();
        }
    };

    private final Runnable bridgeStep = new Runnable() {
        @Override
        public void run() {
            if (!VoiceState.awaitingBridgeSend) {
                bridgeRunning = false;
                return;
            }
            bridgeRunning = true;
            // The session-live hook (v1.16, Jonathan 2026-11-11
            // 20:15 EST): a combined fire holds its prompt until
            // the voice session is mechanically detected live, so
            // the prompt lands as a LIVE-session user turn — a
            // prompt typed before the session exists becomes
            // pre-session history and the opener ignores it
            // (measured twice, 2026-11-11 19:46 + 20:04 EST; the
            // proven speak-first at 12:10 had the prompt arrive
            // ~23 s after the start, into the live session). Poll
            // every 500 ms; at the hold deadline, type anyway —
            // the fallback keeps a fire from never landing.
            if (VoiceState.holdBridgeForSessionLive) {
                // v1.17: CONNECTED-only + sustained. The generic
                // session UI (end-call button) renders on the
                // connecting screen and released the prompt (and,
                // through it, the return) seconds before the
                // session was truly live. The prompt now waits for
                // the connected marker on two consecutive polls
                // (~1 s sustained) — prompt first, in a genuinely
                // live session, then back (Jonathan, 2026-11-11
                // 20:47 EST).
                if (observeVoiceConnected() == UI_PRESENT) {
                    holdConnectedStreak++;
                    if (holdConnectedStreak >= 2) {
                        VoiceState.holdBridgeForSessionLive = false;
                        holdConnectedStreak = 0;
                        BridgeLog.log(VoiceAccessibilityService.this,
                                "VOICE", "voice_call: session"
                                + " CONNECTED (sustained) - firing"
                                + " the prompt now");
                    } else {
                        handler.postDelayed(this, 500);
                        return;
                    }
                } else if (System.currentTimeMillis()
                        < VoiceState.bridgeHoldDeadlineMs) {
                    holdConnectedStreak = 0;
                    handler.postDelayed(this, 500);
                    return;
                } else {
                    VoiceState.holdBridgeForSessionLive = false;
                    holdConnectedStreak = 0;
                    BridgeLog.log(VoiceAccessibilityService.this,
                            "VOICE", "voice_call: session-connected"
                            + " not detected in 30s - typing anyway"
                            + " (fallback)");
                }
            }
            if (tryBridgeSend()) {
                VoiceState.awaitingBridgeSend = false;
                VoiceState.bridgeText = null;
                bridgeRunning = false;
                bridgeAttempts = 0;
                bridgeLaunchedApp = false;
                BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                        "bridge sent: message delivered into the main conversation");
                onMarkerResolved();
                return;
            }
            bridgeAttempts++;
            if (bridgeAttempts >= 24) { // ~17 s of retries, then stop loud
                VoiceState.awaitingBridgeSend = false;
                VoiceState.bridgeText = null;
                bridgeRunning = false;
                bridgeAttempts = 0;
                bridgeLaunchedApp = false;
                BridgeLog.log(VoiceAccessibilityService.this, "VOICE",
                        "bridge FAILED: composer/send never became reachable");
                onMarkerResolved();
                return;
            }
            handler.postDelayed(this, 700);
        }
    };

    private boolean tryBridgeSend() {
        String text = VoiceState.bridgeText;
        if (text == null) return true; // nothing pending; treat as done
        AccessibilityNodeInfo root = auraRootAnyWindow();
        if (root == null) {
            if (!bridgeLaunchedApp) {
                bridgeLaunchedApp = launchAuraMain();
                BridgeLog.log(this, "VOICE", "bridge: Muse not foreground, launch="
                        + bridgeLaunchedApp);
            }
            return false;
        }
        AccessibilityNodeInfo editable = findEditable(root);
        if (editable == null) return false;
        Bundle args = new Bundle();
        args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            editable.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            if (!editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return false;
            }
        }
        AccessibilityNodeInfo send = findSendButton(root);
        if (send == null) return false;
        boolean clicked = clickNode(send);
        if (clicked) {
            BridgeLog.log(this, "VOICE", "bridge: text set + send tapped");
        }
        return clicked;
    }

    /** Muse's root node from any window (the chat may not be the
     *  active window while a voice session floats over it). */
    private AccessibilityNodeInfo auraRootAnyWindow() {
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && r.getPackageName() != null
                            && "com.facebook.aura".contentEquals(r.getPackageName())) {
                        return r;
                    }
                }
            }
        } catch (Exception ignored) {}
        return museRoot();
    }

    private boolean launchAuraMain() {
        try {
            Intent launch = getPackageManager()
                    .getLaunchIntentForPackage("com.facebook.aura");
            if (launch == null) return false;
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
            return true;
        } catch (Exception e) {
            BridgeLog.log(this, "VOICE", "bridge: Muse launch failed: "
                    + e.getClass().getSimpleName());
            return false;
        }
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        CharSequence cls = node.getClassName();
        if (cls != null && "android.widget.EditText".contentEquals(cls)) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findEditable(node.getChild(i));
            if (hit != null) return hit;
        }
        return null;
    }

    private AccessibilityNodeInfo findSendButton(AccessibilityNodeInfo root) {
        String[] ids = {
                "com.facebook.aura:id/send_button",
                "com.facebook.aura:id/hatch_send_button",
                "com.facebook.aura:id/composer_send_button",
                "com.facebook.aura:id/send",
        };
        for (String id : ids) {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(id);
            if (nodes != null && !nodes.isEmpty()) return nodes.get(0);
        }
        return findSendInTree(root);
    }

    private AccessibilityNodeInfo findSendInTree(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isClickable()) {
            CharSequence d = node.getContentDescription();
            CharSequence t = node.getText();
            if ((d != null && d.toString().toLowerCase().contains("send"))
                    || (t != null && "Send".equalsIgnoreCase(t.toString()))) {
                return node;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findSendInTree(node.getChild(i));
            if (hit != null) return hit;
        }
        return null;
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

    // ---- Screen sharing hands (v1.25) ----
    // The takeover + snapshot surface for the screen-sharing module
    // (design: docs/v1.25-screen-sharing-design-2026-11-13.md),
    // reached only through ScreenGestures from ScreenRouter / the
    // capture service's input pump. Nothing here runs on its own:
    // no event handler calls into this section. Coordinates are
    // already real pixels (ScreenGestures owns the normalized ->
    // pixel scaling). Gesture calls block the CALLING thread on the
    // platform callback (callers are bridge / capture threads,
    // never the main thread).

    /** Dispatch one stroke and wait for the platform verdict. */
    private boolean dispatchStrokeAndWait(Path path, long durationMs) {
        try {
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, durationMs);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke).build();
            final CountDownLatch latch = new CountDownLatch(1);
            final boolean[] completed = {false};
            boolean dispatched = dispatchGesture(gesture,
                    new GestureResultCallback() {
                        @Override
                        public void onCompleted(GestureDescription g) {
                            completed[0] = true;
                            latch.countDown();
                        }

                        @Override
                        public void onCancelled(GestureDescription g) {
                            latch.countDown();
                        }
                    }, null);
            if (!dispatched) return false;
            latch.await(3, TimeUnit.SECONDS);
            return completed[0];
        } catch (Exception e) {
            return false;
        }
    }

    /** One tap at real pixels. */
    public boolean screenTapPx(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        return dispatchStrokeAndWait(p, 60);
    }

    /** One swipe between real pixels over durationMs. */
    public boolean screenSwipePx(int x1, int y1, int x2, int y2, long durationMs) {
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        return dispatchStrokeAndWait(p, durationMs);
    }

    /** A global action by wire name (gate-validated vocabulary). */
    public boolean screenGlobal(String name) {
        int action;
        switch (name) {
            case "back": action = GLOBAL_ACTION_BACK; break;
            case "home": action = GLOBAL_ACTION_HOME; break;
            case "recents": action = GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": action = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "lock": action = GLOBAL_ACTION_LOCK_SCREEN; break;
            default: return false;
        }
        try {
            return performGlobalAction(action);
        } catch (Exception e) {
            return false;
        }
    }

    /** Type into the focused editable node. Null on success. */
    public String screenTypeText(String text) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return "no_active_window";
            AccessibilityNodeInfo target = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (target == null || !target.isEditable()) {
                target = findEditable(root);
            }
            if (target == null) return "no_editable_focused";
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                return null;
            }
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    ? null : "set_text_refused";
        } catch (Exception e) {
            return "set_text_failed:" + e.getClass().getSimpleName();
        }
    }

    /** Tap the node exactly labelled (text or description,
     *  case-insensitive) in the active window or any window. */
    public boolean screenFindTap(String label) {
        AccessibilityNodeInfo root = null;
        try { root = getRootInActiveWindow(); } catch (Exception ignored) {}
        if (root != null) {
            AccessibilityNodeInfo hit = findByExactLabel(root, label);
            if (hit != null && clickNode(hit)) return true;
        }
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                for (AccessibilityWindowInfo w : wins) {
                    AccessibilityNodeInfo r = null;
                    try { r = w.getRoot(); } catch (Exception ignored) {}
                    if (r == null || r == root) continue;
                    AccessibilityNodeInfo hit = findByExactLabel(r, label);
                    if (hit != null && clickNode(hit)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private AccessibilityNodeInfo findByExactLabel(AccessibilityNodeInfo node, String label) {
        if (node == null) return null;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if ((t != null && label.equalsIgnoreCase(t.toString()))
                || (d != null && label.equalsIgnoreCase(d.toString()))) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findByExactLabel(node.getChild(i), label);
            if (hit != null) return hit;
        }
        return null;
    }

    /**
     * One snapshot via takeScreenshot (API 30+; the module's config
     * declares canTakeScreenshot). Returns a software ARGB_8888
     * bitmap the caller owns, or null on any refusal — including a
     * FLAG_SECURE window in front, which is the platform's rule.
     * Blocks the calling thread up to ~8 s on the callback.
     */
    public Bitmap screenCaptureBitmap() {
        if (android.os.Build.VERSION.SDK_INT < 30) return null;
        final CountDownLatch latch = new CountDownLatch(1);
        final Bitmap[] out = {null};
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            HardwareBuffer hb = null;
                            try {
                                hb = result.getHardwareBuffer();
                                Bitmap hw = Bitmap.wrapHardwareBuffer(
                                        hb, result.getColorSpace());
                                if (hw != null) {
                                    out[0] = hw.copy(Bitmap.Config.ARGB_8888, false);
                                    hw.recycle();
                                }
                            } catch (Exception ignored) {
                            } finally {
                                if (hb != null) {
                                    try { hb.close(); } catch (Exception ignored) {}
                                }
                                latch.countDown();
                            }
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            latch.countDown();
                        }
                    });
        } catch (Exception e) {
            return null;
        }
        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        return out[0];
    }
}
