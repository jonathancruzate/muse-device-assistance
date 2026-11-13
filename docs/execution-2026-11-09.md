# Execution record — 2026-11-09

## v1.10.0 — voice-mode PiP confirmation sequence (build 2026-11-09 ~23:42–23:55 EST; install BLOCKED, see §4)

### 1. Version resolution (read first)

The dispatch brief named this build "v1.5.3 / versionCode 15, next
after v1.5.2/vc14". That premise was stale by the time the build ran:
the project's live line had already advanced past it the same day —
v1.6.0/vc15 (push channels), v1.7.0/vc16 (observers), v1.8.0/vc17
(Phase 2 expansion), **v1.9.0/vc18 installed on the phone**
(2026-11-10 ~00:49 UTC, transport cut; `app/build.sh` at
VERSION_CODE=18 / 1.9.0 on entry). A "1.5.3/vc15" APK would be a
downgrade Android refuses to install in place. The brief's intent —
the next version after the current line, carrying the v1.5.2 voice
work forward, same package, same family cert, in-place `-r` only —
was therefore executed as **v1.10.0 / versionCode 19**. Everything
below is under that number.

### 2. Spec implemented

Source investigation: `docs/pip-investigation-2026-11-09.md`
(REA/APKMirror lane; the Muse APK binary itself was not obtainable
from this VM, so the dossier leaned "PiP unlikely"). Two owner
corrections then superseded the dossier's lean and reshaped the
sequence:

- **Correction (Jonathan, 2026-11-09 23:40 EST):** "The app always
  goes into PiP if voice mode is on" — aura's voice session DOES
  auto-PiP on user-leave. Ground truth from the device owner.
- **Refinement (Jonathan, 2026-11-09 23:41 EST):** his choreography —
  "Open app, turn on voice mode, and go back to the last app that was
  open in the recents" — so the session floats over the app he was
  using instead of dumping him on the launcher.

Implemented in `VoiceAccessibilityService` (the PiP confirmation
sequence), `VoiceLauncher`, `VoiceState` — voice module only:

1. **Record (pre-launch).** `VoiceLauncher.launchVoice` first asks
   the accessibility service for the current foreground package
   (`currentForegroundPackage()`), skipping aura itself, our own
   package, and the launcher (resolved HOME package + known-launcher
   list); the result is kept in `VoiceState.returnTargetPkg`
   (in-memory, one activation flow). Cleared by `endVoice`.
2. **Wait + settle.** Only on self-activated sessions (armed by the
   START hunt's successful tap — the only path that schedules it):
   poll `observeVoiceSessionUi()` until UI_PRESENT (≤ ~10 s), then
   settle 900 ms (dossier range 600–1200 ms).
3. **Inventory (once per session).** Full accessibility node dump of
   the Muse voice window(s) to BridgeLog (`PiP node d=… id=… class=…
   text=… desc=… clickable=…`, capped 400 nodes) — confirms/strikes
   the dossier's Q3 minimize candidates from live evidence.
4. **Return (the PiP trigger).** Relaunch the recorded package via
   its launch intent — leaving aura is the user-leave that makes
   the session auto-PiP over his app. No recorded package (or the
   relaunch throws) → one `GLOBAL_ACTION_HOME` (equivalent landing).
   Every branch BridgeLog'd (`PiP return: …`).
5. **Verify.** ~1.2 s later: log `pipWindowObserved` (an aura window
   at ≤ 65% of display width and height), the foreground package
   after the return, and the session-UI observation (`PiP verify:`).
6. **Fallback only.** If no PiP window is observed, the dossier's
   minimize-control hunt runs (view-ids `hatch-voice-minimize-button`
   / `hatch-voice-pip-button` / `hatch-minimize-button` /
   `hatch-voice-collapse-button` / `minimize_button` / `pip_button`,
   then labels "Minimize" / "Picture in picture" / "Collapse" /
   "Shrink" / "Floating window") — at most one tap — followed by a
   `PiP final:` re-observation. All branches log their outcome.

Untouched, per the brief: speak-first / voice-bridge behavior, the
v1.5.2 belief reconciler and external-end watch, the pairing, both
accessibility services' "Muse Device Assistance" labels, and the
entire Phase 2 actuation surface (disarmed, unmodified — no
actuation file was edited; the diff is exactly three voice files).

### 3. Build + verification (VM side)

- `app/build.sh`: VERSION_CODE=19, VERSION_NAME="1.10.0".
- Build clean (build's own dex guards + native-lib guards passed).
- APK `app/Muse-Device-Assistance-1.10.0.apk`, **5,724,244 bytes**,
  **SHA-256 `4488e68a27995063fec1006c85a87307f7e511835b4ed1473c90b7675d50504d`**.
- aapt2 badging: package `com.jonathancruzate.metamusedeviceassistance`,
  versionCode **19** / versionName **1.10.0**.
- apksigner: signer cert SHA-256
  `4dcb45fa69b2d82ffad68f877ab491b29d989188b09bcad55f3578906d477236`
  — the family cert, unchanged from v1.5.x–v1.9.0.

### 4. Install — BLOCKED tonight (nothing on the phone changed)

The ADB install lane was unreachable on every known endpoint; no
install was attempted against any wrong target, and no uninstall
was performed or considered:

- The only other connected ADB device (127.0.0.1:5556) was
  positively identified as the **Shield TV** (`SHIELD Android TV` /
  mdarcy) and left alone.
- Relay report (box device-relay): still the 2026-11-09T15:10:58Z
  seed — endpoint `192.168.1.153:37165`; the tunnel proxy closed
  every CONNECT to the phone's tailnet IP on that port.
- Worker report: frozen at `received_at 2026-11-10T00:32:34Z`
  (mode-sync KV write cap exhausted for 2026-11-10) — endpoint
  `192.168.1.153:42671`, the port the v1.9.0 install used; the
  tunnel proxy likewise closed every CONNECT (the v1.9 record
  notes the same refusal since that install).
- A single relay `battery` probe (`cmd-26f0422f-e573-451e-9722-2ac73f9268d5`,
  enqueued singly) stayed `pending` — the app has not yet taken its
  first relay cycle (the v1.9 self-switch is still unobserved), so
  no fresh endpoint exists on either store tonight.

**Unblock:** the app's next wake cycle completing the transport
self-switch (fresh report on the relay with the current endpoint),
or the KV cap reset at 2026-11-11 00:00 UTC restoring Worker
reports — then `adb install -r Muse-Device-Assistance-1.10.0.apk`
over the forwarded endpoint and the read-backs below.

### 5. Read-back state (from the freshest reports, pre-install)

- Relay seed + Worker 00:32Z report agree: `shell_bridge {paired:
  true, self_test_ok: true, on: true, voice_enabled: true,
  voice_active: false}` — pairing + self-test healthy, voice module
  enabled, no session active. Installed version remains
  **v1.9.0 / vc18** (the v1.9 install read-back); accessibility
  grant state cannot be re-read until the ADB lane returns.
- Post-install read-backs still owed: installed version = 1.10.0 /
  vc19, accessibility grant intact (both services), pairing +
  shell self-test ok, `voice_enabled` unchanged (true).

### 6. Status of the feature

**Built-not-yet-live-verified.** The PiP confirmation sequence has
never run on the phone. Live verification is the joint trial with
Jonathan present (runs separately): self-activate a session and
read the BridgeLog branch lines (`PiP return:` / `PiP verify:` /
any `PiP fallback:`) plus his visual confirmation that the session
floats over the app he was using.

### 7. Install retry — 2026-11-10 ~05:04–05:20 UTC (00:04–00:20 EST), STILL BLOCKED

Retried at Jonathan's "Try again" (run-supervisor
`da-v1100-install-retry`). The premise that the KV window had reset
did not hold for the Worker store, and the app has still not taken
its first relay cycle:

- **Worker report: still frozen** at `received_at
  2026-11-10T00:32:34.940Z` — the 2026-11-10 KV write cap was
  exhausted at ~00:38Z by the pre-fix push loop (v1.9 transport-cut
  record), so Worker writes stay dead until the **2026-11-11
  00:00 UTC** reset; the 00:00 UTC reset that did occur (start of
  Nov 10) was the one the loop burned. Endpoint in the frozen
  report: `192.168.1.153:42671`; `shell_bridge` unchanged
  (paired/self-test/on/voice all healthy).
- **Relay report: still the seed** at `received_at
  2026-11-09T15:10:58.422Z` (endpoint `192.168.1.153:37165`),
  re-checked after a 4-minute wait with the phone in active use;
  both queued relay `battery` probes (`cmd-57bc6ed9…`,
  `cmd-26f0422f…`) still `pending` — the transport self-switch is
  still unobserved.
- **Connect attempts:** VM tailnet-forward to the phone's tailnet
  IP `100.88.51.94:42671` → tunnel proxy closed during CONNECT;
  `adb connect` failed (device offline). Cross-check from the box
  over direct tailnet: the phone IS online (`galaxy-s24-t1cx`,
  idle) but TCP to **both** 42671 and 37165 is **connection
  refused** — adbd is not listening on either stale port; the
  wireless-debugging endpoint has rotated and no live store names
  the current one. Max-attempt discipline kept; nothing forced;
  the Shield (127.0.0.1:5556) untouched; no uninstall.
- APK re-verified on disk before the attempt: 5,724,244 B,
  SHA-256 `4488e68a…04d` (matches §3).

**Unblock (unchanged, either leg):** the app's next cycle
completing the self-switch (a fresh relay report names the current
endpoint), or the 2026-11-11 00:00 UTC KV reset restoring Worker
reports — then `install -r` + the §5 read-backs. The connector and
skill v1.10.0 bullets stay at NOT YET INSTALLED.

### 8. Post-wake install attempt — 2026-11-10 ~05:23–05:30 UTC (00:23–00:30 EST), NOT WOKEN

Jonathan opened the app on the phone at ~00:22 EST (05:22Z) at the
parent's request, specifically to wake it into its first relay cycle
(run-supervisor `da-v1100-install-postwake`). The wake did NOT land
on the relay:

- Relay store polled every 45 s from 05:23:57Z to 05:29:12Z, final
  direct read 05:29:22Z: `phonebridge:report` unchanged at
  `received_at 2026-11-09T15:10:58.422Z` (the seed) — no fresh
  report after the app-open.
- All three relay probes still `state: pending`, none executed:
  `cmd-57bc6ed9…` (enqueued 01:08:13Z), `cmd-9735eff1…` (01:04:39Z),
  `cmd-26f0422f…` (04:49:17Z); queue file unchanged.
- No relay store file modified after 2026-11-09 23:49:17 EST (the
  cmd-26f0422f enqueue) — zero app contact with the relay base.
- Per the attempt's stop rule: no ADB dials to stale ports, nothing
  installed; the phone still runs v1.9.0/vc18 and the v1.10.0
  bullets in the connector + skills remain NOT YET INSTALLED.

**Reading:** opening the app's UI did not produce a relay cycle in
the window — either the foreground open doesn't run the desired
fetch/report path, or the cycle needs more settling than ~7 min.
Unblock unchanged: a fresh relay report naming the current endpoint
(app cycle completing the self-switch), or the 2026-11-11 00:00 UTC
KV reset restoring Worker reports — then `install -r` + §5
read-backs.

### 9. Push-500 diagnosis + serverless redesign — 2026-11-10 ~05:35–05:50 UTC (00:35–00:50 EST)

Run-supervisor `da-push500-diagnosis`. Jonathan's screenshot (00:31:14–17
EST) showed the app's BridgeLog flooded with
`[PUSH] phone-notify push FAILED -> HTTP 500 (queued for retry)`
(+ dialer/calendar), many per second.

**500 diagnosis (proven live tonight):**
- The base 500ing is the **mode-sync Worker** — the app is pre-switch
  (relay store still the seed, received_at 2026-11-09T15:10:58.422Z;
  Worker report still frozen at 2026-11-10T00:32:34.940Z).
- Cause: the **account KV daily write cap**, exhausted 2026-11-10
  ~00:38:14Z by the v1.8 self-notification loop (711 puts in ~35 min
  pre-fix). Live proof tonight via the Cloudflare API (this VM's direct
  Worker curls are bot-blocked, CF 1010): KV reads healthy (report key
  + notify queue readable; queue at the 500-item cap, last write
  00:38:14.649Z) while a scratch-key PUT probe returned **429, code
  10048** ("reached the free usage limit … for today"). Every
  KV-putting Worker handler throws → HTTP 500 / error 1101 to the app.
- The bleed-stop fix **is** deployed (repo `worker.js` carries the
  self-package drop + commit-on-change; the cut record's post-deploy
  A/B matches its behavior exactly). It prevents recurrence; it cannot
  refund 2026-11-10. Only cure for the Worker lane is the
  **2026-11-11 00:00 UTC** cap reset — and per Jonathan's steering the
  Worker is being exited, not rehabilitated.
- Mode-state GETs (read-only) still succeed and still carry the
  transport directive — reads were never the problem.

**Why the switch never landed (new root cause, code-proven):**
`app/build.sh` uses `--target-sdk-version 35`; `AndroidManifest.xml`
has **no `usesCleartextTraffic` and no `networkSecurityConfig`** → at
targetSdk ≥ 28 the platform denies cleartext HTTP, so
`WorkerClient.probeModeState` against `http://100.85.53.70:8792`
throws every cycle and the app stays on the Worker base. The relay is
healthy (app-equivalent probe from this VM: HTTP 200, body carries
`desired`, token matches). **v1.10.0 inherits the same manifest — as
built it cannot switch to any `http://100.` base.** A future endpoint
must be https, or a future build must carry a scoped cleartext
exception.

**Endpoint-home reconciliation:** the cutover record put the relay on
the Hetzner box; Jonathan's decision (2026-11-10 00:33 EST) was that
the home is **this VM**. Assessed: this VM has no tailnet interface
(lo + NAT host0 only) and its tailnet tunnel accepts no inbound
connections — the phone can never dial it, so a VM-hosted endpoint is
not viable as a phone target. The 00:35 EST redesign order then
superseded endpoint relocation entirely.

**Redesign (Jonathan 2026-11-10 00:35 EST — "pushed directly to the
Muse app on the phone, no server required"):** full design at
`docs/direct-to-muse-app-design-2026-11-10.md`. Summary: notifications
(and read-side texts/calls/calendar) retire onto the Muse Android
app's **own pairing pipe** — per `~/docs/client-surfaces.md` the
pairing already reads phone notifications/texts/call history and
pushes them to Muse, and the fleet already consumes that stream
(`phone-notify-drain` → `pending_replies.json`; shade state refreshed
2026-11-10 00:00 EST, live while the DA queue sat frozen). DA's
`phone-notify` forwarding is a duplicate of it — and the loop source.
The DA-unique structured lanes (state report incl. the ADB endpoint,
command channel, mode desired/ack) have **no platform carrier and no
local ingest API in the Muse app** (docs list none; Android sandboxing
bars app-to-app writes); their minimal residual is **one small https
rendezvous endpoint** (relay code behind Tailscale Serve is the
cheapest form) — named as a rendezvous, not dressed as serverless.
Future DA build spec'd in the design (delete push forwarding, https or
scoped-cleartext, kill the Worker probation fallback); not built in
this run; no installs attempted.

**Fixes applied:** none to the Worker (steering: exit, not rehab; the
one Worker-side change that could help — re-pointing the directive —
is moot for http bases under the cleartext proof, and the design
supersedes it). No fresh report landed anywhere during this run
(Worker frozen 00:32:34.940Z; relay seed; relay probes still pending).
**Unblocks, fastest first:** (1) Jonathan flips DA's Notifications
channel off in-app → the flood/cap class dies immediately;
(2) 2026-11-11 00:00 UTC reset → the current build resumes reporting
to the Worker (still cannot switch to an http base); (3) the design's
future build + https residual endpoint → the phone leaves Cloudflare
for good, then the v1.10.0 install lane heals off the fresh report.

## §10 — v1.11.0 built under the corrected spec (2026-11-10)

**Job 1 — toggle verified:** after Jonathan turned DA's Notifications
channel OFF (05:40Z), Worker device-push queues read twice were
byte-identical (notify 500 @ max 2026-11-10T00:38:14.649Z; dialer 6;
calendar 1) — the flood's observable footprint stopped. KV writes stay
cap-blocked (429/10048) until 2026-11-11T00:00Z; the post-reset read
is the decisive confirmation.

**Premise check — the design's duplication claim is REFUTED.**
device-push-drain (sole notify intake since 2026-11-09; the old
phone-notify-drain was deleted that day) last ledgered any DA item
2026-11-09T17:41:28Z and its notify staging holds 0 items;
pending_replies' newest item is 2026-11-09T15:05:56Z from the deleted
drain; the Muse pairing catalog is pull-based and feeds no
notification ledger. No DA-independent event-driven notification
stream exists tonight — Jonathan's premise held, and the build
branch changed accordingly (channels KEPT).

**Build:** agy was ordered and attempted — frontier ladder exhausted,
proven cause RESOURCE_EXHAUSTED 429 (resets ~95h); executed on Muse
under the matrix re-selection rule. v1.11.0/vc20 shipped the loop fix
(ignore always-running notifications + self-package exclusion) and
the local-first PushClient transport (tier 1 on-device outbox =
delivery; tier 2 mirror only to https://*.ts.net bases; data channels
no longer POST to the Worker). Full record:
docs/v1.11-build-report.md. Tier 2's Serve front is blocked on one
sudo command on the box (policy-refused in the parent session);
Worker directive repoint held until it answers. APK 5,724,244 B,
SHA-256 89cee61a…1f82, family cert unchanged. NOT installed.
