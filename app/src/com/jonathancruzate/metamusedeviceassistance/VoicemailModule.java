package com.jonathancruzate.metamusedeviceassistance;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import org.json.JSONObject;
/**
 * VoicemailModule, v1.30.0: the Muse Voicemail fold-in. OPTIONAL, OFF BY
 * DEFAULT, following the VoiceModeModule and Screen module pattern.
 * While Store.K_VM_ENABLED is false the voicemail_export verb is refused
 * and VoicemailExportActivity writes MODULE_DISABLED: the capability
 * does not exist. The module NEVER requests or holds ROLE_DIALER
 * permanently. The role is borrowed and handed back only, by the
 * voicemail-borrow.sh automation, exactly as the standalone app worked.
 */
public final class VoicemailModule {
    private VoicemailModule() {}

    // v1.30.1 (DEC-0017 condition 1): OS-level component gating.
    // The ROLE_DIALER qualification components. While the module is
    // disabled these are DISABLED at the PackageManager level, so the
    // app does not sit dialer-eligible just because the manifest
    // declares them. While enabled they are set ENABLED explicitly
    // (not DEFAULT, so the gate state is unambiguous on read-back).
    // VoicemailExportActivity is deliberately NOT gated: it must stay
    // reachable so a disabled module can answer MODULE_DISABLED, and
    // it holds no dialer qualification by itself.
    static final Class<?>[] QUALIFICATION_COMPONENTS = {
            VoicemailDialActivity.class,
            VoicemailInCallService.class,
            VoicemailVvmService.class,
            VoicemailInCallActivity.class,
    };

    /** Reconcile qualification component states with K_VM_ENABLED.
     *  Idempotent, never throws, DONT_KILL_APP throughout. Called at
     *  BridgeService start, MainActivity create, and on every toggle
     *  change, so a freshly installed or upgraded app reconciles to
     *  disabled on first launch (module defaults off). */
    public static void reconcileComponents(Context ctx) {
        try {
            boolean want = moduleEnabled(ctx);
            PackageManager pm = ctx.getPackageManager();
            for (Class<?> cls : QUALIFICATION_COMPONENTS) {
                ComponentName cn = new ComponentName(ctx, cls);
                int cur = pm.getComponentEnabledSetting(cn);
                int target = want ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                  : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                if (cur != target) {
                    pm.setComponentEnabledSetting(cn, target,
                            PackageManager.DONT_KILL_APP);
                }
            }
            boolean now = componentsEnabled(ctx);
            Store.put(ctx, Store.K_VM_COMPONENTS_ENABLED, now);
            Store.put(ctx, Store.K_VM_COMPONENTS_AT, System.currentTimeMillis());
            BridgeLog.log(ctx, "VOICEMAIL", "component gate reconciled: module="
                    + want + " components_enabled=" + now);
        } catch (Exception e) {
            BridgeLog.log(ctx, "VOICEMAIL", "component gate reconcile FAILED: " + e);
        }
    }

    /** True only when every qualification component is enabled at
     *  the OS level (ENABLED, or DEFAULT which follows the manifest
     *  enabled state). */
    public static boolean componentsEnabled(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            for (Class<?> cls : QUALIFICATION_COMPONENTS) {
                int cur = pm.getComponentEnabledSetting(new ComponentName(ctx, cls));
                if (cur == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                        || cur == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                        || cur == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) { return false; }
    }
    public static boolean moduleEnabled(Context ctx) { return Store.getBool(ctx, Store.K_VM_ENABLED, false); }
    public static boolean roleHeld(Context ctx) {
        try { RoleManager rm = ctx.getSystemService(RoleManager.class); return rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER); } catch (Exception e) { return false; }
    }
    public static LoopbackAdb.Result execute(Context ctx, String op) {
        long t0 = System.currentTimeMillis();
        if (!"voicemail_export".equals(op)) return new LoopbackAdb.Result(false,"",System.currentTimeMillis()-t0,"unknown voicemail op");
        reconcileComponents(ctx);
        if (!moduleEnabled(ctx)) { BridgeLog.log(ctx,"VOICEMAIL","voicemail_export REFUSED: module disabled in-app"); return new LoopbackAdb.Result(false,"",System.currentTimeMillis()-t0,"refused: voicemail module disabled"); }
        VoicemailExporter.Outcome out = VoicemailExporter.run(ctx);
        boolean ok = "OK".equals(out.status) || "NO_ROLE".equals(out.status);
        return new LoopbackAdb.Result(ok, "voicemail_export status=" + out.status + " rows=" + out.rows + " max_id=" + out.maxId, System.currentTimeMillis()-t0, ok?null:"voicemail export status "+out.status);
    }
    public static JSONObject snapshot(Context ctx) {
        JSONObject o=new JSONObject();
        try { o.put("enabled", moduleEnabled(ctx)); o.put("components_enabled", componentsEnabled(ctx)); o.put("components_reconciled_at", Store.getLong(ctx, Store.K_VM_COMPONENTS_AT,0)); o.put("role_held", roleHeld(ctx)); o.put("last_export_at", Store.getLong(ctx, Store.K_VM_LAST_EXPORT_AT,0)); o.put("last_rows", Store.getLong(ctx, Store.K_VM_LAST_ROWS,0)); o.put("last_status", Store.get(ctx, Store.K_VM_LAST_STATUS,"")); o.put("last_max_id", Store.getLong(ctx, Store.K_VM_LAST_MAX_ID,0)); } catch(Exception ignored){}
        return o;
    }
    public static String uiState(Context ctx) {
        StringBuilder sb=new StringBuilder();
        sb.append("Voicemail module enabled: ").append(moduleEnabled(ctx)).append('\n');
        sb.append("Dialer components enabled (OS gate): ").append(componentsEnabled(ctx)).append('\n');
        sb.append("ROLE_DIALER held: ").append(roleHeld(ctx)).append(" (borrow only, never permanent)\n");
        long at=Store.getLong(ctx, Store.K_VM_LAST_EXPORT_AT,0);
        sb.append("Last export: ").append(at==0?"never":String.valueOf(at)).append(" status=").append(Store.get(ctx, Store.K_VM_LAST_STATUS,"")).append(" rows=").append(Store.getLong(ctx, Store.K_VM_LAST_ROWS,0)).append('\n');
        return sb.toString();
    }
}
