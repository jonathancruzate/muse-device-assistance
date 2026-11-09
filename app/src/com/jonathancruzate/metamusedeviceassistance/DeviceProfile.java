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
 */
public final class DeviceProfile {
    private static Boolean cachedTv = null;

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

    public static boolean isPhone(Context ctx) { return !isTv(ctx); }

    /** Worker "device" wire string for this instance. */
    public static String deviceString(Context ctx) {
        return isTv(ctx) ? "shield-bridge" : "phone-bridge";
    }

    public static String profileName(Context ctx) {
        return isTv(ctx) ? "tv" : "phone";
    }

    public static String describe(Context ctx) {
        return profileName(ctx) + " (device=" + deviceString(ctx)
                + ", model=" + Build.MODEL + ", sdk=" + Build.VERSION.SDK_INT + ")";
    }
}
