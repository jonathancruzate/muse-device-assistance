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

    // Transport migration (v1.9) — the Worker can hand the app a new
    // base URL + secret (the local relay) inside a mode-state response
    // (WorkerClient.handleTransportDirective). The previous pair is
    // kept here so BridgeService's probation fallback can revert if
    // the new base proves unreachable. Secrets live only in these
    // prefs — never in the APK, never in the log.
    public static final String K_BASE_URL_PREV = "base_url_prev";
    public static final String K_SECRET_PREV = "secret_prev";
    public static final String K_TRANSPORT_SWITCHED_AT = "transport_switched_at";         // epoch ms of the switch
    public static final String K_TRANSPORT_PROBATION_UNTIL = "transport_probation_until"; // epoch ms probation ends

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
    // v1.20 local channel (Muse Launcher Pack): pairing token +
    // pending claim window + execution bookkeeping. The token value
    // itself is never logged and never leaves this app except to
    // the cert-verified pack at claim time.
    public static final String K_LC_TOKEN = "lc_token";
    public static final String K_LC_PENDING_TOKEN = "lc_pending_token";
    public static final String K_LC_PENDING_UNTIL = "lc_pending_until";
    public static final String K_LC_PAIRED_AT = "lc_paired_at";
    public static final String K_LC_LAST_TEMPLATE = "lc_last_template";
    public static final String K_LC_LAST_OK = "lc_last_ok";
    public static final String K_LC_LAST_AT = "lc_last_at";
    public static final String K_LC_LAST_MS = "lc_last_ms";
    public static final String K_LC_COUNT = "lc_count";
    public static final String K_LC_REFUSED = "lc_refused";
    public static final String K_LC_WINDOW_START = "lc_window_start";
    public static final String K_LC_WINDOW_COUNT = "lc_window_count";

    // v1.5 extension pack — native (on-device) commands.
    public static final String K_TORCH_ON = "torch_on";       // last torch state from the CameraManager callback
    public static final String K_TORCH_AT = "torch_at";       // epoch ms of the last torch callback / set
    // Voice-mode self-activation module (v1.5) — OPTIONAL, OFF by
    // default; Jonathan enables it in-app. While K_VOICE_ENABLED is
    // false the voice_mode Worker verb is refused: the capability
    // does not exist.
    public static final String K_VOICE_ENABLED = "voice_enabled"; // module switch (default false)
    public static final String K_VOICE_ACTIVE = "voice_active";   // persisted toggle: voice session believed on

    // Muse Wear Installer routing (v1.13) — last wear_install_face
    // job's state, mirrored by WearInstallRouter / WearStatusService
    // and carried in the report's wear_install object.
    public static final String K_WEAR_JOB_ID = "wear_job_id";
    public static final String K_WEAR_LABEL = "wear_label";
    public static final String K_WEAR_STAGE = "wear_stage";
    public static final String K_WEAR_DETAIL = "wear_detail";
    public static final String K_WEAR_AT = "wear_at";

    // Self-update (v1.21) — last self_update job's state, mirrored by
    // SelfUpdateRouter and carried in the report's shell_bridge
    // self_update object. Stage "installing" is written BEFORE pm
    // runs because the install kills this process; the snapshot
    // reconciles it to "installed" once the running versionCode no
    // longer equals K_UPDATE_FROM.
    public static final String K_UPDATE_SHA = "update_sha";
    public static final String K_UPDATE_STAGE = "update_stage";
    public static final String K_UPDATE_DETAIL = "update_detail";
    public static final String K_UPDATE_AT = "update_at";
    public static final String K_UPDATE_FROM = "update_from_code";

    // Phone file management (v1.24) — last files_* op's bookkeeping,
    // mirrored by FileRouter and carried in the report's
    // shell_bridge.files object. There is NO enable switch key:
    // the capability gate is the system's All Files Access grant
    // itself (Environment.isExternalStorageManager), Jonathan's
    // one-time act in Settings / the in-app Phone files section.
    public static final String K_FILES_LAST_OP = "files_last_op";
    public static final String K_FILES_LAST_DETAIL = "files_last_detail";
    public static final String K_FILES_LAST_AT = "files_last_at";

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
    public static final String K_PUSH_BATTERY = "push_battery";             // default false
    public static final String K_PUSH_MODE_RECONCILE = "push_mode_reconcile"; // default true

    // Per-channel bookkeeping: cursor (last id/ts the channel
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
    // (v1.12: the Health Connect channel's Store keys were removed
    // with the channel — see PushChannels.)

    // Screen sharing (v1.25) — see docs/v1.25-screen-sharing-design-2026-11-13.md.
    // The module switch is Jonathan's alone (voice-module pattern):
    // while K_SCREEN_ENABLED is false every screen_* verb except
    // screen_status refuses — the capability does not exist. The
    // MediaProjection consent result itself is NEVER persisted (it
    // lives in ScreenState's memory only, per platform rules); these
    // keys are bookkeeping: stream belief, frame counters, last error.
    public static final String K_SCREEN_ENABLED = "screen_enabled";   // module switch (default false)
    public static final String K_SCREEN_STREAMING = "screen_streaming"; // stream believed live
    public static final String K_SCREEN_STARTED_AT = "screen_started_at"; // epoch ms stream started
    public static final String K_SCREEN_FRAMES = "screen_frames";     // cumulative frames uploaded
    public static final String K_SCREEN_LAST_FRAME_AT = "screen_last_frame_at"; // epoch ms
    public static final String K_SCREEN_LAST_W = "screen_last_w";     // last uploaded frame size
    public static final String K_SCREEN_LAST_H = "screen_last_h";
    public static final String K_SCREEN_LAST_ERROR = "screen_last_error";
    public static final String K_SCREEN_LAST_INPUT = "screen_last_input"; // last input summary
    public static final String K_SCREEN_LAST_INPUT_AT = "screen_last_input_at";

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
