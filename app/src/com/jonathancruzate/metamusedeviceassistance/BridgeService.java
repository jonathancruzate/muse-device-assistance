package com.jonathancruzate.metamusedeviceassistance;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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
 * SHADOW MODE, permanently: the service observes, verifies, plans,
 * and reports — it NEVER fires. The Phase 2 actuation machinery
 * (v1.3.0–v1.22.0) was removed in v1.23.0 after its armed in-app
 * firing double-toggled the same Samsung Switch routines that
 * mode-sync.sh fires; mode-sync.sh is the only phone-mode writer.
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
    private PushObservers pushObservers;

    // v1.27: command fetch/execute lives on its OWN thread with a
    // 7 s poll, draining until the queue is empty. Through v1.26
    // commands rode this service's single 60 s cycle thread, one
    // per cycle, AFTER the desired-state fetch: one slow command
    // froze command fetches, reports, and push behind it, and a
    // queued command could wait minutes just to be fetched
    // (2026-11-18 incident, docs/lag-diagnosis-2026-11-18.md).
    private HandlerThread cmdThread;
    private Handler cmdHandler;
    private static final long CMD_POLL_MS = 7_000;

    private final Runnable cmdLoop = new Runnable() {
        @Override
        public void run() {
            try {
                if (Store.workerConfigured(BridgeService.this)) {
                    ShellBridge.drainCommands(BridgeService.this);
                }
            } catch (Exception e) {
                BridgeLog.log(BridgeService.this, "SERVICE",
                        "command loop error: " + e.getClass().getSimpleName()
                                + " " + e.getMessage());
            } finally {
                if (cmdHandler != null) {
                    cmdHandler.removeCallbacks(this);
                    cmdHandler.postDelayed(this, CMD_POLL_MS);
                }
            }
        }
    };

    /** Run a command drain now (the poll chain continues). */
    private void kickCommands() {
        if (cmdHandler != null) {
            cmdHandler.removeCallbacks(cmdLoop);
            cmdHandler.post(cmdLoop);
        }
    }

    public static void start(Context ctx) {
        Store.put(ctx, Store.K_SERVICE_ON, true);
        Intent i = new Intent(ctx, BridgeService.class);
        try {
            ctx.startForegroundService(i);
        } catch (Exception ignored) {
            // Same discipline as requestCycle: a refused FGS start must
            // never take the caller (UI / boot / listener) down with it.
        }
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
        try {
            startForeground(1, statusNotification("starting"),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (RuntimeException e) {
            // v1.4.2: NEVER crash in onCreate. Through v1.4.1 this call
            // was unguarded with type dataSync; once the platform's
            // dataSync time budget was exhausted, startForeground threw
            // ForegroundServiceStartNotAllowedException here and every
            // restart crash-looped ("Unable to create service",
            // chronic since 2026-11-05). A refused start is a clean
            // stop instead — the next boot / UI start / listener cycle
            // can try again without a crash record.
            BridgeLog.log(this, "SERVICE", "startForeground refused: "
                    + e.getClass().getSimpleName() + " " + e.getMessage()
                    + " — stopping cleanly");
            stopSelf();
            return;
        }
        thread = new HandlerThread("bridge-loop");
        thread.start();
        handler = new Handler(thread.getLooper());
        BridgeLog.log(this, "SERVICE", "created profile=" + DeviceProfile.describe(this));
        AdbBridge.startDiscovery(this);
        // v1.7 push observers: ContentObservers + dialer callback,
        // registered on this service's handler thread, gated per
        // channel toggle inside PushObservers (no-op on TV).
        pushObservers = new PushObservers(this, handler);
        pushObservers.start();
        // v1.26 Duo companion: the wake-ritual watcher lives only
        // on the Duo profile (DuoRitual.start self-guards too).
        // v1.29: a set Duo debug flag never survives a service
        // start (boot, app open, restart) — cleared before the
        // watcher registers.
        if (DeviceProfile.isDuo(this)) {
            DuoRitual.clearDebugOnStart(this);
            DuoRitual.start(this);
        }
        handler.post(this::cycle);
        // v1.27: the dedicated command thread (TV keeps no command
        // channel — ShellBridge self-guards there too).
        if (!DeviceProfile.isTv(this)) {
            cmdThread = new HandlerThread("bridge-commands");
            cmdThread.start();
            cmdHandler = new Handler(cmdThread.getLooper());
            cmdHandler.post(cmdLoop);
            BridgeLog.log(this, "SERVICE",
                    "command thread started (7s poll, drain-until-empty)");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CYCLE.equals(intent.getAction()) && handler != null) {
            handler.post(this::cycle);
            kickCommands();
        }
        return START_STICKY;
    }

    /**
     * Platform FGS time-budget callback (API 35+). If a future OS ever
     * time-limits the specialUse type the way it limited dataSync, the
     * contract is: stop promptly and cleanly. Through v1.4.1 there was
     * no handler, so the system's dataSync timeout killed the process
     * with ForegroundServiceDidNotStopInTimeException instead.
     */
    @Override
    public void onTimeout(int startId, int fgsType) {
        BridgeLog.log(this, "SERVICE", "FGS onTimeout startId=" + startId
                + " type=" + fgsType + " — stopping cleanly");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override
    public void onDestroy() {
        BridgeLog.log(this, "SERVICE", "destroyed");
        if (DeviceProfile.isDuo(this)) DuoRitual.stop(this);
        if (pushObservers != null) pushObservers.stop();
        if (cmdHandler != null) cmdHandler.removeCallbacksAndMessages(null);
        if (cmdThread != null) cmdThread.quitSafely();
        LoopbackAdb.close(this);
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
            boolean tv = DeviceProfile.isTv(this);
            // v1.26: the Duo profile shares TV's phone-surface gates
            // (no Samsung modes, no house-mode desired, no push
            // channels) but KEEPS the shell bridge + reports, under
            // its own duobridge identity.
            boolean duo = DeviceProfile.isDuo(this);
            boolean configured = Store.workerConfigured(this);
            // v1.9: per-cycle transport health for the probation
            // fallback below — did this cycle's desired fetch and
            // report attempt succeed against the current base?
            boolean fetchOk = false;
            boolean reportAttempted = false;
            boolean reportOk = false;
            if (configured && !tv) {
                // Duo: no house-mode desired fetch (a phone surface;
                // WorkerClient.fetchDesired self-guards too) — the
                // transport directive rides it, so the Duo's base is
                // whatever Jonathan entered in setup, by design.
                if (!duo) fetchOk = WorkerClient.fetchDesired(this);
                // v1.27: commands no longer execute here — the
                // dedicated command thread (cmdLoop) owns fetch +
                // execute, so a slow command can never freeze this
                // cycle's reports or push again.
            }
            Reconciler.Verdict v = Reconciler.evaluate(this);
            // v1.6 push channels: Low Battery crossing latch + retry of
            // any queued push payloads. Both are no-ops when their
            // channel is OFF / the queue is empty, and both are gated
            // off on TV inside PushChannels/PushClient.
            if (!tv && !duo) {
                BatteryPush.check(this);
                // v1.7: observer diffs (calendar/contacts/messages/
                // call log; dialer pushes live from its callback).
                // (v1.12: the Health Connect change-log check is gone
                // with its channel — see PushChannels.)
                if (pushObservers != null) pushObservers.syncDirty();
                PushClient.flushQueue(this);
            }
            long gtAt = Store.getLong(this, Store.K_GT_AT, 0);
            long lastReport = Store.getLong(this, Store.K_LAST_REPORT_AT, 0);
            boolean changed = gtAt != lastReportedGtAt || !v.state.equals(lastReportedVerdict);
            boolean heartbeatDue = System.currentTimeMillis() - lastReport > HEARTBEAT_MS;
            if (configured && (changed || heartbeatDue)) {
                reportAttempted = true;
                reportOk = WorkerClient.postReport(this, changed ? "change" : "heartbeat");
                if (reportOk) {
                    lastReportedGtAt = gtAt;
                    lastReportedVerdict = v.state;
                }
            }
            // v1.9 transport probation: within 24 h of a transport
            // switch, a cycle where BOTH the desired fetch and the
            // report attempt failed against the new base probes the
            // previous base once and reverts if it answers (no-op
            // outside probation or with no previous base stored;
            // WorkerClient.maybeRevertTransport owns the window).
            if (configured && !tv && !duo && !fetchOk && reportAttempted && !reportOk) {
                WorkerClient.maybeRevertTransport(this);
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
        if (DeviceProfile.isTv(this)) {
            NotificationManager nmTv = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nmTv != null) nmTv.notify(1, statusNotification(
                    "Shield TV profile - shieldbridge - observing (phone surfaces off)"));
            return;
        }
        if (DeviceProfile.isDuo(this)) {
            NotificationManager nmDuo = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nmDuo != null) nmDuo.notify(1, statusNotification(
                    "Surface Duo profile - duobridge - ritual "
                            + (DuoRitual.ritualEnabled(this) ? "armed" : "off")
                            + " (phone surfaces off)"));
            return;
        }
        String mode = Reconciler.resolveObservedMode(
                Store.get(this, Store.K_GT_MODE, "?"), Store.get(this, Store.K_GT_RUNNING, ""));
        String desired = Store.get(this, Store.K_DESIRED_MODE, "?");
        String text = "Mode: " + mode + " · Desired: " + desired + " · " + v.state
                + " (shadow — observing only)";
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
