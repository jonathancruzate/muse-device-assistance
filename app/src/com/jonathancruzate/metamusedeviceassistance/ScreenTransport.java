package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.util.Base64;

import org.json.JSONArray;
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
 * ScreenTransport — the v1.25 screen module's HTTP lane to the app's
 * configured base (since the v1.9 cut, the box device relay; the
 * Worker before it — this class does not care: it uses the same
 * Store base + secret as WorkerClient, entered on-device, never
 * hardcoded, never logged).
 *
 * Routes (relay additions, docs/v1.25-screen-sharing-design-2026-11-13.md):
 *   POST {base}/screen/frame  <- {frame_b64, w, h, ts, streaming}
 *   POST {base}/screen/state  <- {streaming, frames, started_at, error?}
 *   GET  {base}/screen/input  -> {"inputs":[{...}]}  (drain semantics:
 *        the relay returns the queue and clears it)
 *
 * Frames ride as base64-in-JSON because the relay's plumbing is
 * JSON-shaped end to end (4 MB body cap; a stream frame is
 * ~60–150 KB on the wire). All calls are blocking — callers run on
 * bridge / capture-service threads, never the UI thread.
 */
public final class ScreenTransport {
    private ScreenTransport() {}

    private static final int TIMEOUT_MS = 8000;

    /** Upload one JPEG frame. Returns true only on HTTP 2xx. */
    public static boolean postFrame(Context ctx, byte[] jpeg, int w, int h,
                                    boolean streaming) {
        if (!Store.workerConfigured(ctx) || jpeg == null || jpeg.length == 0) return false;
        try {
            JSONObject body = new JSONObject();
            body.put("frame_b64", Base64.encodeToString(jpeg, Base64.NO_WRAP));
            body.put("w", w);
            body.put("h", h);
            body.put("ts", System.currentTimeMillis());
            body.put("streaming", streaming);
            return post(ctx, "/screen/frame", body) >= 200;
        } catch (Exception e) {
            BridgeLog.log(ctx, "SCREEN", "frame POST failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    /** Post stream state (start/stop/error heartbeats). */
    public static boolean postState(Context ctx, boolean streaming, long frames,
                                    long startedAt, String error) {
        if (!Store.workerConfigured(ctx)) return false;
        try {
            JSONObject body = new JSONObject();
            body.put("streaming", streaming);
            body.put("frames", frames);
            body.put("started_at", startedAt);
            body.put("ts", System.currentTimeMillis());
            if (error != null && !error.isEmpty()) body.put("error", error);
            return post(ctx, "/screen/state", body) >= 200;
        } catch (Exception e) {
            BridgeLog.log(ctx, "SCREEN", "state POST failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    /** Drain the fast input queue; null when none / unreachable. */
    public static JSONArray fetchInputs(Context ctx) {
        if (!Store.workerConfigured(ctx)) return null;
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        String secret = Store.get(ctx, Store.K_SECRET, "").trim();
        try {
            URL url = new URL(base + "/screen/input?secret="
                    + URLEncoder.encode(secret, "UTF-8"));
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestProperty("User-Agent", "MusePhoneBridge/1.1");
            int code = c.getResponseCode();
            if (code == 404 || code == 204) return null; // lane not deployed yet
            if (code != 200) {
                BridgeLog.log(ctx, "SCREEN", "input GET -> HTTP " + code);
                return null;
            }
            String body = readAll(c.getInputStream());
            if (body == null || body.trim().isEmpty()) return null;
            JSONArray arr = new JSONObject(body).optJSONArray("inputs");
            return arr != null && arr.length() > 0 ? arr : null;
        } catch (Exception e) {
            BridgeLog.log(ctx, "SCREEN", "input GET failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return null;
        }
    }

    /** Shared POST helper: returns the HTTP code, or -1 on failure. */
    private static int post(Context ctx, String path, JSONObject body) throws Exception {
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        String secret = Store.get(ctx, Store.K_SECRET, "").trim();
        URL url = new URL(base + path + "?secret="
                + URLEncoder.encode(secret, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(TIMEOUT_MS);
        c.setReadTimeout(TIMEOUT_MS);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("User-Agent", "MusePhoneBridge/1.1");
        c.setDoOutput(true);
        byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream os = c.getOutputStream()) {
            os.write(payload);
        }
        return c.getResponseCode();
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        }
        return sb.toString();
    }
}
