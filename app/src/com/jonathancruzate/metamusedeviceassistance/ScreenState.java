package com.jonathancruzate.metamusedeviceassistance;

import android.content.Intent;

/**
 * ScreenState — transient state for the v1.25 screen-sharing module
 * (design: docs/v1.25-screen-sharing-design-2026-11-13.md).
 *
 * The MediaProjection consent grant lives HERE and nowhere else: an
 * in-memory result code + the result Intent the system's consent
 * dialog handed ScreenConsentActivity. It is never persisted (a
 * stored grant would be both a platform violation in spirit and
 * useless — Android re-consents every session) and never logged.
 * Process death clears it, which fails safe: the next screen_start
 * simply asks for consent again.
 *
 * Persisted bookkeeping (enabled belief, frame counters, last error)
 * lives in Store, like every other module.
 */
public final class ScreenState {
    private ScreenState() {}

    /** True while ScreenCaptureService believes it is streaming. The
     *  service sets and clears it; Store.K_SCREEN_STREAMING mirrors
     *  it for reports/UI across process restarts of other components. */
    public static volatile boolean streaming = false;

    /** Set by screen_stop (any lane) and the UI; the capture loop
     *  checks it every tick and tears down. */
    public static volatile boolean stopRequested = false;

    /** Consent grant, in-memory only (see class doc). */
    public static volatile int consentResultCode = 0;
    public static volatile Intent consentData = null;

    /** Wall-clock the current stream session started (0 = none). */
    public static volatile long sessionStartedAt = 0;

    /** Frames uploaded in the current session (report/UI color). */
    public static volatile long sessionFrames = 0;

    public static void clearConsent() {
        consentResultCode = 0;
        consentData = null;
    }
}
