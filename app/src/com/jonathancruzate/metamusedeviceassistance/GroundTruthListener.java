package com.jonathancruzate.metamusedeviceassistance;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Ground truth, on-device: listens to the Modes and Routines app's own
 * notifications. The "Device Status" dump notification is parsed by
 * DumpParser into (current mode, running routines) and stored as the
 * bridge's observed state; every other Routines notification is logged
 * verbatim (truncated) as Phase 0 evidence of what the app posts.
 *
 * This is the same evidence source the server-side system already treats
 * as ground truth — here it is read in the loop, at notification time,
 * instead of via a server round-trip minutes later.
 */
public class GroundTruthListener extends NotificationListenerService {

    @Override
    public void onListenerConnected() {
        if (DeviceProfile.isTv(this)) {
            BridgeLog.log(this, "LISTENER", "tv profile: ground-truth listener inert (phone surface gated off)");
            return;
        }
        BridgeLog.log(this, "LISTENER", "connected");
        // Sweep anything already in the shade.
        try {
            StatusBarNotification[] active = getActiveNotifications();
            if (active != null) {
                for (StatusBarNotification sbn : active) handle(sbn, "sweep");
            }
        } catch (Exception ignored) {}
    }

    @Override
    public void onListenerDisconnected() {
        BridgeLog.log(this, "LISTENER", "disconnected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Ground-truth parsing path FIRST and unchanged (v1.6 design §4).
        handle(sbn, "posted");
        forwardPush(sbn, "posted");
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // A removed dump changes nothing about state; the last parsed dump
        // stands until a newer one lands. Log for the evidence trail.
        if (sbn != null && Probe.ROUTINES_PKG.equals(sbn.getPackageName())) {
            BridgeLog.log(this, "LISTENER", "routines notification removed: "
                    + titleOf(sbn.getNotification()));
        }
        forwardPush(sbn, "removed");
    }

    /**
     * Notifications push channel (v1.6) — replaces MacroDroid's Notify
     * Push macro AND the notifications.get pulls. When (and only when)
     * Jonathan has turned push_notifications ON, every package's posted
     * AND removed notifications are forwarded via PushClient with
     * source "phone-notify". Removed events are new in v1.6: MacroDroid
     * Notify Push v2 (never shipped) was the only prior plan for them —
     * stops/CLEARED are now visible too.
     *
     * When the channel is OFF this method returns immediately and the
     * listener's behavior is exactly the pre-v1.6 behavior. TV profile
     * is gated off by PushChannels.isOn.
     */
    private void forwardPush(StatusBarNotification sbn, String event) {
        if (sbn == null) return;
        if (!PushChannels.isOn(this, PushChannels.NOTIFICATIONS)) return;
        // v1.11 loop fix at the source (Jonathan's ruling, 2026-11-10):
        // IGNORE always-running notifications entirely. This app's own
        // foreground-service notification is in that class, and it was
        // the loop source: every push updated it, the listener re-fired,
        // and the re-push burned the Worker's KV write cap two days
        // running (2026-11-09/10). Two guards, both silent (logging a
        // skip per item would itself be noise at shade rates):
        //  1. hard self-package exclusion — our own notifications are
        //     never channel content, whatever their flags;
        //  2. the always-running class — FLAG_ONGOING_EVENT or
        //     FLAG_FOREGROUND_SERVICE set — from ANY package: persistent
        //     notifications are state, not events, and re-post on every
        //     internal update of their owning app.
        String pkg = sbn.getPackageName();
        if (pkg == null || pkg.equals(getPackageName())) return;
        Notification guard = sbn.getNotification();
        if (guard != null && (guard.flags & (Notification.FLAG_ONGOING_EVENT
                | Notification.FLAG_FOREGROUND_SERVICE)) != 0) return;
        try {
            Notification n = sbn.getNotification();
            String title = "", text = "", big = "";
            if (n != null && n.extras != null) {
                Bundle ex = n.extras;
                title = stringOf(ex.getCharSequence(Notification.EXTRA_TITLE));
                text = stringOf(ex.getCharSequence(Notification.EXTRA_TEXT));
                big = stringOf(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
                if (text.isEmpty()) {
                    CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                    if (lines != null) {
                        StringBuilder sb = new StringBuilder();
                        for (CharSequence l : lines) {
                            if (sb.length() > 0) sb.append('\n');
                            sb.append(l);
                        }
                        text = sb.toString();
                    }
                }
            }
            org.json.JSONObject item = new org.json.JSONObject();
            item.put("package", sbn.getPackageName());
            item.put("title", title);
            item.put("text", text);
            item.put("big_text", big);
            item.put("posted_at", sbn.getPostTime());
            item.put("event", event);
            item.put("key", sbn.getKey());
            org.json.JSONArray items = new org.json.JSONArray();
            items.put(item);
            PushClient.pushAsync(this, PushChannels.NOTIFICATIONS.source, items);
        } catch (Exception e) {
            BridgeLog.log(this, "PUSH", "phone-notify forward failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    private void handle(StatusBarNotification sbn, String how) {
        if (sbn == null) return;
        if (!Probe.ROUTINES_PKG.equals(sbn.getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null || n.extras == null) return;
        Bundle ex = n.extras;
        String title = stringOf(ex.getCharSequence(Notification.EXTRA_TITLE));
        String text = stringOf(ex.getCharSequence(Notification.EXTRA_TEXT));
        String big = stringOf(ex.getCharSequence(Notification.EXTRA_BIG_TEXT));
        String candidate = !big.isEmpty() ? big : text;

        // Text lines variant (some dumps render as inbox-style lines).
        if (candidate.isEmpty()) {
            CharSequence[] lines = ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
            if (lines != null) {
                StringBuilder sb = new StringBuilder();
                for (CharSequence l : lines) {
                    if (sb.length() > 0) sb.append('|');
                    sb.append(l);
                }
                candidate = sb.toString();
            }
        }

        BridgeLog.log(this, "NOTIF", how + " title=\"" + truncate(title, 60)
                + "\" text=\"" + truncate(candidate, 200) + "\"");

        if (candidate.isEmpty()) return;
        boolean looksLikeDump = title.toLowerCase().contains("device status")
                || candidate.contains("|") || candidate.contains("\u200B");
        if (!looksLikeDump) return;

        DumpParser.Result r = DumpParser.parse(candidate);
        if (r.parseOk) {
            String prevMode = Store.get(this, Store.K_GT_MODE, "");
            Store.put(this, Store.K_GT_MODE, r.mode);
            Store.put(this, Store.K_GT_RUNNING, r.running);
            Store.put(this, Store.K_GT_RAW, String.join("|", r.fields));
            Store.put(this, Store.K_GT_AT, System.currentTimeMillis());
            Store.put(this, Store.K_GT_PARSE_OK, true);
            BridgeLog.log(this, "GROUND_TRUTH", "mode=" + r.mode
                    + " running=\"" + r.running + "\" fields=" + r.fields.size()
                    + (r.mode.equals(prevMode) ? "" : " (changed from " + prevMode + ")"));
            BridgeService.requestCycle(this, "ground-truth");
            if (!r.mode.equals(prevMode)) {
                // v1.20: poke the Muse Launcher Pack (Section 7.3).
                ContextBroadcasts.sendModeChanged(this, r.mode);
            }
        } else {
            Store.put(this, Store.K_GT_PARSE_OK, false);
            // LOUD by design: a format change must never pass silently.
            BridgeLog.log(this, "PARSE_FAIL",
                    "dump did not parse; field1=\"" + truncate(r.mode, 40)
                    + "\" raw=\"" + truncate(candidate, 200) + "\"");
        }
    }

    private static String titleOf(Notification n) {
        if (n == null || n.extras == null) return "";
        return stringOf(n.extras.getCharSequence(Notification.EXTRA_TITLE));
    }

    private static String stringOf(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
