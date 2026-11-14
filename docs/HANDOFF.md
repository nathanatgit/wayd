# Handoff

_Last updated: 2026-11-14 · **v0.5.0** on `main` (versionCode 8) · decisions through D-064._

WAYD (在干嘛, `com.nathanhanapps.wayd`) is an Android privacy monitor: a VpnService forwards traffic and applies local blocks, optional recording saves evidence locally, consent-gated decryption reads supported HTTPS, on-device Gemma (or an approved external OpenAI-compatible server) explains findings. Read [AGENTS.md](../AGENTS.md) first. Older session-by-session notes were compacted on 2026-11-11; see git history (`git log -p docs/HANDOFF.md`) for the full device logs.

## Now

- **Launcher icon (2026-11-12):** adaptive icon now uses the hugging-knife artwork (`docs/brand/wayd-fluent-hugging-knife.svg`) on a white `#FFFFFF` background; the monochrome layer was removed (the silhouette would merge the face, hand and knife). The foreground VectorDrawable was generated from the SVG by a one-off converter (transforms baked, scaled into the 66dp safe zone; SVG drop shadows dropped). Installed the clash debug build over `com.nathanhanapps.wayd.d049` on NX733J (`-PskipNative`, data kept); `assembleCore/ClashDebug` and `lintCore/ClashDebug` pass. Launcher appearance not yet checked on device.

_Released as v0.5.0 on 2026-11-14 (D-060 dashboards, D-061/D-062 labels, depth, Insights chat, D-063 relations/history/APK dashboard/profiles, D-064 block lists). None of D-061 to D-064 has been checked on a device yet; see each entry's "Not device-checked" list._

- **D-064 (latest, 2026-11-14):** global Adblock/hosts **block lists** work end to end in code: native matcher `capture/native/src/hostlist.rs` (hashed hosts, suffix walk, policy ABI 3), `AdblockParser` + `BlockListStore` (`capture/.../BlockLists.kt`), engine hook in `BlockingRules` (`reloadLists`, `listEntries`), and Controls → Block lists (`feature/controls/BlockListsSection.kt`: import file, add HTTPS URL, per-list consent switch, update, remove). Lists use the existing ≤ 600 ms hostname wait and unnamed connections pass. AGENTS.md rules 1 and 9 carry the two approved exceptions. Validation: `cargo test --lib` 15 passed (needs `%USERPROFILE%\.cargo\bin` and WinLibs on PATH); full Gradle build **with native rebuild** (`test`, both debug assemblies, both lints) passes (`build/d064-validation.log`); `libwayd_net.so` contains `nativeSetBlockList`. **Not device-checked:** a real list on the NX733J (load time, connection delay from the hostname wait on non-HTTP apps, memory), list blocking of a live app, URL download from the phone. **Next:** rewrite phase 1 (REJECT + header/query actions) per [16-RULE-SETS-AND-REWRITE.md](16-RULE-SETS-AND-REWRITE.md): active HTTP/1.1 stage in `mitm/`, `RewriteRule` table + migration, Controls → Rewrite; then list hit counters.
- **D-063 (2026-11-13):** labels now tie connections to APK evidence. `LabelRelations` (rules) gives each app host its related permissions (used at the same time / declared and fitting), components and SDKs, a combined attention level (Note / Review / Act) and pointer suggestions (block trial, AppOps restrict, Thanox 移花接木 / disable; "Open AppOps/Thanox" if installed). Connection history is organised by host with labels and a Block button per row; "AI check all connections" classifies hosts in model-sized chunks (`HistoryCheck.kt`, version 201). APK page is a dashboard with collapsed lists (`ApkDashboard.kt`). On-device AI depth is Auto/Small/Medium with no effort control; effort and Large are external-only; external servers can be saved as switchable profiles. **Designed, not built:** separate block/rewrite rule sets, Adblock lists, Loon-style rewrite and the fast matcher: [16-RULE-SETS-AND-REWRITE.md](16-RULE-SETS-AND-REWRITE.md), which ends with four decisions for Nat. Validation: `:analysis:test :ai:test` (130 tests, 1 skipped live test), `:enrich:test :apkscan:test`, both debug assemblies and lints pass (`build/d063-validation.log`). Not device-checked: all new layouts, the history AI check on Gemma (speed and answer quality per chunk), profile switching, AppOps/Thanox launch.
- **D-062 :** label contract v102 (links cited automatically; 4+4 links fit; per-host labels and algorithm advice with reasons; ranked, compact evidence; CONTENT role; more permission meanings). Shared **Analysis depth** panel (context size + effort) on the on-device and external AI pages, applied to app analysis, labels and chat. Insights tab starts with an AI chat card: text, photos, video frames, audio files and push-to-talk voice (`RECORD_AUDIO`, memory only); answers cite the window's report ids. App detail shows its AI card first. Shared `AiIdentity` prefixes every prompt. Validation: `:analysis:test :ai:test :enrich:test :apkscan:test` pass (ai + analysis: 82 tests, 1 skipped live test); `assembleCoreDebug`, `assembleClashDebug`, `lintCoreDebug`, `lintClashDebug` pass (`build/labels-chat-validation.log`). **Not device-checked:** whether the installed Gemma 4 E2B/E4B `.litertlm` files load the vision/audio backends (if not, chat reports "cannot take images/audio"), voice capture on the NX733J, external `image_url`/`input_audio` support on Nat's server, the new layouts in both locales/large fonts.

- **D-061 labels:** APK permissions show simple capability markers; Activity/service/receiver/provider/SDK facts support user-started AI feature explanations; endpoints and recorded connections show controlled roles plus possible permission/feature links. Every generated marker has `AI:`. Tap a linked marker for its exact APK evidence. Expand Labels and evidence to generate/refresh one item; inputs are a disclosed selection of at most 24 metadata items, without captured bodies. No automatic whole-APK inference.
- **Suggestions/actions:** neutral algorithm and AI advice are separate from descriptive markers and deterministic risk findings. Algorithms require attributed TCP SNI/HTTP hostname evidence and recorded bytes before suggesting a domain-block trial. Connection detail now has the same exact-target temporary/persistent confirmation and Undo as app history; AI supplies no action target. Global connection rows show roles instead of the old Danger/Warn/Sensitive/Ad/Normal markers and can reuse saved per-flow AI roles.
- **Evidence/storage:** typed reference validation rejects invented/wrong-kind links, uncited targets, unsupported advice and overlong output. Stored answers retain model/time and original bounded input references; detail views revalidate retained references, so removed/rescanned APK facts cannot silently keep a link. Results reuse the encrypted `AiAnalysis` table (promptVersion 101); Room schema remains v5. No dependency, permission, destination, capture-path or consent-policy change. AppOps context is preserved rather than converting background=false to foreground.
- **Scanner/export:** scanner cache v2 adds declared Activities in the existing TEXT-backed fact kind; rescan to populate them. JSON exports now sample across fact kinds, disclose total/truncation and retain ID/version/source, fixing the old first-400-facts omission of SDKs/permissions. The Nov 11 user export and local analysis are ignored under `build/evidence-review/`; never commit its raw/private data.
- **D-060 retained:** shared compact dashboards, bilingual region naming, Data/Connections pills and summed Others rows. Ranking scope now reads state where sorting occurs, rows have stable identities, icons are package-keyed, and screen-off bars scale by selected totals. Existing dashboard/ranking validation passed; visual acceptance is still open.
- **Validation:** `:analysis:test :ai:test :apkscan:test`, both debug assemblies and both debug lints pass: 127 tests passed, 1 skipped, including 13 new contract/rule tests (`build/labels-revision-complete-validation.log`; preceding full run: `build/labels-revision-final-validation.log`). `git diff --check` passes; schema JSON, manifest and dependency catalog are unchanged. APK metadata confirms `com.nathanhanapps.wayd.d049`, versionCode 7. Use JBR at `C:/Program Files/Android/Android Studio1/jbr`, `-PskipNative` with existing libraries, and quote `'-PappIdSuffix=.d049'`. PowerShell 7 is currently unavailable to the tool runner; explicit `C:/Windows/System32/WindowsPowerShell/v1.0/powershell.exe` works.
- **Resume entry points/artifacts:** `ai/.../LabelAnalysis.kt` (contract/analyzer), `analysis/.../LabelRules.kt` (roles/advice/export selection), `app/.../feature/analysis/LabelViewModel.kt` and `LabelUi.kt` (context/markers), `feature/apps/ConnectionBlockingActions.kt` (detail action). Ready APKs: `app/build/outputs/apk/clash/debug/app-clash-debug.apk` and `app/build/outputs/apk/core/debug/app-core-debug.apk`. Device review path: APK scanner → select app → Rescan → Labels and evidence → Explain labels with AI; also inspect app-history expansion and global connection detail. Check that old/rescanned APK links disappear and saved AI roles survive unrelated new traffic.
- **Device/next:** last installed build is the Nov 11 ranking fix on NX733J `320146880841`, `com.nathanhanapps.wayd.d049` (versionCode 7, data preserved). No adb device is connected on Nov 12, so the label revision is not installed or visually/live-model checked. Next: review new APK labels and connection expansion, rescan one app for Activities, generate one on-device label, then review external-provider preview and both locales/large fonts. Do not start a VPN or alter existing rules for acceptance without Nat. Release CI, custom-model importer and Clash acceptance remain open.

## Status

| Area | State |
|---|---|
| M0 skeleton, theme, en + 简体中文, Room + SQLCipher | done |
| M1 capture (VpnService + Rust netstack), UID attribution, SNI/Host/DNS | accepted for progression by Nat; formal targets not met (last panel sample 99% attribution, 78–85% hostname; target ≥ 99% / ≥ 90%); router/IPv6 comparison not done |
| M2 enrichment, rules, reports, app pages | done, device-verified |
| M3 Shizuku AppOps permission history | first slice done |
| M4 message inspector + R-ID-IN-REQUEST | built; inspector not checked on device |
| M5 APK scanner | built; 22-app sample verified; full-inventory scan via Shizuku and < 5 min cold scan open |
| M6 Gemma 4 on device + external provider | working on NX733J; quality tuning open |
| M7 blocking | exact app/host/IP native gates, session/persistent rules, Undo, Controls inventory, portable JSON transfer. Host blocking, session expiry, recording toggle and VPN shutdown verified on device; whole-app/durable/reinstall cases and content controls open |
| M8 decryption / root CA | working: Chrome via user CA, WeChat/Douyin need root system CA; WeChat MMTLS metadata-only |
| Clash routing (`clash` variant) | Mihomo v1.19.33, profiles, resources, shared GeoIP, whitelist/blacklist access control; import + whitelist save verified on device; live routed VPN run, blacklist save and full FlClash parity open |
| v0.3.0 | Settings hub, external AI, connection detail, exports (JSON v2, PCAPdroid CSV, reconstructed PCAP) — device-verified |

## Repository map

```
app/            UI and navigation; feature/{ai,analysis,apps,common,controls,export,extensions,inspector,record,reports,settings,tools,traffic}
                src/clash (Clash UI, routing store, manifest entry, libwayd_route.so) + src/core (no-op RoutingBridge twin), D-049
core/model      enums, EvidenceRef      core/db  Room v5 + SQLCipher, schemas/ exported      core/ui  theme
capture/        VpnService, FlowTracker, BlockingRules, TrafficRecording, JNI; capture/native = Rust (ipstack relay, blocking, mitm feature); capture/pcap
routing/        Go Mihomo transport (C ABI) → libwayd_route.so (`clash` variant only after D-049)
enrich/ analysis/ collectors/ privilege/ apkscan/ ai/      see docs/01-ARCHITECTURE.md
scripts/        Measure-Capture.ps1 (aggregate-only coverage from an exported capture)
.github/workflows/  ci.yml (branches/PR), release.yml (tags)
```

## Build and test (Windows)

```
export ANDROID_HOME=C:/Users/Nathan/AppData/Local/Android/Sdk   # never create local.properties (lint rejects its escaping)
./gradlew test :app:assembleCoreDebug :app:assembleClashDebug :app:lintCoreDebug :app:lintClashDebug   # builds Rust + Go (Go only for clash)
./gradlew … -PskipNative          # reuse prebuilt .so files
./gradlew … -PappIdSuffix=.m1test # install next to other builds
cd capture/native && cargo test --features mitm
cd routing && go test ./...
```

- JDK: Android Studio JBR. Rust: GNU toolchain + WinLibs MinGW + cargo-ndk, NDK r29 (`29.0.14206865`), SDK `platforms;android-37.0`, arm64-v8a only. Go 1.27.3 portable under ignored `build/tools/go` (`-PgoExecutable=` or PATH; module caches under `build/tools`). Desktop downloads need the local proxy `127.0.0.1:7897`.
- Last full run (v0.3.0): 168 JVM tests, 0 failures (1 skipped: live external-AI test, run with `WAYD_OLLAMA_URL`/`WAYD_OLLAMA_MODEL`, plus `WAYD_EXTERNAL_KEY` for hosted servers; passed 2026-11-11 against Gemini via `https://generativelanguage.googleapis.com/v1beta/openai`, model `models/gemini-2.5-flash`; the key lives in git-excluded `teskapi.md`); both flavors and lints pass.
- Android-native Rust tests: cross-compile with `cargo ndk -t arm64-v8a -P 29 test --lib --no-run [--features mitm]`, push to a dedicated writable dir under `/data/local/tmp` (the CA test writes relative paths), `chmod 700` after each push, `LD_LIBRARY_PATH` for `libwayd_route.so`, delete afterwards. Opt-in live tests and their env vars: [12-TESTING.md](12-TESTING.md).
- Signed local release: `WAYD_KEYSTORE_FILE`, `WAYD_KEYSTORE_PASSWORD`, `WAYD_KEY_ALIAS=key1`, `WAYD_KEY_PASSWORD` (or ignored `keystore.properties`), then `:app:assembleCoreRelease` / `:app:assembleClashRelease -PappIdSuffix=.reltest`.

## Device testing (Nat's NX733J, Android 16, KernelSU, Shizuku 13.6, 15 GB RAM)

- Wireless adb only (last `192.168.31.52:35193`; **the port changes every time** — ask Nat). USB `6f3e7112` is a different phone: never use it. adb drops when the VPN starts/stops.
- Test builds go into `com.nathanhanapps.wayd.m1test` (check `output-metadata.json` before installing). Never replace the production app or clear its data/history; never delete Nat's own rules as fixtures.
- Drive the UI on a **scrcpy virtual display**, never the main screen: `scrcpy -s <dev> --new-display=1080x2400/420 --start-app=<pkg> --no-audio --screen-off-timeout=3600 --keep-active`. Screenshot with `adb exec-out screencap -p -d <SurfaceFlinger id>`; tap with `input -d <display>`; black 15,580-byte PNGs mean the display slept. Kill stale `app_process … scrcpy` after closing.
- Logcat is muted on this ROM: `setprop log.tag I` while testing, **always** `setprop log.tag S` afterwards. After a VPN test: `am force-stop`, no WAYD VPN in `dumpsys connectivity`, ping works.
- Nat accepts the VPN consent dialog himself. Nubia AutoLaunch blocks starting the service via `am`; start from the UI. Only one VPN can run: check who owns it (often FlClash) and ask before switching.
- No `su` mount tricks without D-024's safeguards. **When Nat is away, no live testing.** Nat runs soaks manually; don't repeat or require them.
- Disposable probes (ignored sources under `build/`): `.blockprobe` (raw HTTP to a PC fixture), `.httpsprobe` (Google robots.txt, trusts the test WAYD CA). Never print or commit credential-bearing Clash profiles (`build/*.private.*`).

## Releases

Bump `versionCode`/`versionName`, merge to `main` with `--no-ff`, then `git tag -a vX.Y.Z -m … && git push origin vX.Y.Z` → `release.yml`: version check, Rust + JVM tests, signed release build, `apksigner verify`, GitHub release `wayd-vX.Y.Z.apk` + `.sha256` (~8 min). Secrets `WAYD_KEYSTORE_BASE64`, `WAYD_KEYSTORE_PASSWORD`, `WAYD_KEY_ALIAS`, `WAYD_KEY_PASSWORD` are set; cert SHA-256 `c3ec78c2…d890db`. The release app id has no suffix: uninstall a debug `com.nathanhanapps.wayd` before installing it. Dead tag `v0.1.1` can be deleted (`git push origin :refs/tags/v0.1.1`) when Nat agrees.

## Feature notes

- **Capture:** idle per flow (TCP 1 h, UDP 5 min, DNS 15 s); IPv6 offered only with an underlying IPv6 default route; app excludes itself; host sniffing accumulates ≤ 16 KiB (D-029); unresolved owners retried twice in the first second (D-030). Record traffic defaults off per process and only saves flows wholly inside an enabled window (D-037).
- **Blocking:** native gates before upstream dial for app/IP and evaluable hosts; any BLOCK wins; session rules end at VPN stop; shutdown fixed via JoinSet drain (D-035). Spec: [13-BLOCKING.md](13-BLOCKING.md).
- **Decryption:** rustls engine, Kotlin policy (600 ms decision, default passthrough), per-app allowlist plus fixed exclusions, CA key Keystore-encrypted, bodies ≤ 128 KB in SQLCipher, pinned hosts passed through. The ROM hides the app list, so the picker merges package manager, traffic history, typed names and root `pm list`.
- **AI:** Gemma 4 E2B/E4B `.litertlm` via `litertlm-android` 0.19.1; ModelScope works from the phone (~30 MB/s), Hugging Face does not; GPU needs `libOpenCL.so`, falls back to CPU; E2B ≈ 27 s for ~10 items. External provider per D-046.
- **Clash:** profiles under `no_backup/routing/` (index + per-profile files, wrapped data key, D-045); resources download only on explicit import, Direct via Android's stack or via the first node, no silent fallback; one validated `country.mmdb` serves GEOIP rules and `Flow.country`. Unsupported: MRS/GEOSITE/ASN/subrules, full profile DNS/fake-IP, subscription refresh, live connection controls, group `exclude-type` (stripped — implement or reject). Don't call it FlClash-complete.

## Open items

1. D-048/D-049 migration (above).
2. Clash acceptance: blacklist save, enable routing, live routed run with per-app consent, then decryption over a routed flow from the installed app (native proof exists, installed-app proof does not).
3. M7: whole-app/durable rules through the UI, consent/identity/reinstall cases; then research local replies and bounded response edits.
4. AI quality: stray "OTHER" data type, E4B comparison, injection tests on the real model, overnight summary.
5. M1: session-scoped coverage metrics (separate UID-only owners), TTL-aware DNS correlation, router/IPv6 comparison. QUIC/DoH/DoT/ECH stay invisible; ICMP dropped.
6. M3/M5 leftovers: R-BOOT-REPORT, alarms/netstats/package collectors, heatmap, Shizuku full-inventory scan.

## Known problems

- Wireless adb flaps with the VPN; scrcpy displays die when the phone's screen sleeps.
- Pinned apps and WeChat MMTLS can't be decrypted.
- Coverage panel aggregates all stored flows, not one session.
