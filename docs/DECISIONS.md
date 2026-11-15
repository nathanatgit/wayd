# Decisions log

**D-038 · 2026-11-10 · portable rule identity and import review gate**

Add a nullable unique portable UUID, revision and explicit import-review state to persistent blocking rules in Room v5. New durable rules receive a UUID; v4 rules preserve all existing behavior and get an ID later during transactional export. The native policy excludes pending-review rules even when a matching per-app consent already exists. Future import must set the review flag regardless of enabled state or saved grants; review and fresh consent are not implemented in this slice. No importer, exporter, permission, network endpoint or change to session-rule lifetime is introduced.

Format: ID · date · status (proposed / accepted / superseded) · decision · why · alternatives.
Add new entries at the bottom. Never delete; supersede instead.

---

**D-001 · 2026-11-09 · accepted**
Local-only product. No servers, accounts, analytics or crash upload.
*Why:* trust is the product; simplifies PIPL position. *Alt:* opt-in telemetry (rejected for v1).

**D-002 · 2026-11-09 · accepted (supersedes the Go proposal)**
Netstack: Rust userspace stack (smoltcp-based) via JNI, built with cargo-ndk.
*Why:* Nat wants an efficient one: no Go runtime/GC, smaller APK, lower memory and battery cost. *Alt:* gVisor via gomobile (mature, heavier), lwIP (C, older).
*Consequence:* needs rustup, Android NDK and cargo-ndk installed; native code lives in `capture/native/`.

**D-003 · 2026-11-09 · accepted**
Rules decide severity; Gemma only explains. AI output must cite evidence IDs and pass schema validation.
*Why:* small on-device models hallucinate; captured data is adversarial.

**D-004 · 2026-11-09 · accepted**
UI in Jetpack Compose with Material 3 Expressive from the `material3` 1.5 alpha line, pinned; experimental components wrapped in `core/ui`.
*Why:* Expressive APIs aren't in 1.4 stable. *Alt:* Views + MDC (AppDualZuku style) — rejected: Expressive is Compose-first.

**D-005 · 2026-11-09 · accepted**
Privilege abstraction with sealed `Command` allowlist; reuse AppDualZuku's Shizuku `ShellClient`; libsu fallback for root.

**D-006 · 2026-11-09 · accepted**
Two flavors: `standard` (no MITM/root plaintext) and `research` (all). **Superseded by D-048.**

**D-007 · 2026-11-09 · proposed**
LLM runtime: LiteRT-LM with Gemma 4 E2B default, E4B on ≥ 12 GB RAM; llama.cpp fallback. Model hosted on own mirror/ModelScope.

**D-008 · 2026-11-09 · accepted (M0)**
DI: Hilt. *Alt:* Koin (simpler, runtime).

**D-009 · 2026-11-09 · accepted**
WAYD source is **GPL-3.0-or-later**; reusing GPL code from PCAPdroid / NetGuard is allowed. Keep upstream copyright headers and note provenance in the file header of any reused code.

**D-010 · 2026-11-09 · accepted**
Toolchain pins for M0: AGP 9.5.1 (built-in Kotlin, no `kotlin-android` plugin), Kotlin 2.4.21, KSP 2.3.13, Hilt 2.60.1, Room 2.8.5, SQLCipher 4.20.0, Compose BOM 2026.09.00, Gradle 9.8.1. `compileSdk` 37 (required by current AndroidX), `targetSdk` 36, `minSdk` 29.
*Why:* latest stable at 2026-11-09; minSdk 29 for `getConnectionOwnerUid`.

**D-011 · 2026-11-09 · accepted**
`material3` pinned to **1.5.0-rc01** (no longer alpha), overriding the Compose BOM. Supersedes the "alpha" wording of D-004; wrapping experimental APIs in `core/ui` still applies.

**D-012 · 2026-11-09 · accepted**
DB key: random 32-byte SQLCipher passphrase, wrapped by an AES-GCM Android Keystore key, stored in `noBackupFilesDir/db.key`.

**D-013 · 2026-11-09 · accepted**
Outbound relay sockets bypass the VPN by excluding WAYD's own package (`addDisallowedApplication`) instead of calling `VpnService.protect()` per socket.
*Why:* no JNI callback per socket, simpler and faster. *Cost:* WAYD's own traffic is not recorded (it has none).

**D-014 · 2026-11-09 · accepted**
Native stack = `ipstack` 1.0.1 (tokio, 2 worker threads) + custom relay in `capture/native`; JNI surface is `nativeStart` / `nativeStop` / `nativePoll` with a binary event queue (format in `events.rs`). Built by Gradle via cargo-ndk (arm64-v8a only for now; `-PskipNative` reuses a prebuilt `.so`). Host toolchain on Windows: Rust GNU target + portable MinGW (winget `BrechtSanders.WinLibs.POSIX.UCRT`), NDK r29.

**D-015 · 2026-11-09 · accepted**
Analysis is app-oriented (Nat's request): the Apps tab and app detail are the primary surfaces ("what is this app doing"); the daily report links into them. Findings are stored per app and window (`createdAt` = window start) and replaced on each re-run.

**D-016 · 2026-11-09 · accepted**
Enrichment seed = hand-curated `SeedDomains` (owner/purpose of well-known domains, ~350 entries) in module `enrich`, with suffix matching. It is a seed only; swap in a licence-checked dataset (e.g. DuckDuckGo Tracker Radar) before claiming coverage. Unknown stays unknown. Offline GeoIP and the identifier matcher are deferred (no licensed data / no message payloads yet).

**D-017 · 2026-11-09 · accepted**
Two rules beyond the original table: R-TRACKER-CONTACT and R-CLEARTEXT-HTTP (docs/05). Reason: the original rules need inputs that arrive in M3-M5, and M2 needed 8 rules computable from flows alone.

**D-018 · 2026-11-09 · proposed**
Permission-use history per app (Nat's request) = M3 AppOps collector. Feasibility on NX733J (Android 16): `dumpsys appops --history --package <pkg>` works from adb shell and returns per-op access counts and durations with flags; discrete timestamps still to be verified. In-app access needs Shizuku or root (GET_HISTORICAL_APP_OPS_STATS is privileged). Visualisation goes into the app detail as a "Permission use" lane (placeholder card exists).

**D-018 · 2026-11-09 · accepted (supersedes proposed)**
Permission-use history per app = Shizuku UserService (`privilege` module) exposing fixed methods only (`appOpsHistory(pkg)`, `appOpsGet(pkg)`; package name validated by regex, run as argv without a shell). Data = `dumpsys appops --history --package`: time buckets (about 15 min for recent history, coarser further back) with per-op, per-process-state counters (`top`, `fgsvc`, `fg`, `bg`, `cch`, `pers`). It is bucket-resolution, not exact timestamps. Shizuku is opt-in; the user grants WAYD in Shizuku's own dialog.

**D-019 · 2026-11-09 · accepted**
Privileged output must be reduced *inside* the privileged process. `dumpsys appops --history` prints every app in every bucket (about 7 MB), which exceeds the Binder transaction limit (about 1 MB) and the call fails. `HistoryFilter` keeps only bucket headers and the target package; replies are capped at 600 KB. A read failure is its own UI state (`Failed`), never reported as "Shizuku not running".

**D-020 · 2026-11-09 · accepted**
MITM engine = Rust (`capture/native/src/mitm`, cargo feature `mitm`): rustls 0.23 (ring) + rcgen CA/leaf issuing + httparse-based HTTP/1.1 capture. Built into the `research` flavor only (separate native build, `app/src/research`-style packaging; the standard library has no TLS or MITM code). Design: read the app's ClientHello (SNI) first, connect upstream TLS first, answer the app with a locally issued leaf only if upstream succeeded; any earlier failure falls back to plain passthrough by replaying the recorded bytes; an app that rejects our certificate marks the host as pinned (passthrough next time). Only HTTP/1.1 is offered via ALPN so traffic is parseable; QUIC (UDP 443) of intercepted apps is dropped so they fall back to TCP. Upstream trust = bundled Mozilla roots (`webpki-roots`); hosts whose CA is missing there fall back to passthrough.

**D-021 · 2026-11-09 · accepted**
Interception policy lives in Kotlin (only Kotlin knows the owning app): for each new flow the relay registers a decision slot, Kotlin answers via `nativeDecide(handle, flowId, intercept)`, the relay waits at most 600 ms; no answer = passthrough. Default is no interception; per-app opt-in, banking/payment/government/password-manager apps and hosts are never interceptable (list in code).

**D-022 · 2026-11-09 · accepted**
The CA's Android system-store file name is `subject_hash_old` = first 4 bytes (little endian) of MD5 over the DER subject, verified against OpenSSL (`f28c7bdf` for the test CA).

**D-023 · 2026-11-09 · accepted**
Decrypted HTTP messages are stored in the existing SQLCipher database (`Message.body`, `bodyTruncated`; schema v3 via AutoMigration), not in a separate AES-GCM blob store. The database is already encrypted with a Keystore-wrapped key, so a second layer would add code without adding protection. Bodies are capped at 128 KB per message by the native engine. Interception policy: `InterceptApp` allowlist table (empty by default), `InterceptHook` is the only seam between capture and the research build.

**D-024 · 2026-11-09 · accepted**
System-store CA install (research, root, optional toggle with warning, off by default) = volatile tmpfs overlay, no module, nothing persistent. It runs in the GLOBAL mount namespace (`nsenter -t 1 -m`) because KernelSU gives the app's `su` a private namespace, applies the overlay in every zygote namespace too, verifies each namespace and rolls back on any mismatch (a first version left the zygote with an empty store; never again). Reboot or "Disable and remove CA" undoes it.

**D-025 · 2026-11-09 · accepted**
On-device AI = LiteRT-LM (`com.google.ai.edge.litertlm:litertlm-android` 0.19.1, Apache 2.0) running Gemma 4 `.litertlm` files (E2B 2.59 GB, E4B 3.66 GB; sizes and SHA-256 are the published values from Hugging Face and ModelScope). GPU backend first, CPU fallback. Model download is the only AI-related network use: user-initiated, user-chosen host (Hugging Face / ModelScope / custom https URL), resumable, SHA-256 verified, run in a `dataSync` foreground service. The model's JSON is validated against schema v1 (unknown enums rejected, every cited evidence id must exist in the input, claims without evidence stripped, one repair retry); the rule engine keeps deciding severity.

**D-026 · 2026-11-09 · proposed**
Blocking / content control (M7) design: see docs/13-BLOCKING.md.

**D-027 · 2026-11-09 · accepted**
Releases: the shipped build is the `research` flavor as a signed **release** build under the plain application id `com.nathanhanapps.wayd` (no `.research` suffix, no `-research` version suffix); the `standard` flavor stays in the source and in CI. Signing uses Nat's existing key (alias `key1`, PKCS12, from `NebulaThemePorterKey`); it is never committed: locally it comes from a git-ignored `keystore.properties` or `WAYD_KEYSTORE_*` environment variables, in CI from the repository secrets `WAYD_KEYSTORE_BASE64`, `WAYD_KEYSTORE_PASSWORD`, `WAYD_KEY_ALIAS`, `WAYD_KEY_PASSWORD`. Pushing a tag `vX.Y.Z` (must equal `versionName`) runs `.github/workflows/release.yml`: Rust and unit tests, signed `assembleResearchRelease`, `apksigner verify`, then a GitHub release with `wayd-vX.Y.Z.apk` and its SHA-256. `-PappIdSuffix=.x` installs a build next to another for testing. Supersedes the "research is a separate app id" part of D-006.


**D-028 · 2026-11-09 · accepted product direction; implementation planned**
Keep WAYD focused on local inspection and control. Replace the absolute 'nothing leaves the phone' promise with no automatic uploads of inspection data, reports or AI analysis, acknowledging app forwarding, user exports and downloads. Clash/FlClash-style routing is an optional extension: WAYD owns the VPN and blocking precedence, the extension gets minimum routing inputs, routes are visible, and direct fallback requires opt-in. M7 and routing remain unimplemented. See 14-OPTIONAL-ROUTING.md; engine and integration choices remain open.

**D-029 · 2026-11-09 · accepted**
M1 TCP hostname parsing uses a bounded 16 KiB upstream prefix across native DATA chunks; native prefix reporting uses the same limit. Parse only complete HTTP heads or TLS ClientHello handshakes (including multiple handshake records), then release the Kotlin buffer; stop inspecting at the limit while relaying all remaining bytes normally. This fixes segmentation-dependent hostname loss without retaining unbounded payloads or guessing a host from a truncated handshake. Larger prefixes, ECH, QUIC and encrypted DNS still have coverage gaps. Host-side tests compile the portable relay/shared modules on Windows too; Unix-only tun/engine code remains gated.

**D-030 · 2026-11-10 · accepted**
M1 unresolved owner lookups may retry the same exact tuple on at most two DATA events within one second of OPEN. Never guess from neighbouring flows, retry after close, replace a resolved owner, or reopen a previously denied MITM decision. This handles transient lookup failures without claiming to fix every unknown flow. DNS-over-TCP fallback consumes bounded length-framed replies across chunks; malformed, incomplete or oversized frames never create a hostname. Native TCP inspection remains limited to the first 16 KiB per direction; no additional payload retention or privileges are introduced.

**D-031 · 2026-11-10 · user-directed sequencing**
Nat accepts the current capture coverage for moving forward to M5. This supersedes the bedtime instruction to wait for formal M1 acceptance before M5, without changing measured coverage or declaring router/IPv6 acceptance complete. GeoIP is explicitly deferred until after Clash feature integration. No raw overnight capture export is required for this progression or authorized by it.

**D-032 · 2026-11-10 · M5 implementation**

Discovery may reuse an existing Shizuku grant through a fixed `Command.ListPackages` current-user inventory call; it never prompts automatically or reads privileged APK directories. Worker concurrency is capped at two APKs. The package-list parser rejects invalid names and caps replies/counts; a real shell check on the NX733J returned 535 current-user packages. This is command-shape evidence, not proof that the app holds a Shizuku grant or can read all those APKs.

Static APK inspection runs only on user request as unique, cancellable, non-expedited WorkManager work (pinned stable 2.11.2), at most two APKs concurrently to limit memory. Cache by scanner version, package version/update time and base/split file metadata; replace facts and AppInfo atomically in the existing SQLCipher tables (no schema change). Small bounded binary-XML and DEX metadata readers avoid a decompiler dependency: DEX 035/037–040, defined-class SDK prefixes, ASCII string hosts only; no code execution, free-form shell, APK uploads or embedded credential storage. The prefix list is a small original curated seed, not a licensed third-party tracker database. Unsupported/container DEX, odex/VDEX, malformed APKs and caps remain explicit coverage gaps. Signer subjects are self-asserted; show fingerprints without asserting verified company identity. Declared/Used/Sent compare static facts with bounded retained history and exact host equality; they do not infer permission-to-payload causation. Sources: [DEX format](https://source.android.com/docs/core/runtime/dex-format), [WorkManager releases](https://developer.android.com/jetpack/androidx/releases/work).

**D-033 · 2026-11-10 · control investigation; proposed defaults, implementation pending**

Nat requests history-based temporary/permanent blocking, site matching, response customization, rule backups and future Clash compatibility. Investigation confirms only a minimal BlockRule entity exists: relay currently starts outbound dialing before hostname inspection completes, HTTP capture is passive, and no control UI/enforcement is wired up. Do not claim existing protection or add buttons without enforcement.

Proposed defaults: Block this time means app + exact target for the current capture session (including reconnects), expiring on stop/engine restart; Block forever persists until disabled/deleted and requires WAYD running. Disconnect live flow is a separate action. Edits affect future messages, never historical evidence. Any applicable local BLOCK wins; remove the earlier contradictory general ALLOW precedence. Start with app/host/IP gates, then selected research request-time static local replies, then bounded deterministic response edits. No general script/JavaScript injection or per-request AI. Battery limits are proposed budgets until measured. Portable WAYD JSON excludes history, grants, secrets and temporary rules; import reviews identity/scope and cannot transfer consent. Clash is a routing adapter under WAYD policy/VPN ownership; YAML export is an explicitly reported subset, not the lossless backup. See [13-BLOCKING.md](13-BLOCKING.md) and [14-OPTIONAL-ROUTING.md](14-OPTIONAL-ROUTING.md). No runtime/schema/UI changes in this investigation; exact lifetimes and limits are recommendations rather than claimed user-approved behavior.

**D-034 · 2026-11-10 · M7 core enforcement and history controls**

Implement the accepted first slice as small typed native APP/exact-HOST/exact-IP BLOCK snapshots, independent of any Clash engine. Any matching block wins; no ALLOW or executable predicates. Exact observed host is preferred, otherwise explicit IP with shared-address warning. Resolve only single-package owner identities and bind durable rules to signer/package/current Android user plus per-app ConsentRecord. Session rules live in memory until capture stops or engine/process restarts. Room v4 preserves old data and leaves unbound legacy rules inactive.

Native app/IP gates precede dialing/sending; visible TCP hosts use the existing parser and a bounded 16 KiB/600 ms prefix gate, with a one-second owner gate. Unknown metadata is recorded as a coverage gap, not guessed from shared DNS/IP evidence. Required-gate failure closes its flow. Atomic acknowledged installation updates active known flows, preserving counters; old native ABI capability fails startup. Serialized native ownership prevents UI rule updates from racing engine destruction. Offer bilingual history confirmation, Undo and an always-reachable global removal list. Literal history search runs only on bounded retained rows. Device acceptance and power measurement remain open. Response transformations, portable JSON backup/import and actual Clash integration are separate later slices; GeoIP follows Clash. No evidence rewriting, scripts or new destinations/permissions.
**D-037 · 2026-11-10 · dashboard and independent traffic recording**

Home replaces the Record tab with a dashboard. Start/Stop WAYD owns VPN forwarding and rule enforcement; Record traffic independently controls new flow/DNS/message persistence, default off after process restart. A monotonically changing recording window excludes connections spanning a toggle; no retroactive partial capture. Keep bounded owner/host parsing and aggregate counters for processing while off. Preserve old evidence and allow previously completed batches to finish; engine session metadata and independent permission collectors are outside this traffic toggle. Decline new research interception decisions when off, without dropping existing intercepted connections or revoking consent. Existing session rules expire on VPN engine stop, not recording changes. Show future Clash as a disabled switch with no routing behavior. No new schema, network destination, privilege or periodic worker; battery effects remain unmeasured. Add illustrative examples, explicitly separating supported exact blocking from planned regex and response controls.

**D-036 · 2026-11-10 · unified Controls inventory**

Provide a dedicated Controls / 管控 bottom tab for all apps' saved and session rules, independent of history windows. Reuse BlockingRules and native-acknowledged active IDs; persistence alone never means enforcement. Show effective/total counts, literal package/host/IP search, status filters, target/lifetime, expandable identity binding and confirmed removal. Refresh on resume or explicit action without periodic polling. Replace the former Apps modal with a link to this inventory. Explicitly mark alterations unavailable until local replies/response replacement are implemented. No enforcement, permissions, schema, consent or routing changes in this UI slice.

**D-035 · 2026-11-10 · drain native relays before runtime shutdown**

Android regression reproduced a leaked TUN duplicate after Stop raced blocked TCP teardown. ipstack 1.0.1 TCP destructors synchronously wait for protocol tasks, so shutdown_background before relay cleanup can strand workers/resources. Own relay tasks in a JoinSet, stop accepting, abort/drain relays while the runtime is active, then release the stack and stop the runtime. Bound owner cleanup to two seconds and runtime shutdown to one second; keep the polling thread as the native handle owner with existing serialized JNI access. Drain completed relay handles during normal recording. No periodic cleanup task, new permissions or routing change. Synthetic Android lifecycle tests and installed-app start/stop checks verify resource release; battery cost is not measured.

**D-039 · 2026-11-10 · reviewed persistent rule transfer**

Implement a strict, versioned, bounded JSON subset for persistent exact block definitions. Reject unknown keys/actions, malformed UTF-8, duplicate decoded keys/UUIDs, noncanonical identities/targets and excessive nesting/size. Export assigns missing UUIDs transactionally and defaults to target redaction. Import keeps existing UUIDs, previews current-profile identity gaps, commits pending-review definitions atomically without grants, and offers undo only while the batch remains unreviewed. Per-rule explicit review rechecks installed signer/profile/target and obtains fresh consent before clearing the gate. No automatic rebind, remote sync, session-rule transfer or credential/history export. Both locales expose document-picker and provider/privacy warnings. This completes the local first transfer workflow, subject to device acceptance; it does not implement response editing or Clash routing.

**D-040 · 2026-11-10 · initial optional Mihomo transport**

Pin Mihomo v1.19.33 outbound adapters and Go 1.27.3, recording module hashes and source/license notices in routing/. Use a strict bounded Clash subset (SOCKS5/HTTP CONNECT, numeric proxy IPs, static first-entry select groups, package/domain/IP rules and final MATCH), rather than loading Mihomo hub/executor, listeners, DNS, resource providers or arbitrary profiles. Reject unsupported/security-sensitive fields. WAYD supplies live metadata and selects routes; its native gates and cancellation remain authoritative before dispatch. The private version-1 C ABI returns Unix socketpairs with cancellation leases; no listening port or inspection-store/CA handles. JNI start capability is version 2 to reject older native libraries that could ignore routing arguments. TLS fallback uses the same route. No Direct retry on proxy error. The same-UID library is not OS isolation. Release distribution audit remains separate from this source implementation.

**D-041 · 2026-11-10 · explicit routing grants and secret lifecycle**

Document-picker import requires current-user, single-UID app selection and fresh signer-bound ConsentRecords, and saves disabled. Keep profiles AES-GCM encrypted under Android Keystore in no-backup private storage; no credential exports. Configure only while WAYD is stopped; enabled invalid profiles fail startup, and selected-package changes stop active routing before revalidation. Delete destroys profile/key and revokes current-user routing records even if the file is damaged. Unselected/unknown/shared-UID flows explicitly remain Direct. No DNS bootstrap, automatic downloads, subscriptions or health probes; retain visible underlying-DNS and IPv6 limitations. Aggregate observed Direct/Proxied/Blocked/Error transport outcomes in session UI without a routing-history schema; reconnect/TLS fallback can add outcomes. Routing does not enable inspection recording. Broader protocols/profile compatibility, installed-VPN/DNS acceptance and research response controls remain open.

**D-042 · 2026-11-10 · shared GeoIP and optional Clash extension resources**

Use one validated, private no-backup country.mmdb asset for both optional Mihomo GEOIP routing and WAYD flow country enrichment. Country enrichment performs local numeric-IP lookup without sending IPs to a geolocation service; private/unknown destinations remain unknown. Preserve database routing categories for Clash but expose only two-letter country codes as WAYD countries. Existing Room Flow.country is reused, with no schema change. Explicit HTTPS downloads show resource hosts; users can edit the GeoIP source or choose download through the first embedded proxy node, without Direct retry. Profile source is bounded to 4 MiB, individual provider YAML to 8 MiB, resources to 28 MiB, compiled bundle to 48 MiB, and encrypted profile catalog to 70 MiB. Domain/IP providers use upstream indexed strategies. Native Compose extension screens reuse WAYD VPN ownership and per-app consent; this is not bundled Flutter FlClash or full config compatibility. GEOSITE, ASN, MRS, subrules, full DNS handling, persistent subscription refresh and complete health/event controls remain open. No automatic updates or health probes.

**D-043 · 2026-11-10 · bounded, cancellable import downloads**

Resource review shows current index/host and errors inside its modal. Explicit cancellation reaches native HTTP context; each fetch is bounded to 45 seconds and the batch to four minutes. Retry reuses at most 28 MiB of memory-only assets. Optional jsDelivr rewriting requires an explicit checkbox and applies only to public raw GitHub URLs without credentials, query, fragment, custom port or escaped path segments. Bootstrap instantiates only the selected embedded node. No silent mirror or Direct fallback.

**D-044 · 2026-11-10 · light background path, app-like UI, per-resource imports**

Home observes no Room table (its coverage and recent-flow queries full-scanned the encrypted Flow table on every 500 ms flush); connection history moved to Traffic → Connections, loaded in pages on resume. Insights loads on resume/window change instead of observing `observeDays`. `AnalysisService` caches one result per window, reused while `MAX(Flow.id)` is unchanged or for 20 s. App icons decode off the main thread. In the VPN loop the notification channel/foreground call happen once; later text updates use `notify()` only when counts change and the screen is interactive (5 s). The live per-connection table in `CaptureState` is tracked only while a screen collects `connections` (the extension's Connections tool). The speed chart samples in-memory counters once a second only while a dashboard is visible. UI follows a flat, FlClash-like style: VPN switch in the header, speed chart, small stat tiles, inset lists; no explanatory paragraphs outside consent/decision points. Clash import downloads each resource independently (three at a time), shows per-row status with stable error codes, and retries only failures. Direct downloads use Android's `HttpURLConnection` (system DNS, per-app network policy, platform trust); "first node" downloads stay native. Native downloads replace the 45 s wall clock with a 4 min cap plus a 25 s no-progress watchdog and return classified codes (`resource_dns/connect/tls/timeout/stalled/proxy/http_N/...`). Supersedes D-043's per-fetch/batch deadlines.

**D-045 · 2026-11-10 · routing access control modes and fast profile storage**

Nat chose FlClash-style access control with both whitelist and blacklist, using WAYD's app list. Import only stores a profile; access control is separate. Blacklist mode is the one approved exception to per-app consent (AGENTS.md rule 9): a single revocable global consent covering future apps too, still below WAYD blocks and never routing unknown/shared-UID owners or other users. Routing storage moved from one Keystore-encrypted file (read 3× and written 2× per Save; 31.9 MB on the test phone, minutes of apparent hang) to an index plus per-profile files under a Keystore-wrapped software AES-256-GCM data key, with the index cached in memory. Details in docs/14 "Access control modes".

**D-046 · 2026-11-11 · external OpenAI-compatible AI provider, AI/Settings reorganisation**

Nat asked for custom models through an OpenAI-compatible API with a warning that traffic records may leak. Implemented as an opt-in exception to AGENTS.md rule 1 (see the rule text and the threat model). `OpenAiCompatibleClient` asks for schema-constrained JSON, steps down to `json_object` and then prompt-only for servers that reject `response_format`, never follows redirects, bounds replies, maps failures to stable `ext_*` codes. `ProviderEngine` routes between on-device Gemma and the external engine. `AiAnalyzer.analyse` takes an approval callback that defaults to declining, so no caller can send data by accident; stored answers are keyed per model. Verified against a real Ollama (`gemma4:e2b`, first call 1m39s cold: read timeout is 300 s). Settings is now a hub (AI, Privileges, Decryption/research, About) with sub-pages. On-device download source is one global choice (Hugging Face / ModelScope) with a per-model custom address, and downloading vs paused is distinguished.

**D-047 · 2026-11-11 · connection detail and three export formats**

Tapping a connection opens a detail page (overview, TLS, rule outcome, DNS, decrypted content with Text/Hex view). Exports: (1) `WAYD detail JSON` v2: flows, sessions, per-app facts, DNS, permission/access history, findings, coverage, optional decrypted messages, with evidence ids shared with the AI; (2) `PCAPdroid CSV`: PCAPdroid's 17 connection columns (read from its source), unquoted with commas replaced, ISO-8601 times; (3) `Traffic PCAP`: classic libpcap (link type 101) **reconstructed** from records. WAYD stores metadata, not packets, so endpoints, times and decrypted payload are real, packets are synthesised with valid checksums, blocked flows are SYN+RST, flows without decrypted content have handshake and close only, and no bytes are invented to match volume. Exports page flows by keyset so long histories do not load into memory. Connections paging was also fixed to keyset (rows sharing a timestamp were skipped at page boundaries).

**D-046 addendum · 2026-11-11 · thinking models and automatic model lists**

Reasoning models (verified with `qwen9b-4k` on Ollama) can spend the whole context thinking and return an empty answer (`ext_empty`). The client now tries a dialect ladder per server: schema + `reasoning_effort: "none"`, schema, `json_object`, prompt-only; a 400/422 steps down and the working dialect is remembered. The same real analysis prompt then validated in about 12 s on the phone. The model list loads automatically once the address is valid (debounced) and never sends a key that is still being typed; a typed key goes only to the address it was typed for and a stored key only to the server it was saved for (`ExternalAiUrl.keyFor`, unit-tested). The approval dialog now shows the system prompt as well as the evidence.

**D-048 · 2026-11-11 · one app build; Clash routing as a separate extension APK (Nat)** — separate-APK part superseded the same day by D-049; the `research` merge stands.

The `research` flavor is merged into `standard`: one build, no product flavors. Releases already shipped `research` (D-027), so the store-safe `standard` build had no users. MITM, root system-CA, developer console and `rootcap/` ship in the single build and stay inert until the Danger zone consent (AGENTS.md rules 7 and 9). The native capture library is always built with `--features mitm`. Clash routing leaves the main APK: Mihomo/Go (`libwayd_route.so`), profile storage (`RoutingSettings`), resource downloads and the extension screens move to their own APK, signed with the same key, found through the existing `CLASH_EXTENSION` intent with a caller/signature check. WAYD keeps sole VPN ownership and block precedence; allowed flows of routed apps are handed to the extension as socket descriptors over a signature-protected bound service (planned; the current socketpair C ABI already has this shape), which also gives real UID isolation. The main app must build, run and pass tests with the extension absent (GeoIP absent → `Flow.country` null). Supersedes D-006 and the flavor part of D-027; CI/release become `assembleDebug` / `assembleRelease`.

**D-049 · 2026-11-11 · two release variants: `core` and `clash` (Nat)**

Instead of a separate extension APK, WAYD ships two variants of the same app: `core` without Clash routing and `clash` with it built in. One flavor dimension `routing` replaces `dist`; the `research` merge from D-048 stays, so both variants include consent-gated decryption and the native capture library is always built with `--features mitm`. Both use application id `com.nathanhanapps.wayd` and the same key: installing one over the other keeps data, and only one can be installed at a time (only one VPN can run anyway). `core` packages no `libwayd_route.so`, no Mihomo/Go, no routing settings or Clash screens, which also keeps the GPL Mihomo component and proxy features out of the plain build. Clash sources move to `app/src/clash`; `main` keeps the existing `CLASH_EXTENSION` intent lookup, so `core` hides Clash rows and the country panel and `Flow.country` stays null. The in-process socketpair C ABI is unchanged, so no new IPC is needed. Switching `clash` → `core` leaves an encrypted routing profile in `no_backup/routing/`; `core` keeps it untouched (Nat confirmed) so reinstalling `clash` restores the setup; neither variant deletes the other's data. Release: `assembleCoreRelease` + `assembleClashRelease`, assets `wayd-vX.Y.Z.apk` and `wayd-clash-vX.Y.Z.apk`, each with `.sha256`. Supersedes the separate-APK part of D-048. The `CLASH_EXTENSION` lookup resolves only inside WAYD's own package, so a `core` install never shows Clash because another build or app declares the action.

**D-050 · 2026-11-11 · external AI calls follow Clash routing (Nat)**

Nat needs the external AI provider to work while WAYD's Clash routing is on (on campus Wi-Fi, Google is only reachable through a proxy). WAYD's own traffic stays outside its VPN (`addDisallowedApplication`; the relay relies on that instead of `protect()`), so the AI call cannot simply enter the tunnel. Instead, in the `clash` variant, the routing library gets a `connect` command (`routing/connect.go` `DialName`, `cmd/wayd-route` `connect`) that applies the profile's rules to the AI host with WAYD's own package as owner, dials **by name** through the selected node (the node resolves it, so poisoned local DNS cannot redirect the call; IP/GEOIP rules cannot match a name and are skipped like `no-resolve`), and hands back one end of a socketpair. WAYD runs TLS itself over that stream (`ai/ExternalAiStream.kt` `EngineTls`: platform trust, endpoint identification `HTTPS`, SNI, plus the platform hostname verifier) and HTTP/1.1 (`StreamHttp`, bounded, `Connection: close`, no redirects). The routing library and the proxy operator therefore only carry ciphertext: the API key and the masked evidence never reach them readable (AGENTS.md rule 7 keeps holding). Recorded app traffic keeps the same order: WAYD inspects plaintext, then re-encrypts, then Clash adds the node's encryption on send and removes it on receive.

When it applies (Nat's choice): routing enabled, the VPN running, and access control selecting WAYD itself (checked in whitelist mode; not excluded in blacklist mode). Otherwise, and always for private/LAN servers and plain `http://`, the call uses the normal network as before. When it applies but the rule rejects the host or the node fails, the analysis fails with `ext_route` and is never retried Direct. Only extension bundles support name routing; a legacy profile fails closed. At most 8 concurrent routed connections, each capped at 15 minutes. `ai/` depends only on a small `ExternalConnector` interface; `core` binds a connector that never routes.

**D-051 · 2026-11-11 · a label on every recorded connection (Nat)**

Every row in Traffic → Connections carries one label decided by rules, never by AI (AGENTS.md rule 2): **Danger** if a HIGH finding cites the connection as evidence, **Warn** for MEDIUM, **Sensitive** for a LOW/INFO finding that names personal data types, **Ad** for an ADS or ANALYTICS host in the tracker list, otherwise **Normal**; the highest wins (`analysis/ConnectionLabels.kt`). The list runs the rules for each day it shows (cached per day, the same findings Insights uses). After an AI analysis, connections the AI cited also show an outlined, neutral hint such as "AI: ads?" (`ai/AiHints.kt`): each stored answer is re-validated against the evidence ids it was given, the newest answer wins, UNKNOWN is not shown, and the hint never changes the label or its colour.

**D-052 · 2026-11-11 · models in Download/WAYD, checked against the published hash (Nat)**

On-device models can live in a folder the user grants once with the system folder picker (suggested: Download/WAYD; storage framework, no new permission), so they survive reinstalls and can be copied in by hand under the official names (`gemma-4-E2B-it.litertlm`, `gemma-4-E4B-it.litertlm`). Without a folder, app storage is used as before; a folder copy wins over an app-storage copy. A file is checked on first use and whenever its size or modified time changes: WAYD hashes it and compares the SHA-256 with the value the chosen source publishes **now** (Hugging Face `/api/models/<repo>/tree/main` `lfs.oid`, ModelScope `/api/v1/models/<repo>/repo/files` `Sha256`), falling back to the pinned value when offline or with a custom address. Pinned values were confirmed against both APIs on 2026-11-11. Equal → loads. Equal to the pinned value but not to the published one → "older version". Neither → "damaged or edited". On a mismatch nothing is loaded until the user chooses: download again, place the file by hand and re-check, or use it anyway (remembered only for that exact file; any later change asks again). The original `/proc/self/fd` loading approach is superseded by D-053. The listing request goes only to the source the user chose, carries no user data, and happens only during a check.

**D-053 · 2026-11-11 · stage folder models under a real `.litertlm` filename**

Nat's NX733J screenshot confirmed `Failed to create engine: INVALID_ARGUMENT: Unsupported or unknown file format` with the verified E4B in Download/WAYD. LiteRT-LM's filename-based format dispatch rejects `/proc/self/fd/N` (upstream [issue 3905](https://github.com/google-ai-edge/LiteRT-LM/issues/3905)). Folder models now use a private cache copy named `<accepted-sha256>-<catalog-name>.litertlm`. The copy is streamed, size-limited and hashed against the accepted file's digest before atomic publication; failed or cancelled copies are removed. A mismatch still fails closed unless Nat chose Use anyway, in which case that exact accepted digest is used. Private copies are reused, old copies of the same model are removed on replacement, and Delete removes its runtime copy too. Android may evict cache, requiring another copy. This costs one extra model-sized allocation (E4B ~3.66 GB); Download/WAYD remains the reinstall-safe original. No new dependency, permission, network destination or custom-model catalog is added.

**D-054 · 2026-11-11 · app-detail hierarchy and collapsed connection history (Nat)**

App detail uses a compact persistent identity header, a single scrolling report, side-by-side scanner/export actions, a tonal summary with separate sent/received/connection totals, and grouped Material surface containers for activity, destinations, findings, permissions and AI. Connection history and blocking is one card, collapsed initially; its whole header toggles the search, controls and compact history rows. Expansion survives configuration changes. Existing rule operations and confirmation dialogs are unchanged. Shared badges use paired Material container/on-container colors rather than translucent accent fills, and the hourly chart has a keyed theme-colored legend and reference lines. All copy reuses existing bilingual resources; no new permission, dependency or network destination.

**D-055 · 2026-11-11 · remember external-AI previews, reset from Settings (Nat)**

Nat explicitly requested an unchecked Don't ask again checkbox on the external-AI approval dialog and Settings → Re-enable all warnings. This amends D-046 and AGENTS.md rule 1: only Send with the box checked records the choice, separately from the mandatory origin acknowledgement, as a versioned/revocable ConsentRecord keyed by a hash of the full server URL and model. Copy discloses that future user-started analyses, including selected decrypted content, skip the preview. Server/model edits, acknowledgement withdrawal and provider removal revoke remembered choices. Reset revokes all optional preview records, preserving provider/key, capture/decryption/routing consents and rules. No background analysis or automatic upload is introduced; missing approvers still refuse sends. No stored schema or permission/dependency/destination changes.

**D-056 · 2026-11-11 · traffic directions and decrypted messages belong in connection history (Nat)**

Each app-history connection shows theme-colored up/down icons, labels and byte counts. Rows with stored DECRYPTED evidence get a tertiary-container surface plus a text/check marker; TLS alone never implies decryption. Clicking a connection expands it inline, including an explicit blocking action and stored request/response items that expand to bounded headers/text/hex using the existing message renderer. The separate app-detail message-list button is removed. Read-only DAO projections observe message counts/tiers without loading bodies; only one expanded connection loads its first 50 messages, with a visible limit notice. The existing latest-200 / shown-30 connection scope remains disclosed. No table/entity/schema changes or capture/decryption behavior changes.

Nat also requested adjacent pills linking separate related TCP records. The visible records are grouped conservatively by session, Android user/UID/package, capture source, IP, destination port and hostname, with the entire group's start times within 30 seconds (no unbounded time-gap chaining). UDP is never paired. Each pill switches the displayed record's own counters, messages and blocking target in place. Decrypted evidence is marked on the relevant pills and group. Copy calls them nearby records and explicitly avoids asserting a shared TCP session: source ports are not stored. This grouping affects presentation only, never rule evaluation or capture.

**D-057 · 2026-11-11 · seven-day app activity heatmap (Nat)**

The app-detail 7-day window uses seven local-date rows and 24 hourly cells, with a five-step theme-colored traffic scale. The report builder retains per-date/hour sent and received bytes from the full window's recorded internet flows, not the capped connection-history list; bins use connection start time and the same local zone as daily windows. Empty dates render empty cells, never a claim of complete capture. Nat's GitHub reference follow-up makes the grid compact (18 dp date rows at normal font size), removes the slider and visible explanatory paragraph, and shows hour/sent/received detail only after tapping a cell. The full chart remains one large accessibility target, with previous/next hour/day actions for nonvisual navigation. Today/yesterday keep their existing hourly chart. Regression tests cover local midnight separation, totals, LAN exclusion and TCP grouping boundaries. No dependency or stored schema changes.

The same heatmap also replaces the hourly chart for Insights → 7 days (Nat's final screenshot showed this selector). Insights keeps the old chart's all-recorded-flow scope, including LAN and unknown-app connections; app detail retains its internet-traffic scope. Each chart's accessibility description states its scope. A regression test checks Insights totals across local midnight, including LAN and unknown-app traffic.

**D-058 · 2026-11-11 · two directly visible HTTPS trust methods (Nat)**

Nat requested removing the redundant collapsed Danger zone card from the dedicated HTTPS decryption page. Shared risk text, enable switch and per-app selection remain above two method cards: manual user-CA installation/export and optional system-wide trust requiring root. Both method cards are always visible; actions stay disabled until consent and CA creation succeed. The existing full-screen warning, scroll/checkbox privacy statement, separate root confirmation, captured-content deletion and disable/remove confirmation remain intact. Neither page entry nor layout expansion starts decryption, creates a CA or invokes root. Controller, certificate, privileged commands and privacy-statement text/version are unchanged; only presentation/navigation is reorganized.

**D-059 · 2026-11-11 · recorded app-traffic dashboard (Nat)**

App detail adopts compact controls and ActionDash's metric/chart hierarchy: a recorded-internet-byte headline with sent/received ring and metric cards, individual insight tiles with bounded percentage bars, the existing activity heatmap/hourly chart, ranked destination cards with proportional byte bars and wrapping metadata/host pills, and individual finding cards. Totals retain their existing scope (internet bytes and destinations; connection count includes LAN), explicitly disclosed alongside recording gaps. Today/Yesterday/Last 7 days pills inherit the entry window and replace the app-detail navigation entry when selected, refreshing all window-scoped report, permission, history, AI and export state together. Scan/export actions sit compactly at the right of the identity header; its duplicate window label is removed. No new dependency, schema, permission, destination or capture behavior.

**D-060 · 2026-11-11 · compact statistical dashboards for app detail and Insights (Nat)**

Nat found the app-detail and Insights cards too sparse and wordy and asked for ActionDash-style statistics.

- Shared `feature/analysis/Dashboard.kt`: tonal panel, hero (total, sent/received ring), stat tiles, share bars, ranked bar rows, percentage rings.
- App detail: insights as number tiles (full sentence kept for screen readers); destinations as a category share bar plus ranked rows (first 8, Show all to 30); findings as compact rows.
- Insights: hero with four tiles; Needs attention as severity tiles plus one row per app; hourly chart with average line; ranked apps, companies, screen-off apps and regions with a Data / Connections switch and a summed "Others (n)" row.
- Regions follow PRC naming in both languages (中国台湾 / 中国香港 / 中国澳门; no flag for Taiwan); exports keep ISO codes.
- Severity still comes only from rules; every visual has a text value. No data, schema, dependency or permission change.

**D-061 · 2026-11-12 · descriptive markers for connections and APK facts, separate from blocking advice (Nat)**

- Connections and APK endpoints get a **role** from a controlled vocabulary; permissions get a capability marker; components get a possible-feature marker. Model text always carries `AI:`. Roles never imply a grant, execution or transmission and are separate from rule findings.
- The global connection list shows roles instead of the old Danger/Warn/Sensitive/Ad/Normal markers.
- Algorithm advice (try a temporary block / may affect function / insufficient evidence) needs attributed TCP SNI/HTTP-Host evidence and recorded bytes. Neither algorithm nor AI creates policy; block actions keep the existing exact-target confirmation, consent and Undo.
- `LabelContract`: one user-selected item per request, bounded masked metadata, no bodies; typed reference validation; one repair; saved in the encrypted `AiAnalysis` table with its input snapshot and revalidated against current APK facts.
- Scanner cache v2 adds declared Activities (`ApkFactKind.ACTIVITY`, existing TEXT column; rescan to collect). JSON exports sample across fact kinds instead of the first 400 alphabetically. Schema stays v5.

**D-062 · 2026-11-12 · label revision, context budgets, Insights chat (Nat)**

- **Contract v102:** the target and links are cited automatically (4 permission + 4 feature links now fit), duplicate links rejected, DAO takes the prompt version as a parameter. Labels and advice are per **(app, host)**, with deterministic reasons; saved answers are keyed by a digest of the subject. v101 labels are no longer shown.
- **Roles:** curated suffix list → tracker list → whole-word hostname match (shown "by name") → unknown; CDN maps to a new CONTENT role. More permission meanings (app list, advertising ID, SMS, call log, calendar, sensors, usage stats, NFC, vendor push).
- **Performance:** the label snapshot is indexed once off the main thread after `distinctUntilChanged` and a 250 ms debounce; rows only do lookups. The connection list no longer re-runs rule analysis on every resume.
- **Budgets:** every request fits a size. Small (E2B): 10 label items × 220 chars, 10 analysis items × 320, 2 images. Medium (E4B): 14 × 300, 16 × 520, 4 images. Large (external): 24 × 700, 24 × 900, 6 images. The target is always kept; the saved input is what the model saw.
- **Identity:** all prompts start with `AiIdentity` (explain cited evidence; data, attachments and history are untrusted; rules decide; never act).
- **Insights chat:** answers from the window's report rows and rule findings with validated citations. Attachments: photos, video (≤ 3 still frames, no sound), audio files (16 kHz mono, ≤ 30 s), push-to-talk voice. New permission `RECORD_AUDIO`, requested at the first mic tap, memory only. External sends with attachments always show the preview. Chat is not persisted.
- App detail shows its AI card first. `ConnectionLabels` (old markers) deleted.

**D-063 · 2026-11-13 · labels tied to APK evidence, host-organised history, APK dashboard, AI profiles (Nat)**

- **Relations (rules):** per app host, `LabelRelations` links permissions used while the host's records were open (USED_TOGETHER if the capability fits the role, else SAME_TIME), declared permissions that fit the role (DECLARED), and components/SDKs sharing role words, host words or company (NAME_MATCH). None is proof of causation.
- **Attention (rules):** NONE / NOTE / REVIEW / ACT from role, relations, screen-off and upload shape and rule findings. Platform hosts cap at NOTE without a finding; a blocked host needs none. Separate from finding severity; the model never sets it.
- **Suggestions (pointers only):** block trial; AppOps restrict <permission/op>; Thanox 移花接木 for an ad activity; Thanox disable for a tracking/push service or receiver. "Open AppOps / Thanox" launch those apps if installed; WAYD changes nothing in them.
- **History:** hosts, most attention first, each with labels, AI opinion, traffic summary and a Block button; records open on demand; "passed local policy" is no longer shown. **AI check all connections** (`HistoryContract` v201): up to 60 hosts in chunks of 8 (E2B) / 14 (E4B) / 40 (external), one rule-built line per host, per-chunk failure tolerance.
- **APK page:** tiles, sensitive-capability chips, endpoint purpose bar and SDK chips first; fact lists collapsed.
- **AI settings:** on-device depth is Auto / Small / Medium with the compact prompts and one repair, no effort control. Effort and Large are external-only. Up to 12 saved external profiles; each server origin keeps its own acknowledgement; the per-send preview resets on every address/model change.

**D-064 · 2026-11-14 · global Adblock/hosts block lists (Nat)**

Nat approved: one revocable global consent per imported list; import from a file or a typed URL; rewrite phase 1 as local reject plus header/query edits; list rules use the bounded hostname wait.

- Native `hostlist.rs`: hosts as sorted 64-bit hashes (exact, suffix, exception, important, `$app` scope); per-app rules first, then lists; policy ABI 3 (`nativeSetBlockList`); Kotlin still accepts ABI 2 and reports lists unsupported.
- With a list installed each TCP connection gets the existing ≤ 600 ms / 16 KiB hostname wait, except server-speaks-first ports; an unnamed connection is forwarded as unknown-host.
- `AdblockParser` + `BlockListStore`: hosts lines, plain domains, `||host^`, `@@`, `$important`, `$app=`; the rest counted as skipped. ≤ 20 lists, ≤ 1,000,000 entries each, ≤ 32 MiB.
- Exceptions recorded in AGENTS.md: rule 9 (global list consent), rule 1 (user-typed HTTPS list download on Import/Update only).
- Details: [16-RULE-SETS-AND-REWRITE.md](16-RULE-SETS-AND-REWRITE.md).

**D-065 · 2026-11-14 · DNS-stage list blocking, app chat, prompt eval (Nat)**

Nat asked to implement the improvement review after v0.5.0.

- **DNS stage:** a UDP port-53 query for a listed name is answered locally (A → `0.0.0.0`, AAAA → `::`, other types → empty NOERROR) and not forwarded. Covers QUIC and other non-TCP use of the name. Responses, non-standard opcodes and compressed or malformed questions are forwarded untouched. Apps using their own encrypted DNS are not covered. The TCP hostname stage stays as the second line.
- **App chat:** the app page has the same chat card as Insights, scoped to that app: rule-built host lines (role, traffic, relations, attention, suggestions), declared sensitive capabilities and rule findings.
- **Eval:** opt-in `HistoryEvalTest` scores role and advice on synthetic hosts against a model server (`WAYD_OLLAMA_URL`, `WAYD_OLLAMA_MODEL`), so prompt changes can be compared by number.
- **CI:** `ci.yml` builds the Core and Clash variants with lint and sets up Go (it still referenced the removed Standard/Research variants).
- Unused strings removed. No schema, permission or dependency change.

**D-066 · 2026-11-15 · rewrite rules, phase 1: request side (Nat)**

Nat asked to implement the alter feature. Scope kept to the request side so nothing has to buffer responses yet.

- **Actions:** answer locally (`REJECT` with `204`, `200-empty` or `200-json`), `HEADER_REMOVE`, `HEADER_SET`, `QUERY_REMOVE`. Matching: app + host (exact or with subdomains) + optional method + path/query prefix, exact or contains. No regex and no scripts yet.
- **Engine** (`mitm/rewrite.rs`): a request-side HTTP/1.1 state machine entered only when a rule names the flow's app and host; other decrypted flows keep the byte-for-byte pump. It frames `Content-Length` and chunked bodies to find the next request; upgrades, heads over 64 KiB and parse errors switch the connection to pass-through for good. A rejected request and its body never reach the server; the local answer is sent once earlier requests have their real responses and carries `X-WAYD-Rewrite: reject`. The inspector shows the original request and the local answer. Hits are counted per rule. Policy ABI 4 (`nativeSetRewrite`, `nativeRewriteHits`); Kotlin accepts 2 to 4.
- **Storage and consent:** rules live in a JSON file under no-backup storage (no schema change). Adding a rule records `ConsentRecord("rewrite-v1:<pkg>")`; removing an app's last rule revokes it. A rule only acts on flows already decrypted under that app's decryption consent and can never switch decryption on. Blocks win: a blocked connection is never decrypted or rewritten.
- **UI:** Controls → Rewrite rules, a separate section from per-app rules and block lists: add, enable, remove, hit count, engine status.
- **Known limits:** request side only (response header and body edits are phase 2); HTTP/1.1 only; pipelined requests after a rejected one could receive answers out of order (clients rarely pipeline); a `100 Continue` response is counted as a finished response; rewritten requests are shown in the inspector in their original form.

**D-067 · 2026-11-15 · name memory for unnamed connections; rule-plugin list import (Nat)**

- **Address → name memory** (`DnsIpMap`): DNS answers now carry their TTL (5-minute floor) and a fresh answer wins. A name a connection showed itself (SNI / HTTP Host) is remembered for its address for 24 h and names later unnamed connections to that address (QUIC, raw TCP) with the new source `HostSource.INFERRED`. A stale DNS answer is still used when nothing better exists, so coverage cannot drop. At VPN start the memory is seeded from the last day of recorded connections (up to 4,000), for apps that reuse cached addresses without a new DNS query.
- `INFERRED` is an added enum value in the existing TEXT column (no schema change). It is never a block target (block targets still need SNI / HTTP Host) and it counts as a hostname in the coverage panel, so that figure now includes inferred names.
- **List import** also accepts rule-plugin / rule-set lines: `DOMAIN,host` and `DOMAIN-SUFFIX,host` with no policy or a `REJECT…` policy become block-list entries; other rule types, other policies and the rewrite / MITM / script sections are counted as skipped.
- Not measured: the effect on hostname coverage needs a capture on the phone.

**D-068 · 2026-11-15 · redirect rule, interim-response fix, list counters, housekeeping (Nat)**

- **Rewrite:** new action `REDIRECT` (`302 <url>` or `307 <url>`, http/https only), answered locally like a reject with `X-WAYD-Rewrite: redirect`. `1xx` responses no longer count as a finished response when deciding when a local answer may be sent.
- **Block lists:** the engine counts connections blocked by a list and DNS names answered locally per VPN run (`nativeListStats`); Controls → Block lists shows both.
- **Housekeeping:** `.gitattributes` stores and checks out text with LF (ends the CRLF warnings on Windows); AGENTS.md names JUnit 4, which the suites actually use; CI runs the Rust tests with and without the decryption feature.
- No schema, permission or dependency change. Policy ABI stays 4.

**D-069 · 2026-11-15 · regex library for rewrite URL patterns (Nat)**

Nat approved adding a regex library to the engine.

- **Dependency:** Rust `regex` `=1.13.1`, only under the `mitm` feature, `default-features = false` with `std` and `unicode-perl`. It is a finite-automaton engine: matching time is linear in the input, so a pattern cannot stall a connection; look-around and back-references are not supported by design.
- **Rule match kind `REGEX`:** the pattern is matched against `https://<flow host><path?query>`. It is compiled once when rules are installed, with a 1 MiB compiled-size limit and a 2,048-character pattern limit. A pattern the engine cannot compile disables only that rule; the other rules stay installed.
- The rule's app and host are still required and still decide whether a flow enters the rewrite stage at all, so a regex never runs on flows of other hosts.
- Kotlin pre-checks a pattern (compiles, no look-around or back-reference) before saving; the engine has the final word.
- Opens the way to importing `[Rewrite]` lines from rule plugins (not built: imported rules still need an app and a host).

**D-070 · 2026-11-15 · import rewrite rules from rule-plugin text (Nat)**

- **Import** (工具 → 阻止与改写 → 改写规则 → 导入规则插件): pick a text file, WAYD reads its `[Rewrite]` (also `[URL Rewrite]`, `[rewrite_local]`) section and shows how many rules can be imported, how many lines are skipped and which hosts must be decrypted; the user types the app's package name and confirms. Nothing is saved before that. Consent is recorded per app as for hand-made rules.
- **Mapping:** `reject` / `reject-drop` → local 204; `reject-200` / `reject-img` → empty 200; `reject-dict` → `{}`; `reject-array` → `[]` (new local answer `200-array`); `302` / `307` → redirect (targets with `$1` substitutions are skipped); `header-del` → remove request header; `header-add` / `header-replace` → set request header; WAYD's own `query-del <name>` → remove query parameter. The `<regex> url <type>`, `<regex> <target> 302` and `<regex> - reject` forms are accepted.
- **Scope:** plugin rules are global; WAYD rules need an app and a host. The host is read from the literal host in the pattern; if the host part is itself a regex, its literal suffix is used with subdomains (never a partial label, never a bare top-level label). The pattern is kept as a `REGEX` rule. Lines with no readable host are skipped.
- **Skipped and counted:** scripts, response header and body edits, mock bodies, patterns the regex library cannot take.
- `[MITM] hostname` is only displayed; it never switches decryption on. `[Rule]` lines belong to block lists (import the same file there).
- The keyword list is from memory of the format; check it against the format's documentation when a real plugin fails to import.
- `tools/rwprobe/probe-rules.plugin` holds the probe's six rules in this format.

**D-071 · 2026-11-15 · four tabs, rule modules with master switches (Nat)**

- **Tabs:** 总览 · 流量 · 规则 · 洞察 (was 总览 · 流量 · 洞察 · 工具). 设置 opens from a gear in the Home header; the APK scanner opens from the 洞察 header (and still from each app page). Home no longer links to tabs it duplicates.
- **规则 tab:** one row per module in engine order: 路由 (Clash, `clash` variant only), DNS, 请求. Each row opens its page and carries a switch. 响应 is not shown until response rewriting exists (phase 2).
- **DNS module:** the block lists. Off installs an empty list path in the engine, so no list applies at either the DNS or the TCP hostname stage; list consents are untouched.
- **Request module:** per-app connection blocks, rewrite rules, rule backup. Its switch gates **rewrites only** (Nat's choice): connection blocks always apply, so a module switch can never silently unblock an app. Off installs the bare `WAYDRW1` header.
- Switches live in `RuleModules` (SharedPreferences `rule_modules`, both default on, so existing setups are unchanged) and are pushed through `BlockingRules.setDnsModule` / `setRequestModule`; also applied at VPN start.
- Planned next: DNS change actions (custom answers, record-type blocks such as HTTPS/SVCB), a 流量 → DNS segment, a `phase` field on rewrite rules (JSON store, no migration) when the 响应 module arrives.

**D-072 · 2026-11-15 · DNS rules: local answers, Daedalus hosts/dnsmasq import, record-type blocks (Nat)**

- **Where:** 规则 → DNS → DNS 规则 and 记录类型, gated by the DNS module switch together with the block lists. Built into WAYD's own DNS stage, not Clash: Clash is `clash`-only and profile DNS / fake-IP stay overridden (14-OPTIONAL-ROUTING).
- **Order per UDP/53 query:** block list → DNS rule (exact name, then the most specific suffix, as dnsmasq) → record-type block → forward.
- **Answers:** a name with addresses answers A/AAAA from them (TTL 60 s, up to 16 each); the other family and HTTPS/SVCB get an empty answer so the mapping holds; other types are forwarded. `BLOCK` answers like a block list.
- **Import, compatible with Daedalus (`RuleResolver.java`):** hosts `ip name…` → exact names (Daedalus reads only the first name; WAYD also takes aliases); dnsmasq `address=/d[/d…]/ip` → name and subdomains, empty value → block (Daedalus answers `0.0.0.0`). Differences, all stricter: `0.0.0.0`/`::` also answer AAAA with `::` (Daedalus forwards AAAA), `#` and `local=/d/` block, a mapped name's other family gets an empty answer (Daedalus forwards it). Skipped and counted: `server=` and other options, `address=/#/`, localhost names, single-label names.
- **Record types:** HTTPS+SVCB (64, 65) and AAAA (28) as two switches; HTTPS/SVCB blocking also keeps ECH keys from apps.
- **Storage and consent:** like block lists (no-backup files, compiled at import, `active.dns`). One revocable `ConsentRecord("dnsrule-v1:<id>")` per enabled file; typed rules and type blocks share `dnsrule-v1:local`. This extends the D-064 rule-9 exception pattern to DNS rules; **the AGENTS.md exception table is not yet updated** (needs Nat's approval).
- **No download:** files come only from the picker, so no new network destination. Upstream choice (plain/DoT/DoH) is not built and needs a rule-1 exception first.
- **Engine:** policy version 5 (`nativeSetDnsRules`, third `nativeListStats` field "answered by a DNS rule"). Kotlin accepts 2 to 5.

**D-073 · 2026-11-16 · rewrite rule sets with target apps, rule editor page (Nat)**

- **Model:** a rewrite **set** (imported rule plugin or hand-made) holds rules without an app plus a list of target apps, each with a switch. The engine gets one line per enabled rule per enabled app; lines share the rule id, so hits add up per rule. No native change. Limit: 2,000 engine lines in total (rules × apps), checked before saving.
- **Storage:** `no_backup/rewrite/sets.json`. The old per-app `rules.json` migrates to one set per app on first read (renamed `rules.v1.json`); ids, switches and hits keep working.
- **Consent:** `rewrite-v1:<pkg>` is recorded when an app becomes active in any enabled set and revoked when no enabled set targets it.
- **Suggested apps:** packages named by the plugin (`#!packages=a.b,c.d`, a WAYD header) first, then apps whose recorded connections (30 days) reached the set's rule or `[MITM]` hosts, by connection count. Rows say when an app is not decrypted, so its rules cannot apply. WAYD never switches decryption on from here.
- **UI:** 请求 → 改写规则 lists sets → set page (switch, target apps, suggestions, rules, hosts to decrypt, rename, delete) → rule editor page (add or edit): form plus raw text, docked below in portrait and beside in landscape. Raw text has two views: WAYD `key: value` text (lossless) and the plugin line (no method). Editing either side updates the other.
- The plugin parser reads the editor's optional-subdomain prefix `(?:[^/]+\.)?` back as "host and subdomains".

**D-074 · 2026-11-16 · Clash import names the failing rule type**

- Continue (profile check) used to show only "Failed" for any non-download error. Go now reports `rule_resource_or_metadata_unsupported:<TYPE>` and `invalid_rule:<TYPE>` (TYPE is a fixed keyword or a sanitized rule keyword, never profile text); the screen maps these and the other check codes to readable text and shows unknown codes verbatim.
- Supporting GEOSITE / RULE-SET / SUB-RULE / ASN is still open (14-OPTIONAL-ROUTING "Not supported").

**D-075 · 2026-11-16 · Clash layer: full Mihomo rule compatibility behind WAYD's layers (Nat)**

- **Goal (Nat):** Clash profiles work as in Clash/FlClash. FlClash (GPL-3.0) is the reference; WAYD keeps its own VPN in front, so it copies FlClash's compatibility, not its architecture (Mihomo owning TUN/DNS).
- **Layers:** WAYD DNS stage → WAYD connection blocks → WAYD request rewrites → Clash layer (route choice) → dial. WAYD blocks always win.
- **Slice 1 (done):** every rule line (top level, AND/OR/NOT, SUB-RULE, classical providers) is compiled by Mihomo's parser: GEOSITE, GEOIP, SRC-GEOIP, IP-ASN, SRC-IP-ASN, RULE-SET (yaml, text, mrs, inline), SUB-RULE, PROCESS-NAME (= package). Rule providers load through Mihomo's own provider from WAYD-downloaded files (file vehicle, no fetch). No matching rule means DIRECT, as in Clash (the old "last rule must be MATCH" check is gone).
- **Data files:** Mihomo's home directory is `no_backup/routing-resources` (shared `country.mmdb`, plus `GeoSite.dat`, `ASN.mmdb`). All Mihomo download URLs are cleared, and rules needing a file compile only after WAYD verified it (`geodata-home`, `geodata-verify`), so Mihomo never downloads on its own and a bad ASN file can never reach its `log.Fatalln`. GeoSite updates take effect after WAYD restarts (Mihomo caches matchers).
- **Providers registry:** only the routing engine registers rule providers in Mihomo's registry (`tunnel.UpdateRules`); validating another profile never replaces them.
- **Decided, slice 2 (not built):** real-IP only (profile `dns` used for resolution, `fake-ip` read as redir-host and disclosed); provider/subscription refresh and health checks on profile intervals only while routing is on (AGENTS.md rule-1 exception added); a Clash REJECT is recorded as "Rejected by Clash", separate from WAYD blocks.

**D-076 · 2026-11-16 · on-device prompts tuned with a prompt lab; per-request JSON schemas**

- **Tool:** `tools/promptlab/` (stdlib Python, localhost Ollama only) replays the history, label, chat and app-analysis contracts with LiteRtEngine's sampler and constrained decoding against `gemma4:e2b`/`e4b`. Cases come from Nat's real exports (`build/promptlab/`, ignored) plus synthetic traps. Reference answers for real data come from Claude Haiku subagents answering the full (external-path) prompts; they pass the same validators. Ollama's Q4 GGUF stands in for the `.litertlm` build, so scores are relative.
- **Schemas per request** (constrained decoding enforces what `parse` checks): history `minItems = maxItems = N` and `id ∈ h1..hN`; label `role`/`advice` fixed where the target leaves no choice, refs limited to supplied ids of the right kind, `target_id` fixed; chat `evidence_ids ∈` supplied ids. LiteRT-LM 0.19.1 decodes with llguidance (enum, minItems supported).
- **Free text before decisions:** history `note` and label `explanation`/`reason` precede `role`/`advice` in the schema, because properties are generated in schema order.
- **No default values in templates:** E2B copied the old templates' `UNKNOWN`/`INSUFFICIENT`/`""` into its answers; examples now use placeholders or a filled example in the answer language.
- **Language and injection reminders at the end of the user message;** the full label prompt gets one too (Haiku answered 3 of 5 zh-CN labels in English without it).
- **Chat evidence** is picked round-robin by id kind, so the Small budget keeps company and screen-off records.
- **Measured** (3 repeats, v1 → final): history advice E2B 36→94%, E4B 47→91% (synthetic); agreement with Haiku E2B 44→86%; empty notes 23→0%. Labels valid 77/81→100%, E4B role agreement 13→85%. Chat valid E2B 77→100%; E2B now cites company records. Analysis prompt unchanged (no gain measured).
- **Not changed:** contract `VERSION`s (answer shape and validation are the same, so saved answers stay); `PromptBuilder`. Open: E2B label roles still agree with Haiku only ~22–48%; feeding the rules' role guess helped E2B but hurt E4B with the lab's crude stand-in, so test it with real `LabelRules` before shipping.

**D-077 · 2026-11-16 · AI evidence gets decrypted content and APK facts; export/import survive leaving the screen; Settings tab (Nat)**

- **Labels:** new evidence kind `MESSAGE` (`m:` ids): up to 3 masked decrypted messages of the target's host (target's own first), placed right after the target so the Small budget keeps them. Header names only, the first 240 characters of a text body.
- **History lines:** `dec=N METHOD /path,…` (requests only, query dropped), from a light `MessageDao.requestLines` projection (no bodies).
- **App analysis:** APK static-scan facts as `a:` evidence when a scan exists: permissions with a known meaning (not network/storage/boot plumbing, max 8), SDKs (4), components with exported ones first (4); each marked "declared, not proof of grant or use". `PromptBuilder` and chat pick evidence round-robin by id kind, so `m:`/`a:` items are no longer cut by take(N).
- **Export/import cause (2026-11-15):** `.d049` was first installed at 22:33:57, 7 s after the export's last write: the reinstall killed the export (file cut at 45 × 256 KiB) and wiped the database; the import of that file then stopped after 5,381 of 14,292 flows, so DNS, messages and permission events (later in the file) were never imported.
- **Export/import changes:** both run in their service's own scope (not the button's composition), the `apps` section is written last, import inserts flows in transactions of 500 and keeps what it read from a file that ends early (`import_partial`).
- **Navigation:** Settings is a fifth tab (gear icon); its first group, 工具, holds the APK scanner (Clash stays on the Rules tab), replacing the removed Tools tab.
- **External acknowledgement (Nat):** "我了解数据会离开本机" moved from the external-server page to the provider switch on the AI page: choosing External on a server without an acknowledgement shows the warning dialog, and confirming records the per-origin `ConsentRecord` and switches. Unconfigured → opens the server page. Deleting a profile or changing its origin still revokes it.
- **External AI over Clash, checked 2026-11-16:** blacklist mode + WAYD VPN on: model list and an analysis against `generativelanguage.googleapis.com/v1beta/openai` work. In whitelist mode the call goes Direct unless WAYD itself is ticked in the Clash app list (D-050); that is silent today.
- **Entry points (Nat):** the gear next to the VPN switch on 总览 is gone; the last row of the 洞察 list opens the Settings tab.
