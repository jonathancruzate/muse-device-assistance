package com.jonathancruzate.metamusedeviceassistance;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Build;

/**
 * Device profile — TV vs phone (LEG A, ratified 2026-11-08 10:10 EST:
 * ONE Muse Device Assistance app, runtime Shield detection + TV
 * profile; no separate Shield TV app).
 *
 * Detection is cheap and deterministic, checked in order:
 *   1. UiModeManager current mode type == UI_MODE_TYPE_TELEVISION
 *   2. PackageManager system feature FEATURE_LEANBACK
 *   3. PackageManager system feature FEATURE_TELEVISION (older TV)
 * Any one hit means TV. The result is cached per-process; a device
 * never changes form factor at runtime.
 *
 * Profile differences (all fenced behind isTv() so the live phone
 * build cannot regress):
 *   - Reports carry device="shield-bridge" and profile="tv" so the
 *     Worker stores them under shieldbridge:report, never the phone's
 *     phonebridge:report.
 *   - BridgeService auto-starts (MainActivity + BootReceiver), no
 *     "Start bridge" tap needed.
 *   - ADB heal is a no-op-with-logging (see AdbBridge.tickTv).
 *   - Phone-only surfaces are gated off: Samsung mode ground truth /
 *     reconciler verdicts, the phone status set, desired-state fetch,
 *     and Phase 2 actuation (which stays DISARMED everywhere anyway).
 *   - The loopback shell bridge is gated off on TV in v1.5.0: Shield
 *     debugging is LAN ADB (:5555), not the phone's wireless-debugging
 *     pairing flow — a TV shell lane is a separate design if wanted.
 *
 * v1.26 DUO profile (2026-11-14): a third profile for Jonathan's
 * Surface Duo 1 running the DUO-DE Android 16 GSI — the "Duo
 * companion" (design: docs/v1.26-duo-companion-2026-11-14.md).
 * Detection: Build.DEVICE / Build.PRODUCT containing "surfaceduo"
 * (the Duo's stock + GSI props: product surfaceduo, verified from
 * the device's pre-flash capture) or Build.MODEL containing
 * "surface duo". Checked AFTER TV (a Duo is never a TV) and cached
 * the same way. All Duo behavior is fenced behind isDuo() so the
 * phone and TV builds cannot regress:
 *   - Reports carry device="duo-bridge" and profile="duo"; the
 *     relay stores them under duobridge:report / duobridge:* command
 *     keys, never the phone's (the shield-bridge precedent).
 *   - Phone-only surfaces are gated off exactly as on TV (Samsung
 *     mode ground truth / reconciler / house-mode desired fetch /
 *     push channels) — there is no Samsung Modes app on the GSI.
 *   - The loopback shell bridge STAYS ON (unlike TV): the Duo's
 *     wireless debugging is the same AOSP pairing flow as the
 *     phone's, and the bridge is how the wake ritual ensures the
 *     two-panel prop and how Muse drives the Duo's screen.*
 *     verbs.
 *   - BridgeService auto-starts like TV (a station device comes up
 *     armed after boot / app open).
 *   - The Duo feature set (wake ritual, debug mode, posture)
 *     runs in the Muse Duo Companion plugin since v1.31.0;
 *     this core keeps only the Duo detection + duo-bridge wire
 *     identity and gates the phone surfaces off.
 */
public final class DeviceProfile {
    private static Boolean cachedTv = null;
    private static Boolean cachedDuo = null;

    private DeviceProfile() {}

    public static synchronized boolean isTv(Context ctx) {
        if (cachedTv != null) return cachedTv;
        boolean tv = false;
        try {
            UiModeManager um = (UiModeManager) ctx.getSystemService(Context.UI_MODE_SERVICE);
            if (um != null && um.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
                tv = true;
            }
        } catch (Exception ignored) {}
        if (!tv) {
            try {
                PackageManager pm = ctx.getPackageManager();
                if (pm.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                        || pm.hasSystemFeature("android.hardware.type.television")) {
                    tv = true;
                }
            } catch (Exception ignored) {}
        }
        cachedTv = tv;
        return tv;
    }

    /** Surface Duo profile (v1.26). Never true on the phone or the
     *  Shield: the check is the Duo's own build identity, which
     *  neither device can present. */
    public static synchronized boolean isDuo(Context ctx) {
        if (cachedDuo != null) return cachedDuo;
        boolean duo = false;
        if (!isTv(ctx)) {
            try {
                String device = Build.DEVICE == null ? "" : Build.DEVICE.toLowerCase();
                String product = Build.PRODUCT == null ? "" : Build.PRODUCT.toLowerCase();
                String model = Build.MODEL == null ? "" : Build.MODEL.toLowerCase();
                duo = device.contains("surfaceduo")
                        || product.contains("surfaceduo")
                        || model.contains("surface duo");
            } catch (Exception ignored) {}
        }
        cachedDuo = duo;
        return duo;
    }

    public static boolean isPhone(Context ctx) { return !isTv(ctx); }

    /** Worker "device" wire string for this instance. */
    public static String deviceString(Context ctx) {
        if (isTv(ctx)) return "shield-bridge";
        if (isDuo(ctx)) return "duo-bridge";
        return "phone-bridge";
    }

    public static String profileName(Context ctx) {
        if (isTv(ctx)) return "tv";
        if (isDuo(ctx)) return "duo";
        return "phone";
    }

    public static String describe(Context ctx) {
        return profileName(ctx) + " (device=" + deviceString(ctx)
                + ", model=" + Build.MODEL + ", sdk=" + Build.VERSION.SDK_INT + ")";
    }
}
