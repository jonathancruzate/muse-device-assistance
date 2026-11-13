package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * FileRouter — v1.24: executes the native `files_*` commands
 * (gate: ShellAllowlist.checkFiles; rules: FilePlan; design:
 * docs/v1.24-phone-files-2026-11-13.md).
 *
 * Why native, not shell: real download names carry spaces and
 * punctuation the shell bridge's hygiene bans, and these ops never
 * need a shell — java.io.File consumes the gate's typed values
 * (folder token + direct-child name) directly. The gate guarantees
 * no value can be a path: folders are code-mapped tokens and names
 * are direct children only.
 *
 * Access model: every op except files_access requires Android's
 * All Files Access for this app (Environment.isExternalStorageManager)
 * — Jonathan's one-time grant in the app's Phone files section or
 * system Settings. Without it the ops refuse typed
 * (files_access_not_granted); they never fall back to a partial
 * scoped-storage view that would silently under-report the phone.
 *
 * Safety rules (Jonathan's file-cleanup rules, carried to phone):
 *  - Nothing is ever permanently deleted. files_trash MOVES to
 *    /storage/emulated/0/.muse-trash/<folder>/ with a ledger line;
 *    restore is a files_move back (or any file manager).
 *  - Moves never overwrite: a destination collision is a skip /
 *    refusal, and every move is size-verified after the rename.
 *  - files_organize files only loose direct-child files whose
 *    extension has a category, untouched for at least an hour
 *    (never an in-progress download), capped per run.
 *  - Output is capped well under the command channel's tail bound
 *    (16 KB): listings paginate at 40 entries.
 */
public final class FileRouter {
    private FileRouter() {}

    private static final int LIST_PAGE = 40;
    private static final int SUMMARY_SCAN_CAP = 4000;
    private static final int ORGANIZE_CAP = 100;
    private static final long ORGANIZE_MIN_AGE_MS = 60L * 60 * 1000;
    private static final long HASH_MAX_BYTES = 1024L * 1024 * 1024;
    private static final int OUT_CAP = 12_000;

    public static boolean accessGranted() {
        return Environment.isExternalStorageManager();
    }

    // ---- entry point -----------------------------------------------------

    /** ShellBridge entry point. args are the gate's validated typed
     *  values (see ShellAllowlist.checkFiles for each op's shape). */
    public static LoopbackAdb.Result execute(Context ctx, String op, String[] args) {
        long t0 = System.currentTimeMillis();
        try {
            LoopbackAdb.Result r;
            switch (op) {
                case "files_access":
                    r = ok(accessReport(), t0);
                    break;
                case "files_summary":
                    r = requireAccess(t0);
                    if (r == null) r = ok(summary(), t0);
                    break;
                case "files_list": {
                    r = requireAccess(t0);
                    if (r == null) {
                        int offset = args.length > 1 && !args[1].isEmpty()
                                ? Integer.parseInt(args[1]) : 0;
                        r = ok(list(args[0], offset), t0);
                    }
                    break;
                }
                case "files_hash":
                    r = requireAccess(t0);
                    if (r == null) r = hash(args[0], args[1], t0);
                    break;
                case "files_move":
                    r = requireAccess(t0);
                    if (r == null) r = move(ctx, args[0], args[1], args[2], t0);
                    break;
                case "files_trash":
                    r = requireAccess(t0);
                    if (r == null) r = trash(ctx, args[0], args[1], t0);
                    break;
                case "files_organize":
                    r = requireAccess(t0);
                    if (r == null) r = organize(ctx, args[0], t0);
                    break;
                default:
                    r = new LoopbackAdb.Result(false, "", ms(t0), "unknown files op");
            }
            record(ctx, op, r);
            return r;
        } catch (Exception e) {
            LoopbackAdb.Result r = new LoopbackAdb.Result(false, "",
                    ms(t0), e.getClass().getSimpleName() + " " + e.getMessage());
            record(ctx, op, r);
            return r;
        }
    }

    private static long ms(long t0) {
        return System.currentTimeMillis() - t0;
    }

    private static LoopbackAdb.Result ok(String out, long t0) {
        return new LoopbackAdb.Result(true, cap(out), ms(t0), null);
    }

    private static LoopbackAdb.Result requireAccess(long t0) {
        if (accessGranted()) return null;
        return new LoopbackAdb.Result(false, "", ms(t0),
                "files_access_not_granted: enable All Files Access for "
                        + "Muse Device Assistance (in-app Phone files "
                        + "section, or Settings > Apps > Muse Device "
                        + "Assistance > All files access)");
    }

    private static String cap(String out) {
        if (out == null) return "";
        return out.length() <= OUT_CAP ? out
                : out.substring(0, OUT_CAP) + "\n... (truncated)";
    }

    private static void record(Context ctx, String op, LoopbackAdb.Result r) {
        Store.put(ctx, Store.K_FILES_LAST_OP, op);
        Store.put(ctx, Store.K_FILES_LAST_AT, System.currentTimeMillis());
        Store.put(ctx, Store.K_FILES_LAST_DETAIL,
                r.ok ? "ok" : (r.error == null ? "" : r.error));
        BridgeLog.log(ctx, "FILES", op + " -> ok=" + r.ok + " " + r.ms + "ms"
                + (r.error != null ? " error=" + r.error : ""));
    }

    // ---- ops ---------------------------------------------------------------

    private static String accessReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("files_access granted=").append(accessGranted()).append('\n');
        for (String token : FilePlan.FOLDER_TOKENS) {
            File d = new File(FilePlan.folderPath(token));
            sb.append(token).append(" exists=").append(d.isDirectory())
                    .append(" readable=").append(d.canRead()).append('\n');
        }
        File trash = new File(FilePlan.TRASH_ROOT);
        sb.append("trash exists=").append(trash.isDirectory()).append('\n');
        if (!accessGranted()) {
            sb.append("grant: app > Phone files > Grant All Files Access\n");
        }
        return sb.toString().trim();
    }

    private static String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("files_summary\n");
        for (String token : FilePlan.FOLDER_TOKENS) {
            File root = new File(FilePlan.folderPath(token));
            long[] stats = walkStats(root);
            sb.append(token)
                    .append(" files=").append(stats[0])
                    .append(" dirs=").append(stats[1])
                    .append(" bytes=").append(stats[2])
                    .append(" newest=").append(stats[3])
                    .append(stats[4] > 0 ? " (scan capped)" : "")
                    .append('\n');
        }
        return sb.toString().trim();
    }

    /** Recursive {files, dirs, bytes, newestMtimeMs, cappedFlag}
     *  with a hard entry cap so a huge library can't hang the
     *  bridge cycle. */
    private static long[] walkStats(File dir) {
        long[] s = new long[5];
        if (dir == null || !dir.isDirectory()) return s;
        List<File> stack = new ArrayList<>();
        stack.add(dir);
        int scanned = 0;
        while (!stack.isEmpty()) {
            File d = stack.remove(stack.size() - 1);
            File[] kids = d.listFiles();
            if (kids == null) continue;
            for (File k : kids) {
                if (++scanned > SUMMARY_SCAN_CAP) {
                    s[4] = 1;
                    return s;
                }
                if (k.isDirectory()) {
                    s[1]++;
                    stack.add(k);
                } else {
                    s[0]++;
                    s[2] += k.length();
                    if (k.lastModified() > s[3]) s[3] = k.lastModified();
                }
            }
        }
        return s;
    }

    private static String list(String token, int offset) {
        File dir = new File(FilePlan.folderPath(token));
        if (!dir.isDirectory()) return "files_list folder=" + token + " missing";
        File[] kids = dir.listFiles();
        if (kids == null) return "files_list folder=" + token + " unreadable";
        Arrays.sort(kids, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        StringBuilder sb = new StringBuilder();
        int from = Math.min(offset, kids.length);
        int to = Math.min(from + LIST_PAGE, kids.length);
        sb.append("files_list folder=").append(token)
                .append(" total=").append(kids.length)
                .append(" showing=").append(from).append("..").append(to)
                .append('\n');
        for (int i = from; i < to; i++) {
            File k = kids[i];
            sb.append(k.isDirectory() ? "D" : "F")
                    .append(" | ").append(k.isDirectory() ? -1 : k.length())
                    .append(" | ").append(k.lastModified())
                    .append(" | ").append(FilePlan.sanitizeForOutput(k.getName()))
                    .append('\n');
        }
        if (to < kids.length) {
            sb.append("more: files_list ").append(token).append(' ').append(to).append('\n');
        }
        return sb.toString().trim();
    }

    private static LoopbackAdb.Result hash(String token, String name, long t0)
            throws Exception {
        File f = new File(new File(FilePlan.folderPath(token)), name);
        if (!f.isFile()) {
            return new LoopbackAdb.Result(false, "", ms(t0), "not_a_file");
        }
        if (f.length() > HASH_MAX_BYTES) {
            return new LoopbackAdb.Result(false, "", ms(t0), "file_too_large_to_hash");
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
        }
        String out = "files_hash folder=" + token
                + " size=" + f.length()
                + " sha256=" + WearProtocol.toHex(md.digest())
                + " name=" + FilePlan.sanitizeForOutput(name);
        return ok(out, t0);
    }

    /** Shared move core: rename + size verification. Never
     *  overwrites; returns null on success or an error string. */
    private static String moveVerified(File src, File dst) {
        if (dst.exists()) return "destination_exists";
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return "destination_dir_failed";
        }
        long size = src.length();
        if (!src.renameTo(dst)) return "rename_failed";
        if (!dst.isFile() || dst.length() != size) return "verify_failed";
        return null;
    }

    private static LoopbackAdb.Result move(Context ctx, String token,
                                           String destToken, String name, long t0) {
        File src = new File(new File(FilePlan.folderPath(token)), name);
        if (!src.isFile()) {
            return new LoopbackAdb.Result(false, "", ms(t0), "not_a_file");
        }
        File dst = new File(new File(FilePlan.folderPath(destToken)), name);
        String err = moveVerified(src, dst);
        if (err != null) return new LoopbackAdb.Result(false, "", ms(t0), err);
        return ok("files_move " + token + " -> " + destToken
                + " size=" + dst.length()
                + " name=" + FilePlan.sanitizeForOutput(name), t0);
    }

    private static LoopbackAdb.Result trash(Context ctx, String token,
                                            String name, long t0) {
        File src = new File(new File(FilePlan.folderPath(token)), name);
        if (!src.isFile()) {
            return new LoopbackAdb.Result(false, "", ms(t0), "not_a_file");
        }
        File trashDir = new File(FilePlan.TRASH_ROOT, token);
        File dst = new File(trashDir, name);
        if (dst.exists()) dst = new File(trashDir, collisionName(name));
        String err = moveVerified(src, dst);
        if (err != null) return new LoopbackAdb.Result(false, "", ms(t0), err);
        try (FileWriter w = new FileWriter(
                new File(FilePlan.TRASH_ROOT, "ledger.txt"), true)) {
            w.write(System.currentTimeMillis() + " | "
                    + FilePlan.folderPath(token) + "/" + name
                    + " -> " + dst.getAbsolutePath() + "\n");
        } catch (Exception ignored) {}
        return ok("files_trash " + token + " -> .muse-trash/" + token
                + " size=" + dst.length()
                + " name=" + FilePlan.sanitizeForOutput(dst.getName()), t0);
    }

    /** "name (2).ext" style collision suffix (single retry shape;
     *  a second collision refuses via destination_exists). */
    private static String collisionName(String name) {
        String ext = FilePlan.extensionOf(name);
        if (ext.isEmpty()) return name + " (2)";
        String base = name.substring(0, name.length() - ext.length() - 1);
        return base + " (2)." + ext;
    }

    private static LoopbackAdb.Result organize(Context ctx, String token, long t0) {
        if (!FilePlan.organizeOk(token)) {
            return new LoopbackAdb.Result(false, "", ms(t0),
                    "organize_refused_for_folder: only download and documents");
        }
        File dir = new File(FilePlan.folderPath(token));
        File[] kids = dir.listFiles();
        if (kids == null) {
            return new LoopbackAdb.Result(false, "", ms(t0), "folder_unreadable");
        }
        long now = System.currentTimeMillis();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String c : FilePlan.CATEGORIES) counts.put(c, 0);
        List<String> moved = new ArrayList<>();
        int skippedYoung = 0, skippedNoCategory = 0, skippedCollision = 0;
        int movedTotal = 0;
        Arrays.sort(kids, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return Long.compare(a.lastModified(), b.lastModified());
            }
        });
        for (File k : kids) {
            if (movedTotal >= ORGANIZE_CAP) break;
            if (!k.isFile()) continue;
            String category = FilePlan.categoryFor(k.getName());
            if (category == null) {
                skippedNoCategory++;
                continue;
            }
            if (now - k.lastModified() < ORGANIZE_MIN_AGE_MS) {
                skippedYoung++;
                continue;
            }
            File dst = new File(new File(dir, category), k.getName());
            String err = moveVerified(k, dst);
            if (err != null) {
                if ("destination_exists".equals(err)) skippedCollision++;
                continue;
            }
            counts.put(category, counts.get(category) + 1);
            movedTotal++;
            if (moved.size() < 40) {
                moved.add(category + "/" + FilePlan.sanitizeForOutput(k.getName()));
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("files_organize folder=").append(token)
                .append(" moved=").append(movedTotal)
                .append(" skipped_young=").append(skippedYoung)
                .append(" skipped_no_category=").append(skippedNoCategory)
                .append(" skipped_collision=").append(skippedCollision)
                .append('\n');
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > 0) {
                sb.append(e.getKey()).append(": ").append(e.getValue()).append('\n');
            }
        }
        for (String m : moved) sb.append("moved ").append(m).append('\n');
        return ok(sb.toString().trim(), t0);
    }

    // ---- report ------------------------------------------------------------

    /** Report object (carried in the bridge report): access state +
     *  the last files op's bookkeeping — never file names. */
    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("access_granted", accessGranted());
            long at = Store.getLong(ctx, Store.K_FILES_LAST_AT, 0);
            if (at > 0) {
                o.put("last_op", Store.get(ctx, Store.K_FILES_LAST_OP, ""));
                o.put("last_detail", Store.get(ctx, Store.K_FILES_LAST_DETAIL, ""));
                o.put("last_at", at);
            }
        } catch (Exception ignored) {}
        return o;
    }

    /** Human-readable state for MainActivity's Phone files section. */
    public static String uiState(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("All Files Access: ")
                .append(accessGranted() ? "GRANTED" : "not granted").append('\n');
        long at = Store.getLong(ctx, Store.K_FILES_LAST_AT, 0);
        if (at > 0) {
            sb.append("Last files op: ")
                    .append(Store.get(ctx, Store.K_FILES_LAST_OP, ""))
                    .append(" · ").append(Store.get(ctx, Store.K_FILES_LAST_DETAIL, ""))
                    .append(" · ").append(new SimpleDateFormat(
                            "yyyy-MM-dd HH:mm", Locale.US).format(new Date(at)))
                    .append('\n');
        }
        return sb.toString();
    }
}
