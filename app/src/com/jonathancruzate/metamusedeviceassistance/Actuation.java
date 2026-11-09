package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Phase 2 actuation (v1.3: night-only first rung; v1.8: all house
 * modes). The gate discipline is unchanged from v1.3 — v1.8 widens
 * WHICH modes may fire, never WHEN:
 *
 *  - Acts ONLY when the arm switch (Store.K_ACT_ARMED) is ON. The
 *    switch is Jonathan's in-app act; default OFF. Disarmed, this
 *    class only observes and records — exactly the pre-v1.8 shadow.
 *  - Acts ONLY on a fresh, parse-verified "drift" verdict, ONLY for
 *    a house mode whose routine Jonathan has LINKED in-app, and ONLY
 *    once per desired sequence per step — a fire that does not
 *    engage shows up as continued drift in the next report; it is
 *    never machine-gunned.
 *  - Multi-step plans are sequenced across cycles, one fire per
 *    cycle, each step verified against a fresh Device Status dump
 *    before the next fires: the Away latch is toggled OFF first
 *    (its own once-per-seq fire) and the target fires only once a
 *    fresh dump shows the latch gone. The target is never fired
 *    over a held latch, and a latch that survives its toggle on a
 *    fresh dump blocks the sequence loudly instead of being
 *    retried blind.
 *  - The drifted-latch clear (a mode reads active while its switch
 *    routine is not running) is two blind toggles of the same
 *    switch; it is NOT automated. The decision is recorded as
 *    blocked in the shadow evidence and logged — clearing it stays
 *    a human/mode-sync act, as in v1.3.
 *
 * Shadow evidence (v1.8): every cycle computes the decision the
 * loop WOULD take (or did take) and stores it — latest decision,
 * last real fire with its verified outcome, and a capped decision
 * history — all carried in the bridge report (see snapshot()).
 * That record is the parity proof against MacroDroid Mode Poll:
 * it is produced armed or not, so shadow diffing needs no arming.
 *
 * Verification is the loop itself: after a fire, the next fresh
 * dump recomputes ground truth; the fire's outcome resolves to
 * verified (in_sync, or the Away latch gone), not_engaged (a fresh
 * dump still shows the drift the fire was meant to end), or
 * superseded (a newer desired sequence arrived first).
 */
public final class Actuation {

    private static final long LOG_THROTTLE_MS = 10 * 60_000;
    private static final int HISTORY_CAP = 12;
    public static final String[] HOUSE_MODES = {"home", "away", "night", "morning"};

    private Actuation() {}

    /** One cycle's decision. action stays "pending" inside decide();
     *  cycle() finalizes it to would_fire / fired / blocked. */
    private static final class Decision {
        String action = "none"; // none|would_fire|fired|awaiting_verify|blocked|pending
        String step = "";       // fire_target|clear_away|clear_drifted_latch
        String target = "";     // house mode whose linked routine the step fires
        String reason = "";
    }

    /** BridgeService cycle entry point (replaces the v1.3 maybeAct). */
    public static void cycle(Context ctx, Reconciler.Verdict v) {
        if (DeviceProfile.isTv(ctx)) return;
        if (v == null) return;
        boolean armed = Store.getBool(ctx, Store.K_ACT_ARMED, false);
        String desired = Store.get(ctx, Store.K_DESIRED_MODE, "").toLowerCase();
        long seq = Store.getLong(ctx, Store.K_DESIRED_SEQ, -1);
        String gtMode = Store.get(ctx, Store.K_GT_MODE, "");
        String running = Store.get(ctx, Store.K_GT_RUNNING, "");
        long gtAt = Store.getLong(ctx, Store.K_GT_AT, 0);
        String effMode = Reconciler.resolveObservedMode(gtMode, running);
        boolean awayLatched = running.toLowerCase().contains("switch away");
        long now = System.currentTimeMillis();

        updateFireOutcome(ctx, v, seq, awayLatched, gtAt, now);

        Decision d = decide(ctx, v, desired, seq, effMode, running, awayLatched);
        if ("pending".equals(d.action)) {
            if (!armed) {
                d.action = "would_fire";
            } else if (RoutineFirer.fire(ctx, d.target)) {
                d.action = "fired";
                if ("clear_away".equals(d.step)) {
                    Store.put(ctx, Store.K_ACT_AWAY_CLEAR_SEQ, seq);
                    Store.put(ctx, Store.K_ACT_AWAY_CLEAR_AT, now);
                    Store.put(ctx, Store.K_ACT_AWAY_CLEAR_GT, gtAt);
                } else {
                    Store.put(ctx, Store.K_ACT_FIRED_SEQ, seq);
                    Store.put(ctx, Store.K_ACT_FIRED_AT, now);
                    Store.put(ctx, Store.K_ACT_FIRED_GT, gtAt);
                }
                writeLastFire(ctx, seq, desired, d, now, gtAt);
                BridgeLog.log(ctx, "ACT", "Phase 2 fire: step=" + d.step
                        + " desired=" + desired + " seq=" + seq
                        + " routine=" + RoutineFirer.linkedName(ctx, d.target)
                        + " — awaiting a fresh dump to verify");
            } else {
                // RoutineFirer already logged the failure. The seq
                // markers stay unset (v1.3 discipline), so a later
                // cycle may retry; the decision records the block.
                d.action = "blocked";
                d.reason = "linked routine fire failed for " + d.target + " (see ACT log)";
            }
        }
        if ("blocked".equals(d.action)) {
            throttledLog(ctx, "actuation blocked: step=" + d.step + " desired=" + desired
                    + " seq=" + seq + " — " + d.reason);
        }
        writeShadow(ctx, d, seq, desired, effMode, v.state, armed, now);
    }

    private static Decision decide(Context ctx, Reconciler.Verdict v, String desired,
                                   long seq, String effMode, String running,
                                   boolean awayLatched) {
        Decision d = new Decision();
        if (!"drift".equals(v.state)) {
            d.reason = "verdict " + v.state;
            return d;
        }
        if (!isHouseMode(desired)) {
            d.reason = "no house-mode desired (\"" + desired + "\")";
            return d;
        }
        if ("away".equals(desired)) {
            d.step = "fire_target";
            d.target = "away";
            if (!RoutineFirer.isLinked(ctx, "away")) {
                d.action = "blocked";
                d.reason = "desired=away but the Switch Away routine is not linked";
            } else if (Store.getLong(ctx, Store.K_ACT_FIRED_SEQ, -1) == seq) {
                d.action = "awaiting_verify";
                d.reason = "Switch Away fired for seq " + seq + "; awaiting a fresh dump";
            } else {
                d.action = "pending";
                d.reason = "Away latch not running; fire Switch Away once to latch it";
            }
            return d;
        }
        // Non-away target.
        String target = Reconciler.samsungModeFor(desired);
        // Drifted latch: a mode reads active but its switch routine is
        // not running. Clearing it is toggle ON then OFF — two blind
        // toggles — so it is recorded, never fired (v1.3 scope kept).
        if (target != null && !effMode.isEmpty() && !"none".equalsIgnoreCase(effMode)
                && !target.equalsIgnoreCase(effMode)
                && !Reconciler.runningNamesMode(running, effMode)) {
            d.step = "clear_drifted_latch";
            d.target = desired;
            d.action = "blocked";
            d.reason = effMode + " reads active but its switch routine is not running; "
                    + "the ON-then-OFF latch clear is not automated";
            return d;
        }
        if (awayLatched) {
            d.step = "clear_away";
            d.target = "away";
            if (!RoutineFirer.isLinked(ctx, "away")) {
                d.action = "blocked";
                d.reason = "the Away latch is running but the Switch Away routine is "
                        + "not linked — cannot clear it before firing " + desired;
            } else if (Store.getLong(ctx, Store.K_ACT_AWAY_CLEAR_SEQ, -1) != seq) {
                d.action = "pending";
                d.reason = "toggle Switch Away OFF once before firing " + desired;
            } else if (Store.getLong(ctx, Store.K_GT_AT, 0)
                    > Store.getLong(ctx, Store.K_ACT_AWAY_CLEAR_GT, 0)) {
                d.action = "blocked";
                d.reason = "the Away latch is still running on a fresh dump after its "
                        + "once-per-seq clear toggle; not firing " + desired
                        + " over a held latch";
            } else {
                d.action = "awaiting_verify";
                d.reason = "Away clear fired for seq " + seq + "; awaiting a fresh dump";
            }
            return d;
        }
        d.step = "fire_target";
        d.target = desired;
        if (!RoutineFirer.isLinked(ctx, desired)) {
            d.action = "blocked";
            d.reason = "desired=" + desired + " but its routine is not linked";
        } else if (Store.getLong(ctx, Store.K_ACT_FIRED_SEQ, -1) == seq) {
            d.action = "awaiting_verify";
            d.reason = "fired for seq " + seq + "; awaiting a fresh dump";
        } else {
            d.action = "pending";
            d.reason = "drift from " + effMode + " to " + desired
                    + "; fire the linked " + desired + " routine once";
        }
        return d;
    }

    /** Resolve a pending fire's outcome from fresh ground truth. */
    private static void updateFireOutcome(Context ctx, Reconciler.Verdict v, long seq,
                                          boolean awayLatched, long gtAt, long now) {
        String raw = Store.get(ctx, Store.K_ACT_LAST_FIRE, "");
        if (raw.isEmpty()) return;
        try {
            JSONObject f = new JSONObject(raw);
            if (!"pending".equals(f.optString("outcome", ""))) return;
            long fireSeq = f.optLong("seq", -2);
            long fireGt = f.optLong("gt_at", 0);
            String step = f.optString("step", "");
            String outcome = null;
            if (fireSeq != seq) {
                outcome = "superseded";
            } else if ("clear_away".equals(step)) {
                if (gtAt > fireGt && !awayLatched) outcome = "verified";
                else if (gtAt > fireGt && awayLatched) outcome = "not_engaged";
            } else if ("in_sync".equals(v.state)) {
                outcome = "verified";
            } else if ("drift".equals(v.state) && gtAt > fireGt) {
                outcome = "not_engaged";
            }
            if (outcome != null) {
                f.put("outcome", outcome);
                f.put("outcome_at", now);
                Store.put(ctx, Store.K_ACT_LAST_FIRE, f.toString());
                BridgeLog.log(ctx, "ACT", "fire outcome: step=" + step + " seq=" + fireSeq
                        + " -> " + outcome);
            }
        } catch (Exception ignored) {
            // A corrupt record must never take the cycle down; the
            // next fire rewrites it wholesale.
        }
    }

    private static void writeLastFire(Context ctx, long seq, String desired, Decision d,
                                      long now, long gtAt) {
        try {
            JSONObject f = new JSONObject();
            f.put("seq", seq);
            f.put("desired", desired);
            f.put("step", d.step);
            f.put("target", d.target);
            f.put("routine", RoutineFirer.linkedName(ctx, d.target));
            f.put("at", now);
            f.put("gt_at", gtAt);
            f.put("outcome", "pending");
            f.put("outcome_at", 0);
            Store.put(ctx, Store.K_ACT_LAST_FIRE, f.toString());
        } catch (Exception ignored) {}
    }

    private static void writeShadow(Context ctx, Decision d, long seq, String desired,
                                    String effMode, String verdict, boolean armed, long now) {
        try {
            JSONObject s = new JSONObject();
            s.put("at", now);
            s.put("seq", seq);
            s.put("desired", desired);
            s.put("observed", effMode);
            s.put("verdict", verdict);
            s.put("armed", armed);
            s.put("action", d.action);
            s.put("step", d.step);
            s.put("target", d.target);
            s.put("routine", d.target.isEmpty() ? ""
                    : RoutineFirer.linkedName(ctx, d.target));
            s.put("reason", d.reason);
            Store.put(ctx, Store.K_ACT_SHADOW, s.toString());

            // History: append only when the decision's identity
            // changes, so the record reads as one entry per mode
            // change / step, not one per 60-s cycle.
            String key = seq + "|" + d.step + "|" + d.action + "|" + d.target;
            JSONArray hist = parseArray(Store.get(ctx, Store.K_ACT_HISTORY, ""));
            JSONObject last = hist.length() > 0 ? hist.optJSONObject(hist.length() - 1) : null;
            String lastKey = last == null ? "" : last.optLong("seq", -1) + "|"
                    + last.optString("step", "") + "|" + last.optString("action", "")
                    + "|" + last.optString("target", "");
            if (!key.equals(lastKey)) {
                JSONObject h = new JSONObject();
                h.put("at", now);
                h.put("seq", seq);
                h.put("desired", desired);
                h.put("action", d.action);
                h.put("step", d.step);
                h.put("target", d.target);
                hist.put(h);
                while (hist.length() > HISTORY_CAP) hist.remove(0);
                Store.put(ctx, Store.K_ACT_HISTORY, hist.toString());
            }
        } catch (Exception ignored) {}
    }

    /** The report's actuation object (WorkerClient). Phone only —
     *  the caller gates TV, whose reconciler never runs. */
    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("armed", Store.getBool(ctx, Store.K_ACT_ARMED, false));
            JSONObject linked = new JSONObject();
            for (String m : HOUSE_MODES) linked.put(m, RoutineFirer.linkedName(ctx, m));
            o.put("linked", linked);
            o.put("shadow", parseObject(Store.get(ctx, Store.K_ACT_SHADOW, "")));
            o.put("last_fire", parseObject(Store.get(ctx, Store.K_ACT_LAST_FIRE, "")));
            o.put("history", parseArray(Store.get(ctx, Store.K_ACT_HISTORY, "")));
        } catch (Exception ignored) {}
        return o;
    }

    private static JSONObject parseObject(String raw) {
        if (raw == null || raw.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static JSONArray parseArray(String raw) {
        if (raw == null || raw.isEmpty()) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static boolean isHouseMode(String mode) {
        for (String m : HOUSE_MODES) if (m.equals(mode)) return true;
        return false;
    }

    private static void throttledLog(Context ctx, String msg) {
        long last = Store.getLong(ctx, Store.K_ACT_LAST_LOG, 0);
        long now = System.currentTimeMillis();
        if (now - last > LOG_THROTTLE_MS) {
            Store.put(ctx, Store.K_ACT_LAST_LOG, now);
            BridgeLog.log(ctx, "ACT", msg);
        }
    }
}
