# AGENTS.md — rules for AI coding agents working on WAYD

Read this file first, then [docs/HANDOFF.md](docs/HANDOFF.md) for the current state, then the doc for the module you touch.

## Project

WAYD (在干嘛, `com.nathanhanapps.wayd`) is a local-only Android privacy monitor. A `VpnService` forwards all app traffic, applies local blocks and optionally records it; Shizuku/root collectors add permission-use evidence; a deterministic rule engine produces findings; an on-device Gemma 4 model (or a server the user approves) explains selected evidence as schema-validated JSON. UI is Jetpack Compose with Material 3 Expressive. HTTPS decryption is built in but off until the user consents per app. Inspection copies, reports and AI analysis stay on the phone unless the user exports them.

## Build shape (D-048, D-049)

- **One flavor dimension, `routing`, two variants:** `core` (no Clash routing) and `clash` (Clash routing built in). Same application id and signing key; installing one over the other switches variants. Release assets: `wayd-vX.Y.Z.apk` (core), `wayd-clash-vX.Y.Z.apk`.
- **No `research` flavor.** MITM, root system-CA, developer console and `rootcap/` ship in every build, default off, reachable only through Settings → Decryption / Danger zone consent.
- `core` contains no Mihomo/Go code (`libwayd_route.so`), routing settings or Clash screens. Clash code lives in `app/src/clash`; `main` reaches it only through the `CLASH_EXTENSION` intent lookup or a `clash`/`core` twin.

## Non-negotiables

1. **Local inspection and analysis.** No analytics, uploading crash reports, remote logging, or automatic upload of captures, reports or AI analysis. Exports require the user's choice.
2. **Rules decide, AI explains.** Model output never sets severity, attention, verdict colour, block rules, consents or privileges.
3. **No free-form shell.** Privileged operations go through the sealed `Command` allowlist in `privilege/`. No `exec(String)` APIs.
4. **`ai/` must not depend on `privilege/`, `rootcap/` or `mitm/`.** Its tools are read-only DB queries.
5. **Captured data is untrusted.** Payloads, DNS names, APK strings, decompiled code and user attachments are hostile input: size-limit, never `eval`, wrap in `<data>` blocks for the model.
6. **No identifiers or payloads in logs** in release builds.
7. **Decryption and root capture are consent-gated, not build-gated.** MITM and `rootcap/` stay inert until the Danger zone consent is recorded; nothing starts them implicitly. **Routing code is `clash`-variant only:** `main` must not reference it directly, `core` must build and run without it, and routing never receives inspection stores, findings, AI data or CA keys.
8. **Never claim complete coverage** in UI copy. Coverage gaps are shown, not hidden.
9. **Every dangerous feature is opt-in, per app, reversible, and recorded in `ConsentRecord`.**

### Approved exceptions (each decided by Nat; add none without asking)

| Rule | Exception | Conditions |
|---|---|---|
| 1 | User-initiated downloads: models, GeoIP/routing resources, and a block list from an HTTPS address the user typed (D-064) | Only on the user's action (Download / Import / Update). No schedule, no bundled list. |
| 1 | Optional external AI provider, OpenAI-compatible (D-046, D-055) | Off by default; the user chooses the server; each server origin needs a recorded acknowledgement. Every user-started request shows the exact masked text for approval and fails closed, unless the user checked "Don't ask again" and pressed Send for that exact address and model (separate revocable `ConsentRecord`, resettable in Settings → Re-enable all warnings). Changing address or model, or revoking consent, restores the preview. Requests with attachments always show it. Plain `http://` only for loopback or private-network hosts. |
| 7 | WAYD's own external-AI calls over a Clash route (D-050) | Only as TLS that WAYD terminates itself, so routing carries ciphertext. |
| 9 | Clash routing in blacklist mode (D-045) | One revocable global consent for every single-package app of the user except the excluded ones, including later installs. Unknown/shared-UID traffic stays Direct; WAYD blocks apply first. |
| 9 | Imported block lists (D-064) | One revocable global consent per list for every app WAYD covers, including later installs. Per-app rules still win; connections whose hostname cannot be read still pass. |
| 1 | Clash profile refreshes and health checks (D-075, `clash` variant) | Only while Clash routing is enabled, on the intervals the profile sets: subscription, rule-provider and proxy-provider updates and url-test / fallback / load-balance health checks, to hosts named in the profile only. Listed on the profile page; all stop when routing is off. No capture, report or AI data is sent. |

App traffic forwarding itself is the product and is not an exception. Routing must follow [docs/14-OPTIONAL-ROUTING.md](docs/14-OPTIONAL-ROUTING.md) and must not expose inspection stores or override WAYD blocks.

## How Nat works

- Iterative, minimal changes that preserve existing logic; no surprise architectural rewrites.
- Where integration errors are likely (layouts, Gradle files, large Composables), give complete file replacements.
- Fix bugs incrementally in the same session. Validate shell/`dumpsys` behaviour on a real device before relying on it.
- Only the files relevant to the task are needed; ask for a specific file rather than guessing its content.
- Bilingual product: every user-facing string goes in `values/strings.xml` **and** `values-zh-rCN/strings.xml`.
- Commit messages carry no Claude co-author line.
- UI checks on the phone: ask Nat to tap through them.

## Tech conventions

- Kotlin (latest stable), coroutines + Flow; no RxJava.
- Compose only (no XML layouts). Material 3 Expressive via `MaterialExpressiveTheme`; experimental APIs only inside `core/ui` wrappers with `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- Version catalog (`gradle/libs.versions.toml`); pin exact versions; note alpha dependencies in `docs/DECISIONS.md`.
- Room + SQLCipher; every schema change ships a migration and an exported schema JSON.
- DI: Hilt (D-008). Package layout: `com.nathanhanapps.wayd.<module>.<feature>`.
- Tests: JUnit 4 (`org.junit.Test`) for pure logic, as the existing suites use; fixtures under `src/test/resources`. Formatting: ktlint / Spotless default rules.

## Local build (Windows) — follow in order

Run from `C:\Users\Nathan\GitRepo\wayd`. Do not change dependency versions or repositories to work around a local failure. Newer machine notes: handoff.

**1. Set JDK and SDK in the same shell call as Gradle** (environment does not persist between tool calls). The working JDK path contains **Android Studio1**; `Android Studio/jbr` is incomplete. Never create `local.properties`.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
$env:ANDROID_HOME = 'C:\Users\Nathan\AppData\Local\Android\Sdk'
```

Required: Android platform 37, NDK `29.0.14206865`, arm64-v8a. Do not swap the JBR for another JDK.

**2. Decide whether native code must be rebuilt.** `-PskipNative` skips Rust **and** Go. Use it only for Kotlin/UI/resource changes, and only if both `capture\build\native\mitm\arm64-v8a\libwayd_net.so` and `routing\build\arm64-v8a\libwayd_route.so` exist. If Rust or Go sources changed, or a library is missing, omit it. Do not run `clean` first.

**3. Build and lint both variants**, keep the log under ignored `build/`, and check the exit code:

```powershell
& .\gradlew.bat :app:assembleCoreDebug :app:assembleClashDebug :app:lintCoreDebug :app:lintClashDebug -PskipNative *> build\local-validation.log
if ($LASTEXITCODE -ne 0) { throw "Gradle failed; inspect build/local-validation.log" }
```

Add the affected module's test task for logic changes (for example `:analysis:test`), or `test` for all. APKs: `app/build/outputs/apk/{core,clash}/debug/`. The daily build uses `'-PappIdSuffix=.d049'` (quote it in PowerShell). Compilation does not authorise installation: follow the handoff's device rules and check `output-metadata.json` first.

**4. Recover environment failures before calling the code broken.**

| Symptom | Do |
|---|---|
| Tool runner cannot start PowerShell 7 | Use `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe` (login `false`), or Git Bash with `export`. Do not reinstall PowerShell. |
| Wrapper redownloads an installed Gradle | Find the exact version under `%USERPROFILE%\.gradle\wrapper\dists\` and invoke its `gradle.bat` with the same environment and tasks. Never a different version. |
| `Permission denied: getsockopt`, cache access denied | Likely a sandbox restriction: retry through the tool's normal escalation. Do not conclude caches are missing. |
| `--offline` reports "plugin not found" | The cache is incomplete, not the plugin invalid. Retry online; do not downgrade AGP/Kotlin/Gradle or add mirrors. |
| Direct downloads fail | Check the proxy `127.0.0.1:7897` is listening, then pass `-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7897 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7897` to that Gradle call only. |
| Still blocked | Report the exact command, the first relevant failure and which checks never ran. Never claim success from old APKs, old logs or skipped tasks. |

**5. Native rebuilds** use the same Gradle command without `-PskipNative`; `capture/build.gradle.kts` wires cargo-ndk, NDK clang and Go. Do not invent linker commands.

- Rust: `%USERPROFILE%\.cargo\bin\cargo.exe`, GNU host toolchain, `aarch64-linux-android` target, cargo-ndk, WinLibs MinGW (Gradle adds it to PATH; a manual `cargo test` needs both on PATH).
- Go: pinned portable toolchain at `build/tools/go/bin/go.exe` (or `-PgoExecutable=`). `core` does not need the routing library.
- Never mix WSL and Windows toolchains or caches. Do not install or replace global toolchains; use existing tools or project-local downloads under `build/tools/`.

## Before you finish a task

- [ ] Both variants build and lint (commands above). No obsolete `standard`/`research` tasks.
- [ ] Unit tests added or updated for parsers, rules and validators you touched.
- [ ] Strings added in both locales.
- [ ] No new network destination; no new permission without updating `docs/10-SECURITY-THREAT-MODEL.md`.
- [ ] Design choices appended to `docs/DECISIONS.md`.
- [ ] `docs/HANDOFF.md` "Now" section replaced: what changed, what is unverified, what is next. Keep it short; history lives in git.

## Stop and ask Nat before

- Changing anything covered by a non-negotiable, or adding an exception.
- Adding a permission, network destination, dependency or Gradle module.
- A Room schema change (needs migration and exported schema JSON).
- A change spanning more than about 5 files or two modules that the prompt did not ask for.
- Relying on `adb`/`dumpsys`/shell behaviour you have not seen verified: write the exact commands for Nat to run, then wait for the output.

## Model routing (for Nat; agents may suggest escalation)

| Work | Model |
|---|---|
| `privilege/`, `mitm/`, `rootcap/`, consent, VPN packet path, routing transport, schema migrations, bugs that failed twice | Opus |
| Features, UI, tests, refactors, Gradle | Sonnet |
| Log/stack-trace reading, codebase search, strings, commit messages | Haiku |

If a Sonnet session touches the first row, say so and suggest switching before writing code. For lighter sub-tasks, suggest spawning a lighter agent.

## Where things are specified

| Topic | Doc |
|---|---|
| Current state, build and device notes | docs/HANDOFF.md |
| Requirements | docs/00-PRD.md |
| Modules and dependencies | docs/01-ARCHITECTURE.md |
| Entities and retention | docs/02-DATA-MODEL.md |
| VpnService and parsers | docs/03-CAPTURE.md |
| Shizuku / root | docs/04-PRIVILEGE-AND-ROOT.md |
| Rules and report | docs/05-ANALYSIS-RULES.md |
| Gemma, external AI, labels, chat | docs/06-AI-GEMMA.md |
| APK scanner | docs/07-APK-SCANNER.md |
| MITM and consent | docs/08-MITM-AND-CONSENT.md |
| UI | docs/09-UI-M3-EXPRESSIVE.md |
| Security, permissions | docs/10-SECURITY-THREAT-MODEL.md |
| Milestones | docs/11-ROADMAP.md |
| Testing | docs/12-TESTING.md |
| Per-app blocking | docs/13-BLOCKING.md |
| Clash routing (`clash` variant) | docs/14-OPTIONAL-ROUTING.md, routing/README.md |
| Rule examples | docs/15-RULE-EXAMPLES.md |
| Block lists, rewrite rules | docs/16-RULE-SETS-AND-REWRITE.md |
| Decisions | docs/DECISIONS.md |
| To-do list | docs/TODO.md, docs/todo/ |

## Task prompt template (for Nat to give an agent)

```
Milestone: M<n> — <name>
Goal: <one sentence>
Touch only: <modules/files>
Spec: <doc sections>
Acceptance: <copy from 11-ROADMAP>
Constraints: follow AGENTS.md non-negotiables; full-file output for changed files.
```
