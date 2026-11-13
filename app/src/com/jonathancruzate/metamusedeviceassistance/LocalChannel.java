package com.jonathancruzate.metamusedeviceassistance;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;

import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * The local command channel for the Muse Launcher Pack
 * (v1.20.0, Launcher Pack design Section 7.2 as amended at
 * execution 2026-11-12 on the Skeptic's swarm dissent).
 *
 * Transport is ContentProvider.call(), not a broadcast:
 * broadcasts cannot authenticate their sender, call() can. Every
 * method first verifies (a) the calling package is the Muse
 * Widget app and (b) its signing certificate SHA-256 matches
 * that app's known fingerprint (a public value, printed by its
 * own build; not a secret). execute() additionally requires the
 * pairing token: a random value generated on this phone when
 * Jonathan opens the pairing window in MainActivity, claimable
 * exactly once inside that window by the cert-verified pack,
 * stored only in both apps' private preferences, revocable
 * from either app. Because call() is synchronous, its return
 * Bundle is the acknowledgement the pack re-renders from.
 *
 * Execution reuses ShellBridge.executeGated: the identical
 * ShellAllowlist gates and dispatch as the Worker channel. No
 * new verbs. wear_install is refused locally (it is a Worker
 * job flow, not a launcher surface). Shell templates also
 * require the loopback bridge to be paired and switched on,
 * exactly as on the Worker channel. Every call is counted;
 * every refusal is counted and logged (never with the token).
 */
public class LocalChannel extends ContentProvider {

    static final String PACK_PACKAGE =
            "com.jonathancruzate.metamusewidget";
    // The Muse Widget app's signing certificate SHA-256, as
    // printed by its own build's apksigner verify step.
    static final String PACK_CERT_SHA256 =
            "2746d2a2d6dbe0fda4271acd14f4e5ab990da4e9155e7f9e75ae07fca4b814da";

    static final String METHOD_EXECUTE = "execute";
    static final String METHOD_CLAIM_PAIRING = "claim_pairing";
    static final String METHOD_PAIR_STATUS = "pair_status";
    static final String METHOD_REVOKE = "revoke";

    private static final long PAIR_WINDOW_MS = 5L * 60L * 1000L;
    private static final long RATE_WINDOW_MS = 60L * 1000L;
    private static final int RATE_MAX = 30;

    @Override
    public boolean onCreate() {
        return true;
    }

    // ---- Pairing lifecycle (called from MainActivity) ----

    /** Open the claim window with a fresh pending token. */
    public static void openPairingWindow(Context ctx) {
        String token = randomToken();
        Store.put(ctx, Store.K_LC_PENDING_TOKEN, token);
        Store.put(ctx, Store.K_LC_PENDING_UNTIL,
                System.currentTimeMillis() + PAIR_WINDOW_MS);
        BridgeLog.log(ctx, "LOCAL", "pairing window opened (5 min)");
    }

    /** Revoke from this side: drop token and pending window. */
    public static void revokePairing(Context ctx) {
        Store.put(ctx, Store.K_LC_TOKEN, "");
        Store.put(ctx, Store.K_LC_PENDING_TOKEN, "");
        Store.put(ctx, Store.K_LC_PENDING_UNTIL, 0L);
        BridgeLog.log(ctx, "LOCAL", "pairing revoked from Device Assistance");
    }

    public static String statusText(Context ctx) {
        boolean paired = !Store.get(ctx, Store.K_LC_TOKEN, "").isEmpty();
        long until = Store.getLong(ctx, Store.K_LC_PENDING_UNTIL, 0);
        boolean window = until > System.currentTimeMillis();
        long count = Store.getLong(ctx, Store.K_LC_COUNT, 0);
        long refused = Store.getLong(ctx, Store.K_LC_REFUSED, 0);
        return "Paired: " + (paired ? "yes" : "no")
                + (window ? " · pairing window OPEN" : "")
                + " · executions: " + count
                + " · refusals: " + refused;
    }

    /** Report object: state only, never the token (design bound). */
    public static JSONObject snapshot(Context ctx) {
        JSONObject o = new JSONObject();
        try {
            o.put("paired",
                    !Store.get(ctx, Store.K_LC_TOKEN, "").isEmpty());
            o.put("count", Store.getLong(ctx, Store.K_LC_COUNT, 0));
            o.put("refused", Store.getLong(ctx, Store.K_LC_REFUSED, 0));
            long at = Store.getLong(ctx, Store.K_LC_LAST_AT, 0);
            if (at > 0) {
                JSONObject last = new JSONObject();
                last.put("template",
                        Store.get(ctx, Store.K_LC_LAST_TEMPLATE, ""));
                last.put("ok", Store.getBool(ctx, Store.K_LC_LAST_OK,
                        false));
                last.put("ms", Store.getLong(ctx, Store.K_LC_LAST_MS, 0));
                last.put("at", at);
                o.put("last", last);
            }
        } catch (Exception ignored) {}
        return o;
    }

    // ---- ContentProvider.call ----

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        Bundle out = new Bundle();
        out.putBoolean("ok", false);
        if (ctx == null) {
            out.putString("error", "no context");
            return out;
        }
        if (!verifyCaller(ctx)) {
            refuse(ctx, "caller not the cert-verified Muse Widget app");
            out.putString("error", "unauthorized caller");
            return out;
        }
        if (extras == null) extras = new Bundle();
        try {
            switch (method) {
                case METHOD_CLAIM_PAIRING:
                    return claimPairing(ctx);
                case METHOD_PAIR_STATUS: {
                    boolean paired = tokenMatches(ctx,
                            extras.getString("token", ""));
                    out.putBoolean("paired", paired);
                    out.putBoolean("ok", true);
                    return out;
                }
                case METHOD_REVOKE: {
                    if (tokenMatches(ctx,
                            extras.getString("token", ""))) {
                        revokePairing(ctx);
                        out.putBoolean("ok", true);
                    } else {
                        refuse(ctx, "revoke with wrong token");
                        out.putString("error", "bad token");
                    }
                    return out;
                }
                case METHOD_EXECUTE:
                    return execute(ctx, extras);
                default:
                    refuse(ctx, "unknown method " + method);
                    out.putString("error", "unknown method");
                    return out;
            }
        } catch (Exception e) {
            BridgeLog.log(ctx, "LOCAL", "call " + method + " failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            out.putString("error", e.getClass().getSimpleName());
            return out;
        }
    }

    private Bundle claimPairing(Context ctx) {
        Bundle out = new Bundle();
        out.putBoolean("ok", false);
        long until = Store.getLong(ctx, Store.K_LC_PENDING_UNTIL, 0);
        String pending = Store.get(ctx, Store.K_LC_PENDING_TOKEN, "");
        if (pending.isEmpty() || System.currentTimeMillis() > until) {
            refuse(ctx, "claim outside pairing window");
            out.putString("error", "no pairing window open");
            return out;
        }
        Store.put(ctx, Store.K_LC_TOKEN, pending);
        Store.put(ctx, Store.K_LC_PENDING_TOKEN, "");
        Store.put(ctx, Store.K_LC_PENDING_UNTIL, 0L);
        Store.put(ctx, Store.K_LC_PAIRED_AT,
                System.currentTimeMillis());
        BridgeLog.log(ctx, "LOCAL",
                "pairing claimed by the Muse Launcher Pack");
        out.putBoolean("ok", true);
        out.putString("token", pending);
        return out;
    }

    private Bundle execute(Context ctx, Bundle extras) {
        Bundle out = new Bundle();
        out.putBoolean("ok", false);
        if (!tokenMatches(ctx, extras.getString("token", ""))) {
            refuse(ctx, "execute with bad or missing token");
            out.putString("error", "not paired");
            return out;
        }
        if (!rateOk(ctx)) {
            refuse(ctx, "rate limit");
            out.putString("error", "rate limited");
            return out;
        }
        String template = extras.getString("template", "");
        if (template.isEmpty() || template.startsWith("wear_")) {
            refuse(ctx, "template refused locally: " + template);
            out.putString("error", "template not available locally");
            return out;
        }
        String[] arr = extras.getStringArray("args");
        List<String> args = new ArrayList<>();
        if (arr != null) {
            for (String a : arr) args.add(a);
        }
        boolean nativeTemplate =
                ShellAllowlist.isNativeTemplate(template);
        if (!nativeTemplate
                && (!Store.getBool(ctx, Store.K_SHELL_ON, false)
                        || !Store.getBool(ctx, Store.K_SHELL_PAIRED,
                                false))) {
            refuse(ctx, "shell executor not armed for template "
                    + template);
            out.putString("error",
                    "shell bridge is off or unpaired on this phone");
            return out;
        }
        LoopbackAdb.Result r = ShellBridge.executeGated(ctx, template,
                args, "local");
        Store.put(ctx, Store.K_LC_COUNT,
                Store.getLong(ctx, Store.K_LC_COUNT, 0) + 1);
        Store.put(ctx, Store.K_LC_LAST_TEMPLATE, template);
        Store.put(ctx, Store.K_LC_LAST_OK, r.ok);
        Store.put(ctx, Store.K_LC_LAST_MS, r.ms);
        Store.put(ctx, Store.K_LC_LAST_AT, System.currentTimeMillis());
        out.putBoolean("ok", r.ok);
        out.putString("output", r.stdoutTail == null ? "" : r.stdoutTail);
        out.putString("error", r.error == null ? "" : r.error);
        out.putLong("ms", r.ms);
        return out;
    }

    // ---- Verification ----

    private boolean verifyCaller(Context ctx) {
        try {
            int uid = Binder.getCallingUid();
            PackageManager pm = ctx.getPackageManager();
            String[] pkgs = pm.getPackagesForUid(uid);
            if (pkgs == null) return false;
            boolean isPack = false;
            for (String p : pkgs) {
                if (PACK_PACKAGE.equals(p)) { isPack = true; break; }
            }
            if (!isPack) return false;
            PackageInfo info = pm.getPackageInfo(PACK_PACKAGE,
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) return false;
            Signature[] signers = info.signingInfo.hasMultipleSigners()
                    ? info.signingInfo.getSigningCertificateHistory()
                    : info.signingInfo.getApkContentsSigners();
            if (signers == null || signers.length == 0) return false;
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(signers[0].toByteArray());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return PACK_CERT_SHA256.equalsIgnoreCase(hex.toString());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean tokenMatches(Context ctx, String presented) {
        String stored = Store.get(ctx, Store.K_LC_TOKEN, "");
        if (stored.isEmpty() || presented == null
                || presented.isEmpty()) {
            return false;
        }
        // Constant-time-ish compare; lengths leak nothing useful.
        if (stored.length() != presented.length()) return false;
        int diff = 0;
        for (int i = 0; i < stored.length(); i++) {
            diff |= stored.charAt(i) ^ presented.charAt(i);
        }
        return diff == 0;
    }

    private boolean rateOk(Context ctx) {
        long now = System.currentTimeMillis();
        long start = Store.getLong(ctx, Store.K_LC_WINDOW_START, 0);
        long count = Store.getLong(ctx, Store.K_LC_WINDOW_COUNT, 0);
        if (now - start > RATE_WINDOW_MS) {
            Store.put(ctx, Store.K_LC_WINDOW_START, now);
            Store.put(ctx, Store.K_LC_WINDOW_COUNT, 1L);
            return true;
        }
        if (count >= RATE_MAX) return false;
        Store.put(ctx, Store.K_LC_WINDOW_COUNT, count + 1);
        return true;
    }

    private void refuse(Context ctx, String why) {
        Store.put(ctx, Store.K_LC_REFUSED,
                Store.getLong(ctx, Store.K_LC_REFUSED, 0) + 1);
        BridgeLog.log(ctx, "LOCAL", "REFUSED " + why);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    // ---- Unused ContentProvider surface ----

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection,
                      String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
                      String[] selectionArgs) {
        return 0;
    }
}
