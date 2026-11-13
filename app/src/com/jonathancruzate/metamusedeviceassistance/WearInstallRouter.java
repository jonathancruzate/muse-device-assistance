package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.net.Uri;

import com.google.android.gms.tasks.Tasks;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * WearInstallRouter — v1.13: executes the native `wear_install_face`
 * command (gate: ShellAllowlist.checkWearInstall; transport: the
 * Worker command channel, unchanged).
 *
 * Flow (all synchronous on the bridge thread, BudsRouter-style — an
 * install is a rare, deliberate command, and the command's own TTL
 * already elapsed before fetch if it went stale):
 *   1. Download the APK from the command's HTTPS URL into the app
 *      cache, streaming-verifying its SHA-256 against the command's
 *      digest. Mismatch = typed failure, nothing is relayed.
 *   2. Pick the connected watch node (Wearable NodeClient).
 *   3. Send the job offer (MessageClient) and stream the APK over a
 *      ChannelClient channel — the Muse Wear Installer app on the
 *      watch re-verifies the hash before it prompts.
 *   4. Block on a job-keyed latch for the watch's terminal verdict
 *      (installed / failed / verify_failed / dismissed), delivered
 *      by WearStatusService from the watch's status messages.
 *      Budget: 300 s end to end. A timeout is a typed failure
 *      (watch_install_timeout, carrying the last stage seen) — never
 *      a retry: the watch may still prompt later, and its JobStore
 *      record + the report's wear_install object carry the truth.
 *
 * Each stage transition is mirrored to the Store (K_WEAR_*) and the
 * bridge log, so the periodic report shows the last job's state even
 * after the command result has been posted.
 */
public final class WearInstallRouter {
    private WearInstallRouter() {}

    private static final long TOTAL_BUDGET_MS = 300_000;
    private static final long IO_AWAIT_S = 30;

    private static final class Slot {
        final CountDownLatch done = new CountDownLatch(1);
        volatile String stage = "started";
        volatile String detail = "";
    }

    private static final ConcurrentHashMap<String, Slot> SLOTS =
            new ConcurrentHashMap<>();

    /** WearStatusService entry point: record a watch status message. */
    static void deliver(Context ctx, String jobId, String label,
                        String stage, String detail) {
        mirror(ctx, jobId, label, stage, detail);
        Slot s = SLOTS.get(jobId);
        if (s != null) {
            s.stage = stage;
            s.detail = detail == null ? "" : detail;
            if (WearProtocol.isTerminal(stage)) s.done.countDown();
        }
    }

    private static void mirror(Context ctx, String jobId, String label,
                               String stage, String detail) {
        Store.put(ctx, Store.K_WEAR_JOB_ID, jobId);
        if (label != null && !label.isEmpty()) {
            Store.put(ctx, Store.K_WEAR_LABEL, label);
        }
        Store.put(ctx, Store.K_WEAR_STAGE, stage);
        Store.put(ctx, Store.K_WEAR_DETAIL, detail == null ? "" : detail);
        Store.put(ctx, Store.K_WEAR_AT, System.currentTimeMillis());
        BridgeLog.log(ctx, "WEAR", "job=" + jobId + " stage=" + stage
                + (detail != null && !detail.isEmpty() ? " " + detail : ""));
    }

    /** ShellBridge entry point. args = {label, url, sha256}, already
     *  gated by ShellAllowlist.checkWearInstall. */
    public static LoopbackAdb.Result execute(Context ctx, String[] args) {
        long t0 = System.currentTimeMillis();
        String label = args[0], url = args[1], sha = args[2];
        String jobId = UUID.randomUUID().toString();
        Slot slot = new Slot();
        SLOTS.put(jobId, slot);
        File tmp = null;
        try {
            mirror(ctx, jobId, label, "downloading", url);
            tmp = new File(ctx.getCacheDir(), "wear-" + jobId + ".apk");
            long size = downloadVerified(url, sha, tmp);
            if (size < 0) {
                return fail(ctx, jobId, label, t0, "sha256_mismatch",
                        "downloaded bytes do not match the command sha256");
            }
            mirror(ctx, jobId, label, "downloaded", "bytes=" + size);

            Node watch = pickWatch(ctx);
            if (watch == null) {
                return fail(ctx, jobId, label, t0, "watch_not_connected",
                        "no Wear OS node is connected to the phone");
            }

            slot.stage = "offering";
            Tasks.await(Wearable.getMessageClient(ctx).sendMessage(
                    watch.getId(), WearProtocol.PATH_OFFER,
                    WearProtocol.offerJson(jobId, label, sha, size)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    IO_AWAIT_S, TimeUnit.SECONDS);
            mirror(ctx, jobId, label, "offered", "node=" + watch.getDisplayName());

            slot.stage = "transferring";
            ChannelClient.Channel channel = Tasks.await(
                    Wearable.getChannelClient(ctx).openChannel(watch.getId(),
                            WearProtocol.CHANNEL_PREFIX + jobId),
                    IO_AWAIT_S, TimeUnit.SECONDS);
            try {
                Tasks.await(Wearable.getChannelClient(ctx)
                        .sendFile(channel, Uri.fromFile(tmp)), 240,
                        TimeUnit.SECONDS);
            } finally {
                try {
                    Tasks.await(Wearable.getChannelClient(ctx).close(channel),
                            10, TimeUnit.SECONDS);
                } catch (Exception ignored) {}
            }
            mirror(ctx, jobId, label, "transferred",
                    "awaiting on-watch verdict");

            long remaining = TOTAL_BUDGET_MS - (System.currentTimeMillis() - t0);
            boolean finished = remaining > 0
                    && slot.done.await(remaining, TimeUnit.MILLISECONDS);
            long ms = System.currentTimeMillis() - t0;
            if (!finished) {
                return new LoopbackAdb.Result(false,
                        "wear_install " + label + " stage=" + slot.stage, ms,
                        "watch_install_timeout: last stage " + slot.stage
                                + " after " + (TOTAL_BUDGET_MS / 1000) + "s");
            }
            if ("installed".equals(slot.stage)) {
                return new LoopbackAdb.Result(true,
                        "wear_install " + label + " stage=installed job="
                                + jobId.substring(0, 8), ms, null);
            }
            return new LoopbackAdb.Result(false,
                    "wear_install " + label + " stage=" + slot.stage, ms,
                    "watch_" + slot.stage
                            + (slot.detail.isEmpty() ? "" : ": " + slot.detail));
        } catch (Exception e) {
            long ms = System.currentTimeMillis() - t0;
            String err = "wear_install_error: " + e.getClass().getSimpleName()
                    + " " + e.getMessage();
            mirror(ctx, jobId, label, "failed", err);
            return new LoopbackAdb.Result(false,
                    "wear_install " + label + " stage=error", ms, err);
        } finally {
            SLOTS.remove(jobId);
            if (tmp != null) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    private static LoopbackAdb.Result fail(Context ctx, String jobId,
                                           String label, long t0,
                                           String stage, String detail) {
        mirror(ctx, jobId, label, stage, detail);
        return new LoopbackAdb.Result(false,
                "wear_install " + label + " stage=" + stage,
                System.currentTimeMillis() - t0, stage + ": " + detail);
    }

    /** Stream the URL to disk while hashing; returns the byte count,
     *  or -1 when the digest does not match the expected sha256. */
    private static long downloadVerified(String url, String expectedSha,
                                         File out) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "MuseDeviceAssistance/wear-install");
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
                    if (total > WearProtocol.MAX_APK_BYTES) {
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

    private static Node pickWatch(Context ctx) throws Exception {
        List<Node> nodes = Tasks.await(
                Wearable.getNodeClient(ctx).getConnectedNodes(),
                IO_AWAIT_S, TimeUnit.SECONDS);
        if (nodes == null || nodes.isEmpty()) return null;
        for (Node n : nodes) {
            String name = n.getDisplayName();
            if (name != null && name.toLowerCase(java.util.Locale.US)
                    .contains("watch")) {
                return n;
            }
        }
        return nodes.get(0);
    }

    /** Report object: the last job's mirrored state. */
    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            long at = Store.getLong(ctx, Store.K_WEAR_AT, 0);
            if (at > 0) {
                o.put("job_id", Store.get(ctx, Store.K_WEAR_JOB_ID, ""));
                o.put("label", Store.get(ctx, Store.K_WEAR_LABEL, ""));
                o.put("stage", Store.get(ctx, Store.K_WEAR_STAGE, ""));
                o.put("detail", Store.get(ctx, Store.K_WEAR_DETAIL, ""));
                o.put("at", at);
            }
        } catch (Exception ignored) {}
        return o;
    }
}
