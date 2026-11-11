# Handoff

_Last updated: 2026-11-11 · **v0.3.0** on `main` (versionCode 6) · decisions through D-050._

WAYD (在干嘛, `com.nathanhanapps.wayd`) is an Android privacy monitor: a VpnService forwards traffic and applies local blocks, optional recording saves evidence locally, consent-gated decryption reads supported HTTPS, on-device Gemma (or an approved external OpenAI-compatible server) explains findings. Read [AGENTS.md](../AGENTS.md) first. Older session-by-session notes were compacted on 2026-11-11; see git history (`git log -p docs/HANDOFF.md`) for the full device logs.

## Now

**D-048 + D-049 build migration is in code on `feat/d049-variants` (uncommitted).** `research` is merged into `main` (one `--features mitm` capture lib, `buildNativeMitm`, decryption still gated by the Danger zone consent); flavor dimension `routing` has `core` and `clash`. Clash UI, `RoutingSettings`, `RoutingGeoIp`/`RoutingDownloads`, resource URLs, their strings (both locales), manifest entry and `libwayd_route.so` packaging live in `app/src/clash`; `:capture:buildRouting` feeds only `clash` merge tasks. The shared `WaydVpnService`/`RecordViewModel` use `capture.RoutingBridge` (Hilt-bound to `ClashRoutingBridge` in `clash`, `NoRouting` in `core`, which never touches `no_backup/routing/`). The Rust side `dlopen`s `libwayd_route.so` lazily and fails soft, and `core` always starts with an empty routing profile, so `core` needs no native change. CI and `release.yml` build both variants (assets `wayd-vX.Y.Z.apk`, `wayd-clash-vX.Y.Z.apk`).

**D-050 external AI over Clash (same branch, uncommitted).** In `clash`, when routing is on, the VPN runs and access control selects WAYD itself, external AI calls to public HTTPS servers go through a Go `connect` command (`routing/connect.go` `DialName`: rules applied with WAYD as owner, dialed **by name** through the node) that returns a socketpair; WAYD runs TLS (`ai/ExternalAiStream.kt` `EngineTls`, hostname-verified) and HTTP/1.1 (`StreamHttp`) over it, so routing sees only ciphertext. Failure → `ext_route`, never Direct. The access-control picker now lists WAYD itself (its own traffic never enters the tunnel, so this only affects AI calls).

**Verified 2026-11-11:**
- This PC now has the full toolchain: Rust 1.99.1 (GNU host) + `aarch64-linux-android` + cargo-ndk 4.1.2, WinLibs MinGW (winget, needed for `dlltool`; add its `mingw64/bin` and `~/.cargo/bin` to PATH), Go 1.27.3 in `build/tools/go` (SHA-256 checked), NDK 29.0.14206865 unpacked into the SDK (SHA-1 checked; no `sdkmanager` here). Both native libs built from source.
- `./gradlew :app:assembleCoreDebug :app:assembleClashDebug :app:lintCoreDebug :app:lintClashDebug test`: BUILD SUCCESSFUL, 187 tests, 0 failures, 1 skipped (live AI). `go vet` clean, all Go tests pass (3 new `DialName`). Live Gemini test over the new `EngineTls` path passed from the PC (`WAYD_EXTERNAL_ROUTED=1`).
- NX733J, `clash` debug as `com.nathanhanapps.wayd.d049`: imported `Mixed_country_20261010.yaml` (10 nodes, 5 groups, 24 rules); **all 17 resources incl. 7.6 MB GeoIP downloaded via the first node** after one retry (FlClash was running underneath). Whitelist = WAYD, routing on, Gemini provider saved. **Nat started WAYD's VPN and ran an analysis: report returned** over the route. Without WAYD's VPN, the old direct call hung in `connectSocket` (Google unreachable directly), which is the case D-050 fixes. Earlier: `core` over `clash` hides Clash rows and leaves `no_backup/routing/` untouched; `ClashExtension.intent()` now resolves only in WAYD's own package.

**Still to do:**
1. Commit `feat/d049-variants` when Nat agrees (no Claude co-author line), then merge per Releases.
2. Not yet checked on device: a real profile surviving `clash` → `core` → `clash`; a failing node giving `ext_route` in the UI; blacklist mode routing WAYD; Opus review of the `RoutingBridge` wiring in `WaydVpnService`.
3. Possible follow-up: show "via Clash" in the AI approval dialog when the call will be routed.
4. Device testing: ask Nat to do the taps (faster than scrcpy automation). `.m1test` has a different debug key than this PC; use `-PappIdSuffix=.d049`.

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
