# 10 · Security and threat model

WAYD holds the most sensitive data on the phone. Treat WAYD itself as a high-value target.

## Assets

1. Captured traffic, decrypted payloads, permission history (DB + blobs).
2. MITM CA private key.
3. Root capabilities (installed iptables/eBPF/modules).
4. The user's trust (honest reports).

## Threats and mitigations

| # | Threat | Mitigation |
|---|---|---|
| T1 | Another app or a backup reads WAYD's DB | SQLCipher + Keystore key; `allowBackup=false`; no exported providers; no world-readable files |
| T2 | Exported components abused (start/stop VPN, read data) | All components non-exported except launcher + VpnService (protected by `BIND_VPN_SERVICE`); no deep links that trigger actions |
| T3 | Captured content performs prompt injection on Gemma | `<data>` wrapping, masking, read-only tools, schema validation, evidence-ID checks, no privilege access from `ai` |
| T4 | Malicious payload exploits WAYD parsers | Parsers in memory-safe code where possible; fuzz parsers (DNS/TLS/HTTP/AXML/DEX) in CI; size limits everywhere |
| T5 | CA key theft enables interception | Per-install CA, encrypted key, delete on disable, short validity |
| T6 | Root command injection | Sealed `Command` allowlist, argument validation, no string shells; dev console research-only |
| T7 | WAYD leaks data itself | No analytics/crash SDKs; network allowlist for WAYD's own sockets (model/DB downloads only); WAYD's own traffic shown in its own report |
| T8 | Misleading report (false "clean") | Coverage panel; tiers; "unknown" verdicts; never claim completeness |
| T9 | Root state left broken after crash | RootStateJournal + restore on start; "Restore network" button |
| T10 | Supply chain (model file, tracker DB) | SHA-256 pinned in app release; signed manifest for updates |
| T11 | Exported pcap/JSON shared carelessly | Export warning; optional redaction of identifiers and bodies |

## Logging

- No payloads, identifiers or hostnames in logcat in release builds.
- Debug logs only in debug builds.

## Permissions WAYD requests (and why)

| Permission | Why |
|---|---|
| `BIND_VPN_SERVICE` (service) | Capture |
| `FOREGROUND_SERVICE` (+ type) | Keep recording alive |
| `POST_NOTIFICATIONS` | Recording notification, report ready |
| `QUERY_ALL_PACKAGES` | Attribute traffic and scan APKs |
| `PACKAGE_USAGE_STATS` (special access) | Foreground time, netstats |
| `INTERNET` | Forwarding traffic; model download |
| `RECEIVE_BOOT_COMPLETED` | Optional auto-start |
| Shizuku permission | ADB/root features |

No location, contacts, SMS or storage permissions. WAYD reads device identifiers only to match them in traffic, and only at the privilege level available.

## Permissions added in M1 (2026-11-09)

| Permission | Why | Risk / mitigation |
|---|---|---|
| `INTERNET` | The userspace stack relays the user's own traffic to its real destination. WAYD makes no network calls of its own. | Code review rule: no other `Socket`/`URL` use outside `capture/native` relay. The app excludes itself from the VPN (`addDisallowedApplication`) so relay sockets bypass the tunnel. |
| `BIND_VPN_SERVICE` (service) | Declared on `WaydVpnService`; only the system can bind. | User must accept the system VPN consent dialog. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `POST_NOTIFICATIONS` | Keep recording alive and show a persistent "recording" notification with a Stop action. | Notification permission is optional; recording works without it. |
| `QUERY_ALL_PACKAGES` | Map connection UIDs to package names for every installed app. | Sideload/`research` distribution only; a store build needs a policy declaration. Package names never leave the device. |

Native code (`capture/native`) parses no payloads; it reports only bounded prefixes (2 KB TCP per direction, DNS datagrams, first UDP datagram) to Kotlin, where parsers are size-limited and treat input as hostile.
