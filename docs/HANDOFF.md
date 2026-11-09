# Handoff

_Last updated: 2026-11-09 · released **v0.1.2** (GitHub release, signed) · branch `main` = released state · working branch for the next work: `feat/m7-control`_

WAYD (在干嘛, `com.nathanhanapps.wayd`) is a local-only Android privacy monitor: a VpnService relay records what each app sends, a rule engine judges it, optional decryption (research features) reads HTTPS content, and an on-device Gemma model explains findings. Read [AGENTS.md](../AGENTS.md) first (non-negotiables), then this file, then [DECISIONS.md](DECISIONS.md) (D-001 .. D-027).

## 1. Where things stand

| Area | State |
|---|---|
| **M0** skeleton, theme (Material 3 Expressive), en + 简体中文, Room v3 + SQLCipher | done |
| **M1** capture (VpnService + Rust netstack), UID attribution, SNI/Host/DNS | working; acceptance soak not done (§7) |
| **M2** enrichment + rules + app-oriented UI (Apps, app detail, Reports, company drill-down) | done, verified on device |
| **M3** per-app permission history (Shizuku AppOps) + permission rules | first slice done |
| **M4** message inspector + identifier rule (R-ID-IN-REQUEST) | built; inspector UI not yet seen on the phone |
| **M6** on-device Gemma (LiteRT-LM) | **working on the NX733J**, quality tuning open |
| **M8 (research)** MITM CA, consent, per-app picker, optional root system-CA | working; Chrome verified, WeChat metadata-only |
| **M7** blocking / content control | designed only → [13-BLOCKING.md](13-BLOCKING.md) |
| Release pipeline | working: tag `vX.Y.Z` → GitHub Actions → signed APK (§5) |

## 2. Repository map

```
app/            UI (Compose), navigation, feature/{analysis,apps,reports,record,settings,ai,inspector,export}
                src/research + src/standard hold the flavor twins (research/ResearchFeatures, Danger zone, MITM controller)
core/model      enums, EvidenceRef
core/db         Room v3 (AutoMigrations 1→2→3), DAOs, SQLCipher key wrapper, schemas/ exported
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
5. **M3 leftovers:** R-BOOT-REPORT, alarms/netstats/package collectors, GeoIP (needs a licensed DB), 24 h per-app heatmap, stored/versioned reports, report render time (< 1 s) measurement, libsu fallback, docs/04 update.
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
