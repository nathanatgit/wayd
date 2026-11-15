# Handoff

_Last updated: 2026-11-14 · **v0.5.0** released from `main` (versionCode 8) · decisions through D-070._

WAYD (在干嘛, `com.nathanhanapps.wayd`) is a local-only Android privacy monitor. A `VpnService` forwards traffic and applies local blocks; optional recording keeps evidence on the phone; consent-gated decryption reads supported HTTPS; rules produce findings; on-device Gemma (or an approved external OpenAI-compatible server) explains them.

Read [AGENTS.md](../AGENTS.md) first, then this file, then the doc for the module you touch. History of this file: `git log -p docs/HANDOFF.md`.

## Now

_Handed off 2026-11-15, 12:20._

| | State |
|---|---|
| Released | **v0.5.0** (2026-11-14): D-060 to D-064. Release workflow and CI pass. |
| Unreleased | Branch `feat/dns-list-blocking`, **local only, not pushed**, 12 commits ahead of `main`: D-065 DNS-stage block lists, app chat, eval test · D-066 rewrite rules (request side) · D-067 inferred hostnames, rule-plugin list import · D-068 redirect, list counters, `.gitattributes` · D-069 regex matching (new Rust `regex` dependency) · D-070 rewrite import from rule plugins · `tools/rwprobe` test app · docs and [TODO.md](TODO.md). The last commit is the D-070 work plus this handoff. |
| On the phone | NX733J `320146880841`: clash debug with **everything through D-070** installed over `com.nathanhanapps.wayd.d049` on 2026-11-15 12:14, data kept (version still reads 0.5.0). Probe `com.nathanhanapps.wayd.rwprobe` installed. `Download/wayd-probe-rules.plugin` pushed for import. |
| Last validation | `cargo test --lib --features mitm` 37 passed; full Gradle build with native rebuild (`test`, both debug assemblies, both lints) passes: `build/d070-validation.log`. |
| Device evidence so far | One probe **baseline** run (2026-11-15, FlClash owned the VPN, no WAYD rules): 15 requests returned 200, no FAIL, every rule check "no". **No WAYD feature since v0.4.0 has been exercised on the phone.** |

### Where the new screens are (Nat could not find them at first)

There is no 管控 tab. Open **工具 → 阻止与改写** (or the 阻止与改写 row on 总览), then scroll: summary card → rule backup → **阻止列表** → **改写规则** → per-app rules.

### Immediate next step: the probe run with WAYD

Nat does on the phone (details: [tools/rwprobe/README.md](../tools/rwprobe/README.md)):

1. Switch the VPN from FlClash to WAYD.
2. Add `com.nathanhanapps.wayd.rwprobe` to the decrypted apps (WAYD user CA installed).
3. 改写规则 → 导入规则插件 → `Download/wayd-probe-rules.plugin` → package `com.nathanhanapps.wayd.rwprobe`.
4. 阻止列表 → import a one-line list `||example.org^` and enable it.

Then run the probe (`adb shell am start -n com.nathanhanapps.wayd.rwprobe/.MainActivity --ez run true`, read `/sdcard/Android/data/com.nathanhanapps.wayd.rwprobe/files/result.txt`; in Git Bash prefix adb with `MSYS_NO_PATHCONV=1`). Expected: steps 2, 3, 5, 6, 7, 8 show `INFO … yes`, no `FAIL`. A Sonnet subagent is enough for the run; it must not start a VPN, tap the UI, use root or touch other packages.

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

1. Probe run and device pass; fix what they find.
2. Push and merge the branch; tag `v0.6.0` (versionCode 9) unless Nat prefers otherwise.
3. Rewrite phase 2 (response headers, JSON body edits; gzip dependency decision still open).
4. Measure hostname coverage; run `HistoryEvalTest` against Gemma.

### Open questions for Nat

- Add a gzip library for body edits?
- Push the branch before or after the device pass?

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
