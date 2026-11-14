package com.jonathancruzate.metamusedeviceassistance;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.WindowManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ScreenCaptureService — the v1.25 streaming engine (design:
 * docs/v1.25-screen-sharing-design-2026-11-13.md).
 *
 * A mediaProjection foreground service (the Android 14+ shape):
 * started only by ScreenConsentActivity after the system's consent
 * dialog, it mirrors the display into an ImageReader at reduced
 * resolution (long edge ≤ 720), encodes ~4 frames/sec as JPEG q55,
 * and uploads them to the configured relay base via
 * ScreenTransport. While streaming it also pumps the relay's fast
 * input queue (~750 ms cadence) through ScreenRouter's
 * re-validating dispatcher, so the live viewer is interactive
 * instead of waiting out the 60 s bridge cycle.
 *
 * Bounds: a session auto-stops after 10 minutes; the persistent
 * notification carries a Stop action and the platform shows its
 * own projection indicator for the whole session; frames are
 * never written to device storage; a stop from any lane (command,
 * notification, UI, projection callback, timeout) tears everything
 * down and posts the stopped state.
 */
public class ScreenCaptureService extends Service {

    public static final String ACTION_STOP =
            "com.jonathancruzate.metamusedeviceassistance.SCREEN_STOP";

    private static final String CHANNEL = "screen";
    private static final int NOTIF_ID = 2;
    private static final long TICK_MS = 250;
    private static final long MAX_SESSION_MS = 10 * 60_000;
    private static final int STREAM_MAX_EDGE = 720;
    private static final int JPEG_QUALITY = 55;
    /** Upload backlog bound: if posts fall behind, frames drop —
     *  a stale frame is worse than a skipped one for control. */
    private static final int MAX_PENDING_POSTS = 2;

    private HandlerThread thread;
    private Handler handler;
    private ExecutorService net;
    private final AtomicInteger pendingPosts = new AtomicInteger(0);

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private int frameW;
    private int frameH;
    private int tickCount;
    private boolean tornDown;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            BridgeLog.log(this, "SCREEN", "stop requested from notification");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ScreenState.streaming) return START_STICKY;

        createChannel();
        try {
            startForeground(NOTIF_ID, buildNotification("starting"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } catch (RuntimeException e) {
            BridgeLog.log(this, "SCREEN", "startForeground(mediaProjection) refused: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            Store.put(this, Store.K_SCREEN_LAST_ERROR,
                    "fgs_refused:" + e.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = ScreenState.consentResultCode;
        android.content.Intent data = ScreenState.consentData;
        if (data == null) {
            BridgeLog.log(this, "SCREEN", "capture start without consent grant — stopping");
            Store.put(this, Store.K_SCREEN_LAST_ERROR, "no_consent_grant");
            stopSelf();
            return START_NOT_STICKY;
        }
        ScreenState.clearConsent(); // the grant is single-use; it is consumed below

        thread = new HandlerThread("screen-capture");
        thread.start();
        handler = new Handler(thread.getLooper());
        net = Executors.newSingleThreadExecutor();

        try {
            MediaProjectionManager mpm = (MediaProjectionManager)
                    getSystemService(MEDIA_PROJECTION_SERVICE);
            projection = mpm.getMediaProjection(resultCode, data);
            // Android 14+: a callback must be registered before the
            // virtual display is created.
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    BridgeLog.log(ScreenCaptureService.this, "SCREEN",
                            "projection stopped by system — ending session");
                    stopSelf();
                }
            }, handler);

            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            Display display = wm.getDefaultDisplay();
            DisplayMetrics dm = new DisplayMetrics();
            display.getRealMetrics(dm);
            int[] size = scaledSize(dm.widthPixels, dm.heightPixels);
            frameW = size[0];
            frameH = size[1];

            reader = ImageReader.newInstance(frameW, frameH, PixelFormat.RGBA_8888, 2);
            virtualDisplay = projection.createVirtualDisplay(
                    "muse-screen", frameW, frameH, dm.densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
        } catch (Exception e) {
            BridgeLog.log(this, "SCREEN", "capture setup failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            Store.put(this, Store.K_SCREEN_LAST_ERROR,
                    "setup_failed:" + e.getClass().getSimpleName());
            stopSelf();
            return START_NOT_STICKY;
        }

        long now = System.currentTimeMillis();
        ScreenState.streaming = true;
        ScreenState.sessionStartedAt = now;
        ScreenState.sessionFrames = 0;
        Store.put(this, Store.K_SCREEN_STREAMING, true);
        Store.put(this, Store.K_SCREEN_STARTED_AT, now);
        Store.put(this, Store.K_SCREEN_LAST_ERROR, "");
        BridgeLog.log(this, "SCREEN", "streaming started " + frameW + "x" + frameH);
        updateNotification("Muse is viewing this screen");
        final android.content.Context appCtx = getApplicationContext();
        if (net != null) {
            net.execute(() -> ScreenTransport.postState(appCtx, true, 0,
                    ScreenState.sessionStartedAt, null));
        }
        handler.post(tick);
        return START_STICKY;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (tornDown) return;
            long elapsed = System.currentTimeMillis() - ScreenState.sessionStartedAt;
            if (ScreenState.stopRequested || elapsed > MAX_SESSION_MS) {
                BridgeLog.log(ScreenCaptureService.this, "SCREEN",
                        ScreenState.stopRequested
                                ? "stream stopped by request"
                                : "stream auto-stopped at the 10-minute cap");
                stopSelf();
                return;
            }
            captureFrame();
            tickCount++;
            if (tickCount % 3 == 0) pumpInputs();
            if (handler != null) handler.postDelayed(this, TICK_MS);
        }
    };

    private void captureFrame() {
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return;
            Bitmap bmp = imageToBitmap(img);
            if (bmp == null) return;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
            bmp.recycle();
            byte[] jpeg = out.toByteArray();
            if (pendingPosts.get() >= MAX_PENDING_POSTS) return; // drop, stay live
            pendingPosts.incrementAndGet();
            final android.content.Context appCtx = getApplicationContext();
            final int w = frameW, h = frameH;
            net.execute(() -> {
                try {
                    boolean ok = ScreenTransport.postFrame(appCtx, jpeg, w, h, true);
                    if (ok) {
                        long now = System.currentTimeMillis();
                        ScreenState.sessionFrames++;
                        Store.put(appCtx, Store.K_SCREEN_LAST_FRAME_AT, now);
                        Store.put(appCtx, Store.K_SCREEN_LAST_W, (long) w);
                        Store.put(appCtx, Store.K_SCREEN_LAST_H, (long) h);
                        Store.put(appCtx, Store.K_SCREEN_FRAMES,
                                Store.getLong(appCtx, Store.K_SCREEN_FRAMES, 0) + 1);
                    }
                } finally {
                    pendingPosts.decrementAndGet();
                }
            });
        } catch (Exception e) {
            BridgeLog.log(this, "SCREEN", "frame capture failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
        } finally {
            if (img != null) {
                try { img.close(); } catch (Exception ignored) {}
            }
        }
    }

    /** The fast input queue: drained here, re-validated + executed
     *  by ScreenRouter on this thread. */
    private void pumpInputs() {
        try {
            JSONArray inputs = ScreenTransport.fetchInputs(this);
            if (inputs == null) return;
            for (int i = 0; i < inputs.length(); i++) {
                JSONObject in = inputs.optJSONObject(i);
                if (in != null) ScreenRouter.executeFastInput(this, in);
            }
        } catch (Exception e) {
            BridgeLog.log(this, "SCREEN", "input pump failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private Bitmap imageToBitmap(Image img) {
        Image.Plane[] planes = img.getPlanes();
        if (planes.length == 0) return null;
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * frameW;
        int paddedW = frameW + rowPadding / pixelStride;
        Bitmap padded = Bitmap.createBitmap(paddedW, frameH, Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        if (paddedW == frameW) return padded;
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0, frameW, frameH);
        padded.recycle();
        return cropped;
    }

    private static int[] scaledSize(int w, int h) {
        int longEdge = Math.max(w, h);
        if (longEdge <= STREAM_MAX_EDGE) return new int[]{even(w), even(h)};
        double k = STREAM_MAX_EDGE / (double) longEdge;
        return new int[]{even((int) Math.round(w * k)), even((int) Math.round(h * k))};
    }

    private static int even(int v) {
        int e = v - (v % 2);
        return e < 2 ? 2 : e;
    }

    @Override
    public void onDestroy() {
        tornDown = true;
        long frames = ScreenState.sessionFrames;
        long started = ScreenState.sessionStartedAt;
        ScreenState.streaming = false;
        ScreenState.sessionStartedAt = 0;
        ScreenState.sessionFrames = 0;
        Store.put(this, Store.K_SCREEN_STREAMING, false);
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignored) {}
            virtualDisplay = null;
        }
        if (reader != null) {
            try { reader.close(); } catch (Exception ignored) {}
            reader = null;
        }
        if (projection != null) {
            try { projection.stop(); } catch (Exception ignored) {}
            projection = null;
        }
        if (thread != null) thread.quitSafely();
        BridgeLog.log(this, "SCREEN", "streaming stopped (session frames=" + frames + ")");
        final android.content.Context appCtx = getApplicationContext();
        final ExecutorService netRef = net;
        if (netRef != null) {
            netRef.execute(() -> {
                ScreenTransport.postState(appCtx, false, frames, started, null);
                netRef.shutdown();
            });
            net = null;
        }
        super.onDestroy();
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stop = new Intent(this, ScreenCaptureService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("Muse Device Assistance — screen sharing")
                .setContentText(text)
                .setContentIntent(pi)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop sharing", stopPi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(text));
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Screen sharing", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
