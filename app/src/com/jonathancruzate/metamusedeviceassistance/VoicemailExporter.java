package com.jonathancruzate.metamusedeviceassistance;
import android.app.role.RoleManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.VoicemailContract;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
/**
 * Voicemail export core, ported from Muse Voicemail ExportActivity v1.2.0.
 * Same file and format: Download/MuseVoicemailExport.txt, parse compatible
 * with voicemail-borrow.sh (status, rows, max_id, newest_date_ms fields and
 * row blocks carrying "id: <n>"). Header version is the DA module version.
 * Statuses: OK, NO_ROLE, QUERY_BLOCKED, MODULE_DISABLED.
 */
public final class VoicemailExporter {
    static final String FILE_NAME = "MuseVoicemailExport.txt";
    static final String HEADER_VERSION = "1.30.0";
    public static final class Outcome {
        public final String status; public final int rows; public final long maxId; public final long newestMs;
        Outcome(String s, int r, long m, long n){status=s;rows=r;maxId=m;newestMs=n;}
    }
    private VoicemailExporter() {}
    static String header(String status, int rows, long maxId, long newestMs, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("MUSE-VOICEMAIL-EXPORT ").append(HEADER_VERSION).append('\n');
        sb.append("exported_at_ms: ").append(System.currentTimeMillis()).append('\n');
        sb.append("status: ").append(status).append('\n');
        sb.append("rows: ").append(rows).append('\n');
        sb.append("max_id: ").append(maxId).append('\n');
        sb.append("newest_date_ms: ").append(newestMs).append('\n');
        if (detail != null && !detail.isEmpty()) sb.append("detail: ").append(detail.replace('\n',' ')).append('\n');
        sb.append("---\n");
        return sb.toString();
    }
    /** Runs the export synchronously. Never throws. Records Store bookkeeping. */
    public static Outcome run(Context ctx) {
        String text; Outcome out;
        if (!VoicemailModule.moduleEnabled(ctx)) {
            out = new Outcome("MODULE_DISABLED", 0, 0, 0);
            text = header(out.status, 0, 0, 0, "Voicemail module disabled in-app; capability does not exist");
        } else {
            try { Build b = build(ctx); text = b.text; out = b.outcome; }
            catch (Exception e) { out = new Outcome("QUERY_BLOCKED",0,0,0); text = header("QUERY_BLOCKED",0,0,0,String.valueOf(e)); }
        }
        try { writeToDownloads(ctx, text); } catch (Exception ignored) {}
        Store.put(ctx, Store.K_VM_LAST_EXPORT_AT, System.currentTimeMillis());
        Store.put(ctx, Store.K_VM_LAST_ROWS, (long) out.rows);
        Store.put(ctx, Store.K_VM_LAST_STATUS, out.status);
        Store.put(ctx, Store.K_VM_LAST_MAX_ID, out.maxId);
        VoicemailLog.log(ctx, "export status=" + out.status + " rows=" + out.rows);
        return out;
    }
    private static final class Build { String text; Outcome outcome; }
    private static Build build(Context ctx) {
        Build b = new Build();
        RoleManager rm = ctx.getSystemService(RoleManager.class);
        boolean roleHeld = rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
        if (!roleHeld) { b.outcome = new Outcome("NO_ROLE",0,0,0); b.text = header("NO_ROLE",0,0,0,"ROLE_DIALER not held, read access is closed outside a borrow window"); return b; }
        String[] projection = { VoicemailContract.Voicemails._ID, VoicemailContract.Voicemails.NUMBER, VoicemailContract.Voicemails.DATE, VoicemailContract.Voicemails.DURATION, VoicemailContract.Voicemails.TRANSCRIPTION, VoicemailContract.Voicemails.HAS_CONTENT, VoicemailContract.Voicemails.IS_READ, VoicemailContract.Voicemails.SOURCE_PACKAGE };
        Cursor c;
        try { c = ctx.getContentResolver().query(VoicemailContract.Voicemails.CONTENT_URI, projection, VoicemailContract.Voicemails.DELETED + " = 0", null, VoicemailContract.Voicemails.DATE + " DESC"); }
        catch (SecurityException se) { b.outcome = new Outcome("QUERY_BLOCKED",0,0,0); b.text = header("QUERY_BLOCKED",0,0,0,se.getMessage()); return b; }
        if (c == null) { b.outcome = new Outcome("QUERY_BLOCKED",0,0,0); b.text = header("QUERY_BLOCKED",0,0,0,"provider returned no cursor"); return b; }
        List<String> blocks = new ArrayList<>(); long maxId=0, newest=0;
        SimpleDateFormat fmt = new SimpleDateFormat("EEE MMM d, yyyy h:mm a", Locale.US);
        try { while (c.moveToNext()) {
            long id=c.getLong(0); String number=c.getString(1); long date=c.getLong(2); long duration=c.getLong(3); String tr=c.getString(4); boolean hasContent=c.getInt(5)==1; boolean read=c.getInt(6)==1; String src=c.getString(7);
            if (id>maxId) maxId=id; if (date>newest) newest=date;
            StringBuilder blk=new StringBuilder();
            blk.append(read?"":"NEW  ").append(number==null||number.isEmpty()?"Unknown number":number).append("  \u00b7  ").append(fmt.format(new Date(date))).append("  \u00b7  ").append(duration).append("s").append("  \u00b7  audio: ").append(hasContent?"available":"not downloaded").append("  \u00b7  source: ").append(src).append("  \u00b7  id: ").append(id).append('\n');
            blk.append(tr!=null&&!tr.trim().isEmpty() ? "\""+tr.trim()+"\"" : "(no transcription)").append("\n");
            blocks.add(blk.toString());
        } } finally { c.close(); }
        StringBuilder sb=new StringBuilder(header("OK", blocks.size(), maxId, newest, null));
        for (String blk: blocks) sb.append(blk).append('\n');
        b.text=sb.toString(); b.outcome=new Outcome("OK", blocks.size(), maxId, newest); return b;
    }
    private static void writeToDownloads(Context ctx, String text) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, MediaStore.Downloads.DISPLAY_NAME + " = ?", new String[]{FILE_NAME});
        ContentValues v=new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME); v.put(MediaStore.Downloads.MIME_TYPE,"text/plain"); v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS); v.put(MediaStore.Downloads.IS_PENDING,1);
        Uri uri=cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri==null) throw new IllegalStateException("MediaStore insert returned null");
        try (OutputStream os=cr.openOutputStream(uri,"w")) { if(os==null) throw new IllegalStateException("openOutputStream returned null"); os.write(text.getBytes(StandardCharsets.UTF_8)); }
        v.clear(); v.put(MediaStore.Downloads.IS_PENDING,0); cr.update(uri,v,null,null);
    }
}
