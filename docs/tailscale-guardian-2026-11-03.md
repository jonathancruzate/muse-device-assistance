# Tailscale guardian — v1.3.1 (2026-11-03)

Jonathan's idea, 2026-11-03 02:16 EST: "We can self heal tailscale with
the app." Built and shipped the same night as v1.3.1.

## The failure it answers

On 2026-11-03 the phone vanished from the tailnet after a network
change (~01:57 EST). Every VM-side heal step worked — adb-ensure read
the fresh endpoint (port 40761) from the bridge's Worker report, fresh
forwarders were started on fresh local ports — and the last hop was
still dead: the tailnet tunnel proxy closed every CONNECT, because
nothing on the phone owned Tailscale's survival. The VM cannot revive
a killed VPN app. The phone can.

## Design (`TailscaleGuard.java`)

- **Assert**: Android's Always-on VPN is a secure setting —
  `Settings.Secure "always_on_vpn_app"`, the same key the Settings UI
  writes. The bridge already holds `WRITE_SECURE_SETTINGS` (the ADB
  self-heal grant), so each 60 s cycle asserts that key for
  `com.tailscale.ipn`. With it set, the OS itself relaunches
  Tailscale's VpnService whenever it dies; no watchdog polling needed.
  If `always_on_vpn_lockdown` is ever `1` for it, the guardian clears
  it — lockdown would take ALL phone traffic down with the VPN.
- **Detect**: the tailnet is up iff some up, non-loopback interface
  holds an address in 100.64.0.0/10. Tailscale's battery-exemption
  state is read via `PowerManager.isIgnoringBatteryOptimizations`
  (any package may be queried). The guardian detects and reports the
  exemption; it cannot grant it (user toggle or shell only).
- **Report**: every Worker report carries a `tailscale` object —
  `{installed, up, ip, battery_exempt, always_on, down_since}` — kept
  by the mode-sync Worker's sanitizer into KV `phonebridge:report`.
  Muse sees tailnet presence directly instead of inferring it from
  failed connects minutes later.
- **Fallback**: if the interface stays down 10+ minutes despite the
  assertion, the guardian tries launching Tailscale's main activity
  (best effort; background activity starts are restricted), throttled
  to once per 30 minutes, logged either way.

## Verification

- Build: guards pass, 21 app classes, dex contains `TS_GUARD` /
  `always_on_vpn_app` strings; same signing cert as all prior versions.
- Worker: sanitizer redeployed via `deploy.py` (bindings preserved);
  reports without the new object still parse (fields default).
- On-device (the kill-test): with v1.3.1 running, confirm a report's
  `tailscale.always_on == true`; force-stop Tailscale; the tailnet
  interface returns on its own (OS relaunch); the next report shows
  `tailscale.up == true` with the phone's 100.x address. (Status of
  this test is recorded in the v1.3.1 build record in the README.)
