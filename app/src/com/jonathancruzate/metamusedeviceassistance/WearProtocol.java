package com.jonathancruzate.metamusedeviceassistance;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WearProtocol — the phone-side copy of the Muse Wear Installer
 * wire contract (the watch app's Protocol class, copied verbatim but
 * for package/class name, per fleet convention — the same way the
 * Buds plugin contract strings are duplicated across host/plugin).
 * Keep the two copies in lockstep: any change here lands in
 * muse-wear-installer Protocol.java in the same handling.
 *
 * Transport: Wearable Data Layer between this phone app and Muse
 * Wear Installer on the paired watch.
 *
 *   offer   phone -> watch  MessageClient  PATH_OFFER
 *           flat JSON {job_id, label, sha256, size, protocol}
 *   payload phone -> watch  ChannelClient  CHANNEL_PREFIX + job_id
 *           raw APK bytes, nothing else on the channel
 *   status  watch -> phone  MessageClient  PATH_STATUS
 *           flat JSON {job_id, label, stage, detail, ts}
 *
 * Stages (watch-reported): received, transferred, verified,
 * prompted, installing, installed, failed, verify_failed, dismissed.
 * Terminal: installed, failed, verify_failed, dismissed.
 *
 * This class is deliberately free of Android dependencies (same
 * discipline as the phone's ShellAllowlist) so the hash + framing
 * logic is unit-testable on a plain JVM: tools/ProtocolTest.
 */
public final class WearProtocol {
    private WearProtocol() {}

    public static final String PATH_OFFER = "/muse-wear-installer/offer";
    public static final String PATH_STATUS = "/muse-wear-installer/status";
    public static final String CHANNEL_PREFIX = "/muse-wear-installer/apk/";
    public static final int PROTOCOL_VERSION = 1;

    /** Upper bound the watch accepts for one payload (64 MB). Faces
     *  are ~600 KB; the cap exists so a bad job cannot fill the disk. */
    public static final long MAX_APK_BYTES = 64L * 1024 * 1024;

    // ---- hashing --------------------------------------------------------

    public static String sha256Hex(InputStream in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        return toHex(md.digest());
    }

    public static String sha256Hex(File f) throws Exception {
        try (InputStream in = new FileInputStream(f)) {
            return sha256Hex(in);
        }
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static String shortSha(String sha) {
        if (sha == null) return "";
        return sha.length() > 12 ? sha.substring(0, 12) : sha;
    }

    // ---- flat JSON (string/long fields only) ----------------------------
    // The payloads are flat by contract, so both ends use this small
    // writer/parser instead of a JSON library: identical behavior on
    // phone and watch, and JVM-testable. The parser tolerates field
    // order and whitespace; it is NOT a general JSON parser and must
    // never be fed nested structures.

    public static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String offerJson(String jobId, String label, String sha256,
                                   long size) {
        return "{\"job_id\":\"" + jsonEscape(jobId)
                + "\",\"label\":\"" + jsonEscape(label)
                + "\",\"sha256\":\"" + jsonEscape(sha256)
                + "\",\"size\":" + size
                + ",\"protocol\":" + PROTOCOL_VERSION + "}";
    }

    public static String statusJson(String jobId, String label, String stage,
                                    String detail, long ts) {
        return "{\"job_id\":\"" + jsonEscape(jobId)
                + "\",\"label\":\"" + jsonEscape(label)
                + "\",\"stage\":\"" + jsonEscape(stage)
                + "\",\"detail\":\"" + jsonEscape(detail == null ? "" : detail)
                + "\",\"ts\":" + ts + "}";
    }

    /** Parse one flat JSON object into string fields (numbers kept as
     *  their literal text). Returns an empty map on malformed input. */
    public static Map<String, String> parseFlat(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null) return out;
        int i = json.indexOf('{');
        if (i < 0) return out;
        i++;
        while (i < json.length()) {
            // key
            i = skipWs(json, i);
            if (i >= json.length() || json.charAt(i) == '}') break;
            if (json.charAt(i) != '"') break;
            StringBuilder key = new StringBuilder();
            i = readString(json, i, key);
            if (i < 0) break;
            i = skipWs(json, i);
            if (i >= json.length() || json.charAt(i) != ':') break;
            i = skipWs(json, i + 1);
            if (i >= json.length()) break;
            String value;
            if (json.charAt(i) == '"') {
                StringBuilder v = new StringBuilder();
                i = readString(json, i, v);
                if (i < 0) break;
                value = v.toString();
            } else {
                int start = i;
                while (i < json.length()
                        && ",}]\" \t\r\n".indexOf(json.charAt(i)) < 0) i++;
                value = json.substring(start, i);
            }
            out.put(key.toString(), value);
            i = skipWs(json, i);
            if (i < json.length() && json.charAt(i) == ',') { i++; continue; }
            break;
        }
        return out;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
        return i;
    }

    /** Read a JSON string starting at the opening quote; appends the
     *  unescaped content; returns the index just past the closing
     *  quote, or -1 on malformed input. */
    private static int readString(String s, int i, StringBuilder out) {
        i++; // opening quote
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"') return i + 1;
            if (c == '\\' && i + 1 < s.length()) {
                char e = s.charAt(i + 1);
                switch (e) {
                    case 'n': out.append('\n'); i += 2; continue;
                    case 'r': out.append('\r'); i += 2; continue;
                    case 't': out.append('\t'); i += 2; continue;
                    case 'u':
                        if (i + 5 < s.length()) {
                            try {
                                out.append((char) Integer.parseInt(
                                        s.substring(i + 2, i + 6), 16));
                                i += 6; continue;
                            } catch (NumberFormatException ignored) {}
                        }
                        return -1;
                    default: out.append(e); i += 2; continue;
                }
            }
            out.append(c);
            i++;
        }
        return -1;
    }

    public static boolean isTerminal(String stage) {
        return "installed".equals(stage) || "failed".equals(stage)
                || "verify_failed".equals(stage) || "dismissed".equals(stage);
    }
}
