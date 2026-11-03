package com.jonathancruzate.metamusephonebridge;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * The command channel to Muse's own Cloudflare Worker — no third-party
 * relay, no MacroDroid cloud, no FCM (investigation report §4.3).
 *
 * Protocol (documented in the project README):
 *   GET  {base}/mode-state?secret={secret}
 *        -> {"desired":{"mode":"home","seq":139,...}, ...}   (existing
 *           mode-sync Worker shape; only "desired" is consumed)
 *   POST {base}/phone-bridge/report?secret={secret}
 *        <- {"device":"phone-bridge","kind":"report","observed":{...},
 *            "status":{...},"verdict":"in_sync|drift|unknown",
 *            "ack": true only when observed == desired}
 *
 * The secret is entered on-device in the setup screen and stored only in
 * this app's private preferences. It is NEVER hardcoded in this source
 * and never written to the log.
 */
public final class WorkerClient {
    private WorkerClient() {}

    /** Fetch desired state; stores it in Store. Returns true on success. */
    public static boolean fetchDesired(Context ctx) {
        if (!Store.workerConfigured(ctx)) return false;
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        String secret = Store.get(ctx, Store.K_SECRET, "").trim();
        try {
            URL url = new URL(base + "/mode-state?secret="
                    + URLEncoder.encode(secret, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "MusePhoneBridge/1.0");
            int code = c.getResponseCode();
            if (code != 200) {
                BridgeLog.log(ctx, "WORKER", "mode-state GET -> HTTP " + code);
                return false;
            }
            String body = readAll(c.getInputStream());
            JSONObject root = new JSONObject(body);
            JSONObject desired = root.optJSONObject("desired");
            if (desired == null) {
                BridgeLog.log(ctx, "WORKER", "mode-state response had no desired object");
                return false;
            }
            String mode = desired.optString("mode", "");
            long seq = desired.optLong("seq", -1);
            String prevMode = Store.get(ctx, Store.K_DESIRED_MODE, "");
            long prevSeq = Store.getLong(ctx, Store.K_DESIRED_SEQ, -1);
            Store.put(ctx, Store.K_DESIRED_MODE, mode);
            Store.put(ctx, Store.K_DESIRED_SEQ, seq);
            Store.put(ctx, Store.K_DESIRED_AT, System.currentTimeMillis());
            if (!mode.equals(prevMode) || seq != prevSeq) {
                BridgeLog.log(ctx, "WORKER", "desired updated: mode=" + mode + " seq=" + seq);
            }
            return true;
        } catch (Exception e) {
            BridgeLog.log(ctx, "WORKER", "mode-state GET failed: " + e.getClass().getSimpleName()
                    + " " + e.getMessage());
            return false;
        }
    }

    /**
     * Post a state report. The ack field is the verified-ack contract:
     * true ONLY when the reconciler's verdict is in_sync, i.e. observed
     * ground truth matches desired. Drift and unknown are reported as
     * such — this is the structural fix for MacroDroid's unconditional
     * acks.
     */
    public static boolean postReport(Context ctx, String kind) {
        if (!Store.workerConfigured(ctx)) return false;
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        String secret = Store.get(ctx, Store.K_SECRET, "").trim();
        try {
            JSONObject body = new JSONObject();
            body.put("device", "phone-bridge");
            body.put("kind", kind);
            body.put("ts", System.currentTimeMillis());

            JSONObject observed = new JSONObject();
            observed.put("mode", Store.get(ctx, Store.K_GT_MODE, ""));
            observed.put("running", Store.get(ctx, Store.K_GT_RUNNING, ""));
            observed.put("at", Store.getLong(ctx, Store.K_GT_AT, 0));
            observed.put("parse_ok", Store.getBool(ctx, Store.K_GT_PARSE_OK, false));
            body.put("observed", observed);

            JSONObject desired = new JSONObject();
            desired.put("mode", Store.get(ctx, Store.K_DESIRED_MODE, ""));
            desired.put("seq", Store.getLong(ctx, Store.K_DESIRED_SEQ, -1));
            body.put("desired", desired);

            String verdict = Store.get(ctx, Store.K_VERDICT, "unknown");
            body.put("verdict", verdict);
            body.put("ack", "in_sync".equals(verdict));
            body.put("plan", Store.get(ctx, Store.K_PLAN, ""));
            body.put("status", StatusProducer.produce(ctx));

            URL url = new URL(base + "/phone-bridge/report?secret="
                    + URLEncoder.encode(secret, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "MusePhoneBridge/1.0");
            c.setDoOutput(true);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
            }
            int code = c.getResponseCode();
            Store.put(ctx, Store.K_LAST_REPORT_AT, System.currentTimeMillis());
            Store.put(ctx, Store.K_LAST_REPORT_CODE, code);
            BridgeLog.log(ctx, "WORKER", "report (" + kind + ", verdict=" + verdict
                    + ") -> HTTP " + code);
            return code >= 200 && code < 300;
        } catch (Exception e) {
            BridgeLog.log(ctx, "WORKER", "report POST failed: " + e.getClass().getSimpleName()
                    + " " + e.getMessage());
            return false;
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
