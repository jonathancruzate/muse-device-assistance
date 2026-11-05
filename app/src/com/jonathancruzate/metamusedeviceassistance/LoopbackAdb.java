package com.jonathancruzate.metamusedeviceassistance;

import android.content.Context;

import org.conscrypt.Conscrypt;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import android.sun.security.x509.AlgorithmId;
import android.sun.security.x509.CertificateAlgorithmId;
import android.sun.security.x509.CertificateIssuerName;
import android.sun.security.x509.CertificateSerialNumber;
import android.sun.security.x509.CertificateSubjectName;
import android.sun.security.x509.CertificateValidity;
import android.sun.security.x509.CertificateVersion;
import android.sun.security.x509.CertificateX509Key;
import android.sun.security.x509.X500Name;
import android.sun.security.x509.X509CertImpl;
import android.sun.security.x509.X509CertInfo;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;
import io.github.muntashirakon.adb.AdbPairingRequiredException;
import io.github.muntashirakon.adb.AdbStream;

/**
 * LoopbackAdb — the v1.4 shell bridge's ADB client (design:
 * docs/loopback-shell-bridge-v1.4-design-2026-11-04.md, Route B).
 *
 * The app holds an ADB connection to the phone's own adbd over the
 * wireless-debugging TLS endpoint (discovered by AdbBridge via mDNS;
 * loopback first, Wi-Fi address second). The wire protocol, SPAKE2
 * pairing, and TLS 1.3 + exporter come from libadb-android +
 * conscrypt-android + sun-security-android (vendored in app/libs/,
 * hashes recorded in the design doc). This class is the only place
 * that touches them.
 *
 * Identity: a long-term RSA keypair + self-signed cert generated
 * on-device on first use and stored in app-private files
 * (shellbridge_key.pk8 / shellbridge_cert.der). adbd records the key
 * as authorized during the one-time pairing flow; there is no other
 * credential and nothing leaves the phone.
 *
 * Execution is one OPEN shell: stream per command, hard timeout
 * 20 s, output read capped; any protocol error reaps the connection
 * so the next call re-establishes it (the design's executor rule).
 */
public final class LoopbackAdb {
    private LoopbackAdb() {}

    private static final String KEY_FILE = "shellbridge_key.pk8";
    private static final String CERT_FILE = "shellbridge_cert.der";
    private static final long EXEC_TIMEOUT_MS = 20_000;
    private static final int READ_CAP_BYTES = 64 * 1024;
    public static final int TAIL_BYTES = 16 * 1024;

    private static final Object LOCK = new Object();
    private static ShellManager manager;
    private static final ExecutorService IO = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "loopback-adb-io");
        t.setDaemon(true);
        return t;
    });

    /** Connection manager: supplies the on-device identity to libadb. */
    static final class ShellManager extends AbsAdbConnectionManager {
        private final Context appCtx;
        private PrivateKey privateKey;
        private Certificate certificate;

        ShellManager(Context ctx) throws Exception {
            this.appCtx = ctx.getApplicationContext();
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1);
            }
            loadOrCreateIdentity();
            setTimeout(15, TimeUnit.SECONDS);
        }

        private void loadOrCreateIdentity() throws Exception {
            File kf = new File(appCtx.getFilesDir(), KEY_FILE);
            File cf = new File(appCtx.getFilesDir(), CERT_FILE);
            if (kf.isFile() && cf.isFile()) {
                byte[] keyBytes = readFile(kf);
                byte[] certBytes = readFile(cf);
                privateKey = KeyFactory.getInstance("RSA")
                        .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
                certificate = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(certBytes));
                return;
            }
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(2048);
            KeyPair kp = kpg.generateKeyPair();
            privateKey = kp.getPrivate();
            certificate = selfSigned(kp);
            writeFile(kf, privateKey.getEncoded());
            writeFile(cf, certificate.getEncoded());
            BridgeLog.log(appCtx, "SHELL", "generated loopback ADB identity (RSA 2048, self-signed)");
        }

        private Certificate selfSigned(KeyPair kp) throws Exception {
            X500Name owner = new X500Name("CN=Muse Device Assistance");
            Date from = new Date();
            Date to = new Date(from.getTime() + 10L * 365 * 24 * 3600 * 1000);
            X509CertInfo info = new X509CertInfo();
            info.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
            info.set(X509CertInfo.SERIAL_NUMBER,
                    new CertificateSerialNumber(new BigInteger(64, new SecureRandom())));
            info.set(X509CertInfo.SUBJECT, new CertificateSubjectName(owner));
            info.set(X509CertInfo.ISSUER, new CertificateIssuerName(owner));
            info.set(X509CertInfo.VALIDITY, new CertificateValidity(from, to));
            info.set(X509CertInfo.KEY, new CertificateX509Key(kp.getPublic()));
            info.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(
                    new AlgorithmId(AlgorithmId.sha256WithRSAEncryption_oid)));
            X509CertImpl cert = new X509CertImpl(info);
            cert.sign(kp.getPrivate(), "SHA256withRSA");
            return cert;
        }

        private static byte[] readFile(File f) throws Exception {
            try (FileInputStream in = new FileInputStream(f);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                return out.toByteArray();
            }
        }

        private static void writeFile(File f, byte[] data) throws Exception {
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(data);
            }
        }

        @Override
        protected PrivateKey getPrivateKey() {
            return privateKey;
        }

        @Override
        protected Certificate getCertificate() {
            return certificate;
        }

        @Override
        protected String getDeviceName() {
            return "muse-device-assistance";
        }
    }

    private static ShellManager manager(Context ctx) throws Exception {
        synchronized (LOCK) {
            if (manager == null) manager = new ShellManager(ctx);
            return manager;
        }
    }

    /** Generate/load the identity without connecting (pairing needs it). */
    public static boolean ensureIdentity(Context ctx) {
        try {
            manager(ctx);
            return true;
        } catch (Exception e) {
            BridgeLog.log(ctx, "SHELL", "identity setup failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    /**
     * One-time pairing against the pairing endpoint shown by the
     * system's "Pair device with pairing code" dialog. SPAKE2 + TLS
     * are libadb's; on success adbd has authorized this app's key.
     */
    public static boolean pair(Context ctx, String host, int pairingPort, String code) {
        synchronized (LOCK) {
            try {
                boolean ok = manager(ctx).pair(host, pairingPort, code);
                BridgeLog.log(ctx, "SHELL", "pairing with " + host + ":" + pairingPort
                        + " -> " + ok);
                return ok;
            } catch (Exception e) {
                BridgeLog.log(ctx, "SHELL", "pairing failed: "
                        + e.getClass().getSimpleName() + " " + e.getMessage());
                return false;
            }
        }
    }

    /** Connect to the phone's own adbd if not already connected. */
    public static boolean ensureConnected(Context ctx) {
        synchronized (LOCK) {
            try {
                ShellManager m = manager(ctx);
                if (m.isConnected()) return true;
                String ep = Store.get(ctx, Store.K_ADB_ENDPOINT, "").trim();
                String host = null;
                int port = -1;
                int colon = ep.lastIndexOf(':');
                if (colon > 0) {
                    host = ep.substring(0, colon);
                    try {
                        port = Integer.parseInt(ep.substring(colon + 1));
                    } catch (NumberFormatException ignored) {}
                }
                if (port <= 0) {
                    BridgeLog.log(ctx, "SHELL", "no discovered ADB endpoint to connect to");
                    return false;
                }
                // Loopback first (no LAN in the path); the Wi-Fi address
                // second (same listener, the address AdbBridge verified).
                if (tryConnect(ctx, m, "127.0.0.1", port)) return true;
                if (host != null && !"127.0.0.1".equals(host)) {
                    if (tryConnect(ctx, m, host, port)) return true;
                }
                return false;
            } catch (Exception e) {
                BridgeLog.log(ctx, "SHELL", "connect failed: "
                        + e.getClass().getSimpleName() + " " + e.getMessage());
                return false;
            }
        }
    }

    private static boolean tryConnect(Context ctx, ShellManager m, String host, int port) {
        try {
            boolean ok = m.connect(host, port);
            BridgeLog.log(ctx, "SHELL", "connect " + host + ":" + port + " -> " + ok);
            return ok;
        } catch (AdbPairingRequiredException e) {
            BridgeLog.log(ctx, "SHELL", "connect " + host + ":" + port
                    + " -> pairing required (not paired yet)");
            return false;
        } catch (Exception e) {
            BridgeLog.log(ctx, "SHELL", "connect " + host + ":" + port + " failed: "
                    + e.getClass().getSimpleName() + " " + e.getMessage());
            return false;
        }
    }

    /** Outcome of one shell execution. exit is -1: the classic shell:
     *  stream carries no exit code; ok means the stream completed. */
    public static final class Result {
        public final boolean ok;
        public final int exit;
        public final String stdoutTail;
        public final long ms;
        public final String error;

        Result(boolean ok, String stdoutTail, long ms, String error) {
            this.ok = ok;
            this.exit = -1;
            this.stdoutTail = stdoutTail;
            this.ms = ms;
            this.error = error;
        }
    }

    /**
     * Execute one allowlisted argv. The argv is joined with single
     * spaces for the shell service — safe ONLY because ShellAllowlist
     * guarantees no arg contains whitespace or metacharacters. Never
     * call this with anything that did not pass the gate.
     */
    public static Result execute(Context ctx, String[] argv) {
        long t0 = System.currentTimeMillis();
        if (!ensureConnected(ctx)) {
            return new Result(false, "", System.currentTimeMillis() - t0, "not connected");
        }
        String cmdline = String.join(" ", argv);
        Future<Result> fut = IO.submit(() -> {
            long s0 = System.currentTimeMillis();
            try {
                ShellManager m = manager(ctx);
                try (AdbStream stream = m.openStream("shell:" + cmdline)) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = stream.read(buf, 0, buf.length)) != -1) {
                        if (out.size() < READ_CAP_BYTES) {
                            out.write(buf, 0, Math.min(n, READ_CAP_BYTES - out.size()));
                        }
                    }
                    byte[] all = out.toByteArray();
                    int from = Math.max(0, all.length - TAIL_BYTES);
                    String tail = new String(all, from, all.length - from,
                            java.nio.charset.StandardCharsets.UTF_8);
                    return new Result(true, tail, System.currentTimeMillis() - s0, null);
                }
            } catch (Exception e) {
                reap(ctx);
                return new Result(false, "", System.currentTimeMillis() - s0,
                        e.getClass().getSimpleName() + " " + e.getMessage());
            }
        });
        try {
            return fut.get(EXEC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            fut.cancel(true);
            reap(ctx);
            return new Result(false, "", System.currentTimeMillis() - t0,
                    "timeout after " + EXEC_TIMEOUT_MS + "ms");
        }
    }

    /** Drop the connection after a protocol error; next call rebuilds. */
    private static void reap(Context ctx) {
        synchronized (LOCK) {
            try {
                if (manager != null) manager.disconnect();
            } catch (Exception ignored) {}
        }
    }

    public static boolean isConnected(Context ctx) {
        try {
            return manager(ctx).isConnected();
        } catch (Exception e) {
            return false;
        }
    }

    public static void close(Context ctx) {
        synchronized (LOCK) {
            try {
                if (manager != null) {
                    manager.close();
                    manager = null;
                }
            } catch (Exception ignored) {}
        }
    }
}
