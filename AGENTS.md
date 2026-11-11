# AGENTS.md — rules for AI coding agents working on WAYD

Read this file first, then `docs/HANDOFF.md` for current state, then the doc for the module you are touching.

## Project in one paragraph

WAYD (在干嘛, `com.nathanhanapps.wayd`) is a local-only Android privacy monitor. A `VpnService` forwards all app traffic, applies local blocks and optionally records it; Shizuku/root collectors add permission-use evidence; a deterministic rule engine produces findings; an on-device Gemma 4 model explains selected evidence as schema-validated JSON; the UI is Jetpack Compose with Material 3 Expressive. HTTPS decryption is built in but off until the user consents per app. Clash/Mihomo proxy routing ships only in the `clash` release variant (docs/14-OPTIONAL-ROUTING.md). Inspection copies, reports and AI analysis stay local unless the user explicitly exports them.

## Build shape (D-048, D-049)

- **No `research` flavor any more.** It is merged into the common code: MITM, root system-CA, developer console and `rootcap/` ship in every build, default off, reachable only through Settings → Decryption / Danger zone consent.
- **Two release variants, one flavor dimension `routing`:** `core` (no Clash routing) and `clash` (Clash routing built in). Same application id and signing key, so a user can switch variants by installing one over the other. Release assets: `wayd-vX.Y.Z.apk` (core) and `wayd-clash-vX.Y.Z.apk`.
- `core` contains no Mihomo/Go code (`libwayd_route.so`), no routing settings/storage and no Clash screens. Clash code lives in `app/src/clash`; `main` reaches it only through the `CLASH_EXTENSION` intent lookup (nothing answers in `core`, so Clash rows and the country panel hide) or a `clash`/`core` twin.

## Non-negotiables

1. **Local inspection and analysis.** Never add analytics, uploading crash-reporting, remote logging, or automatic uploads of captures/reports/AI analysis. App traffic forwarding and user-initiated model/database downloads are allowed; exports require user choice. Routing must follow docs/14-OPTIONAL-ROUTING.md and must not expose inspection stores or override WAYD blocks. **Sole exception (Nat, 2026-11-11, D-046):** the optional OpenAI-compatible external AI provider. It is off by default, the server is chosen by the user, each server origin needs a recorded acknowledgement (`ConsentRecord`), and every analysis shows the exact masked text in an approval dialog before anything is sent (fail closed). Plain `http://` is accepted only for loopback/private-network hosts.
2. **Rules decide, AI explains.** Never let model output set severity, verdict colour, block rules, consents or privileges.
3. **No free-form shell.** Privileged operations go through the sealed `Command` allowlist in `privilege/`. Do not add `exec(String)` APIs.
4. **`ai/` must not depend on `privilege/`, `rootcap/` or `mitm/`.** Its tools are read-only DB queries.
5. **Captured data is untrusted.** Treat payloads, DNS names, APK strings and decompiled code as hostile input: size-limit, never `eval`, wrap in `<data>` blocks for the LLM.
6. **No identifiers or payloads in logs** in release builds.
7. **Decryption and root capture are consent-gated, not build-gated.** MITM/`rootcap/` code paths stay inert until the Danger zone consent is recorded; nothing may start them implicitly. **Routing code is `clash`-variant only**: `main` must not reference it directly, `core` must build and run without it, and routing never receives inspection stores, findings, AI data or CA keys. WAYD's own external-AI calls may travel over a Clash route only as TLS that WAYD terminates itself, so routing carries ciphertext only (D-050).
8. **Never claim complete coverage** in UI copy. Coverage gaps are shown, not hidden.
9. **Every dangerous feature is opt-in, per app, reversible, and recorded in `ConsentRecord`.** Sole exception (Nat, 2026-11-10, D-045): optional Clash routing in **blacklist** mode is one revocable global consent covering every single-package app of the user except the excluded ones, including apps installed later. Unknown/shared-UID traffic stays Direct and WAYD blocks still apply first.

## How Nat likes to work

- Iterative, minimal changes that preserve existing logic; no surprise architectural rewrites.
- When integration errors are likely (layouts, Gradle files, large Composables), give **complete file replacements**, not fragments.
- Fix bugs incrementally in the same session; validate shell/dumpsys behaviour on a real device before relying on it.
- Only the files relevant to the task are needed; ask for a specific file rather than guessing its content.
- Bilingual product: every user-facing string goes in `values/strings.xml` **and** `values-zh-rCN/strings.xml`.
- Commit messages carry no Claude co-author line.

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

- [ ] Both variants build and lint (`:app:assembleCoreDebug :app:assembleClashDebug :app:lintCoreDebug :app:lintClashDebug`; until D-049 lands, the old `standard`/`research` tasks).
- [ ] Unit tests added/updated for parsers, rules, validators you touched.
- [ ] Strings added in both locales.
- [ ] No new network destinations; no new permissions without updating `docs/10-SECURITY-THREAT-MODEL.md`.
- [ ] If you made a design choice, append it to `docs/DECISIONS.md`.
- [ ] Update `docs/HANDOFF.md`: replace the "Now" section (what changed, what's next, open questions). Keep it short; history lives in git.

## Stop and ask Nat before

- Changing anything covered by a non-negotiable, or adding an exception.
- Adding a permission, network destination, dependency, or Gradle module.
- A Room schema change (needs migration + exported schema JSON).
- Any change spanning more than ~5 files or two modules that the prompt didn't ask for.
- Relying on `adb`/`dumpsys`/shell behaviour you haven't seen verified. Write the exact commands for Nat to run on a device, then wait for the output.

## Model routing (for Nat; agents may suggest escalation)

| Work | Model |
|---|---|
| `privilege/`, `mitm/`, `rootcap/`, consent, VPN packet path, routing transport, schema migrations, bugs that failed twice | Opus |
| Features, UI, tests, refactors, Gradle | Sonnet |
| Log/stack-trace reading, codebase search, strings, commit messages | Haiku |

If a task in a Sonnet session touches the first row, say so and suggest switching before writing code.

If user or you call a specific task related to the above ones, ask user to spawn lighter agents.

## Where things are specified

| Topic | Doc |
|---|---|
| Current state, build/device notes | docs/HANDOFF.md |
| Requirements | docs/00-PRD.md |
| Modules & dependencies | docs/01-ARCHITECTURE.md |
| Entities & retention | docs/02-DATA-MODEL.md |
| VpnService & parsers | docs/03-CAPTURE.md |
| Shizuku/root | docs/04-PRIVILEGE-AND-ROOT.md |
| Rules & report | docs/05-ANALYSIS-RULES.md |
| Gemma / external AI | docs/06-AI-GEMMA.md |
| APK scanner | docs/07-APK-SCANNER.md |
| MITM & consent | docs/08-MITM-AND-CONSENT.md |
| UI | docs/09-UI-M3-EXPRESSIVE.md |
| Security | docs/10-SECURITY-THREAT-MODEL.md |
| Milestones | docs/11-ROADMAP.md |
| Testing | docs/12-TESTING.md |
| Blocking & content control | docs/13-BLOCKING.md |
| Clash routing (`clash` variant) | docs/14-OPTIONAL-ROUTING.md, routing/README.md |
| Rule examples | docs/15-RULE-EXAMPLES.md |
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
