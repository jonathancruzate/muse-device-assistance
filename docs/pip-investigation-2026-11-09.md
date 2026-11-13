# Voice-mode PiP investigation — can Muse voice mode start in picture-in-picture?

- **Date:** 2026-11-09 (investigation run `voice-pip-rea-investigation`, subagent session c81bdc27)
- **Question (Jonathan):** when we self-activate Muse voice mode, can the session go straight into the floating mini window (PiP) instead of the full-screen voice UI — and by which mechanism?
- **Scope honored:** investigation only. No app code modified, no device commands fired, no phone contact.
- **Tools used:** REA skill guidance (`~/workspace/custom-skills/local-rea/SKILL.md`), our own sources, APKMirror/Wayback, GitHub connector (repos read, not cited from memory).

## APK acquisition record (REA audit target)

Per Jonathan's 2026-10-19 rule (APKMirror preferred, record source + version + SHA-256):

| Item | Result |
|---|---|
| Target | Muse from Meta, `com.facebook.aura`, Meta Platforms, Inc. |
| Newest APKMirror release found | **13.0.0.4.159** (Oct 23, 2026) — APKMirror release page + APKPure agree (40.5 MB). Uptodown lists a newer 15.0.0.3.156 (Nov 7, 2026), not on APKMirror yet. |
| APKMirror source URL | https://www.apkmirror.com/apk/facebook-2/muse-from-meta/muse-from-meta-13-0-0-4-159-release/muse-from-meta-13-0-0-4-159-android-apk-download/ |
| Archived copy | Wayback Machine snapshot of that page, 2026-10-23 (`web.archive.org/web/20261023115616/…`); the page's APKMirror download key was recovered from the snapshot (`6b2291e3…a96e7ac7`). |
| **Binary obtained?** | **NO — acquisition blocked from this VM.** SHA-256: **not recordable** (no binary). |
| Blockers (each tried) | APKMirror direct (app list, variant page, keyed `/download/?key=…`): Cloudflare "Just a moment" managed challenge on this VM's datacenter egress; same keyed URL via the browser crawler's egress: HTTP 403. APKPure (.com + mobile host) and APKFab: Cloudflare 403. Uptodown: page loads, but the download-URL mint is gated by Cloudflare Turnstile (JS challenge, not solvable from a shell). APKCombo and apkpure.net: unreachable from this VM (curl 000). VPS home-egress lane: SSH publickey denied from this VM. Wayback: page snapshots exist, but **no APK binary capture and no download.php capture** for this app. |
| Consequence | Q1/Q2 below rest on non-binary evidence and are labeled **hypotheses** (REA guardrail: findings are hypotheses until reproduced). Everything else rests on live-verified or source-verified evidence, labeled as such. |

## How the voice hunt works today (our sources, verified by reading)

- `~/workspace/muse-device-assistance/app/src/com/jonathancruzate/metamusedeviceassistance/VoiceAccessibilityService.java` (the shortcut app's service is the same design):
  - START: `ACTION_ASSIST` opens Muse; the service taps the voice button — view-ids `hatch_voice_button` / `voice_button` / `voice_call_button` / `start_voice_button`, then exact labels "Voice call" / "Start voice call" / "Voice mode" / "Voice" / "Talk"; ~9 s of retries.
  - END: ids `end_voice_button` / `end_call_button` / `voice_end_call`, labels "End call" / "End voice call" / "Hang up" / "Leave call" / "End"; fallback chain `GLOBAL_ACTION_BACK` → `GLOBAL_ACTION_HOME` (lines 152–154).
  - **There is no PiP / minimize step anywhere in either service.**
- Live-verified session markers (foreground uiautomator dump during a real session, 2026-11-08 23:31 EST; `docs/execution-2026-11-08.md`, "Groundwork — the observable markers"): the session screen renders view-ids **`hatch-voice-end-call-button`**, **`hatch-voice-call-connected`**, **`hatch-floating-call-controls`** and content-descriptions **"End voice call"** / **"Mute voice call"**. The sessionless chat screen shows none of them (only the START control `hatch-voice-call-button`). v1.5.2's `observeVoiceSessionUi()` reconciles against exactly this marker set.
- Notable: Muse's session UI already contains a **floating call-controls bar** (`hatch-floating-call-controls`) — an in-app floating element, distinct from system PiP.

## Q1 — Does the voice activity declare `android:supportsPictureInPicture="true"`? Which activity?

**Not directly verifiable — the binary could not be obtained (see acquisition record).** Indirect evidence, all pointing the same way:

- The live session UI invests in its own in-app floating control bar (`hatch-floating-call-controls`) instead of relying on the system PiP window — the pattern apps use when the call surface is custom, not a PiP activity.
- GitHub code search across public code finds **zero** references to aura PiP behavior and zero published aura voice hunt lists (`"hatch_voice_button" OR "hatch-voice" OR "hatch_voice"` → 0 results).
- The voice session lives inside the main Muse activity flow (our `ACTION_ASSIST` lands on `AuraMainActivity` per the shortcut skill's REA notes); a chat activity that also hosts a voice session is an unlikely PiP candidate, and no separate voice activity has ever surfaced in our dumps.

**Hypothesis: the voice session's host activity does not declare PiP support.** Confidence: moderate, unproven. Settled definitively by either (a) `aapt`/jadx on the APK manifest once a binary is obtainable, or (b) the live HOME-probe in Q4 — if HOME shrinks the session into a system PiP window, the flag + auto-enter exist; if it merely backgrounds the app, they don't (for practical purposes).

## Q2 — Is there PiP code (`enterPictureInPictureMode`, `PictureInPictureParams`, `onUserLeaveHint`, `onPictureInPictureModeChanged`, `setAutoEnterEnabled`), wired to voice?

**Binary-dependent; not directly verifiable this run.** What is established:

- These are **own-app-only APIs**: only the owning activity can call `enterPictureInPictureMode()` on itself; no external app or accessibility service can invoke PiP on aura's behalf. (`setAutoEnterEnabled` alone appears in 539 public Java files on GitHub — all own-app usage.) So even if aura contains PiP code, the only externally triggerable path is the system user-leave-hint path (Q4).
- No public artifact (GitHub, the open-metamuse harness repo below) documents aura calling any PiP API.

**Hypothesis: no PiP code path is wired to the voice flow** (consistent with Q1's floating-bar pattern). Same settlement routes as Q1.

## Q3 — Does the voice UI expose a minimize / PiP / collapse control we could tap after connect?

**Partially answered from live evidence.** The recorded session-screen inventory (Nov 8 dump) contains exactly: end-call button, connected indicator, floating-controls container, "End voice call", "Mute voice call". **No minimize/PiP/collapse control was in that inventory** — but the dump recorded only the markers the v1.5.2 reconciler needed, not a full node tree, so absence is *not* proven.

Candidate hunt list for a minimize step, in order (aura's `hatch-*` naming pattern first, platform conventions after) — **all unverified**, to be confirmed or struck by one full session node dump:

- View-ids: `com.facebook.aura:id/hatch-voice-minimize-button`, `…/hatch-voice-pip-button`, `…/hatch-minimize-button`, `…/hatch-voice-collapse-button`, `…/minimize_button`, `…/pip_button`
- Exact content-descriptions / labels: "Minimize", "Picture in picture", "Collapse", "Shrink", "Floating window"

Also worth inspecting in that dump: the children of `hatch-floating-call-controls` — if any node inside the floating bar is a minimize affordance, it will be there.

## Q4 — Fallback: `GLOBAL_ACTION_HOME` right after connect (auto-PiP on user-leave-hint)

**Mechanism is real at platform level; aura-specific support is the open question.**

- Platform contract: `GLOBAL_ACTION_HOME` produces the user-leave-hint path. An activity that declares `supportsPictureInPicture` **and** enables auto-enter (`PictureInPictureParams.Builder.setAutoEnterEnabled(true)`, Android 12+; or calls `enterPictureInPictureMode()` from `onUserLeaveHint()` on older versions) shrinks into PiP. Without that opt-in, HOME simply backgrounds the app. This is the contract demonstrated by Google's official sample (see GitHub section).
- Our service already performs `GLOBAL_ACTION_HOME` safely (end-hunt fallback, `VoiceAccessibilityService.java:154`) — no new permission or capability is needed.
- Degraded-but-useful outcome if aura lacks auto-PiP: HOME backgrounds Muse while the voice session's audio continues — the phone returns to Jonathan's control and the conversation keeps going, re-enterable via recents/notification. Not PiP, but it does satisfy "don't take over my screen."
- `GLOBAL_ACTION_BACK` is **not** recommended as the probe: from a session screen it may pop back to chat or disrupt the session UI; HOME is the clean probe and doubles as the Q1/Q4 diagnostic.

## Q5 — Verdict

**Ranked mechanisms:**

1. **Minimize-control tap (if the control exists)** — cleanest, fully in our control, same hunt machinery we already run. Blocked on one piece of evidence: a full node inventory of the live session screen.
2. **`GLOBAL_ACTION_HOME` auto-PiP probe** — zero new capabilities; works **only** if aura's voice activity opts into PiP auto-enter (Q1 hypothesis says it doesn't). The probe is worth running regardless: its outcome *is* the Q1/Q4 answer, and the fallback outcome (backgrounded session, audio continues) is itself useful.
3. **Aura's own floating controls** — the session already renders `hatch-floating-call-controls`; combined with #2's backgrounding, the practical "not full-screen" experience may already be reachable without true PiP.

**Not real:** invoking PiP on aura from outside via any API/intent (platform-forbidden); overlay-cloning aura's session (we can't render its UI).

**Exact hunt additions the VoiceAccessibilityService would need** (spec only — no code changed in this investigation):

1. After the START tap lands, wait for `observeVoiceSessionUi() == UI_PRESENT`, then settle ~600–1200 ms.
2. One-shot PiP step, in order: (a) tap by the Q3 view-id list; (b) tap by the Q3 description/label list; (c) if still full-screen and PiP-on-start is configured, `performGlobalAction(GLOBAL_ACTION_HOME)` once.
3. Log which branch fired (and the full session node inventory on the first run) to `BridgeLog`, so the first live trial produces the missing evidence either way.
4. Verify outcome by window inspection (`getWindows()` — a PiP window presents as a small separate window) plus Jonathan's visual confirmation on the first trial.

**Only a live on-device trial can settle:** (1) whether a minimize control exists in the session UI (full node inventory); (2) whether the voice activity auto-enters PiP on HOME; (3) whether speak-first audio and the session survive PiP/backgrounding intact; (4) the exact settle timing between connect and the PiP step.

## GitHub open-source sweep (verified by reading via the GitHub connector, 2026-11-09)

### Candidates

| Repo | What it does | License / activity | Liftable piece |
|---|---|---|---|
| [googlesamples/android-PictureInPicture](https://github.com/googlesamples/android-PictureInPicture) → migrated to [android/media-samples](https://github.com/android/media-samples) | Google's official PiP sample: manifest flag + `onUserLeaveHint` → `enterPictureInPictureMode`, the canonical auto-PiP wiring | Google sample (README/migration verified by reading) | **Knowledge, not code:** the authoritative contract Q4 depends on — exactly what must be true inside aura for a HOME press to shrink it. Nothing runnable lifts into our service (own-app code). |
| [NoelJacob/open-metamuse](https://github.com/NoelJacob/open-metamuse) | Clean-room Muse clone **plus a harness that runs the real `com.facebook.aura`** on an emulator and drives it: `server/driver.py` scenarios (`to_otp`, `tap_text` on native nodes), mitm backend stub, pre-grant scripts (CONTRIBUTING.md read in full) | LICENSE file present (34,523 B — GPL-3.0-length text, not line-verified); active in the Muse-launch era (2026) | The **driver pattern** for state-driving the real aura app, and a warning we should heed: aura mixes Flutter and native surfaces ("Flutter surfaces are invisible to it") — node hunts only work on its native surfaces; the voice session screen is one (our markers prove it). No PiP content. |
| [Skythinker616/gpt-assistant-android](https://github.com/Skythinker616/gpt-assistant-android) | Full-scenario Android GPT assistant: **accessibility-based volume-key wake, global overlay UI, voice I/O**, plus an agent mode that operates the phone via accessibility nodes "without image recognition" (README read) | **GPL-3.0** (README badge); 909★; updated 2026-11-09 — actively maintained | **The fallback architecture:** an accessibility-woken WindowManager **overlay voice surface** that pops over any app and speaks — if aura won't shrink, *our own* floating voice window is the proven OSS pattern for the same user experience. Wake/surface lifecycle patterns lift into Device Assistance (not the accessibility service proper). |
| [ahmedeltaher/Agnentic-Voice-AI-SDK](https://github.com/ahmedeltaher/Agnentic-Voice-AI-SDK) | Voice-AI conversation pipeline SDK for embedding in your own app (2,579★, updated 2026-11-06) | Not read | **Nothing liftable** — own-app SDK; it cannot surface or shrink another app's voice UI. |
| [mohsenoid/Android-PiP](https://github.com/mohsenoid/Android-PiP) (42★); [shaun-chiang/rn-android-pip](https://github.com/shaun-chiang/rn-android-pip) (17★); [EdgarJMesquita/expo-pip](https://github.com/EdgarJMesquita/expo-pip) (20★) | PiP helper libraries / React Native & Expo bridges wrapping `enterPictureInPictureMode` | Small, mixed maintenance | **Nothing liftable** — all wrap the own-app API; they re-confirm from three ecosystems that PiP entry is owner-only. |
| Accessibility-auto-answer / call-UI automation niche (category 1 sweep) | Repo search "accessibility auto answer call android" → 1 irrelevant hit; code search for published aura hunt lists (`hatch_voice*`) → **0 results** | — | **Nothing liftable found.** No mature OSS exemplar of hunting another app's in-call controls surfaced via the connector; as far as public code shows, our own two VoiceAccessibilityServices are the reference implementation of this pattern against aura. |

### Top 3 by usefulness

1. **Skythinker616/gpt-assistant-android** — the only candidate that solves the *underlying want* (assistant pops up as a floating voice surface) with public, maintained, accessibility-based code.
2. **googlesamples/android-PictureInPicture (android/media-samples)** — the authoritative contract for the Q4 probe.
3. **NoelJacob/open-metamuse** — the only aura-specific automation experience in public code; its Flutter/native caveat directly informs hunt design.

**Single most liftable piece:** gpt-assistant-android's accessibility-woken **overlay voice-surface pattern** (wake on accessibility signal → WindowManager floating window → voice I/O) — the proven route to a floating Muse voice window that does not depend on aura supporting PiP at all.

## Sources

- Our code: `~/workspace/muse-device-assistance/app/src/com/jonathancruzate/metamusedeviceassistance/VoiceAccessibilityService.java`; `~/workspace/custom-skills/local-muse-voice-mode-shortcut/SKILL.md`.
- Live markers: `~/workspace/muse-device-assistance/docs/execution-2026-11-08.md` (2026-11-08 23:31 EST foreground dump).
- APKMirror release page (13.0.0.4.159) + Wayback snapshot 2026-10-23; acquisition attempts logged in `pip-investigation-progress.md` beside this file.
- GitHub connector reads: googlesamples/android-PictureInPicture README; NoelJacob/open-metamuse README + CONTRIBUTING.md + root listing; Skythinker616/gpt-assistant-android README; repo/code searches as itemized above.
