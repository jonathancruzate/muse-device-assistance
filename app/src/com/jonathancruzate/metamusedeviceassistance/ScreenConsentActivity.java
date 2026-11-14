package com.jonathancruzate.metamusedeviceassistance;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/**
 * ScreenConsentActivity — the v1.25 trampoline that shows Android's
 * system screen-capture consent dialog (design:
 * docs/v1.25-screen-sharing-design-2026-11-13.md).
 *
 * The dialog is the platform's per-session gate for MediaProjection
 * and cannot be bypassed by any app; this activity is the smallest
 * honest surface for it. Entry points: MainActivity's "Start screen
 * sharing" button, the consent notification ScreenRouter posts on a
 * remote screen_start, and that command's direct launch attempt.
 *
 * On approval the grant is handed to ScreenCaptureService through
 * ScreenState (memory only, single use, never persisted) and the
 * service is started as a mediaProjection foreground service. On
 * denial the refusal is logged loudly and stored as the module's
 * last error — a denied consent is a state a caller can read, not a
 * silent no-op.
 */
public class ScreenConsentActivity extends Activity {

    private static final int REQ_PROJECTION = 7412;

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, ScreenConsentActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (ScreenState.streaming) {
            finish();
            return;
        }
        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (mpm == null) {
                fail("no_projection_manager");
                return;
            }
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
        } catch (Exception e) {
            fail("consent_launch_failed:" + e.getClass().getSimpleName());
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) {
            finish();
            return;
        }
        if (resultCode == RESULT_OK && data != null) {
            ScreenState.consentResultCode = resultCode;
            ScreenState.consentData = data;
            ScreenState.stopRequested = false;
            try {
                Intent svc = new Intent(this, ScreenCaptureService.class);
                startForegroundService(svc);
                BridgeLog.log(this, "SCREEN",
                        "consent granted — capture service starting");
            } catch (Exception e) {
                ScreenState.clearConsent();
                fail("service_start_failed:" + e.getClass().getSimpleName());
                return;
            }
            ScreenRouter.cancelConsentNotification(this);
        } else {
            fail("consent_denied");
        }
        finish();
    }

    private void fail(String why) {
        Store.put(this, Store.K_SCREEN_LAST_ERROR, why);
        BridgeLog.log(this, "SCREEN", "screen consent: " + why);
        finish();
    }
}
