package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.health.connect.HealthConnectManager;
import android.health.connect.changelog.ChangeLogTokenRequest;
import android.health.connect.changelog.ChangeLogTokenResponse;
import android.health.connect.changelog.ChangeLogsRequest;
import android.health.connect.changelog.ChangeLogsResponse;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.HeartRateRecord;
import android.health.connect.datatypes.InstantRecord;
import android.health.connect.datatypes.IntervalRecord;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.SleepSessionRecord;
import android.health.connect.datatypes.StepsRecord;
import android.os.OutcomeReceiver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Health Connect push channel (v1.7) — framework Health Connect API
 * (android.health.connect, in the android-35 platform jar this
 * project already compiles against; NO new dependency).
 *
 * Mechanism: per the design's changes-token plan, the check keeps
 * ONE change-log token covering Sleep / Steps / HeartRate / Exercise.
 * It runs in the BridgeService cycle, throttled to at most every
 * 15 minutes (Store.K_PUSH_HEALTH_LAST_CHECK):
 *  - No stored token → request one, store it, done (cursor jumps to
 *    now; no historical dump).
 *  - Token stored → getChangeLogs(token); every upserted record is
 *    converted to a "phone-health" item and pushed; the next token
 *    is stored ONLY when the push returns 2xx (or when there were
 *    no records to push). PushClient queues on failure, so a
 *    re-read duplicate is possible and dedupes downstream by the
 *    record's metadata id, which every item carries.
 * Permissions: HC grants are per-type, managed in the Health Connect
 * app. If no health permission is granted, or the manager is
 * unavailable on this build, the channel's last_error is set (loud
 * in the bridge report) — never a crash, never silent.
 * Toggle OFF / TV: check() returns immediately, nothing is read.
 */
public final class HealthPush {
    static final long THROTTLE_MS = 15 * 60_000;

    private HealthPush() {}

    private static final String[] HEALTH_PERMS = {
            "android.permission.health.READ_SLEEP",
            "android.permission.health.READ_STEPS",
            "android.permission.health.READ_HEART_RATE",
            "android.permission.health.READ_EXERCISE",
    };

    public static void check(Context ctx) {
        PushChannels.Channel ch = PushChannels.HEALTH;
        if (!PushChannels.isOn(ctx, ch)) return;
        long last = Store.getLong(ctx, Store.K_PUSH_HEALTH_LAST_CHECK, 0);
        if (System.currentTimeMillis() - last < THROTTLE_MS) return;
        Store.put(ctx, Store.K_PUSH_HEALTH_LAST_CHECK, System.currentTimeMillis());
        try {
            HealthConnectManager mgr = (HealthConnectManager)
                    ctx.getSystemService(Context.HEALTHCONNECT_SERVICE);
            if (mgr == null) {
                fail(ctx, ch, "HealthConnectManager unavailable on this build");
                return;
            }
            boolean anyGranted = false;
            for (String p : HEALTH_PERMS) {
                if (ctx.checkSelfPermission(p) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    anyGranted = true; break;
                }
            }
            if (!anyGranted) {
                fail(ctx, ch, "no Health Connect permissions granted (grant per-type access in the Health Connect app)");
                return;
            }
            String token = Store.get(ctx, Store.K_PUSH_HEALTH_TOKEN, "");
            if (token == null || token.isEmpty()) {
                String fresh = requestToken(mgr);
                if (fresh == null) { fail(ctx, ch, "change-log token request failed"); return; }
                Store.put(ctx, Store.K_PUSH_HEALTH_TOKEN, fresh);
                BridgeLog.log(ctx, "PUSH", "phone-health token seeded (no historical dump)");
                PushChannels.recordError(ctx, ch, "");
                return;
            }
            ChangeLogsResponse resp = fetchChanges(mgr, token);
            if (resp == null) { fail(ctx, ch, "change-logs fetch failed (token may have expired)"); return; }
            List<Record> records = resp.getUpsertedRecords();
            String next = resp.getNextChangesToken();
            if (records == null || records.isEmpty()) {
                if (next != null && !next.isEmpty()) Store.put(ctx, Store.K_PUSH_HEALTH_TOKEN, next);
                return;
            }
            JSONArray items = new JSONArray();
            for (Record r : records) {
                JSONObject o = convert(r);
                if (o != null) items.put(o);
                if (items.length() >= 50) break;
            }
            if (items.length() == 0) {
                if (next != null && !next.isEmpty()) Store.put(ctx, Store.K_PUSH_HEALTH_TOKEN, next);
                return;
            }
            if (PushClient.push(ctx, ch.source, items)) {
                if (next != null && !next.isEmpty()) Store.put(ctx, Store.K_PUSH_HEALTH_TOKEN, next);
            }
        } catch (Exception e) {
            fail(ctx, ch, "health check failed: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private static void fail(Context ctx, PushChannels.Channel ch, String err) {
        PushChannels.recordError(ctx, ch, err);
        BridgeLog.log(ctx, "PUSH", "phone-health error: " + err);
    }

    private static String requestToken(HealthConnectManager mgr) throws Exception {
        ChangeLogTokenRequest req = new ChangeLogTokenRequest.Builder()
                .addRecordType(SleepSessionRecord.class)
                .addRecordType(StepsRecord.class)
                .addRecordType(HeartRateRecord.class)
                .addRecordType(ExerciseSessionRecord.class)
                .build();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> out = new AtomicReference<>();
        mgr.getChangeLogToken(req, Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<ChangeLogTokenResponse, android.health.connect.HealthConnectException>() {
                    @Override public void onResult(ChangeLogTokenResponse r) {
                        out.set(r.getToken()); latch.countDown();
                    }
                    @Override public void onError(android.health.connect.HealthConnectException e) {
                        latch.countDown();
                    }
                });
        if (!latch.await(20, TimeUnit.SECONDS)) return null;
        return out.get();
    }

    private static ChangeLogsResponse fetchChanges(HealthConnectManager mgr, String token) throws Exception {
        ChangeLogsRequest req = new ChangeLogsRequest.Builder(token)
                .setPageSize(100).build();
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ChangeLogsResponse> out = new AtomicReference<>();
        mgr.getChangeLogs(req, Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<ChangeLogsResponse, android.health.connect.HealthConnectException>() {
                    @Override public void onResult(ChangeLogsResponse r) {
                        out.set(r); latch.countDown();
                    }
                    @Override public void onError(android.health.connect.HealthConnectException e) {
                        latch.countDown();
                    }
                });
        if (!latch.await(20, TimeUnit.SECONDS)) return null;
        return out.get();
    }

    private static JSONObject convert(Record r) {
        try {
            JSONObject o = new JSONObject();
            o.put("record_id", r.getMetadata() != null ? r.getMetadata().getId() : "");
            if (r instanceof IntervalRecord) {
                IntervalRecord ir = (IntervalRecord) r;
                o.put("start_time", ir.getStartTime() != null ? ir.getStartTime().toEpochMilli() : 0);
                o.put("end_time", ir.getEndTime() != null ? ir.getEndTime().toEpochMilli() : 0);
            } else if (r instanceof InstantRecord) {
                o.put("start_time", ((InstantRecord) r).getTime() != null
                        ? ((InstantRecord) r).getTime().toEpochMilli() : 0);
            }
            if (r instanceof SleepSessionRecord) {
                SleepSessionRecord s = (SleepSessionRecord) r;
                o.put("type", "sleep");
                o.put("title", s.getTitle() != null ? s.getTitle().toString() : "");
                o.put("stages", s.getStages() != null ? s.getStages().size() : 0);
            } else if (r instanceof StepsRecord) {
                o.put("type", "steps");
                o.put("count", ((StepsRecord) r).getCount());
            } else if (r instanceof HeartRateRecord) {
                HeartRateRecord h = (HeartRateRecord) r;
                o.put("type", "heart_rate");
                o.put("samples", h.getSamples() != null ? h.getSamples().size() : 0);
                if (h.getSamples() != null && !h.getSamples().isEmpty()) {
                    o.put("bpm_first", h.getSamples().get(0).getBeatsPerMinute());
                }
            } else if (r instanceof ExerciseSessionRecord) {
                ExerciseSessionRecord e = (ExerciseSessionRecord) r;
                o.put("type", "exercise");
                o.put("exercise_type", e.getExerciseType());
                o.put("title", e.getTitle() != null ? e.getTitle().toString() : "");
            } else {
                return null; // token covers only the four types; ignore anything else
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }
}
