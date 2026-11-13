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
    // v1.13 Muse Wear Installer routing validators (checkWearInstall).
    private static final Pattern WEAR_LABEL = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    private static final Pattern SHA256_HEX = Pattern.compile(
            "^[0-9a-f]{64}$");
    // v1.24 phone file management validators (tables live in FilePlan).
    private static final Pattern FILE_OFFSET = Pattern.compile("^\\d{1,7}$");
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
            "files_list",      // args: <folder> [offset] -> one page, newest first
            "files_hash",      // args: <folder> <name...> -> sha256 of one file
            "files_move",      // args: <folder> <dest-folder> <name...>
            "files_trash",     // args: <folder> <name...> -> move to .muse-trash
            "files_organize",  // args: <folder> -> file loose files by extension
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
            default:
                return nRefuse("unknown native template");
        }
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
                if (args.size() < 1 || args.size() > 2
                        || !FilePlan.folderOk(args.get(0))) {
                    return nRefuse("needs <folder> [offset]");
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
    private static String pctEncodeDest(String dest) {
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
