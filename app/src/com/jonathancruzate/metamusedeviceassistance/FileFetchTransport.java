package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * FileFetchTransport: the v1.36 files_get upload lane to the app's
 * configured base (the box device relay; the same Store base +
 * secret as WorkerClient and ScreenTransport, entered on-device,
 * never hardcoded, never logged).
 *
 * Route (relay addition, docs/v1.36-files-subfolders-fetch-2026-11-24.md):
 *   POST {base}/files/fetch  <- {name, folder, sub, ext, size,
 *        sha256, ts, data_b64}
 * The relay verifies the SHA-256 against the decoded bytes and the
 * magic bytes against the claimed image type, then stores the
 * binary beside its JSON store; Muse pulls it from there. The
 * payload rides base64-in-JSON because the relay's plumbing is
 * JSON-shaped end to end (the screen-frame discipline); the size
 * cap lives in FilePlan.FETCH_MAX_BYTES so the encoded body always
 * fits the relay's 4 MB request cap.
 *
 * All calls are blocking: callers run on the bridge command thread,
 * never the UI thread. The read timeout is generous because a
 * near-cap payload on cellular uploads slowly; a timeout is a
 * typed files_upload_failed in FileRouter, never a silent retry.
 */
public final class FileFetchTransport {
    private FileFetchTransport() {}

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 60_000;

    /** Upload one image. Returns true only on HTTP 2xx. */
    public static boolean postFetch(Context ctx, String folder, String sub,
                                    String name, String ext, long size,
                                    String sha256, byte[] data) {
        if (!Store.workerConfigured(ctx) || data == null || data.length == 0) {
            return false;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("name", name);
            body.put("folder", folder);
            body.put("sub", sub == null ? "" : sub);
            body.put("ext", ext);
            body.put("size", size);
            body.put("sha256", sha256);
            body.put("ts", System.currentTimeMillis());
            body.put("data_b64", Base64.encodeToString(data, Base64.NO_WRAP));
            int code = post(ctx, "/files/fetch", body);
            return code >= 200 && code < 300;
        } catch (Exception e) {
            BridgeLog.log(ctx, "FILES", "fetch POST failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    /** Shared POST helper: returns the HTTP code, or -1 on failure. */
    private static int post(Context ctx, String path, JSONObject body)
            throws Exception {
        String base = Store.get(ctx, Store.K_BASE_URL, "").trim();
        String secret = Store.get(ctx, Store.K_SECRET, "").trim();
        URL url = new URL(base + path + "?secret="
                + URLEncoder.encode(secret, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
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
}
