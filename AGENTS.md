# AGENTS.md — rules for AI coding agents working on WAYD

Read this file first, then `docs/HANDOFF.md` for current state, then the doc for the module you are touching.

## Project in one paragraph

WAYD (在干嘛, `com.nathanhanapps.wayd`) is a local-only Android privacy monitor. A `VpnService` records all app traffic; Shizuku/root collectors add permission-use evidence; a deterministic rule engine produces findings; an on-device Gemma 4 model explains selected evidence as schema-validated JSON; the UI is Jetpack Compose with Material 3 Expressive. Nothing leaves the phone.

## Non-negotiables

1. **Local only.** Never add analytics, crash-reporting, remote logging, or any network call except user-initiated model/database downloads to configured hosts.
2. **Rules decide, AI explains.** Never let model output set severity, verdict colour, block rules, consents or privileges.
3. **No free-form shell.** Privileged operations go through the sealed `Command` allowlist in `privilege/`. Do not add `exec(String)` APIs.
4. **`ai/` must not depend on `privilege/`, `rootcap/` or `mitm/`.** Its tools are read-only DB queries.
5. **Captured data is untrusted.** Treat payloads, DNS names, APK strings and decompiled code as hostile input: size-limit, never `eval`, wrap in `<data>` blocks for the LLM.
6. **No identifiers or payloads in logs** in release builds.
7. **`mitm/` and `rootcap/` are `research`-flavor only.** Don't reference them from `standard` code paths.
8. **Never claim complete coverage** in UI copy. Coverage gaps are shown, not hidden.
9. **Every dangerous feature is opt-in, per app, reversible, and recorded in `ConsentRecord`.**

## How Nat likes to work

- Iterative, minimal changes that preserve existing logic; no surprise architectural rewrites.
- When integration errors are likely (layouts, Gradle files, large Composables), give **complete file replacements**, not fragments.
- Fix bugs incrementally in the same session; validate shell/dumpsys behaviour on a real device before relying on it.
- Only the files relevant to the task are needed; ask for a specific file rather than guessing its content.
- Bilingual product: every user-facing string goes in `values/strings.xml` **and** `values-zh-rCN/strings.xml`.

## Tech conventions

- Kotlin (latest stable), coroutines + Flow; no RxJava.
- Compose only (no XML layouts except manifest/resources). Material 3 Expressive via `MaterialExpressiveTheme`; experimental APIs only inside `core/ui` wrappers with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- Version catalog (`gradle/libs.versions.toml`); pin exact versions; note any alpha dependency in `docs/DECISIONS.md`.
- Room + SQLCipher; every schema change ships a migration and an exported schema JSON.
- DI per D-008 (Hilt unless changed).
- Package layout: `com.nathanhanapps.wayd.<module>.<feature>`.
- Tests: JUnit5/Kotest for pure logic; fixtures under `src/test/resources`.
- Formatting: ktlint / Spotless default rules.

## Before you finish a task

- [ ] Builds for both `standard` and `research` flavors.
- [ ] Unit tests added/updated for parsers, rules, validators you touched.
- [ ] Strings added in both locales.
- [ ] No new network destinations; no new permissions without updating `docs/10-SECURITY-THREAT-MODEL.md`.
- [ ] If you made a design choice, append it to `docs/DECISIONS.md`.
- [ ] Update `docs/HANDOFF.md`: what changed, what's next, open questions.

## Where things are specified

| Topic | Doc |
|---|---|
| Requirements | docs/00-PRD.md |
| Modules & dependencies | docs/01-ARCHITECTURE.md |
| Entities & retention | docs/02-DATA-MODEL.md |
| VpnService & parsers | docs/03-CAPTURE.md |
| Shizuku/root | docs/04-PRIVILEGE-AND-ROOT.md |
| Rules & report | docs/05-ANALYSIS-RULES.md |
| Gemma | docs/06-AI-GEMMA.md |
| APK scanner | docs/07-APK-SCANNER.md |
| MITM & consent | docs/08-MITM-AND-CONSENT.md |
| UI | docs/09-UI-M3-EXPRESSIVE.md |
| Security | docs/10-SECURITY-THREAT-MODEL.md |
| Milestones | docs/11-ROADMAP.md |
| Testing | docs/12-TESTING.md |
| Decisions | docs/DECISIONS.md |

## Task prompt template (for Nat to give an agent)

```
Milestone: M<n> — <name>
Goal: <one sentence>
Touch only: <modules/files>
Spec: <doc sections>
Acceptance: <copy from 11-ROADMAP>
Constraints: follow AGENTS.md non-negotiables; full-file output for changed files.
```
