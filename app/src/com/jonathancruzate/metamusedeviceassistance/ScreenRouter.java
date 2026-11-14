package com.jonathancruzate.metamusedeviceassistance;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ScreenRouter — v1.25: executes the native `screen_*` commands
 * (gate: ShellAllowlist.checkScreen / checkScreenText; design:
 * docs/v1.25-screen-sharing-design-2026-11-13.md).
 *
 * Gate discipline (house pattern, voice module): the module switch
 * Store.K_SCREEN_ENABLED is Jonathan's alone. While it is OFF every
 * action op refuses typed (screen_module_disabled) — the capability
 * does not exist. Two ops are exempt: screen_status (always
 * answers) and screen_stop (stopping must never be stranded by the
 * switch being flipped mid-stream). TV profile refuses everything
 * except a status that says so.
 *
 * screen_start honesty rule: MediaProjection consent is a system
 * dialog Android shows per session and no app can bypass. A remote
 * start therefore (a) tries to surface the consent activity
 * directly (works when the app is foreground; background launches
 * are usually blocked by the platform), and (b) ALWAYS posts a
 * tappable "Start screen sharing" notification as the reliable
 * path, and the command result says consent is required rather
 * than claiming a stream that does not exist yet. screen_status
 * is how a caller verifies the stream actually began (freshness:
 * the frame counters / last-frame stamp move).
 */
public final class ScreenRouter {
    private ScreenRouter() {}

    private static final String CONSENT_CHANNEL = "screen-consent";

    // ---- entry point ----------------------------------------------------

    /**
     * ShellBridge entry point. For coordinate/key ops, args are the
     * gate's validated typed values; for screen_text /
     * screen_find_tap, textArg carries the gate-joined free text.
     */
    public static LoopbackAdb.Result execute(Context ctx, String op,
                                             String[] args, String textArg) {
        long t0 = System.currentTimeMillis();
        try {
            if ("screen_status".equals(op)) {
                return ok(statusText(ctx), t0);
            }
            // Stop is exempt from the module gate too: turning the
            // module off mid-stream must never strand a live stream
            // (the MainActivity Stop button rides this same path).
            if ("screen_stop".equals(op)) {
                return stop(ctx, t0);
            }
            if (DeviceProfile.isTv(ctx)) {
                return refuse("tv_profile", t0);
            }
            if (!Store.getBool(ctx, Store.K_SCREEN_ENABLED, false)) {
                return refuse("screen_module_disabled", t0);
            }
            switch (op) {
                case "screen_shot":
                    return shot(ctx, t0);
                case "screen_start":
                    return start(ctx, t0);
                case "screen_tap": {
                    int x = Integer.parseInt(args[0]);
                    int y = Integer.parseInt(args[1]);
                    String err = ScreenGestures.tap(ctx, x, y);
                    if (err == null) recordInput(ctx, "tap " + x + "," + y);
                    return err == null
                            ? ok("tapped " + x + "," + y + " (normalized)", t0)
                            : refuse(err, t0);
                }
                case "screen_swipe": {
                    int x1 = Integer.parseInt(args[0]);
                    int y1 = Integer.parseInt(args[1]);
                    int x2 = Integer.parseInt(args[2]);
                    int y2 = Integer.parseInt(args[3]);
                    int ms = Integer.parseInt(args[4]);
                    String err = ScreenGestures.swipe(ctx, x1, y1, x2, y2, ms);
                    if (err == null) recordInput(ctx, "swipe");
                    return err == null
                            ? ok("swiped " + x1 + "," + y1 + " -> " + x2 + "," + y2
                                    + " in " + ms + "ms", t0)
                            : refuse(err, t0);
                }
                case "screen_key": {
                    String err = ScreenGestures.key(ctx, args[0]);
                    if (err == null) recordInput(ctx, "key " + args[0]);
                    return err == null ? ok("key " + args[0], t0) : refuse(err, t0);
                }
                case "screen_text": {
                    if (textArg == null || textArg.isEmpty()) {
                        return refuse("no_text", t0);
                    }
                    String err = ScreenGestures.typeText(ctx, textArg);
                    if (err == null) recordInput(ctx, "text (" + textArg.length() + " chars)");
                    return err == null
                            ? ok("typed " + textArg.length() + " chars", t0)
                            : refuse(err, t0);
                }
                case "screen_find_tap": {
                    if (textArg == null || textArg.isEmpty()) {
                        return refuse("no_label", t0);
                    }
                    String err = ScreenGestures.findTap(ctx, textArg);
                    if (err == null) recordInput(ctx, "find_tap " + textArg);
                    return err == null
                            ? ok("tapped label \"" + textArg + "\"", t0)
                            : refuse(err, t0);
                }
                default:
                    return refuse("unknown screen op", t0);
            }
        } catch (Exception e) {
            BridgeLog.log(ctx, "SCREEN", op + " failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /**
     * Fast-lane input pump entry (called by ScreenCaptureService
     * with each drained relay input object while streaming). The
     * relay validated the shape; this method RE-validates every
     * field on-device before dispatch — the queue is a transport,
     * never an authority. Never throws.
     */
    public static void executeFastInput(Context ctx, JSONObject in) {
        try {
            if (!Store.getBool(ctx, Store.K_SCREEN_ENABLED, false)) return;
            if (DeviceProfile.isTv(ctx)) return;
            String type = in.optString("type", "");
            switch (type) {
                case "tap": {
                    int x = in.optInt("x", -1), y = in.optInt("y", -1);
                    if (!coordOk(x) || !coordOk(y)) return;
                    String err = ScreenGestures.tap(ctx, x, y);
                    if (err == null) recordInput(ctx, "tap " + x + "," + y + " (fast)");
                    break;
                }
                case "swipe": {
                    int x1 = in.optInt("x1", -1), y1 = in.optInt("y1", -1);
                    int x2 = in.optInt("x2", -1), y2 = in.optInt("y2", -1);
                    int ms = in.optInt("ms", 300);
                    if (!coordOk(x1) || !coordOk(y1) || !coordOk(x2) || !coordOk(y2)) return;
                    if (ms < 50) ms = 50;
                    if (ms > 2000) ms = 2000;
                    String err = ScreenGestures.swipe(ctx, x1, y1, x2, y2, ms);
                    if (err == null) recordInput(ctx, "swipe (fast)");
                    break;
                }
                case "key": {
                    String name = in.optString("key", "");
                    if (!keyOk(name)) return;
                    String err = ScreenGestures.key(ctx, name);
                    if (err == null) recordInput(ctx, "key " + name + " (fast)");
                    break;
                }
                case "text": {
                    String text = in.optString("text", "");
                    if (text.isEmpty() || text.length() > 1000) return;
                    String err = ScreenGestures.typeText(ctx, text);
                    if (err == null) recordInput(ctx, "text (fast)");
                    break;
                }
                case "find_tap": {
                    String label = in.optString("label", "");
                    if (label.isEmpty() || label.length() > 200) return;
                    String err = ScreenGestures.findTap(ctx, label);
                    if (err == null) recordInput(ctx, "find_tap " + label + " (fast)");
                    break;
                }
                case "stop": {
                    stop(ctx, System.currentTimeMillis());
                    break;
                }
                default:
                    break; // unknown fast input: ignored, never executed
            }
        } catch (Exception e) {
            BridgeLog.log(ctx, "SCREEN", "fast input failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    public static boolean coordOk(int v) { return v >= 0 && v <= 1000; }

    public static boolean keyOk(String name) {
        return "back".equals(name) || "home".equals(name)
                || "recents".equals(name) || "notifications".equals(name)
                || "quick_settings".equals(name) || "lock".equals(name);
    }

    // ---- ops --------------------------------------------------------------

    private static LoopbackAdb.Result shot(Context ctx, long t0) {
        if (!ScreenGestures.accessibilityRunning()) {
            return refuse("accessibility_not_running", t0);
        }
        ScreenGestures.Frame f = ScreenGestures.snapshot(ctx);
        if (f == null) {
            Store.put(ctx, Store.K_SCREEN_LAST_ERROR, "snapshot_failed_or_secure_window");
            return refuse("snapshot_failed (secure window or platform refusal)", t0);
        }
        boolean streaming = ScreenState.streaming;
        boolean up = ScreenTransport.postFrame(ctx, f.jpeg, f.w, f.h, streaming);
        long now = System.currentTimeMillis();
        Store.put(ctx, Store.K_SCREEN_LAST_FRAME_AT, now);
        Store.put(ctx, Store.K_SCREEN_LAST_W, (long) f.w);
        Store.put(ctx, Store.K_SCREEN_LAST_H, (long) f.h);
        Store.put(ctx, Store.K_SCREEN_FRAMES,
                Store.getLong(ctx, Store.K_SCREEN_FRAMES, 0) + 1);
        Store.put(ctx, Store.K_SCREEN_LAST_ERROR, "");
        BridgeLog.log(ctx, "SCREEN", "shot " + f.w + "x" + f.h + " "
                + f.jpeg.length + "B uploaded=" + up);
        return ok("shot " + f.w + "x" + f.h + " bytes=" + f.jpeg.length
                + " uploaded=" + up, t0);
    }

    private static LoopbackAdb.Result start(Context ctx, long t0) {
        if (ScreenState.streaming) {
            return ok("already_streaming started_at=" + ScreenState.sessionStartedAt, t0);
        }
        ScreenState.stopRequested = false;
        // Try the direct launch first (succeeds when the app is
        // foreground); the notification below is the reliable path
        // and is posted either way when the activity did not take.
        boolean launched = false;
        try {
            ScreenConsentActivity.start(ctx);
            launched = true;
        } catch (Exception e) {
            BridgeLog.log(ctx, "SCREEN", "consent launch failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        postConsentNotification(ctx);
        BridgeLog.log(ctx, "SCREEN", "screen_start: consent requested (launched="
                + launched + ", notification posted)");
        return ok("consent_required: Jonathan must tap the system screen-capture "
                + "dialog (notification posted: 'Start screen sharing'). "
                + "Verify with screen_status — streaming=true + fresh frames.", t0);
    }

    private static LoopbackAdb.Result stop(Context ctx, long t0) {
        ScreenState.stopRequested = true;
        try {
            ctx.stopService(new Intent(ctx, ScreenCaptureService.class));
        } catch (Exception ignored) {}
        ScreenState.streaming = false;
        Store.put(ctx, Store.K_SCREEN_STREAMING, false);
        cancelConsentNotification(ctx);
        BridgeLog.log(ctx, "SCREEN", "screen_stop: stream stopped");
        return ok("stopped", t0);
    }

    // ---- consent notification ----------------------------------------------

    /** The tappable consent path: a high-priority notification whose
     *  tap opens ScreenConsentActivity (which shows the system
     *  dialog). Posted on every remote screen_start; cancelled on
     *  stream start and on stop. */
    public static void postConsentNotification(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(
                    CONSENT_CHANNEL, "Screen sharing consent",
                    NotificationManager.IMPORTANCE_HIGH));
            Intent open = new Intent(ctx, ScreenConsentActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent pi = PendingIntent.getActivity(ctx, 7411, open,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(ctx, CONSENT_CHANNEL)
                    .setSmallIcon(android.R.drawable.presence_video_online)
                    .setContentTitle("Start screen sharing")
                    .setContentText("Tap, then approve the system dialog, so Muse can see this screen")
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .build();
            nm.notify(7411, n);
        } catch (Exception e) {
            BridgeLog.log(ctx, "SCREEN", "consent notification failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    public static void cancelConsentNotification(Context ctx) {
        try {
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(7411);
        } catch (Exception ignored) {}
    }

    // ---- state / reporting ---------------------------------------------------

    private static void recordInput(Context ctx, String what) {
        Store.put(ctx, Store.K_SCREEN_LAST_INPUT, what);
        Store.put(ctx, Store.K_SCREEN_LAST_INPUT_AT, System.currentTimeMillis());
        BridgeLog.log(ctx, "SCREEN", "input: " + what);
    }

    public static String statusText(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("enabled=").append(Store.getBool(ctx, Store.K_SCREEN_ENABLED, false));
        sb.append(" accessibility=").append(ScreenGestures.accessibilityRunning());
        boolean streaming = ScreenState.streaming
                || Store.getBool(ctx, Store.K_SCREEN_STREAMING, false);
        sb.append(" streaming=").append(streaming);
        long started = Store.getLong(ctx, Store.K_SCREEN_STARTED_AT, 0);
        if (started > 0) sb.append(" started_at=").append(started);
        sb.append(" frames=").append(Store.getLong(ctx, Store.K_SCREEN_FRAMES, 0));
        long lf = Store.getLong(ctx, Store.K_SCREEN_LAST_FRAME_AT, 0);
        if (lf > 0) {
            sb.append(" last_frame_at=").append(lf);
            sb.append(" last_frame=").append(Store.getLong(ctx, Store.K_SCREEN_LAST_W, 0))
              .append("x").append(Store.getLong(ctx, Store.K_SCREEN_LAST_H, 0));
        }
        String err = Store.get(ctx, Store.K_SCREEN_LAST_ERROR, "");
        if (err != null && !err.isEmpty()) sb.append(" last_error=").append(err);
        String li = Store.get(ctx, Store.K_SCREEN_LAST_INPUT, "");
        if (li != null && !li.isEmpty()) sb.append(" last_input=").append(li);
        if (DeviceProfile.isTv(ctx)) sb.append(" tv_profile=true");
        return sb.toString();
    }

    /** Report object: state only — never frame data, never the secret. */
    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("enabled", Store.getBool(ctx, Store.K_SCREEN_ENABLED, false));
            o.put("accessibility", ScreenGestures.accessibilityRunning());
            o.put("streaming", ScreenState.streaming
                    || Store.getBool(ctx, Store.K_SCREEN_STREAMING, false));
            o.put("started_at", Store.getLong(ctx, Store.K_SCREEN_STARTED_AT, 0));
            o.put("frames", Store.getLong(ctx, Store.K_SCREEN_FRAMES, 0));
            o.put("last_frame_at", Store.getLong(ctx, Store.K_SCREEN_LAST_FRAME_AT, 0));
            o.put("last_w", Store.getLong(ctx, Store.K_SCREEN_LAST_W, 0));
            o.put("last_h", Store.getLong(ctx, Store.K_SCREEN_LAST_H, 0));
            String err = Store.get(ctx, Store.K_SCREEN_LAST_ERROR, "");
            if (err != null && !err.isEmpty()) o.put("last_error", err);
            String li = Store.get(ctx, Store.K_SCREEN_LAST_INPUT, "");
            if (li != null && !li.isEmpty()) {
                o.put("last_input", li);
                o.put("last_input_at", Store.getLong(ctx, Store.K_SCREEN_LAST_INPUT_AT, 0));
            }
        } catch (Exception ignored) {}
        return o;
    }

    /** UI text for MainActivity's Screen sharing section. */
    public static String uiState(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("Module enabled: ")
          .append(Store.getBool(ctx, Store.K_SCREEN_ENABLED, false)).append('\n');
        sb.append("Accessibility service: ")
          .append(ScreenGestures.accessibilityRunning() ? "RUNNING" : "not running")
          .append(" (snapshots + takeover need it)\n");
        boolean streaming = ScreenState.streaming
                || Store.getBool(ctx, Store.K_SCREEN_STREAMING, false);
        sb.append("Streaming: ").append(streaming ? "LIVE" : "off").append('\n');
        sb.append("Frames uploaded: ")
          .append(Store.getLong(ctx, Store.K_SCREEN_FRAMES, 0)).append('\n');
        long lf = Store.getLong(ctx, Store.K_SCREEN_LAST_FRAME_AT, 0);
        if (lf > 0) {
            sb.append("Last frame: ")
              .append(Store.getLong(ctx, Store.K_SCREEN_LAST_W, 0)).append("x")
              .append(Store.getLong(ctx, Store.K_SCREEN_LAST_H, 0)).append(" at ")
              .append(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                      .format(new java.util.Date(lf))).append('\n');
        }
        String err = Store.get(ctx, Store.K_SCREEN_LAST_ERROR, "");
        if (err != null && !err.isEmpty()) sb.append("Last error: ").append(err).append('\n');
        return sb.toString();
    }

    private static LoopbackAdb.Result ok(String out, long t0) {
        return new LoopbackAdb.Result(true, out, System.currentTimeMillis() - t0, null);
    }

    private static LoopbackAdb.Result refuse(String why, long t0) {
        return new LoopbackAdb.Result(false, "", System.currentTimeMillis() - t0, why);
    }
}
