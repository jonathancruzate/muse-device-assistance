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
