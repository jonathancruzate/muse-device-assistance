package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

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
        long t0 = System.currentTimeMillis();
        try {
            switch (op) {
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
}
