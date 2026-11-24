package com.jonathancruzate.metamusedeviceassistance;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * FilePlan — the pure-Java half of the v1.24 phone file management
 * (design: docs/v1.24-phone-files-2026-11-13.md). Everything here is
 * free of Android dependencies so the gate + the filing rules can be
 * unit-tested on a plain JVM (tools/ShellAllowlistTest), exactly like
 * ShellAllowlist itself. FileRouter (the Android half) consumes
 * these tables and rules and does the actual java.io.File work.
 *
 * Fixed vocabularies, never user paths:
 *  - Folder tokens map in code to the public shared-storage folders.
 *    A command can name a token, never a path, so no command can
 *    reach outside the public folders (plus the app's trash dir).
 *  - Category names map in code from file extensions — the
 *    organizer's filing rules. A file whose extension has no
 *    category is never moved by files_organize.
 */
public final class FilePlan {

    private FilePlan() {}

    // ---- public folders --------------------------------------------------

    public static final String STORAGE_ROOT = "/storage/emulated/0";

    private static final Map<String, String> FOLDERS = new HashMap<>();
    static {
        FOLDERS.put("download", STORAGE_ROOT + "/Download");
        FOLDERS.put("documents", STORAGE_ROOT + "/Documents");
        FOLDERS.put("dcim", STORAGE_ROOT + "/DCIM");
        FOLDERS.put("pictures", STORAGE_ROOT + "/Pictures");
        FOLDERS.put("movies", STORAGE_ROOT + "/Movies");
        FOLDERS.put("music", STORAGE_ROOT + "/Music");
    }

    public static final String[] FOLDER_TOKENS = {
            "download", "documents", "dcim", "pictures", "movies", "music"};

    /** Recoverable trash the app owns: files_trash MOVES here, and
     *  nothing in v1.24 ever permanently deletes from it. */
    public static final String TRASH_ROOT = STORAGE_ROOT + "/.muse-trash";

    public static boolean folderOk(String token) {
        return token != null && FOLDERS.containsKey(token);
    }

    /** Absolute path for a validated folder token, else null. */
    public static String folderPath(String token) {
        return token == null ? null : FOLDERS.get(token);
    }

    /** Folders files_organize may file loose files in: the two
     *  document-accumulation folders. Media folders (dcim /
     *  pictures / movies / music) are app-managed libraries —
     *  organizing them is out of scope and refused. */
    public static boolean organizeOk(String token) {
        return "download".equals(token) || "documents".equals(token);
    }

    // ---- organizer filing rules ------------------------------------------

    private static final Map<String, String> EXT_CATEGORY = new HashMap<>();
    static {
        for (String e : new String[]{"jpg", "jpeg", "png", "gif", "webp",
                "heic", "heif", "bmp", "svg", "avif"}) {
            EXT_CATEGORY.put(e, "Images");
        }
        for (String e : new String[]{"pdf", "doc", "docx", "txt", "md",
                "csv", "xls", "xlsx", "ppt", "pptx", "odt", "rtf",
                "json", "xml", "epub"}) {
            EXT_CATEGORY.put(e, "Documents");
        }
        for (String e : new String[]{"zip", "rar", "7z", "tar", "gz",
                "tgz", "bz2", "xz"}) {
            EXT_CATEGORY.put(e, "Archives");
        }
        for (String e : new String[]{"apk", "xapk", "apks"}) {
            EXT_CATEGORY.put(e, "APKs");
        }
        for (String e : new String[]{"mp3", "wav", "m4a", "ogg",
                "flac", "aac", "opus"}) {
            EXT_CATEGORY.put(e, "Audio");
        }
        for (String e : new String[]{"mp4", "mkv", "mov", "webm",
                "avi", "3gp"}) {
            EXT_CATEGORY.put(e, "Video");
        }
    }

    public static final String[] CATEGORIES = {
            "Images", "Documents", "Archives", "APKs", "Audio", "Video"};

    /** Lowercase extension without the dot, or "" when none. */
    public static String extensionOf(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    /** The organizer category for a file name, or null when the
     *  file is not filed (unknown / unfinished-download extension,
     *  no extension, dotfile). In-progress downloads are excluded
     *  here AND by the router's age gate. */
    public static String categoryFor(String name) {
        if (name == null || name.startsWith(".")) return null;
        String ext = extensionOf(name);
        if (ext.equals("crdownload") || ext.equals("part")
                || ext.equals("tmp") || ext.equals("download")) {
            return null;
        }
        return EXT_CATEGORY.get(ext);
    }

    // ---- file-name validation (typed values, never shell text) -----------

    /**
     * A direct-child file name the files_* commands may act on.
     * Names arrive as word args joined with single spaces (the
     * voice_bridge pattern), because real download names carry
     * spaces and punctuation that the shared shell hygiene bans —
     * they are consumed by FileRouter as typed values via
     * java.io.File, never as shell text, never as paths: a name
     * containing a path separator, a parent reference, or a
     * control character is refused, and dotfiles are refused
     * outright (system / app state is not file management).
     */
    public static boolean nameOk(String name) {
        if (name == null || name.isEmpty() || name.length() > 255) return false;
        if (name.startsWith(".")) return false;
        if (name.equals(".") || name.equals("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c < 0x20 || c == 0x7F) return false;
        }
        return true;
    }

    // ---- v1.36: subfolder navigation + image fetch -----------------------

    /** Deepest subfolder chain a files_list / files_get command may
     *  name below a mapped base folder. Real app libraries nest one
     *  or two levels (Pictures/Instagram, DCIM/Camera); four is
     *  generous headroom without opening arbitrary tree walks. */
    public static final int MAX_SUB_DEPTH = 4;

    /** A subfolder segment obeys exactly the direct-child name
     *  rules: no separators, no parent references, no dotfiles, no
     *  control characters. A segment is ONE argv element (quote it
     *  on the command line when it contains spaces, e.g.
     *  "Clipped images"); it is consumed as a typed value by
     *  FileRouter via java.io.File, never as shell text. */
    public static boolean segmentOk(String segment) {
        return nameOk(segment);
    }

    /** Absolute path of base folder + validated segments, or null
     *  when the folder token is unknown, the chain is too deep, or
     *  any segment fails segmentOk. The result is built only from
     *  the code-mapped base plus validated segments, so it cannot
     *  escape the base; FileRouter additionally canonical-checks
     *  containment on-device (symlink defense in depth). */
    public static String subPath(String token, String[] segments) {
        String base = folderPath(token);
        if (base == null) return null;
        if (segments == null) return base;
        if (segments.length > MAX_SUB_DEPTH) return null;
        StringBuilder sb = new StringBuilder(base);
        for (String s : segments) {
            if (!segmentOk(s)) return null;
            sb.append('/').append(s);
        }
        return sb.toString();
    }

    /** Extensions files_get may fetch: still images only. The
     *  router re-checks this on-device and the relay re-checks the
     *  magic bytes, so the extension is a gate, not the proof. */
    private static final String[] IMAGE_EXTS = {
            "jpg", "jpeg", "png", "webp", "gif"};

    public static boolean imageExtOk(String name) {
        String ext = extensionOf(name);
        for (String e : IMAGE_EXTS) if (e.equals(ext)) return true;
        return false;
    }

    /** Hard cap for one files_get fetch, in bytes of the ORIGINAL
     *  file. The fetch rides base64-in-JSON to the device relay
     *  (the screen-frame discipline), whose request body cap is
     *  4 MB; base64 inflates by a third, so a 2.5 MB original is
     *  the largest payload that fits with room for the JSON shell.
     *  Above the cap the router refuses typed (files_too_large);
     *  nothing is downscaled or truncated, so the SHA-256 the
     *  command reports is always the original file's hash and the
     *  receiver can verify the delivered bytes against it. */
    public static final long FETCH_MAX_BYTES = 2_500_000L;

    /** Make a file name safe for one-line result output: path /
     *  table separators and line breaks become spaces. The name on
     *  disk is never changed by this — output hygiene only. */
    public static String sanitizeForOutput(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            sb.append(c == '|' || c == '\n' || c == '\r' || c == '\t'
                    ? ' ' : c);
        }
        return sb.toString();
    }
}
