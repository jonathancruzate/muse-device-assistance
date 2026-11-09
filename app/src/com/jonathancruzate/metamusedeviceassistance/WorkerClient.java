package com.jonathancruzate.metamusedeviceassistance;

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
        // TV profile: house-mode desired state is a phone surface.
        // A Shield instance must never consume or echo it.
        if (DeviceProfile.isTv(ctx)) return false;
        if (!Store.workerConfigured(ctx)) return false;
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        String secret = Store.get(ctx, Store.K_SECRET, "").trim();
        try {
            URL url = new URL(base + "/mode-state?secret="
                    + URLEncoder.encode(secret, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "MusePhoneBridge/1.1");
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
            boolean tv = DeviceProfile.isTv(ctx);
            JSONObject body = new JSONObject();
            body.put("device", DeviceProfile.deviceString(ctx));
            body.put("profile", DeviceProfile.profileName(ctx));
            body.put("kind", kind);
            body.put("ts", System.currentTimeMillis());

            JSONObject observed = new JSONObject();
            if (tv) {
                // TV profile: Samsung mode ground truth is gated off.
                observed.put("mode", "");
                observed.put("raw_mode", "");
                observed.put("running", "");
                observed.put("at", 0);
                observed.put("parse_ok", false);
            } else {
                observed.put("mode", Reconciler.resolveObservedMode(
                        Store.get(ctx, Store.K_GT_MODE, ""), Store.get(ctx, Store.K_GT_RUNNING, "")));
                observed.put("raw_mode", Store.get(ctx, Store.K_GT_MODE, ""));
                observed.put("running", Store.get(ctx, Store.K_GT_RUNNING, ""));
                observed.put("at", Store.getLong(ctx, Store.K_GT_AT, 0));
                observed.put("parse_ok", Store.getBool(ctx, Store.K_GT_PARSE_OK, false));
            }
            body.put("observed", observed);

            JSONObject desired = new JSONObject();
            desired.put("mode", tv ? "" : Store.get(ctx, Store.K_DESIRED_MODE, ""));
            desired.put("seq", tv ? -1 : Store.getLong(ctx, Store.K_DESIRED_SEQ, -1));
            body.put("desired", desired);

            // On TV there is no reconciler verdict: never claim an ack.
            String verdict = tv ? "unknown" : Store.get(ctx, Store.K_VERDICT, "unknown");
            body.put("verdict", verdict);
            body.put("ack", !tv && "in_sync".equals(verdict));
            body.put("plan", tv ? "tv profile: phone surfaces gated off" : Store.get(ctx, Store.K_PLAN, ""));
            body.put("status", StatusProducer.produce(ctx));
            body.put("adb", AdbBridge.snapshot(ctx));
            body.put("tailscale", TailscaleGuard.snapshot(ctx));
            body.put("shell_bridge", ShellBridge.snapshot(ctx));

            URL url = new URL(base + "/phone-bridge/report?secret="
                    + URLEncoder.encode(secret, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "MusePhoneBridge/1.1");
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

    // ---- v1.4 loopback shell bridge channel ------------------------------
    //   GET  {base}/phone-bridge/commands?secret={secret}
    //        -> {"command": {"id","template","args":[...],"expires_at"}}
    //           (a bare command object, or {"commands":[...]}, are also
    //           accepted; no command -> empty/null body or {"command":null})
    //   POST {base}/phone-bridge/command-result?secret={secret}
    //        <- {"device":"phone-bridge","id","ok","stdout_tail","ms","error"}
    // The Worker is a transport only: ShellAllowlist gates every command
    // on-device, and the Worker cannot widen the allowlist.

    /** One structured command pulled from the Worker. */
    public static final class Command {
        public final String id;
        public final String template;
        public final java.util.List<String> args;
        public final long expiresAtMs;

        Command(String id, String template, java.util.List<String> args, long expiresAtMs) {
            this.id = id;
            this.template = template;
            this.args = args;
            this.expiresAtMs = expiresAtMs;
        }
    }

    /** Fetch at most one pending command; null when none / endpoint absent. */
    public static Command fetchCommand(Context ctx) {
        if (!Store.workerConfigured(ctx)) return null;
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        String secret = Store.get(ctx, Store.K_SECRET, "").trim();
        try {
            URL url = new URL(base + "/phone-bridge/commands?secret="
                    + URLEncoder.encode(secret, "UTF-8")
                    + "&device=" + URLEncoder.encode(DeviceProfile.deviceString(ctx), "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", "MusePhoneBridge/1.1");
            int code = c.getResponseCode();
            if (code == 404 || code == 204) return null; // channel not deployed yet
            if (code != 200) {
                BridgeLog.log(ctx, "SHELL", "commands GET -> HTTP " + code);
                return null;
            }
            String body = readAll(c.getInputStream());
            if (body == null || body.trim().isEmpty()) return null;
            JSONObject root = new JSONObject(body);
            JSONObject cmdObj = root.optJSONObject("command");
            if (cmdObj == null && root.optJSONArray("commands") != null
                    && root.optJSONArray("commands").length() > 0) {
                cmdObj = root.optJSONArray("commands").optJSONObject(0);
            }
            if (cmdObj == null && root.has("template")) cmdObj = root;
            if (cmdObj == null) return null;
            String id = cmdObj.optString("id", "");
            String template = cmdObj.optString("template", "");
            if (id.isEmpty() || template.isEmpty()) return null;
            java.util.List<String> args = new java.util.ArrayList<>();
            org.json.JSONArray arr = cmdObj.optJSONArray("args");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) args.add(arr.optString(i, ""));
            }
            long expires = cmdObj.optLong("expires_at", 0);
            return new Command(id, template, args, expires);
        } catch (Exception e) {
            BridgeLog.log(ctx, "SHELL", "commands GET failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    /** Post one command's result back over the same channel. */
    public static boolean postCommandResult(Context ctx, String id, boolean ok,
                                            String stdoutTail, long ms, String error) {
        if (!Store.workerConfigured(ctx)) return false;
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        String secret = Store.get(ctx, Store.K_SECRET, "").trim();
        try {
            JSONObject body = new JSONObject();
            body.put("device", DeviceProfile.deviceString(ctx));
            body.put("id", id);
            body.put("ok", ok);
            body.put("stdout_tail", stdoutTail != null ? stdoutTail : "");
            body.put("ms", ms);
            if (error != null) body.put("error", error);
            URL url = new URL(base + "/phone-bridge/command-result?secret="
                    + URLEncoder.encode(secret, "UTF-8")
                    + "&device=" + URLEncoder.encode(DeviceProfile.deviceString(ctx), "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", "MusePhoneBridge/1.1");
            c.setDoOutput(true);
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
            }
            int code = c.getResponseCode();
            BridgeLog.log(ctx, "SHELL", "command-result " + id + " ok=" + ok
                    + " -> HTTP " + code);
            return code >= 200 && code < 300;
        } catch (Exception e) {
            BridgeLog.log(ctx, "SHELL", "command-result POST failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
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
