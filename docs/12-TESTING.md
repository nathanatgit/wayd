# 12 · Testing

## M5 static scanner regression and device checks

Run `:apkscan:testDebugUnitTest :privilege:testDebugUnitTest :apkscan:lintDebug`, then build both app flavors. Parser tests cover UTF-8/UTF-16 XML pools, typed attributes, every truncated fixture prefix, invalid offsets/counts, defined versus referenced SDK classes, unsupported DEX versions, string/endpoint caps, host-only normalization and bounded stream reads. Package inventory tests reject malformed/injected names and oversized replies/counts. As of 2026-11-10: 13 scanner + 8 privilege tests pass; scanner lint has 0 errors and one merged-permission visibility warning.

Device smoke: scan discoverable apps, reopen a result from Apps and from an app-detail link, verify signer subjects/fingerprints and static coverage limits, then repeat to check cache hits. An APK update must invalidate its result. Verify cancel retains completed packages and does not alter VPN state. Formal M5 acceptance additionally requires comparing discovery against the current-user package inventory and timing a cold full scan; a fast visible-only sample is insufficient. NX733J evidence: 22 discoverable apps / 42 s, repeat 22 cache hits / 1 s; shell inventory 535. Final test-app update: 1 rescan + 21 cached / 5 s. Full-inventory/Shizuku app authorization remains open.

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

### Local capture measurement

After the user chooses to export a capture JSON, run `powershell -File scripts/Measure-Capture.ps1 -Path captures/session.json` from the repository root. Keep raw files in ignored `captures/` or `build/`; do not commit them. The analyzer separates package names from UID-only/unknown attribution and reports the existing coverage-panel denominator alongside all TCP flows. It does not emit destinations or payloads; package/port groups still describe app activity, so treat its output as private. A session's timestamp duration alone does not prove continuous recording or successful app use. Router comparison remains separate.

## Fixtures

- `fixtures/sessions/*.json` — synthetic sessions for rule tests.
- `fixtures/dumpsys/<oem>-<android>/{appops,alarm,netstats,package}.txt` — real outputs (anonymised).
- `fixtures/http/*.http` — cleartext requests containing identifiers in plain, URL-encoded, base64, hashed forms.
- `fixtures/injection/*.txt` — payloads attempting to instruct the model.

## Test apps

- `testapps/leaky` — sends Android ID in cleartext, app list over HTTPS, location after Wi-Fi scan, periodic beacon. Used for end-to-end acceptance.

## Dashboard / recording boundary (2026-11-10)

Latest JVM capture suite: 35 tests passed (FlowTracker 19, parsers 13, block targets 3). New recording tests prove owner/visible-host policy callbacks continue with no flow/DNS persistence while off, connections spanning recording changes are excluded, new connections resume recording, repeated same-mode setting does not invalidate a window, and decrypted messages from an invalidated window are not stored. These are JVM boundary tests, not an end-to-end Android enforcement acceptance claim.

Both standard/research debug APKs and app lint pass, plus capture lint. Evidence: ignored `build/dashboard-final-validation.log`; final accessibility labels/Home icon: `build/dashboard-ui-final.log`. On NX733J isolated `.m1test`, installed and checked dashboard cards, recording off→on→off, disabled/off Clash and navigation to saved rules. Final polish reinstalled successfully; final label recheck encountered secure keyguard. No VPN was started during dashboard smoke tests.

Next controlled device test: start WAYD with recording off, confirm probe forwarding and exact-host blocking while retained flow/DNS/message counts do not grow; toggle recording on and verify only newly opened/completed flows save; toggle off/on while a flow is open and ensure it stays excluded. A session rule must survive recording toggles and disappear on VPN stop; durable user rules must remain. Check VPN/TUN/worker cleanup after Stop. Existing session start/end metadata is expected even with traffic recording off. Use the disposable probe, preserve retained history and user rules, and recheck which app owns the VPN before switching. User performs the overnight soak manually; no new soak or battery result is claimed.

## Performance

- Macrobenchmark: report open time, app list scroll.
- Throughput: iperf3 over the tunnel; record CPU and battery for 1 h idle + 1 h streaming.

## 2026-11-10 Nubia M7 live fixture result

On NX733J, the isolated research debug app blocked the disposable probe's exact HTTP Host using a session rule created from app history. The local server count stayed at 23 during blocked attempts, then rose to 39 after Undo. Reapplying the rule and stopping capture removed the session rule and preserved BLOCKED history. Android nevertheless retained a connected VPN, one TUN descriptor and native workers after Stop. Shutdown acceptance FAILED; clean-process reproduction is pending an unlocked/open app because Nubia rejected the background launch. Force-stopping the test app released its VPN. Probe traffic and the PC fixture were stopped. This run does not establish durable/whole-app enforcement, profile/reinstall handling, battery cost, or full M7 acceptance.

### VPN lifecycle regression fixed (2026-11-10)

The prior shutdown failure is fixed by D-035. engine::lifecycle_tests::stopping_blocked_tcp_releases_tun_duplicate runs on Linux/Android and uses synthetic Unix datagrams, never upstream networking. Each of 12 cycles asserts 32 actual TCP opens, races Stop with pending or blocked flows, then requires the native descriptor duplicate and all wayd-net workers to disappear. It failed before the fix and passed afterward in both Android feature builds. NX733J native totals: standard 11/11, research 26/26. Both installed research-app service cycles also had TUN descriptors 2 before / 0 after, native/poll threads 0 after, VPN connections 0 after. Broader M7 device cases remain open.

Cross-compile the native test executable with cargo ndk -t arm64-v8a -P 29 test --lib --no-run (also --features mitm), push the generated executable from target/aarch64-linux-android/debug/deps to the device and chmod 700 after every push. Run from a dedicated writable directory under /data/local/tmp: the existing CA hash test writes relative target/mitm-test fixtures. A writable TMPDIR alone does not change that relative working directory. Remove test executables/fixtures afterward. Capture tests/lint and both fresh-native APK builds passed in build/m7-shutdown-build.log. No repeat of the manually owned overnight soak was needed or claimed.
