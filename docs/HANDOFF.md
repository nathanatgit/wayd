# Handoff

_Last updated: 2026-11-09 — M0 skeleton built and verified; v0.0.1._

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

## Next step

**M1 · Capture MVP** (see `docs/11-ROADMAP.md`). Blockers to settle first:
- D-002 netstack (Go/gomobile toolchain is not installed here) and D-009 license.
- Testing a VPN cannot be done on a virtual display: `VpnService.prepare()` shows a system dialog and reroutes the phone's traffic. Needs an emulator/spare device or an agreed test window.

## Open decisions (see DECISIONS.md)

- D-002 netstack, D-007 LLM runtime, D-009 source license.

## Inputs Nat can provide

- AppDualZuku's `ShellClient` / Shizuku UserService files (for M3).
- Test device list; brand seed colour and icon (placeholders in use).
