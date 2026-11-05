# v1.4 Loopback Shell Bridge — design (2026-11-04)

Status: **BUILT 2026-11-05 (v1.4.0, Route B) — awaiting the pairing
session with Jonathan; NOT installed.** The command channel, loopback
executor, pairing flow, and allowlist gate are implemented and the
APK is built and verified offline (see the build record at the end
of this doc). The renamed v1.3.2 remains the installed version of
record. Remaining before it is live: install v1.4.0, the one-time
pairing below, the self-test, and one real template end-to-end via
the Worker — plus the Worker-side `/phone-bridge/commands` and
`/phone-bridge/command-result` endpoints, which do not exist yet
(the app treats a 404 as "no channel" and stays inert).

## What v1.4 is

From the repo README (v1.1.0 section, renumbered to v1.4): the app
pairs once in-app (LADB pattern) with the phone's own wireless
debugging, holds an ADB connection to itself over loopback
(127.0.0.1:<wireless-debugging connect port>), and pulls
**allowlisted** shell commands down the existing Worker channel,
posting results back over the same channel. It kills the VM→phone
tailnet dial entirely — which matters: on 2026-11-04 the VM's tailnet
tunnel proxy was refusing every CONNECT (phone audit lane, Shield
lane, and box SSH all failed identically), while the phone's own
Worker HTTPS reports kept flowing. A loopback lane has no tailnet in
its path at all.

## Architecture

```
Worker (mode-sync)                     Muse Device Assistance app
  GET  /phone-bridge/commands?secret=…     BridgeService cycle:
  POST /phone-bridge/command-result        1. fetchDesired (existing)
        {id, template, args}    ────►      2. fetchCommands (new)
                                           3. ShellAllowlist.check()
                                           4. LoopbackAdb.execute(argv)
                                           5. postReport + postCommandResult
        {id, ok, stdout_tail, ms} ◄────
```

- **Command shape is structured, never a shell string:**
  `{id, template, args[]}`. The Worker stores commands Muse (or a
  Muse-side script) enqueued; each command has a TTL (default 10 min)
  and is delivered once. The app executes at most one command per
  bridge cycle, output capped at 16 KB tail.
- **The gate is `ShellAllowlist`** (built this run:
  `app/src/.../ShellAllowlist.java`, pure Java, no Android deps).
  Templates are an argv table with typed slots (package name, settings
  namespace/key/value, role enum, bounded int). The load-bearing rule:
  **no arg may contain whitespace or any shell metacharacter**, because
  the ADB shell service joins argv with spaces — a space inside an arg
  would split into a smuggled token. The Worker cannot widen the
  allowlist; adding a template is an app code change + review.
  Verified this run on a plain JVM: 13/13 gate tests pass (allows,
  injection refusals, range checks) — test harness at
  `tools/ShellAllowlistTest.java`, run with javac/java, no Android SDK.
- **Executor interface:** `LoopbackAdb.execute(String[] argv) ->
  {exit, stdout}` over the paired loopback connection, one OPEN
  `shell:` stream per command, hard timeout 20 s, connection reaped
  and re-established on any protocol error.

## The hard part: pairing, and why the executor is not built yet

Android 11+ wireless pairing is **SPAKE2 (BoringSSL curve25519
SPAKE2) over the pairing port, then a TLS 1.3 session** in which the
host presents its long-term RSA key/cert and adbd records it as
authorized (AOSP `pairing_auth` API: `pairing_auth_client_new(pswd)`,
`pairing_auth_get_spake2_msg`, `pairing_auth_init_cipher`). The
subsequent connect session is also TLS 1.3 and needs the TLS
*exporter* (`exportKeyingMaterial`) for the ADB AUTH exchange.
Every working in-app implementation found in the 2026-11-04 online
check stands on the same three-legged stack:

- `libadb-android` — the ADB wire protocol client (CNXN/AUTH/OPEN),
- `conscrypt-android` — TLS 1.3 + exporter (on-device builds reach
  the platform Conscrypt only through hidden-API bypass, which
  targetSdk 35 / Android 17 hidden-API enforcement puts at risk),
- `sun-security-android` — ADB-compatible RSA key generation.

(LADB ships the same stack as bundled native/dex libraries.)

This app's raw build is deliberately framework-only: `build.sh`
compiles with aapt2/javac/d8 straight from the SDK, and its guards
**fail the build if the dex references any androidx class** and
require every app class present in the root dex. There is no
dependency mechanism, no AAR/native-lib packaging stage, and no NDK
in the toolchain on hand. Three honest routes:

- **Route A — pure-Java SPAKE2 + ADB.** SPAKE2 over Ed25519 is
  implementable in pure Java (group ops + HKDF + AES-GCM are all
  reachable: javax.crypto covers HKDF-adjacent HMAC and AES-GCM;
  Ed25519 point arithmetic is hand-rolled, ~500–700 lines, and is
  exactly the kind of crypto that must not be hand-rolled casually).
  TLS exporter access still needs platform Conscrypt internals.
  Highest risk, zero new dependencies.
- **Route B — adopt the three libraries into the raw build.**
  Extract the AARs' classes.jar + native .so into `app/libs/`, extend
  build.sh to dex the jars and package `lib/<abi>/`, and amend the
  dex guards to allow the named libraries (androidx stays banned).
  This is the LADB-proven stack and the recommended route — but it
  is a build-system change whose every failure mode is on-device
  (pairing is a live handshake), so it cannot be landed responsibly
  in a run where the phone is unreachable and no APK can be
  installed or verified.
- **Route C — skip pairing; authorize the app key externally.**
  Not viable: adbd authorizes keys via the pairing flow (or a USB
  RSA prompt for a *different* host identity). The VM's authorized
  key cannot delegate to the app's key. Recorded so it is not
  re-proposed.

**Decision for this run:** Route B, executed in the next run where
(a) the ADB lane is up (install + verify possible) and (b) Jonathan
is present for the one-time pairing-code step (below). This run
ships the design, the allowlist engine + tests, and the Worker
protocol spec; it does not ship an APK.

## One-time pairing UX (needs Jonathan at the phone, once)

1. Install v1.4 (needs the VM ADB lane, or a direct install tap).
2. In the app: "Pair local shell" → the app shows its pairing screen.
3. On the phone: Settings → Developer options → Wireless debugging →
   **Pair device with pairing code**; type the 6-digit code into the
   app (split-screen keeps the dialog alive — the LADB lesson).
4. App completes SPAKE2+TLS pairing, stores its RSA key in
   app-private storage, connects over loopback, runs the `battery`
   template as the self-test, and reports `shell_bridge: {paired:
   true, self_test_ok: true}` in its next Worker report.
After that: no further taps. Wireless-debugging toggles rotate the
connect port; the app already discovers it via AdbBridge mDNS.

## Security bounds

- Allowlist only (above); no free-form commands, ever; the Worker is
  a transport, not an authority — a compromised Worker can only ask
  for templates that already exist.
- Every executed command + result tail is written to the on-device
  bridge log and summarized in the Worker report (command id,
  template, ok/ms — args for settings_put are logged; nothing else
  leaves the phone).
- A UI switch ("Local shell") disarms the executor entirely, same
  pattern as the ADB self-heal switch; default ON only after the
  pairing self-test passes.
- Phase 2 actuation gates are untouched: the shell bridge does not
  arm routines, and role-borrow templates exist for the voicemail
  migration but inherit that skill's borrow/restore + read-back
  rules (restore is trap-guaranteed; a vacant role is a failure).

## What unblocks the build (next run checklist)

1. ADB lane recovered (install path) — see the 2026-11-04 backlog
   note: the VM tailnet tunnel proxy is refusing CONNECTs; the phone
   itself is alive and reporting via the Worker.
2. Fetch the three Route-B artifacts at pinned versions
   (libadb-android 3.1.0, conscrypt-android 2.5.3,
   sun-security-android 1.1 — versions as documented by the anyapk
   integration write-up, 2026-11-04 online check), hash them, and
   record the hashes here before first build.
3. Extend build.sh (libs stage + guard amendment), bump
   VERSION_CODE 7 / VERSION_NAME 1.4.0, build, install, pair with
   Jonathan, verify self-test + one real template
   (`standby_set_active` for Tailscale) end-to-end via the Worker.
4. Only then: GitHub snapshot + Release v1.4.0 (standard bundle),
   and the voicemail-borrow migration (depends on v1.4) unblocks.

## Build record — v1.4.0 (2026-11-05)

Route B executed. Step 2 of the checklist above was completed with
these artifacts (fetched 2026-11-05; the AARs/JAR are vendored as
extracted classes.jar + jni/ under `app/libs/`, local-only build
input like the keystore — recreate from these coordinates + hashes):

| Artifact | Source | SHA-256 |
|---|---|---|
| libadb-android-3.1.0.aar | JitPack `com.github.MuntashirAkon:libadb-android:3.1.0` | `e6fcb495a20a507ca4a125afc9c554e838a84b3a15fbcc29f7deafd0f88bbb58` |
| sun-security-android-1.1.aar | JitPack `com.github.MuntashirAkon:sun-security-android:1.1` | `b6421c5b446fc574af47e61161b0ac67bea37b84a1ac400ba1cee74634628f54` |
| spake2-android-2.2.0.aar (libadb transitive) | JitPack `com.github.MuntashirAkon.spake2-java:spake2-android:2.2.0` | `ee1210525de61e5eb9fe1a08d1d650b3a821a97d5429a73bb0c31ba12ffc68a7` |
| conscrypt-android-2.5.3.aar | Maven Central `org.conscrypt:conscrypt-android:2.5.3` | `551ae4e301c571760d1791e647db6ed1dcb10d34dcae7aa12b67f220f2ce98d1` |
| bcprov-jdk15to18-1.81.jar (libadb transitive) | Maven Central `org.bouncycastle:bcprov-jdk15to18:1.81` | `0ef9c4d9536719aaa1c92a7c694d87981fa9d4d64dd9479cc322a08aebbc651e` |

Implemented: `LoopbackAdb` (identity in app-private files,
RSA 2048 + self-signed cert via sun-security; connect to the
AdbBridge-discovered endpoint, loopback first; one `shell:` stream
per command, 20 s hard timeout, 16 KB output tail, reap on error),
`ShellBridge` (one command per cycle after fetchDesired; TTL check;
ShellAllowlist gate; result POST; `shell_bridge` object added to
every report), WorkerClient `fetchCommand`/`postCommandResult`,
the MainActivity pairing screen + "Local shell" disarm switch, and
the build.sh libs stage. The dex guard amendment is narrow: androidx
stays banned except `androidx.annotation` (the compile-time
annotation artifact the libraries carry; d8 strips annotations and
they never execute). The allowlist gate re-tested on a plain JVM
against the renamed sources: 13/13 pass.

- APK: `app/Muse-Device-Assistance-1.4.0.apk`, 5,687,299 bytes,
  SHA-256 `b422fb71b214ed009060428aa7f9ed104496b0e41cdcae85a6cdbcb8d4fa3535`
- Signing cert unchanged (`4dcb45fa...`). Badging: package
  `com.jonathancruzate.metamusedeviceassistance`, versionCode 7,
  versionName 1.4.0, label "Muse Device Assistance", minSdk 29,
  targetSdk 35, `extractNativeLibs=true` (new in v1.4, for the JNI
  libs: libconscrypt_jni + libspake2, arm64-v8a/armeabi-v7a/x86/x86_64).
- Build guards: 28 app classes; library presence verified in dex
  (libadb, conscrypt, sun-security x509, BouncyCastle, spake2);
  native libs verified in the APK. Single classes.dex.
- Phase 2 untouched: arm state still defaults DISARMED.
- Known executor limit: the classic `shell:` stream carries no exit
  code, so results report ok = stream completed, exit = -1.
- NOT installed, NOT paired: the pairing UX above is Jonathan's
  one-time session. Worker endpoints (checklist step 3's other half)
  still to be deployed server-side.
