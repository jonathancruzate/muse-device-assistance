# Device Assistance — Wave 2 scoping (2026-11-09)

Parent: tracked item `goal_f5284ed781eb` (Device Assistance
capabilities build-out), green-lit in full by Jonathan 2026-11-09.
Source: `docs/device-assistance-capabilities-brainstorm-2026-11-09.md`
§3 (remainder), §4 (`watch`), §6. Wave 1 covered §1, §2, §4 (`buds`
finish + `shield` namespace), §5, §7, and the §3 connector checklist
(sibling task — the `voice_mode` entry in
`custom.device-assistance/CONNECTOR.md` now carries it; this doc does
not touch the connector).

**Design only.** No code, no cron changes, no connector edits in this
pass. Where a build step belongs to the parent chat or a later wave,
it says so.

---

## 1. Voice-channel systematization (brainstorm §3, remainder)

Premise (already true, not re-scoped): `voice_mode` is LIVE in
Device Assistance v1.5.2 under Jonathan's 2026-11-08 discretion grant,
with the SPEAK-FIRST rule and the main-chat bridge procedure (a
session fired from any non-main chat must be bridged by a user-role
relay message in the main chat, which owns the session and speaks
first). What's missing is a *routing policy*: which existing
escalation paths try `voice_mode` before their current channel, and
what a spoken brief must contain so any path can generate one.

### 1.1 Paths that should prefer `voice_mode`

**(a) Blocked-on-Jonathan heartbeat calls — migrate first.**
Today: `HEARTBEAT.md`, "Blocked-on-Jonathan calls" (his order,
2026-11-03) — a genuine needs-Jonathan block (needs_human, exhausted
failure, urgent user-facing item) places ONE agentring call per
dedup-key, ever; never in Night mode; a purely informational flag
never calls.
Migration shape: insert a channel-choice step before the agentring
call, driven by the Wave 1 context engine (fresh Device Assistance
report):

- Prefer `voice_mode` when ALL hold: latest report ≤15 min old ·
  `screen_on` true · house mode is not Night · foreground is not a
  call app, meeting app, or camera (per the context-engine channel
  rules in `local-proactive-intervention`) · the report's
  `shell_bridge` shows `voice_enabled:true`.
- Fall back to the agentring call when ANY hold: report stale or
  absent · screen off · `voice_mode` returns
  refused/no-op twice · the session opens but he does not engage
  within the engagement window (§1.3, open question) · the matter
  needs the agentring brain's consult tiers (deep memory Q&A about
  the blocked work — `voice_mode` reaches the main chat, which has
  full context, so this should be rare).
- **Invariants that must not change:** one contact attempt per
  dedup-key, ever, *across both channels* — the heartbeat record's
  notes gain a `channel:` field beside the existing `called:<utc>`
  stamp, and a `voice_mode` attempt consumes the one call exactly as
  an agentring ring does · Night mode blocks both channels, no
  exceptions · a missed/unanswered attempt is never consent ·
  informational flags still never call on either channel.
- Owner: heartbeat procedure text is main-agent-owned; the parent
  chat edits `HEARTBEAT.md` + the `local-agentring` skill's
  blocked-call paragraph (add "check voice-first per the routing
  policy" pointer) when this migrates. Gate: 3 supervised trials
  (main agent fires, confirms speak-first + bridge landed, reports
  back) before the heartbeat worker applies the preference
  unsupervised.

**(b) Morning ramp-up spoken brief.**
Today: the ramp-up is a text digest consumed in the main chat
(overnight results, needs-you items, day shape); nothing speaks it.
Migration shape: on mornings where the day's first movement is
on-foot or transit — office days (Tue/Thu/Fri, in-office 10:30) and
any day whose first calendar block is away from home — the main
agent offers the ramp-up as a spoken brief via `voice_mode` *from
the main chat* (no bridge needed; the main chat owns its own
session), generated from the same consolidated ramp-up content as
the text version (one source, two renderings — never a separately
researched brief). Desk-first mornings stay text-only. The text
digest is still posted in full; the spoken brief is its headline
layer (§1.2), never a replacement that hides detail.
Owner: main agent (ramp-up is a main-chat behavior, not a job).
Gate: Jonathan's reaction on the first 2–3 spoken ramp-ups decides
whether the day-shape rule stands or narrows.

**(c) Bedtime wind-down spoken brief.**
Today: the Wind Down calendar block (30–45 min, directly before
Sleep) is silent, and the overnight-job ritual asks its three
questions by text in the main chat (~23:30 ET,
`overnight-job-bedtime-ask`, procedure in `local-overnight-job`).
Migration shape: during Wind Down, when the phone is in hand
(screen on, home mode, not Night yet), the bedtime ask may open as
a spoken brief: Muse speaks the day-close summary + the three
overnight-job questions; his spoken answers land in the main chat
natively (the voice session *is* the main chat), and the existing
confirm-back restatement still happens before he sleeps — by voice
if the session is live, else text as today. The text ask remains
the fallback and the default on nights the phone is idle.
Owner: main agent + the `overnight-job-bedtime-ask` job body gains
one pointer line (parent chat edits; the job's questions and limits
do not change). Hard bound carried from the skill: never wake him —
a spoken brief is only ever started while he is demonstrably awake
and holding the phone, inside Wind Down, never after Sleep starts.

**Explicitly NOT migrated:** inbound "call Muse" (the agentring-for-
Muse handset — a different surface; he calls it, it does not become
`voice_mode`), business calling (agentring is Jonathan-only by rule;
business calls keep their own lane), and the departure copilot's
spoken opener (Wave 1 §2 work — it consumes the brief spec below but
its routing is already scoped there).

### 1.2 What a spoken brief needs (shared spec, all three paths)

1. **Speak-first opener** — identity + reason in one sentence
   ("It's me — you're blocked on X" / "Morning — here's your day"),
   per the SPEAK-FIRST rule. No silent open session, ever.
2. **Length budget** — ≤~90 seconds spoken: one headline, at most
   three facts/decisions, one closing line. Anything longer is a
   phone call, not a brief, and belongs on agentring or in text.
3. **Structure for action paths (a):** what is blocked · the one
   thing he needs to do or decide · what happens once he does —
   the same three beats the agentring skill already requires of a
   blocked call, so a brief and its fallback call carry identical
   content.
4. **Structure for digest paths (b/c):** headline outcome first
   (overnight job result / day shape), then needs-him items by
   urgency, then the single next anchor (leave-by, first meeting,
   Wind Down/Sleep times). No full enumeration — the text version
   holds the complete list and the brief says so.
5. **Grounding** — briefs are rendered from the same consolidated
   source as their text counterpart (heartbeat record, ramp-up
   digest, day-close state). No facts originate in the brief; a
   number or name not in the source does not get spoken.
6. **Close-out** — if he engages, the conversation is his. If he
   doesn't, Muse ends the session explicitly (`voice_mode` off)
   after the brief; never leave a session open as an ambient
   channel. The attempt, channel, brief text, and outcome
   (engaged / no-engage / refused) are logged to the owning record
   (heartbeat failure record, daily log) the same handling.

### 1.3 Migration steps + owners (summary)

| Step | Owner | Gate |
|---|---|---|
| Adopt this routing policy + brief spec into `local-muse-device-assistance` skill (voice section addendum) | Parent chat | — |
| (a) `HEARTBEAT.md` channel-choice step + `channel:` note field; agentring skill pointer line | Parent chat (main-agent-owned files) | 3 supervised trials before unsupervised use |
| (b) Main-chat ramp-up: day-shape rule + spoken rendering from the text digest | Main agent habit, no job edit | Jonathan's reaction after 2–3 runs |
| (c) `overnight-job-bedtime-ask` body: one pointer line to the spoken form + in-hand preconditions | Parent chat (cron edit) | Text ask stays default until spoken form proves out |
| Engagement-window + no-engage logging convention (§1.2.6) applied uniformly | Whoever runs the path | — |

**Open questions for Jonathan (do not block scoping):** the
no-engagement window before fallback/close-out (suggest 60 s for
(a), end-of-brief for (b/c)) · whether (c) should *replace* the text
ask on in-hand nights or always be additive · whether (b) extends
to Stephany-relevant mornings (shared plans) or stays his-only.

---

## 2. Watch plugin — the `watch` namespace (brainstorm §4)

### 2.1 What already exists (build on it, don't duplicate it)

**Muse for Wear OS** (`~/workspace/muse-wear-os/`, skill
`local-muse-wear-os`): the standalone Galaxy Watch 8 Classic app
(Galaxy Watch 8 Classic, Fi LTE), package
`com.jonathancruzate.metamusewearos`, v1.0.1. The watch talks to
Stef through the `muse-watch` Cloudflare Worker (watch POSTs
`/ask`, polls `/answer/:id`; the `muse-watch-poll` hook wakes a
worker per question). Two facts govern everything below:

- **Standalone is the product.** The app must work with the phone
  off, away, or unpaired. Build guard **G2 fails the build if any
  Wear Data Layer / GMS wearable reference enters the dex**; the
  skill's guardrail is explicit: never add a phone companion or
  Data Layer sync to "improve" it — new features ride the watch's
  own network or on-watch APIs.
- The Device Assistance plugin model (Buds) routes a namespace
  from the *phone host* to a *plugin APK on the phone* over the
  Worker command channel. A watch is not reachable that way: there
  is no on-phone endpoint for it. The namespace design has to
  bridge that gap without breaking G2.

### 2.2 The transport question — three options, one recommendation

**Option A — Worker-mediated (recommended).** Extend the existing
bridge pattern, exactly as the Shield TV profile did when it got
its own `shieldbridge:` queue + report identity in the mode-sync
Worker beside the phone's. The watch app gains a plugin client
alongside its ask/answer lane: it polls a `watch` command queue on
a Worker and posts `watch` reports (sleep/health summaries, verb
results) back. The Device Assistance host registers the `watch`
namespace and gains a WatchRouter (the BudsRouter analog) that
enqueues commands to that queue instead of firing an on-phone
plugin intent; results return via the watch's report posts, read
by Muse server-side like every other bridge report.
- Pros: G2 untouched (plain HTTPS from the watch, its own network —
  the same shape as the existing `/ask` lane) · standalone property
  preserved and provable · reuses two proven bridge patterns
  (muse-watch + shieldbridge) · works with the phone off — the
  host's role is enqueue/read, not transport.
- Cons: command latency is poll-bound (suggest a 60 s watch poll
  while awake, event-driven posts for sleep/health; find-my-watch
  is latency-tolerant, nothing in v1 is urgent) · watch battery
  cost of polling, bounded by the awake-only cadence · two Worker
  surfaces to keep consistent (queue home: open question, §2.5).

**Option B — Wear Data Layer + new phone companion.** Real-time,
but it requires a phone companion APK and a watch module talking
Data Layer — a direct G2 violation for Muse for Wear OS. It could
only ship as a *second, separate* watch app, splitting the watch
identity in two and adding a companion to maintain, to buy latency
no v1 verb needs. **Rejected as the primary;** revisit only if a
future verb needs sub-second delivery.

**Option C — BLE direct from a phone plugin APK.** No supported
API gives a custom data channel to a Wear OS app over BLE without
the Data Layer. **Rejected.**

### 2.3 Namespace + verb sketch (`watch_*` allowlist templates)

Same discipline as the existing channel: allowlisted templates
only, validated args, one command per fetch, results verified by
report/read-back, never free-form.

| Template | Args | Returns | Notes |
|---|---|---|---|
| `watch_status` | — | battery, charging, connectivity, last-report age, app version | The liveness/read-back verb, like `battery` on the phone |
| `watch_sleep_get` | optional night date | sleep onset, wake, duration, stage summary (light/deep/REM as available), worn flag | The bedtime-goal feed (§2.4) |
| `watch_health_summary` | — | resting HR, HR range today, steps, SpO₂ if exposed | Summaries only — no raw sensor series leaves the watch |
| `watch_notify` | text ≤120 chars | posted ack | Muse-initiated wrist tap; title fixed "Muse". Distinct from the ask/answer reply lane (that answers *his* questions; this is Muse reaching out — same discretion rules as other proactive surfaces) |
| `watch_find` | — | ack + dismissed ts | Sound/vibrate pattern until dismissed — find-my-watch |

Deliberately out of v1: any settings write, alarm setting, reply
composition, raw health export. Read + notify + find is the whole
surface until the reads prove trustworthy.

**On-watch data sources:** Wear OS Health Services / the watch's
own sleep tracking for onset + stages; sensor summaries for HR/
steps. What the watch knows that nothing else does: *worn* state
and sleep onset at the wrist, in real time, phone or no phone.

### 2.4 What it feeds — the bedtime-prediction goal

Goal `goal_8896372386b1` (Predict my bedtime, automate sleep mode)
Phase 1 scores predictions (`bedtime_predict.py`,
`predictions.jsonl`) against an actual defined as the first phone
"Sleep" mode detection each night (`actuals.jsonl`). The phone
actual is a proxy — Sleep mode engages when the routine fires, not
when he falls asleep. The watch's sleep onset is the better ground
truth the goal has been missing. Feed design:

1. Nightly (morning-after), `watch_sleep_get` for the night just
   ended is recorded as a *candidate* actual (`source: watch`)
   alongside the phone actual, dual-logged — prediction scoring
   unchanged.
2. After ~2 weeks of dual logging, the goal compares both actuals
   against predictions; if the watch actual is consistently
   tighter/more plausible (e.g. phone mode firing an hour after
   watch onset), the goal — not this plugin — decides to switch
   the primary actual source. The plugin's job ends at delivering
   trustworthy data with a `worn` flag (an unworn watch reporting
   "sleep" is a discarded night, and the flag is what makes that
   call possible).
3. Explicit non-goal for v1: live "he's asleep" signaling into
   Night-mode actuation. The goal's Phase 2 bundle has its own
   gates and history; a live watch-asleep feed is a future input
   to *that* decision, not something this plugin wires up.

Also noted, not scoped: Health Connect already syncs watch-derived
aggregates to the phone on its own daily cadence — the plugin must
not duplicate those aggregates as a second source of truth. Its
marginal value is onset precision, worn state, and the command
verbs. If the §2.6 spike finds Health Connect already carries
trustworthy onset, `watch_sleep_get` narrows to worn-state +
onset-delta only.

### 2.5 Jonathan's on-device steps (complete list)

- Keep wearing the watch overnight (the obvious one — an unworn
  night is no data).
- Grant Health Services / sensor and notification permissions on
  the watch when the v1.1 build first asks (one pass, on-watch).
- Any Samsung Health data-sharing toggle the spike (§2.6, step 1)
  identifies as gating sleep-stage detail — named exactly when
  known, done by him on the watch/phone; Muse never drives that
  UI.
- Nothing else: install rides the existing Wi-Fi ADB lane (the
  watch already has developer options + Wi-Fi debugging from the
  v1.0.x installs), and no phone-side setup exists in Option A.

### 2.6 Build order

1. **Spike (evidence first):** what sleep/health data the current
   watch + Health Connect already expose — read the Health Connect
   coverage for sleep sessions, and probe what a standalone watch
   app can read from Health Services on the Watch 8 Classic
   without a phone companion. Output: a short findings note that
   fixes the verb payloads above (esp. whether stages exist).
2. **Worker routes:** `watch` command queue + report endpoints
   (home decided in step 1's note — extend `muse-watch`, whose
   watch-facing auth already exists, or mode-sync beside
   `shieldbridge:`; recommendation: `muse-watch`, it is already
   the watch's Worker) + a simulated-client round-trip proof.
3. **Watch app v1.1:** plugin poller + `watch_status` + report
   posting added to Muse for Wear OS; build guards re-run (G2 is
   the proof the transport stayed legal); install via Wi-Fi ADB;
   live round-trip: VM enqueue → watch executes → report read
   back.
4. **Sleep read:** `watch_sleep_get` + the nightly candidate-
   actual dual-logging into the bedtime goal's `actuals.jsonl`
   shape (the goal's own files; change made under that goal, not
   this one).
5. **Notify + find:** `watch_notify`, `watch_find`, each
   live-verified (notification seen on wrist, find dismissed on
   watch) before the label comes off.
6. **Host + docs:** WatchRouter + allowlist templates in Device
   Assistance (new phone build, versioned like v1.5.x), `watch.*`
   verbs documented in the Device Assistance connector, and the
   `local-muse-wear-os` + `local-muse-device-assistance` skills
   updated in both copies; GitHub exports upstreamed in the same
   handling, per the standing rules for both projects.

**Open questions:** Worker home for the queue (above) · poll
cadence vs battery on LTE-only days · whether sleep *stages*
survive the standalone path or only onset/wake do (spike decides)
· `watch_notify` overlap ettiquette with the muse-watch reply lane
(both can tap the wrist; the discretion rules need one shared
paragraph before step 5).

---

## 3. Weekly Device Assistance health read (brainstorm §6)

### 3.1 The sweep's current home — found

The existing device-care surface in our own automations is
**Tending §13, the Phone Readiness Audit**, inside the
`nightly-automations-review` cron (daily ~01:22 ET, owner
`goal:nightly-hygiene`; procedure: `local-tending` skill +
§13 of the job body). §13 already runs a deterministic script leg
nightly — `~/workspace/phone-toolkit/phone_audit.py`, writing
`~/workspace/phone-toolkit/state/latest-audit.json`, diffed against
the previous night — and it **already has a weekly leg**: the
`phone_settings_snapshot.sh` settings snapshot, run weekly with
§12. §13 is also where the v1.4.2 crash loop would have surfaced
(it audits the bridge's grants, standby bucket, and latest report
`parse_ok`) — it is the natural home; the DA health read becomes
§13's second weekly leg.

Considered and rejected: `device-health-sweep` (daily 09:00,
`goal:manage-my-smart-home`) — despite the name it sweeps
*smart-home* offline devices, not phone apps. Its pattern
(script-owned state + dedup, quiet unless an alert line) is worth
mirroring, but the job itself is the wrong home.

### 3.2 The section to add — exact placement

- **Job:** `nightly-automations-review` body, §13 — one added
  paragraph: "WEEKLY (with §12, alongside the settings snapshot):
  run the Device Assistance health read…". **No new cron**; the
  weekly leg rides the existing Sunday-night weekly cadence, and
  the parent chat makes this edit.
- **Skill:** `local-tending` §13 row/procedure gains the same leg
  (executor: worker shell script leg, like `phone_audit.py` —
  deterministic, zero-model; classification of any finding is agy
  economy, per §13's existing routing row).
- **Script (to build):** `~/workspace/phone-toolkit/da_health_read.py`,
  sibling to `phone_audit.py`, writing state to
  `~/workspace/phone-toolkit/state/da-health-latest.json` +
  keeping the prior week's file for the diff.
- **Reads, per device** — phone now; Shield added the moment a
  `shieldbridge:report` exists in the Worker KV (Wave 1's Shield
  reporting-node work is the dependency). Until then the Shield
  half emits a single `shield: not reporting yet (known gap)` line
  that is never counted as a defect:
  - **Crashes:** enqueue the Worker `dropbox_print` template
    (singly — the channel executes one command per fetch) filtered
    to `com.jonathancruzate.metamusedeviceassistance` and, once
    SPP-verified, `com.jonathancruzate.metamusebuds`: crash count
    in 7 d, distinct signatures, first/last timestamps.
  - **Report freshness:** latest `phonebridge:report` age at read
    time; `parse_ok` current value and streak (as §13 already
    samples nightly — the weekly read records the week's worst
    gap *as observed by the nightly §13 runs*, see §3.3 note).
  - **Self-heal events:** week-over-week deltas from the report's
    `adb` object (endpoint changes count; `wireless_debugging`
    recoveries) — deltas, not the on-device `BridgeLog` (no
    off-device read of it exists; inventing one is out of scope).
  - **Tailscale:** `down_count` delta for the week, current
    `down_since`/up state, `last_recovered_ms` sanity.
  - **Shell bridge:** `paired` / `self_test_ok` / `on` as
    reported; `voice_enabled` / `voice_active` consistency — a
    `voice_active:true` persisting across two consecutive weekly
    reads with no session is the v1.5.2 desync recurring, and is
    named as such.
  - **Version:** installed versionCode (from the report/audit)
    vs the newest built version in the project — a phone running
    behind the latest build for >7 d is a finding.
  - **Shield mirror fields:** the same freshness/version/service
    set from `shieldbridge:report` once it exists.

### 3.3 Surfacing rule — silent unless a defect persists

Mirrors §13's and `device-health-sweep`'s discipline: the read
always writes state; it speaks only on a defect.

- **Silent** (state file + one daily-log line only): a clean week,
  or a first-week minor anomaly (a single non-BridgeService crash,
  one endpoint change, one Tailscale episode that recovered).
- **Surfaces into the Tending findings ledger** when either: the
  *same defect signature* appears in **two consecutive weekly
  reads**, or a single read is severity-high — BridgeService crash
  loop (≥5 crashes/7 d), unexplained report silence >24 h,
  `self_test_ok` flipped to false, `parse_ok` false at read time.
- **Needs-you filing** only for a surfaced defect that needs
  Jonathan (a permission lost that ADB cannot re-grant, a build
  that must be reinstalled by hand), dedup-key
  `da-health:<signature>`; the job never messages Jonathan
  directly — Tending's normal reporting carries it.
- **Recoveries are logged, never alerted** (a defect present last
  week and absent this week gets one line in state + the log).
- **Un-read is not zero:** if the Worker channel is unreachable,
  the crash section records `unread` and only the report-derived
  fields run; an `unread` crash section two weeks running is
  itself a channel-health finding. Absence of evidence is never
  reported as "no crashes."

**Note on freshness history (§3.2):** the Worker KV holds only the
*latest* report, so a true 7-day gap census needs a sampler that
doesn't exist. V1 accepts the honest subset: freshness at read
time + nightly §13 observations + counter deltas (Tailscale
down_count, endpoint changes). If that proves too thin, a
report-sampler is a separate scoped build, not a silent addition.

**Dependencies / sequencing:** phone half is buildable now (all
templates live in v1.5.2) · Shield half waits on the Wave 1
Shield reporting node · the buds package joins the crash filter
after the Wave 1 SPP verification lands. Parent chat owns the
cron-body edit + the go-live call; this section is the spec it
edits from.
