# AGENTS.md — rules for AI coding agents working on WAYD

Read this file first, then `docs/HANDOFF.md` for current state, then the doc for the module you are touching.

## Project in one paragraph

WAYD (在干嘛, `com.nathanhanapps.wayd`) is a local-only Android privacy monitor. A `VpnService` forwards all app traffic, applies local blocks and optionally records it; Shizuku/root collectors add permission-use evidence; a deterministic rule engine produces findings; an on-device Gemma 4 model explains selected evidence as schema-validated JSON; the UI is Jetpack Compose with Material 3 Expressive. HTTPS decryption is built in but off until the user consents per app. Clash/Mihomo proxy routing ships only in the `clash` release variant (docs/14-OPTIONAL-ROUTING.md). Inspection copies, reports and AI analysis stay local unless the user explicitly exports them.

## Build shape (D-048, D-049)

- **No `research` flavor any more.** It is merged into the common code: MITM, root system-CA, developer console and `rootcap/` ship in every build, default off, reachable only through Settings → Decryption / Danger zone consent.
- **Two release variants, one flavor dimension `routing`:** `core` (no Clash routing) and `clash` (Clash routing built in). Same application id and signing key, so a user can switch variants by installing one over the other. Release assets: `wayd-vX.Y.Z.apk` (core) and `wayd-clash-vX.Y.Z.apk`.
- `core` contains no Mihomo/Go code (`libwayd_route.so`), no routing settings/storage and no Clash screens. Clash code lives in `app/src/clash`; `main` reaches it only through the `CLASH_EXTENSION` intent lookup (nothing answers in `core`, so Clash rows and the country panel hide) or a `clash`/`core` twin.

## Non-negotiables

1. **Local inspection and analysis.** Never add analytics, uploading crash-reporting, remote logging, or automatic uploads of captures/reports/AI analysis. App traffic forwarding and user-initiated model/database downloads are allowed, as is a block list fetched over HTTPS from an address the user typed, only when they press Import or Update (D-064); exports require user choice. Routing must follow docs/14-OPTIONAL-ROUTING.md and must not expose inspection stores or override WAYD blocks. **Sole exception (Nat, 2026-11-11, D-046, amended D-055):** the optional OpenAI-compatible external AI provider. It is off by default, the server is chosen by the user, and each server origin needs a recorded acknowledgement (`ConsentRecord`). Each user-started analysis offers the exact masked text in an approval dialog before sending (fail closed), unless the user explicitly checked Don't ask again and pressed Send for that exact server address and model; this is separately recorded in a revocable `ConsentRecord`, includes selected decrypted content, and can be reset in Settings → Re-enable all warnings. Changing the address/model or revoking server consent restores the preview. Plain `http://` is accepted only for loopback/private-network hosts.
2. **Rules decide, AI explains.** Never let model output set severity, verdict colour, block rules, consents or privileges.
3. **No free-form shell.** Privileged operations go through the sealed `Command` allowlist in `privilege/`. Do not add `exec(String)` APIs.
4. **`ai/` must not depend on `privilege/`, `rootcap/` or `mitm/`.** Its tools are read-only DB queries.
5. **Captured data is untrusted.** Treat payloads, DNS names, APK strings and decompiled code as hostile input: size-limit, never `eval`, wrap in `<data>` blocks for the LLM.
6. **No identifiers or payloads in logs** in release builds.
7. **Decryption and root capture are consent-gated, not build-gated.** MITM/`rootcap/` code paths stay inert until the Danger zone consent is recorded; nothing may start them implicitly. **Routing code is `clash`-variant only**: `main` must not reference it directly, `core` must build and run without it, and routing never receives inspection stores, findings, AI data or CA keys. WAYD's own external-AI calls may travel over a Clash route only as TLS that WAYD terminates itself, so routing carries ciphertext only (D-050).
8. **Never claim complete coverage** in UI copy. Coverage gaps are shown, not hidden.
9. **Every dangerous feature is opt-in, per app, reversible, and recorded in `ConsentRecord`.** Second exception (Nat, 2026-11-14, D-064): an imported block list is one revocable global consent per list, covering every app WAYD covers, including apps installed later; per-app rules still win and unnamed connections still pass. Sole exception (Nat, 2026-11-10, D-045): optional Clash routing in **blacklist** mode is one revocable global consent covering every single-package app of the user except the excluded ones, including apps installed later. Unknown/shared-UID traffic stays Direct and WAYD blocks still apply first.

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

## Local compile toolchain — follow in order (Windows / PowerShell)

Use this checklist literally before reporting that a build is blocked. Read `docs/HANDOFF.md` for newer machine notes. Run from `C:\Users\Nathan\GitRepo\wayd`. Do not change dependency versions or repositories to work around a local environment failure.

1. **Select the installed JDK and SDK in the same shell invocation as Gradle.** Environment changes in one tool call may not persist into the next. The working JDK path contains **Android Studio1**; `Android Studio/jbr` is incomplete on this machine. Do not create `local.properties` (its Windows escaping has caused lint failures).

   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
   $env:ANDROID_HOME = 'C:\Users\Nathan\AppData\Local\Android\Sdk'
   & "$env:JAVA_HOME\bin\java.exe" -version
   Test-Path "$env:ANDROID_HOME\platforms\android-37\android.jar"
   Test-Path "$env:ANDROID_HOME\ndk\29.0.14206865"
   ```

   Required: Android platform 37, NDK `29.0.14206865`, arm64-v8a. Java source compatibility 17 in the project does not mean replacing the working JBR with an arbitrary JDK 17.

2. **Choose whether native libraries must be rebuilt.** For Kotlin/UI/resource-only changes, `-PskipNative` is allowed only when both commands below return `True`:

   ```powershell
   Test-Path 'capture\build\native\mitm\arm64-v8a\libwayd_net.so'
   Test-Path 'routing\build\arm64-v8a\libwayd_route.so'
   ```

   `-PskipNative` skips Rust **and** Go; it does not generate missing libraries or validate changed native code. If either file is missing, or Rust/Go sources changed, omit it and follow step 5. Do not run `clean` before reusing these outputs.

3. **Build and lint both variants.** Repeat the environment assignments from step 1 in the build invocation. Keep the full output in an ignored file under `build/`, and inspect the exit code and final result:

   ```powershell
   $env:JAVA_HOME = 'C:\Program Files\Android\Android Studio1\jbr'
   $env:ANDROID_HOME = 'C:\Users\Nathan\AppData\Local\Android\Sdk'
   New-Item -ItemType Directory -Force build | Out-Null
   & .\gradlew.bat :app:assembleCoreDebug :app:assembleClashDebug :app:lintCoreDebug :app:lintClashDebug -PskipNative *> build\local-validation.log
   $buildExit = $LASTEXITCODE
   Get-Content build\local-validation.log -Tail 80
   if ($buildExit -ne 0) { throw "Gradle failed with exit code $buildExit; inspect build/local-validation.log" }
   ```

   Add the affected module's unit-test task for logic changes (for example `:analysis:test`). APKs are under `app/build/outputs/apk/core/debug/` and `app/build/outputs/apk/clash/debug/`. Compilation does not authorize device installation; follow the device instructions in the handoff and inspect `output-metadata.json` before any install. The daily build uses `'-PappIdSuffix=.d049'` (quote it in PowerShell); do not silently replace another installed package.

4. **Recover environment failures before calling the code broken.**

   - If the tool runner cannot spawn `C:\Program Files\PowerShell\7\pwsh.exe`, set the tool call's `shell` to `C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe` and `login` to `false`. This built-in shell was verified on 2026-11-12; do not reinstall PowerShell to run a build.

   - Wrapper tries downloading an already-installed Gradle: inspect `gradle/wrapper/gradle-wrapper.properties`, then look for that exact version locally:

     ```powershell
     Get-ChildItem "$env:USERPROFILE\.gradle\wrapper\dists\gradle-9.8.1-bin" -Recurse -Filter gradle.bat | Select-Object -ExpandProperty FullName
     ```

     Invoke the discovered `gradle.bat` with `&`, the same environment assignments, and the same tasks. Do not hard-code the distribution's hash directory or use a different Gradle version. Update the search version if the wrapper changes.
   - `Permission denied: getsockopt`, cache access denied, or a wrapper redownload despite an existing distribution may be a sandbox restriction. Retry the same build through the tool's normal escalation flow when available. Do not conclude that caches are absent solely from a restricted attempt.
   - `--offline` works only with a complete dependency cache. An offline “plugin not found” result does **not** establish that the pinned plugin is invalid. Retry online with permitted network access; do not downgrade AGP/Kotlin/Gradle or add mirrors.
   - The desktop download proxy recorded in the handoff is `127.0.0.1:7897`. If direct downloads fail, check that the proxy is listening before using it. For Gradle, pass temporary JVM properties rather than editing tracked configuration:

     ```powershell
     Test-NetConnection 127.0.0.1 -Port 7897 -InformationLevel Quiet
     # Append these arguments to the Gradle invocation only when the proxy is available:
     # '-Dhttp.proxyHost=127.0.0.1' '-Dhttp.proxyPort=7897' '-Dhttps.proxyHost=127.0.0.1' '-Dhttps.proxyPort=7897'
     ```

   - If recovery is blocked, report the exact command, first relevant failure, and which checks never ran. Do not claim a successful build from old APKs, previous logs, or skipped tasks.

5. **Native rebuilds use the Windows toolchain for this checkout.** `capture/build.gradle.kts` configures cargo-ndk, NDK clang, portable Go and their output locations. Use the same build command without `-PskipNative`; do not invent manual linker commands.

   - Rust: `%USERPROFILE%\.cargo\bin\cargo.exe`, GNU host toolchain, `aarch64-linux-android` target, cargo-ndk, and WinLibs MinGW for Windows host build scripts. Gradle adds the installed WinLibs location to the native task's PATH. Check with `& "$env:USERPROFILE\.cargo\bin\rustup.exe" show` and `& "$env:USERPROFILE\.cargo\bin\cargo.exe" ndk --version`.
   - Go: pinned portable toolchain at `build/tools/go/bin/go.exe` (handoff currently records 1.27.3); check `& .\build\tools\go\bin\go.exe version`. Gradle selects this automatically or accepts `-PgoExecutable=<absolute path>`. Module/build caches stay under `build/tools/`; `core` does not require the routing library to be packaged.
   - Never mix WSL toolchain paths with Windows Gradle or assume Windows and WSL caches/environments are shared. If deliberately using WSL for a Linux build, check its tools separately and use `/mnt/c/Users/Nathan/` paths.
   - Do not install or replace global toolchains automatically. Prefer existing tools and project-local downloads under ignored `build/tools/`; obtain approval for dependency changes as required below.

## Before you finish a task

- [ ] Follow the local compile checklist above; both variants build and lint (`:app:assembleCoreDebug :app:assembleClashDebug :app:lintCoreDebug :app:lintClashDebug`). Do not use obsolete `standard`/`research` tasks.
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
