package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * All bridge state in one SharedPreferences file: worker settings (entered
 * on-device, NEVER hardcoded — no secret ships in this APK), the latest
 * ground truth, the latest desired state, the Away-latch belief, and the
 * reconciler's last verdict.
 */
public final class Store {
    private static final String PREFS = "bridge";

    // Settings
    public static final String K_BASE_URL = "base_url";       // e.g. https://jonathancruzate.com/mode-sync
    public static final String K_SECRET = "secret";           // mode-state poll secret, entered on device
    public static final String K_SERVICE_ON = "service_on";

    // Ground truth (from GroundTruthListener)
    public static final String K_GT_MODE = "gt_mode";         // Samsung mode name, e.g. "Home", "Sleep", "None"
    public static final String K_GT_RUNNING = "gt_running";   // running routines list text
    public static final String K_GT_RAW = "gt_raw";           // raw dump fields, joined
    public static final String K_GT_AT = "gt_at";             // epoch ms of last parsed dump
    public static final String K_GT_PARSE_OK = "gt_parse_ok";

    // Desired state (from the Worker)
    public static final String K_DESIRED_MODE = "desired_mode"; // house mode: home|night|morning|away
    public static final String K_DESIRED_SEQ = "desired_seq";
    public static final String K_DESIRED_AT = "desired_at";

    // ADB bridge (v1.1)
    public static final String K_ADB_ENDPOINT = "adb_endpoint";       // "ip:port" of _adb-tls-connect
    public static final String K_ADB_ENDPOINT_AT = "adb_endpoint_at"; // epoch ms of last endpoint/pairing update
    public static final String K_ADB_ENDPOINTS_JSON = "adb_endpoints_json";
    public static final String K_ADB_PAIRING_OPEN = "adb_pairing_open";
    public static final String K_ADB_HEAL_ON = "adb_heal_on";         // self-heal armed (default true)
    public static final String K_ADB_HEAL_LAST_LOG = "adb_heal_last_log";
    public static final String K_ADB_WIFI_IP = "adb_wifi_ip";

    // Reconciler verdict
    public static final String K_VERDICT = "verdict";         // in_sync|drift|unknown
    public static final String K_PLAN = "plan";               // shadow plan text when drift
    public static final String K_LAST_REPORT_AT = "last_report_at";
    public static final String K_LAST_REPORT_CODE = "last_report_code";

    // Phase 2 actuation (v1.3) — night mode only, OFF unless armed here.
    // A routine is fired through the launch-shortcut intent Samsung itself
    // minted when the routine was linked on-device (CREATE_SHORTCUT flow);
    // the intent URI lives only in these prefs, never in the APK.
    public static final String K_ACT_ARMED = "act_armed";           // default false
    public static final String K_LINK_NIGHT = "link_night";         // intent URI for Switch Sleep
    public static final String K_LINK_HOME = "link_home";
    public static final String K_LINK_MORNING = "link_morning";
    public static final String K_LINK_AWAY = "link_away";
    public static final String K_LINK_NAME_NIGHT = "link_name_night";
    public static final String K_LINK_NAME_HOME = "link_name_home";
    public static final String K_LINK_NAME_MORNING = "link_name_morning";
    public static final String K_LINK_NAME_AWAY = "link_name_away";
    public static final String K_ACT_FIRED_SEQ = "act_fired_seq";   // desired seq already fired for
    public static final String K_ACT_LAST_LOG = "act_last_log";     // throttle for repeated actuation logs

    // Phase 2 expansion (v1.8) — all house modes. The Away-latch clear
    // is its own once-per-seq fire, sequenced BEFORE the target fire;
    // its markers mirror the target fire's.
    public static final String K_ACT_AWAY_CLEAR_SEQ = "act_away_clear_seq";
    public static final String K_ACT_AWAY_CLEAR_AT = "act_away_clear_at";
    public static final String K_ACT_AWAY_CLEAR_GT = "act_away_clear_gt"; // gt_at when the clear fired
    public static final String K_ACT_FIRED_AT = "act_fired_at";           // epoch ms of the last target fire
    public static final String K_ACT_FIRED_GT = "act_fired_gt";           // gt_at when the last target fired
    // Shadow-evidence records (v1.8): the latest per-cycle decision,
    // the last real fire with its verified outcome, and a capped
    // decision history — JSON strings, carried in every report so
    // parity with MacroDroid Mode Poll is provable from the outside.
    public static final String K_ACT_SHADOW = "act_shadow_json";
    public static final String K_ACT_LAST_FIRE = "act_last_fire_json";
    public static final String K_ACT_HISTORY = "act_history_json";

    // Loopback shell bridge (v1.4) — in-app pairing with the phone's own
    // wireless debugging; allowlisted commands pulled from the Worker.
    // The RSA key + cert live in app-private FILES (LoopbackAdb), never
    // in prefs; only state flags live here.
    public static final String K_SHELL_PAIRED = "shell_paired";           // pairing completed at least once
    public static final String K_SHELL_SELF_TEST_OK = "shell_self_test_ok"; // battery template passed over loopback
    public static final String K_SHELL_ON = "shell_on";                   // UI switch: executor armed (default false; auto-set true when the self-test first passes)
    public static final String K_SHELL_LAST_ID = "shell_last_id";
    public static final String K_SHELL_LAST_TEMPLATE = "shell_last_template";
    public static final String K_SHELL_LAST_OK = "shell_last_ok";
    public static final String K_SHELL_LAST_MS = "shell_last_ms";
    public static final String K_SHELL_LAST_AT = "shell_last_at";

    // v1.5 extension pack — native (on-device) commands.
    public static final String K_TORCH_ON = "torch_on";       // last torch state from the CameraManager callback
    public static final String K_TORCH_AT = "torch_at";       // epoch ms of the last torch callback / set
    // Voice-mode self-activation module (v1.5) — OPTIONAL, OFF by
    // default; Jonathan enables it in-app. While K_VOICE_ENABLED is
    // false the voice_mode Worker verb is refused: the capability
    // does not exist.
    public static final String K_VOICE_ENABLED = "voice_enabled"; // module switch (default false)
    public static final String K_VOICE_ACTIVE = "voice_active";   // persisted toggle: voice session believed on

    // Push channels (v1.6) — local push replacement, design
    // docs/local-push-replacement-v1.6-design-2026-11-09.md §4.
    // Every channel is Jonathan-controlled: its own Store key, OFF by
    // default except mode reconcile (the bridge's existing core loop).
    // OFF means not registered, not read, not pushed.
    public static final String K_PUSH_NOTIFICATIONS = "push_notifications"; // default false
    public static final String K_PUSH_CALENDAR = "push_calendar";           // default false
    public static final String K_PUSH_CONTACTS = "push_contacts";           // default false
    public static final String K_PUSH_MESSAGES = "push_messages";           // default false
    public static final String K_PUSH_DIALER = "push_dialer";               // default false
    public static final String K_PUSH_CALL_LOG = "push_call_log";           // default false
    public static final String K_PUSH_HEALTH = "push_health";               // default false
    public static final String K_PUSH_BATTERY = "push_battery";             // default false
    public static final String K_PUSH_MODE_RECONCILE = "push_mode_reconcile"; // default true

    // Per-channel bookkeeping: cursor (last id/ts/HC token the channel
    // has consumed; advances only on a 2xx push — see PushClient),
    // last successful push time, and last error string.
    public static final String K_PUSH_NOTIFICATIONS_CURSOR = "push_notifications_cursor";
    public static final String K_PUSH_NOTIFICATIONS_LAST_PUSH = "push_notifications_last_push";
    public static final String K_PUSH_NOTIFICATIONS_LAST_ERROR = "push_notifications_last_error";
    public static final String K_PUSH_CALENDAR_CURSOR = "push_calendar_cursor";
    public static final String K_PUSH_CALENDAR_LAST_PUSH = "push_calendar_last_push";
    public static final String K_PUSH_CALENDAR_LAST_ERROR = "push_calendar_last_error";
    public static final String K_PUSH_CONTACTS_CURSOR = "push_contacts_cursor";
    public static final String K_PUSH_CONTACTS_LAST_PUSH = "push_contacts_last_push";
    public static final String K_PUSH_CONTACTS_LAST_ERROR = "push_contacts_last_error";
    public static final String K_PUSH_MESSAGES_CURSOR = "push_messages_cursor";
    public static final String K_PUSH_MESSAGES_LAST_PUSH = "push_messages_last_push";
    public static final String K_PUSH_MESSAGES_LAST_ERROR = "push_messages_last_error";
    public static final String K_PUSH_DIALER_CURSOR = "push_dialer_cursor";
    public static final String K_PUSH_DIALER_LAST_PUSH = "push_dialer_last_push";
    public static final String K_PUSH_DIALER_LAST_ERROR = "push_dialer_last_error";
    public static final String K_PUSH_CALL_LOG_CURSOR = "push_call_log_cursor";
    public static final String K_PUSH_CALL_LOG_LAST_PUSH = "push_call_log_last_push";
    public static final String K_PUSH_CALL_LOG_LAST_ERROR = "push_call_log_last_error";
    public static final String K_PUSH_HEALTH_CURSOR = "push_health_cursor";
    public static final String K_PUSH_HEALTH_LAST_PUSH = "push_health_last_push";
    public static final String K_PUSH_HEALTH_LAST_ERROR = "push_health_last_error";
    public static final String K_PUSH_BATTERY_CURSOR = "push_battery_cursor";
    public static final String K_PUSH_BATTERY_LAST_PUSH = "push_battery_last_push";
    public static final String K_PUSH_BATTERY_LAST_ERROR = "push_battery_last_error";
    public static final String K_PUSH_MODE_RECONCILE_CURSOR = "push_mode_reconcile_cursor";
    public static final String K_PUSH_MODE_RECONCILE_LAST_PUSH = "push_mode_reconcile_last_push";
    public static final String K_PUSH_MODE_RECONCILE_LAST_ERROR = "push_mode_reconcile_last_error";
    // Low Battery latch: true once the 20% downward crossing has been
    // pushed; re-arms when battery rises above 25%.
    public static final String K_PUSH_BATTERY_LATCHED = "push_battery_latched";
    // PushClient retry queue: a JSON array string of failed payloads
    // ({source, ts, items}) kept in prefs; see PushClient.
    public static final String K_PUSH_QUEUE = "push_queue";

    // v1.7 observer row cursors — separate from the per-channel
    // timestamp cursors above (MainActivity seeds those to "now" on
    // toggle-ON). Row-id cursors seed themselves to the current max
    // _ID on first observer run (no historical dump).
    public static final String K_PUSH_MESSAGES_SMS_ID = "push_messages_sms_id";
    public static final String K_PUSH_MESSAGES_MMS_ID = "push_messages_mms_id";
    public static final String K_PUSH_CALL_LOG_ID = "push_call_log_id";
    // Health Connect (v1.7): change-log token (string) + last-check
    // throttle timestamp. Token advances only on a 2xx push.
    public static final String K_PUSH_HEALTH_TOKEN = "push_health_token";
    public static final String K_PUSH_HEALTH_LAST_CHECK = "push_health_last_check";

    // Tailscale guardian (v1.3.1 detection; v1.3.2 reviver)
    public static final String K_TS_DOWN_SINCE = "ts_down_since";   // epoch ms tailnet first seen down (0 = up)
    public static final String K_TS_LAST_LAUNCH = "ts_last_launch"; // epoch ms of last revive launch attempt
    public static final String K_TS_LAUNCH_COUNT = "ts_launch_count"; // revive attempts in the current episode
    public static final String K_TS_DOWN_COUNT = "ts_down_count";   // cumulative down episodes observed
    public static final String K_TS_LAST_RECOVERED = "ts_last_recovered"; // epoch ms of last recovery
    public static final String K_TS_NOTIFIED = "ts_notified";       // escalation notification posted this episode

    private Store() {}

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String get(Context ctx, String key, String def) {
        return prefs(ctx).getString(key, def);
    }

    public static long getLong(Context ctx, String key, long def) {
        return prefs(ctx).getLong(key, def);
    }

    public static boolean getBool(Context ctx, String key, boolean def) {
        return prefs(ctx).getBoolean(key, def);
    }

    public static void put(Context ctx, String key, String value) {
        prefs(ctx).edit().putString(key, value).apply();
    }

    public static void put(Context ctx, String key, long value) {
        prefs(ctx).edit().putLong(key, value).apply();
    }

    public static void put(Context ctx, String key, boolean value) {
        prefs(ctx).edit().putBoolean(key, value).apply();
    }

    /** Worker configured = base URL and secret both present. */
    public static boolean workerConfigured(Context ctx) {
        return !get(ctx, K_BASE_URL, "").trim().isEmpty()
                && !get(ctx, K_SECRET, "").trim().isEmpty();
    }
}
