# Handoff

_Last updated: 2026-11-16 · **v0.6.0** released (versionCode 9): D-065 to D-077 · decisions through D-077._

WAYD (在干嘛, `com.nathanhanapps.wayd`) is a local-only Android privacy monitor. A `VpnService` forwards traffic and applies local blocks; optional recording keeps evidence on the phone; consent-gated decryption reads supported HTTPS; rules produce findings; on-device Gemma (or an approved external OpenAI-compatible server) explains them.

Read [AGENTS.md](../AGENTS.md) first, then this file, then the doc for the module you touch. History of this file: `git log -p docs/HANDOFF.md`.

## Now

_Handed off 2026-11-16, night._

| | State |
|---|---|
| Released | **v0.6.0** (2026-11-16): D-065 to D-077 (DNS-stage lists, rewrite rule sets, Clash rule compatibility, import, prompt tuning, AI evidence with decrypted content and APK facts, Settings tab). Tag pushed; check the release workflow result. |
| On the phone | NX733J `320146880841`: clash debug of the release tree over `com.nathanhanapps.wayd.d049` (data kept). History there is a partial re-import (D-077). |
| Last validation | `./gradlew test :app:assembleCoreDebug :app:assembleClashDebug :app:lintCoreDebug :app:lintClashDebug` with native builds, and `go test ./...` in `routing/` (`build/release-validation.log`). |
| Device evidence | Exports A/B complete and valid; external AI (Gemini, OpenAI-compatible) works over Clash in blacklist mode; prompts run on E2B without errors. Not yet tapped through: Settings tab, 外部 acknowledgement dialog, re-import of the cut file, AI with decrypted content. |

### UI revision (2026-11-15, Nat's requests)

- **Quick Settings tile** (`capture/…/CaptureTileService.kt`): starts/stops the VPN; opens the app when VPN consent is still missing.
- **AI cards** (`AiUi.kt` `AiAssistCard`): provider mark (`AiMark.kt`: Gemma → Gemini mark; external → Grok/ChatGPT/Claude/Kimi/Qwen/DeepSeek by model name then host; star fallback; marks from LobeHub icons, MIT, `res/drawable/ic_ai_*.xml`), `action` slot in the title row ("用 AI 分析"), the "rules decide severity" line moved behind a `?` (`HelpHint`).
- **Chat** (`ChatUi.kt`): Gemini-style rounded box, rotating grey hints (`chat_hints` / `chat_hints_app` arrays), stop button while answering.
- **Connection history**: "AI 检查" button in the collapsed header.
- **External AI**: profile list + 添加配置; every save is a profile (`ExternalAiSettings.saveProfile(name, id)`); legacy single server migrates to a profile. Warning banner and per-server acknowledgement unchanged.
- **阻止与改写 / HTTPS 解密**: rebuilt with `InsetGroup` / `ItemRow` / `ActionRow` / `PageHeader` (in `DashboardParts.kt`), summary panel with `StatGrid`; explanations behind `?`. Unused strings removed in both locales.
- **Not built**: whitelist/blacklist mode for the decryption app list. The mode check is free, but blacklist with few exclusions decrypts nearly every app (double TLS, storage, pinned apps failing). Offered instead: "apps with recorded traffic except these" if Nat wants it.

### Where the new screens are

Tabs since D-071: 总览 · 流量 · 规则 · 洞察 (设置 behind the gear on 总览, APK scanner behind the search icon on 洞察). **规则 → DNS**: switch, numbers, DNS 规则 (typed rules + imported hosts/dnsmasq files, D-072), 记录类型, 阻止列表. **规则 → 请求**: 改写规则 switch, summary, 改写规则 → 阻止规则 → 规则备份与恢复. Not installed or tapped through on the phone yet.

### Immediate next step: install, then the probe run with WAYD

Install a core/clash debug over `.d049` (ask Nat first), have him tap through the revised screens and add the tile. Then Nat does on the phone (details: [tools/rwprobe/README.md](../tools/rwprobe/README.md)):

1. Switch the VPN from FlClash to WAYD.
2. Add `com.nathanhanapps.wayd.rwprobe` to the decrypted apps (WAYD user CA installed).
3. 规则 → 请求 → 改写规则 → 导入规则插件 → `Download/WAYD/wayd-probe-rules.plugin` → package `com.nathanhanapps.wayd.rwprobe`.
4. 规则 → DNS → 阻止列表 → 导入文件 → `Download/WAYD/wayd-probe-blocklist.txt`, enable it.

Then run the probe (`adb shell am start -n com.nathanhanapps.wayd.rwprobe/.MainActivity --ez run true`, read `/sdcard/Android/data/com.nathanhanapps.wayd.rwprobe/files/result.txt`; in Git Bash prefix adb with `MSYS_NO_PATHCONV=1`). Expected: steps 2, 3, 5, 6, 7, 8 show `INFO … yes`, no `FAIL`. A Sonnet subagent is enough for the run; it must not start a VPN, tap the UI, use root or touch other packages.

### AI prompt tuning (D-076, 2026-11-16, uncommitted)

- `ai/`: `HistoryContract`, `LabelContract`, `ChatContract` prompts and per-request schemas changed; `:ai:testDebugUnitTest` passes. Measured in `tools/promptlab/` (README there) against Ollama Gemma E2B/E4B with Claude Haiku as the reference.
- **Unverified on the phone:** that LiteRT-LM honours the enum/minItems schemas and the property order (ask Nat to run "AI check all connections" and one label on E2B; a schema it rejects falls back to unconstrained text, which `parse` still guards).
- Exports: `wayd-capture-20261115-223327.json` on the phone is cut at 11,796,480 bytes (45 × the 256 KiB writer buffer). Likely cause, not confirmed by a log: the export coroutine lives in `ExportButton`'s `rememberCoroutineScope`, and `runCatching` in `ExportService.export` swallows cancellation; the partial file was not deleted. Capture exports also keep only 400 APK facts per app, almost all endpoints for large apps, so permissions/components are missing there. Not fixed.

### AI evidence, export/import, Settings tab (D-077, 2026-11-16, uncommitted)

- Built, linted (both variants) and `:ai:testDebugUnitTest` green; **not installed on the phone**.
- Phone history is a partial re-import (see D-077): flows from 11-12 19:04 to 11-15 21:25 and all permission events are gone from `.d049`; the cut file `Download/WAYD/wayd-capture-20261115-223327.json` can be re-imported with the new importer to recover flows up to its cut (dedupe skips what is already stored).
- External AI over Clash: under test with Nat (blacklist mode, WAYD VPN on).

### Not checked on a device (everything since D-061)

Full table: [todo/device-pass.md](todo/device-pass.md). Ask Nat to tap through; do not start a VPN or change his rules without him.

1. **Labels and history** (D-061 to D-063): host rows, attention badges, relation chips, Block button, per-item "Ask AI", "AI check all connections" on Gemma, APK dashboard, both locales and large fonts.
2. **AI settings:** on-device Auto/Small/Medium, external effort and Large, profiles.
3. **Chat** (Insights and app page): text, photo, video frames, audio file, push-to-talk; whether the installed Gemma files load vision/audio.
4. **Block lists** (D-064, D-065, D-068): import, consent, a listed host blocked, DNS-stage answer, counters, hostname-wait delay on non-HTTP apps, a large list.
5. **Rewrite rules** (D-066, D-068 to D-070): the probe run above; import of a real rule plugin.
6. **Inferred hostnames** (D-067): coverage before/after.
7. **AppOps / Thanox buttons**, new launcher icon.

### Next

Full prioritised list: [TODO.md](TODO.md).

1. Probe run and device pass (including the UI revision and the tile); fix what they find.
2. Tag `v0.6.0` (bump to versionCode 9) unless Nat prefers otherwise; the branch is already merged.
3. Rewrite phase 2 (response headers, JSON body edits; gzip dependency decision still open).
4. Measure hostname coverage; run `HistoryEvalTest` against Gemma.

### Open questions for Nat

- Add a gzip library for body edits?
- Decryption app list: keep allowlist only, or add "recorded apps except these"?

## Status

| Area | State |
|---|---|
| M0 skeleton, theme, en + 简体中文, Room + SQLCipher | done |
| M1 capture, UID attribution, SNI/Host/DNS | accepted for progression; targets not met (99% attribution, 78–85% hostname vs ≥ 99% / ≥ 90%); router/IPv6 comparison not done |
| M2 enrichment, rules, reports, app pages | done, device-verified |
| M3 AppOps permission history (Shizuku) | first slice done |
| M4 message inspector, R-ID-IN-REQUEST | built; inspector not device-checked |
| M5 APK scanner | built, 22-app sample verified; dashboard UI (D-063) not device-checked; full-inventory scan and < 5 min cold scan open |
| M6 AI | Gemma 4 on device and external provider work on NX733J; labels, history check, chat, depth, profiles (D-061 to D-065) not device-checked |
| M7 blocking | per-app app/host/IP rules verified on device; block lists, DNS stage and request-side rewrite rules (D-064 to D-066) not device-checked; response and body rewriting not built |
| M8 decryption / root CA | works: Chrome via user CA; WeChat/Douyin need the root system CA; WeChat MMTLS metadata only |
| Clash routing (`clash` variant) | import and whitelist save verified; live routed run, blacklist save, FlClash parity open |

## Repository map

```
app/            UI; feature/{ai,analysis,apps,common,controls,export,extensions,inspector,record,reports,settings,tools,traffic}
                src/clash (Clash UI, routing store, libwayd_route.so) + src/core (no-op twin), D-049
core/model      enums, labels        core/db  Room v5 + SQLCipher, schemas/ exported        core/ui  theme
capture/        VpnService, FlowTracker, BlockingRules, BlockLists, JNI; capture/native = Rust (relay, blocking, hostlist, mitm)
routing/        Go Mihomo transport → libwayd_route.so (clash variant only)
enrich/ analysis/ collectors/ privilege/ apkscan/ ai/      see 01-ARCHITECTURE.md
.github/workflows/  ci.yml (push/PR: tests, both variants, lint), release.yml (tags)
```

Where the recent work lives:

| Topic | Files |
|---|---|
| Label rules, relations, attention | `analysis/…/LabelRules.kt`, `LabelRelations.kt`; UI `app/…/feature/analysis/LabelViewModel.kt`, `LabelUi.kt` |
| AI contracts | `ai/…/LabelAnalysis.kt` (v102), `HistoryCheck.kt` (v201), `ChatContract.kt`, `AiIdentity.kt`, `AiDepth.kt`, `AiMedia.kt` |
| History and APK screens | `app/…/feature/apps/AppBlockingSection.kt`, `ApkDashboard.kt` |
| Chat | `app/…/feature/ai/ChatUi.kt`, `InsightsChatViewModel.kt`, `ChatMedia.kt` |
| Block lists | `capture/native/src/hostlist.rs`, `capture/…/BlockLists.kt`, `app/…/feature/controls/BlockListsSection.kt` |
| Rewrite rules | `capture/native/src/mitm/rewrite.rs`, `capture/…/RewriteRules.kt`, `RewritePlugin.kt` (import), `app/…/feature/controls/RewriteSection.kt` |
| Test probe | `tools/rwprobe/` (standalone Gradle project; `./gradlew -p tools/rwprobe assembleDebug`) |

## Build and test (Windows)

```
export JAVA_HOME="C:/Program Files/Android/Android Studio1/jbr"
export ANDROID_HOME=C:/Users/Nathan/AppData/Local/Android/Sdk      # never create local.properties
./gradlew test :app:assembleCoreDebug :app:assembleClashDebug :app:lintCoreDebug :app:lintClashDebug   # builds Rust + Go
./gradlew … -PskipNative             # reuse prebuilt .so files (not after Rust/Go changes)
./gradlew … '-PappIdSuffix=.d049'    # install next to other builds
cd capture/native && cargo test --lib [--features mitm]            # needs %USERPROFILE%\.cargo\bin and WinLibs on PATH
cd routing && go test ./...
```

- Toolchain: Android Studio JBR; Rust GNU toolchain + WinLibs MinGW + cargo-ndk; NDK r29 (`29.0.14206865`); SDK `platforms;android-37.0`; arm64-v8a only; Go 1.27.3 under ignored `build/tools/go`. Desktop downloads need the local proxy `127.0.0.1:7897`. Full checklist: AGENTS.md.
- Opt-in live tests (skipped without env): `WAYD_OLLAMA_URL` + `WAYD_OLLAMA_MODEL` (+ `WAYD_EXTERNAL_KEY`) run the external analysis test and `HistoryEvalTest` (prints a role/advice score). Details: [12-TESTING.md](12-TESTING.md).
- Android-native Rust tests: `cargo ndk -t arm64-v8a -P 29 test --lib --no-run [--features mitm]`, push to a dedicated dir under `/data/local/tmp`, `chmod 700`, `LD_LIBRARY_PATH` for `libwayd_route.so`, delete afterwards.
- Signed local release: `WAYD_KEYSTORE_FILE`, `WAYD_KEYSTORE_PASSWORD`, `WAYD_KEY_ALIAS=key1`, `WAYD_KEY_PASSWORD` (or ignored `keystore.properties`), then `:app:assembleCoreRelease` / `:app:assembleClashRelease`.

## Device testing (Nat's NX733J, Android 16, KernelSU, Shizuku 13.6, 15 GB RAM)

- Device id `320146880841`. USB `6f3e7112` is a different phone: never use it. Over wireless adb the port changes every time (ask Nat); adb drops when the VPN starts or stops.
- Test builds go into a suffixed package (currently `.d049`); check `output-metadata.json` before installing. Never replace the production app, clear its data, or use Nat's rules as fixtures.
- **Ask Nat to tap through UI checks.** If driving the UI is unavoidable, use a scrcpy virtual display, never the main screen.
- Logcat is muted on this ROM: `setprop log.tag I` while testing, **always** `setprop log.tag S` afterwards. After a VPN test: `am force-stop`, no WAYD VPN in `dumpsys connectivity`, ping works.
- Nat accepts the VPN consent dialog himself; start the service from the UI (AutoLaunch blocks `am`). Only one VPN can run: check who owns it (often FlClash) and ask before switching.
- No `su` mount tricks without D-024's safeguards. **When Nat is away, no live testing.** Nat runs soaks himself.
- Never print or commit credential-bearing Clash profiles (`build/*.private.*`) or `teskapi.md`.

## Releases

Bump `versionCode`/`versionName` in `app/build.gradle.kts`, merge to `main` with `--no-ff`, then `git tag -a vX.Y.Z -m … && git push origin vX.Y.Z`. `release.yml` checks the tag against `versionName`, runs Rust + JVM tests, builds and verifies signed APKs and publishes `wayd-vX.Y.Z.apk`, `wayd-clash-vX.Y.Z.apk` and their `.sha256` (about 10 min). Commit messages carry no co-author line. The release app id has no suffix: uninstall a debug `com.nathanhanapps.wayd` before installing it.

## Feature notes

- **Capture:** idle per flow TCP 1 h, UDP 5 min, DNS 15 s; IPv6 only with an underlying IPv6 route; the app excludes itself; host sniffing ≤ 16 KiB (D-029); recording is off by default per process (D-037).
- **Blocking:** per-app rules, then block lists; decided once per connection before the upstream dial; listed DNS names are answered locally (D-065). Session rules end at VPN stop. Spec: [13-BLOCKING.md](13-BLOCKING.md), lists and rewrite: [16-RULE-SETS-AND-REWRITE.md](16-RULE-SETS-AND-REWRITE.md).
- **Decryption:** rustls, HTTP/1.1 only, per-app allowlist, 600 ms decision with pass-through default, bodies ≤ 128 KB in SQLCipher, pinned hosts passed through.
- **AI:** Gemma 4 E2B/E4B `.litertlm` via `litertlm-android` 0.19.1, GPU with CPU fallback, 8K-token window; every request fits a per-provider budget; all prompts start with `AiIdentity`; rules decide, AI explains. Details: [06-AI-GEMMA.md](06-AI-GEMMA.md).
- **Clash:** profiles under `no_backup/routing/`; resources download only on explicit import; unsupported: MRS/GEOSITE/ASN/subrules, profile DNS/fake-IP, subscription refresh, live connection controls. Not FlClash-complete.

## Open items

1. Device pass for D-061 to D-065 (list above).
2. Rewrite rules and plugin-format import/export (doc 16); list hit counters.
3. M1: TTL-aware DNS correlation, session-scoped coverage metrics, router/IPv6 comparison. DoH/DoT/ECH stay invisible; QUIC is only covered by the DNS stage of block lists.
4. AI quality: eval score per model, stray "OTHER" data type, injection tests on the real model.
5. Label snapshot is rebuilt in full on each database change; measure on the phone before optimising.
6. M7 per-app rules: whole-app/durable/reinstall cases through the UI.
7. Clash acceptance: blacklist save, live routed run, decryption over a routed flow from the installed app.
8. M3/M5 leftovers: R-BOOT-REPORT, alarms/netstats/package collectors, Shizuku full-inventory scan.

## Known problems

- Wireless adb flaps with the VPN.
- Pinned apps and WeChat MMTLS cannot be decrypted.
- Coverage panel aggregates all stored flows, not one session.
- Apps using their own encrypted DNS bypass the DNS stage of block lists (the TCP hostname check still applies).
