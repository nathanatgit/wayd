# 11 · Roadmap

Each milestone ends with a working, installable build. Don't start a milestone before the previous one's acceptance criteria pass.

## M0 · Skeleton
- Multi-module Gradle project (see 01-ARCHITECTURE), version catalog, `standard`/`research` flavors.
- `WaydTheme` with Material 3 Expressive, 4-tab navigation, empty screens, zh/en strings.
- Room + SQLCipher wired, entities from 02-DATA-MODEL, migrations folder.
- CI: build + unit tests + lint.
**Accept:** app installs, tabs navigate, DB opens encrypted, both locales render.

## M1 · Capture MVP
- VpnService with IPv4+IPv6, netstack, protected sockets, UID attribution.
- FlowTracker, DNS server + DnsIpMap, TLS SNI, HTTP/1.x parser, batched writes.
- Foreground service, notification, start/stop, live counters.
- Flow list screen (debug-grade).
**Accept:** browse, stream video and use WeChat for 1 h with no breakage; ≥ 99% of flows have a package; ≥ 90% of TCP flows have a host; external router capture shows no unexplained IPv4/IPv6 traffic (except documented OS bypass).

## M2 · Enrichment + daily report
- Company/category/tracker DB, GeoIP, identifier matcher.
- Rule engine with first 8 rules + tests; risk score.
- Daily report screen; app list + app detail (timeline, destinations).
- Coverage monitor + panel.
**Accept:** report renders in < 1 s for a 24 h session; each finding opens its evidence.

## M3 · Shizuku collectors
- Privilege module, ShizukuShell (reuse AppDualZuku), Command allowlist.
- AppOps, alarms, netstats, package collectors with real-device fixtures (HyperOS, ColorOS, Nubia, Pixel).
- Rules: R-LOC-THEN-UP, R-BOOT-REPORT, R-SCREEN-CAPTURE, R-CLIPBOARD-BG.
**Accept:** reproduce the "SystemHelper" style evidence chain on a test device.

## M4 · Message inspector
- Message list/detail, tier badges, PII highlighting, follow stream, export.
**Accept:** cleartext HTTP test app's identifiers are highlighted correctly.

## M5 · APK scanner
- Manifest/cert/NSC/DEX/strings, tracker signatures, vdex handling, Declared/Used/Sent view.
**Accept:** scans all apps on a test phone in < 5 min; carrier/vendor components identified by signer.

## M6 · Gemma 4
- Model manager (mirror, hash, RAM check), LiteRT-LM engine, prompt builder, tools, schema validator.
- "Analyse with AI" on selection, AI result screen, overnight summary.
- Prompt-injection test suite.
**Accept:** E2B analysis of a 10-item selection < 30 s on target device; 0 unvalidated claims shown; injection fixtures don't change output structure.

## M7 · Control
- Block rules (host/app) at VPN level; adb command helper with critical-package warnings.

## M8 · Research flavor
- MITM with consent flow and per-app picker.
- Root: NFLOG bypass detection → eBPF SSL capture → file access monitor → system CA module.
**Accept:** consent recorded and revocable; disabling leaves no CA/iptables/eBPF residue.

## M9 · Polish and release
- Performance/battery tuning, accessibility pass, onboarding, privacy statement final, distribution packaging.
