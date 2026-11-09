# Departure Copilot — procedure (2026-11-09)

**Status: BUILT, SHADOW MODE ONLY.** The driver exists and is
shadow-tested; it has never fired a live sequence. Going live is a
separate, gated step — see "Conditions for going live" below.

Source: brainstorm §2 ("Departure & errand copilot"),
`docs/device-assistance-capabilities-brainstorm-2026-11-09.md`.
Green-lit by Jonathan 2026-11-09; tracked as
`goal_bd2e775ce85a` under the Device Assistance capabilities
build-out (`goal_f5284ed781eb`).

Driver: `tools/departure-copilot.py` (stdlib-only Python; transport
is the proven `phone-bridge-cmd.sh` Worker driver plus a read-only
GET of the latest bridge report).

## The sequence

At a departure, in order, **one Worker command at a time** — the
app executes one command per fetch, so each command is enqueued
singly and its posted result is awaited before the next is enqueued
(the connector's SINGLY rule):

| # | Step | Template | Proof of success |
|---|------|----------|------------------|
| 0 | Pre-flight (live only) | `screen_foreground` | result ok; feeds the call guard |
| 1 | Navigation | `nav_open <destination>` | result ok **and** a `screen_foreground` read-back showing `com.google.android.apps.maps` in focus |
| 2 | Media resume | `media_play_pause` | result ok — **only fired when media evidence says `paused`** (see below) |
| 3 | Driving DND | `dnd_on` | result ok **and** a `settings_get global zen_mode` read-back ≠ 0 (when the read-back parses) |
| 4 | Voice opener | `voice_mode on` | result ok ("dispatched", or "already active" against a genuinely live session — trustworthy since v1.5.2) **plus** the bridge message sent (below) |

A fire is never a success on its own (verified-ack discipline).
Any failed step **aborts the chain**: nothing after it is enqueued,
the abort is logged with the failing step and reason, and the exit
code says where it stopped.

## How the calendar leave-by ladder invokes it

The calendar-event-stream / leave-by ladder already computes the
leave-by moment for an event. At leave-by, the invoking agent:

1. Resolves the destination to **one token** (see the token rule
   below) from the event's location — e.g. `Flushing,Queens`,
   `Astoria`, a venue's single-token locality. If the location
   cannot be reduced to one honest token, the sequence does not
   run; say so instead of guessing.
2. Reads media evidence itself, if it has a lane for it (e.g. a
   media-session / Spotify read), and passes `--media-state
   paused|playing`. No evidence → omit the flag → the media step
   is skipped and logged (`media_state_unknown_no_blind_toggle`).
   `media_play_pause` is a *toggle*: firing it at playing media
   would pause it, so unknown always means skip.
3. Runs:
   `tools/departure-copilot.py <token> [--media-state …] [--opener "…"]`
   — shadow by default. `--opener` overrides the composed opener
   (use it to add the one thing to grab: "…and your gym bag is by
   the door").
4. **Live runs only:** the instant the script exits 0, the caller
   sends the bridge message the script printed (see Voice bridge)
   and reports the run's outcome from the script's JSON record —
   never from the fact that commands were enqueued.

The reverse shape on arrival (foreground flips home + mode ack
confirms Home) is *not* built; arrival detection belongs to the
context-engine item.

## The destination token rule

`nav_open` takes exactly one validated token — the on-device
ShellAllowlist `DEST_TOKEN` pattern
`^[A-Za-z0-9][A-Za-z0-9,.'\-_]{0,63}$`. Spaces, `&`, and `=` are
not in the charset at all, so a destination with spaces or a
smuggled second URI parameter is unrepresentable. The driver
mirrors the same rule set-for-set and rejects anything else
(exit 2) before touching the transport.

## Guards (fail-closed, both modes)

- **Stale report → refuse.** The latest bridge report (anchored on
  the Worker's `received_at`) must be ≤ 30 minutes old; older
  means phone state is unknown (connector: ground truth > 30 min
  is stale evidence). Exit 3 live; shadow logs the refusal.
- **Call in progress → refuse (live).** The pre-flight
  `screen_foreground` must not show telephony in-call UI, a dialer,
  or a messaging app's call activity. Shadow mode cannot read the
  foreground without enqueueing a command — and shadow enqueues
  nothing — so the shadow log records the check as *deferred to
  the live pre-flight*, never as passed.
- **Voice gate (live).** If the fresh report's
  `shell_bridge.voice_enabled` is not true, the voice step fails
  (the module would refuse the fire anyway), the run ends as
  `completed_without_voice` (exit 4), and there is **no bridge
  message to send** — the script prints that explicitly.

## Shadow vs live

| | Shadow (default) | Live (`--live`) |
|---|---|---|
| Enqueues | **nothing, ever** | the sequence above, singly |
| Reads | latest report (GET), pending queue (GET) — both read-only | same, plus the pre-flight/read-back commands |
| Voice | logs the opener + bridge message that *would* be used | fires `voice_mode on`, prints the bridge message |
| Log | goal `hidden_files/shadow-log.jsonl` | goal `hidden_files/live-log.jsonl` |
| Also records | pending queue before/after + `nothing_fired_verified` | per-step command ids, results, read-backs |

## The voice bridge (required in live mode)

Voice activation always lands in the **main chat**, not the chat
that fired it (skill: VOICE BRIDGE, 2026-11-08/09). The main-chat
Muse owns the session and cannot know why it was called. So a
live Departure Copilot run is not finished when the script exits:

1. The script fires `voice_mode on` and, on success, prints the
   bridge message between `=== SEND THIS … ===` markers.
2. The caller **immediately** sends that exact message into the
   main chat **as a user-role relay message** (bracket-labeled
   automation origin, exactly like the Shield relay messages).
   Delivery form is decisive: an agent instruction via
   `chat.send_message` does **not** reach a running voice session
   (Jonathan's 2026-11-09 00:04 finding) — the bridge only lands
   when it enters the thread as Jonathan.
3. The receiving (main-chat) side speaks first, immediately, with
   the suggested opener (SPEAK-FIRST rule: a self-activated
   session is a call Muse placed; never an open silent session).

If the caller cannot perform step 2 in the same handling, it must
not run the sequence live — a fired voice step without its bridge
is the exact 2026-11-08 bug this procedure exists to prevent.

## Failure behavior

Exit codes: `0` complete / shadow plan logged · `2` invalid
destination · `3` guard refusal (stale report, call in progress) ·
`4` a step failed, chain aborted (including
`completed_without_voice`, where nav/media/DND landed but the
voice step did not) · `5` report/transport unavailable.

Documented DND fallback: the in-app `dnd_on` shell template is the
primary lane (live-verified 2026-11-08, zen_mode read-backs). The
MacroDroid delegated pair (`routine-control.sh on dnd`, per the
phone-automations skill) remains the fallback lane **for a human
or agent decision** — the driver never auto-falls-back mid-chain;
a failed `dnd_on` aborts, and the caller decides.

Known transport note (found in the 2026-11-09 shadow test): the
report GET must send a `User-Agent` header — the edge drops the
default Python-urllib UA (403 / connection reset). The driver
sends `muse-departure-copilot/1.0`.

## Shadow test record (2026-11-09, build night)

- `departure-copilot.py Astoria` → exit 0, outcome
  `shadow_plan_logged`. Report evidence: heartbeat received
  2026-11-09T07:19:16Z, age 168.6 s (fresh), observed mode Home,
  `shell_bridge {paired:true, self_test_ok:true, on:true,
  voice_enabled:true, voice_active:false}`. Media step planned
  `would_skip` (state unknown). Pending queue `[]` before and
  after; the log line carries `nothing_fired_verified: true`.
- `departure-copilot.py "New York"` → exit 2,
  `refused_invalid_destination`; queue still empty.
- A first pre-fix run (exit 5, `shadow_report_unavailable`, HTTP
  403) is also in the shadow log — the User-Agent transport note
  above came from it. The log is append-only audit; all three
  lines are well-formed JSON.
- Log: `~/workspace/goals/departure-copilot-chained-nav-media-dnd-voice-sequence/hidden_files/shadow-log.jsonl`

## Conditions for going live

ALL of the following, in order:

1. Jonathan's explicit go-ahead for live mode (his green-light on
   2026-11-09 covered building this in shadow; the first live
   firing is its own decision unless he says otherwise).
2. The caller is a chat that can and will send the bridge message
   as a user-role relay into the main chat **in the same
   handling**, immediately on the script's exit.
3. A supervised first run: Jonathan present/aware, a real
   departure with a one-token destination, and the run's JSON
   record + main-chat speak-first both verified afterwards
   (session observably started; opener spoken; nav/DND
   read-backs matched).
4. Guards green on the day: fresh report, `voice_enabled:true`,
   no call in progress — enforced by the driver, listed here so a
   refusal is read as the system working, not failing.

Phase 2 actuation is untouched by this work and remains DISARMED;
arming it is Jonathan's in-app decision only, and nothing in this
sequence needs it.
