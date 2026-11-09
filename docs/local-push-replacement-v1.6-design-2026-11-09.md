# Local push replacement — v1.6 design (2026-11-09)

Jonathan's orders, in sequence, 2026-11-09 09:34–09:41 EST:
1. Replace the rest of MacroDroid, locally, instead of relying on endpoints.
2. For notifications: turn them into a push activity instead of Muse
   having to pull notifications using tokens.
3. (Screenshot: Muse app "From this device" — Calendar, Contacts,
   Messages, Phone dialer, Notifications, Call Log, Health Connect)
   For everything there that is pull, use the app to turn it into
   push if possible locally as well.
4. With user control for each — turn each channel on or off.

Tracked home: `goal_0b4ebbb0ed33` (MacroDroid retirement — local push
replacement in Device Assistance), parent `goal_f5284ed781eb`.
Owner: Device Assistance build lane (this project). Next step: build
v1.6 per §4 below, in the order given.

## 1. What exists today, and why it costs pulls/tokens

### MacroDroid — 4 macros (verified 2026-10-14 backup)
- `Muse` (universal webhook): server → trigger.macrodroid.com → FCM →
  macro → Samsung Routines step. Branches: house modes, theater /
  driving / exercise / work / relax / before-bed, commute, meeting,
  power standard/max, sound, DND (fallback), WoL, status, status_full,
  speak (TTS, deprecated for Muse-initiated contact).
- `Mode Poll`: 5-min interval, GET mode-desired, applies drift itself.
- `Notify Push`: Notification Received (any app) → POST phone-ingress.
- `Low Battery Alert`: battery ↓20% → status POST.

### "From this device" — paired-device catalog pulls
Calendar (`calendar.search`), Contacts (cached pull), Messages
(`message.search`), Phone dialer / Call Log (`call_log.search`),
Notifications (`notifications.get`), Health Connect
(`health.backfill` + 3× daily sync crons at 09:00 / 12:00 / 00:05).
Every read is Muse-initiated: a `device.invoke` round-trip, paid in
tokens and latency, and stale between pulls. The Oct 8 push-only order
already killed the 10-min notification pull cron for exactly this
reason; MacroDroid Notify Push was the workaround. This design makes
the *app itself* the pusher for all of it, and retires both the
MacroDroid relay and the remaining pull backstops (e.g. the 2-hour
shade-reconcile pull).

## 2. Feasibility audit (source-verified 2026-11-09)

All of it is buildable locally in Muse Device Assistance:

- **Notifications — EASY, half-built.** `GroundTruthListener` is
  already a granted `NotificationListenerService`; today its
  `handle()` returns early for every package except
  `com.samsung.android.app.routines`. Removing that filter behind a
  toggle and POSTing the same `phone-notify` text/plain shape
  MacroDroid sends (source / package / title / text) replaces Notify
  Push 1:1, plus `onNotificationRemoved` gives the CLEARED events
  Notify Push v2 never shipped — stops become visible too.
- **Calendar / Contacts / Messages / Call Log — STANDARD.**
  `ContentObserver` on `CalendarContract`, `ContactsContract`,
  `Telephony.Sms/Mms`, `CallLog.Calls`. Observer fires on-device →
  read only the rows changed since the channel's stored cursor →
  push the diff. Needs the matching runtime permission, requested
  only when the user turns that channel ON.
- **Phone dialer — STANDARD.** `TelephonyManager` call-state +
  outgoing-call events push in real time (dialed / ringing /
  offhook / idle). Distinct from Call Log: dialer = live event,
  call log = the durable after-the-fact record. Both channels exist
  in the screenshot, so both ship, separately toggled.
- **Health Connect — YES, via its changes API.** HC has no push,
  but it has a changes-token API (`getChanges(token)`). The app
  keeps a per-record-type token, runs a cheap *on-device* check
  (15-min WorkManager + opportunistic on Samsung Health
  notifications, which are already a push signal) and pushes only
  new records: sleep, steps, heart rate, workouts, SpO2 where
  exposed. This replaces `health.backfill` pulls: the check moves
  from Muse's crons (tokens) to the phone (free).
- **Low Battery — TRIVIAL.** `BatteryManager` / ACTION_BATTERY_CHANGED
  in `BridgeService`'s existing 60-s cycle + a crossing latch at
  20% → push the same status payload MacroDroid sends.
- **Status (`?do=status`) — ALREADY REPLACED.** `StatusProducer`
  in every report carries battery/charging/power-save/screen/
  ringer/network/storage — a superset of the macro's 5 fields.
  v1.6 only repoints the remaining `phone-status` consumers to
  `phonebridge:report` and deletes the branch from the contract.
- **Speak/TTS — EASY.** Native `TextToSpeech` + a `speak` allowlist
  template in the existing Worker command channel replaces
  `?do=speak` with no cloud relay. (Muse-initiated *voice contact*
  still prefers `voice_mode` per the 2026-11-08 order; this is the
  one-way announcement surface.)
- **Mode Poll + universal mode branches — THE HARD ONE, already
  scoped.** No app can beat Samsung's signature gate; actuation
  stays "fire the linked routine shortcut intent." What changes
  locally: the reconciler already runs on-device every 60 s
  (`BridgeService.cycle` → `fetchDesired` → `Reconciler.evaluate` →
  `Actuation.maybeAct`). Expanding Phase 2 from night-only to all
  four linked house modes turns Mode Poll into a redundant
  duplicate of a loop the app already runs — at 1 min instead of
  5, with verified acks instead of unconditional ones. The webhook
  endpoint then has no job: desired state is *pulled by the phone
  from our own Worker* (already true) and converged locally.
  Sound/power/meeting/commute branches that are really settings
  (ringtones, DND, power-save boolean via shell templates) move to
  the loopback shell allowlist where a template exists; the rest
  stay routine-shortcut links. Arming remains Jonathan's in-app
  act, per mode — never Muse's.

## 3. Push architecture (the pattern for every channel)

```
on-device observer (listener / ContentObserver / receiver / HC token)
  → channel toggle check (Store, §4) — OFF means: not registered,
    not read, not pushed. No silent collection behind a toggle.
  → diff against the channel's cursor (last id / ts / HC token)
  → POST {workerBase}/device-push?secret=…   (JSON, one channel per post)
        {source: "phone-notify"|"phone-calendar"|"phone-contacts"|
                 "phone-messages"|"phone-dialer"|"phone-calllog"|
                 "phone-health"|"phone-battery",
         ts, items: [ …same fields the old pull returned… ]}
  → Worker stores to a per-source KV queue (phone-ingress Worker
    gains /device-push, or mode-sync Worker — build decides ONE home
    and documents it; the secret is the one already on-device)
  → VM drain hook (bash, 60 s, zero model tokens — the
    phone-notify-drain pattern) stages items; only actionable items
    wake a model, via push-urgent-inbox as today.
```

Rules:
- **Push is the only intake.** No new pull consumer is built; pull
  commands (`notifications.get`, `message.search`, `call_log.search`,
  `calendar.search`, `health.backfill`) remain only as on-demand
  diagnostics until their channel proves out, then are retired from
  standing automations (shade-reconcile backstop included).
- **Verify-then-mark cursors** on both ends, as in phone-notify-drain:
  an item is acked in KV only after the drain stores it; the phone
  advances its cursor only on HTTP 2xx.
- **Payloads carry what the pull returned**, field-for-field, so
  consumers don't change shape — only their source changes.
- **Fail loud:** a push failure is logged on-device (`BridgeLog`)
  and retried with backoff from the queue in prefs; a channel whose
  observer dies (permission revoked, listener disconnected) reports
  that state in the regular bridge report (`push_channels` object),
  so silence is never mistaken for "nothing happened."

## 4. Per-channel user control (Jonathan's order, 2026-11-09 09:41)

Every channel gets its own switch in MainActivity, in a new
"Push channels — this device → Muse (you control each one)"
section, following the exact existing pattern (`K_VOICE_ENABLED` /
`voiceCheck`, `K_SHELL_ON` / `shellCheck`, `K_ACT_ARMED` / `armCheck`):

| Channel | Store key | Default | Observer | Permission asked when turned ON |
|---|---|---|---|---|
| Notifications | `push_notifications` | OFF* | GroundTruthListener (all pkgs) | Notification access (already granted) |
| Calendar | `push_calendar` | OFF | ContentObserver CalendarContract | READ_CALENDAR |
| Contacts | `push_contacts` | OFF | ContentObserver ContactsContract | READ_CONTACTS |
| Messages | `push_messages` | OFF | ContentObserver Telephony + SMS_RECEIVED | READ_SMS / RECEIVE_SMS |
| Phone dialer | `push_dialer` | OFF | Call-state listener | READ_PHONE_STATE |
| Call Log | `push_call_log` | OFF | ContentObserver CallLog.Calls | READ_CALL_LOG |
| Health Connect | `push_health` | OFF | HC changes-token check | Health Connect per-type grants |
| Low Battery | `push_battery` | OFF | BridgeService cycle latch | none |
| Mode reconcile (local) | `push_mode_reconcile` | ON† | Existing 60-s cycle | none |

\* OFF is the honest default for new capability in this app
(voice_mode precedent). \*Migration: when Jonathan turns Notifications
ON in v1.6 and the first push lands, MacroDroid Notify Push gets
disabled by him on-phone to avoid double-posting (drain dedupes by
package|title|text in the meantime). †Mode reconcile is the bridge's
existing core loop (shadow/verdicts); its *actuation* stays under
the separate Phase 2 ARM switch, per mode, as today.

Toggle semantics, all channels:
- OFF → observer unregistered / listener branch inert, cursor kept
  (so re-ON pushes only what changed while off *if* the user
  re-enables and a "sync backlog" is confirmed — default: cursor
  jumps to now, no historical dump without asking).
- The bridge report's `push_channels` object reports each channel's
  toggle + last-push time + last error, so Muse can see the state
  Jonathan set without asking him.
- TV profile: every push channel is gated OFF on the Shield, same
  as other phone surfaces.

## 5. Build order (v1.6 → v1.7)

1. **v1.6 core:** Store keys + MainActivity toggle section +
   `PushClient` (POST /device-push) + `push_channels` in report.
2. **Notifications channel** (replaces MacroDroid Notify Push +
   notifications.get pulls) — first, per Jonathan's emphasis.
3. **Low Battery channel** (replaces Low Battery Alert).
4. **Worker /device-push ingress + drains** for the live channels;
   repoint phone-notify consumers; dedupe against MacroDroid during
   overlap.
   **Status 2026-11-09: DONE (VM drain side).** New hook
   `device-push-drain` (`~/hooks/definitions/device-push-drain.json`,
   script `~/hooks/scripts/device-push-drain.sh`, 60 s poll,
   midnight-anchor grid gate + loud-failure counter) polls
   `GET /mode-sync/device-push` for all 8 sources. The endpoint
   returns the full queue with no item ids or server cursor, so the
   drain uses content-fingerprint cursors
   (`~/hooks/state/device-push-cursors.json`), marking an item seen
   only after its staging writes are re-read and verified. Staging:
   `~/hooks/state/device-push-<source>-staged.json` +
   `device-push-<source>-latest.json` per source, one ledger line
   per item in `~/hooks/state/device-push-ledger.jsonl` (daily-log-
   ready `summary` field). phone-notify is registry-classified in
   bash like the MacroDroid lane: routine →
   `notification-handled.jsonl`, actionable → pending item in
   `push-urgent-inbox.json` with the canonical leg spec in
   `required_action`; cross-lane dedupe (package|title|text[:60],
   30-min window, checked against the handled log, MacroDroid's
   staged file, pending inbox items, and the drain's own window
   file `device-push-notify-dedupe.json`). Other sources are
   stage+ledger only until their v1.7 consumers exist. First live
   run 2026-11-09 15:11Z staged 130 queued items (78 notify, 50
   calendar, 1 dialer, 1 battery — the marked `v16-deploy-test`
   fixture, verified field-for-field) with phone-notify in
   first-run baseline mode (staged+ledgered, nothing handed off:
   the pre-existing queue was the listener's connect-time sweep of
   the whole shade); 9 cross-lane duplicates were caught in that
   run. Routing row added to the local-trigger-routing skill
   (Phone notifications section) before the hook was created;
   tracked on goal_0b4ebbb0ed33.
   **Primary/legacy framing (Jonathan's order, 2026-11-09):**
   Jonathan turned the app's Notifications channel ON at ~10:04
   EST and the app was verified pushing real posted AND removed
   events from 15:04:43Z — so `device-push-drain` is the PRIMARY
   notification intake going forward. MacroDroid's Notify Push
   macro is redundant; the `phone-notify-drain` lane is the legacy
   overlap lane, retired once Jonathan disables that macro
   on-phone (not yet confirmed disabled). Post-baseline live
   proof 15:12Z: first new-stream poll handed 1 actionable item
   to the urgent inbox and caught 4 cross-lane duplicates.
5. **TTS `speak` template** (replaces `?do=speak`); repoint status
   consumers to the bridge report (retires `?do=status`).
6. **v1.7 channels:** Call Log + Dialer, Messages, Calendar,
   Contacts — one at a time, each live-verified against the pull it
   replaces before that pull is retired.
7. **Health Connect channel** — changes-token check + push;
   retire the 3 daily sync crons to a weekly backstop audit.
8. **Mode reconcile expansion** — Phase 2 links for all house
   modes presented to Jonathan with shadow-diff evidence
   (goal_2b6213617252); on his arming, Mode Poll + the universal
   webhook macro are retired by him on-phone. This is the only step
   gated on Jonathan.
9. **Skill + connector update in the same handling as the build**
   (standing rule: every APK ships with its skill) + GitHub
   private backup + public export.

## 6. What does NOT change
- Samsung Routines remain the actuator; no signature-gate claims.
- The Worker secret lives only on-device; nothing is hardcoded.
- Phase 2 arming/linking/test-fire stay Jonathan's in-app actions.
- Outward catalog actions (`message.send`, `phone.dial`) keep their
  existing approval rules — push is read/intake only.
