# Handoff

_Last updated: 2026-11-09 — M0 done; M1 capture MVP first slice working on NX733J._

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

## M1 status (capture MVP)

Working on device: VpnService + Rust `ipstack` relay (arm64), UID attribution, TLS SNI / HTTP Host / DNS enrichment, batched Room writes, foreground notification, Record tab with live counters and flow list. Verified: 19 flows / 5 apps attributed correctly in a ~1 min run; tunnel teardown restores connectivity.

Fixed during testing: missing `ACCESS_NETWORK_STATE` crashed the poll thread (process died, VPN lingered until force-stop). Tracker now survives enrichment failures.

**Measured (cumulative, NX733J, 2026-11-09):** 512 flows, 99% with package (target >=99% OK); hostname coverage 84% over 103 public TCP flows that carried data (target >=90%, not yet met). The Record tab shows this live; LAN probes and zero-byte flows are excluded from the hostname metric because they cannot have a hostname. Relay now captures the client's first bytes while the outbound connect is in flight (SNI of slow/failed connections).

**Not yet met (M1 acceptance):** 1 h soak with browse/video/WeChat; >=99% flows with package; >=90% TCP flows with host (many flows are still "no hostname": IP-only/QUIC/cached DNS); external-router IPv4/IPv6 completeness check. **Known gaps:** ICMP is dropped (ping fails inside the tunnel), DoH/DoT bypasses DNS visibility, QUIC SNI not parsed, arm64 only, `appForeground` is always false until M3, no pkt counters, `nativePoll` allocates a 64 KB scratch buffer per call (optimise).

## Testing a VPN build without disturbing the phone

Consent dialog persists after the first accept (Nat accepts it once on the main display). Then drive the app on a scrcpy virtual display. scrcpy may drop (`Device disconnected`) the moment the VPN comes up; just start a new scrcpy session afterwards. Always finish with: stop recording, `am force-stop com.nathanhanapps.wayd`, confirm `dumpsys connectivity` shows no WAYD VPN, ping works, `setprop log.tag S`, kill `scrcpy.exe`. Crash details without logcat: `dumpsys dropbox --print data_app_crash`.

## Known testing problems

- Wireless adb drops (`device offline`, scrcpy `Device disconnected`) whenever the WAYD VPN comes up, even with the PC excluded via the debug-only `--es exclude <cidr>` extra on `MainActivity`; root cause not found. If the scrcpy display dies, apps launched on it can fall back to the main display. Do not drive multi-app tests through scripts; cleanup commands can fail while adb is offline, so always re-verify `log.tag`, VPN state and `pidof` afterwards.
- Nubia AutoLaunch blocks `am start-foreground-service` for the VPN service ("Blocked by AutoLaunch"); start only from the UI.
- Only one VPN can be active: WAYD replaces other VPN/proxy apps (e.g. Clash on Nat's phone) while recording.

## Next step

Finish M1 acceptance (soak test, host coverage, IPv6 check), then **M2 · Enrichment + daily report**.

## Open decisions (see DECISIONS.md)

- D-007 LLM runtime.

## Inputs Nat can provide

- AppDualZuku's `ShellClient` / Shizuku UserService files (for M3).
- Test device list; brand seed colour and icon (placeholders in use).
