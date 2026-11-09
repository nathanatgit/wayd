# 12 · Testing

## Pyramid

| Level | What | Tools |
|---|---|---|
| Unit | Parsers, rules, scoring, schema validator, command renderer | JUnit5, Kotest, fixtures |
| Fuzz | DNS/TLS/HTTP/AXML/DEX parsers | Jazzer (JVM) / cargo-fuzz (if Rust) |
| Integration | Room DAOs, retention, collectors with recorded `dumpsys` outputs | Robolectric / instrumented |
| Device | Capture correctness, OEM survival, performance | Real devices |
| AI | Schema compliance, evidence validity, injection resistance | Golden fixtures run on device |

## Ground-truth capture (most important)

Verify WAYD against an external capture:

1. Linux laptop as Wi-Fi AP (hostapd) or a router with tcpdump.
2. Phone on that AP, WAYD recording, Always-on + lockdown on.
3. Run a scripted 30-min usage session.
4. Compare: every (dst IP, port, approx time) in the router pcap must map to a WAYD flow, or be explained as known bypass (connectivity check, NTP…).
5. Store the diff report in `testing/ground-truth/<device>-<date>.md`.

Repeat per device family: Pixel (AOSP), Xiaomi HyperOS, OPPO/OnePlus ColorOS, vivo OriginOS, Nubia/ZTE, Huawei (if available).

## Fixtures

- `fixtures/sessions/*.json` — synthetic sessions for rule tests.
- `fixtures/dumpsys/<oem>-<android>/{appops,alarm,netstats,package}.txt` — real outputs (anonymised).
- `fixtures/http/*.http` — cleartext requests containing identifiers in plain, URL-encoded, base64, hashed forms.
- `fixtures/injection/*.txt` — payloads attempting to instruct the model.

## Test apps

- `testapps/leaky` — sends Android ID in cleartext, app list over HTTPS, location after Wi-Fi scan, periodic beacon. Used for end-to-end acceptance.

## Performance

- Macrobenchmark: report open time, app list scroll.
- Throughput: iperf3 over the tunnel; record CPU and battery for 1 h idle + 1 h streaming.
