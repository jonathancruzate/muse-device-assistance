# Complete-execution run — 2026-11-08 (Jonathan's order, same day)

Executed by Muse (subagent session for the Device Assistance
complete-execution prep). Jonathan was present in the parent chat for
later phone steps; nothing was asked of him in this run. Phase 2
actuation was NOT armed, linked, or test-fired — it remains DISARMED
(the live report's plan line still reads "(shadow: not fired)").

## (a) Worker command-channel endpoints — LIVE, verified (no deploy was needed for the channel itself)

The task brief said the endpoints "reportedly did NOT exist as of
2026-11-05". That was stale: the channel was built into
`~/workspace/cloudflare-workers/mode-sync/worker.js` on 2026-11-05
(20:19 edit; `phone-bridge-cmd.sh` + `test-commands.mjs` landed the
same day) and was already deployed — the deployed script fetched via
the Cloudflare API on 2026-11-08 was byte-identical to the local
`worker.js` before this run's edit.

Live verification (2026-11-08 ~07:08Z, against
`https://jonathancruzate.com/mode-sync`):

- `GET /phone-bridge/command-status` (no id) → `{"pending":[]}`
- `GET /phone-bridge/commands` on the empty queue → HTTP 200
  `{"command":null}`
- Full round-trip probe, id `prep-probe-20261108-0207`, template
  `battery`: `POST /phone-bridge/enqueue` → pending;
  `GET /phone-bridge/commands` delivered it exactly once;
  `POST /phone-bridge/command-result` accepted a (clearly labeled
  probe-only) result; `command-status` then read back
  `state:"done"` with `delivered_at` + the result. Queue left empty.
  Note: the first status read after the result POST still showed
  `pending` — Cloudflare KV eventual consistency; the settled
  read-back (after ~60 s) showed `done`. Read-backs of command
  records should allow a propagation window before judging.
- Local unit suite `test-commands.mjs`: ALL PASS (32 checks before
  this run's addition; 34 after).

### Gap found + fixed in this run: `shell_bridge` report passthrough

The v1.4 app sends `shell_bridge {paired, self_test_ok, on, last?}`
in every Worker report (`WorkerClient` + `ShellBridge.snapshot`),
but the Worker's report sanitizer dropped the field entirely, so
pairing state could never be verified server-side. Fix deployed
2026-11-08 via the folder's preserve-bindings `deploy.py`
(bindings before/after identical: KV `MODE_SYNC` + secrets
`PANEL_SLUG`, `PHONE_PUSH_SECRET`, `POLL_SECRET`):

- `handlePhoneBridgeReportPost` now keeps a sanitized `shell_bridge`
  (booleans + capped `last {id, template, ok, ms, at}`), only when
  the app sent one (pre-v1.4 reports are unchanged in shape).
- Regression + passthrough checks added to `test-commands.mjs`
  (ALL PASS, 34 checks).
- Pre-edit source kept as `worker.js.bak-20261108-pre-shellbridge-report`.
- Live proof: the first post-deploy heartbeat report
  (received 2026-11-08T07:27:23Z) carries
  `shell_bridge: {paired:false, self_test_ok:false, on:false}` —
  the true pre-pairing state of the phone.

## (b) APK install — v1.4.0 verified installed and running

Surprise on first read-back: the phone **already ran v1.4.0**
(versionCode 7) when this run started — an earlier install had
landed without being recorded in the skill/connector docs (both
still described v1.3.2 as the installed version of record). This run
re-installed in place and verified everything by read-back:

- Lane: `~/workspace/bin/adb-ensure.sh` (Worker-reported endpoint
  port + tailnet host 100.88.51.94, local forward `localhost:14253`;
  device SM_S926U1). Note: with the Shield also attached
  (127.0.0.1:5556), always pass `-s <serial>` — bare `adb` commands
  fail with "more than one device/emulator".
- Pre-install `dumpsys package`: versionCode 7 / versionName 1.4.0.
- `adb install -r app/Muse-Device-Assistance-1.4.0.apk` → Success
  (APK SHA-256
  `b422fb71b214ed009060428aa7f9ed104496b0e41cdcae85a6cdbcb8d4fa3535`,
  same signing cert as every prior version; in-place, prefs and
  grants survive — no uninstall, no package swap).
- Post-install read-backs:
  - versionCode 7 / 1.4.0; `lastUpdateTime=2026-11-08 02:11:19` EST
    (this run's install); firstInstallTime 2026-11-05 (the rename
    cutover).
  - `BridgeService` running as foreground service
    (`dumpsys activity services`, process alive).
  - Fresh reports landing post-install (change report
    2026-11-08T07:11:21Z during the install window; heartbeat
    07:27:23Z), `parse_ok=true`, verdict/ack machinery live.
  - Grants: `WRITE_SECURE_SETTINGS` granted=true (re-applied
    idempotently via `pm grant`), `POST_NOTIFICATIONS` granted,
    `NEARBY_WIFI_DEVICES` granted; notification-listener list
    includes `…metamusedeviceassistance/.GroundTruthListener`.
  - Battery: the package was **missing** from the deviceidle
    whitelist — re-added in this run
    (`dumpsys deviceidle whitelist +com.jonathancruzate.metamusedeviceassistance`,
    verified in `cmd deviceidle whitelist` output, uid 10108).
    Tailscale `battery_exempt: true` in the same report.
  - Report `adb` object: wireless_debugging true,
    secure_settings_granted true, self_heal_armed true, endpoint
    192.168.1.153:43869 (Wi-Fi LAN; port is the volatile part),
    Tailscale up at 100.88.51.94.

## (c) Pairing prep — everything staged; Jonathan's step is minimal

Pairing itself was NOT attempted (it needs his hands on the phone,
by design) and no code was requested. State: `paired:false`,
executor `on:false` — the executor auto-arms only after the in-app
self-test passes during his pairing session. His exact steps are in
the run report to the parent agent and mirror the app's own hint
text ("Local shell bridge (v1.4)" section of the app).

## (d) What can be verified after pairing — in order

Correction to the run brief: the connector's EXTENSION verbs
(`app.launch`, `nav.open`, `media.*`, `flashlight.set`, native
`dnd.set`, `screen.foreground`) are **not implemented in v1.4.0** —
the shipped `ShellAllowlist` contains shell templates only, and
there is no `am start` / intent / media / torch template. Those
verbs remain EXTENSION (a future build). What pairing unlocks, and
the verification order once Jonathan reports "paired":

1. Report read-back: `shell_bridge.paired=true`,
   `self_test_ok=true`, `on=true` in `GET /phone-bridge/report`
   (the in-app self-test is the `battery` template over loopback).
2. First real Worker-channel command: `phone-bridge-cmd.sh enqueue
   battery` → within one bridge cycle (~60 s) state `done` with a
   `dumpsys battery` tail in the result.
3. `getprop_model` (read-only identity check).
4. `deviceidle_whitelist` (read-only; also re-verifies the grant
   fixed in this run, from the phone's own loopback lane).
5. `settings_get global adb_wifi_enabled` (read-only settings read).
6. `standby_get com.tailscale.ipn` (read-only standby-bucket read).
   The design's first WRITE template, `standby_set_active` for
   Tailscale, stays a separate, deliberate step — not part of first
   verification.

`settings_put` and the voicemail role borrow/restore templates
exist in the allowlist but are writes with their own owning-skill
rules — not fired by this prep.

## (e) Pairing session 03:43 EST — paired, self-test failed; v1.4.1 fix built + installed

Jonathan ran the pairing session at 2026-11-08 03:43 EST and sent
the in-app log (screenshot `Screenshot_20261108_034420_9_386j.png`):

- `03:43:02 [ADB_ENDPOINT] pairing window advertised at 192.168.1.153:37695`
- `03:43:26 [SHELL] pairing with 192.168.1.153:37695 -> true`
- `03:43:26 [SHELL] connect 127.0.0.1:43869 -> true`
- `03:43:26 [SHELL] self-test (battery) -> ok=false 106ms error=IOException Stream closed.`
- `03:43:39 [SHELL] connect 127.0.0.1:43869 -> true`
  (second attempt — the app's "Run shell self-test" button)
- `03:43:39 [SHELL] self-test (battery) -> ok=false 46ms error=IOException Stream closed.`

**Diagnosis (evidence from the vendored jar, decompiled with javap):**
`AdbStream.read()` throws `IOException("Stream closed.")` only
when the remote CLSEs a stream whose read queue is still empty,
and `AdbConnection.open()` throws a different error
(`ConnectException: Stream open actively rejected`) when the OPEN
itself is refused — so adbd **accepted** the `shell:dumpsys
battery` OPEN (OKAY), spawned nothing, and closed the stream with
zero payload, deterministically on fresh connections.
`LoopbackAdb` never called libadb-android's `setApi()`: the
library's `AbsAdbConnectionManager` constructor defaults `mApi`
to **1** (bytecode-verified), and `AdbProtocol` maps API < 28 to
CNXN protocol version `0x01000000` with maxData **4096**, sending
the bare `host::` system identity with **no feature list**. That
is the one deviation from every working libadb consumer:
pairing (SPAKE2) and TLS/CNXN auth are API-independent and
succeeded, while the modern daemon refuses shell service to a
feature-less legacy peer. A desktop-JVM harness attempt to
reproduce A/B was abandoned as non-representative — the raw
forward path probed perfectly (CNXN → A_STLS), but the library's
STLS upgrade under the desktop SunJSSE TLS stack garbles message
framing (`StreamCorruptedException: Invalid magic 0x69746365`);
the on-device stack is Conscrypt. Diagnosis therefore rests on
the bytecode + failure signature, and v1.4.1's new connect log
line (negotiated protocol + maxData) will confirm on-device.

**Fix — v1.4.1 (versionCode 8), `LoopbackAdb.java` only:**
1. `setApi(android.os.Build.VERSION.SDK_INT)` in the
   `ShellManager` constructor → CNXN carries version 0x01000001,
   maxData 1 MB, and the library's real feature set.
2. 300 ms settle after a successful connect before the first
   stream opens + the connect log line now records the negotiated
   protocol version and maxData.
3. `execute()` retries once on a fresh connection from the
   alternate source address (Wi-Fi ↔ loopback); every allowlisted
   template is idempotent, so the retry cannot double-apply.

Build: all guards pass (28 app classes; dex defines every app
class + libadb/conscrypt/sun-security/BouncyCastle/spake2; no
banned androidx; native libs packaged); signer cert SHA-256
`4dcb45fa69b2d82ffad68f877ab491b29d989188b09bcad55f3578906d477236`
— the same cert as every prior version. ShellAllowlist gate tests
ALL_PASS (13/13). APK `app/Muse-Device-Assistance-1.4.1.apk`,
5,687,299 bytes, SHA-256
`19a9ad627a8aa7d823718981faeb3f2b0d0a9a0a4f3f4481b50cb2887f27181e`.

Install: `adb install -r` over the ADB/Tailscale lane (the lane
was flaky 03:52-04:02 EST — tailnet forward drops, port rotation
38865 → 43869; recovered in-invocation via `adb-ensure.sh`).
Read-back: versionCode 8 / 1.4.1, `lastUpdateTime=2026-11-08
04:02:59` EST, BridgeService running. **Pairing survived**: the
first post-install report (received 2026-11-08T09:03:02Z) carries
`shell_bridge {paired:true, self_test_ok:false, on:true}` — the
app-private key and prefs were untouched (update install, no data
clear), so **no re-pairing is needed**. Phase 2 remains DISARMED
(report plan line still "(shadow: not fired)").

**Next step (Jonathan, ~30 s):** open the app → "Run shell
self-test (battery)" in the Local shell bridge section → expect
"Self-test passed — shell bridge armed". The bridge is **not
live** until that passes AND the chat side runs the first Worker
`battery` command to `done` as end-to-end proof. Source push to
GitHub not run in this handling (same as the (a)-(d) run above).

## Health space verdict (for the coordinator to save)

Suggested verdict for the Device Assistance entry in the Custom
Connector Health space: **Working — engine live, pairing pending
owner.** Text: "v1.4.0 installed and reporting (verified
2026-11-08); Worker command channel live incl. shell_bridge
passthrough deployed 2026-11-08; one owner step remains: the
one-time in-app pairing. Phase 2 actuation DISARMED."

## (f) Device care crash flag 10:14 EST — dataSync FGS budget crash-loop; v1.4.2 fix built + installed

Jonathan screenshotted Samsung Device care (10:14 EST): "1 app with
app crashes: Muse Device Assistance" (alongside the intentional
Unrestricted-battery flag, which is by design and untouched).

**Crash evidence (ADB lane: exit-info, dropbox, logcat crash buffer):**
- Today: 5 crash exits 10:12:50–10:13:06 EST. First (pid 27215,
  rss 122 MB — the long-running process):
  `RemoteServiceException$ForegroundServiceDidNotStopInTimeException:
  A foreground service of type dataSync did not stop within its
  timeout: BridgeService`. The next three restarts (10:12:51,
  10:13:04, 10:13:06) died in `BridgeService.onCreate:62`:
  `RuntimeException: Unable to create service` caused by
  `ForegroundServiceStartNotAllowedException: Time limit already
  exhausted for foreground service type dataSync` at
  `Service.startForeground`. (Exit-info lists 5 records for 4
  logcat FATALs — one pid was recorded twice.)
- **Chronic, not new:** the identical onCreate/StartNotAllowed trace
  is in dropbox from **2026-11-05 10:44** onward on v1.4.0 (v7) —
  11-05 10:44, 12:45, 15:24, 18:32; 11-06 07:00 (that process had run
  21,610,559 ms ≈ 6.0 h before dying — the budget made visible);
  11-07 07:27, 08:57, 10:57, 13:27 — and it continued on v1.4.1.
  Today's first crash landed ~6 h 10 m after the 04:02:59 v1.4.1
  install restart. **Zero frames from LoopbackAdb/ShellBridge appear
  in any trace** — the v1.4.1 shell changes did not cause this, and
  the self-test path is not the crasher. Correlation with the
  still-false `self_test_ok` is coincidental timing (the app was
  dead from 10:13 until the fix install, and no report shows a
  v1.4.1 self-test attempt completing).
- **Root cause:** BridgeService ran as FGS type **dataSync**, which
  carries a platform time budget (~6 h on this Android 17 build).
  A 24/7 bridge always exhausts it. On exhaustion the system times
  the service out; with no `onTimeout` handler the process died with
  DidNotStopInTime, and every restart then threw StartNotAllowed —
  uncaught — in onCreate, crash-looping until enough budget slid
  back into the window. Device care's flag was a LIVE problem, not
  stale history: at flag time the app was dead (last report
  10:10:32 EST).

**Fix — v1.4.2 (versionCode 9):**
1. FGS type dataSync → **specialUse** (no platform time budget):
   manifest `foregroundServiceType="specialUse"` +
   `FOREGROUND_SERVICE_SPECIAL_USE` permission (dataSync permission
   removed) + the required `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
   declaration; onCreate now passes
   `ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE` explicitly.
2. `startForeground` in onCreate is guarded: a refused start logs
   and `stopSelf()`s cleanly — a crash in onCreate is now impossible
   on this path.
3. `Service.onTimeout(int,int)` implemented (API 35+): logs and
   stops cleanly, so any future platform FGS timeout is a stop,
   never a DidNotStopInTime crash.
4. `BridgeService.start()` wraps `startForegroundService` in
   try/catch (same discipline requestCycle already had).

Build: all guards pass (28 app classes; dex defines every app class
+ the named v1.4 libs; no banned androidx; native libs packaged —
the build's final grep guard flaked once on a SIGPIPE race and
passed on direct re-check of the finished APK). Signer cert SHA-256
`4dcb45fa...` (same as every prior version). ShellAllowlist gate
tests ALL_PASS. APK `app/Muse-Device-Assistance-1.4.2.apk`,
5,687,299 bytes, SHA-256
`f9cbb9379a1ddb32e8a33a161fd8f1958571ac095d3fbfdd70898483b036a3d7`.
Manifest verified in the packaged APK (type 0x40000000, subtype
property present).

Install: `adb install -r` at 2026-11-08 10:26:56 EST (read-back
versionCode 9 / 1.4.2). Update install only — pairing survived
(first v1.4.2 report, a `change` report received 10:26:58 EST,
carries `shell_bridge {paired:true, self_test_ok:false, on:true}`;
Phase 2 still DISARMED — plan line "(shadow: not fired)").
**Stability:** the post-install process (pid 10688) was verified
alive across 10:27–10:35 EST with **no new exit-info crash records
and no new dropbox crash entries after 10:13:06** (the persistence
timestamp still ends at the 10:13 cluster). Four `pid=NONE` samples
mid-watch were ADB-lane artifacts — the flaky forward lane dropped
pidof answers while the process demonstrably never exited (same
pid, no exit record). The Device care crash flag itself will only
clear from Samsung's side on its own schedule; the underlying
crashing has stopped.

**Self-test:** still `self_test_ok:false` — Jonathan has not yet
had a stable app to tap it in. With v1.4.2 proven stable, the
standing next step applies again: app → "Run shell self-test
(battery)" → then the chat side runs the first Worker `battery`
command as end-to-end proof. Source push to GitHub not run in this
handling (same as the (a)–(e) runs above).
Heartbeat confirmation (same section (f) verification): the
v1.4.2 process's 15-minute heartbeat report landed on schedule —
received 2026-11-08T15:42:05Z (10:42:05 EST), kind `heartbeat`,
`shell_bridge {paired:true, self_test_ok:false, on:true}` — and the
live service record read `isForeground=true types=0x40000000`
(SPECIAL_USE) on pid 10688. Reports are flowing on the fixed build.

## (g) Self-test failure — real root cause found (libadb end-of-stream race); v1.4.3 fix built, installed, and PROVEN end-to-end (17:07–17:43 EST)

Jonathan re-paired fresh at 17:07 EST (the 16:57 failure was a
dropped digit in the pairing port — 3759 for 37359 — not an IP
problem). Pairing succeeded (system Paired devices shows
`muse-device-assistance` alongside `@htch-runtime`), connects
negotiated modern (protocol 0x01000001, maxData 1048576), yet both
self-test attempts still failed with `IOException: Stream closed.`
at 94 ms / 38 ms. The v1.4.1 root-cause theory (legacy/feature-less
negotiation) was therefore wrong or incomplete; this section
replaces it. The v1.4.1 `setApi()` change itself is correct and is
kept; it just was not the bug.

**Discriminating evidence, in the order gathered:**

1. **Daemon side is healthy.** From the VM's desktop adb client to
   the same adbd (endpoint 192.168.1.153:43015, Android 17 / SDK 37,
   SM-S926U1): `dumpsys battery`, `echo`, `getprop` all work.
2. **Hypothesis (a) — feature-less CNXN — DISPROVEN by a raw wire
   probe.** A hand-rolled Python ADB client on the VM lane (TLS with
   the VM's own authorized key) sent libadb-android's exact CNXN
   (bare `host::`, no features) in both legacy (0x01000000/4096) and
   modern (0x01000001/1 MB) forms, plus a 300 ms post-connect settle
   variant: every run received the FULL `dumpsys battery` payload
   (13,073–13,084 bytes over 2 WRTEs, then CLSE). adbd does not care
   about client CNXN features. (Bytecode had already shown libadb
   sends no feature list regardless of `setApi`.)
3. **Daemon logcat:** every app attempt shows `adbd service
   requested 'shell:dumpsys battery'` then, ~40–65 ms later,
   `SSL_read failed [bytes_read=0]` — the daemon sees the CLIENT
   vanish (TCP FIN). Reproduced cleanly at 17:22 EST with a
   Worker-enqueued `battery` command executed by the bridge tick
   (no UI involved): delivered 17:22:19, result `ok:false, ms:68,
   error:"IOException Stream closed."`.
4. **App logcat:** the library connection thread dies with
   `java.net.SocketException: Socket closed` at
   `AdbProtocol$Message.parse(AdbProtocol.java:397)` — javap line
   tables show line 397 is the packet-HEADER read, i.e. the thread
   was blocked awaiting the next packet when the socket closed
   LOCALLY. The only socket-closing call sites in app code are
   `LoopbackAdb.reap()` (after a failed execute) and
   `LoopbackAdb.close()` (service onDestroy) — so the socket close
   is the app's own reap AFTER the stream failure, not the cause.
   The stream failure itself is upstream of reap.
5. **Instrumented diagnostic build (v1.4.3-diag1, versionCode 10,
   installed 17:32:14 EST; pairing survived)** settled it in one
   Worker-triggered execution (17:34:30 EST, logcat tag
   `LoopbackAdbDiag`): stream opened at +19 ms; reads returned
   **4096, 4096, 4096, 781 bytes — 13,069 bytes, i.e. essentially
   the entire `dumpsys battery` output — and the NEXT read threw**
   `IOException: Stream closed.` at `AdbStream.read(AdbStream.java:
   176)`. reap() was then called from the IO catch (its logged call
   site), closing the socket — the daemon FIN and the connection
   thread's `Socket closed` trace follow from that. The payload was
   arriving all along; the app read it and then discarded it
   because the final read threw instead of returning -1. (Earlier
   "zero payload" readings of this failure were wrong for the same
   reason: the Result throws the buffered output away.)

**Root cause — libadb-android 3.1.0 end-of-stream race**
(bytecode-verified in the vendored classes.jar): adbd sends a
shell command's output as WRTE packets and then CLSEs the stream.
The connection thread handles CLSE via `AdbStream.notifyClose(true)`:
if the read queue is non-empty it sets `mPendingClose` (graceful —
`read()` returns -1 after draining); **if the queue happens to be
empty it sets `mIsClosed` immediately — including when the last
payload is sitting in the reader's own `mReadBuffer`, already
polled out of the queue but not yet drained.** `read()` serves the
buffered bytes normally, but the read after they run out hits
`if (mIsClosed) throw new IOException("Stream closed.")`
(AdbStream.java:175–176) instead of returning -1. Because adbd
delivers a one-shot command's whole output in one burst, the CLSE
is routinely processed in exactly that window — the failure is
deterministic on this device, for every command, at any
negotiation. This also fully explains the v1.4.0 03:43 failures
(legacy negotiation was never the issue).

**Fix (v1.4.3, app-side, minimal):** in `LoopbackAdb.executeOnce`'s
read loop, an `IOException` whose message is exactly
`"Stream closed."` **while the connection itself is still alive**
(`manager.isConnected()`) is treated as end-of-stream: the bytes
already read (possibly zero, for no-output templates) are the
command's output. Rationale: with a live connection, that throw
can only be the remote-CLSE path above; a genuinely dead
connection flips `isConnected()` via the library's cleanupStreams,
and any other IOException still fails the execution (and reaps) as
before. The diag1 logcat instrumentation (reap call site, stream
open/failure with stack, plus a line when the race-EOF path fires)
is kept in v1.4.3 as the standing discriminator. The stale v1.4.1
theory comment in `ShellManager` was corrected in the source.

**Build:** v1.4.3, versionCode 11, `app/build.sh` bumped; all build
guards pass; ShellAllowlist gate tests ALL_PASS; signer cert
SHA-256 `4dcb45fa...` (same as every version). Diagnostic build
v1.4.3-diag1 (versionCode 10) APK SHA-256
`58af89c498ad123967adaa7250616e518ccfe22b25114a614314ec949abca42e`.
Fix build APK `app/Muse-Device-Assistance-1.4.3.apk`, 5,687,299
bytes, SHA-256
`b40b70827b6b03380be05983c01e28ad2dd1fb64ad2853c3d6a982ffbaec4d6b`.

**Install + proof:** `install -r` at 2026-11-08 17:39:35 EST
(dumpsys read-back: versionCode 11 / 1.4.3). First post-install
report (22:39:37Z) carries `shell_bridge {paired:true,
self_test_ok:false, on:true}` — the 17:07 pairing SURVIVED; no
re-pairing. Then, from the chat side via the Worker channel:
- `battery` (cmd-1ebc0a06-f74f-4d1a-822d-ec752650dd20): delivered
  17:40:51 EST, result **`ok:true`, ms 75**, full `dumpsys battery`
  tail returned (level 87, AC powered true, voltage 4186). Logcat:
  `end-of-stream via libadb race: treating 'Stream closed.' as
  EOF, bytes=13065`.
- `getprop_model` (cmd-eeaaf74e-8fac-411e-8433-cf492f5e0a15):
  **`ok:true`, ms 75**, output `SM-S926U1` (bytes=10 via the same
  race-EOF path).

**The loopback shell bridge is LIVE for Worker commands** — the
first end-to-end completions in the channel's history. One item
remains, and it is bookkeeping, not function: the report's
`self_test_ok` flag is set only by the in-app self-test
(`ShellBridge.runSelfTest`, the same `LoopbackAdb.execute` path
just proven), so it still reads false until Jonathan taps "Run
shell self-test (battery)" once in the app; the tap exercises the
identical code path and is expected to pass. Phase 2 remains
DISARMED and untouched — plan line still "(shadow: not fired)".
Nothing was re-paired in this handling.
Upstreaming: the `github-export/` snapshot was synced in this
handling (LoopbackAdb.java, build.sh, BridgeService.java and
AndroidManifest.xml — the latter two were stale from before (f) —
SKILL.md, this record, and the export README build record incl. a
correction note on the v1.4.1 entry). The GitHub push itself was
not run, same as the (a)–(f) runs above.

## (h) v1.5 extension pack — built, installed, and live-verified verb by verb; voice-mode module built (gate proven, enable steps staged); Gadget SDK leverage evaluated (20:35–21:57 EST)

Jonathan's instruction, verbatim: "Build the v1.5 extension pack
next" + "Leverage the judge gadget sdk as much as might make
sense", plus two additions the same night: the voice-mode
self-activation module (his words: "allowing you to turn on voice
mode yourself so you can decide when to talk") and an explicit
evaluation of his example "the sdk says you can probably see my
screen with Home Link we have."

**Method (per the brief):** every verb's underlying command was
FIRST proven on this actual phone (SM-S926U1, Android 17 / SDK 37)
over the VM ADB lane (tailnet `100.88.51.94`, app-reported connect
port 43015), THEN encoded as an allowlist template / gated native
op, gate-tested, built, installed in place, and verified live
through the Worker command channel with an independent read-back
where the effect is observable.

**ADB-lane proofs (before encoding):** `am start -a MAIN -c
LAUNCHER com.google.android.apps.maps` → focus read-back
`com.google.android.apps.maps/.MapsActivity`. `input keyevent
85/87/88` → Spotify session PAUSED(2)→PLAYING(3)→PAUSED(2), track
index 1→2 (next) and 2→3 (previous), all via `dumpsys
media_session`. `cmd media_session volume --stream 3 --get` → 12,
range [0..15] on this device; `--adj raise` 12→13, `--adj lower`
13→12, `--set 12` → 12. `cmd notification set_dnd on` (present in
`cmd notification help`) → `settings get global zen_mode` 0→2;
`off` → 0. `dumpsys window displays | grep -m 1 mCurrentFocus` →
single focus line (the raw dump is 45,410 bytes with the focus at
~byte 3,457 — the loopback executor returns the output TAIL, so
the raw form would scroll the one line that matters off the
result; the fixed zero-arg pipe template exists for that reason
and is commented as such in the gate). Flashlight: NO shell torch
route exists on this build (`cmd -l` / service list checked) →
on-device CameraManager route, gated in the same allowlist class
as native templates.

**Encoding:** 11 new shell templates (`app_launch`, `nav_open`,
`media_play_pause`, `media_next`, `media_previous`,
`media_volume`, `media_volume_set`, `media_volume_get`, `dnd_on`,
`dnd_off`, `screen_foreground`) + 3 native templates
(`flashlight` on|off, `flashlight_status`, `voice_mode`
toggle|on|off) gated by `ShellAllowlist.checkNative`. No free-form
shell anywhere: structured {template, args[]}, global arg hygiene
(no whitespace/metacharacters) shared by both gates, per-slot
validators (PKG for launch; DEST_TOKEN for nav — charset excludes
space/`&`/`=` outright, and the builder percent-encodes into the
`google.navigation:q=` value as defense-in-depth; volume set is a
bounded int 0..15, the device's proven STREAM_MUSIC range).
`ShellBridge` dispatches native ops before the shell gate, through
the same log/post/record flow. Gate tests extended for every new
template (allowed argv, exact describe strings, refusals:
nav destinations with space/`&`/`=`/`;`, bad packages, volume
16/−1/7.5/injection, native bad args) — **ALL_PASS**.

**Voice-mode module (pack item 8):** the Muse Voice Mode
Shortcut's proven classes ported in (`VoiceState`, `VoiceLauncher`,
`VoiceAccessibilityService` — ACTION_ASSIST into
`com.facebook.aura`, `hatch_voice_button` view-id candidates then
exact text/desc labels, end-call ids/labels with Back→Home
fallback, state persisted on-device), wrapped in
`VoiceModeModule`'s two-key gate: the Worker verb is REFUSED
unless (1) Jonathan enables the module in-app (checkbox, default
OFF — `Store.K_VOICE_ENABLED`) AND (2) the accessibility service
is granted and running. Reports carry `voice_enabled` /
`voice_active`; the app UI shows module + service state and has a
gated "Test voice toggle" button. The ask-first rule is unchanged
and remains Muse's chat-side behavior (skill); the app provides
capability + gates only.

**Build:** v1.5.0, versionCode 12, `app/build.sh` bumped; all
build guards pass (42 app classes, dex contents, no
non-annotation androidx, native libs packaged); signer cert
SHA-256 `4dcb45fa…7236` (same as every version). APK
`app/Muse-Device-Assistance-1.5.0.apk`, 5,699,668 bytes, SHA-256
`8dfbba37bc998e476de64732b945bad8db9096af8e6a91fef845a928e20666e9`.
(Build-environment note: the workspace tree raced the build twice
— a phantom `produceTv` javac error and a d8
`NoSuchFileException` on class files that existed in a /tmp
compile — so the release build ran from a /tmp copy of the app
tree; the APK was copied back to `app/`. Sources in the workspace
tree are the canonical ones and compile clean.)

**Install + proof:** `install -r` at 2026-11-08 20:53:57 EST
(dumpsys read-back: versionCode 12 / 1.5.0; firstInstallTime
preserved — in place, same cert, NO re-pairing). First
post-install report (20:53:58 EST) carries `shell_bridge
{paired:true, self_test_ok:true, on:true}` — pairing and
self-test survived.

**Operational finding — the Worker command channel is
single-shot per fetch.** A batch of 18 enqueued commands executed
exactly two (the newest pending at each of the app's first two
fetches: a `screen_foreground` at 21:56:00Z→01:56 EST and
`media_play_pause` at 01:57 EST) and the other 16 sat `pending`
until TTL despite the app cycling — each command is effectively
served once, and the app executes one per fetch. All live
verification below therefore ran as sequential single enqueues
(enqueue → await result → next), which completed every command in
~60–75 s each. Future Worker command work: enqueue singly, never
in batches. (The orphaned batch commands expired harmlessly at
their 30-min TTL; none executed late — state read-backs after the
fact confirm no stray dnd/flashlight/volume changes.)

**Per-verb live verification (Worker channel, v1.5.0):**

| Verb (template) | Result | Independent read-back |
|---|---|---|
| `screen.foreground` (`screen_foreground`) | LIVE — ok:true, 55–158 ms | Returns the real `mCurrentFocus` line (Muse app; Messenger `InCallActivity` during his call) |
| `app.launch` (`app_launch`) | LIVE — ok:true | ADB-lane focus proof (MapsActivity foreground); Worker run: "its current task has been brought to the front"; **Jonathan's own observation, 20:41 EST in chat: "App control is working well and opening apps, fyi"** |
| `nav.open` (`nav_open`) | LIVE — ok:true | Intent delivered (`dat=google.navigation:q=Astoria`, Maps task fronted). Foreground read-backs in the same window were polluted by his active Messenger call's in-call UI (focus held by `InCallActivity`, once null mid-transition) — the intent + task evidence stands; a clean focus delta was not capturable while he was on the call |
| `media.play_pause` / `media.next` / `media.previous` | LIVE — all ok:true | Effect proven on the ADB lane pre-encoding (Spotify state + track-index read-backs). The Worker `media_play_pause` fired while no session was active later (session had ended; phone in his hands), so no fresh session delta was observable for that one fire |
| `media.volume` (`media_volume` raise/lower, `media_volume_set`, `media_volume_get`) | LIVE — all ok:true | get=12 baseline → raise → get=15 → set 12 → get=12 exactly. The raise step read +3, not the +1 the quiet lane showed earlier — the run happened during his call, when the media-session adjust step differs / his own button presses interleave; the set+get pair is exact, and volume was restored to 12 |
| `dnd.set` (`dnd_on`/`dnd_off` shell templates) | LIVE — ok:true | ADB-lane read-back in the same minute: `zen_mode` 0→**2** after `dnd_on`, 2→**0** after `dnd_off`. FIRST attempt (during his call) read 0 a cycle later — state interference while a call/Mode held zen; the tight-coupled retest is unambiguous. **The live lane for `dnd.set` is now the in-app shell template; the MacroDroid delegated pair remains the fallback** |
| `flashlight.set` (`flashlight`/`flashlight_status`, native) | LIVE — ok:true | Camera-service event log: "Torch for camera id 0 turned on for client PID 13954" (the app); `flashlight_status` read back **torch=on**; off → status **torch=off** (final state verified off). Two earlier on→off readings are explained by the same log: torch activity from another client PID at 21:46 and the phone in active use — the torch was turned off again within the minute those times. Cosmetic known-lag: the result text's "(callback-confirmed)" vs "(set accepted)" depends on a 150 ms beat in `setTorch`; the callback record (what `flashlight_status` reads) catches up within the cycle — status is the truth path |
| `voice_mode` (native, module) | BUILT — NOT LIVE | Gate proof via Worker: `voice_mode toggle` → **ok:false, "refused: voice module disabled"** (cmd-4c46d6bf) — disabled means the capability does not exist, exactly as designed. Full toggle awaits Jonathan's two enable steps (below) |

**Voice module — Jonathan's two enable steps (staged exactly):**
(1) Open Muse Device Assistance → check "Voice module: allow
Muse voice-mode start/stop from chat". (2) Settings →
Accessibility → Installed apps → Muse Device Assistance → On.
Then a Worker `voice_mode toggle` can be live-verified the
shortcut skill's way: the voice session must OBSERVABLY start
(audio session / Muse voice UI), never on the tap alone. Until
then the verb stays labeled built-but-not-live everywhere.

**Gadget SDK leverage report (Jonathan: "leverage the judge
gadget sdk as much as might make sense"):**
- Inventory: `~/workspace/muse-gadget-sdk/` = ESP32 firmware +
  Linux `musegadget` Python host (BLE pairing to the Muse app,
  commands via `/etc/musegadget/commands.d/*.json`) + a skills
  catalog of consumer-gadget playbooks. Its `android/` tree is a
  test streamer app, not a phone host — **the SDK does not run on
  the phone, so the phone verbs stay on the Worker/shell channel
  (nothing forced).**
- (a) Routing a pack capability through a gadget host: REJECTED
  on live-state evidence. The Windows PC / Shield bridge project
  (`~/workspace/muse-gadget-windows-shield/`) is design/build
  only and has **never paired** (its own record: zero connections
  ever); the `shield/` tree is an empty placeholder. No pack verb
  targets a gadget-host device anyway (all seven are phone
  verbs). No live gadget command surface exists to route through,
  and the brief forbade blocking the phone pack on gadget pairing
  work.
- (b) Pattern reuse: ADOPTED where real. The pack's verb surface
  follows the SDK's/plugin model's dotted, namespaced
  command-definition convention (`app.launch`, `nav.open`,
  `media.*`, `dnd.set`, `flashlight.set`, `screen.foreground`,
  `voice_mode` — the same shape as the connector's `buds.*` plugin
  registration), and the gate mirrors the SDK's command-table
  discipline: fixed named commands, typed/validated args, no
  free-form execution strings.
- (c) Jonathan's example — "the sdk says you can probably see my
  screen with Home Link we have" — evaluated against
  `protocols/VIDEO.md`, `~/workspace/muse-home-link/BUILD.md`,
  and the skills catalog. **Verdict: no existing gadget/Home Link
  surface gives Muse screen visibility, and VIDEO.md cannot
  serve `screen.foreground`.** Reasons: (1) VIDEO.md streams
  phone → *gadget display* (H.264/VP8, sourced from the camera or
  a clip via the Muse Gadgets Test app) — frames terminate ON
  the gadget's panel; nothing flows back to Muse. (2) The stream
  source is camera/clip, never the phone's screen. (3) The DIY
  Home Link board (`homelink-dda658`) is a bare ESP32-C5
  DevKitC-1 — no display, no camera — while the video panels are
  ESP32-S3 (232×232 round) / S31 (480×480); the C5 isn't a video
  target at all. (4) `screen.foreground` needs a window-focus
  fact (which package holds focus), not pixels — already proven
  via `dumpsys`. What real Muse-side screen visibility WOULD
  take: MediaProjection capture inside Device Assistance itself
  (per-session consent grant from Jonathan on-device; a new
  capture + transport path, privacy-sensitive) — recorded here as
  a STAGED FOLLOW-ON, not built, not blocking. A gadget-display
  build (S3 panel + firmware + Gadgets Test app streaming) would
  put frames on a desk gadget, still not in front of Muse.

**Phase 2 actuation: DISARMED and untouched** throughout — no
arming surface was read, flipped, or test-fired in this handling.
Nothing was re-paired. The Worker secret and key material were
never logged or extracted. Device left tidy: volume 12 (his
baseline), zen_mode 0, torch off (final read-backs 21:55–21:56
EST).
Upstreaming: the `github-export/` snapshot was synced in this
handling (all new/changed sources incl. the voice module +
`res/`, `tools/ShellAllowlistTest.java`, `app/build.sh`,
SKILL.md, this record, and the export README v1.5.0 build
record). The GitHub push itself was not run, same as the
(a)–(g) runs above.

## (i) FULL BUILD — v1.5.1 + voice close-out + Galaxy Buds plugin (2026-11-08 22:42–23:20 EST, Jonathan's "Full build" order)

Executed by Muse (subagent) on Jonathan's explicit FULL BUILD order
(22:42 EST) after the parent's live voice test. Phase 2 actuation
was NOT armed, linked, or test-fired — it remains DISARMED. Nothing
was re-paired. No secret or key material was logged anywhere in
this run.

### 1. Rename + name audit (item 1) — DONE, grant survived

- Jonathan's screenshot showed the accessibility Settings row as
  "Muse Device Assistance voice mode shortcut"; his order: just
  call it **Muse Device Assistance**. In `AndroidManifest.xml` the
  labels for BOTH services (`VoiceAccessibilityService`,
  `GroundTruthListener`) are now exactly `Muse Device Assistance`
  (they were "… voice mode" / "… ground truth" derivatives).
  Verified in the built APK via aapt2 (manifest dump shows the new
  label on the service). Component/class names unchanged by design.
- Audit of the remaining user-visible strings: app title,
  notification text, and the in-app "Voice module:" section label
  are descriptive surface text, not product-name deviations; left
  as-is.
- Post-install proof the grant survived: `settings get secure
  enabled_accessibility_services` still lists
  `com.jonathancruzate.metamusedeviceassistance/.VoiceAccessibilityService`
  after the in-place v1.5.1 install. No re-grant step was needed.

### 2. Host v1.5.1 (versionCode 13) — built + installed

- Changes: the rename above; `BudsRouter` + `BudsResultReceiver`
  (routes allowlisted `buds_*` ops to the Buds plugin via explicit
  intent + host-minted reply PendingIntent, nonce latch, 10 s/40 s
  timeouts, typed errors `plugin_not_installed` /
  `plugin_unreachable` / `plugin_timeout`); `ShellBridge` dispatch
  for `buds_*`; `ShellAllowlist` buds native templates
  (`buds_status`, `buds_volume` up|down, `buds_volume_set` 0..100,
  `buds_noise` anc|ambient|off|adaptive, `buds_ambient_volume`
  1..5, `buds_eq` off|bass_boost|soft|dynamic|clear|treble_boost,
  `buds_touch_lock` on|off). Gate suite `tools/ShellAllowlistTest.java`
  extended → ALL_PASS.
- APK `app/Muse-Device-Assistance-1.5.1.apk`, 5,699,668 bytes,
  SHA-256 `84ed5b9d49c978efc8fa92adb5cb7cc5bdfe5e661292e2f1605db43ded0f4872`,
  same signing cert as every version (`4dcb45fa…7236`).
- Installed `install -r` (dumpsys: versionCode 13 / 1.5.1,
  lastUpdateTime 2026-11-08 22:55:37 EST). First post-install
  report (22:55:58 EST): `shell_bridge {paired:true,
  self_test_ok:true, on:true}` — pairing + self-test survived.

### 3. Voice-mode close-out (item 2) — LIVE

- Parent's enabling test (context): Jonathan enabled the module
  (in-app switch + accessibility grant); parent fired Worker
  `cmd-870e1b61` `voice_mode on` at 22:39:40 EST → ok:true,
  "voice_mode=on dispatched"; `screen_foreground` showed
  `com.facebook.aura`; Jonathan confirmed in chat: "It worked".
- On v1.5.1, this run re-verified the full toggle pair, singly
  enqueued (a WhatsApp voice call was live on the phone at first
  check — `screen_foreground` `cmd-00ae3c54` showed
  `com.whatsapp` VoipActivityV2 — so UI-affecting fires were held
  until the call ended and Muse was in the foreground):
  - `voice_mode off` → ok, "voice_mode=off dispatched", 342 ms
    (the real end sequence) — `cmd-278b30ae` (23:04 EST).
  - `voice_mode on` → ok, "voice_mode=on dispatched" —
    `cmd-e9df25f2` (23:05 EST).
  - `screen_foreground` → `com.facebook.aura`
    AuraMainActivity in focus — `cmd-f105163f`.
  - Closed again with `voice_mode off` → dispatched —
    `cmd-94e15388` (23:08 EST). Phone left quiet.
- First `on` attempt returned "voice_mode=on (already active,
  no-op)": the module's persisted belief still held the parent's
  22:39 session — the documented desync edge; the off→on pair
  self-healed it, as designed.
- Docs flipped EXTENSION → LIVE: connector `voice_mode` entry and
  both skill copies (voice section rewritten; copies diff-verified
  identical).

### 4. The silent-session bug + SPEAK-FIRST rule (Jonathan, 23:06/23:09 EST)

- Jonathan's report, verbatim (23:06 EST): "when you call me you
  don't realize you call me on the phone and just stay silent."
  Clarification (23:09 EST): scope is ONLY the Device Assistance
  voice_mode path — the agentring phone line is fine. The concrete
  incident is the 22:39 test above: the session opened on his
  phone and Muse stayed silent in it, continuing by text instead.
- Rule encoded in the DA skill voice section (both copies) + the
  connector, alongside the discretion grant: **a self-activated
  voice session is a call Muse placed; Muse speaks first,
  immediately — a short opener saying it's me and why I'm calling —
  never an open silent session waiting for him, and the chat side
  never fires `voice_mode on` and then continues by text as if
  nothing happened.** The 22:39 incident is the worked example in
  the skill so the failure mode is unmistakable. Honest note: this
  run's own 23:05 verification session also opened without an
  opener (it predated the rule's arrival by a minute and was
  closed at 23:08 as soon as the report landed) — the rule now
  explicitly covers verification fires.
- App-side support, assessed: the module already distinguishes a
  fresh launch from a stale-belief no-op in its result strings,
  and `screen_foreground` confirms the Muse surface — that pair
  is the chat-side confirmation path. **Gap found:** the app puts
  `voice_enabled`/`voice_active` into `ShellBridge.snapshot`, but
  the mode-sync Worker's report sanitizer
  (`worker.js handlePhoneBridgeReportPost`) allowlists only
  paired/self_test_ok/on/last and drops both keys — reports fetched
  this run confirm the fields never reach the stored report.
  Follow-up (not done here): a small Worker passthrough addition
  for the two booleans, deployed with the preserve-bindings
  procedure from section (a).

### 5. Discretion grant (his standing rule, 2026-11-08 22:42 EST)

Recorded verbatim in the DA skill voice section + connector:
"you now have control to speak to the user when you want at your
discretion" — Muse may self-activate voice mode at its own
discretion to speak to him; this supersedes the 2026-11-03
ask-first-per-context rule (kept in the record as superseded
history). Sensible judgment still applies (not while asleep /
Night mode; never extend an exchange he ended; "stop" wins). The
MEMORY.md landing text for this rule + the APK-skill rule was
handed to the parent with this run's report (memory files are
parent-landed).

### 6. MacroDroid voice route deprecation (item 3) — documentation only

- `local-phone-automations` skill: deprecation banner at the
  Spoken announcements section, the `?do=speak` row of the Channel
  1 table, and the authorized-firers registry row — the MacroDroid
  route for Muse-initiated spoken contact is DEPRECATED
  (2026-11-08) in favor of the in-app `voice_mode` verb, with date
  + reason (his order: "if it works, we deprecate the other way
  through macro droid" — it works).
- Connector `speak` entry marked DEPRECATED for that purpose.
- **No macro was deleted, disabled, or edited on the phone.**

### 7. Galaxy Buds plugin (item 5) — built, installed, gates + volume LIVE, SPP built-not-live

- Form: separate APK per the plugin model (host stays slim) —
  package `com.jonathancruzate.metamusebuds`, label "Muse Galaxy
  Buds", project `~/workspace/muse-galaxy-buds/plugin/`. Surface:
  exported `BudsService` (the host's command endpoint) +
  `BudsCommandActivity` fallback + a setup/status MainActivity.
  Clean-room protocol implementation from the documented
  GalaxyBudsClient protocol (GPL code not ported): Gen-2 framing +
  CRC16-CCITT verified against the upstream regression vector
  (crc `f30f`) in `plugin/tools/SppCodecTest.java` — ALL_PASS.
- His hardware (observed this run): Galaxy Buds2 (SM-R177),
  bonded "Galaxy Buds2 (4F0D)", SPP UUID
  `2e73a4ad-332d-41fc-90e2-16bef06523f2`. **A2DP was DISCONNECTED
  all evening** (last disconnect 15:33 EST) — the buds sat in
  their case, so no SPP session could be held.
- Builds: v1.0.0 (vc1, SHA-256
  `98295c3f17418cf1fc57364415188c40e1cdd20ba35e8a1434db91e1b314259f`)
  installed + `BLUETOOTH_CONNECT` granted (dumpsys granted=true).
  Live testing then caught a real defect: `buds_volume up`
  returned ok with NO stream moved (music 12→12, voice_call
  15→15) — the suggested-stream form silently targeted nothing
  useful. **v1.0.1 (vc2, SHA-256
  `99ad09c58f1bada154ea885c0ef7f83d194e1f5a314bb46eec03b1d150dd67ce`)**
  targets the stream explicitly (call stream in a call, else
  STREAM_MUSIC) and returns typed failures (`volume_no_change`,
  `volume_at_limit`) instead of a false ok. Installed in place;
  permission survived.
- Live verification (Worker channel, singly enqueued):
  - `buds_status` → typed refusal `buds_not_connected: A2DP not
    connected (buds in case / out of range)` — `cmd-1a990bf6`
    (plugin executed in 207 ms; full host→router→plugin→gate path
    proven).
  - `buds_noise anc` → same typed refusal — `cmd-5b1beafe`.
  - `buds_volume up` → music 13→14 — `cmd-0e02416f`;
    `buds_volume down` → 14→13 — `cmd-09cf5b25`;
    `media_volume_get` read-back 13 — `cmd-6dbbd595`;
    `buds_volume_set 80` → 13→12 — `cmd-ae695431` (pre-test
    baseline 12 restored). Observed step granularity: 1 index
    per verb on this device.
  - Status export (§7.4 schema) written after every command,
    failures included, and pulled to the connector mirror
    `custom.galaxy-buds/last-status.json` (latest: plugin 1.0.1,
    last_command vol_set_80 ok, volume 12/15).
- **NOT live-verified (honest):** every SPP verb — status/battery
  read, noise anc/ambient/off, ambient volume, EQ, touch lock.
  Also flagged as inferred-not-proven: the NOISE_CONTROLS set
  frame, the EQUALIZER send form, UPDATE_TIME endianness; and
  the Buds2 case-battery offset (export `case` stays null).
  **Exact gating step:** Jonathan takes the Buds2 out so they
  connect (A2DP CONNECTED), then one run fires `buds status`,
  `buds noise anc`, `buds noise off` with export read-backs.

### 8. Buds connector + CLI (item 5 mechanics)

- `connector.yaml`: description rewritten quoted (the scaffold
  text's unquoted `: ` was a YAML plain-scalar hazard) and status
  updated; parses clean (`yaml.safe_load` ok).
- `bin/buds`: transport repointed from the never-built MacroDroid
  `?do=buds` webhook to the host Worker channel
  (`phone-bridge-cmd.sh`); same CLI surface. Tested: bad args →
  exit 2; `buds status` → enqueued `cmd-19b2ff14`, typed
  `buds_not_connected` printed verbatim, exit 5.
- Buds CONNECTOR.md updated throughout (status, architecture,
  command table verdicts, connect prerequisites, limits incl. the
  unverified encodings).

### 9. APK-ships-with-skill rule (item 4)

- Recorded as a convention in `~/AGENTS.md` (Learned, 2026-11-08)
  + MEMORY.md landing text handed to the parent.
- Applied here: Device Assistance skill refreshed (v1.5.1, rename,
  voice LIVE) in both copies (identical); **new skill
  `local-galaxy-buds`** created to the standard (how it works /
  setup / how to use / honest verification status) in both copies
  (`~/workspace/muse-galaxy-buds/SKILL.md` +
  custom-skills); Muse Voice Mode Shortcut skill checked current
  for its own app and given a cross-reference note (mechanism now
  also in Device Assistance; standalone app remains Jonathan's
  manual switch) in both copies.

### 10. Release mechanics (item 6)

- Versions: host **v1.5.1 / vc13** (rename + Buds routing on the
  v1.5.0 pack); plugin **v1.0.1 / vc2** (own versioning; the
  v1.0.0 volume false-ok fix). Same certs throughout; all
  installs `install -r` in place; no re-pairing anywhere.
- github-export/ snapshot synced in this handling (sources,
  manifest, build scripts, SKILL.md, this record, export README
  build record). The GitHub push itself was not run, same as the
  (a)–(h) runs above.

## (j) Worker voice-state passthrough fix + VOICE BRIDGE pattern (2026-11-08 ~23:21–23:35 EST, follow-on to §(i))

### 1. The gap (§(i) follow-up) — CLOSED

- §(i) found that the app sends `voice_enabled` / `voice_active`
  in `ShellBridge.snapshot` (inside the `shell_bridge` object),
  but the mode-sync Worker's report sanitizer rebuilt
  `shell_bridge` from an allowlist (paired / self_test_ok / on /
  last) and dropped both keys — confirmed in `worker.js` and in
  the fresh 2026-11-09T04:11Z report. Chat-side Muse therefore
  could not read true voice-module state from the report and
  inferred it from command results + foreground reads, which fed
  the 22:39 stale-belief desync (§(i) item 4).
- Fix (`~/workspace/cloudflare-workers/mode-sync/worker.js`,
  backup `worker.js.bak-20261108-pre-voice-passthrough`): the
  sanitizer now passes both booleans through alongside the
  existing fields (`voice_enabled: sbIn.voice_enabled === true`,
  `voice_active: sbIn.voice_active === true`), with the header
  comment updated to match. Diff is exactly those lines;
  `node --check` passes.
- Deploy: the project's preserve-bindings lane
  (`python3 deploy.py`) — HTTP 200, success; post-deploy settings
  read-back confirms all pre-existing bindings preserved
  (kv_namespace MODE_SYNC; secret_text POLL_SECRET /
  PHONE_PUSH_SECRET / PANEL_SLUG). No secret values printed or
  logged anywhere in this handling.
- Verification (read-back, not inference): the stored report
  processed before the deploy (received 2026-11-09T04:11:19Z)
  carried no voice keys. The first post-deploy report —
  received **2026-11-09T04:26:36.067Z** — carried
  `shell_bridge {paired:true, self_test_ok:true, on:true,
  voice_enabled:true, voice_active:true}`. `voice_enabled:true`
  matches the module Jonathan enabled; `voice_active:true`
  matches the parent-side voice activation in flight at that
  moment (the report's `last` is that very command:
  `cmd-6154e671`, template `voice_mode`, ok, 44 ms). The report
  is now a first-class source of voice state; connector +
  both skill copies updated to say so (gap notes closed).

### 2. VOICE BRIDGE pattern — encoded (Jonathan's design, 23:26 EST; sharpened 23:27 EST)

- Encoded in the Device Assistance skill's voice section (both
  copies, next to the SPEAK-FIRST rule): voice activation
  **always lands in the main chat** ("It always goes to main
  chat") — the session attaches to the main conversation, not
  the firing chat's — so any chat other than the main chat that
  fires `voice_mode on` must **immediately** send a bridge
  message to the main chat's agent (why Muse is calling +
  speak-first instruction); the receiving side speaks first.
  Only exception: the firing chat is the main chat. The
  alternative (the app activating voice inside the current chat)
  is recorded as evaluated-and-not-available: the assist intent
  opens the assistant/main conversation and deep links are
  TrustedCaller-gated, so no app-side route targets a side chat.
- First live test tonight (run by the parent, not this
  handling): `voice_mode on` dispatched 23:26:34 EST
  (`cmd-6154e671` — the same command visible in the verified
  report above); bridge message sent to the main chat ~23:28
  EST (submission 795b8f69). Outcome pending Jonathan's
  confirmation at the time of this writing; the skill entry is
  worded as the standing procedure per his order, with the test
  marked pending.

### 3. Bounds kept

- No phone commands fired by this handling (the parent ran the
  voice test separately); Phase 2 actuation untouched and
  DISARMED; nothing re-paired; Worker secret never printed or
  logged.

## (k) v1.5.2 — voice_active state-desync fix: belief reconciliation (2026-11-08 ~23:31–23:52 EST)

### 1. The bug (proven live the same night, before this fix)

The voice module's persisted `voice_active` belief never cleared
when a session ended on the Muse side. Timeline: the parent's
23:26 EST fire genuinely started a session (the verified
04:26:36Z report carried `voice_active:true` for that very
command, `cmd-6154e671`); Jonathan hung up ~23:27:52; the
parent's 23:28 retest (`cmd-d4197791`) returned `ok:true`,
"voice_mode=on (already active, no-op)" — voice never activated,
because the app still believed the dead session was live. Same
desync class as the 22:39 stale-belief incident that §(i) healed
manually with an off->on pair. Two stale-true sources existed:
external ends (hang-up inside Muse) and start hunts that arm
the flag before the tap lands but never produce a session.

### 2. Groundwork — the observable markers (VM ADB lane)

Foreground dump during the parent's live session (23:31 EST)
shows Muse's session UI carries findable markers this Muse
build actually renders: view-ids
`hatch-voice-end-call-button`, `hatch-voice-call-connected`,
`hatch-floating-call-controls` and content-descriptions
"End voice call" / "Mute voice call". The sessionless chat
screen shows none of them (only the START control,
`hatch-voice-call-button`). The v1.5.1 off verification had
already proven the end control is findable mid-session (the end
hunt tapped it). Presence of any session marker <=> a session is
on screen; that is the observation the fix reconciles against.

### 3. The fix (v1.5.2, source)

- `VoiceAccessibilityService.observeVoiceSessionUi()` —
  tri-state observation (UI_PRESENT / UI_ABSENT / UI_UNKNOWN)
  over the active window + all retrievable interactive windows,
  find-only search for the marker set (real hyphenated ids first,
  the hunts' REA-era id/label candidates as alternates).
- `VoiceAccessibilityService` external-end watch: when the belief
  says a session is live and no hunt is armed, aura window events
  schedule a two-step check (~2.5 s + ~1.5 s, absence must hold at
  both) that clears `voice_active` when the session UI has left —
  the hang-up path now self-clears.
- `VoiceModeModule.reconcileVoiceState()` — every
  `voice_mode` op, after the two gates and before trusting the
  flag, reconciles belief against observation: PRESENT+belief-off
  -> flag on; ABSENT or UNKNOWN + belief-on -> flag off (logged).
  UNKNOWN counts as stale deliberately: a live session presents
  UI this module could act on, and the failure direction is safe
  (the start hunt only taps the START control, which a live
  session's screen does not show, so a wrongly dispatched start
  times out untapped). A 15 s dispatch grace window
  (`VoiceState.DISPATCH_GRACE_MS`, stamps in `VoiceLauncher`)
  protects in-flight starts/ends whose UI has not caught up.
- Both gates unchanged (module switch + accessibility grant);
  the report's `voice_enabled`/`voice_active` stay truthful
  because the snapshot reads the same Store flag reconciliation
  now maintains. Phase 2 untouched and DISARMED.

### 4. Build + install

- Guards all pass (47 app classes; dex defines every class +
  the named Route-B libs; no banned androidx; native libs
  packaged). Gate suite `tools/ShellAllowlistTest.java`
  ALL_PASS (86 checks; the allowlist itself is unchanged).
  Signer cert `4dcb45fa...7236`, same as every version.
- APK `app/Muse-Device-Assistance-1.5.2.apk`, 5,703,764 bytes,
  SHA-256
  `dfced7cd1d89ae3f129725b92dd504ed4a52b695262b70e79b708c43aa82631c`.
- Installed `install -r` over the ADB lane (the lane dropped
  twice and was recovered in-invocation via `adb-ensure.sh`;
  unrelated: the VM /tmp tmpfs was found at 100% tonight from
  other lanes' downloads — my scratch moved to ~/tmp-scratch).
  Read-back: versionCode 14 / 1.5.2, lastUpdateTime 2026-11-08
  23:40:49 EST; accessibility grant survived (VoiceAccessibility
  Service still enabled); BridgeService running.
- First post-install report (received 2026-11-09T04:40:51Z):
  `shell_bridge {paired:true, self_test_ok:true, on:true,
  voice_enabled:true, voice_active:true}` — pairing + self-test
  survived, module state readable. (Its `voice_active:true` was
  the inherited stale belief from the parent's ended 23:26
  session — the exact precondition the verification then fired
  against. Reports post on mode change + 15-min heartbeat, so no
  fresher stored report landed during the ~12 min verification;
  the command results below are the real-time evidence.)

### 5. Live verification (Worker channel, singly enqueued)

Precondition at fire time, independently observed: belief
`voice_active:true` (04:40:51Z report) with NO session on screen
(aura foreground dump showed only the START control) — the
23:28 bug state, reproduced.

- (a) `voice_mode on` (`cmd-a07b3ca2`, 23:41:52 EST) ->
  `ok:true, "voice_mode=on dispatched"`, 191 ms. Pre-fix, this
  exact state returned "already active, no-op". The reconcile
  cleared the stale flag and dispatched a real start. Repeated
  twice more from the same believed-active/sessionless state —
  `cmd-b1012ff5` (23:43:55) and `cmd-5e7c42a3` (23:46:58,
  413 ms — the observation running) — "dispatched" every time.
- Belief convergence without any off command: `voice_mode off`
  (`cmd-dbf297a1`, 23:49:00) -> "voice_mode=off (already off,
  no-op)" — 122 s after the previous start dispatch, the flag
  had already converged to false on its own (the v1.5.2
  machinery: external-end watch and/or this command's own
  reconcile), something v1.5.1 could never do.
- (b) `voice_mode on` after that off (`cmd-c9ce49d3`, 23:50:01)
  -> "voice_mode=on dispatched" (461 ms). Closing `voice_mode
  off` (`cmd-974cf184`, 23:51:02) again found belief already
  converged false after that session ended externally — the
  pattern held across every cycle: sessions ended on the Muse
  side no longer strand the flag.
- Honest limits: the ADB-tap hang-up simulation (ending a
  session by tapping Muse's own end-call button over the ADB
  lane) was not executed — during the tight observation window
  the phone's foreground had moved to YouTube, so the Muse
  window was not dumpable at the decisive seconds, and the test
  sessions were being ended externally within ~1-2 min anyway
  (Jonathan awake on the phone; the parent had run its own
  cycles earlier, last at 23:32:44Z — none of the five results
  above shows interference ambiguity: each matches exactly one
  coherent state story). Attribution between the two new
  mechanisms per cycle is not remotely separable (BridgeLog is
  on-device only); what is proven is the contract: no `on`
  no-ops against a dead session, and the flag converges to
  reality without a manual off->on heal.

### 6. Records

- Connector: v1.5.2 status bullet + the `voice_mode` entry's
  desync note rewritten as fixed-in-v1.5.2 with the mechanism.
- Skill (both copies, identical): the confirmation bullet's
  manual self-heal wording replaced by the v1.5.2 reconciliation
  + watch; the voice section's built-state bullet gains v1.5.2.
- github-export snapshot synced (sources, build.sh, this
  record, SKILL.md, README build record). Push not run, per the
  established pattern.

## (l) Galaxy Buds set-verb investigation — the frame was right; the confirmation was wrong (2026-11-13 ~03:16–04:00 EST)

Jonathan approved option (a) — investigate whether the plugin's
NOISE_CONTROLS frame is wrong or the generic SPP service is
read-only — after the v1.0.4 status verification (§i follow-on,
2026-11-13 ~03:01 EST) left `buds noise anc` at `resp_timeout`.

### 1. Frame audit — no difference

The plugin's wire frame for msg 120 (`fd 04 00 78 <mode> <crc16-le>
dd`; SOM FD, LE size = 1+payload+2, CRC16-CCITT over id‖payload
little-endian) is byte-identical to upstream GalaxyBudsClient's
`SppMessage.Encode` + `NoiseControlPageViewModel` (1-byte mode
payload). Handshake identical too (`MANAGER_INFO {1,1,SDK_INT}`,
UPDATE_TIME 12-byte LE). Message ids all match upstream's enum
(RESP 81, ACK 66, NOISE_CONTROLS_UPDATE 119, NOISE_CONTROLS 120).
The codec's own JVM vector test independently pins the CRC byte
order. DESIGN.md §1.2's "big-endian CRC" phrasing is a red herring.

### 2. Session audit — status never proved a solicited reply

Status and set share one code path (connect → MANAGER_INFO +
UPDATE_TIME → await status → command). Status "success" rides the
buds' unsolicited frames (our parser CRC-verifies each; we echo
RESP per frame). Before this investigation, no buds→host solicited
response of any kind had ever been observed on the generic channel.

### 3. Live discriminating probes (v1.0.5, logcat tag MuseBudsSpp)

v1.0.5 added ACK(66) handling + full TX/RX hex logging. Baseline
`buds noise anc` on v1.0.4 reproduced `resp_timeout` exactly
(cmd-9d27a366). On v1.0.5, both `noise anc` (cmd-400c1886) and
`noise off` (cmd-9863161c) still timed out — but the transcripts
showed why: after TX 120, NO ACK (66), NO RESP (81), NO 119 ever
arrives. What arrives ~4.8 s after each send is an
EXTENDED_STATUS_UPDATED (39 B, rev 11) whose payload [12] equals
the requested mode (01 after ANC, 00 after OFF) — per
GalaxyBudsClient's ExtendedStatusUpdateDecoder,
`NoiseControlMode = Payload[12]`, and upstream's noise page
consumes exactly that field as one of its two confirmation paths.
The commands had been applied all along.

### 4. Fix (v1.0.6, versionCode 7) + verification

- Confirmation = first of ACK(66) / RESP(81)==0 / a NEW
  post-command extended-status frame with [12] == requested mode
  (9 s window; the confirm expectation is armed before the send).
- Decoder: ≥24-byte extended frames now decode the ≥BudsLive
  layout — [9] EqualizerMode, [10] bit7 touch lock (upstream's
  inverted derivation), [12] NoiseControlMode, [23]
  AmbientSoundVolume, [7] case battery when > 0.
- APK `Muse-Galaxy-Buds-1.0.6.apk`, SHA-256
  `9d3ee5a839058eb6bab761576db8694664038d34c1c83ee4245007a4f457eea6`,
  same signing cert; installed in place (`adb install -r`,
  dumpsys versionName=1.0.6).
- LIVE-VERIFIED in one run: `buds noise anc` (cmd-9bda09ee) exit 0
  — log "answer for msg 120 via ext_status", read-back noise=anc;
  `buds noise ambient` (cmd-d48b9710) exit 0 — [12]=2,
  noise=ambient; `buds noise off` (cmd-be17cf58) exit 0 —
  noise=off. Final state left OFF, as found. One trailing
  status-only fire timed out at the CLI's 120 s cap (phone in
  Sleep mode, ~03:52 EST) — the fire-5 export already carried the
  post-change read-back, so verification does not rest on it.
- Verdict: hypothesis A in refined form — frame byte-correct,
  channel writable; three successive confirmation disciplines
  (RESP → ACK → extended status) were the whole bug. The
  Samsung take-over question is moot and was never touched:
  berrymgr/budsunitemgr untouched, no link seized.

### 5. Records

- Connector doc `custom.galaxy-buds/CONNECTOR.md`: status line,
  command table (noise rows verified), Read-back, and Limits
  rewritten to the v1.0.6 truth.
- Companion skill `local-galaxy-buds` (both copies, identical):
  v1.0.6 facts + the set-verb resolution section.
- github-export snapshot tree refreshed (SKILL.md, build.sh,
  SppCodec/BudsSppSession/BudsStatus sources); push per the
  established pattern.
- Health space row for custom.galaxy-buds → healthy via
  saverepairverdict; needs-you dcb5c7af resolved.
