# To-do · Block lists (P2)

Built: Adblock/hosts/rule-plugin import, per-list consent, hashed matcher, TCP hostname stage, DNS stage (D-064, D-065, D-067). Design and limits: [16-RULE-SETS-AND-REWRITE.md](../16-RULE-SETS-AND-REWRITE.md).

## Tasks

| # | Task | Notes |
|---|---|---|
| 1 | Hit counters per list and overall (blocked connections, locally answered DNS queries). | The engine knows only "a list matched"; keeping a list id per entry costs memory. Start with totals and the top blocked hosts. |
| 2 | Show locally answered DNS names. | Today they are visible only as a `0.0.0.0` answer in a DNS record, and only when recording is on. Add a small "blocked names" view fed by an engine counter. |
| 3 | Per-app exception. | "Never apply lists to this app" for apps a list breaks. One line per app in the compiled file (`@app`), checked before the list. |
| 4 | Optional update schedule per list. | Off by default; daily at most, on unmetered network and charging. Needs Nat's approval: it is a scheduled download (AGENTS.md rule 1 allows only user-initiated ones today). |
| 5 | Binary list format, memory-mapped. | Only if the device pass shows slow VPN start or high memory with large lists. |
| 6 | Decision cache (host → verdict). | Only if profiling shows the lookups matter. |
| 7 | Known encrypted-DNS endpoints. | Optional list entry type that blocks well-known DoH hosts so apps fall back to system DNS. Risky for apps that require it; opt-in. |
| 8 | Import feedback. | Show a few examples of skipped lines and why, not just the count. |

## Open questions for the device pass

- Does the ≤ 600 ms hostname wait break or slow any non-HTTP app on ports outside the exempt list?
- Do some ROM resolvers retry aggressively when they get `0.0.0.0`?
