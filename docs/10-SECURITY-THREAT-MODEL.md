# 10 · Security and threat model

## Local APK scanner (M5)

APK scanning is user-started WorkManager work. It reads installed base/split APKs through PackageManager paths, never executes their code, never extracts ZIP paths to disk, and never reads privileged APK directories. Already-granted Shizuku access may run the fixed read-only current-user package inventory call in `Command.ListPackages`; no generic command execution or automatic permission prompt is added. Binary XML/DEX readers bound sizes, counts and offsets; malformed/unsupported input remains a coverage limitation. Facts and certificate metadata stay in the existing SQLCipher store. Possible endpoints retain hosts only, without embedded credentials, paths or query values. No package names, parser exceptions or APK data are logged. Signer subjects and static signatures are untrusted evidence, not verified identity or behavioral proof.

WorkManager's merged `WAKE_LOCK` and `RECEIVE_BOOT_COMPLETED` permissions support OS scheduling and resumption of user-enqueued local scans; no automatic periodic scan or network destination is added. Its scheduler services use the library's system binding protections. Existing `QUERY_ALL_PACKAGES` permission is reused; a ROM may still restrict visibility.

WAYD holds the most sensitive data on the phone. Treat WAYD itself as a high-value target.

## Assets

1. Captured traffic, decrypted payloads, permission history (DB + blobs).
2. MITM CA private key.
3. Root capabilities (installed iptables/eBPF/modules).
4. The user's trust (honest reports).

## Threats and mitigations

| # | Threat | Mitigation |
|---|---|---|
| T1 | Another app or a backup reads WAYD's DB | SQLCipher + Keystore key; `allowBackup=false`; no exported providers; no world-readable files |
| T2 | Exported components abused (start/stop VPN, read data) | All components non-exported except launcher + VpnService (protected by `BIND_VPN_SERVICE`); no deep links that trigger actions |
| T3 | Captured content performs prompt injection on Gemma | `<data>` wrapping, masking, read-only tools, schema validation, evidence-ID checks, no privilege access from `ai` |
| T4 | Malicious payload exploits WAYD parsers | Parsers in memory-safe code where possible; fuzz parsers (DNS/TLS/HTTP/AXML/DEX) in CI; size limits everywhere |
| T5 | CA key theft enables interception | Per-install CA, encrypted key, delete on disable, short validity |
| T6 | Root command injection | Sealed `Command` allowlist, argument validation, no string shells; dev console behind Danger zone consent |
| T7 | WAYD leaks data itself | No automatic uploads of inspection data; no analytics/uploading crash SDKs. Separate app forwarding, explicit exports and user-initiated model/DB downloads from local inspection. Planned routing must follow docs/14. |
| T8 | Misleading report (false "clean") | Coverage panel; tiers; "unknown" verdicts; never claim completeness |
| T9 | Root state left broken after crash | RootStateJournal + restore on start; "Restore network" button |
| T10 | Supply chain (model file, tracker DB) | SHA-256 pinned in app release; signed manifest for updates |
| T11 | Exported pcap/JSON shared carelessly | Export warning; optional redaction of identifiers and bodies |

## Logging

- No payloads, identifiers or hostnames in logcat in release builds.
- Debug logs only in debug builds.

## Permissions WAYD requests (and why)

| Permission | Why |
|---|---|
| `BIND_VPN_SERVICE` (service) | Capture |
| `FOREGROUND_SERVICE` (+ type) | Keep recording alive |
| `POST_NOTIFICATIONS` | Recording notification, report ready |
| `QUERY_ALL_PACKAGES` | Attribute traffic and scan APKs |
| `PACKAGE_USAGE_STATS` (special access) | Foreground time, netstats |
| `INTERNET` | Forwarding traffic; model download |
| `RECEIVE_BOOT_COMPLETED` | Optional auto-start |
| Shizuku permission | ADB/root features |

No location, contacts, SMS or storage permissions. WAYD reads device identifiers only to match them in traffic, and only at the privilege level available.

## Permissions added with on-device AI (2026-11-09)

| Permission | Why | Risk / mitigation |
|---|---|---|
| `FOREGROUND_SERVICE_DATA_SYNC` (+ `INTERNET`, already declared) | Keep a 2-4 GB model download alive while the app is in the background. | The download starts only when the user taps Download, goes only to the host the user chose, is SHA-256 verified and can be cancelled. No other AI-related network use. |

The captured data is masked (identifiers replaced by type tokens), wrapped in inert `<data>` blocks and size-bounded before it reaches the model; model output is schema- and evidence-validated and can never change rules, blocks, consents or privileges.

## Permissions added in M1 (2026-11-09)

| Permission | Why | Risk / mitigation |
|---|---|---|
| `INTERNET` | The userspace stack relays the user's own traffic to its real destination. Separate from user-initiated model downloads. | Review relay and download network use separately; optional routing is still planned. The app excludes itself from the VPN (`addDisallowedApplication`) so relay sockets bypass the tunnel. |
| `BIND_VPN_SERVICE` (service) | Declared on `WaydVpnService`; only the system can bind. | User must accept the system VPN consent dialog. |
| `ACCESS_NETWORK_STATE` | Read the underlying network type (Wi-Fi / cellular) and resolve connection owners. | Read-only; no data leaves the device. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SYSTEM_EXEMPTED`, `POST_NOTIFICATIONS` | Keep recording alive and show a persistent "recording" notification with a Stop action. | Notification permission is optional; recording works without it. |
| `QUERY_ALL_PACKAGES` | Map connection UIDs to package names for every installed app. | Sideload distribution only; a store build needs a policy declaration. Package names never leave the device. |

Native code (`capture/native`) parses no payloads; it reports only bounded prefixes (2 KB TCP per direction, DNS datagrams, first UDP datagram) to Kotlin, where parsers are size-limited and treat input as hostile.

**Debug-only intent extra:** `WaydVpnService` honours `--es exclude <cidr,...>` to keep prefixes out of the tunnel, but only when the APK is debuggable (`FLAG_DEBUGGABLE`) and the service is not exported. Release builds ignore it.

## Planned optional routing threats

The extension is not implemented. Before shipping, enforce WAYD blocks before routing, isolate inspection stores/CA keys, validate untrusted profiles, protect proxy credentials and subscription URLs, disclose proxy/DNS visibility, and prohibit silent direct fallback. Authorize subscription refreshes and health checks explicitly. Existing local-only statements concern inspection data, not ordinary forwarded traffic or explicit exports. See [14-OPTIONAL-ROUTING.md](14-OPTIONAL-ROUTING.md) for acceptance checks.

### Initial routing implementation superseding the planned baseline · 2026-11-10

No new Android permission. Existing INTERNET also permits explicitly imported
numeric proxy endpoints chosen by the user; these are new user-configured network
destinations. No default endpoints, automatic profile fetch, DNS bootstrap,
subscription update, health probe or remote administration. Only selected apps'
permitted bytes reach those endpoints, after local block gates. Proxy operators
can observe metadata/plaintext; no anonymity or DNS-leak protection promise.

Mihomo v1.19.33 outbound adapters run under the same UID. A strict bounded YAML
subset rejects unknown fields, external resources, file/certificate/key paths,
scripts, controller/listener/TUN/DNS settings and insecure certificate bypass.
The API carries minimum live metadata/bytes, not DB/history/AI/CA handles. This
does not create OS isolation from app files; do not claim it does. Upstream logs
are discarded and transport errors have fixed, nonidentifying text.

Profiles are AES-GCM encrypted with Android Keystore keys in no-backup private
storage, excluded from rule backup/export. Explicit per-app ConsentRecords bind
user/package/signers; shared UIDs are not grantable. Import saves disabled;
changes require stopped VPN. Missing/changed consent or identity in an enabled
profile fails startup. Package changes stop active routing. Delete revokes
routing grants even if the encrypted profile is damaged and deletes its key.
No automatic Direct fallback follows proxy failure. Unknown/unselected traffic
continues Direct under the clearly disclosed initial scope. DNS and IPv6 need
broader installed-VPN/leak acceptance before any protection claim.

## M7 core controls · 2026-11-10

No new permissions or network destinations. Rules require explicit per-app confirmation and a versioned ConsentRecord, bind to current Android user, package and installed signer fingerprints, and reject shared-UID/unknown identities. Legacy unbound rules stay inactive. Temporary controls live only in engine-session memory; permanent controls run only while WAYD VPN runs. Package changes revalidate bindings; removal and a global rules list provide reversal. Missing names/owners are disclosed coverage gaps, not successful policy decisions. Snapshot validation/activation is bounded and atomic; a required gate failure closes its flow. Old native ABI versions fail startup.

Release logs must not contain target names, package identifiers or payloads. No response rewriting or imported grants exist in this slice. Future routing receives only permitted streams after WAYD policy; inspect [13-BLOCKING.md](13-BLOCKING.md) for pending acceptance cases. Capture manifest lint exceptions are scoped to the existing UID-attribution package permission and active-VPN foreground exemption; no exact-alarm permission is required for that VPN eligibility ([Android types](https://developer.android.com/develop/background-work/services/fgs/service-types#system-exempted)).

## Explicit extension resource downloads and shared GeoIP (2026-11-10)

The optional extension can fetch user-entered HTTPS subscriptions, declared HTTPS rule/proxy providers and a user-selected GeoIP MMDB source. The default GeoIP source is https://raw.githubusercontent.com/MetaCubeX/meta-rules-dat/release/country.mmdb. Downloads are initiated in the UI, bounded, HTTPS-only across redirects, and never include inspection stores. Resource hosts are displayed before import. An explicit bootstrap choice downloads through the first embedded proxy node; failure never retries Direct. Ordinary downloads and proxy hostname bootstrap can use underlying-network DNS. No scheduled refresh, telemetry, controller or listening port is started.

The shared GeoIP file and source/checksum metadata stay in app-private no-backup storage. Native MMDB validation precedes replacement. Lookup uses destination numeric IPs locally. Database categories are retained for routing, but WAYD country labels accept only two-letter codes and unknown/private IPs remain unknown. The existing library shares WAYD UID; this is not a process sandbox. Profile YAML/embedded providers remain untrusted bounded inputs; unsupported MRS/GEOSITE/ASN/subrules fail explicitly. Profiles and credentials stay encrypted, and local blocks and per-app consent still precede proxy forwarding. No new Android permissions.

### Explicit public HTTPS integration test

The opt-in Android-native live routing/decryption test contacts www.google.com/robots.txt through the supplied routing profile, using a freshly resolved public numeric destination. It sends no account, cookies or inspection history. An ephemeral local test CA exists only within the native test process and is never installed into the phone trust store. Private profile fixtures are excluded from source control and removed from the device after testing. This does not authorize automatic production probes or uploading captured content.

Explicit resource mirror: the import review can opt into cdn.jsdelivr.net for public GitHub raw assets. URLs with credentials/query/fragment/custom port/escaped path segments are never rewritten. The displayed host reflects this choice; no automatic mirror retry. Resource cache remains memory-only and bounded; cancellation interrupts native HTTP I/O.

## External AI provider (D-046, 2026-11-11)

Optional, off by default, requested by the user. Analysis text (hosts, IPs, app names, times, permission uses, and decrypted message text if selected) can be sent to a user-configured OpenAI-compatible server.

- **Exposure:** the provider may log or keep what it receives. `Masker` removes obvious identifiers and tokens on a best-effort basis; it is not a guarantee.
- **Controls:** per-origin acknowledgement stored as `ConsentRecord("ai-external-v1:<origin>")` (changing the server revokes it); an approval dialog shows the exact prompt before every analysis and the analyzer fails closed without an approver; the dialog flags decrypted content; the API key is stored only as Keystore-AES-GCM ciphertext; redirects are never followed (no bearer replay); replies are bounded to 512 KB and still pass `SchemaValidator`, so the server cannot set severity, rules or consents.
- **Cleartext:** `network_security_config.xml` permits cleartext platform-wide so a LAN server (Ollama) works. `ExternalAiUrl` allows `http://` only for loopback, RFC1918, CGNAT (Tailscale), link-local, `.local`/`.lan` and single-label hosts, without DNS resolution. All other WAYD downloads require `https://` in code.
- **Not changed:** no new permission (INTERNET already present); WAYD's own sockets bypass its VPN; no analytics or automatic upload.
- **Over Clash (D-050, `clash` variant):** when routing is on, the VPN runs and access control selects WAYD itself, the call is dialed by name through the selected node and WAYD runs TLS over it (`EngineTls`, platform trust + hostname checks). The routing library and proxy operator see only ciphertext plus the destination name/port. A rejected host or failed node ends the analysis (`ext_route`), never a Direct retry. Private/LAN servers are never routed.

## Model files in a user folder (D-052, 2026-11-11)

Model files in Download/WAYD can be replaced by the user or other apps with storage access. WAYD hashes a changed file and compares it with the SHA-256 the chosen source publishes (pinned value if offline) before loading; a mismatch blocks loading until the user chooses to download again, replace it, or use it anyway for that exact file. The check request goes to the user-chosen model source only and carries no user data. Model output stays schema-validated and cannot set severity (rule 2), which bounds what a tampered model can do.
