# 16 · Block lists and rewrite rules

| Part | Status |
|---|---|
| Block lists (Adblock / hosts), TCP hostname stage | **implemented** 2026-11-14 (D-064), not device-checked |
| Block lists, DNS stage | **implemented** 2026-11-14 (D-065), not pushed, not device-checked |
| Rewrite rules, phase 1 (request side: local answer, header and query edits) | **implemented** 2026-11-15 (D-066), not pushed, not device-checked |
| Rewrite rules, phases 2–3 (redirect, response headers, body edits) | design only |
| Plugin-format import/export | proposal only |
| List hit counters, decision cache | not built |

Per-app blocking rules are specified in [13-BLOCKING.md](13-BLOCKING.md). This file covers the two newer rule sets and what they cost.

## 1. Two rule sets

| | Block set (阻止) | Rewrite set (改写) |
|---|---|---|
| Works on | connection metadata: app, hostname, IP, DNS name | decrypted HTTP messages |
| Needs decryption | no | yes, per app (existing Danger-zone consent) |
| Enforced | before the upstream dial; DNS answer | inside the MITM relay |
| Stored | compiled list files + `BlockRule` rows | new `RewriteRule` table (migration) |
| Consent | per-app rule: per app. List: one global consent per list | per app |

A block always wins over a rewrite: a blocked connection is never decrypted.

## 2. Block lists (implemented)

### Supported syntax

| Syntax | Meaning |
|---|---|
| `0.0.0.0 host`, `127.0.0.1 host` (hosts file) | block the exact host |
| `host` (one domain per line) | block host and subdomains |
| `\|\|host^` | block host and subdomains |
| `@@\|\|host^` | exception; wins over list blocks |
| `\|\|host^$important` | wins over exceptions |
| `\|\|host^$app=com.x\|com.y` | only for those apps |
| `!`, `#` comments, `[Adblock …]` header | ignored |
| paths, regex, `$third-party`, content-type modifiers, cosmetic `##` | **skipped and counted**: they need page or URL context a VPN does not have |

Precedence: per-app WAYD block → `$important` → `@@` exception → list block → pass.

### How it runs

- **Import** (Controls → Block lists): a file, or an HTTPS address the user types. Downloaded only on Import or Update; no schedule, no bundled list. Limits: 20 lists, 1,000,000 entries each, 32 MiB of text.
- **Compile:** `AdblockParser` turns text into the native line format once, at import; enabled lists are concatenated into one file under no-backup storage.
- **Consent:** enabling a list records `ConsentRecord("blocklist-v1:<id>")` for all covered apps, including later installs; disabling or deleting revokes it (AGENTS.md rule 9 exception).
- **Engine:** `hostlist.rs` stores hosts as sorted 64-bit hashes and decides with one binary search per label suffix. Loaded at VPN start and on every list change (`nativeSetBlockList`, policy ABI 3).
- **DNS stage:** a query for a listed name is answered locally (A → `0.0.0.0`, AAAA → `::`, other types → empty answer) and never leaves the phone. This is what covers QUIC. App-scoped entries rarely apply here because the system resolver, not the app, usually sends the query.
- **TCP stage:** with a list installed, each TCP connection waits ≤ 600 ms / 16 KiB for its hostname before dialing, except server-speaks-first ports (21, 22, 25, 110, 143, 587, 3306, 5432). If the name is still unknown the connection is recorded as unknown-host and forwarded.
- **Reported as:** `BLOCKED` with rule id `-1_000_000_000_000_000`; history shows "Blocked by a block list". A locally answered DNS query is visible only as its `0.0.0.0` answer.

### Limits

- Apps using their own encrypted DNS skip the DNS stage; QUIC to an address learned that way is not blocked.
- Hostnames hidden by ECH are not seen.

## 3. Rewrite rules (design)

Per app, on decrypted HTTP/1.1 only (the engine already forces HTTP/1.1 on decrypted flows). No scripts.

| Action | Effect | Phase |
|---|---|---|
| `REJECT` (204 / empty 200 / empty JSON; 1×1 image later) | answer locally, never contact the server | 1 (done) |
| `HEADER_REMOVE` / `HEADER_SET` on the request | drop or replace named headers | 1 (done) |
| `HEADER_REMOVE` / `HEADER_SET` on the response | drop or replace named headers | 2 |
| `QUERY_REMOVE` | drop named query parameters | 1 (done) |
| `REDIRECT` (302 / 307) | local redirect | 2 |
| `BODY_JSON_SET` / `BODY_JSON_REMOVE` | edit JSON fields by pointer | 2 |
| `BODY_REPLACE` (literal or bounded regex) | text bodies ≤ 1 MiB with a time budget | 3 |

- **Matching today (phase 1):** app + host (exact or with subdomains) + optional method + path/query prefix, exact or contains; no regex dependency yet.
- **Matching planned:** a URL regex compiled once (Rust `regex`, linear time), behind a hostname prefilter so flows without a rule stay on the byte-for-byte pass-through.
- **Engine work:** an active HTTP/1.1 stage that holds the head (and the body, for body actions), re-serialises with correct `Content-Length` / chunking, and decodes gzip before body edits.
- **Evidence:** the inspector keeps the original and the rewritten message; each rule has a hit counter and Undo.
- **AI:** may point to "add a rewrite for this request" as a suggestion; it never authors a rule.

## 4. Plugin-format import and export (proposal)

Use the common rule-plugin text format (`#!name=`, `#!desc=`, sections `[Rule]`, `[Rewrite]`, `[MITM]`, `[Script]`) as the exchange format. The keyword set below is from memory and **must be checked against that format's documentation before the importer is written**.

| Plugin line | WAYD target |
|---|---|
| `[Rule]` `DOMAIN,host,REJECT` / `DOMAIN-SUFFIX,host,REJECT` | block-list entry (exact / suffix) |
| `[Rule]` keyword, CIDR, user-agent, GeoIP, proxy policies | skipped, counted |
| `[Rewrite]` `<url-regex> reject` and its `-200` / `-img` / `-dict` / `-array` variants | rewrite `REJECT` with the matching body |
| `[Rewrite]` `<url-regex> 302\|307 <url>` | rewrite `REDIRECT` |
| `[Rewrite]` header add / delete / replace (request and response) | rewrite header actions |
| `[Rewrite]` body regex replace, JSON edits, mock body | rewrite body actions (phases 2–3) |
| `[MITM]` `hostname = …` | hosts that need decryption; shown to the user, never switched on automatically |
| `[Script]` | skipped, counted |

- Plugin rules are global; WAYD rewrites stay per app. An imported plugin becomes one rule group the user attaches to chosen apps; its `[Rule]` part becomes a block list.
- Export writes a group back without app names. Per-app exact-host blocks export as `DOMAIN,host,REJECT`; APP and IP rules stay in WAYD's JSON export.

## 5. Cost: do many rules slow the phone?

Not if the lookup is built for it. Power goes to CPU per packet, memory pressure and wake-ups, not to the rule count.

| Approach used elsewhere | Why it is cheap |
|---|---|
| DNS-only blockers | only DNS is inspected; the list is a hash set, so lookup time does not grow with list size |
| Compiled filter engines | rules are indexed by a short key, so a handful of candidates are checked per request; compiled lists are cached |
| Rule-based proxy clients | suffix sets decided once per connection; decryption only for listed hostnames |

What WAYD does, and what is still open:

| Measure | State |
|---|---|
| Decide once per connection, never per packet | done |
| Hashed hosts, a few binary searches per lookup | done (300k-entry test list: 100k lookups within the test bound on a PC) |
| Parse lists at import, not at VPN start | done (text → native lines); a binary `mmap` format is a later step |
| Bounded hostname wait, skipped for server-first ports | done |
| DNS-stage answers | done |
| Decision cache (host → verdict) | not built; add only if profiling shows a need |
| Rewrite only for hosts with a rule; body edits size- and time-capped | design |
| No polling: lists update only on the user's action | done |

To measure on the NX733J: list load time and extra memory for a 300k-domain list, per-connection delay with a list on, and screen-off drain with the list on versus off.

## 6. Order of work

1. Device check of block lists and the DNS stage.
2. Rewrite phase 1: `RewriteRule` table and migration, active HTTP/1.1 stage, Controls → Rewrite, inspector original/rewritten view, hit counters.
3. Plugin-format importer/exporter for both rule sets.
4. Rewrite phases 2 and 3.
