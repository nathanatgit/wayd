# CLAUDE.md

All agent instructions for this repository live in [AGENTS.md](AGENTS.md). Read it first, then [docs/HANDOFF.md](docs/HANDOFF.md).

Quick reminders:
- Local-only app: never add network calls, analytics or crash upload.
- Rules decide severity; Gemma only explains with evidence IDs.
- Privileged operations only through the sealed `Command` allowlist.
- Compose + Material 3 Expressive; strings in English and 简体中文.
- Prefer minimal, iterative changes; give complete files when integration is risky.
