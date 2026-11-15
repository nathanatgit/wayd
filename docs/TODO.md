# To-do

_Written 2026-11-15, after D-067. Current state: [HANDOFF.md](HANDOFF.md). Each area below has a detail file under [todo/](todo/)._

Priorities: **P0** blocks trust in what is already shipped or built · **P1** next features and measurements · **P2** worthwhile, not urgent · **P3** housekeeping.

## P0 — before anything new

| # | Task | Detail |
|---|---|---|
| 1 | Device pass for everything since D-061 (labels, history check, chat, depth, profiles, block lists, DNS stage, rewrite rules, inferred names). Nothing after v0.4.0 has been checked on a phone, and v0.5.0 is already released. | [todo/device-pass.md](todo/device-pass.md) |
| 2 | Push and merge `feat/dns-list-blocking` (D-065 to D-068) once the device pass is clean, then decide the next version. | [todo/device-pass.md](todo/device-pass.md) |

## P1 — next

| # | Task | Detail |
|---|---|---|
| 3 | Rewrite phase 2: response header edits, JSON body edits; decide on a regex dependency; show the rewritten request in the inspector; "add rule from this request". | [todo/rewrite-next.md](todo/rewrite-next.md) |
| 4 | Measure hostname coverage after D-067; then QUIC Initial SNI, DNS over TCP, session-scoped coverage numbers. | [todo/hostname-coverage.md](todo/hostname-coverage.md) |
| 5 | AI quality: run `HistoryEvalTest` on Gemma E2B/E4B, add the same kind of score for labels and chat, injection tests on the real model. | [todo/ai-quality.md](todo/ai-quality.md) |

## P2 — worthwhile

| # | Task | Detail |
|---|---|---|
| 6 | Block lists: hit counters, a view of locally answered DNS names, optional per-list update schedule, binary list format. | [todo/block-lists-next.md](todo/block-lists-next.md) |
| 7 | Rule exchange: export per-app blocks and lists in the rule-plugin format; import rewrite lines once regex matching exists. | [todo/rewrite-next.md](todo/rewrite-next.md) |
| 8 | Labels: measure the snapshot rebuild on the phone; grow the curated role list; app-level attention on the Apps list; user override of a role. | [todo/labels-and-integrations.md](todo/labels-and-integrations.md) |
| 9 | AppOps / Thanox: go from "open the app" to a prepared action, if their interfaces allow it. | [todo/labels-and-integrations.md](todo/labels-and-integrations.md) |
| 10 | Controls screen: split into Block rules / Block lists / Rewrite tabs; pick the app from a list in the rewrite dialog. | [todo/rewrite-next.md](todo/rewrite-next.md) |

## P3 — housekeeping

| # | Task | Detail |
|---|---|---|
| 11 | Rust warnings on the host build, release commit hygiene, lint report in CI, unreviewed older docs. | [todo/tech-debt.md](todo/tech-debt.md) |
| 12 | Older open items: per-app rule edge cases (M7), Clash acceptance, M3/M5 collectors, full-inventory APK scan. | [todo/tech-debt.md](todo/tech-debt.md) |

## How to use this file

- Take the lowest-numbered open task unless Nat says otherwise.
- When a task is done: delete its row (and the section in its detail file), record the decision in [DECISIONS.md](DECISIONS.md), update [HANDOFF.md](HANDOFF.md).
- New ideas go into the matching detail file first; promote them here only when they are worth scheduling.
