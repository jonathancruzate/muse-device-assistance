# Tailscale reviver — v1.3.2 (2026-11-03)

Why the guardian stopped asserting always-on and became the reviver
itself.

## (a) The v1.3.1 assertion, and its live failure

v1.3.1's `TailscaleGuard` asserted Android's secure setting
`always_on_vpn_app = com.tailscale.ipn` on every 60-second bridge
cycle (the bridge holds the WRITE_SECURE_SETTINGS grant), expecting
the OS to keep Tailscale's VPN service alive and relaunch it when
killed.

Live test, the same night it shipped: after ~12 minutes of assertion
cycles, Jonathan opened Settings → VPN → Tailscale and found
**Always-on OFF**, and switched it on manually. The writes had never
taken effect. The 02:36 EST heartbeat report in the mode-sync Worker
confirmed the neighboring facts at the same time:
`tailscale: {installed: true, up: true, ip: 100.88.51.94,
battery_exempt: true, always_on: false}` — his Tailscale battery
exemption (set by him to Unrestricted) registered immediately, while
`always_on` stayed false through every assertion cycle.

## (b) Research verdict

The only programmatic always-on read/write on modern Android is
`DevicePolicyManager.getAlwaysOnVpnPackage` /
`setAlwaysOnVpnPackage`, and both are restricted to device-owner /
profile-owner apps (the MDM lane). The Settings app changes always-on
through binder calls into the connectivity service; the legacy
`Settings.Secure` key `always_on_vpn_app` is **not a live store** on
modern Android. A regular owner-built app can therefore neither set
nor reliably read another app's always-on state. v1.3.1's assertion
was writing to a key nothing honors — and reporting it implied a
protection that did not exist.

## (c) The v1.3.2 reviver design

If the app cannot make the OS the reviver, the app becomes the
reviver:

- **Detection (60 s cadence, unchanged):** the tailnet is up iff some
  up, non-loopback interface holds an address in 100.64.0.0/10.
  Tailscale's battery-exemption state is read via `PowerManager`
  (detect/report only — granting it is a user/shell action).
- **Immediate relaunch:** on the first down cycle of an episode, the
  guardian launches Tailscale's main activity right away. Tailscale
  reconnects on launch when its own state says it should be
  connected — exactly the killed-by-the-system case. Attempts are
  best-effort (background activity starts are restricted) and every
  attempt is logged.
- **Backoff retries:** further attempts at 2, 5, 15, then every
  30 minutes while the episode lasts.
- **Escalation:** if an episode passes 5 minutes despite the launch
  attempts, the guardian posts **one** tappable "Tailscale is down"
  notification per episode; a user tap launches Tailscale and is
  never background-restricted. The notification clears on recovery.
- **Recovery logging:** when the interface returns, the episode's
  down-duration is logged on-device and the recovery time is kept
  for reporting.
- **Report fields** (in every Worker report's `tailscale` object):
  `{installed, up, ip, battery_exempt, reviver_armed, down_since,
  down_count, last_recovered_ms}`. The mode-sync Worker sanitizer was
  updated to this shape and redeployed (bindings preserved).
- The always-on assertion code is deleted: zero `always_on_vpn_app`
  references in the v1.3.2 dex.

## (d) Live verification

- Build: versionCode 6, same signing cert as all prior versions
  (`4dcb45fa…`), APK 144,629 bytes, SHA-256
  `1f2055ca02987af63cfea50d9610200223b2ec73d3ba4a7601a2d9ea7cd45cae`.
- Installed on-device by Jonathan and opened once. The Worker report
  at **2026-11-03T07:43:06Z** carried the new shape:
  `tailscale: {installed: true, up: true, ip: 100.88.51.94,
  battery_exempt: true, reviver_armed: true, down_since: 0,
  down_count: 0, last_recovered_ms: 0}`, verdict **in_sync**.
- The deliberate off/on episode test (switch Tailscale off, watch
  the guardian relaunch it, read the down/recovery in the reports)
  was **pending at snapshot time**.

## (e) The OS toggle stays user-owned

The Android always-on VPN toggle for Tailscale — switched on
manually by Jonathan on 2026-11-03 — remains complementary and
user-owned: it is the OS-level protection, set through the only path
that works. The guardian complements it with detection, revival
attempts, escalation, and a reporting trail; it never claims to
control the toggle.
