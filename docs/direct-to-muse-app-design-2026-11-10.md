# Direct-to-Muse-app delivery — design (2026-11-10)

Jonathan's order (2026-11-10 00:35 EST): Device Assistance's data should be
pushed directly to the Muse app on the phone — no Cloudflare Worker, no
box relay, no VM relay in the phone's path. This doc grounds that in the
surfaces that actually exist, splits the data by what each surface can
carry, and names the one residual piece honestly.

## What exists today (evidence, not speculation)

- **ALL** Device Assistance traffic rides one base URL: pushes
  (`POST /device-push`, nine channels on the app's existing base + secret,
  "no second config" — connector), the state report
  (`POST /phone-bridge/report`), desired fetch (`GET /mode-state`), and
  the command channel. Whoever owns the base owns every lane.
- The Worker push queues are consumed **VM-side** by the
  `device-push-drain` hook. The Muse app on the phone is not in that
  path at all today.
- The **Muse Android app itself already ingests phone data** through
  the official pairing: per `~/docs/client-surfaces.md`, Android pairs
  with "alarms, placing calls from the user's number, reading or sending
  texts, **reading phone notifications**, and searching call history,"
  and "pairing shares what your phone pushes to Muse (notifications,
  texts, contacts, calendar)." The fleet already consumes that stream:
  `phone-notify-drain` → `pending_replies.json` + shade state — live
  tonight (shade state refreshed 2026-11-10 00:00 EST, ledger current)
  while the DA→Worker queue sat frozen. That pipe is Meta's platform,
  not a server of ours.
- The Muse app exposes **no local third-party ingest**: the platform
  docs list no intent receiver, ContentProvider, or file drop by which
  another Android app can hand it data; in-app sharing is user-mediated
  (long-press share sheet). Android sandboxing independently forbids DA
  writing into the Muse app's storage. There is no sanctioned
  app-to-app channel to build on.
- This VM **cannot be the phone's endpoint**: it has no tailnet
  interface (only `lo` + a NAT `host0`); its tailnet access is an
  outbound-only tunnel that "accepts no inbound connections"
  (`~/docs/devices/tailscale.md`). The phone can never dial it. (This
  also reconciles the endpoint-home discrepancy: the 2026-11-09 cutover
  record put the relay on the Hetzner box, `100.85.53.70:8792`;
  Jonathan's understanding was "your vm." The box was the only
  tailnet-reachable home that existed — and per the 2026-11-10 redesign
  order, both are now superseded anyway.)

## Why the phone never switched (proven 2026-11-10, feeds this design)

`build.sh` compiles with `--target-sdk-version 35`, and
`AndroidManifest.xml` declares **no `usesCleartextTraffic` and no
`networkSecurityConfig`**. At targetSdk ≥ 28 Android denies cleartext
HTTP by default, so the app's pre-switch probe of the relay base
(`http://100.85.53.70:8792/mode-state`, WorkerClient.probeModeState)
throws and returns false every cycle — "TRANSPORT probe failed, staying
on current base." The relay itself is healthy (app-equivalent probe
from the VM: HTTP 200, body carries `desired`; secret matches). The
v1.10.0 build inherits the same manifest: **as built, it cannot switch
to any `http://100.` base either.** Any future endpoint for this app
must be `https://` (the directive validator accepts it) or the build
must carry a scoped cleartext exception.

## The design — split by data class

### 1. Notifications → the Muse app's own pairing pipe (zero servers of ours)

Retire DA's `phone-notify` channel. The Muse app already reads phone
notifications via the pairing and pushes them to Muse; the fleet's
consumers (pending-replies ledger, shade management, drains) already
read that stream. DA's duplicate forward is pure redundancy — and it
was the dangerous one: its self-notification loop burned the account KV
write cap two days running (2026-11-09 and 2026-11-10; live proof
2026-11-10 ~05:42Z: KV PUT probe → 429, code 10048; notify queue frozen
at 500 items, last write 2026-11-10T00:38:14.649Z; every KV-putting
Worker handler has returned HTTP 500 / error 1101 since).

Path of a notification under this design:
app posts notification → Android notification service → **Muse app
(paired) reads it** → Muse platform → the agent's existing
phone-notify consumers. No Worker, no relay, no DA involvement.

Interim step (no build needed): Jonathan turns the Notifications push
channel OFF in DA's in-app toggles (his alone to flip). The flood and
the cap burn stop being able to recur from DA.

### 2. Texts / calls / calendar (read side) → the pairing pipe too

Same surface: the pairing already carries reading/sending texts, call
history, and cached contacts/calendar (the `device_data` lane reads
Muse's paired-device cache). DA's `phone-messages`, `phone-dialer`,
`phone-calllog`, `phone-calendar`, `phone-contacts` observer channels
duplicate platform data and retire with the same future build. (DA's
calendar *observer* payloads add nothing the calendar connectors don't
already hold.)

### 3. DA-unique structured lanes → one minimal residual rendezvous

What no platform surface carries:

- the **state report** (mode ground truth + parse verdict, shell_bridge
  state, the **ADB endpoint** the self-healing install lane reads,
  actuation shadow record, push_channels state),
- the **command channel** (enqueue/fetch/result — Muse → phone verbs),
- the **mode desired/ack loop** (`/mode-state`, `/mode-ack`).

The consumer of these is the agent, which does not live on the phone;
the Muse app offers no local ingest to receive them on the agent's
behalf. **The minimal residual piece is therefore exactly one small
endpoint serving report + command + mode-state — a rendezvous, stated
plainly, not dressed up as serverless.** Everything else in this design
is server-free; this lane cannot be, because the bits must cross from
the phone to an off-phone consumer and no on-device hand-off exists.

Form factor, given the cleartext proof: the endpoint must present
**https** to the current app build. Cheapest honest home: the existing
relay code (`relay/server.js`, handlers already ported 1:1) behind
**Tailscale Serve** on the box under its `*.ts.net` name (real TLS cert,
tailnet-only), with the Worker directive re-pointed at the https base.
The state store is a local file store — no KV, no daily cap, no
Cloudflare. If a future DA build adds a scoped cleartext exception for
the tailnet, the plain `http://100.85.53.70:8792` relay works as-is;
the https form needs no app change and is preferred.

Rejected alternatives, with reasons:
- DA → Muse app via Android share intents: user-mediated, per-item,
  no automation contract — not a data pipe.
- DA report disguised as a notification (read back via the pairing
  pipe): lossy, racy, abuses the notification stream, breaks the
  command/response direction entirely. A hack, not a design.
- DA → email (Muse Mail): a server by another name, plus credentials
  on-device. Out.
- Loopback/on-device agent endpoint: the agent does not run on the
  phone; nothing to connect to.

## What changes where

- **Device Assistance (future build — spec only, not built here):**
  (i) delete the push-observer forwarding entirely (channels 1–2 above)
  or ship them hard-off and unlisted; the self-notification class dies
  with the code path, not with a filter. (ii) If the residual endpoint
  is http: add a `networkSecurityConfig` permitting cleartext **only**
  to the endpoint's tailnet IP; if https (preferred): no manifest
  change. (iii) Point the compiled-in default base at the residual
  endpoint; (iv) disable the 24 h probation fallback to the Worker pair
  once the Worker lanes retire (a fallback to a dead base is a trap).
  Note: v1.10.0 as built carries none of this — its PiP work is
  unaffected, but it cannot complete a transport switch to an http base.
- **Muse app / phone side:** nothing. No API exists to change; the
  pairing already carries its half. Jonathan's one interim tap: DA
  Notifications channel off.
- **Worker:** `/device-push`, `/phone-bridge/*`, `/mode-state`
  phone-facing lanes retire once (a) the platform pipe is the sole
  notification source (already true de facto tonight) and (b) th
...[truncated 2705 chars]
## ERRATA + SPEC CORRECTION (2026-11-10, premise check — supersedes §1's duplication claim)

The claim above that DA's notification forwarding "duplicates a
stream the fleet already consumes" was verified against live stores
and is REFUTED: device-push-drain is the sole notification intake
since 2026-11-09 (the pairing-era phone-notify-drain hook was
deleted that day); it last ledgered any DA item 2026-11-09T17:41:28Z
and its notify staging is empty; pending_replies' newest item
(2026-11-09T15:05:56Z) came from the deleted drain. The Muse app's
"From this device" catalog is PULL (Muse-initiated device.invoke
round-trips), exactly as Jonathan stated — it feeds no notification
ledger. Jonathan's corrected spec (2026-11-10 00:53–00:55 EST)
therefore governs: channels KEPT; loop fixed at the source by
ignoring always-running notifications (+ self-package exclusion);
data path local-first with a tailnet-https bridge as worst case.
Shipped in v1.11.0 — see docs/v1.11-build-report.md for the tier
outcome and the residual-gap statement.
