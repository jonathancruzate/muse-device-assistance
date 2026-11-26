package com.jonathancruzate.metamusedeviceassistance;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * ShellAllowlist — the v1.4 loopback shell bridge's command gate.
 *
 * Design rule (see docs/loopback-shell-bridge-v1.4-design-2026-11-04.md):
 * the bridge NEVER executes a free-form shell string. A command pulled
 * down the Worker channel is a structured request {template, args[]}
 * matched against the fixed template table below. Each template is an
 * argv array with typed slots; there is no shell interpretation at any
 * point (the ADB shell service receives the joined argv exactly as
 * Android's shell service tokenizes it, and every slot value is
 * validated so it cannot smuggle a second command: no whitespace, no
 * shell metacharacters, length-capped).
 *
 * This class is deliberately free of Android dependencies so the gate
 * can be unit-tested on a plain JVM (tests: tools/ShellAllowlistTest).
 * Adding a template is a code change + review, never a config change —
 * the Worker cannot widen the allowlist.
 */
public final class ShellAllowlist {

    private ShellAllowlist() {}

    // ---- slot validators -------------------------------------------------

    private static final Pattern PKG = Pattern.compile(
            "^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");
    private static final Pattern SETTINGS_KEY = Pattern.compile(
            "^[a-zA-Z0-9_.]+$");
    private static final Pattern SETTINGS_VALUE = Pattern.compile(
            "^[a-zA-Z0-9_.:/\\-]{1,128}$");
    private static final Pattern ROLE = Pattern.compile(
            "^(DIALER|SMS|ASSISTANT|BROWSER|HOME)$");
    private static final Pattern BUCKET = Pattern.compile(
            "^(active|working_set|frequent|rare|restricted)$");
    private static final Pattern INT_RANGE = Pattern.compile("^\\d{1,6}$");
    // v1.5 extension pack validators.
    // nav_open destination: ONE token, no whitespace possible (global
    // hygiene already forbids it); charset is letters/digits plus the
    // few punctuation marks a place token legitimately carries
    // ("Flushing,Queens", "O'Brien", "Stratford-upon-Avon"). The
    // template builder percent-encodes the token into the
    // google.navigation:q= value, so even an accepted character can
    // never break out of the q= value or add a second URI parameter
    // (& and = are not in the charset at all).
    private static final Pattern DEST_TOKEN = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9,.'\\-_]{0,63}$");
    private static final Pattern VOL_DIR = Pattern.compile("^(raise|lower)$");
    private static final Pattern VOL_SET = Pattern.compile("^\\d{1,2}$");
    // v1.5.1 Galaxy Buds plugin routing validators (tokens are the
    // plugin contract's fixed vocabularies — design note §7.3).
    private static final Pattern VOL_PCT = Pattern.compile("^\\d{1,3}$");
    private static final Pattern BUDS_DIR = Pattern.compile("^(up|down)$");
    private static final Pattern NOISE_MODE = Pattern.compile(
            "^(anc|ambient|off|adaptive)$");
    private static final Pattern AMB_VOL = Pattern.compile("^[1-5]$");
    private static final Pattern EQ_PRESET = Pattern.compile(
            "^(off|bass_boost|soft|dynamic|clear|treble_boost)$");
    private static final Pattern ON_OFF = Pattern.compile("^(on|off)$");
    private static final Pattern ROUTINE_KEY = Pattern.compile(
            "^(sleep|home|away|morning|theater|driving|exercise|work|relax|before_bed|commute_home|commute_work|meeting|power_standard|power_max|status_full)$");
    private static final Pattern SOUND_MODE = Pattern.compile("^(sound|vibrate|mute)$");
    // v1.13 Muse Wear Installer routing validators (checkWearInstall).
    private static final Pattern WEAR_LABEL = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern SHA256_HEX = Pattern.compile(
            "^[0-9a-f]{64}$");
    // v1.24 phone file management validators (tables live in FilePlan).
    private static final Pattern FILE_OFFSET = Pattern.compile("^\\d{1,7}$");
    // v1.25 screen sharing validators: normalized coordinates are
    // 0..1000 on each axis (range-checked after the shape check);
    // swipe duration is bounded so a stroke can never be a press-
    // and-hold accident or a no-op flick.
    private static final Pattern COORD = Pattern.compile("^\\d{1,4}$");
    private static final Pattern SWIPE_MS = Pattern.compile("^\\d{2,4}$");
    private static final Pattern SCREEN_KEY = Pattern.compile(
            "^(back|home|recents|notifications|quick_settings|lock)$");
    // STREAM_MUSIC range on this device, proven 2026-11-08 via
    // `cmd media_session volume --stream 3 --get` ("range [0..15]").
    private static final int MEDIA_VOL_MAX = 15;

    private static boolean ok(Pattern p, String v) {
        return v != null && p.matcher(v).matches();
    }

    // ---- template table --------------------------------------------------
    // Each entry: name -> argv builder. {slot} markers are replaced by
    // validated args in order. Anything not in this table is refused.

    public static final String[] TEMPLATES = {
            "getprop_model",        // getprop ro.product.model
            "battery",              // dumpsys battery
            "standby_get",          // am get-standby-bucket <pkg>
            "standby_set_active",   // am set-standby-bucket <pkg> active
            "settings_get",         // settings get <ns> <key>
            "settings_put",         // settings put <ns> <key> <value>
            "role_holders",         // cmd role get-role-holders android.app.role.<ROLE>
            "deviceidle_whitelist", // dumpsys deviceidle whitelist
            "logcat_tail",          // logcat -d -t <lines>
            "dropbox_print",        // dumpsys dropbox --print
            "voicemail_role_borrow",   // cmd role add-role-holder android.app.role.DIALER <pkg>
            "voicemail_role_restore",  // cmd role remove-role-holder android.app.role.DIALER <pkg>
            // ---- v1.5 extension pack (2026-11-08) ----
            "app_launch",           // am start -a MAIN -c LAUNCHER <pkg>
            "nav_open",             // am start -a VIEW -d google.navigation:q=<enc> <maps pkg>
            "media_play_pause",     // input keyevent 85
            "media_next",           // input keyevent 87
            "media_previous",       // input keyevent 88
            "media_volume",         // cmd media_session volume --stream 3 --adj raise|lower
            "media_volume_set",     // cmd media_session volume --stream 3 --set 0..15
            "media_volume_get",     // cmd media_session volume --stream 3 --get
            "dnd_on",               // cmd notification set_dnd on
            "dnd_off",              // cmd notification set_dnd off
            "screen_foreground",    // dumpsys window displays | grep -m 1 mCurrentFocus
            // ---- v1.26 Duo companion — REMOVED in v1.31.0 ----
            // duo_panels_on (setprop persist.debug.phh.duo.panels
            // true) is GONE: the alpha two-panel policy stays OFF
            // (Jonathan's standing order), and the extraction must
            // not carry an ensure-ON path. duo_panels_get survives
            // as a NATIVE template below, routed to the Duo
            // Companion plugin, which reads the prop read-only.
    };

    // ---- v1.5 native (on-device, NOT shell) templates -------------------
    // flashlight + voice_mode cannot be shell commands on this build
    // (no shell torch service exists; voice mode is the accessibility
    // trick). They are gated HERE, in the same class, with the same
    // hygiene + enum discipline, and executed by NativeCommands /
    // VoiceModeModule on-device — never as shell text.
    public static final String[] NATIVE_TEMPLATES = {
            "flashlight",        // args: on|off  -> CameraManager torch
            "flashlight_status", // args: none    -> last torch-callback state
            "voice_mode",        // args: toggle|on|off -> voice module (own gate inside)
            // ---- v1.32 standalone wake/unlock (2026-11-23) ----
            // args: none -> UnlockRouter: wakes the display and
            // dismisses a swipe/none keyguard via the v1.19
            // UnlockActivity trampoline, WITHOUT starting a voice
            // session. Built for the Duo, whose profile gates the
            // voice surface that previously carried the only
            // unlock path (Jonathan's order: app-only work in the
            // Google Home app on the Duo needs the Duo awake).
            // A secure lock still stops at the credential prompt —
            // automation never touches credentials.
            "unlock",
            // ---- v1.5.1 Galaxy Buds plugin routing ----
            // The host routes by namespace only; all Buds semantics
            // live in the Muse Galaxy Buds plugin app (design note
            // §7.3). Ops are executed by BudsRouter, never as shell.
            "buds_status",         // args: none          -> plugin GET_STATUS
            "buds_volume",         // args: up|down       -> plugin SET_VOLUME step
            "buds_volume_set",     // args: 0..100        -> plugin SET_VOLUME set
            "buds_noise",          // args: anc|ambient|off|adaptive
            "buds_ambient_volume", // args: 1..5
            "buds_eq",             // args: off|bass_boost|soft|dynamic|clear|treble_boost
            "buds_touch_lock",     // args: on|off
            // ---- v1.13 Muse Wear Installer routing (2026-11-11) ----
            // args: <label> <https-url> <sha256> -> WearInstallRouter:
            // the phone downloads the watch-face APK, verifies its
            // sha256, relays it to Muse Wear Installer on the watch
            // over the Data Layer, and awaits the on-watch verdict.
            // Gated by the dedicated checkWearInstall() validator —
            // its args are typed values, never shell text.
            "wear_install_face",
            // ---- v1.14 voice bridge injection ----
            // args: <word> <word> ... -> VoiceBridge: the words are
            // joined with single spaces and typed into the Muse
            // app's main-conversation composer by the accessibility
            // service, then sent AS Jonathan (a voice session only
            // hears the conversation's own user turns). Gated by
            // the dedicated checkVoiceBridge() validator — free
            // text can never satisfy shared hygiene(), and it never
            // becomes shell text.
            "voice_bridge",
            // ---- v1.15 combined voice call ----
            // args: NONE. One mechanical fire: start voice mode,
            // return to the previous app (PiP), then type the FIXED
            // marker text (VoiceBridge.VOICE_CONTEXT_MARKER) as
            // Jonathan. No semantics ride this command — the firing
            // Muse writes ~/workspace/voice_context.md first, and
            // the session-side Muse reads it before speaking
            // (Jonathan's design, 2026-11-11: the firing is fast
            // and mechanical; the context file is the semantic
            // portion). Executed by VoiceBridge.executeCombined.
            "voice_call",
            // ---- v1.21 loopback self-update ----
            // args: <url> <sha256> -> SelfUpdateRouter: the app
            // downloads its OWN update APK, verifies the sha256,
            // and installs it through the loopback shell bridge
            // (cp to /data/local/tmp + pm install -r, argv fully
            // hardcoded in the router) — so DA releases never again
            // depend on an external ADB lane being authorized. The
            // command can only ever name THIS app's update payload:
            // the router installs exactly one staged file as exactly
            // this package. Gated by the dedicated
            // checkSelfUpdate() validator (URL + sha slots, same
            // reasoning as checkWearInstall: typed values, never
            // shell text).
            "self_update",
            // ---- v1.24 phone file management ----
            // Folder tokens + direct-child file names are TYPED
            // values consumed by FileRouter via java.io.File —
            // never shell text (real download names carry spaces
            // and punctuation the shared hygiene() bans). Gated
            // by the dedicated checkFiles() validator.
            "files_access",    // args: none -> access state + folder presence
            "files_summary",   // args: none -> per-folder counts/bytes/newest
            "files_list",      // args: <folder> [offset] | <folder> :: <segment...> [offset] -> one page, newest first (v1.36 subfolders)
            "files_get",       // args: <folder> [segment... ::] <name...> -> fetch one image via the relay (v1.36)
            "files_hash",      // args: <folder> <name...> -> sha256 of one file
            "files_move",      // args: <folder> <dest-folder> <name...>
            "files_trash",     // args: <folder> <name...> -> move to .muse-trash
            "files_organize",  // args: <folder> -> file loose files by extension
            // ---- v1.25 screen sharing ----
            // Executed by ScreenRouter (design:
            // docs/v1.25-screen-sharing-design-2026-11-13.md).
            // Coordinates are normalized 0..1000 on both axes,
            // scaled to pixels on-device. screen_text /
            // screen_find_tap carry free text and are gated by the
            // dedicated checkScreenText() — typed values handed to
            // the accessibility service, never shell text.
            "screen_status",   // args: none -> module/stream state
            "screen_shot",     // args: none -> one accessibility snapshot, uploaded
            "screen_start",    // args: none -> request MediaProjection consent + stream
            "screen_stop",     // args: none -> stop the stream
            "screen_tap",      // args: <x 0..1000> <y 0..1000>
            "screen_swipe",    // args: <x1> <y1> <x2> <y2> <ms 50..2000>
            "screen_key",      // args: <back|home|recents|notifications|quick_settings|lock>
            "screen_text",     // args: <words...> -> type into the focused field
            "screen_find_tap", // args: <label words...> -> tap the exactly-labelled node
            // ---- v1.27 lag fix: intent-shaped templates NATIVIZED ----
            // (docs/lag-diagnosis-2026-11-18.md). These templates
            // used to be shell argv (`am start ...`, `input keyevent`,
            // `cmd media_session ...`, a 45 KB dumpsys) — every one
            // of them hostage to the Wi-Fi-bound loopback ADB
            // connection, which is why nav_open took 260 s to fail
            // on cellular on 2026-11-18. They are now gated HERE and
            // executed by NativeCommands with direct framework calls
            // (startActivity from the accessibility-service context,
            // AudioManager) that work on any network. The shell
            // forms REMAIN in TEMPLATES/check() as the fallback:
            // ShellBridge retries through the shell gate only when
            // the native launch reports bal_blocked AND the ADB lane
            // is ready (the at-home case). dnd_on/dnd_off are
            // native-first too (NotificationManager), with one
            // honest caveat: the native form needs the Notification
            // Policy Access grant, which Jonathan has not given.
            // Without it the native op refuses with the typed
            // policy_access_not_granted error and ShellBridge
            // falls back to the shell form (which needs no grant)
            // when the ADB lane is ready; the grant state rides in
            // the report snapshot as shell_bridge.dnd_policy_access.
            "app_launch",        // args: <pkg> -> getLaunchIntentForPackage, verified
            "nav_open",          // args: <dest-token> -> google.navigation: intent, verified
            "media_play_pause",  // args: none -> media key dispatch
            "media_next",        // args: none
            "media_previous",    // args: none
            "media_volume",      // args: raise|lower -> AudioManager adjust
            "media_volume_set",  // args: 0..15 -> AudioManager set
            "media_volume_get",  // args: none -> AudioManager read
            "screen_foreground", // args: none -> accessibility foreground cache
            "dnd_on",            // args: none -> NotificationManager priority filter
            "dnd_off",           // args: none -> NotificationManager all filter
            // ---- v1.28 virtual-appointment session start ----
            // args: <url-chunk> [chunk...] -> NativeCommands.openLink:
            // the chunks reassemble (concatenation, no separator)
            // into ONE https meeting URL, which is opened with an
            // ACTION_VIEW intent from the accessibility-service
            // context and foreground-verified against the expected
            // target app for the host. Chunking exists because the
            // device relay slices every arg at 256 chars
            // (CMD_MAX_ARGS 16, capStr 256 in the relay's enqueue
            // handler): a single-arg URL would be silently
            // truncated past 256 chars. Callers split the URL at
            // 200 chars; most join links (the proof shape is a
            // ~60-char zoom.us/j/<id>?pwd=... URL) fit in one chunk.
            // Gated by the dedicated checkLinkOpen() validator —
            // the URL can never satisfy shared hygiene() ('?', '&',
            // '=' are legitimate here) and NEVER becomes shell text.
            "link_open",
            // ---- Duo plugin routing (v1.29 debug; v1.31.0 extraction) ----
            // args: NONE. Fixed ops routed by DuoRouter to the
            // Muse Duo Companion plugin (the template name is the
            // whole instruction, no caller-supplied value exists
            // to smuggle). duo_debug_on stamps the 60-minute TTL;
            // duo_debug_get reads back the EFFECTIVE state (an
            // expired flag reads off); duo_panels_get reads the
            // DUO-DE two-panel prop READ-ONLY (the plugin never
            // sets it; duo_panels_on was deleted in v1.31.0).
            // Never shell text.
            // ---- v1.30 voicemail module ----
            // args: none -> VoicemailModule: gated export, refused
            // typed when the module is disabled in-app.
            "voicemail_export",
            // ---- v1.35 routine firing + ringer (MacroDroid retirement) ----
            // routine_fire replays a Samsung-minted shortcut intent
            // stored by the in-app link flow (RoutineFirer). It fires
            // ONLY on this explicit command: no reconciler, desired
            // poll, or arm switch anywhere in the app can reach it
            // (the v1.23 lesson). Phone profile only; the executor
            // refuses typed on TV/Duo. sound_set is the ringer-mode
            // leg of routine-control's sound branch (AudioManager).
            "routine_fire",      // args: <registry-key> -> RoutineFirer.fire
            "sound_set",         // args: <sound|vibrate|mute> -> AudioManager ringer
            // ---- v1.37 local pairing fix ----
            // args: none -> NativeCommands: opens the Muse
            // Launcher Pack pairing window on this phone
            // (LocalChannel.openPairingWindow), so pairing no
            // longer requires an in-app tap in Device Assistance.
            // Phone profile only; the executor refuses typed on
            // TV/Duo profiles.
            "local_pair_open",
            "duo_debug_on",        // args: none -> plugin: set Duo debug flag
            "duo_debug_off",       // args: none -> plugin: clear Duo debug flag
            "duo_debug_get",       // args: none -> plugin: effective debug state
            "duo_panels_get",      // args: none -> plugin: read panels prop (read-only)
    };

    public static boolean isNativeTemplate(String template) {
        if (template == null) return false;
        for (String t : NATIVE_TEMPLATES) if (t.equals(template)) return true;
        return false;
    }

    /** Result of a native gate check: the op to run on-device. */
    public static final class NativeGate {
        public final boolean allowed;
        public final String op;
        public final String refusal;
        /** Validated single arg for routed ops (plugin verbs); null
         *  for the fixed ops that encode everything in the op name. */
        public final String arg;
        /** Validated multi-arg payload for routed ops that need more
         *  than one slot (v1.13: wear_install_face carries label +
         *  url + sha256). Null for every op that predates it. */
        public final String[] args;

        NativeGate(boolean allowed, String op, String refusal) {
            this(allowed, op, refusal, null, null);
        }

        NativeGate(boolean allowed, String op, String refusal, String arg) {
            this(allowed, op, refusal, arg, null);
        }

        NativeGate(boolean allowed, String op, String refusal, String arg,
                   String[] args) {
            this.allowed = allowed;
            this.op = op;
            this.refusal = refusal;
            this.arg = arg;
            this.args = args;
        }
    }

    private static NativeGate nRefuse(String why) {
        return new NativeGate(false, null, why);
    }

    private static NativeGate nAllow(String op) {
        return new NativeGate(true, op, null);
    }

    /** Gate a native (on-device) template. Same hygiene as shell. */
    public static NativeGate checkNative(String template, List<String> args) {
        if (template == null) return nRefuse("no template");
        if (args == null) args = new ArrayList<>();
        // v1.13: wear_install_face is gated by its OWN validator,
        // before shared hygiene(). Its slots carry an HTTPS URL and a
        // sha256 — a URL can never satisfy hygiene()'s charset (which
        // bans '?', '&', '=' and caps args at 256 chars). Those bans
        // exist because shell argv is joined with single spaces; these
        // args NEVER become shell text — WearInstallRouter consumes
        // them as typed values (an HTTPS fetch and a hash compare).
        // checkWearInstall() re-implements hygiene's protections per
        // slot: strict label charset, https-only URL with a host and
        // no whitespace/quotes/control characters, exact sha256 shape.
        if ("wear_install_face".equals(template)) {
            return checkWearInstall(args);
        }
        // v1.14: voice_bridge is gated by its OWN validator, before
        // shared hygiene(), for the same reason as wear: its slots
        // carry free text (spaces, punctuation) that hygiene()'s
        // charset bans outright. The text NEVER becomes shell —
        // VoiceBridge hands it to the accessibility service, which
        // types it into the Muse app's composer as a typed value.
        // checkVoiceBridge() re-implements the protections that
        // matter here: non-empty, per-arg and total length caps,
        // no control characters, no quote/backtick characters.
        if ("voice_bridge".equals(template)) {
            return checkVoiceBridge(args);
        }
        // v1.21: self_update is gated by its OWN validator, before
        // shared hygiene(), for the same reason as wear_install_face:
        // its slots carry a URL and a sha256 that hygiene()'s charset
        // can never accept, and they NEVER become shell text — the
        // router's shell argv (cp + pm install) is fully hardcoded.
        if ("self_update".equals(template)) {
            return checkSelfUpdate(args);
        }
        // v1.24: files_* are gated by their OWN validator, before
        // shared hygiene(), for the same reason as voice_bridge:
        // a file name legitimately carries spaces and punctuation
        // hygiene() bans for shell args, and these values NEVER
        // become shell text — FileRouter consumes them as typed
        // values (a code-mapped folder token + a direct-child name
        // via java.io.File). checkFiles() re-implements the
        // protections that matter per slot.
        if (template.startsWith("files_")) {
            return checkFiles(template, args);
        }
        // v1.25: screen_text / screen_find_tap carry free text (a
        // label can contain spaces and punctuation), gated by their
        // OWN validator before shared hygiene(), for the same
        // reason as voice_bridge: the text NEVER becomes shell —
        // ScreenGestures hands it to the accessibility service as a
        // typed value. checkScreenText() re-implements the caps and
        // control-character bans that matter here.
        if ("screen_text".equals(template) || "screen_find_tap".equals(template)) {
            return checkScreenText(template, args);
        }
        // v1.28: link_open is gated by its OWN validator, before
        // shared hygiene(), for the same reason as wear_install_face:
        // its slots carry chunks of an https URL that hygiene()'s
        // charset can never accept, and they NEVER become shell
        // text — NativeCommands consumes the reassembled URL as a
        // typed Uri value in an ACTION_VIEW intent. checkLinkOpen()
        // re-implements hygiene's protections per chunk plus the
        // meeting-host allowlist.
        if ("link_open".equals(template)) {
            return checkLinkOpen(args);
        }
        String bad = hygiene(args);
        if (bad != null) return nRefuse(bad);
        switch (template) {
            case "flashlight":
                if (args.size() != 1) return nRefuse("needs <on|off>");
                if ("on".equals(args.get(0))) return nAllow("torch_on");
                if ("off".equals(args.get(0))) return nAllow("torch_off");
                return nRefuse("needs <on|off>");
            case "flashlight_status":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("torch_status");
            case "voice_mode":
                if (args.size() != 1) return nRefuse("needs <toggle|on|off>");
                if ("toggle".equals(args.get(0))) return nAllow("voice_toggle");
                if ("on".equals(args.get(0))) return nAllow("voice_on");
                if ("off".equals(args.get(0))) return nAllow("voice_off");
                return nRefuse("needs <toggle|on|off>");
            case "voice_call":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("voice_call");
            case "voicemail_export":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("voicemail_export");
            // ---- v1.29 Duo debug mode (fixed, zero args) ----
            case "duo_debug_on":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("duo_debug_on");
            case "duo_debug_off":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("duo_debug_off");
            case "duo_debug_get":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("duo_debug_get");
            case "duo_panels_get":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("duo_panels_get");
            // ---- v1.5.1 Galaxy Buds plugin routing ----
            case "buds_status":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("buds_status");
            case "buds_volume":
                if (args.size() != 1 || !ok(BUDS_DIR, args.get(0))) {
                    return nRefuse("needs <up|down>");
                }
                return nAllow("up".equals(args.get(0)) ? "buds_vol_up" : "buds_vol_down");
            case "buds_volume_set": {
                if (args.size() != 1 || !ok(VOL_PCT, args.get(0))) {
                    return nRefuse("needs <0..100>");
                }
                int p = Integer.parseInt(args.get(0));
                if (p < 0 || p > 100) return nRefuse("volume out of range");
                return new NativeGate(true, "buds_vol_set", null, args.get(0));
            }
            case "buds_noise":
                if (args.size() != 1 || !ok(NOISE_MODE, args.get(0))) {
                    return nRefuse("needs <anc|ambient|off|adaptive>");
                }
                return new NativeGate(true, "buds_noise", null, args.get(0));
            case "buds_ambient_volume":
                if (args.size() != 1 || !ok(AMB_VOL, args.get(0))) {
                    return nRefuse("needs <1..5>");
                }
                return new NativeGate(true, "buds_ambvol", null, args.get(0));
            case "buds_eq":
                if (args.size() != 1 || !ok(EQ_PRESET, args.get(0))) {
                    return nRefuse("needs <off|bass_boost|soft|dynamic|clear|treble_boost>");
                }
                return new NativeGate(true, "buds_eq", null, args.get(0));
            case "buds_touch_lock":
                if (args.size() != 1 || !ok(ON_OFF, args.get(0))) {
                    return nRefuse("needs <on|off>");
                }
                return new NativeGate(true, "buds_lock", null, args.get(0));
            // ---- v1.32 standalone wake/unlock ----
            case "unlock":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return new NativeGate(true, "unlock", null, null,
                        new String[0]);
            // ---- v1.25 screen sharing (typed values ride args) ----
            case "screen_status":
            case "screen_shot":
            case "screen_start":
            case "screen_stop":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return new NativeGate(true, template, null, null,
                        new String[0]);
            case "screen_tap": {
                if (args.size() != 2 || !coordOk(args.get(0)) || !coordOk(args.get(1))) {
                    return nRefuse("needs <x 0..1000> <y 0..1000>");
                }
                return new NativeGate(true, template, null, null,
                        new String[]{args.get(0), args.get(1)});
            }
            case "screen_swipe": {
                if (args.size() != 5
                        || !coordOk(args.get(0)) || !coordOk(args.get(1))
                        || !coordOk(args.get(2)) || !coordOk(args.get(3))
                        || !ok(SWIPE_MS, args.get(4))) {
                    return nRefuse("needs <x1> <y1> <x2> <y2> <ms 50..2000>");
                }
                int ms = Integer.parseInt(args.get(4));
                if (ms < 50 || ms > 2000) return nRefuse("swipe ms out of range");
                return new NativeGate(true, template, null, null,
                        new String[]{args.get(0), args.get(1), args.get(2),
                                args.get(3), args.get(4)});
            }
            case "screen_key":
                if (args.size() != 1 || !ok(SCREEN_KEY, args.get(0))) {
                    return nRefuse("needs <back|home|recents|notifications|quick_settings|lock>");
                }
                return new NativeGate(true, template, null, null,
                        new String[]{args.get(0)});
            // ---- v1.27 nativized intent-shaped templates ----
            // Same validators as the shell forms in check() — the
            // gate discipline is unchanged; only the executor moved.
            // Validated values ride NativeGate.arg (typed values,
            // consumed by NativeCommands; never shell text).
            case "app_launch":
                if (args.size() != 1 || !ok(PKG, args.get(0))) {
                    return nRefuse("needs <pkg>");
                }
                return new NativeGate(true, "app_launch", null, args.get(0));
            case "nav_open":
                if (args.size() != 1 || !ok(DEST_TOKEN, args.get(0))) {
                    return nRefuse("needs <destination-token>");
                }
                return new NativeGate(true, "nav_open", null, args.get(0));
            case "media_play_pause":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("media_play_pause");
            case "media_next":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("media_next");
            case "media_previous":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("media_previous");
            case "media_volume":
                if (args.size() != 1 || !ok(VOL_DIR, args.get(0))) {
                    return nRefuse("needs <raise|lower>");
                }
                return nAllow("raise".equals(args.get(0))
                        ? "media_vol_raise" : "media_vol_lower");
            case "media_volume_set": {
                if (args.size() != 1 || !ok(VOL_SET, args.get(0))) {
                    return nRefuse("needs <0.." + MEDIA_VOL_MAX + ">");
                }
                int v = Integer.parseInt(args.get(0));
                if (v < 0 || v > MEDIA_VOL_MAX) return nRefuse("volume out of range");
                return new NativeGate(true, "media_vol_set", null, args.get(0));
            }
            case "media_volume_get":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("media_vol_get");
            case "screen_foreground":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("screen_foreground");
            case "dnd_on":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("dnd_on");
            case "dnd_off":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("dnd_off");
            // ---- v1.35 routine firing + ringer ----
            case "routine_fire":
                if (args.size() != 1 || !ok(ROUTINE_KEY, args.get(0))) {
                    return nRefuse("needs <routine-key>");
                }
                return new NativeGate(true, "routine_fire", null, args.get(0));
            case "sound_set":
                if (args.size() != 1 || !ok(SOUND_MODE, args.get(0))) {
                    return nRefuse("needs <sound|vibrate|mute>");
                }
                return new NativeGate(true, "sound_set", null, args.get(0));
            // ---- v1.37 local pairing fix ----
            case "local_pair_open":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return nAllow("local_pair_open");
            default:
                return nRefuse("unknown native template");
        }
    }

    /** Normalized coordinate shape + range (0..1000). */
    private static boolean coordOk(String v) {
        if (!ok(COORD, v)) return false;
        int n = Integer.parseInt(v);
        return n >= 0 && n <= 1000;
    }

    /**
     * The screen text gate (v1.25): screen_text's words and
     * screen_find_tap's label words, joined with single spaces into
     * the one typed value ScreenRouter consumes. Rules: at least
     * one arg; each arg 1..256 chars; joined total at most 1000
     * chars; no control characters (the text is typed into a node /
     * matched against node labels — it is never parsed, never
     * shell). The joined text rides NativeGate.arg.
     */
    private static NativeGate checkScreenText(String template, List<String> args) {
        if (args.isEmpty()) return nRefuse("needs <text...>");
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (a == null || a.isEmpty() || a.length() > 256) {
                return nRefuse("bad text arg");
            }
            for (int i = 0; i < a.length(); i++) {
                char c = a.charAt(i);
                if (c < 0x20 || c == 0x7F) return nRefuse("bad text character");
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(a);
            if (sb.length() > 1000) return nRefuse("text too long");
        }
        return new NativeGate(true, template, null, sb.toString());
    }

    /**
     * The voice_bridge gate (v1.14): the command's args are the
     * words of one bridge message, joined here with single spaces
     * into the single typed value VoiceBridge consumes. Rules:
     * at least one arg; each arg 1..256 chars; joined total at
     * most 2000 chars; no control characters and no quote /
     * backtick characters in any arg (the text is logged and
     * rendered, never parsed — this is belt-and-braces). The
     * joined text rides NativeGate.arg.
     */
    private static NativeGate checkVoiceBridge(List<String> args) {
        if (args.isEmpty()) return nRefuse("needs <text...>");
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (a == null || a.isEmpty() || a.length() > 256) {
                return nRefuse("bad text arg");
            }
            for (int i = 0; i < a.length(); i++) {
                char c = a.charAt(i);
                if (c < 0x20 || c == 0x7F || c == '"' || c == '\''
                        || c == '`' || c == '\\') {
                    return nRefuse("bad text character");
                }
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(a);
            if (sb.length() > 2000) return nRefuse("text too long");
        }
        return new NativeGate(true, "voice_bridge", null, sb.toString());
    }

    /**
     * The wear_install_face gate (v1.13): three typed slots —
     * label, https URL, sha256 — validated individually. See the
     * checkNative() call-site comment for why this template cannot
     * ride the shared hygiene() pass. The URL rules: https scheme
     * only, must parse with a non-empty host, no userinfo, no
     * whitespace / control / quote / backtick / angle characters,
     * 10..2048 chars. The label is a file-safe token (it is shown on
     * the watch and used in log lines only). The sha256 must be the
     * exact lowercase-hex digest the phone re-verifies after download
     * and the watch re-verifies after transfer.
     */
    private static NativeGate checkWearInstall(List<String> args) {
        if (args.size() != 3) return nRefuse("needs <label> <url> <sha256>");
        String label = args.get(0);
        String url = args.get(1);
        String sha = args.get(2);
        if (!ok(WEAR_LABEL, label)) return nRefuse("bad label");
        if (!ok(SHA256_HEX, sha)) return nRefuse("bad sha256");
        if (url == null || url.length() < 10 || url.length() > 2048) {
            return nRefuse("bad url length");
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (Character.isWhitespace(c) || c < 0x20 || c == 0x7F
                    || c == '"' || c == '\'' || c == '`' || c == '\\'
                    || c == '<' || c == '>' || c == '#') {
                return nRefuse("bad url character");
            }
        }
        java.net.URI u;
        try {
            u = new java.net.URI(url);
        } catch (Exception e) {
            return nRefuse("unparseable url");
        }
        if (!"https".equalsIgnoreCase(u.getScheme())) {
            return nRefuse("url must be https");
        }
        if (u.getHost() == null || u.getHost().isEmpty()) {
            return nRefuse("url has no host");
        }
        if (u.getUserInfo() != null) return nRefuse("url userinfo refused");
        return new NativeGate(true, "wear_install", null, null,
                new String[]{label, url, sha});
    }

    /** The one cleartext host a self_update URL may use: the Hetzner
     *  box's Tailscale address — the same single host the manifest's
     *  network security config has whitelisted for cleartext since
     *  v1.12 (the relay LAN). Every other http URL is refused. */
    private static final String UPDATE_CLEARTEXT_HOST = "100.85.53.70";

    /**
     * The self_update gate (v1.21): two typed slots — URL, sha256.
     * URL rules mirror checkWearInstall (length, character bans,
     * parseable, host present, no userinfo) with ONE widening and
     * ONE narrowing. Widening: plain http is allowed ONLY to
     * UPDATE_CLEARTEXT_HOST (the box tailnet endpoint already
     * sanctioned for cleartext in the manifest); https is allowed
     * to any host. Narrowing vs wear: nothing else — the payload is
     * additionally pinned by the sha256, which the router verifies
     * streaming before anything is staged, and the staged file can
     * only ever be installed as this app (pm refuses a mismatched
     * package/signature), so a hostile payload cannot become another
     * app or a different signer.
     */
    private static NativeGate checkSelfUpdate(List<String> args) {
        if (args.size() != 2) return nRefuse("needs <url> <sha256>");
        String url = args.get(0);
        String sha = args.get(1);
        if (!ok(SHA256_HEX, sha)) return nRefuse("bad sha256");
        if (url == null || url.length() < 10 || url.length() > 2048) {
            return nRefuse("bad url length");
        }
        for (int i = 0; i < url.length(); i++) {
            char c = url.charAt(i);
            if (Character.isWhitespace(c) || c < 0x20 || c == 0x7F
                    || c == '"' || c == '\'' || c == '`' || c == '\\'
                    || c == '<' || c == '>' || c == '#') {
                return nRefuse("bad url character");
            }
        }
        java.net.URI u;
        try {
            u = new java.net.URI(url);
        } catch (Exception e) {
            return nRefuse("unparseable url");
        }
        String scheme = u.getScheme();
        boolean https = "https".equalsIgnoreCase(scheme);
        boolean boxHttp = "http".equalsIgnoreCase(scheme)
                && UPDATE_CLEARTEXT_HOST.equals(u.getHost());
        if (!https && !boxHttp) {
            return nRefuse("url must be https (http only to the box)");
        }
        if (u.getHost() == null || u.getHost().isEmpty()) {
            return nRefuse("url has no host");
        }
        if (u.getUserInfo() != null) return nRefuse("url userinfo refused");
        return new NativeGate(true, "self_update", null, null,
                new String[]{url, sha});
    }

    // ---- v1.28 link_open: the meeting-host allowlist ---------------
    // THE one place the meeting hosts live. A host qualifies when it
    // equals a listed domain or is a subdomain of one marked
    // (subdomains: yes). Deliberately narrow: this verb exists to
    // start Jonathan's virtual appointments (telehealth + work
    // meetings), not to open arbitrary links — any other host is a
    // typed refusal, never a fallback open.
    //   zoom.us              (subdomains: yes — us02web.zoom.us etc.)
    //   meet.google.com      (exact)
    //   teams.microsoft.com  (exact)
    //   teams.live.com       (exact)
    //   webex.com            (subdomains: yes — <org>.webex.com)
    //   gethealthie.com      (subdomains: yes — secure.gethealthie.com)
    //   doxy.me              (subdomains: yes — <clinic>.doxy.me)
    //   teladoc.com          (subdomains: yes — member.teladoc.com)
    //   amwell.com           (subdomains: yes)
    private static boolean hostIs(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    static boolean meetingHostOk(String host) {
        if (host == null || host.isEmpty()) return false;
        String h = host.toLowerCase(java.util.Locale.US);
        return hostIs(h, "zoom.us")
                || h.equals("meet.google.com")
                || h.equals("teams.microsoft.com")
                || h.equals("teams.live.com")
                || hostIs(h, "webex.com")
                || hostIs(h, "gethealthie.com")
                || hostIs(h, "doxy.me")
                || hostIs(h, "teladoc.com")
                || hostIs(h, "amwell.com");
    }

    /** Max reassembled URL length for link_open. */
    static final int LINK_URL_MAX = 512;
    /** Max chunks: 3 x the relay's 256-char arg slice covers the
     *  512-char cap with room; more chunks is a caller bug. */
    static final int LINK_MAX_CHUNKS = 3;

    /**
     * The link_open gate (v1.28): 1..3 URL chunks, reassembled by
     * plain concatenation into the one URL NativeCommands opens.
     * Per-chunk rules (the relay already slices each arg at 256
     * chars, but the gate does not trust the transport): 1..256
     * chars, no whitespace, no control characters, no quote /
     * backtick / backslash / angle characters. Whole-URL rules:
     * total 10..512 chars; parses as a URI; scheme https ONLY;
     * host present and on the meeting allowlist above; NO userinfo
     * (a userinfo segment is how a lookalike host hides). The
     * reassembled URL rides NativeGate.arg as a typed value.
     */
    private static NativeGate checkLinkOpen(List<String> args) {
        if (args.size() < 1 || args.size() > LINK_MAX_CHUNKS) {
            return nRefuse("needs <url> in 1.." + LINK_MAX_CHUNKS + " chunks");
        }
        StringBuilder sb = new StringBuilder();
        for (String a : args) {
            if (a == null || a.isEmpty() || a.length() > 256) {
                return nRefuse("bad url chunk");
            }
            for (int i = 0; i < a.length(); i++) {
                char c = a.charAt(i);
                if (Character.isWhitespace(c) || c < 0x20 || c == 0x7F
                        || c == '"' || c == '\'' || c == '`' || c == '\\'
                        || c == '<' || c == '>') {
                    return nRefuse("bad url character");
                }
            }
            sb.append(a);
            if (sb.length() > LINK_URL_MAX) return nRefuse("url too long");
        }
        String url = sb.toString();
        if (url.length() < 10) return nRefuse("bad url length");
        java.net.URI u;
        try {
            u = new java.net.URI(url);
        } catch (Exception e) {
            return nRefuse("unparseable url");
        }
        if (!"https".equalsIgnoreCase(u.getScheme())) {
            return nRefuse("url must be https");
        }
        if (u.getHost() == null || u.getHost().isEmpty()) {
            return nRefuse("url has no host");
        }
        if (u.getUserInfo() != null) return nRefuse("url userinfo refused");
        if (!meetingHostOk(u.getHost())) {
            return nRefuse("host not on meeting allowlist: " + u.getHost());
        }
        return new NativeGate(true, "link_open", null, url);
    }

    /**
     * The files gate (v1.24): phone file management ops, gated per
     * slot with FilePlan's fixed vocabularies. Folder slots are
     * folder TOKENS (code-mapped in FilePlan to the public folders —
     * a path can never be named). File-name slots are the words of
     * one direct-child name, joined here with single spaces (the
     * voice_bridge pattern): a real download name can never satisfy
     * shared hygiene(), and it never becomes shell text —
     * FileRouter hands it to java.io.File as a child name only.
     * FilePlan.nameOk refuses separators / parent refs / dotfiles /
     * control characters. Normalized slots ride NativeGate.args:
     * list {folder, offset|""}; hash/trash {folder, name};
     * move {folder, dest, name}; organize {folder}; access/summary {}.
     */
    private static NativeGate checkFiles(String template, List<String> args) {
        switch (template) {
            case "files_access":
            case "files_summary":
                if (!args.isEmpty()) return nRefuse("takes no args");
                return new NativeGate(true, template, null, null, new String[0]);
            case "files_organize":
                if (args.size() != 1 || !FilePlan.folderOk(args.get(0))) {
                    return nRefuse("needs <folder>");
                }
                return new NativeGate(true, template, null, null,
                        new String[]{args.get(0)});
            case "files_list": {
                // v1.24 shape, unchanged: <folder> [offset].
                // v1.36 subfolder shape: <folder> :: <segment...>
                // [offset]. The "::" marker is what switches shapes,
                // so the legacy contract survives exactly: without
                // it, a non-numeric second arg is still a bad offset,
                // never a surprise subfolder. Each segment is ONE
                // argv element (quoted by the caller when it
                // contains spaces). Inside the :: section a trailing
                // numeric arg is the offset only when at least one
                // segment precedes it, so a numeric-only subfolder
                // name works bare (files_list dcim :: 2024) and with
                // an explicit offset (files_list dcim :: 2024 0).
                // Gate output shape: {folder, offset, segment...}.
                if (args.size() < 1 || !FilePlan.folderOk(args.get(0))) {
                    return nRefuse("needs <folder> [offset] or <folder> :: <segment...> [offset]");
                }
                if (args.size() >= 2 && "::".equals(args.get(1))) {
                    List<String> segArgs = new ArrayList<>(
                            args.subList(2, args.size()));
                    if (segArgs.isEmpty()) {
                        return nRefuse("needs a subfolder segment after ::");
                    }
                    String offset = "";
                    if (segArgs.size() >= 2
                            && ok(FILE_OFFSET, segArgs.get(segArgs.size() - 1))) {
                        offset = segArgs.remove(segArgs.size() - 1);
                    }
                    if (segArgs.size() > FilePlan.MAX_SUB_DEPTH) {
                        return nRefuse("subfolder chain too deep");
                    }
                    String[] out = new String[2 + segArgs.size()];
                    out[0] = args.get(0);
                    out[1] = offset;
                    for (int i = 0; i < segArgs.size(); i++) {
                        if (!FilePlan.segmentOk(segArgs.get(i))) {
                            return nRefuse("bad subfolder segment");
                        }
                        out[2 + i] = segArgs.get(i);
                    }
                    if (FilePlan.subPath(out[0],
                            Arrays.copyOfRange(out, 2, out.length)) == null) {
                        return nRefuse("bad subfolder path");
                    }
                    return new NativeGate(true, template, null, null, out);
                }
                if (args.size() > 2) {
                    return nRefuse("needs <folder> [offset] or <folder> :: <segment...> [offset]");
                }
                String offset = "";
                if (args.size() == 2) {
                    if (!ok(FILE_OFFSET, args.get(1))) {
                        return nRefuse("bad offset");
                    }
                    offset = args.get(1);
                }
                return new NativeGate(true, template, null, null,
                        new String[]{args.get(0), offset});
            }
            case "files_get": {
                // v1.36: <folder> [segment... ::] <name...>. The "::"
                // separator arg splits subfolder segments (one argv
                // element each) from the file name (word args joined,
                // the joinName pattern). Without "::", every arg
                // after the folder is the name and the file sits in
                // the folder root. Images only: the gate refuses a
                // name whose extension is not an image extension;
                // the router re-checks type, existence, and the size
                // cap, and the relay re-checks magic bytes + sha256.
                // Gate output shape: {folder, name, segment...}.
                if (args.size() < 2 || !FilePlan.folderOk(args.get(0))) {
                    return nRefuse("needs <folder> [segment... ::] <name...>");
                }
                int sep = -1;
                for (int i = 1; i < args.size(); i++) {
                    if ("::".equals(args.get(i))) { sep = i; break; }
                }
                List<String> segArgs = sep < 0
                        ? new ArrayList<String>()
                        : args.subList(1, sep);
                int nameFrom = sep < 0 ? 1 : sep + 1;
                if (args.size() - nameFrom < 1) {
                    return nRefuse("needs a file name after ::");
                }
                if (segArgs.size() > FilePlan.MAX_SUB_DEPTH) {
                    return nRefuse("subfolder chain too deep");
                }
                for (String s : segArgs) {
                    if (!FilePlan.segmentOk(s)) {
                        return nRefuse("bad subfolder segment");
                    }
                }
                String name = joinName(args, nameFrom);
                if (name == null) return nRefuse("bad file name");
                if (!FilePlan.imageExtOk(name)) {
                    return nRefuse("not_an_image: files_get fetches images only");
                }
                String[] segs = segArgs.toArray(new String[0]);
                if (FilePlan.subPath(args.get(0), segs) == null) {
                    return nRefuse("bad subfolder path");
                }
                String[] out = new String[2 + segs.length];
                out[0] = args.get(0);
                out[1] = name;
                System.arraycopy(segs, 0, out, 2, segs.length);
                return new NativeGate(true, template, null, null, out);
            }
            case "files_hash":
            case "files_trash": {
                if (args.size() < 2 || !FilePlan.folderOk(args.get(0))) {
                    return nRefuse("needs <folder> <name...>");
                }
                String name = joinName(args, 1);
                if (name == null) return nRefuse("bad file name");
                return new NativeGate(true, template, null, null,
                        new String[]{args.get(0), name});
            }
            case "files_move": {
                if (args.size() < 3 || !FilePlan.folderOk(args.get(0))
                        || !FilePlan.folderOk(args.get(1))) {
                    return nRefuse("needs <folder> <dest-folder> <name...>");
                }
                if (args.get(0).equals(args.get(1))) {
                    return nRefuse("source and destination folder are the same");
                }
                String name = joinName(args, 2);
                if (name == null) return nRefuse("bad file name");
                return new NativeGate(true, template, null, null,
                        new String[]{args.get(0), args.get(1), name});
            }
            default:
                return nRefuse("unknown native template");
        }
    }

    /** Join args[from..] with single spaces into one file name and
     *  validate it as a typed value: 1..16 words, each 1..256 chars,
     *  joined at most 512 chars, then FilePlan.nameOk's rules
     *  (no separators, no parent refs, no dotfiles, no controls). */
    private static String joinName(List<String> args, int from) {
        if (args.size() - from > 16) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.size(); i++) {
            String a = args.get(i);
            if (a == null || a.isEmpty() || a.length() > 256) return null;
            if (sb.length() > 0) sb.append(' ');
            sb.append(a);
            if (sb.length() > 512) return null;
        }
        String name = sb.toString();
        return FilePlan.nameOk(name) ? name : null;
    }

    /**
     * Percent-encode a validated nav destination token for the
     * google.navigation:q= value. Keeps only URI-unreserved /
     * query-safe characters raw; everything else becomes %XX. With
     * the DEST_TOKEN charset this is defense-in-depth: accepted
     * tokens are already safe, and any future charset widening
     * cannot smuggle a space, '&', '=', '#', or '%' past it.
     */
    static String pctEncodeDest(String dest) {
        StringBuilder sb = new StringBuilder(dest.length() + 8);
        for (int i = 0; i < dest.length(); i++) {
            char c = dest.charAt(i);
            boolean raw = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')
                    || c == ',' || c == '.' || c == '\'' || c == '-' || c == '_';
            if (raw) {
                sb.append(c);
            } else {
                sb.append('%');
                String hex = Integer.toHexString(c).toUpperCase();
                if (hex.length() < 2) sb.append('0');
                sb.append(hex);
            }
        }
        return sb.toString();
    }

    /** Result of a gate check: either the argv to execute, or a refusal. */
    public static final class Gate {
        public final boolean allowed;
        public final String[] argv;
        public final String refusal;

        Gate(boolean allowed, String[] argv, String refusal) {
            this.allowed = allowed;
            this.argv = argv;
            this.refusal = refusal;
        }
    }

    private static Gate refuse(String why) {
        return new Gate(false, null, why);
    }

    private static Gate allow(String... argv) {
        return new Gate(true, argv, null);
    }

    private static boolean nsOk(String ns) {
        return "system".equals(ns) || "secure".equals(ns) || "global".equals(ns);
    }

    /**
     * Match a structured request against the template table.
     *
     * @param template template name from TEMPLATES
     * @param args     slot values, in template order (may be empty)
     */
    /** Global hygiene, shared by the shell and native gates: no arg
     *  may carry whitespace or shell syntax, whatever the slot — the
     *  bridge joins argv with single spaces for the shell service, so
     *  a space inside an arg WOULD split into a new token. This is
     *  the load-bearing check. Returns a refusal string, or null. */
    private static String hygiene(List<String> args) {
        for (String a : args) {
            if (a == null || a.isEmpty() || a.length() > 256) {
                return "bad arg";
            }
            for (int i = 0; i < a.length(); i++) {
                char c = a.charAt(i);
                if (Character.isWhitespace(c) || ";|&$`\\\"'<>*?~#()[]{}!\n\r".indexOf(c) >= 0) {
                    return "arg contains forbidden character";
                }
            }
        }
        return null;
    }

    public static Gate check(String template, List<String> args) {
        if (template == null) return refuse("no template");
        if (args == null) args = new ArrayList<>();
        String bad = hygiene(args);
        if (bad != null) return refuse(bad);
        switch (template) {
            case "getprop_model":
                if (!args.isEmpty()) return refuse("takes no args");
                return allow("getprop", "ro.product.model");
            case "battery":
                if (!args.isEmpty()) return refuse("takes no args");
                return allow("dumpsys", "battery");
            case "standby_get":
                if (args.size() != 1 || !ok(PKG, args.get(0))) return refuse("needs <pkg>");
                return allow("am", "get-standby-bucket", args.get(0));
            case "standby_set_active":
                if (args.size() != 1 || !ok(PKG, args.get(0))) return refuse("needs <pkg>");
                return allow("am", "set-standby-bucket", args.get(0), "active");
            case "settings_get":
                if (args.size() != 2 || !nsOk(args.get(0)) || !ok(SETTINGS_KEY, args.get(1))) {
                    return refuse("needs <ns> <key>");
                }
                return allow("settings", "get", args.get(0), args.get(1));
            case "settings_put":
                if (args.size() != 3 || !nsOk(args.get(0)) || !ok(SETTINGS_KEY, args.get(1))
                        || !ok(SETTINGS_VALUE, args.get(2))) {
                    return refuse("needs <ns> <key> <value>");
                }
                return allow("settings", "put", args.get(0), args.get(1), args.get(2));
            case "role_holders":
                if (args.size() != 1 || !ok(ROLE, args.get(0))) return refuse("needs <ROLE>");
                return allow("cmd", "role", "get-role-holders",
                        "android.app.role." + args.get(0));
            case "deviceidle_whitelist":
                if (!args.isEmpty()) return refuse("takes no args");
                return allow("dumpsys", "deviceidle", "whitelist");
            case "logcat_tail":
                if (args.size() != 1 || !ok(INT_RANGE, args.get(0))) {
                    return refuse("needs <lines>");
                }
                int lines = Integer.parseInt(args.get(0));
                if (lines < 1 || lines > 5000) return refuse("lines out of range 1..5000");
                return allow("logcat", "-d", "-t", args.get(0));
            case "dropbox_print":
                if (!args.isEmpty()) return refuse("takes no args");
                return allow("dumpsys", "dropbox", "--print");
            case "voicemail_role_borrow":
                if (args.size() != 1 || !ok(PKG, args.get(0))) return refuse("needs <pkg>");
                return allow("cmd", "role", "add-role-holder",
                        "android.app.role.DIALER", args.get(0), "0");
            case "voicemail_role_restore":
                if (args.size() != 1 || !ok(PKG, args.get(0))) return refuse("needs <pkg>");
                return allow("cmd", "role", "remove-role-holder",
                        "android.app.role.DIALER", args.get(0), "0");
            // ---- v1.5 extension pack ----
            // Proven on this phone over the VM ADB lane 2026-11-08
            // before encoding (execution record section (h)):
            case "app_launch":
                // Proven form: the trailing bare arg is the intent
                // package; focus read-back showed the target's
                // MapsActivity foreground after this exact command.
                if (args.size() != 1 || !ok(PKG, args.get(0))) return refuse("needs <pkg>");
                return allow("am", "start", "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.LAUNCHER", args.get(0));
            case "nav_open":
                // Destination is ONE validated token, percent-encoded
                // into the q= value by pctEncodeDest — a destination
                // with a space or '&' is refused by DEST_TOKEN +
                // hygiene before it can ever reach the URI.
                if (args.size() != 1 || !ok(DEST_TOKEN, args.get(0))) {
                    return refuse("needs <destination-token>");
                }
                return allow("am", "start", "-a", "android.intent.action.VIEW",
                        "-d", "google.navigation:q=" + pctEncodeDest(args.get(0)),
                        "com.google.android.apps.maps");
            case "media_play_pause":
                if (!args.isEmpty()) return refuse("takes no args");
                return allow("input", "keyevent", "85");
            case "media_next":
                if (!args.isEmpty()) return refuse("takes no args");
                return allow("input", "keyevent", "87");
            case "media_previous":
                if (!args.isEmpty()) return refuse("takes no args");
                return allow("input", "keyevent", "88");
            case "media_volume":
                if (args.size() != 1 || !ok(VOL_DIR, args.get(0))) {
                    return refuse("needs <raise|lower>");
                }
                return allow("cmd", "media_session", "volume", "--stream", "3",
                        "--adj", args.get(0));
            case "media_volume_set": {
                if (args.size() != 1 || !ok(VOL_SET, args.get(0))) {
                    return refuse("needs <0.." + MEDIA_VOL_MAX + ">");
                }
                int v = Integer.parseInt(args.get(0));
                if (v < 0 || v > MEDIA_VOL_MAX) return refuse("volume out of range");
                return allow("cmd", "media_session", "volume", "--stream", "3",
                        "--set", args.get(0));
            }
            case "media_volume_get":
                if (!args.isEmpty()) return refuse("takes no args");
                return allow("cmd", "media_session", "volume", "--stream", "3", "--get");
            case "dnd_on":
                // `cmd notification set_dnd` exists on this build
                // (help-verified) and flips settings global zen_mode
                // 0 -> 2, read back live 2026-11-08.
                if (!args.isEmpty()) return refuse("takes no args");
                return allow("cmd", "notification", "set_dnd", "on");
            case "dnd_off":
                if (!args.isEmpty()) return refuse("takes no args");
                return allow("cmd", "notification", "set_dnd", "off");
            // duo_panels_on / duo_panels_get shell forms deleted
            // in v1.31.0 (see the TEMPLATES note): unknown here,
            // so the shell gate refuses them like any stranger.
            case "screen_foreground":
                // The ONE fixed-pipe template: template text is a
                // compile-time constant, the template takes ZERO
                // args, and the grep pattern is fixed — there is no
                // arg-controllable surface. It exists because
                // `dumpsys window displays` is ~45 KB with
                // mCurrentFocus at ~byte 3.4 KB, while the loopback
                // executor returns the output TAIL; the raw dump
                // would scroll the one line that matters off the
                // result. Proven via the ADB lane 2026-11-08.
                if (!args.isEmpty()) return refuse("takes no args");
                return allow("dumpsys", "window", "displays", "|",
                        "grep", "-m", "1", "mCurrentFocus");
            default:
                return refuse("unknown template");
        }
    }

    /** Human-readable argv for logs (only ever called on allowed gates). */
    public static String describe(Gate g) {
        return g.allowed ? String.join(" ", g.argv) : ("REFUSED: " + g.refusal);
    }

    // Referenced by BUCKET-using templates if a set-bucket variant is
    // ever added; kept so the validator set is complete in one place.
    static boolean bucketOk(String b) {
        return ok(BUCKET, b);
    }

    static List<String> templateList() {
        return Arrays.asList(TEMPLATES);
    }
}
