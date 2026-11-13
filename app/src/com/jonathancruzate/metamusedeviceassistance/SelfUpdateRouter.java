package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.content.pm.PackageInfo;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;

/**
 * SelfUpdateRouter — v1.21: executes the native `self_update`
 * command (gate: ShellAllowlist.checkSelfUpdate; transport: the
 * Worker command channel or the v1.20 local channel, unchanged).
 *
 * Why this exists (Jonathan's call, 2026-11-13): around 2026-11-10
 * the phone forgot the VM's wireless-debugging pairing, and every
 * Device Assistance release from v1.19 on was stranded — the app
 * could keep its own loopback bridge alive but could not re-authorize
 * an external client, by design. This router removes the external
 * lane from the release path: the app updates ITSELF through the
 * loopback bridge that stays up exactly when external ADB is down.
 *
 * Flow (synchronous on the bridge thread, WearInstallRouter-style):
 *   1. Download the APK from the command's URL into the app's
 *      external files dir, streaming-verifying SHA-256 against the
 *      command's digest. Mismatch = typed failure, nothing staged.
 *      (External files, not cache: the shell can read it there;
 *      internal storage is invisible to the shell user.)
 *   2. Loopback shell: cp the staged APK to /data/local/tmp (the
 *      system server cannot read FUSE paths for install — proven
 *      live 2026-11-13 — but reads /data/local/tmp fine).
 *   3. Mirror stage "installing" to the Store BEFORE pm runs: the
 *      install kills this process, so the command result may never
 *      post. Ground truth is the report: snapshot() reconciles
 *      "installing" to "installed" once the running versionCode
 *      differs from the recorded from-code, and the report now
 *      always carries the app version (it never did before v1.21 —
 *      the blind-diagnosis defect behind the Nov 10–13 confusion).
 *   4. Loopback shell: pm install -r /data/local/tmp/da-update.apk.
 *      Same package + same signing cert = in-place update; grants,
 *      the accessibility binding, and the loopback pairing all
 *      survive (proven live 2026-11-13 for the manual equivalent).
 *   Both shell argv arrays are fully hardcoded here — nothing from
 *   the command (URL, sha) ever becomes shell text.
 */
public final class SelfUpdateRouter {
    private SelfUpdateRouter() {}

    private static final long MAX_APK_BYTES = 32L * 1024 * 1024;
    private static final String STAGED_NAME = "da-update.apk";
    private static final String TMP_PATH = "/data/local/tmp/da-update.apk";

    private static void mirror(Context ctx, String sha, String stage,
                               String detail, long fromCode) {
        Store.put(ctx, Store.K_UPDATE_SHA, sha);
        Store.put(ctx, Store.K_UPDATE_STAGE, stage);
        Store.put(ctx, Store.K_UPDATE_DETAIL, detail == null ? "" : detail);
        Store.put(ctx, Store.K_UPDATE_AT, System.currentTimeMillis());
        if (fromCode >= 0) Store.put(ctx, Store.K_UPDATE_FROM, fromCode);
        BridgeLog.log(ctx, "UPDATE", "stage=" + stage
                + (detail != null && !detail.isEmpty() ? " " + detail : ""));
    }

    static long currentCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.getLongVersionCode();
        } catch (Exception e) {
            return -1;
        }
    }

    static String currentName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "" : pi.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    /** ShellBridge entry point. args = {url, sha256}, already gated
     *  by ShellAllowlist.checkSelfUpdate. */
    public static LoopbackAdb.Result execute(Context ctx, String[] args) {
        long t0 = System.currentTimeMillis();
        String url = args[0], sha = args[1];
        long fromCode = currentCode(ctx);
        File staged = null;
        try {
            mirror(ctx, sha, "downloading", url, fromCode);
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) {
                return fail(ctx, sha, t0, fromCode, "no_staging",
                        "external files dir unavailable");
            }
            staged = new File(dir, STAGED_NAME);
            long size = downloadVerified(url, sha, staged);
            if (size < 0) {
                return fail(ctx, sha, t0, fromCode, "sha256_mismatch",
                        "downloaded bytes do not match the command sha256");
            }
            mirror(ctx, sha, "staged", "bytes=" + size, fromCode);

            String[] cpArgv = {"cp", staged.getAbsolutePath(), TMP_PATH};
            LoopbackAdb.Result cp = LoopbackAdb.execute(ctx, cpArgv);
            if (!cp.ok) {
                return fail(ctx, sha, t0, fromCode, "stage_cp_failed",
                        cp.error != null ? cp.error : "cp result not ok");
            }

            // The install kills this process. Everything after this
            // mirror is best-effort; the report reconciles the truth.
            mirror(ctx, sha, "installing", "from_code=" + fromCode, fromCode);
            String[] pmArgv = {"pm", "install", "-r", TMP_PATH};
            LoopbackAdb.Result pm = LoopbackAdb.execute(ctx, pmArgv);
            long ms = System.currentTimeMillis() - t0;
            if (pm.ok && pm.stdoutTail != null
                    && pm.stdoutTail.contains("Success")) {
                mirror(ctx, sha, "installed", "pm Success", fromCode);
                cleanup(ctx, staged);
                return new LoopbackAdb.Result(true,
                        "self_update installed sha=" + sha.substring(0, 8)
                                + " from_code=" + fromCode, ms, null);
            }
            String why = pm.error != null ? pm.error
                    : ("pm output: " + (pm.stdoutTail == null ? ""
                            : pm.stdoutTail.trim()));
            return fail(ctx, sha, t0, fromCode, "install_failed", why);
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            String err = "self_update_error: " + e.getClass().getSimpleName()
                    + " " + e.getMessage();
            mirror(ctx, sha, "failed", err, fromCode);
            return new LoopbackAdb.Result(false, "self_update stage=error",
                    ms, err);
        }
    }

    private static void cleanup(Context ctx, File staged) {
        try {
            LoopbackAdb.execute(ctx,
                    new String[]{"rm", "-f", TMP_PATH});
        } catch (Exception ignored) {}
        if (staged != null) {
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
        }
    }

    private static LoopbackAdb.Result fail(Context ctx, String sha,
                                           long t0, long fromCode,
                                           String stage, String detail) {
        mirror(ctx, sha, stage, detail, fromCode);
        return new LoopbackAdb.Result(false, "self_update stage=" + stage,
                System.currentTimeMillis() - t0, stage + ": " + detail);
    }

    /** Stream the URL to disk while hashing; returns the byte count,
     *  or -1 when the digest does not match the expected sha256.
     *  (Same pattern as WearInstallRouter.downloadVerified.) */
    private static long downloadVerified(String url, String expectedSha,
                                         File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "MuseDeviceAssistance/self-update");
        try {
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("http_" + code);
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (InputStream in = c.getInputStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_APK_BYTES) {
                        throw new IllegalStateException("payload_too_large");
                    }
                    md.update(buf, 0, n);
                    fos.write(buf, 0, n);
                }
            }
            String actual = WearProtocol.toHex(md.digest());
            return actual.equals(expectedSha) ? total : -1;
        } finally {
            c.disconnect();
        }
    }

    /** Report object (carried in shell_bridge.self_update): always
     *  carries the running app version — new in v1.21 — plus the
     *  last update job's state, with "installing" reconciled to
     *  "installed" once the running build is no longer the build
     *  that started the install. */
    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            long code = currentCode(ctx);
            long lastUpdate = 0;
            try {
                lastUpdate = ctx.getPackageManager()
                        .getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime;
            } catch (Exception ignored) {}
            o.put("version_name", currentName(ctx));
            o.put("version_code", code);
            o.put("last_update_time", lastUpdate);
            long at = Store.getLong(ctx, Store.K_UPDATE_AT, 0);
            if (at > 0) {
                String stage = Store.get(ctx, Store.K_UPDATE_STAGE, "");
                long from = Store.getLong(ctx, Store.K_UPDATE_FROM, -1);
                // Reconcile a job whose result never posted (the
                // install killed the process): it landed if the
                // running build changed OR the package's update
                // time is newer than the "installing" mirror (the
                // same-version reinstall case).
                if ("installing".equals(stage) && from >= 0
                        && (code != from || lastUpdate > at)) {
                    stage = "installed";
                }
                o.put("stage", stage);
                o.put("sha", Store.get(ctx, Store.K_UPDATE_SHA, ""));
                o.put("detail", Store.get(ctx, Store.K_UPDATE_DETAIL, ""));
                o.put("from_code", from);
                o.put("at", at);
            }
        } catch (Exception ignored) {}
        return o;
    }
}
