# Handoff

_Last updated: 2026-11-09 — M0 done; M1 capture working on NX733J; M2 (enrichment, rules, app-oriented analysis UI) built and verified on device._

## State

- M0 done on branch `feat/m0-skeleton`: modules `app`, `core:model`, `core:db`, `core:ui`; flavors `standard` / `research` (`.research` app-id suffix).
- `WaydTheme` (Material 3 Expressive, dynamic colour), 4-tab navigation, strings in en + zh-Hans.
- Room schema v1 with all entities from `docs/02-DATA-MODEL.md` (schema exported to `core/db/schemas/`), SQLCipher with Keystore-wrapped key. Settings tab shows the live `PRAGMA cipher_version`.
- CI workflow: unit tests, both flavor builds, lint.
- Verified on NX733J (Android 16) on a scrcpy virtual display: all tabs render in zh, DB reports encrypted.
- Not yet: release build/signing, instrumented tests (no Room migration test yet — nothing to migrate from v1).

## Build

```
export ANDROID_HOME=C:/Users/Han/AppData/Local/Android/Sdk
./gradlew test :app:assembleStandardDebug :app:assembleResearchDebug :app:lintStandardDebug :app:lintResearchDebug
```
Do not create `local.properties` on this machine: lint rejects its path escaping; use `ANDROID_HOME`.

## Testing without disturbing the phone

`adb install`, then `scrcpy -s <dev> --new-display=1080x2400/420 --start-app=<pkg> --no-audio -N --record=<file>`; screenshot with `adb exec-out screencap -d <SurfaceFlinger display id> -p` (id from `dumpsys SurfaceFlinger --display-id`, not the logical id); tap with `input -d <logical id>`. Kill `scrcpy.exe` afterwards.

## MITM status (research flavor) — working, verified on the phone 2026-11-09

Done and verified on Nat's NX733J: native engine, Kotlin side (MESSAGE/TLS_NOTE/PROGRESS events, messages stored in SQLCipher with the flow, `InterceptHook` seam), research-only consent flow (warning, scroll-to-end privacy statement, Keystore-encrypted CA key, per-app picker with search / checked-first / Name & Date-installed sort, exclusion list, "Disable and remove CA"), CA file saved through the document picker, optional volatile system-wide trust via root (D-024), raw-data JSON export (capture / per app) through the document picker with a privacy warning, Chrome capture (354 decrypted messages incl. Bilibili). Per-flow byte counts for flows still open at stop come from PROGRESS events.

Known limits: WeChat uses its own MMTLS protocol (metadata only even with system trust); apps that pin certificates stay metadata only; ModelScope range requests redirect (302) and were not verified; message inspector UI (M4) not built yet (export is the way to read messages).

## M3 status (permission-use history, first slice)

Done: `collectors` (parser for `dumpsys appops --history`, `PermissionCategory`, `PermissionTimeline` with proportional window clipping, 5 tests on a trimmed real-device fixture), `privilege` (AIDL `IPrivilegedService` with fixed calls, `PrivilegedService`, `HistoryFilter`, `PackageNames`, `ShizukuClient` with NOT_INSTALLED / NOT_RUNNING / NEEDS_PERMISSION / READY), app detail "Permission use" section: per category counts, background share and a 24-hour lane (visible vs background), blocked-only wording. Verified on NX733J with Shizuku 13.6 (Douyin: thousands of background location / file / Bluetooth accesses today).

Shizuku was started with its own starter binary over adb (`<apk dir>/lib/arm64/libshizuku.so`), which lasts until reboot. After a reboot the user must restart it from the Shizuku app (wireless debugging).

Not done: rules R-CLIPBOARD-BG, R-SCREEN-CAPTURE, R-LOC-THEN-UP, R-BOOT-REPORT (inputs now exist at bucket resolution), `appOpsGet` "last used" times, persisting history (PermEvent) for long retention, alarms / netstats / package collectors, libsu fallback, Command allowlist doc update (docs/04). Counts are system AppOps accesses, not proof of data leaving the phone; always show them next to network evidence.

## M2 status (enrichment + analysis)

Done: `enrich` (seed tracker/company DB, DoH hosts, private-address check), `analysis` (8 rules, risk score with reasons, `AppReportBuilder`, `DailyReportBuilder`), Apps tab (list ranked by activity, window chips Today/Yesterday/7 days), app detail (plain-language summary, hourly chart, destinations by company+purpose, findings that expand to their evidence connections, permission-use placeholder), Reports tab (days list, report detail with alerts / background / who-received-data / new / coverage). Flows are enriched with company+category at capture and backfilled on analysis. 42 unit tests.

Not done: GeoIP/countries and the 24 h heatmap by app (report has hourly totals only), stored/versioned report objects (computed on demand, fast for current data), identifier matcher (needs M4 messages), Sankey, per-day baselines beyond "new domain". Report render time for 24 h not yet measured (acceptance: < 1 s).

**Next (Nat's request): per-app permission-use history** (location, camera, mic, clipboard...) visualised in the app detail. Plan: M3 AppOpsCollector. Probe on the device shows `dumpsys appops --history --package` works from adb; in-app needs Shizuku/root. Decide: Shizuku vs adb-only dev path first.

## M1 status (capture MVP)

Working on device: VpnService + Rust `ipstack` relay (arm64), UID attribution, TLS SNI / HTTP Host / DNS enrichment, batched Room writes, foreground notification, Record tab with live counters and flow list. Verified: 19 flows / 5 apps attributed correctly in a ~1 min run; tunnel teardown restores connectivity.

Fixed during testing: missing `ACCESS_NETWORK_STATE` crashed the poll thread (process died, VPN lingered until force-stop). Tracker now survives enrichment failures.

**Measured (cumulative, NX733J, 2026-11-09):** 512 flows, 99% with package (target >=99% OK); hostname coverage 84% over 103 public TCP flows that carried data (target >=90%, not yet met). The Record tab shows this live; LAN probes and zero-byte flows are excluded from the hostname metric because they cannot have a hostname. Relay now captures the client's first bytes while the outbound connect is in flight (SNI of slow/failed connections).

**Not yet met (M1 acceptance):** 1 h soak with browse/video/WeChat; >=99% flows with package; >=90% TCP flows with host (many flows are still "no hostname": IP-only/QUIC/cached DNS); external-router IPv4/IPv6 completeness check. **Known gaps:** ICMP is dropped (ping fails inside the tunnel), DoH/DoT bypasses DNS visibility, QUIC SNI not parsed, arm64 only, `appForeground` is always false until M3, no pkt counters, `nativePoll` allocates a 64 KB scratch buffer per call (optimise).

## Testing a VPN build without disturbing the phone

Consent dialog persists after the first accept (Nat accepts it once on the main display). Then drive the app on a scrcpy virtual display. scrcpy may drop (`Device disconnected`) the moment the VPN comes up; just start a new scrcpy session afterwards. Always finish with: stop recording, `am force-stop com.nathanhanapps.wayd`, confirm `dumpsys connectivity` shows no WAYD VPN, ping works, `setprop log.tag S`, kill `scrcpy.exe`. Crash details without logcat: `dumpsys dropbox --print data_app_crash`.

## Interference found by Nat (2026-11-09): Douyin comments / new videos failed while recording

Causes found in the relay and fixed in the next build (not yet re-verified by Nat): (1) `ipstack` force-RSTs any TCP session idle for 60 s, breaking pooled/push connections: now 2 h; (2) per-direction idle timers half-closed the server side of long downloads/streams after 5 min with no upload: idle is now per flow (shared activity clock, 1 h TCP / 5 min UDP / 15 s DNS); (3) UDP flows ended when either direction was idle: same fix; (4) 16 KB TCP send window and 16 KB read buffer raised to 256 KB / 64 KB; (5) outbound connect timeout 10 s -> 6 s. **Still open suspects:** the stack completes the client's handshake before the outbound connect succeeds, so apps cannot fail over quickly from dead addresses (needs a stack change); ICMP/ICMPv6 is dropped; DoT/DoH hides DNS; QUIC behaviour untested. Re-test with Douyin including a 1-2 min idle then refresh.

**Resolved 2026-11-09 (confirmed by Nat on Wi-Fi):** the remaining cause was IPv6: the VPN advertised `::/0` although the IPv4-only Wi-Fi had no IPv6, so dual-stack apps picked IPv6, our stack accepted, the relay failed and they never fell back. The VPN now offers IPv6 only if the underlying network has an IPv6 default route (decided at start; not re-evaluated on network switch). Douyin works with the idle/window fixes plus this.

## Known testing problems

- Wireless adb drops (`device offline`, scrcpy `Device disconnected`) whenever the WAYD VPN comes up, even with the PC excluded via the debug-only `--es exclude <cidr>` extra on `MainActivity`; root cause not found. If the scrcpy display dies, apps launched on it can fall back to the main display. Do not drive multi-app tests through scripts; cleanup commands can fail while adb is offline, so always re-verify `log.tag`, VPN state and `pidof` afterwards.
- Nubia AutoLaunch blocks `am start-foreground-service` for the VPN service ("Blocked by AutoLaunch"); start only from the UI.
- scrcpy: after `taskkill scrcpy.exe` the on-device server (`app_process ... scrcpy.Server`) can keep running and leave stale virtual displays that render black; kill them by PID (`adb shell kill <pid>` from `ps -A | grep scrcpy`) and verify `dumpsys SurfaceFlinger --display-id | grep -c scrcpy` is 0 before a new session. Screenshots must use the SurfaceFlinger id of the *live* display.
- Only one VPN can be active: WAYD replaces other VPN/proxy apps (e.g. Clash on Nat's phone) while recording.

## On-device AI (M6) status — implemented, NOT yet tested on the phone (Nat asked not to test live while away)

`:ai` module: `Masker`, versioned `PromptBuilder` (inert `<data>` blocks, delimiter neutralising), `SchemaValidator` (schema v1, evidence-id checks, claim stripping), `AiAnalyzer` (one repair retry, cache in `AiAnalysis`), `ModelManager` (resumable download, SHA-256, free-space check, catalog E2B/E4B with Hugging Face + ModelScope + custom URL), `ModelDownloadService` (dataSync foreground service), `LiteRtEngine` (GPU then CPU). 19 JVM tests incl. prompt-injection fixtures. App: Settings → On-device AI (download/delete/use), App detail → "Analyse with AI" with an "AI opinion" card. Untested: real model loading and inference, download service on the device, UI layout. To test: Settings → On-device AI → Download E2B (about 2.6 GB, Wi-Fi), then open an app with findings → Analyse with AI; check latency (target < 30 s for 10 items), JSON validity rate, memory, and that cancelling works.

Not built yet: tools / "ask about my data" chat, overnight summary (WorkManager), selection-based "Analyse selection" from the connection list, injection tests against the real model.

## Next step

1. Test the AI path on the phone (above). 2. Message inspector (M4) and identifier matcher on decrypted data. 3. M7 blocking (docs/13-BLOCKING.md). 4. M1 soak / coverage.

## Open decisions (see DECISIONS.md)

- D-007 LLM runtime.

## Inputs Nat can provide

- AppDualZuku's `ShellClient` / Shizuku UserService files (for M3).
- Test device list; brand seed colour and icon (placeholders in use).
