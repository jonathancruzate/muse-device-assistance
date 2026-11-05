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
    };

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
    public static Gate check(String template, List<String> args) {
        if (template == null) return refuse("no template");
        if (args == null) args = new ArrayList<>();
        // Global hygiene: no arg may carry whitespace or shell syntax,
        // whatever the slot — the bridge joins argv with single spaces
        // for the shell service, so a space inside an arg WOULD split
        // into a new token. This is the load-bearing check.
        for (String a : args) {
            if (a == null || a.isEmpty() || a.length() > 256) {
                return refuse("bad arg");
            }
            for (int i = 0; i < a.length(); i++) {
                char c = a.charAt(i);
                if (Character.isWhitespace(c) || ";|&$`\\\"'<>*?~#()[]{}!\n\r".indexOf(c) >= 0) {
                    return refuse("arg contains forbidden character");
                }
            }
        }
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
