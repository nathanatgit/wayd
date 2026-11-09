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
