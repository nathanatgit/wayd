# 14 · Optional proxy routing

## Current implementation · 2026-11-10

An initial Mihomo v1.19.33 transport is now implemented beneath WAYD's VPN,
disabled until explicit profile import, per-app consent and enablement. It is a
bounded Clash subset, not arbitrary Clash/FlClash profile compatibility. See
[supported fields and build/source notices](../routing/README.md). SOCKS5 and HTTP
CONNECT, static first-entry select groups, package/exact-domain/domain-suffix/IP
rules and explicit final MATCH are supported. No subscriptions, health probes,
controllers, scripts, custom filesystem references, engine TUN or sniffing. Proxy
bootstrap addresses must be numeric IPs; there is no bootstrap DNS resolver.

Local blocks gate both TCP and UDP before adapter dispatch; TLS passthrough
fallback uses the same adapter. Cancelled flows cancel pending proxy handshakes.
The private C API passes only live numeric destination/protocol, approved owner,
visible host and permitted bytes through Unix socketpairs. No listening port,
inspection-store handle or CA key crosses that API. The library shares WAYD's
UID; this is an API boundary, **not OS isolation**.

Controls imports a local document, requests app selection/consent, encrypts the
profile with Android Keystore AES-GCM in no-backup private storage, and saves it
disabled. Enablement and edits require stopping WAYD. Delete destroys the file
and key and revokes routing grants. Grants bind current profile/package/signers;
package changes stop the routed VPN and startup revalidates all grants. Bad
enabled configuration fails startup. Proxy failure never retries Direct.

Only selected, attributable single-UID apps use profile routing. Unselected,
unknown and shared-UID flows use the normal Direct relay, explicitly disclosed.
DNS remains on the underlying network unless an attributable selected flow is
routed; no encrypted-DNS/fake-IP/leak-protection claim. Existing IPv6 coverage
gaps remain. Home/Controls show observed Direct/Proxied/Blocked/Error transport
outcomes, including reconnect/fallback attempts, without storing routing history.

Both app flavors build/lint. Android-native loopback tests cover the actual
Mihomo/C/Rust boundary, block precedence, proxy failure, cancellation, TCP/UDP and
proxy half-close. Installed-app document-picker, Keystore/consent and end-to-end
VPN routing acceptance remain pending. Research response controls, full profile
compatibility, GeoIP and a complete release distribution audit remain open.

The requirements and earlier design text below remain the target contract;
references to a planned/unimplemented extension describe the earlier baseline
and are superseded by the implementation status above.

WAYD is an on-device traffic inspector and control tool. Core inspection and planned M7 blocking work independently of proxy routing. Clash/FlClash-style features belong to an optional extension, disabled by default. This document defines requirements, not an existing integration; engine, packaging and API choices remain open.

## Privacy promise

WAYD inspects traffic and analyzes findings on your Android device. It never automatically uploads captured traffic, inspection logs, reports, or AI analysis. You can explicitly export data to a destination you choose. Apps still communicate with their destinations; user-initiated model/database downloads also use the network. A planned, opt-in routing extension will send traffic selected by routing rules through your configured proxy.

Proxy operators can observe connection metadata and any application traffic not protected end-to-end. Proxy routing is not a guarantee of anonymity. Downloads, profile/subscription updates and proxy health checks contact external hosts; disclose destinations and require user authorization, including a separate choice for scheduled updates. No telemetry or inspection-data uploads.

## Ownership and request order

1. WAYD owns the single Android VPN and attributes the flow to an app.
2. WAYD evaluates applicable app/host/IP blocks before any upstream connection or proxy dispatch. A routing profile cannot override a WAYD block.
3. For an allowed flow, choose Direct or the explicitly configured proxy route. An extension receives only the allowed connection and minimum routing metadata; no capture history, reports, AI outputs, CA keys, or database access.
4. Content rules requiring decryption run locally before forwarding the matching request content. TLS setup may already have reached the destination; do not claim content blocking prevents all network contact. Do not give the extension separate decrypted inspection copies.
5. Record the actual route as Direct, Proxied or Blocked, with route failures shown explicitly. Failed proxy traffic must not silently go direct: fallback requires explicit opt-in.

A separate FlClash VPN cannot run alongside WAYD's VPN. Integration must operate beneath WAYD's VPN ownership. No extension API or process-isolation guarantee is implemented yet.

The implemented Home dashboard starts/stops the WAYD VPN independently of its Record traffic toggle. Its Clash routing switch is disabled and explicitly unavailable. Future routing must use this optional switch under the same WAYD VPN; it must continue to respect blocks when recording is off and must not enable history persistence or another VPN implicitly. No routing implementation is implied by the dashboard placeholder.

## Configuration and secrets

Treat imported profiles as untrusted configuration. They cannot weaken WAYD rules, export local data, run arbitrary commands, or enable remote administration. Keep proxy credentials and subscription URLs out of logs and default exports; protect stored secrets with Android Keystore-backed encryption. Define deletion and revocation controls before implementation. DNS routing and proxy bootstrap resolution must be explicit; test IPv4, IPv6 and DNS leak behavior rather than claiming blanket protection.

## Acceptance before shipping

- Core recording/analysis and implemented blocking work with the extension absent.
- Imported allow/direct rules cannot bypass a WAYD block, including reconnects and profile reloads.
- Proxy failure causes a visible failure without direct fallback unless the user opted in.
- Route labels reflect observed outcomes; DNS behavior and coverage gaps are disclosed.
- Routing cannot query inspection storage or receive CA keys; verify the implementation boundary.
- Profile fetches, scheduled refreshes and health checks respect authorization; credentials stay out of logs/exports.

Implement and validate core M7 blocking first. Engine licensing, isolation, supported protocols, DNS policy and update verification remain implementation decisions.

## Proposed M7 adapter contract (2026-11-10)

This is an implementation target, not an existing API. [13-BLOCKING.md](13-BLOCKING.md) owns rule semantics, lifetime, content controls and portable backups.

- WAYD validates and activates a versioned immutable policy snapshot independently of a routing profile revision. App/IP and evaluable hostname gates run before upstream dial; this requires changing the current relay's concurrent pre-inspection dialing. Profile reload, engine restart and reconnect cannot discard persistent local policy. Session rules expire only under their documented lifetime.
- A transport adapter accepts only policy-approved TCP streams/UDP datagrams, destination and protocol, required DNS context, and resolved package/user metadata when needed for routing. Require an explicit capability/version handshake, route result and error result; transport failure never silently opens a Direct socket. No stored captures, reports, AI data, CA keys or database handles cross the boundary. Permitted application bytes necessarily traverse the selected proxy transport.
- TCP and UDP creation, IPv4/IPv6, cancellation, flow closure, protected upstream sockets and DNS/bootstrap behavior are explicit adapter capabilities. Disable the engine's TUN/VPN ownership and duplicate interception/sniffing. A generic SOCKS handoff alone does not convey Android package metadata: package-aware routing needs a supported metadata API or WAYD-side route selection. Do not claim PROCESS-NAME compatibility until tested with the chosen engine/API.
- Hostname availability and provenance stay visible. A profile must not trigger extra DNS lookups merely to reinterpret a locally blocked target. DNS responses, cache scope and proxy bootstrap need leak tests. Content matching/replacement stays in WAYD's supported research HTTP path; the adapter is not granted a second decrypted inspection feed.
- Keep engine choice replaceable: profile parser, route evaluator and transport are distinct capabilities, not a promise that importing any Clash YAML provides all features. Reject unsupported security-relevant options explicitly. Pin the exact engine build/API and review its license and redistribution obligations before packaging; process separation alone is not a licensing decision or an isolation guarantee.

## Backup versus compatibility export

WAYD JSON preserves persistent definitions, app identity, priority and bounded replacement templates; consent/secrets/history/session rules are excluded as specified in [13-BLOCKING.md](13-BLOCKING.md). Routing profiles and credential backups remain separate explicit exports. Imported routing configuration cannot activate content modification or grants.

A future Clash exporter is a labelled subset conversion, not a lossless backup. Host exact/suffix and IP/CIDR can translate where the selected dialect has equivalent semantics; per-app conjunctions require equivalent logical matching and verified Android package metadata. Never split app AND host into independent reject rules. Show a conversion report for temporary rules, response edits, consent bindings, regex differences and all other unsupported semantics; never silently drop them. DIRECT in a converted or imported profile never bypasses canonical local blocks. Refer to the chosen engine's actual [Mihomo rule semantics](https://wiki.metacubex.one/en/config/rules/), rather than assuming all Clash derivatives agree.

GeoIP implementation remains scheduled after Clash integration, per Nat's instruction.

## Extension resource and GeoIP continuation (2026-11-10)

A native Compose optional extension now provides Dashboard, Profiles, Proxies, Connections, Rules, Events and Settings pages. Local picker accepts downloaded YAML irrespective of MIME. Encrypted multiple profiles, explicit subscription import, group/mode selection and app-consent review are implemented. The extension bundle accepts bounded anchors, upstream adapter protocols (including the user's SS/VLESS/VMess/Hysteria2/TUIC nodes), supported upstream groups, YAML/text domain/IP/classical providers and shared MMDB GEOIP rules. This supersedes the initial subset above only for extension bundles; the original strict legacy transport remains available.

Resources are downloaded only on explicit import/update. The UI displays provider hosts and lets the user choose direct download or the first embedded proxy node, with no Direct fallback. GeoIP source is editable. One private validated country.mmdb serves routing and local Flow.country enrichment, even with proxy routing disabled. WAYD app flow details display the code. MMDB routing categories such as CLOUDFLARE are not presented as countries. No remote country lookup, inspection upload or new permissions.

This remains partial FlClash parity: no actual Flutter UI, MRS/GEOSITE/ASN/subrules support, full profile DNS/fake-IP behavior, persistent subscription updates, complete live connection controls/event stream or automatic health scheduling. Explicit probe API exists; UI is not yet complete. Profile DNS/TUN/listener settings are overridden and disclosed because WAYD retains VPN ownership. Device acceptance of picker/download/consent/live VPN is still required. Do not describe this as full FlClash compatibility.

## Access control modes (2026-11-10, D-045)

Importing a profile no longer asks for apps. Access control is a separate extension page, FlClash-style:

- **Whitelist:** only checked apps are routed. Each gets a signer-bound `routing-v1:<user>:<pkg>:<signer>` consent (statement 1); replacing or removing one of them while enabled stops the VPN.
- **Blacklist:** every single-package app of the current Android user is routed except the checked ones, including apps installed later. One global consent `routing-v1:<user>:*blacklist` (statement 2) is recorded; excluded apps need none. Package changes do not stop the VPN.
- Both: unknown or shared-UID owners stay Direct, other Android users are never routed, and local WAYD blocks are evaluated before routing. Native selection receives `!exclude:<user>` as the first owner line in blacklist mode (`RouteSelection` in `capture/native/src/blocking.rs`).
- The app picker uses WAYD's inventory: installed apps, apps seen in recorded traffic first, system apps behind a toggle.

Storage: `no_backup/routing/index.enc` (small JSON index) and `profile-<id>.enc` (one bundle each), AES-256-GCM with a random data key wrapped by the Keystore key `wayd-routing-v2-wrap`. The previous single `routing-v1.enc` (whole 30+ MB file encrypted through the Keystore on every read and write, which made Save take minutes) is migrated once and deleted; migration resets `enabled` to off.
