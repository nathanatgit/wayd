# 03 · Capture (VpnService)

## VpnService setup

```kotlin
Builder()
  .setSession("WAYD")
  .addAddress("10.111.0.1", 32)
  .addAddress("fd00:77:61:79::1", 128)
  .addRoute("0.0.0.0", 0)
  .addRoute("::", 0)                       // REQUIRED, or IPv6 leaks around the tunnel
  .addDnsServer("10.111.0.2")              // fake DNS inside the tunnel → we see every query
  .setMtu(1500)
  .setBlocking(true)
  .addDisallowedApplication(packageName)   // only if needed; prefer protect() on our sockets
```

- **Never** call `allowBypass()`.
- Outbound sockets created by the netstack must be `protect()`ed.
- Set `setMetered(false)` / underlying networks so apps don't misjudge connectivity.

## Pipeline

```
tun fd → TunLoop (native read) → Netstack (TCP/UDP termination)
       → per-connection: UidResolver → FlowTracker.open()
       → Parsers (DNS / TLS ClientHello / HTTP/1.x / QUIC Initial SNI)
       → Upstream protected socket → bytes counted both ways
       → FlowTracker.close() → batched DB write
```

### UID attribution
- API 29+: `ConnectivityManager.getConnectionOwnerUid(protocol, local, remote)`.
- Cache by 5-tuple; resolve once per connection.
- UID → packages via `PackageManager.getPackagesForUid()`; shared UIDs (e.g. `android.uid.system`) shown as a group.

### DNS
- Answer queries ourselves by forwarding upstream (UDP/53 and TCP/53).
- Record `qname → answers` in `DnsIpMap` (TTL-aware), so later flows to those IPs get a hostname.
- Detect DoH/DoT: connections to known resolvers on 443/853 → flag `viaDoh`.

### TLS
- Parse ClientHello only (no decryption): SNI, ALPN, version, cipher list → JA4.
- ECH present → mark host as hidden; rely on DNS mapping.

### HTTP (cleartext)
- Reassemble HTTP/1.x requests/responses; store headers + bodies (size-capped) as `Message(tier=PLAINTEXT)`.
- Run the identifier matcher on URL, headers and body (see 05-ANALYSIS-RULES).

### QUIC
- Parse Initial packets for SNI where possible; otherwise metadata only.

## Netstack choice

See DECISIONS.md D-002. Candidates:
- gVisor netstack via tun2socks (Go, gomobile) — mature, used by many Android VPN apps.
- Rust (smoltcp / custom) via JNI — smaller binary, more work.

Reference implementations to study: PCAPdroid, NetGuard (GPLv3 — **read, don't copy** unless WAYD adopts a compatible license).

## Coverage monitor

Show users what is *not* captured. Compute and persist a `CoverageSnapshot` every few minutes:

| Check | How |
|---|---|
| Always-on VPN enabled | `Settings.Secure` / DevicePolicy where readable; otherwise ask user |
| Lockdown ("block without VPN") | same |
| Recording gaps | service start/stop log |
| Work profile / other users present | `UserManager` |
| Tethering active | `ConnectivityManager` |
| Battery optimisation exempt | `PowerManager.isIgnoringBatteryOptimizations` |
| Bypass traffic detected (root) | NFLOG counters for traffic not on tun |
| Baseband | always "not visible" |

## OEM survival checklist (China ROMs)

- Foreground service type `specialUse` / `systemExempted` as appropriate for target SDK; persistent notification.
- Guide user to: battery "no restrictions", 自启动 allowed, lock app in recents.
- Detect being killed (gap in heartbeat) and tell the user in the report.

## Performance budget

- Zero-copy where possible in native; batch DB writes; no per-packet Kotlin allocations.
- Target: < 3% extra CPU at idle, sustain 100 Mbps on a mid-range SoC.
