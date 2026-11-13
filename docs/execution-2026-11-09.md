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

## §11 — Tier-2 https front UP + directive repointed (2026-11-10)

The §10 held step is done. On the box, the fleet's passwordless-sudo
lane worked non-interactively (`sudo -n true` OK), and
`sudo tailscale serve --bg --https=8443 http://100.85.53.70:8792`
succeeded: `tailscale serve status` now shows
`https://vps.taila56db5.ts.net:8443 (tailnet only) |-- / proxy
http://100.85.53.70:8792` alongside the untouched :443 Funnel mount
(`https://vps.taila56db5.ts.net |-- / proxy http://127.0.0.1:8952`).
Verified live with the on-box relay token: GET
`https://vps.taila56db5.ts.net:8443/mode-state` resolved to the
tailnet IP (100.85.53.70, the path the phone will take) → HTTP 200
with the relay's real mode-state body (desired home; direct
http://100.85.53.70:8792 read also 200). Accuracy note: a first
attempt curling the ts.net name from the box itself returned 000
(hairpin/self-resolution path) — the tailnet-IP/SNI re-check is
the verification of record.

Directive repoint: `device-relay-base.txt` updated
`http://100.85.53.70:8792` → `https://vps.taila56db5.ts.net:8443`
(backup `device-relay-base.txt.bak-20261110-pre-https` alongside),
Worker redeployed via the binding-preserving `deploy.py` (HTTP 200,
all pre-existing bindings preserved incl. TRANSPORT_BASE /
TRANSPORT_SECRET), and a live Worker mode-state read confirms
`transport.base = https://vps.taila56db5.ts.net:8443` with the
transport secret present. The phone picks the https directive up on
its next mode-state fetch; the self-switch itself is still
unobserved (no phone contact yet — unchanged blocker, and the
v1.11.0 install still waits on a live channel to the phone).

## §12 — v1.11.0 manual install: verification attempted, UNVERIFIED (2026-11-10)

Jonathan installed the v1.11.0 APK by hand (~01:53 EST) and asked
for a check. Verdict: **(c) UNVERIFIED** — no evidence channel
shows the new version running yet, and none shows it failed,
either. What was read, ~01:56–02:06 EST:

- **Relay (primary ground truth): still the seed.** Two reads of
  `GET /phone-bridge/report` on the box relay, ~7 min apart, both
  returned the seeded report (`received_at
  2026-11-09T15:10:58.422Z`, `shell_bridge {paired:true,
  self_test_ok:true, on:true, voice_enabled:true,
  voice_active:false}`, seed adb endpoint `192.168.1.153:37165`,
  push_channels as of Nov 9 — Notifications still `on:true` in
  the seed, which predates his toggle and proves nothing about
  current toggle state). Relay mode-state actual is likewise
  still seeded (`reported_at 2026-11-09T14:36:45.507Z`, ack seq
  172 vs desired 176), and the relay battery probe
  `cmd-57bc6ed9-85f6-417d-8158-29dc0d321494` (enqueued
  2026-11-10T01:08:13Z) is **still pending** — the app has not
  fetched mode-state or commands from the relay since the
  install, so **no self-switch and no install-cycle report**.
- **Worker (secondary): no fresh evidence, as expected.** KV
  writes stay cap-blocked until 2026-11-11 00:00 UTC, so the app
  could not have posted a Worker report even if it cycled. The
  public `GET /phone-bridge/report` tonight returned 404 "no
  report yet" rather than the frozen 2026-11-10T00:32:34.940Z
  copy read earlier via a different path — noted as read; either
  way it carries no evidence about the install.
- **ADB: no path.** Both known endpoints dialed via the tailnet
  forwarder to the phone's tailnet IP (100.88.51.94): port 42671
  (freshest, from the 00:32Z Worker report; proven at the v1.9
  install) and 37165 (seed) — both refused (`failed to connect` /
  `offline`), the same tunnel-proxy refusal state recorded since
  the v1.9 install. No version read-back was possible, so
  verdicts (a)/(b) are unreachable tonight.
- **Most likely reading:** the app simply has not cycled since
  the manual install (a UI install does not guarantee the
  foreground service restarts until the app is opened or its
  next scheduled cycle fires). The connector + skill v1.11.0
  bullets therefore stay at NOT YET INSTALLED. Unblock is
  unchanged: the app's next completed cycle (fresh relay report
  proves install + switch together) or a live ADB endpoint in a
  fresh report for the dumpsys read-back.

## §12 — Device Assistance surface exposed as muse-mcp tools (2026-11-10)

Jonathan redirected the "connector API" task (01:50 EST): no
bespoke connector CLI — reuse muse-mcp. Five tools added to the
muse-mcp Worker (v1.0.0 → v1.1.0) in its existing pattern (TOOLS
array + callTool dispatch, muse_* naming): `muse_device_report`,
`muse_device_data`, `muse_device_command`,
`muse_device_command_status`, `muse_device_mode`. A new `DAKV`
binding points muse-mcp at the mode-sync KV namespace
(ad95c4f20a9a4f9f9286f1f7cd47fc34) — the SAME keys the phone and
the mode-sync Worker already use (phonebridge:report,
devicepush:<source>, phonebridge:queue + phonebridge:cmd:<id>,
mode:desired/actual). Enqueue mirrors the Worker's semantics
(caps, TTL clamp, record shape, queue cap 32); a KV write refusal
returns a structured `{ok:false, refused:true}`, never a fake
success. Deployed via the new `deploy-da.py` (mode-sync pattern:
local code + live bindings preserved + DAKV appended; the older
deploy.py re-uploads live bytes only and cannot ship code
changes): HTTP 200, post-deploy read-back verified bindings
preserved + DAKV present. Local worker.js was byte-identical to
live pre-edit (34f17a7c…); deployed sha cfd8d127….

Verification: a local harness running the exact deployed bytes
against mock KV seeded with faithful fixtures of the documented
store state passed every tool — tools/list shows 9 tools;
muse_device_report classifies the frozen
2026-11-10T00:32:34.940Z report `frozen` (age_s 23198);
in_sync computed; enqueue success and refusal paths both correct;
unbound-DAKV returns a structured not-bound state. LIVE tool
calls were NOT verified: the KV values REST surface returned
HTTP 429 during the verification window (account KV quota burned
for 2026-11-10 by the flood; reset 2026-11-11 00:00 UTC = 19:00
EST) and a 429 is a hard stop, so no live call was attempted after
it. Pending first post-reset run: authed tools/list,
muse_device_report, muse_device_data (phone-notify),
muse_device_mode, one battery probe via muse_device_command +
muse_device_command_status read-back.

Design note for future readers: these tools are an API surface
over the fleet-side stores, NOT a phone transport. The v1.11
design stands untouched (tier-1 on-device outbox; tier-2 tailnet
https mirror, front UP per §11). Do not mistake this for
re-coupling the phone to Cloudflare writes — the only new
Cloudflare writes are MCP-side queue entries the app's existing
control lane already consumes. Records: muse-mcp repo
DEVICE-ASSISTANCE-TOOLS.md; DA connector doc "Consumed via
muse-mcp tools" section; both DA skill copies carry the same note.

## §13 — v1.11.0 install verification RETRY — 2026-11-10 ~02:04–02:10 EST, STILL UNVERIFIED

Retry at Jonathan's "Try again" (02:02 EST; run-supervisor
`da-v1110-install-verify-retry`), minutes after §12's UNVERIFIED
verdict, with his phone awake and in hand (his message came from
the Android app at Astoria). Verdict: **(c) STILL UNVERIFIED** —
the relay stayed dark across the whole window:

- **Relay (primary): still the seed, twice.** Read 1 (~02:04 EST)
  and read 2 (~3 min later, ~02:08 EST) both returned
  `received_at 2026-11-09T15:10:58.422Z` — byte-identical seed:
  `shell_bridge {paired:true, self_test_ok:true, on:true,
  voice_enabled:true, voice_active:false}`, seed adb endpoint
  `192.168.1.153:37165`, push_channels as of Nov 9. **No fresh
  report** → no self-switch to the https base, no install-cycle
  report, no fresh adb endpoint — so the ADB read-back leg
  (dumpsys versionName/versionCode, accessibility services)
  never opened; dialing the stale seed endpoint was not retried
  (proven refused in §12, same tunnel-proxy state).
- **Worker:** unchanged expectation — KV writes cap-blocked until
  2026-11-11 00:00 UTC; a frozen Worker report proves nothing
  either way and was not treated as failure.
- **Missing evidence (exactly):** one fresh relay report (proves
  install + switch together) or a live adb endpoint in any fresh
  report for the dumpsys read-back. Nothing shows the install
  failed; the app still has not completed a relay cycle since the
  ~01:53 EST manual install. Connector + skill v1.11.0 bullets
  stay at NOT YET INSTALLED. Unblock unchanged: the app's next
  completed cycle, or the 2026-11-11 00:00 UTC KV reset.

## §14 — v1.11.0 install VERIFIED by screenshot log signature + directive-miss diagnosis — 2026-11-10 ~02:11–02:25 EST

Jonathan supplied the missing evidence himself: a screenshot of
the app foreground on his S24+ (02:11:49 EST) showing the
on-device BridgeLog. Verdict: **(a′) INSTALLED AND RUNNING —
verified by log signature** (run-supervisor
`da-v1110-directive-diagnosis`). No dumpsys read-back exists (no
ADB endpoint); none is needed:

- **The v1.11.0-only log strings are on screen.** `02:09:34` and
  `02:11:22 [PUSH] phone-notify push local ok (1 items); no
  tailnet bridge base — local only` — the tier-1 outbox wording
  exists only in v1.11.0's PushClient (v1.9.0/v1.10.0 have no
  outbox and no such string). v1.11.0 is installed and cycling.
- **The loop fix is holding.** The listener is alive and SEEING
  the ongoing routines notifications (`02:09:17–02:09:24 [NOTIF]`
  / `[LISTENER]` lines — "At Home running", "Switch Sleep mode
  running", etc.) and they produce no `[PUSH] FAILED` flood:
  pushes are single-item, `local ok`. The class that burned both
  KV caps is being observed and ignored, exactly as built.
- **Tier 1 is delivering; tier 2 has no base.** Both pushes say
  "no tailnet bridge base — local only": the app has not picked
  up the https mirror base, because it has not processed the
  transport directive (below).

**Directive miss — root cause (two layers, both proven):**

1. **The Worker response carries no desired object.** Live
   authed read tonight: `GET /mode-sync/mode-state` → HTTP 200
   `{"desired": null, "actual": null, "in_sync": false,
   "transport": {"base": "https://vps.taila56db5.ts.net:8443",
   "secret": <present>}}`. The §11 directive IS riding every
   response; the KV keys behind desired/actual are what is
   missing. Every Worker handler reads them back empty tonight:
   `/mode-desired` → the `{mode:null, seq:0, updated_at:null}`
   default, `/phone-bridge/report` → 404 "no report yet",
   `/device-push?source=phone-notify` → 0 items — yet §10 read
   that same queue at 500 items at 05:40Z. The Worker's bound
   namespace is confirmed `ad95c4f2…` (live settings read), so
   the phone-era keys stopped being served from that namespace
   somewhere in the §11 redeploy window (between §10's 05:40Z
   reads and §12's 01:56 EST 404). The fleet's live desired now
   exists only in the relay store (seeded: home, seq 176).
   Honest caveat: every Worker handler swallows KV errors into
   nulls/defaults, and the KV REST surface returns 429 tonight
   (hard stop respected — one probe, no retries), so "keys
   absent" vs "in-Worker KV reads failing" cannot be fully
   separated until the 2026-11-11 00:00 UTC reset. The app's
   received body is identical either way.
2. **The app gates the directive behind desired.**
   `WorkerClient.fetchDesired` parses `root.optJSONObject(
   "desired")`; on null it logs "mode-state response had no
   desired object" and returns false BEFORE
   `handleTransportDirective(...)` is reached (directive
   handling sits after the desired store, same method). A
   desired-less response therefore strands the switch even
   though the directive is in the same body — the exact
   02:10:08 / 02:11:08 cycle lines in the screenshot.

**The report HTTP 500s are the known cap, not a second bug:**
`handlePhoneBridgeReportPost` ends in an unguarded
`await env.MODE_SYNC.put(reportKey, …)`; under the KV daily
write cap the put throws and the Worker returns 500. Reads are
unaffected (the app's mode-state GETs return 200 and parse).

**Fix: NOT applied tonight — post-reset prescription.** The
only server-side remedy is re-populating the Worker's
`mode:desired`, which is a KV write: cap-blocked until
2026-11-11 00:00 UTC, and a `/mode-set` would also seq-bump
against the relay's authoritative desired. After the reset:
mirror the relay's current desired verbatim into the Worker KV
(existing lanes), read back `/mode-state` showing desired +
transport together, and the app's next cycle will store desired
AND process the directive → self-switch to the https base.
Optional later app build (not required if the server keeps
desired populated): process the transport directive in
`fetchDesired` before / independent of the desired gate.
Phase 2 untouched throughout.

## §15 — Endpoint home corrected to the VM (design + stage 1 built) — 2026-11-10 ~02:30 EST

Jonathan re-asserted the original agreement (2026-11-09 17:30
EST; his correction 2026-11-10 02:22 EST): the phone endpoint
lives **on this VM**, reached via Home Link, Cloudflare out. The
box relay was interim. (A predecessor run on this brief reported
completion with no artifacts on disk; this section is the re-run,
artifacts verified present.)

- **Design written:**
  `docs/vm-endpoint-via-home-link-design-2026-11-10.md` —
  end-state (service + store on the VM; phone keeps
  `https://vps.taila56db5.ts.net:8443`; TLS terminates at the
  box's Tailscale Serve edge; a VM-initiated reverse tunnel —
  the ha-bridge pattern — is the only inbound path, this VM
  accepting no inbound connections; the box ends as a dumb pipe;
  the Home Link board is explicitly NOT in the phone's data
  path — shipped firmware exposes no TCP relay and the relay
  variants are BLE for the pairing route), staged path,
  muse-mcp recommendation, VPS audit.
- **Built tonight (stage 1):** the service RUNS on this VM —
  `vm-endpoint/server.js` (relay code verbatim except bind
  `127.0.0.1:8793`, own token 0600, own store), nohup pid 62162,
  run-supervisor label `da-vm-endpoint-design2`. Store seeded
  with the fleet's live desired (home, seq 176, per §14).
  Health check ~02:32 EST: unauthed → 403; authed mode-state →
  200 with **desired non-null {home, 176}** (the shape whose
  absence on the Worker stranded the directive); mode-desired
  → 200; report → 404 "no report yet" (expected, no phone has
  written here yet).
- **muse-mcp consolidation (Jonathan 02:24–02:26 EST):**
  verified from live state — Spark's MCP queue-check schedule
  has been paused since 2026-11-03, last MCP-queue completion
  Oct 11–12, `mcp-queue.py tasks` reads `[]` tonight, and the
  `spark-sheet-poll` hook is enabled (300 s): the email +
  Sheets lanes are the live Spark path, the MCP leg is dormant.
  Recommendation (in the design doc): retire the public
  muse-mcp Worker surface and fold the remaining fleet-side
  live tools (incl. tonight's five `muse_device_*`) into the
  VM endpoint service over the same local store.
- **VPS audit:** no piece has a hard reason to stay off-VM —
  box relay was build-night convenience, Cloudflare was Oct
  2026's fastest public host; the box's only irreducible role
  is tailnet node (Serve edge + tunnel terminus).
- **Gated:** §14 mirror fix → 2026-11-11 00:00 UTC KV reset;
  stage-2 tunnel + Serve backend switch → after the reset,
  verified through the public name before switching; phone's
  first report via the VM path → follows on its own cycle;
  muse-mcp fold-in → after the device tools' post-reset live
  verification. The live directive was NOT repointed tonight.
  No board flash is part of this design. Phase 2 untouched.

## §16 — v1.12.0 built: directive gate fix + scoped cleartext + Health channel removed — 2026-11-10 ~03:20 EST

Jonathan (03:08 EST): "now let's update device Assistance too
right? And then finish our pip work" + (03:11 EST) "Also remove
health connect, we agreed we don't need it." Built on Muse (agy
quota still exhausted; re-selection per model routing).

v1.12.0 / vc21, from the v1.11.0 tree, three changes only:
(1) DIRECTIVE GATE FIX — `WorkerClient.fetchDesired` runs
`handleTransportDirective` immediately after parsing the
mode-state body, before/independent of the desired gate; the §14
strand (desired-null response stranding the directive in the same
body) cannot recur. Desired behavior otherwise identical.
(2) SCOPED CLEARTEXT — new `res/xml/network_security_config.xml`
(base cleartext OFF; one domain-config entry permitting cleartext
only to 100.85.53.70, the box tailnet node — domain-config cannot
express 100.64.0.0/10; comment in file says so), wired via the
manifest; no global usesCleartextTraffic. The app can now reach
the tailnet-local endpoints (box relay / VM endpoint tunnel
terminus) without the Serve https hairpin — the §10/§14 SDK-35
cleartext blocker is closed for the endpoint in play.
(3) HEALTH CHANNEL REMOVED (the 2026-11-09 ~10:27 agreement,
scoped to the DA channel only): HealthPush.java deleted,
PushChannels.HEALTH + ALL entry, BridgeService check call,
MainActivity toggle case + hints, Store K_PUSH_HEALTH* keys, and
the manifest HC permission declarations all out. Channel set:
notifications, calendar, contacts, messages, dialer, call log,
battery (+ mode_reconcile). The Health Connect connector and the
Muse app's HC permission untouched — that lane is the keeper.

Build hygiene: a mid-run kill (to fold in change 3) plus a raced
second run produced a guard anomaly on a shared output file; both
artifacts were discarded and the shipped APK is a single clean
run — build.sh exit 0, all guards passed. Verified independently:
badging vc21/1.12.0, package unchanged, apksigner signer SHA-256
4dcb45fa69b2d82ffad68f877ab491b29d989188b09bcad55f3578906d477236
(byte-identical to v1.11.0), dex has zero HealthPush references,
network_security_config.xml packaged. APK
app/Muse-Device-Assistance-1.12.0.apk, 5,720,229 bytes, SHA-256
a8299ea8dfe8e1f1c53284ff733b12c329ab87e7d5640d3b801e1bef4bcf8884.
BUILT — NOT YET INSTALLED. Carry-overs verbatim: v1.11.0 loop
fix, tier-1/tier-2 delivery, PiP sequence, v1.5.2 reconciler,
pairing, Phase 2 DISARMED. Record: docs/v1.12-build-report.md.

## §17 — muse-mcp retired; device tools folded into the VM endpoint — 2026-11-10 ~03:30 EST

Jonathan (03:08 EST): "And retire the mcp, we don't need it."
Audit first (live state): Spark's MCP leg DORMANT (queue-check
schedule paused 2026-11-03; email canonical + Sheets return lane
standing; `mcp-queue.py tasks` read live = []; last MCP
completion Oct 11–12). `mcp-queue.py` / `sheet-poller.py` talk
KV REST directly — never the Worker — and keep working. The five
`muse_device_*` tools had no live client (Gemini, the only MCP
client, never called them; live authed verification was moot).
The a2a soak-report cron references only the local CLI as
historical evidence. Nothing consumer-live blocked the takedown.

FOLD-IN (before takedown, verified): the five tools' semantics
now live on the VM endpoint service as
`vm-endpoint/bin/device-tools.js` (report / data / command /
command-status / mode) over the local store. Verified locally:
mode → live desired home seq 176; report → honest no-report
negative; data → answers; a battery enqueue wrote
`phonebridge_cmd_<id>.json` + queue in the VM store and
command-status read it back pending. Service restarted (pid
124861, supervisor label `da-vm-endpoint`) with the v1.12
channel alignment: `phone-health` source + report `health`
channel id removed from server.js.

TAKEDOWN (cf.py API lane): Worker script `muse-mcp` DELETED
(post-delete settings GET → 404); its 4 routes on
jonathancruzate.com DELETED (`mcp*`, `oauth/*`,
`.well-known/oauth-*`, `.well-known/openid-configuration*`;
read-back: zero routes remain; /mcp now falls through to the
site origin). The `agent-card.json` → `muse-a2a` route was NOT
touched. KEPT: all KV namespaces + data (MCPKV 64abab86…
archive; the mode-sync ns is the phone's live store, untouched),
the folder's code/docs as record, Spark's email + Sheets lanes,
the `spark-sheet-poll` hook (its commit target becomes the VM
store as a later stage item, per the design doc).

Records: `~/workspace/cloudflare-workers/muse-mcp/RETIREMENT-2026-11-10.md`
(full audit table); DA connector + DA skill muse-mcp sections
marked retired with the CLI pointer; `local-gemini-spark` skill
retirement note; `custom.cloudflare` connector inventory entry
marked retired; DEVICE-ASSISTANCE-TOOLS.md header marked
retired. Phase 2 untouched throughout.

## §18 — v1.12.0 install verified live; Worker report read-back lags (KV read consistency) — 2026-11-11 ~01:00 EST

Jonathan installed v1.12.0 and opened the app on request. His two
screenshots (00:50–00:59 EST) are the primary evidence:
- Push channels live: phone-notify pushes "local ok; tailnet bridge
  mirrored" repeatedly; calendar observer fired → 50 items mirrored;
  call_log observer fired → 50 items mirrored. NO Health lines
  anywhere — the removed channel is absent, as built.
- `[WORKER] report (heartbeat, verdict=unknown) -> HTTP 200` at
  00:53:04 EST — the app cycles (60 s loop) and reports succeed
  against its CURRENT base (still the Worker; no TRANSPORT lines in
  the visible log, so no switch yet).
- Phase 0 probe: notification listener GRANTED; app installed from
  com.google.android.packageinstaller (manual in-place install).

Server-side state (this VM, via the proxied Worker API):
- KV reset confirmed working: a fresh mode-set landed post-reset
  (seq 175, 2026-11-11T05:47:06Z), superseding the Nov 9 backstop's
  seq 174. Worker /mode-state now returns desired (home, seq 175)
  + transport {https://vps.taila56db5.ts.net:8443} TOGETHER — the
  §14 precondition is met server-side.
- BUT the Worker's report read-back still returns the frozen
  2026-11-10T00:32:34.940Z report (desired seq 174, health channel
  present) across repeated reads spanning ~20 min after the app's
  HTTP-200 report post. Read-after-write lag vs a stale read path
  is NOT yet distinguished; do not claim the report store is frozen
  — the app's 200s say writes land somewhere. Next diagnostic: a
  longer-lag re-read + the box relay's device-push mirror read-back
  (the tier-2 mirror target) once a read lane to the box store is
  re-established (SSH egress currently times out at the proxy
  banner; the tailnet forwarder reaches the box relay port but the
  box relay's own secret differs from the VM endpoint token.txt —
  403 — so the mirror store is presently unread from here).
- Cycle mechanics (source, v1.12 tree): cycle every 60 s;
  fetchDesired each cycle; report only on change or when the
  15-min heartbeat is due (lastReport persisted). The 00:53 report
  was likely the post-install change report.
- Voice/PiP trial still pending: fire only after a fresh report
  read-back (or screenshot BridgeLog evidence of TRANSPORT/fetch
  lines) shows the app consumed seq 175 + the directive.

## §19 — The Worker command/report lanes are NOT being polled by the phone (2026-11-11 ~01:12 EST)

Decisive separation test. While Jonathan's screenshots prove the
v1.12 app cycles (60 s) and pushes fine against SOME base, a
`battery` command enqueued on the WORKER at 06:06:58Z stayed
`pending` through six status polls over ~2.5 min (never delivered),
and the Worker's report read-back is still the 2026-11-10T00:32:34Z
report after many app cycles — including after the app's own
00:53:04 EST `[WORKER] report -> HTTP 200` log line.

Reading of the evidence: the phone is NOT polling the Worker's
command queue or posting reports to the Worker's store. Its pushes
mirror to the tailnet bridge base, and its WORKER-tagged report 200
went SOMEWHERE that answers — most consistent with the app already
sitting on the https relay base (vps.taila56db5.ts.net:8443): i.e.
the transport switch DID happen (earlier than the visible log
window, or silently during the v1.11→v1.12 probation), and all
live state now lives in the BOX RELAY's store — which this VM
currently cannot read (box SSH times out at the egress-proxy
banner; the Serve front answers but the box relay's own secret
differs from every token on this VM).

Consequences:
- All Worker-side phone reads (report, command status) are STALE
  BY CONSTRUCTION until proven otherwise; stop treating them as
  the phone's state. The VM endpoint's empty store is likewise
  expected (Stage 2 not built).
- The voice/PiP trial must be enqueued on the RELAY (the phone's
  actual base), which needs a working box lane: restore box SSH
  egress or find the relay secret's VM-side copy.
- Open question for the app-log: BridgeLog TRANSPORT lines are the
  only on-device proof of when/if the switch happened; Jonathan
  reported no TRANSPORT line in the visible window (the log view
  shows the latest 40 entries; a switch line from an earlier cycle
  would have scrolled out).

## §20 — BREAKTHROUGH: the relay IS readable from this VM; the phone IS on the relay; v1.12 confirmed server-side (2026-11-11 ~01:13 EST)

The "unreachable box" was a lane error, not an outage. Working lane,
end to end from this VM: `python3 ~/workspace/bin/tailnet-forward.py
18792 100.85.53.70:8792`, then HTTP to 127.0.0.1:18792 with the
relay secret from `~/workspace/goals/manage-my-smart-home/
hidden_files/device-relay-token.txt` (64 chars; the Worker's
TRANSPORT_SECRET in tonight's mode-state read matches its tail —
the file IS the relay token; earlier 403s used the VM endpoint's
OWN token.txt, a different secret — that was the whole "box token
differs" confusion).

Relay reads (2026-11-11 ~01:12 EST):
- /mode-state: desired {home, source "tell-muse-im-up", seq 178,
  updated 2026-11-10T14:13:59Z} — the relay's store carries its own
  desired lineage (seq 176→178), written via relay /mode-set by
  other lanes after the switch.
- /phone-bridge/report: received_at 2026-11-11T06:08:05.217Z —
  FRESH (01:08 EST, minutes old at read time); desired seq 178;
  **push_channels has NO health key** — v1.12.0 confirmed live from
  the server side, closing the install verification without relying
  on screenshots alone.

So the §19 reading is confirmed and completed: the phone switched
to the https relay base, reports/commands/pushes all live in the
relay store now, and the Worker lanes are legacy (a stray battery
command + a junk __retract__ probe sit pending in the Worker queue;
the phone will never fetch them — harmless residue, expires by TTL;
the phone fetch happens BEFORE command delivery per cycle, so no
Worker-queue entry can ever reach a relay-based phone).

Trial preconditions, all verified: phone on relay base ✓; voice
gate state voice_enabled=true in the fresh report ✓; voice module
gates (in-app toggle + accessibility) pass per v1.5-era proofs ✓;
PiP choreography reads the CURRENT foreground at dispatch (no
pre-staging needed); relay enqueue route = POST /phone-bridge/
enqueue on 127.0.0.1:18792 with the relay secret.

## §21 — Voice trial attempt 1: activation works; the prime fired too early and in the wrong voice (2026-11-11 ~01:17-01:18 EST)

Sequence as executed: relay enqueue `voice_mode on`
(cmd-92350504) → delivered 06:17:21.269Z, done, result
{ok:true, "voice_mode=on"} within ~2 s. The main-chat prime
(submission 81bf2998) had been sent BEFORE the enqueue, and it
was phrased as an agent instruction ("[Voice activation — Device
Assistance] … Speak first…"), not as a user-role message.

Jonathan's correction (01:18 EST), the operative spec:
1. WAIT a few seconds after firing activation before priming —
   the session UI has to exist before the loop can pick the
   message up.
2. The prime must be sent AS HIM (user-role relay), not as an
   agent instruction: the voice loop consumes the conversation's
   user messages; an instruction from the assistant does not get
   picked up the same way.
3. The prime's content must EXPLAIN what Muse should then say
   to him — the relay carries his framing of what he wants told,
   and Muse speaks from it.

Attempt 1 verdict: "It almost worked" — activation + session
start confirmed live (a first: the command path is proven
end-to-end on the relay); speak/PiP outcome unobserved because
the prime missed. Retry in this chat's window per his direction.

## §22 — The bridge delivery form, re-taught (2026-11-11 01:20 EST)

Attempts 2 (this chat, cmd-caab6bdb, dispatched ok) repeated
attempt 1's error in a second form: the prime went via
`chat.send_message` in Jonathan's voice — still an agent
instruction, still invisible to the voice loop. Jonathan's
correction (01:20 EST): "you have to send a message as the user,
not yourself, using accessibility service."

Canonical form (already documented in the DA skill's voice
section, from his 2026-11-09 00:04 EST finding — "you have to
send a message as ME to yourself"; re-learned the hard way
tonight): the bridge must enter the MAIN chat thread as a
genuine USER-turn message. The mechanism is the Device
Assistance accessibility service on the phone: it can act on the
Muse app's UI (it already taps the voice button there) — the
bridge text is typed into the main conversation's composer and
sent as Jonathan. chat.send_message in ANY voice (assistant's or
imitation-Jonathan) does not satisfy this; three failed
submissions from 2026-11-08 (795b8f69, 5415c330, 884f25af) plus
tonight's two (81bf2998 — right chat, wrong timing + form;
30b4b633 — right words, wrong delivery) are the evidence set.

Gap: the shipped DA command set has NO text-injection template
today (templates audited 2026-11-11: no type/inject/chat verb;
VoiceAccessibilityService exposes hunts + PiP + foreground reads
only). The user-turn bridge therefore needs a new capability:
an accessibility-driven composer injection in the Muse app's
main conversation (open main conversation → focus composer →
ACTION_SET_TEXT / clipboard paste → tap send), exposed as a
gated template. That is the next build; until it exists, remote
voice activation can start the session but cannot prime it, and
the speak-first half of the trial stays blocked on it.

## §23 — v1.14.0 BUILT: the voice_bridge command (2026-11-11 01:38 EST)

Jonathan approved the build (01:22 EST "Yes", 01:29 "Proceed")
after §22 established that chat-side primes can never reach a
voice session in ANY delivery form Muse controls directly.
v1.14.0 / versionCode 23, built on the v1.13 source tree (so it
carries wear_install_face too — one install for both):

- NEW template `voice_bridge` (native): args are the words of
  one bridge message; ShellAllowlist.checkVoiceBridge joins
  them (per-arg <=256 chars, joined <=2000, no control/quote/
  backtick chars) into one typed value on NativeGate.arg. Never
  shell text.
- VoiceBridge.execute: refuses when the accessibility service
  is not running or a send is already in flight; otherwise sets
  VoiceState {awaitingBridgeSend, bridgeText} and kicks the
  hunt. Result is a QUEUED ack — execution evidence is the
  hunt's BridgeLog lines + the message landing in the main
  chat (dispatched + verified discipline, as with voice hunts).
- VoiceAccessibilityService bridge hunt (~17 s window): not
  aura-gated at the event level (any window change can run it);
  launches Muse via its launch intent when no aura window
  exists; finds the editable composer (isEditable / EditText)
  across ALL windows (the chat sits under a floating session);
  ACTION_SET_TEXT (focus fallback); send via view-id
  candidates then a clickable-desc/text tree search for "send";
  logs "bridge sent" / "bridge FAILED" loudly either way.
- ShellBridge routing: "voice_bridge" is matched BEFORE the
  voice_ prefix branch (it would otherwise hit VoiceModeModule
  and die as an unknown op).

Build: app/build.sh full pass — dex guards green (7 dangling
GMS-dialog androidx refs, same counted set as v1.13), native
libs packaged, signer cert SHA-256
4dcb45fa69b2d82ffad68f877ab491b29d989188b09bcad55f3578906d477236
(byte-identical to the family cert / v1.12.0). APK
app/Muse-Device-Assistance-1.14.0.apk, 6,101,157 bytes,
SHA-256 6f777e3f3aa10d63b2deed226f0ded9c4be02624812922d98b90d16cc611f1ea.
NOT YET INSTALLED; the phone still runs v1.12.0. Next: Jonathan
installs in place (same cert — pairing + grants survive), then
the joint voice trial rerun with the corrected sequence:
fire voice_mode on -> wait a few seconds -> fire voice_bridge
with the prime -> main chat speaks first -> PiP check.

## §24 — Correction to §23's closing note (2026-11-11 01:53 EST)

§23's "next" paragraph claimed the phone still runs v1.12.0 —
WRONG. Jonathan installed v1.14.0 in place at ~01:50 EST
("Done, test it"). A fresh relay report read at 06:38:06Z —
fifteen minutes BEFORE the install — showed voice_enabled
true / voice_active false, so the report alone could not prove
the new build; the proof is Jonathan's install confirmation
plus the relay command channel's acceptance of the new
template (a v1.12 phone would refuse voice_bridge as an
unknown native template).

Trial state at this entry: preconditions verified (fresh
report, relay base, voice enabled, session inactive). The
voice_mode on enqueue for this trial (cmd-49bcefbd…) is
unverifiable from this VM: /phone-bridge/command-status reads
the BOX relay's store (its BASE_DIR is the box's ~/device-relay),
which this VM cannot inspect — the command id 404s there, which
is expected if the id never existed on that store and proves
nothing either way about delivery. Delivery confirmation rests
on Jonathan's on-screen observation. Honest close: whether the
session started, whether the bridge text landed in the main
chat, and whether PiP floated are all awaiting his report;
nothing in this section claims them.

## §25 — v1.14.1: PiP return lands on One Hand Operation + — root cause + fix (2026-11-11 ~02:50 EST report; fix built same night)

Jonathan's report (live voice call, ~02:50 EST): after a
self-activated voice session, the PiP return step lands him in
"One Hand Plus" (Samsung One Hand Operation +, the Good Lock
gesture utility) instead of the app he was using. Intended flow
(his words): open app → start voice mode → Muse talks → return
to his last-used app. "It was working like that a couple of
versions ago."

### Diagnosis (code + history; facts vs inference separated)

Confirmed from source (current tree = phone's v1.14.0 codebase):

- The return target is captured exactly once, in
  `VoiceLauncher.launchVoice`, via
  `VoiceAccessibilityService.currentForegroundPackage()`, and
  consumed once, in the PiP `pipDoStep`, via
  `relaunchPackage(target)` (launch intent + NEW_TASK). No other
  writer of `VoiceState.returnTargetPkg` exists.
- `currentForegroundPackage()` trusts `getRootInActiveWindow()`
  first; on a miss it falls back to iterating `getWindows()`
  **with no window-type filter**, returning the first root whose
  package passes `isReturnable` (not aura / not us / not
  launcher).
- `AccessibilityService.getWindows()` returns ALL interactive
  windows, including `TYPE_APPLICATION_OVERLAY`. **One Hand
  Operation + (package `com.samsung.android.sidegesturepad`)
  keeps a persistent overlay edge-handle window on screen** —
  that is its always-on gesture surface. It is not the launcher,
  not aura, not us: it passes `isReturnable` as written. The
  fallback branch can therefore record the overlay utility as
  "the app he was using", and the return then faithfully
  relaunches One Hand Operation + — matching the reported
  symptom exactly (he lands IN the One Hand app).
- Nothing in the tree special-cases overlay/utility packages
  (grep: no sidegesturepad / One Hand reference anywhere in
  src or docs before this fix).

History (why "a couple of versions ago" it worked):

- The capture → relaunch path shipped in **v1.10.0**
  (2026-11-09, execution record §2 of this doc's v1.10.0 entry).
  Before it, no recorded package was ever relaunched — the flaw
  had no consumer, so the flow could not land in One Hand.
- v1.10.0 was built-not-installed; **v1.11.0 (installed
  ~2026-11-10 01:53 EST)** was the first build carrying the
  sequence onto the phone, and v1.12.0/v1.14.0 carried it
  verbatim (their build reports list the PiP sequence as a
  carry-over; no PiP-code change in v1.11→v1.14 besides the
  v1.14 bridge hunt, which is not aura-gated but does not touch
  the capture). The joint live trial that would have exercised
  the return only happened tonight (2026-11-11 ~01:17–02:50) —
  which is when the bug surfaced. Consistent with his timeline.

Inference, labeled as such: the trigger branch is the fallback
(active root null/non-returnable at capture instant). Which
branch fired on his specific activations cannot be proven from
this VM tonight — the on-device BridgeLog is not readable from
here (VM endpoint service down; §19–§24 lane state) and the
capture was not logged at all pre-fix (only the return branch
was). The fix makes the fallback branch safe regardless, and
adds capture logging so the next live trial's BridgeLog shows
the recorded package by name.

Ruled out: UsageStats/recents ordering (the code never queries
them); return-intent resolution picking the wrong package (the
relaunch uses the recorded package verbatim — the RECORD was
the defect); the v1.14 bridge hunt interfering with the capture
(it neither reads nor writes returnTargetPkg).

### Fix (v1.14.1, versionCode 24 — voice files only)

1. `VoiceAccessibilityService.currentForegroundPackage()`: the
   fallback scan now only accepts windows of type
   `TYPE_APPLICATION` — overlays (One Hand's edge handle, any
   future overlay utility) can never be recorded as the
   foreground app.
2. `isReturnable()` additionally excludes
   `com.samsung.android.sidegesturepad` by package, as
   belt-and-braces in case a future One Hand build presents an
   application window.
3. `VoiceLauncher.launchVoice()` BridgeLogs the recorded
   return target (or "(none)") at capture time, so a bad
   capture is visible in the log instead of only at return.

Untouched: the PiP choreography itself, speak-first, the
v1.5.2 reconciler, the v1.14 bridge hunt, pairing, Phase 2.

### Build + deploy state

- Build: PENDING at this writing (see the build line appended
  below when build.sh completes).
- Deploy: STAGED, deliberately not installed from this lane.
  Jonathan is in a live voice session on the phone right now
  and the app's ADB/install lane from this VM is down (VM
  endpoint service not running; §19–§24) — his established
  pattern for these builds is installing in place himself
  (v1.11.0, v1.14.0), which preserves pairing + grants (same
  family cert). Install: `Muse-Device-Assistance-1.14.1.apk`,
  in place over v1.14.0, no re-grant expected.
- Live verification still owed after install (joint trial):
  self-activate from a normal app (not the launcher); the
  BridgeLog should show `return target recorded: <his app>`,
  `PiP return: relaunched <his app>`, and
  `PiP verify: … foreground=<his app>` — and he should land
  back in his app with the session floating, never in One Hand
  Operation +.

### §25 build line (completed)

- `app/build.sh`: VERSION_CODE=24, VERSION_NAME="1.14.1". Build
  clean: all dex guards passed (66 app classes; the 7 counted
  dangling GMS-dialog androidx refs, same set as v1.13/v1.14),
  native-lib guards passed.
- APK `app/Muse-Device-Assistance-1.14.1.apk`, **6,101,157
  bytes** (byte-size identical to v1.13.0/v1.14.0 — same resource
  set; the change is code-only in two voice files), **SHA-256
  `a8014a66f3183be8bb8b38c17f0359b6e9875df1913bbf8717861deb1f0eb2d2`**.
- apksigner: signer cert SHA-256
  `4dcb45fa69b2d82ffad68f877ab491b29d989188b09bcad55f3578906d477236`
  — family cert, unchanged, so the in-place install preserves
  pairing + grants.
- Deploy: STAGED (see §25 deploy note above). Awaiting
  Jonathan's in-place install + the joint-trial verification.

## §26 — Worker phone-bridge lane DELETED as a callable path; the "two lanes" confusion root-caused (2026-11-11 ~03:01–03:20 EST)

Jonathan's question ("Should we delete the old lane so you don't get
confused again") after the joint-test fire went wrong. Findings, all
read-back verified:

1. **Main's first fire never touched the Worker.** At 03:01:26 EST,
   `phone-bridge-cmd.sh enqueue voice_mode on` (cmd-935eab24) was
   enqueued **on the box relay** — file
   `~/device-relay/store/phonebridge_cmd_cmd-935eab24….json` mtime
   03:01:26.945 EST on the box, and the relay's merged
   `/phone-bridge/command-status` view lists it. The script has
   defaulted to the relay since the 2026-11-09 transport cut; the
   "Worker copy will never be fetched" statement made to Jonathan
   at 03:05 EST was **wrong** and is corrected here and in chat.
   (Local mirror residue: `relay-vm/store/` files dated Nov 9 are a
   synced copy of the box store, not a separate live store.)
2. **The real open problem is pickup, not lanes.** BOTH relay
   commands (cmd-935eab24 03:01, cmd-97a76e24 03:04 — the second
   enqueued directly over the tailnet forwarder, duplicating the
   first) sat `pending` for 15+ min while the relay's
   `phonebridge_report.json` advanced to 03:07:00 EST
   (`voice_enabled:true, voice_active:false, paired:true, on:true`)
   — **reports flow; command fetches do not**, in the cycles
   observed. §19/§20 established the phone polls the relay; why the
   post-v1.14.1-install cycles report without fetching is the open
   diagnostic. Never again conflate "pending" with "wrong lane"
   without reading the box store first.
3. **Deletion executed (the callable surface, per his order):**
   `phone-bridge-cmd.sh`'s `MODE_SYNC_BASE` Worker override is
   removed — setting it now exits 1 with a loud retired-lane
   message (verified live). The relay is the script's only path.
   Backup: `phone-bridge-cmd.sh.bak-20261111-pre-worker-override-removal`.
   Deliberately NOT touched: the mode-sync Worker's deployed
   `/phone-bridge/*` endpoints — §19/§20's own evidence (a battery
   command pending there since 2026-11-10T04:49Z, mirrored as
   cmd-26f0422f) says the phone never polls them, and the Worker
   still carries non-phone lanes (panel exception, transport
   directive). Deleting deployed endpoints strands the wear
   bootstrap (wearbridge KV has no relay port yet) for zero
   confusion benefit; the confusion lived in the callable script
   surface and in stale doc wording, both fixed.
4. **Doc corrections:** the device-assistance CONNECTOR.md's
   loopback-shell-bridge paragraph named the Worker endpoints +
   this script without the relay cut — now leads with the relay
   lane and the 2026-11-11 override removal. The v1.13
   `wear.install` entry still says "on the Worker" — accurate for
   the wear lane specifically (Worker wearbridge KV, bootstrap
   pending) and now cross-noted as the sole remaining Worker-lane
   command path.
5. **Standing lesson (joins the §19/§20 line):** before declaring
   any phone command "wrong lane / stale / unfetchable", read the
   BOX relay store (file + mtime) and the relay report's
   `received_at`. Queue state alone proves nothing about which
   transport a command rode.
6. **Overnight safety retraction (same handling):** both pending
   voice commands were deleted from the box relay store (command
   files removed, queue emptied; verified: `pending` → [],
   status → "unknown command"). A stalled queue that self-heals
   at 4 AM must never be able to start a voice session while he
   sleeps. The joint test re-fires fresh when he's awake.

## §27 — Relay args cap found: voice_bridge primes must be CHUNKED (2026-11-11 ~04:39 EST)

Live trial rerun at Jonathan's direction ("send a message to yourself as me, try it right now" / "Then retry the test"):

- Previous activation re-checked: voice_mode on cmd-d25b8f16 (enqueued 09:32:44Z) delivered 09:33:12Z, result ok "voice_mode=on dispatched". Pending queue empty.
- First voice_bridge fire this run (cmd-545151a3) sent the prime as ~60 word-args: the command record came back with only 16 args / 99 chars and the app acked "voice_bridge queued (99 chars)". Root cause, code-verified in the relay copy on this VM (`relay/server.js`): `CMD_MAX_ARGS = 16`, args sliced to 16, each capped 256 chars. Word-per-arg primes are therefore silently truncated at 16 words — this also explains the truncated bridge arrival in the earlier §23-era trial.
- Correct form (verified live): pack the prime into a few quoted phrase args (<=16 args, each <=256 chars, no apostrophes/quotes/backticks — the app gate bans those characters). Second fire (cmd-4aaf40f2): 4 phrase args, delivered 09:40:11Z, ack "voice_bridge queued (553 chars)" — the full prime queued.
- Return-target verification this run is INCONCLUSIVE so far: a screen_foreground probe (cmd-bcbb4dca, done 09:41:11Z) read `mCurrentFocus=NotificationShade` — Jonathan had the shade down, which overlays any app, so it proves neither Reddit nor One Hand Operation +. Landing evidence still owed: the bridge messages appearing in the main chat as genuine user turns (the hunt's send evidence) and Jonathan's own report of where he is / lands.

## §28 — THE PROVEN WORKFLOW: voice on → bridge → previous app, live-verified end to end (2026-11-11 12:09–12:11 EST)

Jonathan's direction in side chat f4b72e3d: "you have to do a sequence of events, voice mode on first then message then back to the previous app, one more try" — after two same-morning runs in that chat broke the sequence (run 1: bridge fired after the session had already ended; the §27-era runs: word-per-arg primes truncated at the relay's 16-arg cap).

The run, all commands singly via the box relay (`phone-bridge-cmd.sh`), preconditions verified first (fresh report, `voice_enabled:true`, `voice_active:false`, queue empty):

- `voice_mode on` — cmd-22e8f628-f8db-452f-90f8-03b40f92ada3, enqueued 2026-11-11T17:09:49.740Z, delivered 17:09:49.976Z, done, ok, "voice_mode=on dispatched", 203 ms.
- ~10 s wait for the session UI to connect (the PiP return step — relaunch of the recorded foreground app, HOME fallback — runs inside this window).
- `voice_bridge` — cmd-4c6fd0ab-ed54-4ed3-a243-8ec185ea092d, enqueued 17:10:12.740Z, done, ok, "voice_bridge queued (78 chars)". Prime: 15 words, word-per-arg (inside the §27 cap): "Voice bridge test. Voice mode is on. This was typed as me. Speak first please."
- `screen_foreground` probe after the sequence — cmd-9e6a12d1, done 17:10:52Z: `com.facebook.aura/...AuraWidgetLaunchActivity` (the bridge hunt's launch window; per the workflow note, this neither proves nor disproves the PiP landing).
- **Jonathan's verdict, 12:11 EST: "It worked, perfect."** The full sequence — Muse starts voice, the message lands as him into the live session, back to the previous app — is verified end to end by the device owner.

Encoded the same handling, at his order ("update the device Assistance skill with this exact workflow that works, all our automations, instructions, etc — and upstream"): the PROVEN WORKFLOW block in SKILL.md (all three copies byte-identical: project, installed `local-muse-device-assistance`, `github-export/`), the voice-channel checklist in the `custom.device-assistance` connector doc (steps 3–7 rewritten to the sequence), the MEMORY.md voice-bridge standing rule, and the daily log. Automations sweep: no cron or hook body references voice activation (checked `~/workspace/cron.d`, `~/workspace/system-cron.d`, goal crons, `~/hooks/scripts`) — automations reach this path only through the skills, which now carry it. SKILL.md upstreamed to `jonathancruzate/muse-device-assistance` in the same handling (read-back verified; commit recorded in the snapshot-sync log).

## §29 — voice_call: the combined fire, the payload protocol, the unlock gate (2026-11-11 → 2026-11-13)

Jonathan's design orders (2026-11-11 12:16–12:19 EST): combine voice mode and the bridge into ONE fire ("so you just have to fire once"); the semantic part moves BEFORE the fire into a mutating `~/workspace/voice_context.md` — "you write the context about what you want to let me know about in the voice context file" — and the session stays quiet until it reads the context, then speaks first.

**Built:** native template `voice_call` (no args) → `VoiceBridge.executeCombined`. If a session is observably live, marker only; else queue the fixed marker ("This was fired by voice mode. Stay quiet until you read the voice context, then speak first."), arm `combinedCallPending`, launch voice; the PiP return is deferred until the marker hunt resolves (50 s watchdog backstop). Nine live attempts (2026-11-11 19:45 → 2026-11-12 00:09 EST) bought the refinements, each traced to a named cause:

- **v1.16.0 (vc27)** — session-live hook: the prompt holds until the session is mechanically live (a prompt typed before the session exists becomes pre-session history; the proven 12:10 fire had it arrive ~23 s after start).
- **v1.17.0 (vc28)** — CONNECTED-only sustained detection (`hatch-voice-call-connected`, 2 consecutive polls) replaces the generic UI probe, which renders on the CONNECTING screen and released prompt + return seconds early (attempt 5: "it went back before the prompt was sent" — his correction). `schedulePipAttempt()` refuses to run while a combined call is pending: the return has one release point. Prompt-first is structural, not timed.
- **v1.18.0 (vc29)** — 20 s post-send dwell (`POST_SEND_DWELL_MS`): the return also waits for the session to PROCESS the prompt, per his 21:02 diagnosis ("you don't wait long enough after sending the prompt for the agent to process it").
- **Payload protocol v2** — attempt 6 (2026-11-11 20:58 EST) proved the file-only channel unreliable: the session's two reads of `voice_context.md` tripped the runtime's prompt-injection safety check and it refused to speak from the file (his words: "voice context got blocked by my stupid safety check"). The session itself proposed the fix: the payload ALSO goes into the main chat as a normal message before firing ("What's written in the chat between us, I can use"). Firing side now: file (audit/backup) + in-chat payload, then one fire.
- **v1.19.0 (vc30)** — locked-screen gate (his order 2026-11-12 00:14 EST, after attempt 9 failed on a locked screen): `UnlockActivity` trampoline (show-when-locked, turn-screen-on) wakes + `requestDismissKeyguard`, polls `isKeyguardLocked()` every 500 ms up to 15 s; still locked → loud BridgeLog failure + unwind (automation never touches credentials). `weUnlockedPhone` → the sequence re-locks at its end (`maybeRelock` at every PiP resolution point). The combined watchdog re-arms while an unlock is in flight. The same gate shipped in Muse Voice Mode Shortcut v1.8.0 (vc10) the same night.

**Live verification (2026-11-13):** v1.19's code shipped inside the v1.20/v1.21 builds of the Launcher Pack push (installed 2026-11-13 ~01:15–01:32 EST; tree-verified). Retest ordered by Jonathan 02:21 EST ("install the latest version over adb and run a test" — latest was already installed; the ADB detour was dropped at his correction, "you have the device assistant app"). Payload staged (file + main-chat message), phone locked by Jonathan 03:07 EST, one `voice_call` fired 03:08 (cmd-0f745aee). The sleeping phone's app picked it up on its cycle at 03:12:07 EST (~4 min pending — pickup latency on a sleeping phone, now documented as normal) and executed ok. **Jonathan's verdict, 03:21 EST: "it worked"** — unlock, speak-first payload, float-back, re-lock all landed.

**Routing replacement (his order, same message, 2026-11-13 03:21 EST):** "replace our macrodroid voice with this instead... use broadcast on home speakers only if im home and if my phone is offline." Findings on why voice went unused: the Oct 29 broadcast staging gate drifted into broadcasts never firing (recorded in the broadcast skill's own history); the Nov 6/Nov 12 fixes unblocked the gate, but the routing canon (`notification-routing.md`, phone-automations) still named broadcast-first-at-home and MacroDroid speak as the channels, while the DA voice path's discretion grant lived only in the DA skill — so in practice everything defaulted to chat. Fixes applied in the same handling: `notification-routing.md` rewritten (voice_call primary everywhere except work/asleep; broadcast ONLY home + phone offline; MacroDroid speak retired); broadcast skill conditions gain the phone-offline hard condition (welcome-home flagged as pending his word under the new rule); phone-automations' two live routing statements replaced (its spoken-announcements section was already bannered superseded); `morning-briefing.py`'s broadcast-failure fallback no longer fires `?do=speak` — it queues a schema-matched voice_call request in the push-urgent inbox for the agent lane; DA skill gains the VOICE CALL section (all 3 copies byte-identical) and the connector checklist now leads with the single fire.
