# 在干嘛 · WAYD (What Are You Doing)

> An on-device Android privacy monitor that records what every app on your phone sends and receives, explains it in plain language, and helps you stop what you don't want.

- **Package:** `com.nathanhanapps.wayd`
- **Publisher:** nathanhanapps
- **Platform:** Android 10+ (API 29+) — target latest stable SDK
- **UI:** Jetpack Compose + Material 3 Expressive
- **Core principle:** On-device inspection and analysis. WAYD inspects traffic and analyzes findings on your Android device. It never automatically uploads captured traffic, inspection logs, reports, or AI analysis. You can explicitly export data to a destination you choose. Apps still communicate with their destinations; user-initiated model/database downloads also use the network. An opt-in routing adapter forwards permitted traffic from selected apps through configured proxies. **One opt-in exception:** if you turn on an external OpenAI-compatible AI server, analysis text (hosts, IPs, app names, times, and decrypted content if you select it) is sent to the server you chose. It is off by default, needs a per-server acknowledgement, and shows you the exact text for approval before every send.

## Product scope (implemented and planned)

See Status below and the handoff for implementation coverage; this list describes the product direction.

1. **Record** — a switch starts a local `VpnService` that routes all app traffic through WAYD; a separate *Record traffic* switch saves it (flows, DNS, TLS metadata, cleartext payloads). Blocking works with recording off.
2. **Enrich** — maps traffic to apps (UID), domains, companies, countries and known trackers.
3. **Correlate** — with Shizuku (adb) or root, adds non-network evidence: permission use (AppOps), alarms, per-app traffic history, file access.
4. **Analyse** — a deterministic rule engine produces *findings* with evidence links.
5. **Explain** — an on-device Gemma 4 model, or optionally an OpenAI-compatible server you configure (e.g. a local Ollama), explains selected findings/traffic and returns structured JSON that the UI visualises. The AI never decides severity or blocks anything; every claim must cite evidence ids.
6. **Inspect APKs** — static analysis of installed apps (manifest, certificate, SDKs, embedded endpoints), linked to observed behaviour.
7. **Act (M7 core)** — local exact app/host/IP blocking and reviewed rule transfer; research content control remains planned; no proxy or account required.
8. **Route (initial optional subset)** — Pinned Mihomo SOCKS5/HTTP CONNECT routing with FlClash-style whitelist/blacklist access control and encrypted profiles. Unsupported Clash options are rejected; full FlClash compatibility is not claimed. WAYD retains VPN ownership and enforces blocks before routing. See [routing design](docs/14-OPTIONAL-ROUTING.md).
9. **Inspect and export** — open any recorded connection for its details (TLS, rule outcome, DNS, decrypted content as text/hex), and export as *WAYD detail JSON* (flows, DNS, permission history, findings, app facts, for analysis), a *PCAPdroid-compatible connections CSV*, or a *reconstructed PCAP* (WAYD stores metadata, not packets, so the PCAP is rebuilt from records and carries real payload only where HTTPS was decrypted).

## Privilege levels (target capabilities; see handoff for current coverage)

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
| [docs/13-BLOCKING.md](docs/13-BLOCKING.md) | Blocking and content-control design |
| [docs/14-OPTIONAL-ROUTING.md](docs/14-OPTIONAL-ROUTING.md) | Optional Clash/Mihomo routing and access control |
| [docs/12-TESTING.md](docs/12-TESTING.md) | Test strategy, ground-truth capture |
| [docs/DECISIONS.md](docs/DECISIONS.md) | Architecture decision log |

## Status

The current release is **v0.3.0**: working capture, local analysis, on-device and optional external AI, connection detail and exports, local blocking, and research HTTPS inspection. Coverage and acceptance gaps remain. **M7 core blocking, reviewed persistent-rule transfer and an initial optional Mihomo routing subset are implemented locally. Installed-app routing/transfer acceptance, research response controls and broader Clash compatibility remain open.** See [current handoff](docs/HANDOFF.md) and [roadmap](docs/11-ROADMAP.md).
