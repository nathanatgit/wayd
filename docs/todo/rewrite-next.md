# To-do · Rewrite rules, rule exchange, Controls layout

Design: [16-RULE-SETS-AND-REWRITE.md](../16-RULE-SETS-AND-REWRITE.md). Built so far (D-066, D-068): request-side local answer, redirect, header remove/set, query remove; `mitm/rewrite.rs`, `RewriteRules.kt`, `RewriteSection.kt`.

## Phase 2 (P1)

| Task | Notes |
|---|---|
| Response header remove/set | Needs a response-side head stage and a queue pairing each response with the request that matched. Reuse the request framer's states; add close-delimited bodies, HEAD and 204/304. |
| JSON body set/remove | Buffer bodies ≤ 1 MiB, `Content-Type: application/json` only; fix `Content-Length`; gzip decode/encode first (dependency decision below). |
| Ordering with pipelined requests | Today a local answer waits for in-flight responses; a request pipelined after a rejected one can be answered out of order. Either stop forwarding later requests until the local answer is sent, or document and leave. |

## Decisions needed from Nat

1. ~~Regex matching~~ — approved and built (D-069): match kind `REGEX` on the full URL.
2. **Compression:** add a gzip crate (`flate2`) for body edits? Without it, body rules only apply to uncompressed bodies.
3. **Storage:** rules are a JSON file under no-backup storage. Move to a Room table (migration, schema v6) only if rules need to join with history.

## Inspector and usability (P1)

- Show the rewritten request next to the original (today only the original is recorded; local answers are marked `X-WAYD-Rewrite`).
- "Add rewrite rule from this request" in the message inspector: prefill app, host, method and path.
- Pick the app from the installed/traffic list instead of typing the package name.
- Warn in the dialog when the app is not in the decryption allowlist (the rule would never apply).

## Rule exchange (P2)

- Import: rule-plugin `DOMAIN` / `DOMAIN-SUFFIX` lines already load as block-list entries (D-067). Regex matching exists (D-069), so `[Rewrite]` reject / redirect lines can be imported next: the importer must ask which app the group applies to and take the host from `[MITM]` or the pattern. Check the keyword list against the format's documentation before writing the importer.
- Export: per-app exact-host blocks and enabled lists as `DOMAIN` / `DOMAIN-SUFFIX` lines; rewrite rules once the formats line up. APP and IP rules stay in WAYD's JSON export.
- Include block lists (metadata only) and rewrite rules in the existing reviewed JSON backup/import.

## Controls layout (P2)

- Three tabs: Block rules (per app) · Block lists · Rewrite. Today they are three stacked sections.
- Per-rule hit counts for block rules and lists, as rewrite rules already have.
