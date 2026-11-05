package com.jonathancruzate.metamusedeviceassistance;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

/**
 * The bridge loop: every minute, pull desired state from the Worker,
 * evaluate the reconciler against ground truth, and report — on any
 * change, and as a heartbeat at least every 15 minutes. The heartbeat is
 * the observability edge over MacroDroid (report §4.6): if this service
 * dies, the server sees silence with a last-known state, instead of
 * discovering it during an incident.
 *
 * SHADOW MODE by default: the service observes, verifies, plans, and
 * reports. Since v1.3.0 it can fire — only through Actuation's gate
 * (armed in the UI, night mode only, once per desired sequence).
 */
public class BridgeService extends Service {
    private static final String CHANNEL = "bridge";
    private static final long CYCLE_MS = 60_000;
    private static final long HEARTBEAT_MS = 15 * 60_000;
    public static final String ACTION_CYCLE = "com.jonathancruzate.metamusedeviceassistance.CYCLE";

    private HandlerThread thread;
    private Handler handler;
    private long lastReportedGtAt = -1;
    private String lastReportedVerdict = "";

    public static void start(Context ctx) {
        Store.put(ctx, Store.K_SERVICE_ON, true);
        Intent i = new Intent(ctx, BridgeService.class);
        ctx.startForegroundService(i);
    }

    public static void stop(Context ctx) {
        Store.put(ctx, Store.K_SERVICE_ON, false);
        ctx.stopService(new Intent(ctx, BridgeService.class));
    }

    /** Ask the running service (if any) to run a cycle now. */
    public static void requestCycle(Context ctx, String reason) {
        if (!Store.getBool(ctx, Store.K_SERVICE_ON, false)) return;
        Intent i = new Intent(ctx, BridgeService.class);
        i.setAction(ACTION_CYCLE);
        try {
            ctx.startForegroundService(i);
        } catch (Exception ignored) {}
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        startForeground(1, statusNotification("starting"));
        thread = new HandlerThread("bridge-loop");
        thread.start();
        handler = new Handler(thread.getLooper());
        BridgeLog.log(this, "SERVICE", "created");
        AdbBridge.startDiscovery(this);
        handler.post(this::cycle);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CYCLE.equals(intent.getAction()) && handler != null) {
            handler.post(this::cycle);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        BridgeLog.log(this, "SERVICE", "destroyed");
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void cycle() {
        try {
            AdbBridge.tick(this);
            TailscaleGuard.tick(this);
            boolean configured = Store.workerConfigured(this);
            if (configured) {
                WorkerClient.fetchDesired(this);
            }
            Reconciler.Verdict v = Reconciler.evaluate(this);
            Actuation.maybeAct(this, v);
            long gtAt = Store.getLong(this, Store.K_GT_AT, 0);
            long lastReport = Store.getLong(this, Store.K_LAST_REPORT_AT, 0);
            boolean changed = gtAt != lastReportedGtAt || !v.state.equals(lastReportedVerdict);
            boolean heartbeatDue = System.currentTimeMillis() - lastReport > HEARTBEAT_MS;
            if (configured && (changed || heartbeatDue)) {
                if (WorkerClient.postReport(this, changed ? "change" : "heartbeat")) {
                    lastReportedGtAt = gtAt;
                    lastReportedVerdict = v.state;
                }
            }
            updateNotification(v);
        } catch (Exception e) {
            BridgeLog.log(this, "SERVICE", "cycle error: " + e.getClass().getSimpleName()
                    + " " + e.getMessage());
        } finally {
            if (handler != null) handler.postDelayed(this::cycle, CYCLE_MS);
        }
    }

    private void updateNotification(Reconciler.Verdict v) {
        String mode = Reconciler.resolveObservedMode(
                Store.get(this, Store.K_GT_MODE, "?"), Store.get(this, Store.K_GT_RUNNING, ""));
        String desired = Store.get(this, Store.K_DESIRED_MODE, "?");
        boolean armed = Store.getBool(this, Store.K_ACT_ARMED, false);
        String text = "Mode: " + mode + " · Desired: " + desired + " · " + v.state
                + (armed ? " (Phase 2 ARMED — night only)" : " (shadow — observing only)");
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(1, statusNotification(text));
    }

    private Notification statusNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Muse Device Assistance")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "Muse Device Assistance", NotificationManager.IMPORTANCE_LOW));
        }
    }
}
