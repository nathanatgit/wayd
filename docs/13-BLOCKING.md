# 13 · Blocking and content control (M7 design)

Status: **core blocking and history controls implemented locally, 2026-11-10; device acceptance pending.** Response editing, JSON backup/import and Clash integration below remain designs, not available features. Budgets are not measured battery results.

## Current implementation

Sample blocking, future local replies/replacement and field-specific regex: [Rule examples / 规则示例](15-RULE-EXAMPLES.md). Only typed exact blocking is currently executable; examples do not add import or regex support. Session rule lifetime follows the WAYD VPN engine, not the independent Home recording toggle.

- [BlockingRules](../capture/src/main/kotlin/com/nathanhanapps/wayd/capture/BlockingRules.kt) owns durable and memory-only session rules, explicit per-app ConsentRecords, current-user/package/signer binding, activation acknowledgment and removal. Shared UIDs, missing identity and legacy unbound records remain inactive. Rules added while VPN is off are saved but inactive.
- [Native policy](../capture/native/src/blocking.rs) atomically installs increasing versioned snapshots (1,000 rules / 512 KiB); any matching BLOCK wins. Supported targets are per-app APP, exact HOST and exact numeric IP, including IPv6. No generic ALLOW, suffix/CIDR/regex or DNS-derived host rules yet.
- [Relay](../capture/native/src/relay.rs) checks app/IP before dialing or sending UDP. When policy requires metadata, ownership has a one-second gate; applicable host rules inspect at most 16 KiB for 600 ms before TCP dialing. Kotlin reuses the existing complete SNI/HTTP Host parser. Unknown owners/names are explicitly recorded and may forward; required-gate timeout fails closed. UDP host inspection, ECH and late names remain gaps. Installing a rule closes matching known live flows; bytes already sent cannot be recalled. No affected-flow count UI yet.
- [App history](../app/src/main/kotlin/com/nathanhanapps/wayd/feature/apps/AppBlockingSection.kt) has literal hostname/IP search over the latest 200 flows, displays up to 30 matches, and offers Block this time / Block forever with scope/consent confirmation and Undo. Only visible SNI/HTTP Host produces a host target; otherwise the sheet offers the exact IP with a shared-address warning. Whole-app blocking is explicit.
- [Controls](../app/src/main/kotlin/com/nathanhanapps/wayd/feature/controls/ControlsScreen.kt) is a dedicated bottom tab (also reachable from Apps) for the complete saved/session rule inventory, independent of history windows. It shows native-acknowledged effective counts, All / Effective / Inactive filters, literal package/host/IP search, target/action/lifetime/status, expandable identity details, app-history navigation and confirmed removal. Capture-off rules remain visible as inactive. Response replacement/local replies are explicitly unavailable; the alteration section is reserved for their future inventory. Refresh on resume or user request; no periodic polling.
- Temporary rules expire on WAYD VPN stop or process/engine restart, independently of the traffic-recording switch. Persistent rules require the WAYD VPN engine to run and a valid installed identity/consent. Package changes trigger revalidation. Recorded flow rows retain policy outcome, revision and matched rule ID separately from risk findings; recording off does not save new flow evidence. Room v4 adds these fields and binding fields; legacy rules have empty signer bindings and stay inactive.
- Native ABI capability is checked before starting capture; an old library cannot silently start without enforcement. Native destruction happens after the polling thread's final poll, serialized with UI policy JNI calls.
- HTTP capture remains passive. No response-edit button, script engine, backup/import UI or routing engine is introduced in this slice. Existing research UDP/443 dropping is separate from host blocking.

## Remaining design (not implemented below)

## Connection-history actions

Use one rule sheet from app connection rows and message details. Default scope: **this app + this exact observed hostname**. Show package, target, hostname evidence source and lifetime. Without a name, offer app + IP with a shared-address warning; never infer a domain from a shared IP.

| Action / proposed Chinese label | Behavior |
|---|---|
| Block this time / 本次阻止 | Temporary app + target rule for this WAYD VPN run, including reconnects. Expires on VPN stop or engine/process restart, not on recording toggle. Subtitle: Until WAYD VPN stops. |
| Block forever / 一直阻止 | Persistent rule until disabled/deleted. Works while WAYD VPN and policy run, not while VPN is off. Subtitle: Saved rule. |
| Disconnect / 断开连接 | Separate optional action on a still-live exact flow ID. Ends that flow only; reconnect is possible. Disabled on closed rows. |
| Edit response… / 编辑响应… | Preview/create a rule for future matching messages. Never modify retained evidence or automatically replay requests. Show research HTTPS trust/consent requirements. |

These semantics avoid pretending a historical request can be undone. Saving reports Active only after native snapshot acknowledgment. Offer Undo. New app/host blocks close matching known live flows and report the count; bytes already sent cannot be recalled. Per-request actions respect HTTP message boundaries rather than terminating unrelated requests on a shared connection.

Advanced targets: exact host; domain plus subdomains; IP/CIDR; whole app; decrypted method + host + path. Preview affected scope before broad rules; all-app scope requires separate confirmation. Dangerous controls remain per-app opt-in, reversible and recorded in ConsentRecord. Use versioned feature identities including package/user scope; the current generic table is not proof this consent exists. Implement labels in both locales.

## Coverage and matching

| Layer | Requirements and limits |
|---|---|
| App | Covered VPN flow and resolved owner. Shared UIDs cannot safely distinguish packages without additional evidence; show affected packages. Excluded apps, OS bypass and VPN-off traffic remain outside enforcement. |
| IP/CIDR | Known destination, IPv4 and IPv6. Shared hosting can affect unrelated sites. |
| Host | Visible SNI, plaintext HTTP Host, or DNS correlation with source/expiry. ECH, encrypted DNS, missing names and shared IPs limit coverage. DNS correlation alone is not proof of the destination hostname. Pinning does not itself hide visible SNI. |
| URL/method/headers | Parsed plaintext HTTP or successfully intercepted research HTTPS. Ordinary TLS hides paths. Pinning, trust failures and proprietary encryption may prevent inspection. |
| Body | Complete supported HTTP message, selected MIME and size bounds. Initial rewriting excludes HTTP/2, HTTP/3, streams, WebSocket, signed/encrypted payloads and truncated capture prefixes. |

Do not claim every TLS app is host-blockable or every QUIC app falls back. Unknown host/owner is Not evaluable, never a successful host/app policy decision. Default missing-host forwarding is a disclosed coverage gap; offer explicit app-wide blocking. Shared-IP inference must not silently widen a domain block.

Use a versioned WAYD model independent of Clash YAML: UUID, revision, label, enabled, app/user binding (or explicitly any app), lifetime, typed predicates, action and bounded parameters. Counters/last hit are separate from portable definitions. Session rules live only in session memory; durable storage needs a Room migration and exported schema.

Predicates combine with AND:

- hostExact: lowercase ASCII/IDNA with one terminal dot stripped; no substring guessing.
- domainSuffix: example.com plus names ending in .example.com, never notexample.com. This explicitly represents the old *.example.com idea; a one-label wildcard is different.
- Literal hostContains, path prefix, explicit query key/value, method, IP/CIDR, protocol and port. Preserve path case/encoded separators. Query matching is opt-in; do not copy tokens from history by default.
- Advanced regex has an explicit target and fixed search semantics using Rust regex syntax, with source/compiled-size limits and no lookaround/backreferences. Compile once. Kotlin previews must use the authoritative matcher or shared fixtures, not a different backtracking engine.
- Grep-style history search is separate: literal host/path/text search first, optional safe regex over bounded retained data. Search never activates a rule. Never invoke shell grep.

Initial actions: BLOCK and OBSERVE_ONLY; later LOCAL_RESPONSE and REWRITE_RESPONSE in the supported research HTTP path. **Any applicable BLOCK wins. No generic ALLOW in v1.** OBSERVE_ONLY never stops evaluation or defeats a block. Restore access by narrowing/disabling the block. This replaces the earlier contradictory ALLOW/specificity design.

Connection blocks run first, request blocks next, then at most one terminating local-response/rewrite action by explicit user priority and UUID tie-break. Warn about conflicting transforms; no implicit chaining. Future exceptions must reference the specific local block being excepted. Clash DIRECT/allow rules are never WAYD exceptions.

## Enforcement contract

1. Validate/persist a candidate revision, compile a complete immutable native snapshot, then acknowledge activation. Keep the last working snapshot if installation fails; show pending/error state. No partially installed policy.
2. App/IP gates precede upstream dial or proxy dispatch. Visible-host rules require bounded client-prefix inspection before dialing; reuse the 16 KiB budget with a finite timeout. Current concurrent dialing must change on this path. Do not indefinitely stall server-first protocols. Late/missing ownership and names follow the explicit coverage policy above.
3. Block through protocol-correct local closure; prove TCP reset/close and UDP behavior instead of assuming a reset helper exists. Matching plaintext DNS can receive a short-lived negative answer, with caching/scoping documented; DNS filtering never substitutes for connection enforcement.
4. Request blocks/local replies withhold the matched request. HTTPS setup may already contact the destination. Response rewrite necessarily follows an upstream response; never claim it prevented that request leaving.
5. Store actual disposition, rule UUID/revision, stage, unknown/unsupported reason and route separately from risk findings. Batch local hit updates; no per-packet DB writes or release logs containing hosts/payloads.
6. Required enforcement gate errors fail visibly rather than silently allow. Preserve last active policy on compile/reload failure. Ordinary missing host is the coverage case, not a policy-engine crash. Existing MITM passthrough fallback must not bypass an active content-enforcement rule; fail that selected flow visibly.

## Low-power customization

Recommended progression; battery savings require measurement:

| Feature | Cost / recommendation |
|---|---|
| App/host/IP block | Cheapest: once per flow, no decryption/body work. Retries can still consume power; expose retry counts and Undo. |
| LOCAL_RESPONSE: fixed 204, {}, [], or bounded custom JSON/text/status/type | Selected app + host + method/path; respond locally instead of forwarding the request. Avoids upstream response download, though TLS setup can still cost power. Empty responses may break clients; preview/test. |
| Header set/remove; whole response replacement | Selected research HTTP only. Replacement still downloads the original response; use LOCAL_RESPONSE when the real server is unnecessary. |
| JSON Pointer set/delete; literal text replacement | Later optional tier for complete small UTF-8 JSON/text. Deterministic operations, no broad scanning of every page. |
| Arbitrary scripts, injected HTML JavaScript, per-request AI | Excluded initially: unpredictable execution/CPU and larger attack surface. Static user-authored templates cover common customization. |

For HTML, consider a later narrow literal replacement on known small documents, not a script runtime or injection on every page. No remote replacement fetches, polling or system-wide decryption for this feature. Captured strings are never executable template instructions.

Proposed initial budgets: 1,000 enabled rules; regex source <=2 KiB plus compiled-size cap; local/replacement body <=64 KiB; decoded input <=128 KiB per selected response; two concurrent transforms and <=256 KiB combined decoded input; finite body wait (initial 2 s); bounded JSON depth/replacement count. Filter app/host/path before allocating bodies. Index exact/suffix/prefix predicates; compile only on edits. No idle periodic work; batch counters and invalidate session decision caches on revision changes.

First rewrite version: complete identity-encoded HTTP/1.1 only, correctly framed fixed/chunked input, no conflicting lengths. Recompute generated Content-Length, remove stale validators/digests/range metadata and use no-store. Respect HEAD/1xx/204/304: 204 has no body or Content-Length. Reject CR/LF header injection and framing-header edits. Compression, range responses and signed bodies need later explicit support. If selected enforcement exceeds limits, times out or encounters unsupported encoding/malformed framing, terminate visibly; never quietly deliver the original and claim success. Preview works on a copy. Keep original evidence and a bounded action summary, not a second unlimited payload store.

Per-app consent revocation disables its content controls and releases interception resources. Standard flavor implements app/host/IP only and never references research MITM implementation. No script VM, AI call or new privileged shell API is required.

## Rule backup and restore

WAYD JSON is the lossless format, separate from Clash conversion. Explicit Android document-picker export only; a chosen provider may be cloud storage. No automatic sync. Proposed envelope (not an implemented importer):

```json
{
  "format": "wayd-policy",
  "schemaVersion": 1,
  "rules": [{
    "id": "9ce10f14-7d24-4496-b8b1-d5963450c027",
    "revision": 1,
    "enabled": true,
    "app": { "packageName": "org.example.app", "signingSha256": [] },
    "lifetime": "persistent",
    "match": { "hostExact": "metrics.example.com" },
    "action": { "type": "BLOCK" }
  }]
}
```

Populate signing identity from verified installed-package metadata before activation. Empty/unavailable identities require binding review. Map user/profile on restore; Linux UIDs are not portable identities. Missing packages, certificate mismatches, shared-UID conflicts and unverified signing-key rotations remain inactive pending review.

- Include persistent rule definitions, priority, labels and bounded static templates; preserve disabled state. Exclude session rules, traffic/history, counters, device identifiers, UID bindings, AI outputs, grants/ConsentRecords, CA/private keys, database keys and proxy credentials/subscription URLs.
- Hosts/paths/query matches/replies may contain secrets. Show export selection and redact sensitive values by default, marking redacted rules incomplete/inactive on restore. Explicit include-sensitive-values is required for a full-fidelity backup. Do not call a redacted file lossless. Password-encrypted backup is a later reviewed format, not home-grown encryption or a current promise.
- Bound input (initial 4 MiB/1,000 rules plus field/body caps). Reject duplicate JSON keys/IDs, invalid predicates/headers, unknown executable actions and unsupported schema versions before writing. No archives, remote includes, code or filesystem references.
- Dry-run shows additions/conflicts/unsupported/missing-app counts and scope changes. Default merge keeps existing UUIDs/revisions; replacement/copy is explicit. Copy assigns new UUIDs. Apply an approved valid batch atomically with rollback to the prior active snapshot; never silently widen scope.
- Imported rules start inactive pending scope/identity review. Content controls require fresh per-app consent on this install. Imported consent cannot grant privileges. Retain provenance, offer Undo; no auto-start VPN, proxy connection or CA installation.

## Clash boundary and implementation slices

[Optional routing](14-OPTIONAL-ROUTING.md): canonical WAYD enforcement precedes routing. Only allowed streams/datagrams reach an adapter, with minimum live metadata, no DB/history/AI/CA access. Proxying necessarily carries permitted application bytes; it does not require inspection-store access. Core blocking works without Clash.

Mihomo supports Android package matching and typed host/IP rules, but routing YAML cannot express WAYD consent, temporary lifetimes or response edits. Keep lossless backups in WAYD JSON. A future compatibility exporter lists exact translations and omissions. Never flatten app AND host into two broad OR rules or export global REJECT for a per-app block. Unsupported features stay visible, never silently disappear.

1. Canonical app/host/IP policy, native matcher/shared fixtures, DAO/migration, per-app consent, atomic activation, session expiry, active-flow termination and disposition evidence. Both flavors. No working-looking buttons before enforcement exists.
2. History controls and rules manager, bilingual strings, Undo, preview against retained history and explicit JSON backup/import. Missing evidence stays unknown.
3. Research request-time local replies, then bounded response replacement and revocation/error tests. Passive capture remains unchanged for unaffected flows.
4. Routing adapter/Clash integration: pin/review engine licensing/API, Android package metadata, stream handoff, DNS and IPv6 before release. **GeoIP follows Clash integration**, per Nat.

Acceptance: blocked pre-dial app/IP and visible-host TCP/UDP fixtures never reach a local test server. Cover fragmented SNI, unknown names/owners, shared UID/IP, IPv6, active flows, restart/profile reload, session vs durable lifetime, snapshot rejection and import conflicts/identities/no grants. HTTP tests cover framing/HEAD/204, unsupported encoding, budgets, pipelining, evidence retention and fail-closed content rules. Both flavors build without standard-to-MITM dependencies. Compare idle/typical capture/block/local-reply/rewrite CPU, memory, wakeups and battery on device before energy claims; do not disrupt Nat's main VPN during unattended tests.

## Technical sources

- [Android VPN guide](https://developer.android.com/develop/connectivity/vpn): one active VPN service per user/profile.
- [Mihomo route rules](https://wiki.metacubex.one/en/config/rules/): Android package matching and typed host/IP ordered routing; derivatives may differ.
- [Rust regex untrusted input](https://docs.rs/regex/latest/regex/#untrusted-input): bounded compilation/search and no lookaround/backreferences. Pin a reviewed version during implementation.
- [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html) and [RFC 9112](https://www.rfc-editor.org/rfc/rfc9112.html): HTTP semantics/framing for future rewriting.
