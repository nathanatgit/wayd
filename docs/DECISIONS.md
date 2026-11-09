# Decisions log

Format: ID · date · status (proposed / accepted / superseded) · decision · why · alternatives.
Add new entries at the bottom. Never delete; supersede instead.

---

**D-001 · 2026-11-09 · accepted**
Local-only product. No servers, accounts, analytics or crash upload.
*Why:* trust is the product; simplifies PIPL position. *Alt:* opt-in telemetry (rejected for v1).

**D-002 · 2026-11-09 · proposed**
Netstack: gVisor netstack via tun2socks (Go + gomobile) for M1.
*Why:* mature, widely used on Android, handles TCP/UDP/IPv6. *Alt:* Rust smoltcp via JNI (smaller, more work), lwIP (C, older).
*To confirm:* license compatibility and APK size impact.

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

**D-009 · 2026-11-09 · proposed**
License for WAYD source: decide before M1 (affects whether GPL code from PCAPdroid/NetGuard can be reused).

**D-010 · 2026-11-09 · accepted**
Toolchain pins for M0: AGP 9.5.1 (built-in Kotlin, no `kotlin-android` plugin), Kotlin 2.4.21, KSP 2.3.13, Hilt 2.60.1, Room 2.8.5, SQLCipher 4.20.0, Compose BOM 2026.09.00, Gradle 9.8.1. `compileSdk` 37 (required by current AndroidX), `targetSdk` 36, `minSdk` 29.
*Why:* latest stable at 2026-11-09; minSdk 29 for `getConnectionOwnerUid`.

**D-011 · 2026-11-09 · accepted**
`material3` pinned to **1.5.0-rc01** (no longer alpha), overriding the Compose BOM. Supersedes the "alpha" wording of D-004; wrapping experimental APIs in `core/ui` still applies.

**D-012 · 2026-11-09 · accepted**
DB key: random 32-byte SQLCipher passphrase, wrapped by an AES-GCM Android Keystore key, stored in `noBackupFilesDir/db.key`.
