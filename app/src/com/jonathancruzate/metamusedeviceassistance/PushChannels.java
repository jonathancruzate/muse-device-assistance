package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;

import org.json.JSONObject;

/**
 * Push channels (v1.6) — the registry for the local push replacement
 * (design docs/local-push-replacement-v1.6-design-2026-11-09.md §4).
 *
 * One row per channel: its Store toggle key, its wire source name for
 * POST /device-push, and its cursor / last-push / last-error keys.
 * MainActivity builds its checkbox section from this table,
 * PushClient updates bookkeeping through it, and WorkerClient renders
 * the report's push_channels object from it — so the three surfaces
 * can never disagree about what a channel is called.
 *
 * Defaults: every channel OFF except mode reconcile (the bridge's
 * existing core loop, default ON). TV profile: every channel is
 * effectively OFF regardless of the stored toggle (see isOn()).
 */
public final class PushChannels {
    private PushChannels() {}

    public static final class Channel {
        public final String id;          // stable id, e.g. "notifications"
        public final String label;       // UI label
        public final String source;      // wire source for /device-push
        public final String toggleKey;
        public final String cursorKey;
        public final String lastPushKey;
        public final String lastErrorKey;
        public final boolean defaultOn;

        Channel(String id, String label, String source, String toggleKey,
                String cursorKey, String lastPushKey, String lastErrorKey,
                boolean defaultOn) {
            this.id = id;
            this.label = label;
            this.source = source;
            this.toggleKey = toggleKey;
            this.cursorKey = cursorKey;
            this.lastPushKey = lastPushKey;
            this.lastErrorKey = lastErrorKey;
            this.defaultOn = defaultOn;
        }
    }

    public static final Channel NOTIFICATIONS = new Channel(
            "notifications", "Notifications", "phone-notify",
            Store.K_PUSH_NOTIFICATIONS, Store.K_PUSH_NOTIFICATIONS_CURSOR,
            Store.K_PUSH_NOTIFICATIONS_LAST_PUSH, Store.K_PUSH_NOTIFICATIONS_LAST_ERROR, false);
    public static final Channel CALENDAR = new Channel(
            "calendar", "Calendar", "phone-calendar",
            Store.K_PUSH_CALENDAR, Store.K_PUSH_CALENDAR_CURSOR,
            Store.K_PUSH_CALENDAR_LAST_PUSH, Store.K_PUSH_CALENDAR_LAST_ERROR, false);
    public static final Channel CONTACTS = new Channel(
            "contacts", "Contacts", "phone-contacts",
            Store.K_PUSH_CONTACTS, Store.K_PUSH_CONTACTS_CURSOR,
            Store.K_PUSH_CONTACTS_LAST_PUSH, Store.K_PUSH_CONTACTS_LAST_ERROR, false);
    public static final Channel MESSAGES = new Channel(
            "messages", "Messages", "phone-messages",
            Store.K_PUSH_MESSAGES, Store.K_PUSH_MESSAGES_CURSOR,
            Store.K_PUSH_MESSAGES_LAST_PUSH, Store.K_PUSH_MESSAGES_LAST_ERROR, false);
    public static final Channel DIALER = new Channel(
            "dialer", "Phone dialer (live call state)", "phone-dialer",
            Store.K_PUSH_DIALER, Store.K_PUSH_DIALER_CURSOR,
            Store.K_PUSH_DIALER_LAST_PUSH, Store.K_PUSH_DIALER_LAST_ERROR, false);
    public static final Channel CALL_LOG = new Channel(
            "call_log", "Call Log", "phone-calllog",
            Store.K_PUSH_CALL_LOG, Store.K_PUSH_CALL_LOG_CURSOR,
            Store.K_PUSH_CALL_LOG_LAST_PUSH, Store.K_PUSH_CALL_LOG_LAST_ERROR, false);
    // v1.12: the Health Connect channel is REMOVED (Jonathan's order,
    // 2026-11-10 — agreed 2026-11-09: redundant; the Muse app's Health
    // Connect permission + the Health Connect connector own health
    // data). Channel, observer (HealthPush), toggle, and the manifest
    // HC permission declarations are all gone, not just defaulted off.
    public static final Channel BATTERY = new Channel(
            "battery", "Low Battery (20% crossing)", "phone-battery",
            Store.K_PUSH_BATTERY, Store.K_PUSH_BATTERY_CURSOR,
            Store.K_PUSH_BATTERY_LAST_PUSH, Store.K_PUSH_BATTERY_LAST_ERROR, false);
    public static final Channel MODE_RECONCILE = new Channel(
            "mode_reconcile", "Mode reconcile (local, existing loop)", "phone-mode",
            Store.K_PUSH_MODE_RECONCILE, Store.K_PUSH_MODE_RECONCILE_CURSOR,
            Store.K_PUSH_MODE_RECONCILE_LAST_PUSH, Store.K_PUSH_MODE_RECONCILE_LAST_ERROR, true);

    public static final Channel[] ALL = {
            NOTIFICATIONS, CALENDAR, CONTACTS, MESSAGES, DIALER,
            CALL_LOG, BATTERY, MODE_RECONCILE,
    };

    public static Channel byId(String id) {
        for (Channel c : ALL) if (c.id.equals(id)) return c;
        return null;
    }

    public static Channel bySource(String source) {
        for (Channel c : ALL) if (c.source.equals(source)) return c;
        return null;
    }

    /** Stored toggle, ignoring profile gating. */
    public static boolean storedOn(Context ctx, Channel c) {
        return Store.getBool(ctx, c.toggleKey, c.defaultOn);
    }

    /** Effective state: TV profile gates every push channel OFF. */
    public static boolean isOn(Context ctx, Channel c) {
        if (DeviceProfile.isTv(ctx)) return false;
        return storedOn(ctx, c);
    }

    public static void recordSuccess(Context ctx, Channel c, long atMs) {
        Store.put(ctx, c.lastPushKey, atMs);
        Store.put(ctx, c.lastErrorKey, "");
    }

    public static void recordError(Context ctx, Channel c, String error) {
        Store.put(ctx, c.lastErrorKey, error == null ? "" : error);
    }

    /** The report's push_channels object: per channel {on, last_push_at, last_error}. */
    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        boolean tv = DeviceProfile.isTv(ctx);
        try {
            for (Channel c : ALL) {
                JSONObject ch = new JSONObject();
                ch.put("on", !tv && storedOn(ctx, c));
                ch.put("last_push_at", Store.getLong(ctx, c.lastPushKey, 0));
                ch.put("last_error", Store.get(ctx, c.lastErrorKey, ""));
                o.put(c.id, ch);
            }
        } catch (Exception ignored) {}
        return o;
    }
}
