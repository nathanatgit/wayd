# Handoff

_Last updated: 2026-11-11 · **v0.3.0** on `main` (versionCode 6) · decisions through D-052._

WAYD (在干嘛, `com.nathanhanapps.wayd`) is an Android privacy monitor: a VpnService forwards traffic and applies local blocks, optional recording saves evidence locally, consent-gated decryption reads supported HTTPS, on-device Gemma (or an approved external OpenAI-compatible server) explains findings. Read [AGENTS.md](../AGENTS.md) first. Older session-by-session notes were compacted on 2026-11-11; see git history (`git log -p docs/HANDOFF.md`) for the full device logs.

## Now

_Handoff 2026-11-11 evening. Decisions through D-052._

**Branches**
- `main` (`7fc6ef0`, local, **not pushed**): D-048 to D-050 merged: `core`/`clash` variants, research merged into common code, external AI over Clash (Nat verified on NX733J: analysis returned with WAYD's VPN + Clash on).
- `feat/model-folder` (`286cd78`, on top of `feat/connection-labels` `c2c590b`), **not merged**: D-051 connection labels + D-052 model folder. Both variants build, both lints pass, 196 JVM tests, 0 failures (1 skipped: opt-in live AI). Merge `feat/model-folder` into `main` with `--no-ff` once the device test below passes; it carries both commits.

**D-051 connection labels:** every row in Traffic → Connections shows Danger / Warn / Sensitive / Ad / Normal from rules (`analysis/ConnectionLabels.kt`); the list runs the rules per shown day (cached). AI purpose guesses appear only as a separate outlined "AI: …?" hint (`ai/AiHints.kt`, re-validated against the evidence each stored answer was given). Not yet seen on the phone.

**D-052 model folder:** Settings → AI → Model folder (picker, suggested Download/WAYD, no new permission). Downloads go there; hand-copied files are found by exact name. A changed file (size/mtime) is hashed and compared with the SHA-256 the chosen source publishes now (HF / ModelScope API; pinned fallback). Mismatch → "older version" or "damaged/edited", nothing loads until the user picks Download again / Place the file myself (+ Check) / Use anyway. Pinned hashes confirmed against both APIs 2026-11-11.

**Next: Nat tests on the phone** (adb was disconnected; sideload `app/build/outputs/apk/clash/debug/app-clash-debug.apk`, installs over `.d049`):
1. Settings → AI → Model folder → pick Download/WAYD.
2. Put `gemma-4-E2B-it.litertlm` there (copy or Download) → "Not checked yet" → check → Installed.
3. Run an on-device analysis: **unverified risk**, folder models load via `/proc/self/fd/N`; if LiteRT-LM rejects that path, fall back to copying the file into app storage once.
4. Optional: append a byte → expect the "damaged" warning and the three choices.
5. Look at Traffic → Connections labels and, after an analysis, the AI hints.

**Still open**
- Push `main` when Nat agrees (commit messages carry no Claude co-author line).
- Clash: real profile surviving `clash` → `core` → `clash`; failing node showing `ext_route`; blacklist mode routing WAYD; "via Clash" note in the AI approval dialog (idea).
- Release signing key is not on this PC (no `keystore.properties` / `WAYD_KEYSTORE_*`); only debug APKs can be built here.
- Device tests: ask Nat to do the taps (faster than scrcpy automation). `.m1test` has a different debug key; use `-PappIdSuffix=.d049`.

**This PC's toolchain** (all verified downloads): Rust 1.99.1 GNU + `aarch64-linux-android` + cargo-ndk 4.1.2; WinLibs MinGW (winget); Go 1.27.3 in ignored `build/tools/go`; NDK 29.0.14206865 in the SDK. For native builds put `~/.cargo/bin` and WinLibs `mingw64/bin` on PATH; `JAVA_HOME` = the Gradle-provisioned JBR 21 under `~/.gradle/jdks` (Android Studio's JBR lacks `jvm.cfg`). If a build fails with a locked `classes.jar`, an orphaned Gradle/Kotlin daemon holds it: stop it and rebuild.

**Leftovers from v0.3.0:** Traffic → By app and the app page's connection-history cards still use large cards and don't open the connection detail page; the app page's permission section is text-heavy; PCAP has no per-packet app attribution (PCAPNG with comments is a possible follow-up); blacklist-mode Clash save and a live routed VPN run are unverified.

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
