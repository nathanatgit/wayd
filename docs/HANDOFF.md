# Handoff

## Resume here · session boundary, 2026-11-10

User is moving to a new session. This is a documentation-only handoff update; no additional build, install, VPN run or test is claimed here.

- Workspace: `C:/Users/Han/Project/wayd`, branch `feat/m7-control`. Large uncommitted M1/M5/M7 changes and untracked source files are intentional. Do not reset/clean, discard, pull over them, or assume the installed debug app is the published release. No commit, push or new session was created.
- Completed: native exact APP/HOST/IP blocking with per-app identity/consent; history Block this time / Block forever and Undo; fixed native VPN shutdown; unified Controls / 管控 inventory; Home / 总览 dashboard separating VPN Start/Stop from Record traffic. Clash toggle is disabled. [Rule examples](15-RULE-EXAMPLES.md) explicitly mark regex and custom responses as planned.
- Last phone state: NX733J wireless ADB `192.168.31.52:35193`; isolated `com.nathanhanapps.wayd.m1test`, research debug versionCode 4 / versionName 0.1.2. Final APK installed successfully. VPN left stopped, recording off, phone subsequently locked. Recheck connectivity/lock state before future device work; never use USB `6f3e7112` (a different phone). Production app and retained overnight history were preserved. The existing user-created durable rule must not be deleted as a test fixture.
- Verified: both final APK flavors and lint pass; 35 capture JVM tests pass; earlier Android native standard 11/research 26 tests and installed-app shutdown cycles pass. Dashboard layout, recording off→on→off, disabled Clash and rule navigation were checked before the last accessibility-label/Home-icon polish. Final polish rebuilt/linted and installed; its last UI recheck encountered the lock screen. No claim of complete coverage, power improvement or full M7 acceptance.
- Next priority: verify on device that forwarding and active blocks continue while Record traffic is off, no new flow/DNS/message history is saved, new flows record after enabling, spanning flows stay excluded, and session rules survive recording toggles but expire on VPN stop. Use the disposable probe and controlled fixture; preserve user rules and history. User owns the manual overnight soak; do not repeat or demand it as a prerequisite.
- Then continue M7: reviewed portable JSON backup/import without grants/secrets/history; research static local replies before bounded replacements/regex. Actual Clash integration follows core controls; GeoIP follows Clash. UI rework has started with Home only; later tab redesign has no settled design yet.
- Read [AGENTS.md](../AGENTS.md), this handoff, [capture](03-CAPTURE.md), [UI](09-UI-M3-EXPRESSIVE.md), [testing](12-TESTING.md), [control specification](13-BLOCKING.md), [routing boundary](14-OPTIONAL-ROUTING.md) and D-034–D-037 in [decisions](DECISIONS.md). Local-only inspection, any local BLOCK wins, and AI cannot author authoritative policy remain mandatory.

Latest validation logs are ignored local files: `build/dashboard-final-validation.log` (both flavors/lint, capture tests/lint, 1m 21s) and `build/dashboard-ui-final.log` (final app polish both flavors/lint, 1m 15s). APK: `app/build/outputs/apk/research/debug/app-research-debug.apk`. Test sources and contracts, rather than ignored logs or helper files, are the durable evidence if this workspace is moved.

## 2026-11-10 · Home dashboard and independent recording

User requested a dashboard instead of a recording control page. [Home](../app/src/main/kotlin/com/nathanhanapps/wayd/feature/record/RecordScreen.kt) now presents Start/Stop WAYD VPN, independent Record traffic switch, disabled future Clash switch, acknowledged rule counts/Controls link, current-or-last-run aggregate stats, retained-history coverage/reports/export and five recent saved flows. Bilingual navigation label is Home / 总览. Rule lifetime/status copy now refers to VPN rather than recording.

[TrafficRecording](../capture/src/main/kotlin/com/nathanhanapps/wayd/capture/TrafficRecording.kt) defaults off on process restart; preference remains across VPN stop/start within the process. FlowTracker continues owner/visible-host policy callbacks and aggregate counters, but saves flow/message/DNS only within one uninterrupted enabled window. Open connections spanning a mode change are excluded. Existing history, already completed queued batches and VPN session metadata remain. New research interception decisions are declined while off; existing intercepted connections can continue without retaining messages. Independent permission collectors are not controlled by this traffic-only toggle. No new schema/permissions/destinations/native engine changes. See D-037.

[Rule examples / 规则示例](15-RULE-EXAMPLES.md) gives available app+exact-host blocking and illustrative future local JSON reply, response replacement and field-specific regex examples with positive/negative cases. Not importable JSON and not a response/regex implementation. Exact matching/static replies are preferred where sufficient; no scripts, per-request AI or measured power claims.

Validation: both flavor APK builds and both app lint tasks, capture lint and 35 capture JVM tests passed (`build/dashboard-final-validation.log`, BUILD SUCCESSFUL in 1m 21s). Three new tests cover policy metadata without persistence, exclusion/resume across toggles and discarded decrypted messages. Editor diagnostics clear; 22 dashboard resource keys match across both locales and XML parses. Installed research debug into isolated `.m1test` on NX733J at 192.168.31.52:35193 (Success); launch produced no crash-buffer entries. After user unlock, verified dashboard layout, recording switch off→on→off, disabled/off Clash switch and rule navigation with saved user rule intact. No VPN start or user-rule changes; live forwarding with recording changes remains untested on device. Added switch accessibility labels and Home icon after smoke inspection; final build/install recorded in continuation. Preserve all existing uncommitted M1/M5/M7 work; no commit/push.

Final dashboard UI polish: Home icon and switch accessibility labels also built/linted successfully for both flavors (`build/dashboard-ui-final.log`, 1m 15s); final research APK reinstalled successfully with no crash-buffer entries. Earlier dashboard layout and toggle smoke checks remain valid; no additional VPN run.

## 2026-11-10 · Unified Controls inventory

Implemented [ControlsScreen](../app/src/main/kotlin/com/nathanhanapps/wayd/feature/controls/ControlsScreen.kt), a Controls / 管控 bottom tab, also linked from Apps. It replaces the old global rules modal and lists all saved/session rules independently of history windows. Counts and filters use native-acknowledged active IDs. Includes literal package/host/IP search, typed targets, lifetimes, status, expandable identity details, app-history navigation and confirmed removal. Response alteration is explicitly unavailable pending its own engine implementation. No new polling, permissions, schema or policy behavior. See D-036 and [blocking specification](13-BLOCKING.md).

Validation: both standard/research debug APKs and both app lint tasks passed (`build/m7-controls-build.log`); editor diagnostics clear, 33 new resource keys match in both locales, XML parses and diff whitespace check passes. Installed research debug into isolated `.m1test` on NX733J at 192.168.31.52:35193. Real UI verified five-tab navigation, saved inactive rule card, expanded ID/profile/date/signer details, empty Effective filter with recording stopped and populated Inactive filter. Existing saved user rule was preserved. No capture start or rule modification for this UI check; no live effective-rule state tested in this turn. Earlier live blocking/lifecycle results remain recorded below.

Next: portable JSON backup/import with identity review and fresh consent; research static local replies and bounded replacements; actual Clash integration before GeoIP. Preserve existing uncommitted M1/M5/M7 work. No commit/push performed.

_Last updated: 2026-11-10 · released **v0.1.2** (GitHub release, signed) · working branch: `feat/m7-control` (uncommitted M1/M5/M7 and dashboard work)_

WAYD (在干嘛, `com.nathanhanapps.wayd`) is an Android privacy monitor: a VpnService processes traffic and applies local blocks, optional traffic recording saves evidence locally, optional research decryption reads supported HTTPS content, and an on-device Gemma model explains findings. Read [AGENTS.md](../AGENTS.md) first (non-negotiables), then this file, then [DECISIONS.md](DECISIONS.md) (through D-037).

## 1. Where things stand

| Area | State |
|---|---|
| **M0** skeleton, theme (Material 3 Expressive), en + 简体中文, Room v3 + SQLCipher | done |
| **M1** capture (VpnService + Rust netstack), UID attribution, SNI/Host/DNS | current coverage accepted for progression; formal router/session checks remain open |
| **M2** enrichment + rules + app-oriented UI (Apps, app detail, Reports, company drill-down) | done, verified on device |
| **M3** per-app permission history (Shizuku AppOps) + permission rules | first slice done |
| **M4** message inspector + identifier rule (R-ID-IN-REQUEST) | built; inspector UI not yet seen on the phone |
| **M5** APK scanner | implementation underway; user-started bounded static scans, cached facts and Declared/Used/Sent UI; see latest entry |
| **M6** on-device Gemma (LiteRT-LM) | **working on the NX733J**, quality tuning open |
| **M8 (research)** MITM CA, consent, per-app picker, optional root system-CA | working; Chrome verified, WeChat metadata-only |
| **M7** blocking / content control | host blocking/Undo and fixed VPN shutdown verified on Nubia; wider device acceptance open → [13-BLOCKING.md](13-BLOCKING.md) |
| Release pipeline | working: tag `vX.Y.Z` → GitHub Actions → signed APK (§5) |

## 2. Repository map

```
app/            UI (Compose), navigation, feature/{analysis,apps,reports,record,settings,ai,inspector,export}
                src/research + src/standard hold the flavor twins (research/ResearchFeatures, Danger zone, MITM controller)
core/model      enums, EvidenceRef
core/db         Room v4 (AutoMigrations 1→2→3→4), DAOs, SQLCipher key wrapper, schemas/ exported
core/ui         theme
capture/        VpnService, FlowTracker, JNI bridge; capture/native = Rust (ipstack relay, mitm feature)
enrich/         tracker/company seed DB (~350 entries)
analysis/       rules (12), RiskScorer, AppReport/DailyReport builders, DecryptedRules (IdentifierScan)
collectors/     AppOps history parser, PermissionTimeline
privilege/      Shizuku UserService (AIDL), HistoryFilter
ai/             Masker, PromptBuilder (+JSON schema), SchemaValidator, AiAnalyzer, ModelManager, ModelDownloadService, LiteRtEngine
docs/           PRD, architecture, decisions, analysis rules, AI, MITM consent, blocking design, testing …
.github/workflows/  ci.yml (branches/PR), release.yml (tags)
```

Flavors: `standard` (no TLS code; native 870 KB) and `research` (MITM engine; native 2.1 MB). **Releases ship `research` under the plain app id** (D-027). Native libs are built per flavor by Gradle through cargo-ndk (arm64-v8a only).

## 3. Build, test, run (this Windows machine)

```
export ANDROID_HOME=C:/Users/Han/AppData/Local/Android/Sdk      # never create local.properties (lint rejects its escaping)
./gradlew test :app:assembleStandardDebug :app:assembleResearchDebug            # builds Rust too
./gradlew test -PskipNative …                                                    # reuse the prebuilt .so files
cd capture/native && cargo test --features mitm                                  # 16 Rust tests (needs PATH with ~/.cargo/bin + WinLibs MinGW)
```
JDK: Android Studio's JBR. Rust: GNU host toolchain + winget WinLibs MinGW + cargo-ndk, NDK r29 (`29.0.14206865`), SDK platform package is `platforms;android-37.0`. Tests: ~110 JVM tests + 16 Rust tests, all green at v0.1.2.

Local signed release build (does not touch the phone): set `WAYD_KEYSTORE_FILE`, `WAYD_KEYSTORE_PASSWORD`, `WAYD_KEY_ALIAS=key1`, `WAYD_KEY_PASSWORD` (key lives in `C:\Users\Han\Project\nebulaThemePorter\NebulaThemePorterKey\key11`, password in `keypalin.txt`; or a git-ignored `keystore.properties`), then `./gradlew :app:assembleResearchRelease -PappIdSuffix=.reltest` (the suffix lets it install next to other builds).

## 4. Testing on the phone (Nat's NX733J, Android 16, KernelSU, Shizuku 13.6, 15 GB RAM)

- Wireless adb: **the port changes every time Wireless debugging is toggled**; ask Nat for the current `IP:port` (`adb connect`). It also drops whenever the VPN starts/stops.
- Drive the UI on a **scrcpy virtual display**, never the main screen (Nat uses the phone): `scrcpy -s <dev> --new-display=1080x2400/420 --start-app=<pkg> --no-audio --screen-off-timeout=3600 --window-title=...` (a window on the PC shows it; with `-N --record=file` it is hidden). `--screen-off-timeout` is essential: when the phone's own screen sleeps the virtual display sleeps and screenshots come back as 15,580-byte black PNGs. Screenshot with `adb exec-out screencap -p -d <SurfaceFlinger display id>`; tap with `input -d <logical display id>`. Kill stale `app_process … scrcpy` PIDs after `taskkill scrcpy.exe`.
- Logcat is muted on this ROM: `setprop log.tag I` while capturing and **always** `setprop log.tag S` afterwards. After any VPN test: `am force-stop`, no WAYD VPN in `dumpsys connectivity`, ping works.
- The VPN consent dialog is accepted by Nat on the main display (do not tap it via adb). Nubia AutoLaunch blocks starting the VPN service via `am`; start recording from the UI.
- Do not run `su` mount tricks without the safeguards in D-024 (an earlier attempt left the zygote with an empty CA store).
- Nat said: when he is away, do **no live testing** on the phone.

## 5. Releases

`git tag -a vX.Y.Z -m … && git push origin vX.Y.Z` (the tag must equal `versionName` in `app/build.gradle.kts`; bump `versionCode` too) → `.github/workflows/release.yml`: version check, Rust tests, JVM tests, signed `assembleResearchRelease`, `apksigner verify`, GitHub release with `wayd-vX.Y.Z.apk` + `.sha256` (~8 min). Secrets (already set): `WAYD_KEYSTORE_BASE64`, `WAYD_KEYSTORE_PASSWORD`, `WAYD_KEY_ALIAS`, `WAYD_KEY_PASSWORD`. Signing certificate SHA-256: `c3ec78c2…d890db`. `main` is the release branch: merge with `--no-ff`, then tag. Commit messages carry **no** Claude co-author line (Nat's rule).

Caveats: tag `v0.1.1` exists on GitHub but its release failed (wrong SDK package name) — delete it when convenient (`git push origin :refs/tags/v0.1.1`; a safety check stopped me doing it unasked). The release app id has no suffix, so installing it over the debug `com.nathanhanapps.wayd` build fails (different signature): uninstall the debug app first. The research debug app (`.research`) on the phone holds the downloaded Gemma E2B (2.6 GB) and test data; the release app would need its own download.

## 6. Feature notes worth knowing

- **Capture/relay:** idle handling is per flow (TCP 1 h, UDP 5 min, DNS 15 s; ipstack timeout 2 h; 256 KB windows); IPv6 is offered only if the underlying network has an IPv6 default route (an IPv4-only Wi-Fi + advertised `::/0` broke Douyin); the app excludes itself from the VPN; flows still open at stop keep their byte counts through PROGRESS events.
- **MITM (research):** Rust rustls engine; policy in Kotlin (`InterceptHook`/`nativeDecide`, 600 ms, default passthrough); per-app allowlist (empty by default) + exclusion list (banking, payment, government, password managers, OS/store); consent per [08-MITM-AND-CONSENT.md](08-MITM-AND-CONSENT.md); CA key Keystore-encrypted; messages stored in SQLCipher (≤128 KB bodies). Chrome works with a user CA; WeChat/Douyin-class apps need the optional root system-wide trust, and WeChat's own MMTLS stays metadata-only. The ROM hides the app list from ordinary apps, so the picker combines package manager + traffic history + typed names + a root `pm list`.
- **Export:** raw JSON (latest capture / one app) via the system document picker, with a "keep this private — other people's data" warning.
- **AI:** Gemma 4 E2B/E4B `.litertlm` via `litertlm-android` 0.19.1. Hugging Face is unreachable from the phone; **ModelScope works (~30 MB/s)**. GPU needs `uses-native-library libOpenCL.so`; the engine falls back to CPU on failure and uses schema-constrained JSON, repetition penalty and a 1,400-token cap. E2B ≈ 27 s for ~10 items. First run compiles for the GPU (the UI says so). Answers follow the UI language. Debug builds log validator complaints under tag `WAYD-AI`.
- **Blocking:** not built; design in 13-BLOCKING.md (host/app level works for every app, path/content level needs decryption + a trusted CA).

## 7. Open items (suggested order)

1. **AI quality:** still mixes in an odd "OTHER" data type; compare E4B (recommended for 15 GB), add injection tests against the real model, "Analyse selection" from the connection list, tools / chat, overnight summary (WorkManager).
2. **M7 blocking:** rule model + matcher (Kotlin spec, Rust implementation), host/app level first, then history actions and a rules screen, then path/content rules in the MITM loop.
3. **M4:** look at the inspector on the phone; add search/filter; more identifier rules (R-APPLIST variants, cookies).
4. **M1 acceptance:** 1 h soak; hostname coverage was 84–90% (target ≥ 90%); external-router IPv6 check; ICMP is dropped; DoH/DoT and QUIC SNI are invisible.
5. **M3 leftovers:** R-BOOT-REPORT, alarms/netstats/package collectors, GeoIP (deferred until after Clash integration; needs a licensed DB), 24 h per-app heatmap, stored/versioned reports, report render time (< 1 s) measurement, libsu fallback, docs/04 update.
6. Release hygiene: delete the dead `v0.1.1` tag, add release notes/changelog, consider a store-safe `standard` release later.

## 8. Known problems

- Wireless adb flaps with the VPN; scrcpy displays die when the phone's screen sleeps (see §4); only one VPN can be active (WAYD replaces Clash while recording).
- Apps that pin certificates, and WeChat's MMTLS, cannot be decrypted; pinned hosts are remembered and passed through.
- The report screen's "new today" section was removed on purpose; findings still appear in the lists.
- `docs/HANDOFF.md` is the living status; `docs/DECISIONS.md` is the log of why.

## 9. Inputs Nat can provide

Test device list, brand seed colour (placeholders in use), AppDualZuku `ShellClient` files (M3 reference), which apps matter most for the research builds, ModelScope/own-CDN mirror for the models.

## Documentation update · 2026-11-09

Clarified the on-device privacy promise, explicit exports/downloads, and planned optional Clash/FlClash-style routing (D-028; 14-OPTIONAL-ROUTING.md). WAYD owns VPN/block precedence; routing receives minimum data, shows actual routes, and never silently falls back direct. README no longer says no code exists. Documentation only: no runtime behavior changed; M7 and routing remain planned. Next: validate M7, then resolve routing engine/licensing, isolation and DNS policy before implementation.

## M1 refinement · 2026-11-09

Synced `feat/m7-control` with `origin/main` through `291587a`. Work resumes at the earliest incomplete milestone, M1, before starting later milestones. M5 is the first wholly unimplemented milestone; M1–M4 still have the acceptance/feature gaps listed above.

- Fixed a hostname-coverage gap: FlowTracker previously examined only the first upstream DATA chunk. It now accumulates at most 16 KiB across chunks, releases the buffer on a complete TLS ClientHello / HTTP head or the limit, and preserves the first parsed host.
- TLS metadata parsing requires a complete handshake and supports ClientHello split across TLS records. Truncated/invalid lengths return no metadata. Native TCP prefix reporting increased from 2 KiB to 16 KiB to match; bytes beyond the inspection limit still relay normally (D-029).
- Validation: 23 capture JVM tests passed; 17 Rust tests with `mitm` passed, including a relay-prefix/forwarding regression now runnable on Windows. Both `standard` and `research` debug APKs built with freshly rebuilt native libraries. No new permissions, destinations, strings or database schema changes.
- **M1 remains incomplete:** the short device smoke below does not meet the coverage targets. Next: investigate unattributed flows and non-HTTP/TLS TCP traffic, then measure ≥99% package attribution and ≥90% TCP hostname coverage over the 1 h browse/video/WeChat soak and reconcile IPv4/IPv6 with external-router capture. QUIC/DoH/DoT, ECH and >16 KiB prefixes remain coverage limits; ICMP remains dropped.

### Device launch follow-up

Connected to NX733J (Android 16) using the user-supplied wireless adb address (ports are ephemeral). Built/installed `standard` debug with `-PappIdSuffix=.m1test` alongside existing apps. Nat accepted Android VPN consent himself; no VPN consent was injected via adb. Foreground capture ran and Chrome rendered `https://example.com`. The `http://neverssl.com` check remained blank after Chrome's HTTP confirmation, so HTTP page success is inconclusive. No controlled video or WeChat interaction was performed; background app traffic was captured.

Final counters after stopping: **68 flows, 7 apps, 137 kB sent / 680 kB received; 98% app attribution; 42 public TCP flows with data, 85% with a hostname** (UI percentages). Earlier live snapshots showed 92%, then 89% hostname coverage; use the finalized sample, not the transient high value. These are the existing coverage panel's definitions (public TCP with data for hostname coverage), not proof of the roadmap's full acceptance. Missing-host rows included proprietary TCP traffic; do not infer that every missing hostname is a parser defect. No before/after controlled comparison, 1 h soak or external-router IPv6 capture was performed; the test Wi-Fi had no IPv6 default route.

The debug-only developer-PC /32 exclusion kept adb outside the VPN; account for that test exclusion when assessing coverage. `--keep-active` stabilized the virtual display after the first display slept despite the 1 h timeout. Cleanup verified: stopped recording through the UI, force-stopped the test package, no active VPN network or test service, no remaining scrcpy process, and router ping recovered (2/2 replies). Wireless adb briefly went offline during stop and reconnected. Test APK/data remain installed separately; screenshots and build/test logs are local ignored `build/` artifacts.

## Bedtime handoff · 2026-11-10

**Nat will run the soak manually. Stop all automated phone testing while he is using the phone / away.** No hour-long capture is being left running by the agent. M1 is still open; do not begin M5 on the assumption that acceptance passed.

- Follow-up fixes: unresolved owners get at most two exact-tuple retries on DATA during the first second, without changing intercept consent. DNS-over-TCP replies now reassemble bounded length-framed messages across chunks and populate DNS fallback. Tests cover transient lookup failure, retry limits/age, denied interception, every DNS stream split, multiple frames and invalid/incomplete/oversized frames. See D-030.
- Validation: **28 capture JVM tests passed (15 FlowTracker + 13 parsers); both debug flavors built successfully**, reusing the previously rebuilt native libraries (`-PskipNative`). Rust remains unchanged since the prior 17-test passing run. Follow-up logs: ignored `build/m1-followup-validation.log` and `build/m1-followup-device-build.log`.
- Updated isolated `com.nathanhanapps.wayd.m1test` standard debug APK installed successfully. A second launch/page attempt did not yield a verified active capture service, so it supplies **no new capture coverage result**; a rendered browser page alone is not VPN evidence. Last valid capture result remains the earlier 68-flow / 98% attribution / 85% hostname sample.
- At Nat's bedtime handoff request: force-stopped only the test package and closed the test scrcpy display. Verified **zero active VPN networks**, no remaining scrcpy server process, and router ping **2/2 replies**. No further live testing after this cleanup. Test app/data are retained; changes are uncommitted on `feat/m7-control`.

### Manual soak and resumption

1. Use the updated **test** app (`com.nathanhanapps.wayd.m1test`; app label is also 在干嘛). Start recording from its UI and verify the running status / foreground notification. Accept any Android VPN prompt yourself. Only one VPN can run, so record whether replacing your usual VPN affects access.
2. Browse, stream video and use WeChat normally for at least one hour. Note start/end times, any app failures, network switches, screen-off interruptions or recording gaps. Nothing requires sending a test message to someone else.
3. Stop recording, then retain the finalized counters/coverage screen and observations. Captures stay local; raw JSON export is optional and needs your choice. **Coverage currently aggregates all stored flows**, so it includes the earlier 68-flow baseline and is not a pure per-session measurement. Do not clear app data to reset it (that destroys prior evidence). Add session-scoped exact metrics before using this panel as formal acceptance evidence.
4. Acceptance remains ≥99% package attribution and ≥90% TCP hostname coverage, plus no breakage and external-router reconciliation of IPv4/IPv6. Missing hosts in proprietary protocols must stay unknown; do not change the denominator merely to pass. UID-only `uid:NNN` fallbacks currently count in the panel's `pkg != '?'` metric; distinguish them from resolved package names during formal review.
5. Router/AP tcpdump access or an authorized external capture has **not been supplied**. Current Wi-Fi had no IPv6 default route. The router comparison and IPv6-path validation cannot be claimed complete. Ask for a fresh wireless adb address only when Nat is ready to resume; ports change.

Next coding checks: session-scoped exact coverage (including explicit UID-only counts), TTL/per-app DNS correlation (the current map is a session LRU), then investigate missing evidence from the manual run. Do not infer a device coverage improvement from the new regression tests alone.

## Overnight recording review · 2026-11-10

Nat reported recording overnight and supplied a new adb port. Read-only review of the retained `.m1test` recording screen found **2,213 stored flows, 99% panel attribution, 1,894 public TCP flows with data, 78% with hostname**. Recording was already stopped; another VPN was active and was left untouched. The screen's zero live counters reflect process-local state, not loss of stored flows. These coverage totals aggregate previous sessions, so the overnight session duration, exact package attribution (excluding UID-only fallbacks), and hostname result are not established. M1 remains open; hostname coverage is below target even under the existing panel filter.

Prepared [Measure-Capture.ps1](../scripts/Measure-Capture.ps1) for a user-chosen local version-1 capture JSON. It emits only aggregate metrics and missing-host groups by package/port; no IPs, hostnames, DNS query names or message payloads. It reports both the existing panel denominator and all TCP flows, and separates resolved package names from UID-only/unknown records. Synthetic populated and empty captures passed; no app code changed during this review. Raw export/local PC analysis was requested through an explicit user choice and must wait for the answer. Do not infer export consent merely from the adb address.

## Current direction and M5 implementation · 2026-11-10

**Nat accepts the current capture coverage for progressing to M5.** This supersedes the earlier instruction to wait for formal M1 acceptance, without changing the metrics or completing router/IPv6/session checks. Nat explicitly schedules **GeoIP after Clash feature integration**. The raw overnight-export offer was not accepted; no export was performed and it is not a prerequisite for this work (D-031).

- Added [apkscan](../apkscan/) with bounded binary-XML and DEX metadata readers, base/split scanning, manifest receiver actions, permission/component/exported facts, declared network security config, current signing fingerprints/subjects, defined-class SDK prefix matches, possible endpoint hosts, native libraries and protector heuristics. Embedded URL credentials/paths/query values are discarded. DEX 041/reverse-endian/odex/VDEX, non-ASCII string evidence and unsupported/malformed content remain visible limitations; see [scanner status](07-APK-SCANNER.md).
- Reuses existing Room v3 `AppInfo`/`ApkFact` tables through [ApkScanDao](../core/db/src/main/kotlin/com/nathanhanapps/wayd/core/db/ApkScanDao.kt); no schema change. Unique user-enqueued WorkManager scans cache unchanged versions, run at most two APKs concurrently, support cancellation, and atomically retain completed packages. The new fixed [Command.ListPackages](../privilege/src/main/kotlin/com/nathanhanapps/wayd/privilege/Command.kt) may reuse an already-granted Shizuku connection for current-user discovery. No automatic prompt, free-form shell, root APK reads, upload or new network destination.
- Apps → APK scanner, plus per-app links, show cached results, search by package/name/signer, explicit package lookup, scan progress/failures, limited-coverage badges and Declared/Used/Sent. The three columns contain independent static facts, recorded AppOps types and observed hosts; only exact host equality is highlighted. No permission-to-payload causal claim or verified-company inference from self-asserted signer subjects. Both locales updated.
- Validation: **13 scanner tests + 8 privilege tests passed** (including 2 new package-inventory tests). Scanner Android lint: **0 errors, 1 package-visibility warning** (the permission is supplied by the merged capture manifest). **Both standard/research debug flavors built**, using validated existing native libraries (`-PskipNative`). Final log: ignored `build/m5-final-validation.log`. M1 tests/Rust were not rerun because that implementation was unchanged during M5.
- Device: updated only `com.nathanhanapps.wayd.m1test`, preserving its overnight data. On a virtual display, first scan found **22 discoverable apps in 42 s, 0 unavailable**; second scan reused **22 cached results in 1 s**. After the final APK update (two-scan concurrency and Shizuku fallback), **1 app rescanned + 21 cache hits in 5 s**, 0 unavailable. This verifies cache invalidation on APK replacement and persistence across install. WeChat's Declared/Used/Sent screen rendered real stored static facts and retained destinations; system-app signer subjects included ZTE. The initial and final scans are bounded discovery samples, not all-app acceptance.
- The real fixed shell command returned **535 current-user packages**, while final in-app discovery remained **22**. The Shizuku binder path has **not** been established as authorized/working for this test app; do not equate a shell command succeeding with an app grant. Next M5 acceptance step: authorize/use the existing Shizuku settings flow, verify hidden-package access and cold full-inventory scan time (<5 min target), and inspect carrier/vendor signing evidence. Other extraction gaps are recorded in the scanner doc. Do not silently enable root or alter Nubia privacy settings.

Changes remain uncommitted on `feat/m7-control`. The release/base apps were not replaced. No overnight capture export, new VPN session or publication occurred.

Cleanup verified: force-stopped only the isolated test app and closed its scrcpy display; zero scrcpy servers remained on the NX733J. The VPN manager still reports active `com.follow.clash` / FlClash. Overnight recordings remain intact (the updated app still showed the same 2,213 stored capture flows before scanner testing).

## 2026-11-10 · Blocking, content edits, backup and Clash investigation

User requested investigation/design of app-history temporary/permanent blocking, grep-style site matching, response replacement with low power use, rule backup and future Clash integration. Reworked [13-BLOCKING.md](13-BLOCKING.md), extended [routing contract](14-OPTIONAL-ROUTING.md) and M7 acceptance in [roadmap](11-ROADMAP.md); recorded D-033. Proposed defaults are reviewable recommendations, not runtime implementation or user approval of every detail.

Verified source findings: minimal BlockRule entity only; no block enforcement/DAO/install API or control actions. TCP relay dials concurrently before hostname inspection; existing decision slot is MITM authorization only. Native HTTP parser records bounded messages while TLS relay forwards unchanged. Hostname completeness and UID attribution cannot be assumed. Earlier doc promises about every TLS app, guaranteed QUIC fallback and ALLOW precedence were corrected.

Recommended sequence: native app/host/IP policy gates + migration/consent/atomic snapshots/session expiry/live-flow termination; history actions and portable JSON manager; selected research local fixed replies before bounded response transforms; Clash adapter with explicit metadata/socket/DNS/error contract. Temporary defaults to capture-session scope, persistent survives restart, and live-flow Disconnect is distinct. No script runtime/per-request AI. Backup excludes history/grants/keys/proxy secrets; imported rules need binding review and fresh consent. GeoIP stays after Clash integration.

This investigation changed documentation only. No phone commands, VPN changes, runtime/schema changes, build/test runs or publication were performed for it. Earlier M1/M5 worktree changes and validation remain intact. Next implementation starts with M7 enforcement, not working-looking buttons on a passive relay. Validate both flavors and local-server policy fixtures before installing on the phone; response controls and routing have separate later acceptance gates. Proposed limits/lifetimes require implementation validation; no measured battery claim.

## M7 core blocking handoff · 2026-11-10

Implemented the first native control slice and history/rule-management UI in both flavors. See [13-BLOCKING.md](13-BLOCKING.md) for current capabilities and gaps, [D-034](DECISIONS.md) for scope. Preserve the pre-existing uncommitted M1/M5 changes on `feat/m7-control`; this work is not committed or published.

Paths: [policy](../capture/native/src/blocking.rs), [relay](../capture/native/src/relay.rs), [rule lifecycle](../capture/src/main/kotlin/com/nathanhanapps/wayd/capture/BlockingRules.kt), [history and manager UI](../app/src/main/kotlin/com/nathanhanapps/wayd/feature/apps/AppBlockingSection.kt), [Room schema v4](../core/db/schemas/com.nathanhanapps.wayd.core.db.WaydDatabase/4.json).

Usage: Apps → app → Connection history → tap destination → confirm Block this time or Block forever. Whole-app control is explicit. Apps → Blocking rules always permits removal. Session rules need a running capture; durable rules can be queued while capture is off. Search is literal over bounded retained history. Unknown owner/host is not reported as successfully protected.

Validation: native standard 10 and research 25 tests passed; capture Kotlin 32 tests passed. Local TCP/UDP servers verify no pre-dial contact for supported matches, fragmented host gating, active-flow closure with preserved byte counts and unaffected byte forwarding. Actual generated Room 3→4 SQL was applied to a synthetic v3 database using Windows SQLite; legacy rows/counts survived and old rules remain unbound/inactive. Build/lint evidence is in ignored `build/m7-*-validation.log` and native test logs; final outcome recorded below.

Phone: ADB at 192.168.31.52:35193 was reachable; FlClash owns the active VPN. No APK was installed and no phone VPN, overnight data or existing grants were changed in this turn. Host tests do not establish Android netstack closure, package-reinstall/profile or real-device lifetime acceptance.

Next: deliberate device validation with the test app/VPN consent, durable/session expiry and identity/consent failure cases; then explicit JSON backup/import (reviewed portable identities, no grants), research static local replies and bounded response replacement. Actual Clash engine/API integration remains later, with local blocks preceding routing. GeoIP follows Clash integration. Measure CPU/wakeups/battery before energy claims. No scripts, general JS injection or automatic uploads.

Final validation: BUILD SUCCESSFUL (33 s) for both fresh-native debug APK flavors, capture tests, capture lint and both app flavor lint tasks. Lint retains non-blocking warnings. Export-result toast lookup was switched to configuration-aware LocalResources to clear three existing Compose lint errors. Both manifests retain their permissions; scoped documented lint exceptions cover existing broad package attribution and active-VPN foreground eligibility. Bilingual blocking resources have 29 matching keys; XML and Room v4 JSON parse. git diff --check passed. APKs use the isolated .m1test application ID, not a production update.

## 2026-11-10 · Nubia installation and device UI smoke test

At the user's request, installed the validated research debug APK with `adb install -r` on NX733J at 192.168.31.52:35193. Target is the existing isolated `com.nathanhanapps.wayd.m1test`, versionCode 4 / versionName 0.1.2 (lastUpdateTime 14:23:48); the production/release package was not replaced. Install returned Success. App opened without crash-buffer entries; retained capture coverage still reported 2,213 flows and 1,894 data-bearing public TCP flows. No history export or data clearing.

On-device checks passed: global rules dialog opens; historical rows show legacy outcome; visible-host row opens per-app HOST confirmation with consent explanation; Block this time has a disabled parent action with capture off; Block forever saves an inactive rule; rule survives force-stop/relaunch; manager removes it and reports empty afterward. The sole temporary test of a persistent rule was deleted. No live protection was claimed from these UI checks.

Live VPN enforcement/session-expiry testing remains pending explicit VPN-switch approval. Automatic approval review rejected a navigation command because it could start WAYD and replace active FlClash. The user manually approved that exact navigation command, which was executed and only opened the Record tab. A separate explicit question for temporarily switching to WAYD then restoring FlClash remains unanswered at handoff. No workaround was used. Final dumpsys still reports active `com.follow.clash`; WAYD capture remained off. The installed app is left available for use, with no saved or temporary block rules. Further test requires starting capture with the user's routing-switch approval, then controlled local traffic and restoration of FlClash. Main-screen adb taps were more reliable with explicit display 0; inspect after navigation before subsequent actions because screen transitions can discard immediate taps.
## 2026-11-10 · Live-test continuation, waiting for phone unlock

User explicitly approved continuing and said FlClash was closed. On reconnect, VPN manager still named FlClash; after the authorized capture-start action, fresh dumpsys confirmed `com.nathanhanapps.wayd.m1test` owns the VPN. Record UI showed RUNNING, 13 new flows / 4 apps with forwarded bytes; service is foreground. A transient wireless ADB disconnect recovered with reconnect; no crash was established.

Prepared and installed disposable `com.nathanhanapps.wayd.blockprobe` (WAYD Blocking Probe), Internet-only, local PC fixture 192.168.31.169:18765, raw HTTP Host probe.wayd.test. Sources/APK/temporary fixture signing key are ignored under `build/m7-device-probe/`. It has start/stop controls and keeps only its own foreground screen awake. No probe traffic was started: the secure keyguard locked before UI testing. Repeated policy reads confirmed showing=true / screen off. An unlock request is pending; no lock/security setting was changed or bypassed. PC-local test server was stopped while waiting. Restart its `server.cjs` with Node after unlock, start the probe, then verify exact-host/session/whole-app rules and cleanup. The earlier UI-only persistent test rule had already been deleted, so no test rules remain. WAYD capture is currently active; this continuation does not establish live blocking or session-expiry acceptance. Do not claim the previous FlClash state was restored.

## 2026-11-10 · Live host blocking verified; shutdown investigation open

After the user unlocked NX733J, the disposable Internet-only probe sent HTTP requests with Host `probe.wayd.test` to the PC-local fixture. The server received 23 requests before the app-history HOST rule was enabled with Block this time. Its count stayed at 23 while the probe accumulated failed attempts. Undo removed the rule and the server count rose to 39. This verifies actual exact-host enforcement and recovery on Android, beyond the earlier native fixture tests.

Reapplying the temporary rule showed it active. Stopping capture changed the UI to idle, removed the temporary rule and left retained history with BLOCKED outcomes. However, Android connectivity still reported the test VPN CONNECTED; the app process retained one /dev/tun descriptor and two wayd-net threads after Stop. Treat VPN resource shutdown as an unresolved defect, not a successful acceptance check. Native stop currently uses runtime.shutdown_background(); no speculative source fix was applied. Inspect ownership with a clean start/stop reproduction before choosing a fix.

Force-stopping only the isolated .m1test app released the VPN (fresh connectivity dump had no VPN CONNECTED entry). A clean service restart was rejected by Nubia AutoLaunch while the secure keyguard was locked again. An unlock/open-WAYD request is pending; no lock/security setting was bypassed or changed. The disposable probe was force-stopped and its exact owned PC Node fixture process was stopped. Production data/apps were untouched; no user history was exported or cleared. The probe remains installed for follow-up. No test rules remain after session expiry. FlClash was not restarted.

Next: open WAYD on the unlocked phone, reproduce clean capture start/stop and verify all TUN descriptors and wayd-net workers disappear. Then complete whole-app/durable-rule enforcement and remaining identity/consent cases. Backup/import, response editing and actual Clash integration are still follow-on work. Do not mark M7 finished.

## 2026-11-10 · VPN shutdown leak fixed and reinstalled

A clean no-rule app session stopped normally, so the prior failure was not universal. Added an Android/Linux-native regression using a Unix datagram pair as a synthetic TUN: each cycle confirms 32 real TCP OPEN events, races Stop with pending-owner or newly blocked flows, and checks descriptor and native-worker cleanup. The strengthened regression FAILED before the fix with two descriptors for the fixture socket instead of one. It now passes 12 cycles with no native duplicate and no wayd-net workers after each Stop.

Cause: ipstack 1.0.1 TCP stream Drop synchronously waits for its protocol task. Previously nativeStop shut down the runtime before relay destructors finished, allowing those waits to retain resources. The engine now owns relay tasks in a JoinSet, signals its accept owner, aborts/drains relays while protocol tasks remain runnable, drops the stack, then shuts down the runtime. Waits are bounded at two seconds for owner cleanup plus one for runtime shutdown. Existing polling-thread/JNI serialization remains intact; no new permissions, destinations, UI strings or schema changes.

Validation on NX733J: all 11 standard and 26 research native tests passed directly on Android, including the lifecycle regression in both builds. The research CA test initially failed because adb shell starts in read-only / and that test writes a relative target/mitm-test fixture; running from the dedicated writable /data/local/tmp/wayd-m7-tests directory passed the entire suite without a source workaround. Capture unit tests/lint and both fresh-native app flavors built successfully (41 s). Installed the fixed research debug APK with install -r into .m1test, preserving app data. Two installed-app service start/stop cycles each showed two TUN descriptors before Stop, zero afterward, zero wayd-net/wayd-capture threads and no VPN CONNECTED entry. Crash-buffer query returned no entries. These service checks used the debug PC-route exclusion for ADB stability; they do not retest probe blocking through that excluded route.

Evidence stays ignored under build/: m7-lifecycle-standard-test.log, m7-lifecycle-research-test.log, m7-shutdown-build.log and m7-installed-shutdown-result.json. Native test binaries and generated CA fixtures on the phone were removed after testing. Capture remains stopped, FlClash was not restarted, and no saved rules were added. Secure keyguard remains intact; the ADB regression/service checks did not require unlocking, superseding the pending unlock request for this shutdown check.

Remaining M7 device acceptance: whole-app and durable-rule enforcement through UI, persistent consent/identity/profile/reinstall cases. Previously verified exact-host blocking/Undo and session expiry stand. Portable backup/import, response editing and actual Clash integration remain later slices; GeoIP follows Clash. No battery/soak or complete-coverage claim.

## 2026-11-10 · Session-rule lifecycle state normalization

Normalized the VPN-stop path in [BlockingRules](../capture/src/main/kotlin/com/nathanhanapps/wayd/capture/BlockingRules.kt) so transient rules are dropped and the final persistent-only state is published only after consent revocation completes. This keeps the UI and `activeIds` consistent when session rules expire on VPN stop, without affecting durable rules or the independent recording toggle.
