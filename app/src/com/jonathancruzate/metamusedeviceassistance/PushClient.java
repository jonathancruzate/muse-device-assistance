package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * PushClient (v1.6) — the on-device push transport for the local push
 * replacement (design §3). POSTs one channel's items to Muse's own
 * Worker using the SAME base URL + secret as WorkerClient:
 *
 *   POST {base}/device-push?secret=…
 *        <- {"source":"phone-notify"|"phone-battery"|…,
 *            "ts": <epoch ms>, "items":[ … ]}
 *
 * Discipline:
 *  - NEVER call push() on the UI thread; use pushAsync() from UI /
 *    listener callbacks (it spawns a worker thread). push() itself
 *    does blocking HTTP and is for background threads (BridgeService
 *    cycle, listener-spawned threads).
 *  - The channel's cursor / last_push_at advance ONLY on HTTP 2xx
 *    (verify-then-mark, the phone-notify-drain pattern). On success
 *    the channel's last_error is cleared.
 *  - RETRY CHOICE for v1.6 (documented per the task): an in-prefs
 *    queue. A failed payload is appended verbatim to Store.K_PUSH_QUEUE
 *    (a JSON array string in prefs, capped at 200 entries, oldest
 *    dropped) and retried by flushQueue() on the next BridgeService
 *    cycle — no separate scheduler, no data loss across process death
 *    because prefs survive. Failures are logged via BridgeLog ("PUSH")
 *    and recorded in the channel's last_error, which rides the bridge
 *    report's push_channels object, so a failing channel is loud
 *    server-side, never silent.
 *  - TV profile: no pushes, ever (phone surfaces gated off).
 *  - The secret is read from Store at call time; never logged.
 */
public final class PushClient {
    private static final int QUEUE_CAP = 200;

    private PushClient() {}

    /** Blocking push. Returns true only on HTTP 2xx. */
    public static boolean push(Context ctx, String source, JSONArray items) {
        return pushPayload(ctx, source, System.currentTimeMillis(), items, true);
    }

    /** Fire-and-forget push on a fresh worker thread. */
    public static void pushAsync(final Context ctx, final String source, final JSONArray items) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> push(app, source, items)).start();
    }

    private static boolean pushPayload(Context ctx, String source, long ts,
                                       JSONArray items, boolean queueOnFailure) {
        PushChannels.Channel ch = PushChannels.bySource(source);
        if (DeviceProfile.isTv(ctx)) return false;
        if (!Store.workerConfigured(ctx)) {
            String err = "worker not configured";
            if (ch != null) PushChannels.recordError(ctx, ch, err);
            BridgeLog.log(ctx, "PUSH", source + " push skipped: " + err);
            if (queueOnFailure) enqueue(ctx, source, ts, items);
            return false;
        }
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        String secret = Store.get(ctx, Store.K_SECRET, "").trim();
        try {
            JSONObject body = new JSONObject();
            body.put("source", source);
            body.put("ts", ts);
            body.put("items", items != null ? items : new JSONArray());
            URL url = new URL(base + "/device-push?secret="
                    + URLEncoder.encode(secret, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "MuseDeviceAssistance/1.6");
            c.setDoOutput(true);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) {
                if (ch != null) PushChannels.recordSuccess(ctx, ch, System.currentTimeMillis());
                BridgeLog.log(ctx, "PUSH", source + " push ok (" + itemsCount(items)
                        + " items) -> HTTP " + code);
                return true;
            }
            String err = "HTTP " + code;
            if (ch != null) PushChannels.recordError(ctx, ch, err);
            BridgeLog.log(ctx, "PUSH", source + " push FAILED -> " + err + " (queued for retry)");
            if (queueOnFailure) enqueue(ctx, source, ts, items);
            return false;
        } catch (Exception e) {
            String err = e.getClass().getSimpleName() + " " + e.getMessage();
            if (ch != null) PushChannels.recordError(ctx, ch, err);
            BridgeLog.log(ctx, "PUSH", source + " push FAILED: " + err + " (queued for retry)");
            if (queueOnFailure) enqueue(ctx, source, ts, items);
            return false;
        }
    }

    private static int itemsCount(JSONArray items) {
        return items == null ? 0 : items.length();
    }

    // ---- in-prefs retry queue -------------------------------------------

    private static synchronized void enqueue(Context ctx, String source, long ts, JSONArray items) {
        try {
            JSONArray q = queue(ctx);
            JSONObject entry = new JSONObject();
            entry.put("source", source);
            entry.put("ts", ts);
            entry.put("items", items != null ? items : new JSONArray());
            q.put(entry);
            while (q.length() > QUEUE_CAP) q.remove(0);
            Store.put(ctx, Store.K_PUSH_QUEUE, q.toString());
        } catch (Exception e) {
            BridgeLog.log(ctx, "PUSH", "queue append failed: " + e.getMessage());
        }
    }

    private static JSONArray queue(Context ctx) {
        String raw = Store.get(ctx, Store.K_PUSH_QUEUE, "");
        if (raw == null || raw.trim().isEmpty()) return new JSONArray();
        try {
            return new JSONArray(raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public static synchronized int queueSize(Context ctx) {
        return queue(ctx).length();
    }

    /**
     * Retry every queued payload once. Called from the BridgeService
     * cycle (background thread). Entries that succeed are dropped;
     * entries that fail stay queued (in order) for the next cycle.
     * A retried entry that succeeds updates its channel's bookkeeping
     * via the normal push path (queueOnFailure=false so a failure
     * does not duplicate the entry).
     */
    public static void flushQueue(Context ctx) {
        if (DeviceProfile.isTv(ctx)) return;
        JSONArray q = queue(ctx);
        if (q.length() == 0) return;
        JSONArray remaining = new JSONArray();
        int sent = 0;
        for (int i = 0; i < q.length(); i++) {
            JSONObject entry = q.optJSONObject(i);
            if (entry == null) continue;
            String source = entry.optString("source", "");
            long ts = entry.optLong("ts", System.currentTimeMillis());
            JSONArray items = entry.optJSONArray("items");
            if (items == null) items = new JSONArray();
            // Only retry channels Jonathan still has ON — a payload for
            // a channel he turned OFF is dropped, never pushed behind
            // his toggle.
            PushChannels.Channel ch = PushChannels.bySource(source);
            if (ch != null && !PushChannels.isOn(ctx, ch)) continue;
            if (pushPayload(ctx, source, ts, items, false)) {
                sent++;
            } else {
                remaining.put(entry);
            }
        }
        Store.put(ctx, Store.K_PUSH_QUEUE, remaining.toString());
        if (sent > 0) {
            BridgeLog.log(ctx, "PUSH", "retry queue flushed: " + sent + " sent, "
                    + remaining.length() + " remaining");
        }
    }
}
