# 16 · Block lists, rewrite rules and their cost (design)

Status: **block lists implemented 2026-11-14 (D-064: native matcher, Adblock/hosts parser, list storage, consent, Controls section); rewrite rules and hit counters are still design only.** Nat answered the decisions at the end: global consent yes; file and URL import; rewrite phase 1 as proposed; list rules use the bounded hostname wait (no early pass-through). It answers three requests from Nat: split blocking and rewriting into two rule sets with the block set Adblock-compatible; add rewriting of decrypted traffic; and make sure many rules do not slow the phone or drain the battery. It needs Nat's decisions at the end before any native work starts.

## Where the code is today

- Blocking ([blocking.rs](../capture/native/src/blocking.rs), [BlockingRules.kt](../capture/src/main/kotlin/com/nathanhanapps/wayd/capture/BlockingRules.kt)): per-app APP / exact HOST / exact IP rules, a `Vec<Rule>` scanned per connection, capped at 1,000 rules / 512 KiB. Every rule is bound to one app with its own consent record.
- Decryption ([mitm/](../capture/native/src/mitm)): rustls on both sides, HTTP/1.1 only (ALPN is forced to `http/1.1`), and `http1.rs` is a **passive** parser that copies messages for the inspector. Bytes are relayed unchanged.
- So an Adblock list (10⁴–10⁵ domains, all apps) does not fit the current matcher or consent model, and rewriting needs a new active stage in the relay, not a tweak.

## 1. Two rule sets

| | **Block set (阻止)** | **Rewrite set (改写)** |
|---|---|---|
| Works on | connection metadata: app, hostname (SNI / HTTP Host), IP | decrypted HTTP messages |
| Needs decryption | no | yes, per app, existing Danger-zone consent |
| Format | Adblock / hosts lists + WAYD's own per-app rules | WAYD rewrite rules, JSON |
| Where enforced | before the upstream dial (existing gate) | inside the MITM relay |
| Stored | compiled binary set per list + existing `BlockRule` rows | new `RewriteRule` table (Room migration) |
| Screen | Controls → Block | Controls → Rewrite |

They stay separate end to end: separate storage, separate native snapshots, separate enable switches and separate screens. A block always wins over a rewrite (a blocked connection is never decrypted).

### Block set: Adblock compatibility

Supported subset, chosen because it is what a VPN without content access can honour:

| Syntax | Meaning | Supported |
|---|---|---|
| `0.0.0.0 host` / `127.0.0.1 host` (hosts file) | block exact host | yes |
| `host` (domain list, one per line) | block host and subdomains | yes |
| `\|\|host^` | block host and subdomains | yes |
| `@@\|\|host^` | exception (allow), wins over list blocks | yes |
| `\|\|host^$app=com.x\|com.y` (AdGuard `$app`) | limit to apps | yes |
| `\|\|host^$important` | wins over exceptions | yes |
| `! comment`, `# comment`, `[Adblock Plus …]` header | ignored | yes |
| path, regex (`/…/`), `$third-party`, `$script`, cosmetic `##` | need page/URL context | **counted and skipped**, shown as "not applicable to a VPN" |

Lists are imported by the user from a file or a URL they type (a user-initiated download, like models and GeoIP; no automatic refresh unless the user turns on a per-list schedule). Each list shows: rules applied / skipped, last update, and hit count. WAYD's own exact per-app rules remain and keep their consent semantics; a list is one **global** consent ("block these hosts for all apps WAYD covers"), recorded once per list in `ConsentRecord` and revocable, the same shape as the D-045 Clash blacklist exception. This needs a line in AGENTS.md rule 9.

Precedence: per-app WAYD block → `$important` list block → `@@` exception → list block → pass.

### Rewrite set: what a rule can do (no scripts first)

```json
{
  "app": "org.example.app",
  "match": { "host": "api.example.com", "method": "POST", "pathPrefix": "/v1/collect" },
  "action": { "type": "REJECT", "status": 204 }
}
```

| Action | Effect | Phase |
|---|---|---|
| `REJECT` (`200` / `204` / empty JSON / 1×1 image) | answer locally, never contact the server | 1 |
| `HEADER_REMOVE` / `HEADER_SET` (request or response) | drop or replace named headers (e.g. `X-Device-Id`, `Cookie` on a telemetry host) | 1 |
| `QUERY_REMOVE` | drop named query parameters | 1 |
| `REDIRECT` (302/307 to a fixed URL) | local redirect | 2 |
| `BODY_JSON_SET` / `BODY_JSON_REMOVE` (JSON pointer paths) | change or remove fields in a JSON body | 2 |
| `BODY_REPLACE` (literal or bounded regex) | text bodies only, ≤ 1 MiB, with a time budget | 3 |
| scripts (JS) | — | not planned: a script engine is new attack surface and battery cost |

Matching is exact host (or `*.` suffix) + optional method + path prefix; regex on the URL only in phase 3 and compiled once. Rules are per app, need that app's decryption consent, and each has a hit counter and an Undo. The inspector keeps both the original and the rewritten message, so evidence is never silently altered.

Engine work this implies (Rust): an active HTTP/1.1 stage that holds the head (and, for body actions, the body up to the limit) before forwarding, re-serialises with a correct `Content-Length`, handles `chunked`, and leaves everything else as a byte-for-byte pass-through. gzip/br bodies are only rewritten after decoding (gzip in phase 2; br needs a decoder dependency). HTTP/2 stays out: the engine already forces HTTP/1.1 on decrypted flows.

## 2. Will many rules slow the phone or drain the battery?

Not if the lookup is designed for it. What costs power on a phone VPN is CPU time per packet, memory pressure and wake-ups, not the number of rules. How other tools keep it flat (from their published designs and source; worth re-checking specific numbers before quoting them):

| Tool | How it stays cheap |
|---|---|
| DNS66, Blokada (DNS mode), personalDNSfilter | only DNS queries are inspected; the block list is a hash set, so a lookup is O(1) regardless of list size. Costs are load time and RAM (roughly tens of MB for a few hundred thousand hosts as Java strings). |
| AdGuard for Android | native filtering engine; rules are compiled into hash-indexed tables keyed by a short substring ("shortcut"), so only a handful of candidate rules are evaluated per request. HTTPS filtering is opt-in per app and it is the expensive part, not the rule count. |
| uBlock Origin | tokenised filter index plus a hostname trie; compiled lists are cached ("selfie") so start-up does not re-parse text. |
| Rule-based proxy clients | `DOMAIN-SET` / rule sets in a suffix trie; the decision is made **once per connection** and cached; MITM runs only for hostnames explicitly listed; scripts only on URL matches. |

What WAYD should do, in order of effect:

1. **Decide once per connection, never per packet.** WAYD already gates at connection open; keep it that way and cache the decision on the flow.
2. **Hash lookup, not a scan.** Replace the `Vec<Rule>` scan with: exact-host `HashSet` + suffix lookup by walking the host's labels (≤ ~5 hash probes for `a.b.c.example.com`), per-app overrides in a small map keyed by UID. Cost is independent of list size: 100 rules and 300,000 rules take the same few probes.
3. **Compile lists off the hot path.** Parse text once at import into a sorted binary file of 64-bit host hashes (≈ 8 bytes per domain: 300k domains ≈ 2.4 MB, `mmap`ed, binary search or a perfect-hash table). VPN start then maps a file instead of parsing megabytes of text, and RAM stays shared and reclaimable.
4. **Add a small decision cache** (host → verdict, a few thousand entries) so repeat connections skip even the probes.
5. **Keep the host gate bounded.** The existing 600 ms / 16 KiB SNI inspection only runs when host rules exist; with a global list it runs for every TCP connection, so the SNI must come from the first ClientHello packet (it already does in the normal case) and the timeout path must fail open for list rules (fail closed stays for per-app rules).
6. **Rewrite only where asked.** Decryption costs two TLS sessions per connection; rewriting adds buffering. Both stay limited to apps with consent and to hosts that have at least one rewrite rule: a decrypted flow whose host has no rule keeps today's pass-through. Body rewrites need a size cap, a content-type allow-list and a time budget; header/REJECT rules need no buffering at all.
7. **No extra wake-ups.** Lists update only on the user's action or an optional daily job on unmetered power; nothing polls.

Budgets to verify on the NX733J before calling it done: list load < 300 ms for 300k domains; decision < 5 µs p99; extra RSS < 10 MB; no measurable change in a 30-minute screen-off drain test with the list on and off (Nat runs soaks).

## 3. Order of work

1. Native matcher v2 (hash sets, suffix walk, decision cache) behind the existing policy snapshot ABI; unit tests + `cargo test`; no behaviour change for existing rules. Raise the 1,000-rule cap for list sets only.
2. Adblock/hosts parser (Kotlin, pure, unit-tested) → compiled binary; list management screen (import file/URL, counts, skipped syntax, enable, delete); global list consent; Controls split into Block / Rewrite tabs.
3. Rewrite phase 1 (REJECT, header and query actions): `RewriteRule` table + migration, active HTTP/1.1 stage, inspector shows original vs rewritten, hit counters.
4. Rewrite phase 2 (JSON body actions, gzip), then phase 3 (bounded text replace).
5. AI: suggestions may point to "add a rewrite for this request" only as a pointer, like the AppOps/Thanox ones; the model never authors a rule.

## Decisions needed from Nat

1. **Global lists:** accept one revocable global consent per imported list (all covered apps), as an explicit exception in AGENTS.md rule 9? Without it, lists can only apply to apps chosen one by one.
2. **List downloads:** allow fetching a list from a URL the user types (user-initiated, like model downloads), or file import only?
3. **Rewrite scope for phase 1:** REJECT + header/query actions first (no body buffering), as above?
4. **Fail-open for list rules** when the hostname cannot be read in time (per-app rules stay fail-closed)?

## Plugin-format import and export (proposal, 2026-11-14)

Nat asked for rule-based blocking and rewriting of decrypted traffic with rules that can be exchanged. Proposal: treat the common rule-plugin text format (`.plugin` text with `#!name=` / `#!desc=` and `[Rule]`, `[Rewrite]`, `[MITM]`, `[Script]` sections) as the interchange format, importing a subset and exporting the same subset. The exact keyword set below is from memory and **must be checked against that format's current documentation before the importer is written**.

| Plugin section / line | WAYD target | Notes |
|---|---|---|
| `[Rule]` `DOMAIN,host,REJECT` | block list entry, exact | all `REJECT*` policies mean block at connection level |
| `[Rule]` `DOMAIN-SUFFIX,host,REJECT` | block list entry, suffix | |
| `[Rule]` `DOMAIN-KEYWORD`, `IP-CIDR`, `USER-AGENT`, `GEOIP`, proxy policies | skipped, counted | keyword/CIDR could follow later; proxy policies belong to Clash routing, not here |
| `[Rewrite]` `<url-regex> reject` / `reject-200` / `reject-img` / `reject-dict` / `reject-array` | rewrite REJECT with the matching canned body | needs decryption for that host |
| `[Rewrite]` `<url-regex> 302 <url>` / `307 <url>` | rewrite REDIRECT | |
| `[Rewrite]` `header-add` / `header-del` / `header-replace`, `response-header-*` | rewrite header actions | |
| `[Rewrite]` `request-body-replace-regex`, `response-body-replace-regex`, `response-body-json-*`, `mock-response-body` | rewrite body actions | phases 2–3 |
| `[MITM]` `hostname = a.com, *.b.com` | list of hosts the rules need decrypted | never switches decryption on: WAYD shows which apps/hosts would need the existing per-app consent |
| `[Script]` | skipped, counted | no script engine planned |

Consequences for the rewrite design above:

- **Match on a URL regex**, as the plugin format does, instead of host + path prefix. The Rust `regex` crate is linear-time (no catastrophic backtracking), patterns compile once at install, and a hostname prefilter (from `[MITM]` or the literal host in the pattern) keeps unrelated flows on the pass-through path. Imported patterns over a size limit, or using look-around/back-references the crate rejects, are skipped and counted.
- **Scope:** plugin rules are global; WAYD rewrite rules stay per app. An imported plugin is stored as one rule group that the user attaches to chosen apps (each needs its decryption consent); its `[Rule]` part becomes a block list under the D-064 global consent. Export writes a group back as a plugin without app names.
- **Export of WAYD's own rules:** per-app exact-host blocks export as `DOMAIN,host,REJECT`; APP and IP rules have no plugin equivalent and stay in WAYD's JSON export.
- Adblock/hosts import (D-064) stays; the plugin format is an additional format for the same two rule sets.
