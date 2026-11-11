# 01 · Architecture

Target architecture, including planned components. See [HANDOFF.md](HANDOFF.md) for what is implemented.

## Layers

```
Sources      capture (VPN) · mitm (opt-in) · shizuku/root collectors · apkscan
   ↓
Enrichment   UID → package · DNS↔IP · SNI · GeoIP/ASN · tracker & company lists
   ↓
Store        Room + SQLCipher (encrypted), payload blobs, retention
   ↓
Analysis     rule engine → findings (with evidence refs) → daily report
   ↓
AI           Gemma 4 (LiteRT-LM) explains selected evidence → validated JSON
   ↓
UI           Compose + Material 3 Expressive
```

## Gradle modules

```
wayd/
├─ app/                    Activity, NavHost, DI graph, theme, feature screens
├─ core/model/             Pure Kotlin data classes, enums, IDs, JSON schemas
├─ core/common/            Coroutines dispatchers, Result types, logging (local only)
├─ core/db/                Room DB, DAOs, SQLCipher, BlobStore, RetentionWorker
├─ core/ui/                Shared Compose components (EvidenceChip, TierBadge, AppIcon…)
├─ capture/                WaydVpnService, TunLoop, Netstack bridge, FlowTracker,
│                          parsers (Dns, Tls, Http, Quic-SNI), PcapWriter, CoverageMonitor
├─ mitm/        [consent]  CaManager, TlsInterceptor, PinnedHostCache, PerAppPolicy
├─ privilege/              PrivilegeDetector, PrivilegedShell, ShizukuShell, LibsuShell,
│                          Command (sealed allowlist)
├─ collectors/             AppOpsCollector, AlarmCollector, NetstatsCollector,
│                          PackageCollector, UsageCollector
├─ rootcap/     [consent]  NflogCapture, BpfLoader, SslUprobeCapture, FileAccessMonitor,
│                          SystemCaModule
├─ enrich/                 UidResolver, DnsIpMap, GeoIp, TrackerDb, CompanyMap
├─ analysis/               RuleEngine, rules/*, RiskScorer, DailyReportBuilder
├─ apkscan/                ManifestParser, CertReader, DexScanner, VdexExtractor,
│                          StringExtractor, NetworkSecurityConfigParser
└─ ai/                     ModelManager, GemmaEngine, PromptBuilder, Tools,
                           SchemaValidator, AiJobQueue
```

Feature screens live in `app/feature/*` until a feature grows large enough to justify its own module.

`[consent]` components ship in the single build (D-048) but stay inert until the Danger zone consent is recorded. The only flavor dimension is `routing` (D-049); see below.

## Dependency rules

- `core/model` depends on nothing Android-specific.
- `capture`, `collectors`, `rootcap`, `apkscan` write **only** through `core/db` repositories.
- `analysis` reads from `core/db`, writes findings to `core/db`.
- `ai` reads from `core/db` via read-only tool interfaces; it **never** depends on `privilege`, `rootcap` or `mitm`.
- `app` depends on everything; no module depends on `app`.

## Tech stack

| Concern | Choice |
|---|---|
| Language | Kotlin (latest stable), coroutines + Flow |
| UI | Jetpack Compose, Material 3 Expressive (`MaterialExpressiveTheme`) |
| Navigation | Navigation Compose (type-safe routes) |
| DI | Hilt (or Koin — decide in DECISIONS.md) |
| DB | Room + SQLCipher; blobs as encrypted files |
| Background | Foreground service for capture; WorkManager for analysis, AI, retention |
| Netstack | tun2socks-style userspace stack (gVisor netstack via Go/gomobile, or a Rust stack via JNI) — see DECISIONS.md |
| Privilege | Shizuku API (+ AIDL UserService), libsu |
| LLM | LiteRT-LM (Gemma 4 E2B/E4B `.litertlm`), llama.cpp fallback |
| APK | `dexlib2`, an AXML parser, `apksig`-style cert reading |
| GeoIP | Offline DB-IP Lite / GeoLite2 (license-checked), updated with app releases |

## Process and threading model

- **`:vpn` process (optional):** keep `WaydVpnService` in its own process so a UI crash never drops the tunnel. Communicate via a bound service + Flow over AIDL, or just via the DB.
- **Tun loop:** one dedicated thread reading the tun fd, handing packets to the netstack (native).
- **Flow events:** batched (e.g. every 500 ms or 500 events) into Room transactions.
- **Analysis:** WorkManager periodic job + on-demand when the user opens a report.
- **AI:** single-slot queue; one inference at a time; cancellable.

## Main data flow (recording)

1. User taps **Record** → consent check → `VpnService.prepare()` → start foreground service.
2. Tun packets → netstack → outbound connections via `protect()`ed sockets.
3. Every new connection: `getConnectionOwnerUid()` → FlowTracker opens a `Flow`.
4. Parsers attach DNS answers, SNI, HTTP messages; cleartext bodies → BlobStore.
5. On flow close: bytes/durations finalised; batched to DB.
6. Collectors (if privileged) run on a schedule and on session stop.
7. Session stop → enrichment pass → rule engine → report ready notification.

## Builds

| Variant | Includes | Release asset |
|---|---|---|
| `core` | everything above; decryption/root features consent-gated | `wayd-vX.Y.Z.apk` |
| `clash` | `core` + `routing/` Mihomo transport, profiles, resources/GeoIP, Clash screens (`app/src/clash`) | `wayd-clash-vX.Y.Z.apk` |

Same application id and signing key: installing one over the other keeps data. Both are distributed via GitHub / sideload.

## Routing boundary

WAYD keeps sole VPN ownership. Local blocks run before routing; only allowed flows of routed apps reach the routing transport, which never sees inspection stores, findings, AI outputs or CA keys. Proxy failure never falls back to Direct. Contract and acceptance: [14-OPTIONAL-ROUTING.md](14-OPTIONAL-ROUTING.md).
