# Decisions log

Format: ID · date · status (proposed / accepted / superseded) · decision · why · alternatives.
Add new entries at the bottom. Never delete; supersede instead.

---

**D-001 · 2026-11-09 · accepted**
Local-only product. No servers, accounts, analytics or crash upload.
*Why:* trust is the product; simplifies PIPL position. *Alt:* opt-in telemetry (rejected for v1).

**D-002 · 2026-11-09 · accepted (supersedes the Go proposal)**
Netstack: Rust userspace stack (smoltcp-based) via JNI, built with cargo-ndk.
*Why:* Nat wants an efficient one: no Go runtime/GC, smaller APK, lower memory and battery cost. *Alt:* gVisor via gomobile (mature, heavier), lwIP (C, older).
*Consequence:* needs rustup, Android NDK and cargo-ndk installed; native code lives in `capture/native/`.

**D-003 · 2026-11-09 · accepted**
Rules decide severity; Gemma only explains. AI output must cite evidence IDs and pass schema validation.
*Why:* small on-device models hallucinate; captured data is adversarial.

**D-004 · 2026-11-09 · accepted**
UI in Jetpack Compose with Material 3 Expressive from the `material3` 1.5 alpha line, pinned; experimental components wrapped in `core/ui`.
*Why:* Expressive APIs aren't in 1.4 stable. *Alt:* Views + MDC (AppDualZuku style) — rejected: Expressive is Compose-first.

**D-005 · 2026-11-09 · accepted**
Privilege abstraction with sealed `Command` allowlist; reuse AppDualZuku's Shizuku `ShellClient`; libsu fallback for root.

**D-006 · 2026-11-09 · accepted**
Two flavors: `standard` (no MITM/root plaintext) and `research` (all).

**D-007 · 2026-11-09 · proposed**
LLM runtime: LiteRT-LM with Gemma 4 E2B default, E4B on ≥ 12 GB RAM; llama.cpp fallback. Model hosted on own mirror/ModelScope.

**D-008 · 2026-11-09 · accepted (M0)**
DI: Hilt. *Alt:* Koin (simpler, runtime).

**D-009 · 2026-11-09 · accepted**
WAYD source is **GPL-3.0-or-later**; reusing GPL code from PCAPdroid / NetGuard is allowed. Keep upstream copyright headers and note provenance in the file header of any reused code.

**D-010 · 2026-11-09 · accepted**
Toolchain pins for M0: AGP 9.5.1 (built-in Kotlin, no `kotlin-android` plugin), Kotlin 2.4.21, KSP 2.3.13, Hilt 2.60.1, Room 2.8.5, SQLCipher 4.20.0, Compose BOM 2026.09.00, Gradle 9.8.1. `compileSdk` 37 (required by current AndroidX), `targetSdk` 36, `minSdk` 29.
*Why:* latest stable at 2026-11-09; minSdk 29 for `getConnectionOwnerUid`.

**D-011 · 2026-11-09 · accepted**
`material3` pinned to **1.5.0-rc01** (no longer alpha), overriding the Compose BOM. Supersedes the "alpha" wording of D-004; wrapping experimental APIs in `core/ui` still applies.

**D-012 · 2026-11-09 · accepted**
DB key: random 32-byte SQLCipher passphrase, wrapped by an AES-GCM Android Keystore key, stored in `noBackupFilesDir/db.key`.

**D-013 · 2026-11-09 · accepted**
Outbound relay sockets bypass the VPN by excluding WAYD's own package (`addDisallowedApplication`) instead of calling `VpnService.protect()` per socket.
*Why:* no JNI callback per socket, simpler and faster. *Cost:* WAYD's own traffic is not recorded (it has none).

**D-014 · 2026-11-09 · accepted**
Native stack = `ipstack` 1.0.1 (tokio, 2 worker threads) + custom relay in `capture/native`; JNI surface is `nativeStart` / `nativeStop` / `nativePoll` with a binary event queue (format in `events.rs`). Built by Gradle via cargo-ndk (arm64-v8a only for now; `-PskipNative` reuses a prebuilt `.so`). Host toolchain on Windows: Rust GNU target + portable MinGW (winget `BrechtSanders.WinLibs.POSIX.UCRT`), NDK r29.

**D-015 · 2026-11-09 · accepted**
Analysis is app-oriented (Nat's request): the Apps tab and app detail are the primary surfaces ("what is this app doing"); the daily report links into them. Findings are stored per app and window (`createdAt` = window start) and replaced on each re-run.

**D-016 · 2026-11-09 · accepted**
Enrichment seed = hand-curated `SeedDomains` (owner/purpose of well-known domains, ~350 entries) in module `enrich`, with suffix matching. It is a seed only; swap in a licence-checked dataset (e.g. DuckDuckGo Tracker Radar) before claiming coverage. Unknown stays unknown. Offline GeoIP and the identifier matcher are deferred (no licensed data / no message payloads yet).

**D-017 · 2026-11-09 · accepted**
Two rules beyond the original table: R-TRACKER-CONTACT and R-CLEARTEXT-HTTP (docs/05). Reason: the original rules need inputs that arrive in M3-M5, and M2 needed 8 rules computable from flows alone.

**D-018 · 2026-11-09 · proposed**
Permission-use history per app (Nat's request) = M3 AppOps collector. Feasibility on NX733J (Android 16): `dumpsys appops --history --package <pkg>` works from adb shell and returns per-op access counts and durations with flags; discrete timestamps still to be verified. In-app access needs Shizuku or root (GET_HISTORICAL_APP_OPS_STATS is privileged). Visualisation goes into the app detail as a "Permission use" lane (placeholder card exists).

**D-018 · 2026-11-09 · accepted (supersedes proposed)**
Permission-use history per app = Shizuku UserService (`privilege` module) exposing fixed methods only (`appOpsHistory(pkg)`, `appOpsGet(pkg)`; package name validated by regex, run as argv without a shell). Data = `dumpsys appops --history --package`: time buckets (about 15 min for recent history, coarser further back) with per-op, per-process-state counters (`top`, `fgsvc`, `fg`, `bg`, `cch`, `pers`). It is bucket-resolution, not exact timestamps. Shizuku is opt-in; the user grants WAYD in Shizuku's own dialog.

**D-019 · 2026-11-09 · accepted**
Privileged output must be reduced *inside* the privileged process. `dumpsys appops --history` prints every app in every bucket (about 7 MB), which exceeds the Binder transaction limit (about 1 MB) and the call fails. `HistoryFilter` keeps only bucket headers and the target package; replies are capped at 600 KB. A read failure is its own UI state (`Failed`), never reported as "Shizuku not running".
