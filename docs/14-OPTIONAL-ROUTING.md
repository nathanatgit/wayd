# 14 · Optional proxy routing (planned)

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
