package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;

/**
 * ScreenGestures — the v1.25 screen-sharing module's hands and
 * snapshot eyes, as one static facade over VoiceAccessibilityService
 * (the app's already-granted accessibility service; design:
 * docs/v1.25-screen-sharing-design-2026-11-13.md).
 *
 * Coordinates arriving from any lane are NORMALIZED 0..1000 on both
 * axes (the visual-grounding convention) and scaled here to real
 * pixels from the live display metrics, so a caller never needs the
 * phone's resolution and a fold/rotate change cannot strand a tap.
 *
 * Every method fails typed (a String reason or null bitmap), never
 * throws: callers (ScreenRouter on the bridge thread, the capture
 * service's input pump) turn the reason into a command result /
 * BridgeLog line. The accessibility service does nothing at all
 * unless one of these is called — no new always-on behavior.
 */
public final class ScreenGestures {
    private ScreenGestures() {}

    /** Max long edge for a snapshot JPEG (streams run smaller: the
     *  capture service scales to 720). */
    public static final int SHOT_MAX_EDGE = 1080;
    public static final int SHOT_QUALITY = 68;

    public static VoiceAccessibilityService service() {
        return VoiceAccessibilityService.instance;
    }

    public static boolean accessibilityRunning() {
        return VoiceAccessibilityService.instance != null;
    }

    /** Display size in pixels from the service context (falls back
     *  to the app context's metrics). Returns {w, h} or null. */
    public static int[] displaySize(Context ctx) {
        try {
            Context c = VoiceAccessibilityService.instance != null
                    ? VoiceAccessibilityService.instance : ctx;
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            if (dm.widthPixels > 0 && dm.heightPixels > 0) {
                return new int[]{dm.widthPixels, dm.heightPixels};
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Normalized 0..1000 -> pixel, clamped inside the display. */
    static int toPx(int norm, int size) {
        if (size <= 1) return 0;
        int px = (int) Math.round(norm / 1000.0 * (size - 1));
        if (px < 0) px = 0;
        if (px > size - 1) px = size - 1;
        return px;
    }

    /** One tap at normalized coords. Returns null on success. */
    public static String tap(Context ctx, int nx, int ny) {
        VoiceAccessibilityService s = service();
        if (s == null) return "accessibility_not_running";
        int[] d = displaySize(ctx);
        if (d == null) return "no_display_metrics";
        boolean ok = s.screenTapPx(toPx(nx, d[0]), toPx(ny, d[1]));
        return ok ? null : "gesture_refused";
    }

    /** One stroke between normalized coords. Returns null on success. */
    public static String swipe(Context ctx, int nx1, int ny1, int nx2, int ny2, int ms) {
        VoiceAccessibilityService s = service();
        if (s == null) return "accessibility_not_running";
        int[] d = displaySize(ctx);
        if (d == null) return "no_display_metrics";
        boolean ok = s.screenSwipePx(toPx(nx1, d[0]), toPx(ny1, d[1]),
                toPx(nx2, d[0]), toPx(ny2, d[1]), ms);
        return ok ? null : "gesture_refused";
    }

    /** A global action by wire name (gate-validated vocabulary).
     *  Returns null on success. */
    public static String key(Context ctx, String name) {
        VoiceAccessibilityService s = service();
        if (s == null) return "accessibility_not_running";
        boolean ok = s.screenGlobal(name);
        return ok ? null : "global_action_refused";
    }

    /** Type text into the focused editable node. Returns null on
     *  success. */
    public static String typeText(Context ctx, String text) {
        VoiceAccessibilityService s = service();
        if (s == null) return "accessibility_not_running";
        return s.screenTypeText(text);
    }

    /** Tap the node whose text or content-description exactly matches
     *  the label (case-insensitive), walking up to its clickable
     *  parent. Returns null on success. */
    public static String findTap(Context ctx, String label) {
        VoiceAccessibilityService s = service();
        if (s == null) return "accessibility_not_running";
        boolean ok = s.screenFindTap(label);
        return ok ? null : "label_not_found";
    }

    /** A captured frame, ready to upload. */
    public static final class Frame {
        public final byte[] jpeg;
        public final int w;
        public final int h;
        Frame(byte[] jpeg, int w, int h) { this.jpeg = jpeg; this.w = w; this.h = h; }
    }

    /**
     * Snapshot via the accessibility service's takeScreenshot,
     * scaled to SHOT_MAX_EDGE and JPEG-encoded. Returns null when
     * the service is down or the platform refuses (e.g. a secure
     * window is foreground — FLAG_SECURE content captures black /
     * fails, which is the platform's rule, reported as no frame).
     */
    public static Frame snapshot(Context ctx) {
        VoiceAccessibilityService s = service();
        if (s == null) return null;
        Bitmap bmp = s.screenCaptureBitmap();
        if (bmp == null) return null;
        try {
            Bitmap scaled = scaleDown(bmp, SHOT_MAX_EDGE);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, SHOT_QUALITY, out);
            Frame f = new Frame(out.toByteArray(), scaled.getWidth(), scaled.getHeight());
            if (scaled != bmp) scaled.recycle();
            bmp.recycle();
            return f;
        } catch (Exception e) {
            try { bmp.recycle(); } catch (Exception ignored) {}
            return null;
        }
    }

    /** Scale so the long edge is at most maxEdge; returns the input
     *  unchanged when it already fits. */
    public static Bitmap scaleDown(Bitmap bmp, int maxEdge) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        int longEdge = Math.max(w, h);
        if (longEdge <= maxEdge) return bmp;
        double k = maxEdge / (double) longEdge;
        int nw = Math.max(2, (int) Math.round(w * k));
        int nh = Math.max(2, (int) Math.round(h * k));
        return Bitmap.createScaledBitmap(bmp, nw, nh, true);
    }
}
