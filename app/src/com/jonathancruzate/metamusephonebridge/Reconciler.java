package com.jonathancruzate.metamusephonebridge;

import android.content.Context;

/**
 * The reconciler — in v1.0.0 it runs in SHADOW mode: it evaluates desired
 * (from the Worker) against observed (notification ground truth), produces
 * the verified-ack decision and the drift plan, and LOGS what Phase 2
 * actuation would do. It NEVER fires a routine in this version.
 *
 * Jonathan's rules, encoded literally (phone-automations skill):
 *  - Matched real Modes clear each other: fire the TARGET mode directly.
 *  - Away is not a Mode, it is a routine latch: it never self-clears; a
 *    non-away target with the Away latch running first toggles the latch
 *    OFF (one re-fire), then fires the target — never a blind toggle.
 *  - A mode that reads active while its switch routine is NOT running is
 *    a drifted latch: toggle that switch ON then OFF to clear it, only
 *    then start the target.
 *  - An ack is verified ONLY when observed state matches desired state.
 *    Anything else is drift or unknown — never a success report.
 */
public final class Reconciler {

    public static final class Verdict {
        public String state;   // in_sync | drift | unknown
        public String plan;    // shadow plan when drift, else ""
    }

    private Reconciler() {}

    /** House mode (Worker vocabulary) -> Samsung Current mode name.
     *  "away" has no Samsung Mode; it is the Switch Away routine latch. */
    public static String samsungModeFor(String houseMode) {
        if (houseMode == null) return null;
        switch (houseMode.toLowerCase()) {
            case "home": return "Home";
            case "night": return "Sleep";
            case "morning": return "Morning";
            case "away": return null;
            default: return null;
        }
    }

    /** Canonical Samsung mode name: the running list renders the Home mode
     *  as "At Home" while field 1 renders it "Home". */
    public static String canonicalMode(String mode) {
        if (mode == null) return "";
        String m = mode.trim();
        if (m.equalsIgnoreCase("at home")) return "Home";
        return m;
    }

    /** The observed mode the verdicts and reports speak in. Field 1 is the
     *  authority when it names a real Mode. On this phone the house modes
     *  are largely routine-held: field 1 reads "None" while the running
     *  list (field 2) names the engaged mode's routine ("At Home",
     *  "Switch Sleep mode") — the incumbent mode-sync system treats that
     *  signature as the engaged mode (verified aligned 2026-11-02), so
     *  resolve it the same way. The Away latch never resolves a mode:
     *  a latch is not an engagement. (Phase 1 calibration, 2026-11-03.) */
    public static String resolveObservedMode(String gtMode, String gtRunning) {
        String mode = canonicalMode(gtMode);
        if (!mode.isEmpty() && !"none".equalsIgnoreCase(mode)) return mode;
        if (gtRunning == null) return mode;
        String low = gtRunning.toLowerCase();
        if (low.contains("at home") || low.contains("switch home")) return "Home";
        if (low.contains("sleep")) return "Sleep";
        if (low.contains("morning")) return "Morning";
        return mode;
    }

    public static Verdict evaluate(Context ctx) {
        Verdict v = new Verdict();
        String desired = Store.get(ctx, Store.K_DESIRED_MODE, "");
        String gtMode = Store.get(ctx, Store.K_GT_MODE, "");
        String gtRunning = Store.get(ctx, Store.K_GT_RUNNING, "");
        long gtAt = Store.getLong(ctx, Store.K_GT_AT, 0);
        boolean parseOk = Store.getBool(ctx, Store.K_GT_PARSE_OK, false);

        if (desired.isEmpty()) {
            v.state = "unknown";
            v.plan = "no desired state received from the Worker yet";
            return finish(ctx, v);
        }
        if (gtAt == 0 || !parseOk) {
            v.state = "unknown";
            v.plan = "no trustworthy ground truth yet (listener access or a fresh Device Status dump needed)";
            return finish(ctx, v);
        }
        // Ground truth older than 30 minutes is stale evidence, not truth.
        if (System.currentTimeMillis() - gtAt > 30 * 60 * 1000) {
            v.state = "unknown";
            v.plan = "ground truth is stale (>30 min); a fresh dump is needed before any verdict";
            return finish(ctx, v);
        }

        boolean awayLatched = gtRunning.toLowerCase().contains("switch away");
        String target = samsungModeFor(desired);
        String effMode = resolveObservedMode(gtMode, gtRunning);

        if ("away".equals(desired.toLowerCase())) {
            if (awayLatched) {
                v.state = "in_sync";
                v.plan = "";
            } else {
                v.state = "drift";
                v.plan = "fire Switch Away mode once to latch Away (shadow: not fired)";
            }
            return finish(ctx, v);
        }

        // Non-away target.
        if (target != null && target.equalsIgnoreCase(effMode)) {
            if (awayLatched) {
                v.state = "drift";
                v.plan = "mode already " + target + " but the Away latch is also running: "
                        + "toggle Switch Away OFF once (shadow: not fired)";
            } else {
                v.state = "in_sync";
                v.plan = "";
            }
            return finish(ctx, v);
        }

        // Drifted-latch case: a mode reads active but its switch routine is
        // not in the running list.
        StringBuilder plan = new StringBuilder();
        if (!"none".equalsIgnoreCase(effMode) && !effMode.isEmpty()
                && !runningNamesMode(gtRunning, effMode)) {
            plan.append("drifted latch: ").append(effMode)
                .append(" reads active but its switch routine is not running — ")
                .append("toggle its switch ON then OFF to clear it; ");
        }
        if (awayLatched) {
            plan.append("toggle Switch Away OFF once; ");
        }
        plan.append("fire ").append(target == null ? desired : target)
            .append(" directly (matched Modes clear each other); ")
            .append("then verify against a fresh dump (shadow: not fired)");
        v.state = "drift";
        v.plan = plan.toString();
        return finish(ctx, v);
    }

    private static boolean runningNamesMode(String running, String mode) {
        if (running == null || running.isEmpty()) return false;
        String low = running.toLowerCase();
        String m = mode.toLowerCase();
        return low.contains(m) || low.contains("switch " + m)
                || low.contains("at " + m) || low.contains(m + " mode");
    }

    private static Verdict finish(Context ctx, Verdict v) {
        String prev = Store.get(ctx, Store.K_VERDICT, "");
        Store.put(ctx, Store.K_VERDICT, v.state);
        Store.put(ctx, Store.K_PLAN, v.plan == null ? "" : v.plan);
        if (!v.state.equals(prev)) {
            BridgeLog.log(ctx, "RECONCILE", "verdict " + prev + " -> " + v.state
                    + (v.plan == null || v.plan.isEmpty() ? "" : " | plan: " + v.plan));
        }
        return v;
    }
}
