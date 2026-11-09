# Handoff

_Last updated: 2026-11-09 — planning complete, no code yet._

## State

- Product, architecture, data model, capture, privilege/root, rules, AI, APK scanner, MITM/consent, UI, security, roadmap and testing docs written (`docs/`).
- Agent rules in `AGENTS.md` (`CLAUDE.md` points there).
- Repository contains docs only.

## Next step

**M0 · Skeleton** (see `docs/11-ROADMAP.md`):
1. Create the multi-module Gradle project and version catalog.
2. `standard` / `research` flavors.
3. `WaydTheme` (Material 3 Expressive, material3 1.5 alpha pinned), 4-tab navigation, zh/en strings.
4. Room + SQLCipher with the entities in `docs/02-DATA-MODEL.md`.
5. CI: build, unit tests, lint.

## Open decisions (see DECISIONS.md)

- D-002 netstack (gVisor/tun2socks vs Rust).
- D-007 LLM runtime/model hosting.
- D-008 DI (Hilt vs Koin).
- D-009 source license (affects reuse of GPL code from PCAPdroid/NetGuard).

## Inputs Nat can provide

- AppDualZuku's `ShellClient` / Shizuku UserService files (to reuse in `privilege/`).
- Target test devices list (HyperOS, ColorOS, Nubia, Pixel…).
- Brand seed colour and app icon direction.
