# 11 · Roadmap

Targets, not status. Current status per milestone: [HANDOFF.md](HANDOFF.md#status). Each milestone ends with an installable build. Nat may accept a milestone for progression before every criterion passes; record that in DECISIONS.md (e.g. D-031 for M1).

## M0 · Skeleton
- Multi-module Gradle project (01-ARCHITECTURE), version catalog, `core`/`clash` variants (D-049).
- `WaydTheme` with Material 3 Expressive, tab navigation, zh/en strings.
- Room + SQLCipher, entities from 02-DATA-MODEL, migrations. CI: build + unit tests + lint.
**Accept:** app installs, tabs navigate, DB opens encrypted, both locales render.

## M1 · Capture MVP
- VpnService IPv4+IPv6, netstack, protected sockets, UID attribution.
- FlowTracker, DNS + DnsIpMap, TLS SNI, HTTP/1.x parser, batched writes; foreground service, start/stop, live counters.
**Accept:** 1 h of browsing, video and WeChat with no breakage; ≥ 99% of flows have a package; ≥ 90% of TCP flows have a host; external router capture shows no unexplained IPv4/IPv6 traffic (except documented OS bypass).

## M2 · Enrichment + daily report
- Company/category/tracker DB, GeoIP, identifier matcher; rule engine (first 8 rules) + risk score.
- Daily report, app list, app detail; coverage monitor + panel.
**Accept:** report renders in < 1 s for a 24 h session; each finding opens its evidence.

## M3 · Shizuku collectors
- Privilege module, ShizukuShell, `Command` allowlist; AppOps, alarms, netstats, package collectors with real-device fixtures (HyperOS, ColorOS, Nubia, Pixel).
- Rules: R-LOC-THEN-UP, R-BOOT-REPORT, R-SCREEN-CAPTURE, R-CLIPBOARD-BG.
**Accept:** reproduce the "SystemHelper" style evidence chain on a test device.

## M4 · Message inspector
- Message list/detail, tier badges, PII highlighting, follow stream, export.
**Accept:** a cleartext HTTP test app's identifiers are highlighted correctly.

## M5 · APK scanner
- Manifest/cert/NSC/DEX/strings, tracker signatures, vdex handling, Declared/Used/Sent view. Limits: [07-APK-SCANNER.md](07-APK-SCANNER.md).
**Accept:** scans all apps on a test phone in < 5 min; carrier/vendor components identified by signer.

## M6 · Gemma 4
- Model manager (mirror, hash, RAM check), LiteRT-LM engine, prompt builder, tools, schema validator; "Analyse with AI", result screen, overnight summary; prompt-injection suite. External provider per D-046.
**Accept:** E2B analysis of a 10-item selection < 30 s on target device; 0 unvalidated claims shown; injection fixtures don't change output structure.

## M7 · Control
- App/host/IP blocks at VPN level before upstream dispatch; session and persistent rules, Undo, live-flow closure; Controls inventory; reviewed JSON backup/import; adb helper with critical-package warnings.
- Then content control on decrypted HTTP: fixed local replies, then bounded response edits; never scripts or global decryption. Semantics: [13-BLOCKING.md](13-BLOCKING.md).
**Accept:** local-server fixtures prove blocks precede dispatch; session rules expire, persistent rules survive restart; unknown attribution/names stay visible; import cannot grant consent or widen scope. Content control has its own gate in 13-BLOCKING.

## M8 · Decryption and root (consent-gated)
- MITM with consent flow and per-app picker; root: NFLOG bypass detection → eBPF SSL capture → file access monitor → system CA module.
**Accept:** consent recorded and revocable; disabling leaves no CA/iptables/eBPF residue.

## M9 · Polish and release
- Performance/battery tuning, accessibility, onboarding, final privacy statement, distribution packaging.

## Clash routing (`clash` variant)
- Built-in routing under WAYD's VPN (D-049): profiles, access control, resources/GeoIP, outcomes.
**Accept:** [14-OPTIONAL-ROUTING.md](14-OPTIONAL-ROUTING.md#acceptance) — `core` ships none of it, block precedence, no Direct fallback, minimal data across the transport boundary.
