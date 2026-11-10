# Optional routing transport

Initial local implementation, not full Clash configuration compatibility. WAYD
owns the Android VPN and evaluates local blocks before this transport receives
any flow. The engine is pinned to **Mihomo v1.19.33** with module hashes in
`go.sum`; the C transport ABI is version 1, and the Kotlin/Rust start ABI is 2.

## Supported profile subset

- Top-level `proxies`, `proxy-groups`, `rules` only; UTF-8 YAML, at most 256 KiB,
  64 proxies, 64 groups, 1,000 rules, bounded nesting/nodes. No aliases/anchors,
  duplicate keys, extra documents or unknown fields.
- SOCKS5 (optional TLS/auth/UDP), HTTP CONNECT (optional TLS/auth; TCP only).
  Numeric proxy IPs only; TLS certificate verification cannot be disabled.
- Static `select` groups use their first listed entry, with no automatic probing,
  fallback or rotation. Group cycles are rejected.
- Ordered `PROCESS-NAME` (Android package supplied by WAYD), `DOMAIN`,
  `DOMAIN-SUFFIX`, `IP-CIDR`, `IP-CIDR6`, final mandatory `MATCH`.
  CIDRs match existing destination IPs without DNS; optional `no-resolve` accepted.
- `DIRECT` / `REJECT` / named proxies or groups as targets. No GEOIP, rulesets,
  subscriptions, controllers, scripts, TUN, sniffing, custom cert/key paths or DNS
  configuration. Unsupported options cause rejection of the whole profile.

```yaml
proxies:
  - name: chosen-proxy
    type: socks5
    server: 192.0.2.10 # Replace this documentation address with your server IP.
    port: 1080
    udp: true
proxy-groups:
  - name: selected
    type: select
    proxies: [chosen-proxy, DIRECT]
rules:
  - PROCESS-NAME,com.example.app,selected
  - DOMAIN-SUFFIX,example.net,selected
  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve
  - MATCH,selected
```

Only apps explicitly selected and granted consent in WAYD are routed by the
profile. Other apps and unknown/shared-UID owners use the ordinary Direct relay;
the UI discloses this. Each grant binds current Android user/package/signers.
Package changes stop an active routed VPN; startup revalidates bindings/grants.
Routing settings can only change while stopped. A bad enabled profile fails
startup; a proxy error never opens a Direct socket. Profile selection does not
enable inspection-history recording.

DNS stays on the underlying network unless an attributable selected flow is
explicitly routed. No fake-IP, encrypted-DNS or DNS-leak protection claim.
Transport uses numeric destinations from the VPN, not a second hostname lookup.
Existing underlying-network IPv6 coverage limitations still apply.

Profiles are encrypted with an Android Keystore AES-GCM key in no-backup app
storage. Import saves disabled and requires fresh per-app consent. Delete removes
the profile/key and revokes routing grants. No credential export, profile fetching
or update/health timer exists. Transport errors are fixed strings and upstream Go
logging is discarded. There is no local listening port or remote controller.
The library shares WAYD's UID: the API has no inspection/CA/database handles, but
this is **not OS process isolation**. Do not describe it as a sandbox.

## Build and verification

Android arm64, API 29, NDK 29.0.14206865; Go 1.27.3. The Gradle
`:capture:buildRouting` task uses `-PgoExecutable=<path>` or ignored
`build/tools/go/bin/go[.exe]`, then PATH. No SDK bootstrap/download runs in app code.
Both flavors package `routing/build/arm64-v8a/libwayd_route.so` alongside their
separate standard/research Rust libraries. `-PskipNative` reuses these outputs.

From this directory, `go test ./...` checks hostile profiles, metadata, matching,
actual Mihomo HTTP CONNECT forwarding, Direct TCP/UDP, and no Direct fallback.
`go test -fuzz=FuzzProfile -fuzztime=10s` exercises the bounded parser.
Android-native Rust tests additionally exercise the actual C ABI/socketpair,
pending-dial cancellation, half-close, local-block precedence and failed-proxy
relay. They require `libwayd_route.so` on `LD_LIBRARY_PATH`. No VPN/screen unlock
is needed for those loopback fixtures. Device document-picker, Keystore/Room
integration and installed-VPN routing acceptance are separate checks.

## Source and notices

WAYD and the adapter are GPL-3.0-or-later. Mihomo v1.19.33 is GPLv3:
[pinned source](https://github.com/MetaCubeX/mihomo/tree/v1.19.33),
[upstream license](https://github.com/MetaCubeX/mihomo/blob/v1.19.33/LICENSE).
The repository root `LICENSE` contains the GPL text. Preserve these notices,
the pinned source/module manifest and corresponding build sources when
redistributing. `go.mod`/`go.sum` record transitive dependencies; this note is not
a claim that a complete release distribution/license audit has been performed.

## Native extension bundle and shared GeoIP

See D-042 and docs/14-OPTIONAL-ROUTING.md for the continuation beyond the legacy subset. WAYD-CLASH-1 bundles contain source, supplied resources, selected group members, mode and optional explicit probe measurements. Private C command/free exports provide bounded inspect/prepare/configure/download/latency and GeoIP-load/lookup operations; no controller socket is opened. One immutable MMDB reader is shared by routing and country enrichment. The extension uses upstream indexed domain/IP provider strategies; compiled RULE-SET entries preserve top-level rule order rather than expanding into hundreds of thousands of linear rules. Unsupported MRS/GEOSITE/ASN/subrules fail closed. See extension_test.go for optional local private-profile/resource fixtures; credentials and downloaded assets must remain ignored under build/.
