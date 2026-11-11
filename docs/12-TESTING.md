# 12 · Testing

Commands and device rules: [HANDOFF.md](HANDOFF.md#build-and-test-windows). Latest counts live there, not here.

## Pyramid

| Level | What | Tools |
|---|---|---|
| Unit | Parsers, rules, scoring, schema validator, command renderer, policy, portable JSON | JUnit5, Kotest, fixtures |
| Fuzz | DNS/TLS/HTTP/AXML/DEX parsers, Clash profiles | Jazzer / cargo-fuzz / `go test -fuzz=FuzzProfile` |
| Native | Relay, blocking, lifecycle, MITM, routing C ABI | `cargo test`, Android-native executables on device |
| Integration | Room DAOs, migrations, collectors with recorded `dumpsys` | Robolectric / instrumented |
| Device | Capture correctness, OEM survival, performance | Real devices |
| AI | Schema compliance, evidence validity, injection resistance | Golden fixtures on device; opt-in live Ollama test |

## Ground-truth capture (most important)

1. Linux laptop as Wi-Fi AP (hostapd) or a router with tcpdump.
2. Phone on that AP, WAYD recording, Always-on + lockdown on.
3. Scripted 30-min usage session.
4. Every (dst IP, port, approx time) in the router pcap must map to a WAYD flow or a known bypass (connectivity check, NTP…).
5. Store the diff in `testing/ground-truth/<device>-<date>.md`. Repeat per family: Pixel, HyperOS, ColorOS, OriginOS, Nubia/ZTE, Huawei.

Not done yet: no router capture has been supplied, and the test Wi-Fi had no IPv6 route.

**Local measurement:** after the user chooses to export a capture JSON, `powershell -File scripts/Measure-Capture.ps1 -Path captures/session.json`. It prints aggregates only (package names vs UID-only/unknown, panel denominator and all TCP); still treat output as private and keep raw files in ignored `captures/` or `build/`. The in-app coverage panel aggregates all stored flows, so it is not a per-session result.

## Area checks

- **Recording toggle:** JVM tests prove policy callbacks run without persistence while off, flows spanning a toggle are excluded, new flows resume. Device: forwarding and blocking with recording off, session rule survives toggles and expires at VPN stop (verified). Still open: DB row counts not growing while off, spanning-flow exclusion on device.
- **Blocking:** local TCP/UDP servers verify no pre-dial contact, fragmented host gating, live-flow closure with byte counts kept. Device: disposable `.blockprobe` against a PC fixture (server count stops while blocked, resumes after Undo). Lifecycle regression `engine::lifecycle_tests::stopping_blocked_tcp_releases_tun_duplicate` (12 cycles × 32 TCP opens, Stop racing pending/blocked flows, no leftover descriptor or `wayd-net` worker). After every installed-app stop: TUN descriptors 0, no VPN in `dumpsys connectivity`.
- **APK scanner:** `:apkscan:testDebugUnitTest :privilege:testDebugUnitTest :apkscan:lintDebug`. Device: scan, reopen from Apps and app detail, repeat for cache hits, APK update invalidates, cancel keeps completed packages. Formal acceptance needs comparison against the current-user inventory (shell saw 535, app discovered 22) and a cold full scan time.
- **Clash routing:** `go test ./...` (hostile profiles, metadata, Mihomo HTTP CONNECT/SOCKS5, no Direct fallback) and the profile fuzzer. Android-native Rust tests cover the real C ABI/socketpair, TCP/UDP (incl. IPv4-mapped source normalisation), half-close, pending-dial cancellation, block precedence, no Direct after proxy errors, frozen route metadata across late-host fallback. After D-049 add: `core` has no `libwayd_route.so` or Clash classes (inspect the APK), and `core` installed over `clash` starts with routing off.
- **Routing + decryption:** `android_mihomo_routing_and_https_decryption_capture_both_messages` (relay → Mihomo → TLS origin, both MESSAGE events, `TLS_INTERCEPTED`, Proxied). Opt-in `android_live_google_via_mihomo_is_decrypted` (`--ignored`): set `WAYD_LIVE_ROUTING_PROFILE` (private bundle) and `WAYD_LIVE_GOOGLE_DESTINATION` (fresh Google IPv4:443); requests public `/robots.txt` only. Private fixtures via `WAYD_PRIVATE_LIVE_NODE_DIR`, `WAYD_PRIVATE_PROFILE`, `WAYD_PRIVATE_BUNDLE`, `WAYD_PRIVATE_GEOIP`. Never commit or print them; delete credential bundles from the device afterwards. Passed natively on NX733J with the full user profile; installed-app acceptance still open.
- **Exports:** PCAP checked for magic/version/linktype 101, SYN + RST/ACK for blocked flows, valid IP/TCP checksums.

## Fixtures

- `fixtures/sessions/*.json` — synthetic sessions for rule tests.
- `fixtures/dumpsys/<oem>-<android>/{appops,alarm,netstats,package}.txt` — real outputs (anonymised).
- `fixtures/http/*.http` — cleartext requests with identifiers in plain, URL-encoded, base64, hashed forms.
- `fixtures/injection/*.txt` — payloads attempting to instruct the model.
- `testapps/leaky` — sends Android ID in cleartext, app list over HTTPS, location after Wi-Fi scan, periodic beacon.

## Performance

- Macrobenchmark: report open time, app list scroll.
- Throughput: iperf3 over the tunnel; CPU and battery for 1 h idle + 1 h streaming. No battery claim has been measured yet.
