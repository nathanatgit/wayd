# 00 · Product requirements

## Problem

Android phones — especially China-ROM devices — run preinstalled system components and third-party SDKs that collect and upload data in the background (e.g. a carrier "SystemHelper" reporting app lists, usage time and location over Wi-Fi without a SIM). Users have no way to see this, and existing tools show raw packets that ordinary people cannot read.

## Goal

Let a user press one button, live normally for a day, and get an honest, understandable answer to: **"What did my phone's apps do, what did they send, and to whom?"**

## Users

| Persona | Needs |
|---|---|
| Privacy-aware user (primary) | One-tap recording, plain-language daily report, simple actions |
| Power user / developer | Flow tables, message inspector, pcap/JSON export, Shizuku/root features |
| Researcher | Decrypted payloads, APK analysis, evidence chains, reproducible exports |

## Principles

1. **On-device inspection and analysis.** WAYD inspects traffic and analyzes findings on your Android device. It never automatically uploads captured traffic, inspection logs, reports, or AI analysis. You can explicitly export data to a destination you choose. Apps still communicate with their destinations; user-initiated model/database downloads also use the network. A planned, opt-in routing extension will send traffic selected by routing rules through your configured proxy. No analytics SDKs or payload-uploading crash reporters.
2. **Honest coverage.** Always show what WAYD *cannot* see right now.
3. **Evidence first.** Every claim links to the flows, messages, permission events or APK facts behind it.
4. **Rules decide, AI explains.** Verdicts and severities come from deterministic rules; Gemma only explains and summarises.
5. **Least privilege, progressive power.** Work with no privileges; unlock more with Shizuku, then root.
6. **Dangerous things are opt-in, per app, reversible.** MITM and root plaintext capture require explicit consent and can be undone.

## Functional requirements

### Recording
- FR-1 Start/stop recording from the main screen (single primary action).
- FR-2 Capture all IPv4/IPv6 TCP/UDP traffic of all apps in the user profile, including system apps, via `VpnService`.
- FR-3 Attribute each flow to a UID/package.
- FR-4 Record DNS queries/answers, TLS SNI/ALPN/version/JA4, HTTP request/response lines and headers; store cleartext bodies.
- FR-5 Run as a foreground service; survive screen-off, doze and (with user help) OEM battery killers; restart after reboot when enabled.
- FR-6 Show a live counter (flows, apps, bytes) and coverage status during recording.

### Evidence collectors
- FR-7 (ADB) Collect AppOps history, alarms, per-UID netstats, package permissions.
- FR-8 (Root) Detect traffic bypassing the VPN; capture TLS plaintext for opted-in apps; monitor sensitive file access.

### Analysis and reports
- FR-9 Enrich flows (domain, company, country, tracker category).
- FR-10 Rule engine produces findings with severity and evidence references.
- FR-11 Daily report: summary, alerts, background activity, companies, countries, timeline heatmap, new-this-day.
- FR-12 Per-app page: destinations, upload/download, permissions held vs used, findings, APK facts.
- FR-13 Message inspector: per-app list of sent/received messages with tier badge (plaintext / decrypted / metadata) and highlighted personal data.

### AI (Gemma 4, on device)
- FR-14 User selects one or more entries (flows, messages, findings, an app) → "Analyse with AI".
- FR-15 Output is schema-validated JSON rendered as cards/charts; every claim cites evidence IDs.
- FR-16 Optional overnight AI summary of the daily report (charging + idle).
- FR-17 Chat "ask about my data" using read-only tools.

### APK scanner
- FR-18 Scan installed (incl. system) apps: manifest, certificate, SDKs, endpoints, network security config.
- FR-19 Link static facts to observed behaviour.

### Control
- FR-20 Block a domain or an app's network (VPN-level; root: iptables).
- FR-21 Show safe adb commands (`pm disable-user`, `pm uninstall --user 0`, `install-existing`) with warnings for critical packages.

### Data management
- FR-22 Encrypted local storage; retention settings; wipe all; export on demand.

### Dangerous features
- FR-23 MITM decryption: hidden behind a Danger zone toggle, warning, privacy statement, explicit consent; per-app opt-in.

## Non-functional requirements

- NFR-1 Battery: ≤ 5%/day overhead in idle-heavy use on a mid-range device (target, measure in M1).
- NFR-2 Throughput: no user-visible slowdown at 100 Mbps.
- NFR-3 Storage: metadata ≤ 50 MB/day typical; payload storage capped and configurable.
- NFR-4 Privacy: DB encrypted at rest, excluded from backups.
- NFR-5 Languages: 简体中文 and English from day one.
- NFR-6 Accessibility: TalkBack labels, contrast, no colour-only meaning.

## Out of scope (v1)

- Cellular baseband traffic (IMS/SMS/carrier provisioning) — not visible to Android's IP stack; documented as a coverage gap.
- Cloud sync, accounts, remote dashboards.
- iOS.

## Distribution notes (verify before release)

- Chinese app stores restrict VpnService apps and require App 备案; plan for GitHub/sideload distribution.
- Google Play requires a VpnService declaration; network monitoring/firewall is a recognised use case — check current policy.
- Two build flavors: `standard` (no MITM / no root plaintext) and `research` (all features).

## Optional routing extension (planned)

Core inspection and planned blocking must work without an account, proxy, subscription, or routing extension. Clash/FlClash-style routing is optional and off by default; see [14-OPTIONAL-ROUTING.md](14-OPTIONAL-ROUTING.md). WAYD blocks take precedence over imported routing profiles. Show Direct / Proxied / Blocked outcomes; direct fallback after proxy failure requires explicit opt-in. This is a design requirement, not a shipped feature.
