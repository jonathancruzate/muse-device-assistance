package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;

/**
 * Phase 2 actuation gate (v1.3). Deliberately narrow — this is the
 * cutover's first rung, not the general actuator:
 *
 *  - Acts ONLY when the arm switch (Store.K_ACT_ARMED) is ON. Default OFF.
 *  - Acts ONLY for desired=night (the Phase 2 first mode), ONLY on a
 *    fresh, parse-verified "drift" verdict, and ONLY once per desired
 *    sequence number — a fire that does not engage shows up as continued
 *    drift in the next report; it is never machine-gunned.
 *  - If the Away latch is also running, the latch-clear sequence is out
 *    of v1.3 scope: it logs (throttled) and does not fire.
 *  - If the Sleep routine is not linked, it logs (throttled) and does
 *    not fire.
 *
 * Verification is the loop itself: the next Device Status dump updates
 * ground truth, the reconciler recomputes, and the report shows whether
 * the fire engaged — the verified-ack discipline MacroDroid never had.
 */
public final class Actuation {

    private static final long LOG_THROTTLE_MS = 10 * 60_000;

    private Actuation() {}

    public static void maybeAct(Context ctx, Reconciler.Verdict v) {
        if (DeviceProfile.isTv(ctx)) return;
        if (!Store.getBool(ctx, Store.K_ACT_ARMED, false)) return;
        if (v == null || !"drift".equals(v.state)) return;
        String desired = Store.get(ctx, Store.K_DESIRED_MODE, "");
        if (!"night".equalsIgnoreCase(desired)) return;
        long seq = Store.getLong(ctx, Store.K_DESIRED_SEQ, -1);
        if (seq == Store.getLong(ctx, Store.K_ACT_FIRED_SEQ, -1)) return;

        String running = Store.get(ctx, Store.K_GT_RUNNING, "");
        if (running.toLowerCase().contains("switch away")) {
            throttledLog(ctx, "armed, night drift, but the Away latch is also "
                    + "running — latch-clear sequence is out of v1.3 scope; not firing");
            return;
        }
        if (!RoutineFirer.isLinked(ctx, "night")) {
            throttledLog(ctx, "armed, night drift, but no Switch Sleep routine "
                    + "is linked yet — cannot act");
            return;
        }
        BridgeLog.log(ctx, "ACT", "Phase 2 fire for desired=night seq " + seq
                + " (verdict drift) — awaiting a fresh dump to verify");
        if (RoutineFirer.fire(ctx, "night")) {
            Store.put(ctx, Store.K_ACT_FIRED_SEQ, seq);
        }
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
