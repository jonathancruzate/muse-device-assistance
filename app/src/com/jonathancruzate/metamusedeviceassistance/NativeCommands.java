package com.jonathancruzate.metamusedeviceassistance;

import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * NativeCommands — the v1.5 extension pack's on-device executor for
 * the verbs that have no shell form on this Samsung build (there is
 * no shell torch service: `cmd -l` shows media.camera but nothing a
 * shell client can drive to a torch state). Reached only through
 * ShellAllowlist.checkNative's fixed op set, from ShellBridge.
 *
 * Flashlight: CameraManager.setTorchMode on the back camera with a
 * flash unit. Ground truth is the system's own TorchCallback —
 * onTorchModeChanged fires for this app's changes too — persisted
 * to Store so flashlight_status and the report can read it back.
 */
public final class NativeCommands {
    private NativeCommands() {}

    private static String torchCameraId;
    private static boolean callbackRegistered;

    private static synchronized String torchCameraId(Context ctx) throws Exception {
        if (torchCameraId != null) return torchCameraId;
        CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
        String fallback = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics cc = cm.getCameraCharacteristics(id);
            Boolean flash = cc.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (flash == null || !flash) continue;
            Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                torchCameraId = id;
                return torchCameraId;
            }
            if (fallback == null) fallback = id;
        }
        torchCameraId = fallback;
        return torchCameraId;
    }

    private static synchronized void ensureCallback(Context ctx) {
        if (callbackRegistered) return;
        try {
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            final Context appCtx = ctx.getApplicationContext();
            cm.registerTorchCallback(new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    try {
                        if (cameraId.equals(torchCameraId(appCtx))) {
                            Store.put(appCtx, Store.K_TORCH_ON, enabled);
                            Store.put(appCtx, Store.K_TORCH_AT, System.currentTimeMillis());
                            BridgeLog.log(appCtx, "SHELL",
                                    "torch callback: " + (enabled ? "ON" : "OFF"));
                        }
                    } catch (Exception ignored) {}
                }

                @Override
                public void onTorchModeUnavailable(String cameraId) {
                    BridgeLog.log(appCtx, "SHELL", "torch unavailable: " + cameraId);
                }
            }, null);
            callbackRegistered = true;
        } catch (Exception e) {
            BridgeLog.log(ctx, "SHELL", "torch callback register failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** Execute one gated native op. Never throws. */
    public static LoopbackAdb.Result execute(Context ctx, String op) {
        return execute(ctx, op, null);
    }

    /**
     * Execute one gated native op with its validated single arg
     * (v1.27: app_launch's package, nav_open's destination token,
     * media_vol_set's level). Never throws.
     */
    public static LoopbackAdb.Result execute(Context ctx, String op, String arg) {
        long t0 = System.currentTimeMillis();
        try {
            switch (op) {
                // ---- v1.27 nativized intent-shaped ops ----
                case "app_launch":
                    return launchApp(ctx, arg, t0);
                case "nav_open":
                    return openNavigation(ctx, arg, t0);
                case "link_open":
                    return openLink(ctx, arg, t0);
                case "media_play_pause":
                    return mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, t0);
                case "media_next":
                    return mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT, t0);
                case "media_previous":
                    return mediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS, t0);
                case "media_vol_raise":
                    return mediaVolume(ctx, AudioManager.ADJUST_RAISE, -1, t0);
                case "media_vol_lower":
                    return mediaVolume(ctx, AudioManager.ADJUST_LOWER, -1, t0);
                case "media_vol_set":
                    return mediaVolume(ctx, 0, Integer.parseInt(arg), t0);
                case "media_vol_get":
                    return mediaVolume(ctx, 0, -2, t0);
                case "screen_foreground":
                    return foreground(ctx, t0);
                case "dnd_on":
                    return setDnd(ctx, true, t0);
                case "dnd_off":
                    return setDnd(ctx, false, t0);
                // Duo ops (duo_debug_*, duo_panels_get) are NOT
                // here since v1.31.0: ShellBridge routes every
                // duo_* op to the Duo Companion plugin via
                // DuoRouter before this executor is reached.
                case "torch_on":
                    return setTorch(ctx, true, t0);
                case "torch_off":
                    return setTorch(ctx, false, t0);
                case "torch_status": {
                    ensureCallback(ctx);
                    long at = Store.getLong(ctx, Store.K_TORCH_AT, 0);
                    if (at == 0) {
                        return new LoopbackAdb.Result(true,
                                "torch state unknown (no callback observed yet)",
                                System.currentTimeMillis() - t0, null);
                    }
                    boolean on = Store.getBool(ctx, Store.K_TORCH_ON, false);
                    return new LoopbackAdb.Result(true,
                            "torch=" + (on ? "on" : "off"),
                            System.currentTimeMillis() - t0, null);
                }
                default:
                    return new LoopbackAdb.Result(false, "",
                            System.currentTimeMillis() - t0, "unknown native op");
            }
        } catch (Exception e) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /**
     * v1.27: Do Not Disturb via NotificationManager — the native
     * form of `cmd notification set_dnd on|off` (zen IMPORTANT
     * INTERRUPTIONS == INTERRUPTION_FILTER_PRIORITY). HONEST GATE:
     * setInterruptionFilter needs the Notification Policy Access
     * grant, which Jonathan has not given this app. Without it the
     * op refuses with the typed policy_access_not_granted error
     * (never a false ok) and ShellBridge falls back to the shell
     * form when the ADB lane is ready; the grant state is visible
     * in the report snapshot as shell_bridge.dnd_policy_access.
     * With the grant, the filter is set and read back before ok.
     */
    private static LoopbackAdb.Result setDnd(Context ctx, boolean on, long t0) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(
                Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0, "no notification service");
        }
        if (!nm.isNotificationPolicyAccessGranted()) {
            BridgeLog.log(ctx, "SHELL", "dnd native refused:"
                    + " notification policy access not granted");
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "policy_access_not_granted: Muse Device Assistance"
                            + " lacks Notification Policy Access");
        }
        int want = on ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                : NotificationManager.INTERRUPTION_FILTER_ALL;
        nm.setInterruptionFilter(want);
        int cur = nm.getCurrentInterruptionFilter();
        BridgeLog.log(ctx, "SHELL", "dnd native set on=" + on
                + " filter now=" + cur);
        return new LoopbackAdb.Result(cur == want,
                "dnd=" + (on ? "on" : "off") + " filter=" + cur,
                System.currentTimeMillis() - t0,
                cur == want ? null : "dnd_filter_mismatch: got " + cur);
    }

    private static LoopbackAdb.Result setTorch(Context ctx, boolean on, long t0) {
        try {
            ensureCallback(ctx);
            String id = torchCameraId(ctx);
            if (id == null) {
                return new LoopbackAdb.Result(false, "",
                        System.currentTimeMillis() - t0, "no flash camera");
            }
            CameraManager cm = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
            cm.setTorchMode(id, on);
            // setTorchMode is synchronous about acceptance; the
            // callback is the state truth and lands within ms. Give
            // it a brief beat, then report from the callback record.
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            boolean seen = Store.getLong(ctx, Store.K_TORCH_AT, 0) > 0
                    && Store.getBool(ctx, Store.K_TORCH_ON, !on) == on;
            BridgeLog.log(ctx, "SHELL", "torch set " + (on ? "ON" : "OFF")
                    + " camera=" + id + " callback-confirmed=" + seen);
            return new LoopbackAdb.Result(true,
                    "torch=" + (on ? "on" : "off")
                            + (seen ? " (callback-confirmed)" : " (set accepted)"),
                    System.currentTimeMillis() - t0, null);
        } catch (Exception e) {
            BridgeLog.log(ctx, "SHELL", "torch set failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    // ---- v1.27 nativized intent-shaped ops ---------------------------
    // (docs/lag-diagnosis-2026-11-18.md). Direct framework calls —
    // no ADB, no shell, no network dependency. Launches are issued
    // from the accessibility-service context (the component type
    // Android exempts from background-activity-launch restrictions)
    // and are VERIFIED against the live foreground before reporting
    // ok: a denied launch returns the typed bal_blocked error,
    // never a false ok (the verified-ack discipline).

    private static final String MAPS_PKG = "com.google.android.apps.maps";

    /** The live foreground package, unfiltered, via the
     *  accessibility service; null when the service is not running
     *  or nothing is observable. */
    private static String foregroundNow() {
        VoiceAccessibilityService svc = VoiceAccessibilityService.instance;
        return svc == null ? null : svc.foregroundPackageRaw();
    }

    private static LoopbackAdb.Result launchApp(Context ctx, String pkg, long t0) {
        if (pkg == null || pkg.isEmpty()) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0, "needs <pkg>");
        }
        Intent launch = null;
        try {
            launch = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        } catch (Exception ignored) {}
        if (launch == null) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "app_not_launchable: no launch intent for " + pkg);
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return launchAndVerify(ctx, launch, pkg, "app_launch " + pkg, t0);
    }

    private static LoopbackAdb.Result openNavigation(Context ctx, String destToken, long t0) {
        if (destToken == null || destToken.isEmpty()) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0, "needs <destination-token>");
        }
        // The exact intent the proven shell form built (execution
        // record 2026-11-08 (h)): VIEW + google.navigation:q=<pct-
        // encoded token>, Maps package pinned.
        Intent nav = new Intent(Intent.ACTION_VIEW);
        nav.setData(Uri.parse("google.navigation:q="
                + ShellAllowlist.pctEncodeDest(destToken)));
        nav.setPackage(MAPS_PKG);
        nav.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return launchAndVerify(ctx, nav, MAPS_PKG, "nav_open " + destToken, t0);
    }

    // ---- v1.28 link_open ------------------------------------------
    // Browser packages accepted as the landing surface for every
    // meeting host (a join link with no native app installed, and
    // the portal hosts — Healthie / Doxy — are browser surfaces by
    // design).
    private static final String CHROME_PKG = "com.android.chrome";
    private static final String SBROWSER_PKG = "com.sec.android.app.sbrowser";

    /**
     * The expected foreground packages for a meeting host: the
     * host's native meeting app (when one exists) plus the
     * browsers. Mirrors ShellAllowlist's meeting-host allowlist —
     * the gate has already refused any other host, so the default
     * branch is unreachable in gated flow and returns browsers
     * only.
     */
    private static java.util.Set<String> expectedPkgsForHost(String host) {
        java.util.Set<String> s = new java.util.HashSet<>();
        s.add(CHROME_PKG);
        s.add(SBROWSER_PKG);
        if (host == null) return s;
        String h = host.toLowerCase(java.util.Locale.US);
        if (h.equals("zoom.us") || h.endsWith(".zoom.us")) {
            s.add("us.zoom.videomeetings");
        } else if (h.equals("meet.google.com")) {
            s.add("com.google.android.apps.meetings");
            s.add("com.google.android.apps.tachyon");
        } else if (h.equals("teams.microsoft.com") || h.equals("teams.live.com")) {
            s.add("com.microsoft.teams");
        } else if (h.equals("webex.com") || h.endsWith(".webex.com")) {
            s.add("com.cisco.webex.meetings");
        } else if (h.equals("teladoc.com") || h.endsWith(".teladoc.com")) {
            s.add("com.teladoc.members");
        } else if (h.equals("amwell.com") || h.endsWith(".amwell.com")) {
            s.add("com.americanwell.android");
        }
        // gethealthie.com / doxy.me: portal surfaces, browsers only.
        return s;
    }

    /**
     * v1.28: open a validated meeting join URL (ShellAllowlist
     * checkLinkOpen is the only way in: https, meeting allowlist,
     * no userinfo, <=512 chars) with an ACTION_VIEW intent issued
     * from the accessibility-service context — the nav_open /
     * app_launch discipline. No package is pinned: the system
     * resolves the link to the meeting app when installed, else
     * the default browser. Success is VERIFIED: the live
     * foreground must become one of the host's expected packages
     * (native app or a browser) within ~2.5 s (meeting apps are
     * slower to foreground than Maps); a chooser/resolver window
     * is transient and keeps the poll waiting. Non-arrival is the
     * typed bal_blocked error, never a false ok — and there is NO
     * shell fallback for this verb (no shell form exists).
     */
    private static LoopbackAdb.Result openLink(Context ctx, String url, long t0) {
        if (url == null || url.isEmpty()) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0, "needs <url>");
        }
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        java.util.Set<String> expected = expectedPkgsForHost(host);
        Intent view = new Intent(Intent.ACTION_VIEW, uri);
        view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        VoiceAccessibilityService svc = VoiceAccessibilityService.instance;
        Context launchCtx = svc != null ? svc : ctx;
        try {
            launchCtx.startActivity(view);
        } catch (Exception e) {
            BridgeLog.log(ctx, "SHELL", "link_open startActivity threw: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "bal_blocked: startActivity threw "
                            + e.getClass().getSimpleName());
        }
        if (svc == null) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "bal_unverified: accessibility service unavailable");
        }
        String lastFg = null;
        long deadline = System.currentTimeMillis() + 2500;
        while (System.currentTimeMillis() < deadline) {
            lastFg = foregroundNow();
            if (lastFg != null && expected.contains(lastFg)) {
                Store.put(ctx, Store.K_FG_PACKAGE, lastFg);
                Store.put(ctx, Store.K_FG_AT, System.currentTimeMillis());
                BridgeLog.log(ctx, "SHELL", "link_open verified host=" + host
                        + " foreground=" + lastFg + " in "
                        + (System.currentTimeMillis() - t0) + "ms");
                return new LoopbackAdb.Result(true,
                        "foreground=" + lastFg + " host=" + host,
                        System.currentTimeMillis() - t0, null);
            }
            try { Thread.sleep(150); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        BridgeLog.log(ctx, "SHELL", "link_open NOT verified: host=" + host
                + " foreground=" + lastFg + " expected one of " + expected);
        return new LoopbackAdb.Result(false, "",
                System.currentTimeMillis() - t0,
                "bal_blocked: foreground did not become a meeting app/browser"
                        + " for " + host + " (stayed " + lastFg + ")");
    }

    /**
     * Issue the launch from the accessibility-service context and
     * poll the live foreground for the target package (up to ~1.6 s).
     * Success only when the target is actually foreground; any
     * denial or non-arrival is the typed bal_blocked error, which
     * ShellBridge may answer with the shell fallback when the ADB
     * lane is up.
     */
    private static LoopbackAdb.Result launchAndVerify(Context ctx, Intent intent,
                                                      String expectedPkg, String label,
                                                      long t0) {
        VoiceAccessibilityService svc = VoiceAccessibilityService.instance;
        Context launchCtx = svc != null ? svc : ctx;
        try {
            launchCtx.startActivity(intent);
        } catch (Exception e) {
            BridgeLog.log(ctx, "SHELL", label + " startActivity threw: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "bal_blocked: startActivity threw "
                            + e.getClass().getSimpleName());
        }
        if (svc == null) {
            // The launch was attempted from the app context, which
            // Android 10+ BAL rules will normally have denied, and
            // there is no observer to prove otherwise. Refuse to
            // claim success (verified-ack discipline).
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0,
                    "bal_unverified: accessibility service unavailable");
        }
        String lastFg = null;
        long deadline = System.currentTimeMillis() + 1600;
        while (System.currentTimeMillis() < deadline) {
            lastFg = foregroundNow();
            if (expectedPkg.equals(lastFg)) {
                Store.put(ctx, Store.K_FG_PACKAGE, lastFg);
                Store.put(ctx, Store.K_FG_AT, System.currentTimeMillis());
                BridgeLog.log(ctx, "SHELL", label + " verified foreground="
                        + lastFg + " in " + (System.currentTimeMillis() - t0) + "ms");
                return new LoopbackAdb.Result(true, "foreground=" + lastFg,
                        System.currentTimeMillis() - t0, null);
            }
            try { Thread.sleep(150); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        BridgeLog.log(ctx, "SHELL", label + " NOT verified: foreground="
                + lastFg + " expected=" + expectedPkg);
        return new LoopbackAdb.Result(false, "",
                System.currentTimeMillis() - t0,
                "bal_blocked: foreground did not become " + expectedPkg
                        + " (stayed " + lastFg + ")");
    }

    private static LoopbackAdb.Result mediaKey(Context ctx, int keycode, long t0) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0, "no audio service");
        }
        long now = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(now, now,
                KeyEvent.ACTION_DOWN, keycode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(now, now,
                KeyEvent.ACTION_UP, keycode, 0));
        return new LoopbackAdb.Result(true, "media_key=" + keycode,
                System.currentTimeMillis() - t0, null);
    }

    /** setLevel >= 0: absolute set; -2: read only; otherwise adjust
     *  by direction (AudioManager.ADJUST_RAISE / ADJUST_LOWER). */
    private static LoopbackAdb.Result mediaVolume(Context ctx, int direction,
                                                  int setLevel, long t0) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) {
            return new LoopbackAdb.Result(false, "",
                    System.currentTimeMillis() - t0, "no audio service");
        }
        if (setLevel >= 0) {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, setLevel, 0);
        } else if (setLevel != -2) {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
        }
        int cur = am.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        return new LoopbackAdb.Result(true, "volume=" + cur + " max=" + max,
                System.currentTimeMillis() - t0, null);
    }

    private static LoopbackAdb.Result foreground(Context ctx, long t0) {
        String pkg = Store.get(ctx, Store.K_FG_PACKAGE, "");
        long at = Store.getLong(ctx, Store.K_FG_AT, 0);
        if (!pkg.isEmpty()) {
            return new LoopbackAdb.Result(true,
                    "foreground_package=" + pkg + " at=" + at,
                    System.currentTimeMillis() - t0, null);
        }
        // Cache cold (no window event observed since install):
        // one live read through the accessibility service.
        String live = foregroundNow();
        if (live != null) {
            return new LoopbackAdb.Result(true, "foreground_package=" + live,
                    System.currentTimeMillis() - t0, null);
        }
        return new LoopbackAdb.Result(false, "",
                System.currentTimeMillis() - t0,
                "foreground_unknown: no window event observed yet");
    }
}
