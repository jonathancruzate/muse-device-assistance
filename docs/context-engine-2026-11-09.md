# Context engine — encoding note (2026-11-09)

Jonathan green-lit the Device Assistance build-out on 2026-11-09,
including brainstorm §1 (context engine) and §3 (voice_mode as the
standard outbound voice channel), from
`docs/device-assistance-capabilities-brainstorm-2026-11-09.md`.
This note records what was encoded where. The rules themselves live
in their owning documents — this note is a map, not a second copy.

## What was encoded where

**1. Judgment rules → `local-proactive-intervention` skill, new
"Context engine — Device Assistance live context" section.**
That skill is the judgment home (it already owned surfacing /
channel judgment), so the four brainstorm §1 rules are encoded
there as binding judgment, on top of its existing rules 1–3:

- **Freshness first** — a report is current context only inside
  ~15 min (heartbeat) / 30 min (ground truth); older is *unknown
  state*, never stale-but-usable, and none of the rules below may
  be applied on an old report.
- **(a) Channel choice** — foreground = call app / camera /
  meeting app → text silently, never self-activate `voice_mode`;
  foreground = Maps/navigation AND media playing (driving
  signature) → voice-first, short turns.
- **(b) Interruptibility** — DND on + Night mode + screen off =
  urgent-only surfacing; screen on + Home = normal. Encoded as an
  observed-state reading, explicitly *not* a reinstated
  quiet-hours window (the 2026-11-07 fresh start stands).
- **(c) Pre-flight** — before heavy phone work (updates, photo
  work, long ADB sessions, bulk transfers), read the fresh
  report's battery / network / storage; a dying, offline, or full
  phone stops the task at the pre-flight with the reason named.
- **(d) Phone-forgotten** — heartbeat stale >30 min while GLS
  shows Jonathan away from home / moving → treat the phone as
  left behind: house channels, not phone channels; no
  `voice_mode`; Daisy-alone / reachability reasoning proceeds on
  "phone home without him." Stale heartbeat while he's home is
  bridge health, not this rule.

The section closes by stating the engine informs judgment and
grants no new authority — the approval boundary is unchanged.

**2. Fetch mechanics → `local-muse-device-assistance` skill, new
"Reading live context" pointer** (in "Checking state", both the
installed copy and the in-repo project copy — the skill's own
same-document rule). It owns only the mechanics: latest Worker
report first (age before content), `screen_foreground` fired
**singly** through the Worker channel with result read-back, a
media read to complete the driving signature — and points at the
proactive-intervention Context engine section as the rules' only
home. No rule text is duplicated.

**3. Voice-channel checklist → Device Assistance connector,
`voice_mode` entry.** A six-step routing checklist: pick the
channel by fresh phone state (`voice_mode` preferred when the
phone is in hand — screen on, not in a call; **agentring is the
fallback** when idle / locked / in a call / unengaged; MacroDroid
TTS stays deprecated) → fire `on` singly → bridge to the main chat
as a user-role relay message whenever another chat fired it →
speak first → confirm via command result / `screen_foreground`
(`com.facebook.aura`) / the report's `shell_bridge` voice fields.
The full discretion grant, speak-first rule, and voice-bridge
procedure remain in the companion skill's voice section; the
checklist references them rather than copying them.

## Manual judgment vs automatable later

**Encoded as judgment today (manual by design):** all four
context rules and the voice-channel pick are Muse-side judgment
exercised at decision time. There is no new automation, hook, or
poll behind them — the report and foreground reads happen when a
decision needs them. That matches the proactive-intervention
skill's post-2026-10-10 shape (judgment, not scoring machinery)
and the fresh-start removal of standing noise rules.

**Automatable later, not built here:**
- A single helper that returns the fresh report + foreground as
  one context bundle (today: two reads, by hand, in order).
- GLS corroboration for the phone-forgotten rule is a manual
  cross-check against `gls-state.json` today; a combined
  stale-heartbeat + away-from-home detector could be a hook, but
  only if the manual rule proves it fires often enough to earn
  one — and any hook would need the midnight-anchored grid gate.
- The driving signature currently needs a media read to confirm
  playback; if the report ever carries media state, the signature
  becomes one read.

**Not encoded:** brainstorm §1's "pre-flight before heavy phone
tasks" examples stop at reads — no battery/storage thresholds are
hardcoded; "dying / offline / full" stays a judgment call against
the actual task, per the owning skill's anti-formalism stance.

## Upstream status

The skill and connector edits are local. Per the build-out
coordination, their upstream backup push (private `muse` repo for
the skills/connector, project repo snapshot for the in-repo
SKILL.md copy and this note) is batched by the parent — owed, not
done, as of this note.
