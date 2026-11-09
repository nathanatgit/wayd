# 在干嘛 · WAYD (What Are You Doing)

> An on-device Android privacy monitor that records what every app on your phone sends and receives, explains it in plain language, and helps you stop what you don't want.

- **Package:** `com.nathanhanapps.wayd`
- **Publisher:** nathanhanapps (sibling of AppDualZuku)
- **Platform:** Android 10+ (API 29+) — target latest stable SDK
- **UI:** Jetpack Compose + Material 3 Expressive
- **Core principle:** *Everything stays on the phone.* WAYD never uploads captured data, reports, or AI analyses anywhere.

## What it does

1. **Record** — one big button starts a local `VpnService` that routes all app traffic through WAYD and records it (flows, DNS, TLS metadata, cleartext payloads).
2. **Enrich** — maps traffic to apps (UID), domains, companies, countries and known trackers.
3. **Correlate** — with Shizuku (adb) or root, adds non-network evidence: permission use (AppOps), alarms, per-app traffic history, file access.
4. **Analyse** — a deterministic rule engine produces *findings* with evidence links.
5. **Explain** — an on-device Gemma 4 model explains selected findings/traffic and returns structured JSON that the UI visualises.
6. **Inspect APKs** — static analysis of installed apps (manifest, certificate, SDKs, embedded endpoints), linked to observed behaviour.
7. **Act** — block domains, flag apps, or copy safe adb commands.

## Privilege levels

| Level | How | Unlocks |
|---|---|---|
| None | VpnService only | Traffic metadata, cleartext HTTP, DNS |
| ADB | Shizuku | AppOps history, alarms, netstats, package details |
| Root | Shizuku-as-root or libsu | VPN-bypass detection, TLS plaintext via eBPF/hooks, file access, deep APK analysis |

## Documentation map

| Doc | Purpose |
|---|---|
| [AGENTS.md](AGENTS.md) | Rules for AI coding agents (read first if you are an LLM) |
| [docs/HANDOFF.md](docs/HANDOFF.md) | Current state and next step |
| [docs/00-PRD.md](docs/00-PRD.md) | Product requirements, users, scope |
| [docs/01-ARCHITECTURE.md](docs/01-ARCHITECTURE.md) | Modules, data flow, threading |
| [docs/02-DATA-MODEL.md](docs/02-DATA-MODEL.md) | Room entities, IDs, retention |
| [docs/03-CAPTURE.md](docs/03-CAPTURE.md) | VpnService, netstack, parsers, coverage |
| [docs/04-PRIVILEGE-AND-ROOT.md](docs/04-PRIVILEGE-AND-ROOT.md) | Shizuku, root, command allowlist |
| [docs/05-ANALYSIS-RULES.md](docs/05-ANALYSIS-RULES.md) | Rule engine, findings, scoring, daily report |
| [docs/06-AI-GEMMA.md](docs/06-AI-GEMMA.md) | Gemma 4 integration, tools, output schema |
| [docs/07-APK-SCANNER.md](docs/07-APK-SCANNER.md) | Static APK analysis |
| [docs/08-MITM-AND-CONSENT.md](docs/08-MITM-AND-CONSENT.md) | HTTPS decryption, consent flow, privacy statement |
| [docs/09-UI-M3-EXPRESSIVE.md](docs/09-UI-M3-EXPRESSIVE.md) | Screens, navigation, Material 3 Expressive usage |
| [docs/10-SECURITY-THREAT-MODEL.md](docs/10-SECURITY-THREAT-MODEL.md) | Threats, prompt injection, data protection |
| [docs/11-ROADMAP.md](docs/11-ROADMAP.md) | Milestones with acceptance criteria |
| [docs/12-TESTING.md](docs/12-TESTING.md) | Test strategy, ground-truth capture |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Architecture decision log |

## Status

Planning. No code yet. Start at milestone M0 in [docs/11-ROADMAP.md](docs/11-ROADMAP.md).
