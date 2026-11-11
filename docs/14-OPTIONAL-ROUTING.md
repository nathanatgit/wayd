# 14 · Clash routing (`clash` variant)

Clash/Mihomo-style proxy routing is optional, off by default and shipped only in the **`clash` release variant** (D-049). Recording, analysis and blocking never need a proxy, account or subscription. Profile fields and build notes: [routing/README.md](../routing/README.md).

## Packaging (target, D-049)

| `core` variant | `clash` variant |
|---|---|
| VPN ownership, attribution, local blocks, decryption, recording | everything in `core`, plus Mihomo v1.19.33 (`libwayd_route.so`), profile import/storage, resource and GeoIP downloads, Clash screens (Dashboard / Proxies / Profiles / Tools / Access control) |
| `CLASH_EXTENSION` lookup finds nothing: no Clash rows, no country panel, `Flow.country` null | Clash code in `app/src/clash`; `main` reaches it through the intent lookup or a `core`/`clash` twin |

Same application id and key; installing one over the other keeps data. A routing profile left behind by `clash` is ignored by `core`. The transport stays the in-process C ABI over Unix socketpairs: an API boundary with no inspection, CA or DB handles, **not OS isolation**.

## Request order (unchanged by packaging)

1. WAYD owns the single Android VPN and attributes the flow. A separate FlClash VPN cannot coexist; it replaces WAYD's.
2. Local app/host/IP blocks run before any proxy dispatch, for TCP and UDP. Profiles cannot override them, including after reconnects or reloads.
3. Allowed flows of routed apps go to the selected proxy; everything else uses the normal Direct relay. The routing transport receives only the live stream/datagrams, numeric destination, protocol, approved owner package and visible host.
4. Decryption runs in WAYD over the routed stream; TLS fallback keeps the original route metadata. Routing never gets a decrypted copy.
5. Outcomes are shown as Direct / Proxied / Blocked / Error (counters, no routing history). **Proxy failure never retries Direct.**

## Access control (D-045)

- **Whitelist:** only checked apps are routed; each has a signer-bound `routing-v1:<user>:<pkg>:<signer>` consent. Replacing or removing one while enabled stops the VPN.
- **Blacklist:** every single-package app of the current user except the checked ones, including later installs, under one global consent `routing-v1:<user>:*blacklist` (AGENTS.md rule 9 exception). Native selection gets `!exclude:<user>` first.
- Both: unknown/shared-UID owners stay Direct, other Android users are never routed. The picker uses WAYD's inventory (seen-in-traffic first, system apps behind a toggle).
- Settings change only while WAYD is stopped; startup revalidates grants; a bad enabled profile fails startup. Delete removes profile, key and grants.

## Profiles, resources, GeoIP

- Profiles are untrusted input: bounded YAML (anchors bounded), strict rejection of unsupported security-relevant options, no scripts, controllers, listeners, engine TUN or sniffing. Profile DNS/TUN/listener settings are overridden and disclosed.
- Supported: SS/VLESS/VMess/Hysteria2/TUIC/SOCKS5/HTTP nodes, upstream group types, YAML/text domain/IP/classical providers (≤ 8 MiB each, source ≤ 4 MiB), shared MMDB GEOIP rules.
- Storage: `no_backup/routing/index.enc` + `profile-<id>.enc`, AES-256-GCM, data key wrapped by Keystore key `wayd-routing-v2-wrap`. The old single `routing-v1.enc` is migrated once (routing reset to off) and deleted.
- Resources download only on explicit import/update; hosts are shown; Direct (Android network stack) or via the first embedded node, no silent fallback. Optional jsDelivr mirror rewrites only public token-free raw GitHub URLs. Per-resource status, 25 s stall watchdog, 4 min cap, retry failed.
- One validated `country.mmdb` (editable HTTPS source) serves GEOIP rules and local `Flow.country`; categories like CLOUDFLARE are not shown as countries. An invalid update keeps the known-good reader.
- Credentials and subscription URLs never appear in logs or default exports.

## Limits (disclose, don't hide)

- DNS stays on the underlying network unless an attributable selected flow is routed; no fake-IP, encrypted-DNS or leak-protection claim. Existing IPv6 gaps apply.
- Proxy operators see metadata and any traffic not end-to-end encrypted; routing is not anonymity.
- Not supported: MRS/GEOSITE/ASN/subrules, full profile DNS, persistent subscription refresh, live connection close/event stream, automatic health probes (explicit probe API only), group `exclude-type` (currently stripped: implement or reject). Not FlClash-complete; no Flutter FlClash code is vendored.
- A future Clash *export* is a labelled subset conversion with a report of what was dropped; never split app AND host into independent reject rules. WAYD JSON backup ([13-BLOCKING.md](13-BLOCKING.md)) excludes routing profiles and secrets.

## Acceptance

- `core` builds, runs and passes tests; its APK contains no `libwayd_route.so` or Clash classes.
- `core` installed over `clash` starts with routing off and ignores the leftover profile.
- Imported DIRECT/allow rules never bypass a WAYD block; proxy failure is visible with no Direct retry.
- Route labels match observed outcomes; DNS behaviour and coverage gaps are disclosed.
- The transport API exposes no inspection storage, CA keys or DB handles.
- Downloads and probes happen only on explicit user action; credentials stay out of logs/exports.
- Device: import → access control → enable → live routed run with per-app consent, and decryption over a routed flow from the installed app. Native loopback and live Google tests already pass ([12-TESTING.md](12-TESTING.md)); installed-app runs do not yet.

## WAYD's own external AI calls (D-050)

When routing is enabled, the VPN is running and access control selects WAYD itself (whitelist: checked; blacklist: not excluded), external AI calls to public HTTPS servers follow the profile's rules for WAYD's package. The routing library dials the AI host **by name** through the chosen node and returns a socketpair; WAYD runs TLS and HTTP over it, so routing only sees ciphertext. A rejected host or failed node fails the analysis (`ext_route`) and is never retried Direct. LAN/private servers and plain `http://` always use the normal network. Legacy profiles do not support name routing and fail closed.
