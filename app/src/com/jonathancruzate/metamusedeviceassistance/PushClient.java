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
 * PushClient (v1.6; transport reworked v1.11) — the on-device push
 * transport for the Device Assistance data channels.
 *
 * v1.11 delivery hierarchy (Jonathan's rulings, 2026-11-10 00:35–00:55
 * EST — design: docs/direct-to-muse-app-design-2026-11-10.md as
 * corrected the same night):
 *
 *  Tier 1 — APP-TO-APP LOCAL (the delivery). Every payload is handed
 *  off on-device into the local outbox (Store.K_PUSH_QUEUE, an in-prefs
 *  JSON array of {source, ts, items, mirrored} that survives process
 *  death, capped at 200 entries, oldest dropped). The Muse app
 *  exposes NO local ingest API (no intent receiver, ContentProvider,
 *  or file drop in the platform docs; Android sandboxing bars writing
 *  into its storage), so this on-device store is the closest real
 *  endpoint the path can terminate at today: the data is ON the phone,
 *  in this app's store, inspectable via the BridgeLog and the bridge
 *  report — no Cloudflare Worker, no relay, no mailbox of ours is in
 *  the path. The channel's cursor / last_push_at advance on this local
 *  delivery (verify-then-mark against the tier-1 endpoint). The
 *  residual gap is stated plainly: nothing on the phone currently
 *  reads this outbox into the Muse app, because no sanctioned
 *  hand-off surface exists to build that last hop on.
 *
 *  Tier 2 — TAILNET BRIDGE (worst case, best-effort mirror). When —
 *  and only when — the configured base is an https *.ts.net base (the
 *  Tailscale Serve form, tailnet-only, TLS so the targetSdk-35
 *  cleartext ban does not apply), the payload is ALSO POSTed to
 *  {base}/device-push as before. A Worker base, an http base, or any
 *  other base is never POSTed to from this client: the data channels
 *  do not speak to Cloudflare at all as of v1.11. Mirror failures are
 *  logged via BridgeLog; they do NOT mark the channel in error and do
 *  NOT gate its cursor — the delivery already happened at tier 1.
 *  flushQueue() (BridgeService cycle) re-attempts the mirror for
 *  outbox entries not yet mirrored whenever a tailnet bridge base is
 *  configured; entries stay in the outbox either way (it is the tier-1
 *  endpoint's store, not a retry queue anymore).
 *
 * The control lanes (state report, command channel, mode loop) do NOT
 * ride this client — they stay on WorkerClient and its rendezvous,
 * per the design's control-lane treatment.
 *
 * Discipline (unchanged from v1.6):
 *  - NEVER call push() on the UI thread; use pushAsync() from UI /
 *    listener callbacks. push() itself may block (tier-2 mirror HTTP)
 *    and is for background threads.
 *  - TV profile: no pushes, ever (phone surfaces gated off).
 *  - The secret is read from Store at call time; never logged.
 */
public final class PushClient {
    private static final int QUEUE_CAP = 200;

    private PushClient() {}

    /** Blocking push. Returns true when the tier-1 local hand-off landed. */
    public static boolean push(Context ctx, String source, JSONArray items) {
        return pushPayload(ctx, source, System.currentTimeMillis(), items);
    }

    /** Fire-and-forget push on a fresh worker thread. */
    public static void pushAsync(final Context ctx, final String source, final JSONArray items) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> push(app, source, items)).start();
    }

    /** The tier-2 bridge form: https on a tailnet (*.ts.net) name only. */
    private static boolean isTailnetBridgeBase(String base) {
        if (base == null) return false;
        String b = base.trim().toLowerCase();
        if (!b.startsWith("https://")) return false;
        String host = b.substring("https://".length());
        int slash = host.indexOf('/');
        if (slash >= 0) host = host.substring(0, slash);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        return host.endsWith(".ts.net");
    }

    private static boolean pushPayload(Context ctx, String source, long ts, JSONArray items) {
        PushChannels.Channel ch = PushChannels.bySource(source);
        if (DeviceProfile.isTv(ctx)) return false;
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        boolean bridge = isTailnetBridgeBase(base);
        boolean mirrored = false;
        if (bridge) {
            mirrored = postToBridge(ctx, base, source, ts, items);
        }
        // Tier 1: the local hand-off. This append IS the delivery.
        if (!enqueue(ctx, source, ts, items, mirrored)) {
            String err = "local outbox append failed";
            if (ch != null) PushChannels.recordError(ctx, ch, err);
            BridgeLog.log(ctx, "PUSH", source + " push FAILED: " + err);
            return false;
        }
        if (ch != null) PushChannels.recordSuccess(ctx, ch, System.currentTimeMillis());
        BridgeLog.log(ctx, "PUSH", source + " push local ok (" + itemsCount(items)
                + " items)" + (bridge
                    ? (mirrored ? "; tailnet bridge mirrored" : "; tailnet bridge mirror FAILED (logged, delivery stands)")
                    : "; no tailnet bridge base — local only"));
        return true;
    }

    /** Tier-2 mirror POST (the pre-v1.11 wire shape). True only on HTTP 2xx. */
    private static boolean postToBridge(Context ctx, String base, String source,
                                        long ts, JSONArray items) {
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
            c.setRequestProperty("User-Agent", "MuseDeviceAssistance/1.11");
            c.setDoOutput(true);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
            }
            int code = c.getResponseCode();
            if (code >= 200 && code < 300) return true;
            BridgeLog.log(ctx, "PUSH", source + " bridge mirror FAILED -> HTTP " + code);
            return false;
        } catch (Exception e) {
            BridgeLog.log(ctx, "PUSH", source + " bridge mirror FAILED: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    private static int itemsCount(JSONArray items) {
        return items == null ? 0 : items.length();
    }

    // ---- local outbox (the tier-1 endpoint's store) ---------------------

    private static synchronized boolean enqueue(Context ctx, String source, long ts,
                                                JSONArray items, boolean mirrored) {
        try {
            JSONArray q = queue(ctx);
            JSONObject entry = new JSONObject();
            entry.put("source", source);
            entry.put("ts", ts);
            entry.put("items", items != null ? items : new JSONArray());
            entry.put("mirrored", mirrored);
            q.put(entry);
            while (q.length() > QUEUE_CAP) q.remove(0);
            Store.put(ctx, Store.K_PUSH_QUEUE, q.toString());
            return true;
        } catch (Exception e) {
            BridgeLog.log(ctx, "PUSH", "local outbox append failed: " + e.getMessage());
            return false;
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
     * Tier-2 mirror pass over the local outbox. Called from the
     * BridgeService cycle (background thread). For every entry not yet
     * mirrored, if a tailnet bridge base is now configured, attempt the
     * mirror POST and flip the entry's flag on success. Entries are
     * NEVER dropped here — the outbox is the tier-1 endpoint's store.
     * Entries for channels Jonathan has since turned OFF are skipped
     * (never mirrored behind his toggle).
     */
    public static void flushQueue(Context ctx) {
        if (DeviceProfile.isTv(ctx)) return;
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        if (!isTailnetBridgeBase(base)) return;
        JSONArray q = queue(ctx);
        if (q.length() == 0) return;
        int mirroredNow = 0;
        boolean changed = false;
        for (int i = 0; i < q.length(); i++) {
            JSONObject entry = q.optJSONObject(i);
            if (entry == null) continue;
            if (entry.optBoolean("mirrored", false)) continue;
            String source = entry.optString("source", "");
            PushChannels.Channel ch = PushChannels.bySource(source);
            if (ch != null && !PushChannels.isOn(ctx, ch)) continue;
            long ts = entry.optLong("ts", System.currentTimeMillis());
            JSONArray items = entry.optJSONArray("items");
            if (items == null) items = new JSONArray();
            if (postToBridge(ctx, base, source, ts, items)) {
                try { entry.put("mirrored", true); } catch (Exception ignored) {}
                mirroredNow++;
                changed = true;
            }
        }
        if (changed) Store.put(ctx, Store.K_PUSH_QUEUE, q.toString());
        if (mirroredNow > 0) {
            BridgeLog.log(ctx, "PUSH", "tailnet bridge mirror pass: " + mirroredNow + " entries mirrored");
        }
    }
}
