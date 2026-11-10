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

The Home dashboard starts/stops VPN processing independently of traffic recording. `TrafficRecording` defaults off per process and changes an enabled-window generation on every toggle. FlowTracker still supplies owner/visible-host metadata to the native block engine and aggregate in-memory counters while recording is off. It persists flow/message/DNS evidence only for flows in the same uninterrupted enabled window from OPEN to completion (DNS at event time). Existing records and already completed queued batches are retained; no retroactive capture of open flows. VPN session start/end metadata is still stored. New research interception decisions are declined while recording is off; existing intercepted connections can continue until closed, but messages are not retained. This is not a measured low-power mode or a general switch for independent permission collectors.

```
tun fd → TunLoop (native read) → Netstack (TCP/UDP termination)
       → per-connection: UidResolver → FlowTracker.open()
       → Parsers (DNS / TLS ClientHello / HTTP/1.x / QUIC Initial SNI)
       → Upstream protected socket → bytes counted both ways
       → FlowTracker.close() → batched DB write
```

### UID attribution
- API 29+: `ConnectivityManager.getConnectionOwnerUid(protocol, local, remote)`.
- Resolve the exact protocol/local/remote tuple on OPEN. If unresolved, retry on at most two DATA events within the first second; never retry on CLOSE or change an already-resolved owner. A late owner does not reopen the interception decision (D-030).
- UID → packages via `PackageManager.getPackagesForUid()`; shared UIDs (e.g. `android.uid.system`) shown as a group.

### DNS
- Answer queries ourselves by forwarding upstream (UDP/53 and TCP/53).
- Record `qname → answers` in `DnsIpMap` (TTL-aware), so later flows to those IPs get a hostname.
- Implemented fallback consumes UDP replies and length-framed TCP replies across DATA chunks, including multiple replies in a chunk, within the native 16 KiB prefix. Invalid/incomplete/oversized frames do not supply a hostname. The current LRU still uses the most recent observed answer without TTL expiry; TTL-aware and per-app correlation remain open.
- Detect DoH/DoT: connections to known resolvers on 443/853 → flag `viaDoh`.

### TLS
- Parse ClientHello only (no decryption): SNI, ALPN, version, cipher list → JA4.
- ECH present → mark host as hidden; rely on DNS mapping.

Implemented M1 metadata parsing accumulates up to 16 KiB of the upstream TCP prefix across DATA chunks. A ClientHello may span handshake records; metadata is used only once the whole handshake is present. The buffer is released on completion or at the limit. HTTP heads use the same prefix budget. Larger or incomplete prefixes remain metadata-only unless DNS supplies a host; ECH detection/marking and QUIC SNI parsing remain open. See D-029.

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

## M7 native enforcement · 2026-11-10

App/IP gates now precede upstream TCP dialing or UDP forwarding. Native policy snapshots are acknowledged atomically and existing matched flows are closed. Required owner metadata waits at most one second; applicable visible-host rules inspect at most 16 KiB for 600 ms, reuse Kotlin SNI/HTTP parsing, and then apply exact-host matching before dial when evidence arrives within budget. No DNS correlation is used as authoritative blocking evidence. Unknown owners/hosts remain visible gaps and may forward; UDP hostname rules, ECH, late names and excluded/bypass traffic are not covered. Gate infrastructure failure closes the selected flow. No per-packet database writes were introduced. Native counters survive live-flow cancellation, including research TLS forwarding.

A capability-version check prevents starting an older native library without policy enforcement. Policy mutation and native-handle destruction are serialized; only the polling thread destroys its engine after the final poll. See [13-BLOCKING.md](13-BLOCKING.md).

Shutdown owns relays in a JoinSet: signal the accept loop, abort/drain relays while their TCP protocol tasks can still run, then drop the stack and stop the runtime. IpStack TCP destructors synchronously wait for protocol tasks; shutting the runtime down first can leave native threads and the duplicated TUN open. Cleanup waits at most two seconds for the owner task, then one second for runtime shutdown. This work stays on the polling thread, serialized with policy JNI calls. See D-035 and the Android-native lifecycle regression in [12-TESTING.md](12-TESTING.md).
