# Device Assistance — capability brainstorm (2026-11-09)

Jonathan's ask (2026-11-09 01:55 EST): "brainstorm how we can use our new
device Assistance for muse capabilities."

Ground truth this builds on (connector doc, verified 2026-11-08):
v1.5.2 live on the S24+ — 60 s report loop (mode ground truth with
verified acks; battery/charging/power-save/screen/ringer/network/
storage; ADB endpoint + self-heal; Tailscale reviver state;
`shell_bridge` incl. `voice_enabled`/`voice_active`), a Worker command
channel with allowlisted templates (`app_launch`, `nav_open`,
`media_*` transport + volume, `dnd_on/off`, `screen_foreground`,
`battery`, `logcat_tail`, `dropbox_print`, voicemail role
borrow/restore, `settings_get/put`), gated native ops (`flashlight`,
`voice_mode` — LIVE, discretion grant + speak-first rule), a plugin
host bus (first plugin: Galaxy Buds `buds_*`, SPP verbs built but not
yet live-verified), and a TV profile of the same app on the Shield
(v1.5.0, observing locally; no Worker secret entered on the Shield
yet, so it posts no reports). Phase 2 routine actuation remains
DISARMED — Jonathan's in-app call, always.

## 1. Context engine — the phone as Muse's live "what is he doing" sensor
`screen_foreground` + the report's status set are a real-time context
feed no other lane gives:
- **Channel choice:** foreground = a call app, camera, or a meeting →
  text silently, never `voice_mode`; foreground = Maps/navigation +
  media playing → he's driving → voice-first, short turns.
- **Interruptibility score for proactivity:** screen off + DND on +
  Night mode = do not disturb for anything but urgent; screen on +
  home mode = fair game. Feed this into the proactive-intervention
  judgment instead of guessing from the clock.
- **Pre-flight checks before phone work:** battery/network/storage
  from the fresh report before any heavy phone task (updates, photo
  work, long ADB sessions) — stop discovering a dead phone mid-task.
- **Phone-forgotten detection:** report heartbeat stale + GLS shows
  Jonathan moving = he left without the phone → Daisy-alone and
  reachability logic changes (call the house, not the phone).

## 2. Departure & errand copilot — chained allowlisted verbs
The verbs compose into one-command sequences Muse can run at
leave-by time (calendar ladder already computes the moment):
`nav_open <destination>` → media resume → DND driving profile via
the existing lane → a `voice_mode` spoken "leaving now, ETA, one
thing to grab" opener. Same shape in reverse on arrival (foreground
flips to home apps + mode ack confirms Home). This is the first
place the extension pack stops being a demo and becomes a daily
driver.

## 3. `voice_mode` as Muse's standard outbound voice channel
The discretion grant (2026-11-08) + speak-first + main-chat bridge
rules are all in place; what's missing is systematizing when Muse
uses it instead of the agentring line or MacroDroid TTS:
- Blocked-on-Jonathan escalations that today ring the agentring
  could open a voice session first when the phone is in hand
  (screen on, not in a call) — agentring stays the fallback when
  the phone is idle/locked or he doesn't engage.
- Morning ramp-up and bedtime wind-down spoken briefs on days the
  calendar says he's walking/driving first, not at his desk.
- Every side chat that fires it must run the bridge procedure —
  worth encoding as a checklist in the connector doc's voice entry
  (it lives in the skill today).

## 4. Plugin roadmap — the Buds pattern, repeated
The host/plugin split is proven (routing + gates live-verified).
Next namespaces, in value order:
- **`buds` finish line (gate for everything else):** one SPP
  verification run with his Buds2 connected — status/battery, ANC,
  ambient volume, EQ, touch lock. Until that lands, every plugin
  claim rests on one unverified transport.
- **`watch` (Galaxy Watch / Wear OS):** sleep + health reads,
  wrist notifications, find-my-watch ping — the watch is the one
  device that knows sleep onset, which feeds the bedtime-prediction
  goal directly.
- **`shield` as a first-class namespace via the TV profile:** once
  the Worker secret is entered on the Shield (Jonathan's on-device
  step), the TV becomes a second controllable node — app launch for
  movie night, foreground reads for presence corroboration (TV on +
  foreground = living room occupied), media transport.
- **Not plugins:** Roborock, SmartThings devices, Home Link ESP32 —
  they already have working lanes; the plugin bus is for devices
  that live on the phone's radios (Bluetooth SPP/BLE, on-device
  sensors).

## 5. Phase 2 — the one big gated unlock
Night-mode routine firing without MacroDroid is built and disarmed.
The honest path: run the shadow-diff nights now (bridge verdicts
logged against the incumbent system's outcomes — zero risk, pure
data), bring Jonathan the agreement stats, and let him arm it with
evidence in hand. Until then MacroDroid stays the actuator and the
bridge stays the judge — which is already the valuable half.

## 6. Self-maintenance & diagnostics
`logcat_tail` + `dropbox_print` already proved themselves (the
v1.4.2 crash loop was diagnosed from dropbox records). Productize:
- A weekly Device Assistance health read (crash records, report
  freshness, self-heal events, Tailscale down-count) folded into
  the existing device-care sweep instead of ad-hoc discovery.
- The same read on the Shield instance once it reports.

## 7. Upstream angle — Device Assistance as a gadget-sdk candidate
The catalog (facebookincubator/muse-gadget-sdk) has per-device
skills for everything else Muse touches in this house, but
nothing for "the user's own Android phone as a Muse surface."
A scrubbed `gadget-android-phone` candidate — the verified-ack
discipline, allowlisted-template command channel, plugin-host
model — is a genuine upstream contribution and the natural next
PR candidate once the current five open PRs free a slot. The
verified-ack pattern ("a fire is never a success; only observed
state matching desired is") is the piece worth exporting even if
the app itself never leaves this house.

## Suggested order (needs Jonathan's picks to become plans)
1. Buds SPP verification run (small, unblocks the plugin story).
2. Shield Worker secret entry (his 2-minute on-device step) →
   Shield becomes a reporting node.
3. Context-engine rules wired into proactivity (channel choice +
   interruptibility) — pure Muse-side logic, no new phone code.
4. Departure copilot sequence on the calendar ladder.
5. Shadow-diff nights for Phase 2, stats to Jonathan.
6. `gadget-android-phone` upstream candidate drafted, held for a
   PR slot.
