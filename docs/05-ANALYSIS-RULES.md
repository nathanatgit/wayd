# 05 · Analysis, rules and reports

## Principle

Rules are deterministic, testable Kotlin. They decide severity. The AI never changes a rule result.

## Enrichment (before rules)

- Host for each flow: DNS mapping → SNI → HTTP Host (record `hostSource`).
- Company: domain → owner map (bundled JSON, e.g. built from DuckDuckGo Tracker Radar + Chinese vendor additions: Xiaomi, Huawei, OPPO/vivo, Tencent, Alibaba, ByteDance, Baidu, Umeng, carriers). Check each source's license.
- Category: ADS / ANALYTICS / CRASH / PUSH / CDN / OS / CARRIER / FIRST_PARTY / UNKNOWN.
- Country / ASN: offline GeoIP DB.

## Identifier matcher

Build a `DeviceIdentifiers` set at session start (only values readable at current privilege):
Android ID, advertising ID, serial/IMEI (root/ADB), Wi-Fi MAC, current BSSID, phone number (if readable), rounded current location (3 decimals), installed package names, device model string.

Scan PLAINTEXT and DECRYPTED messages (URL, headers, body; also base64/URL-decoded variants and common hashes MD5/SHA1/SHA256 of each value). A match produces `DataType` evidence with near-zero false positives.

**Never** store the raw identifier list in findings — store the type and the message reference.

## Rule interface

```kotlin
interface Rule {
    val id: String            // "R-BG-UPLOAD", stable forever
    val version: Int
    fun evaluate(ctx: RuleContext): List<Finding>
}
```

`RuleContext` gives read-only access to the session's flows, messages, perm events, alarms, app info, APK facts and previous-day baselines.

## Starter rule set

| ID | Trigger | Severity |
|---|---|---|
| R-ID-CLEARTEXT | Device identifier found in PLAINTEXT message | HIGH |
| R-ID-DECRYPTED | Device identifier in DECRYPTED message | MEDIUM–HIGH by type |
| R-APPLIST | Message contains ≥ 20 installed package names | HIGH |
| R-BG-UPLOAD | App uploads > N KB with screen off and 0 foreground minutes today | MEDIUM |
| R-UP-RATIO | Upload/download ratio > 3 and upload > 1 MB | MEDIUM |
| R-BEACON | Periodic connections to the same host (CV of inter-arrival < 0.2, ≥ 6 times) | LOW |
| R-LOC-THEN-UP | Location / Wi-Fi scan perm event followed within 10 s by upload from same app | MEDIUM |
| R-BOOT-REPORT | Upload within 120 s of boot by an app with a boot receiver | LOW–MEDIUM |
| R-NO-SIM-CARRIER | Carrier-signed app uploads while no SIM present | MEDIUM |
| R-SCREEN-CAPTURE | PROJECT_MEDIA op used by a non-foreground or system app | HIGH |
| R-CLIPBOARD-BG | Clipboard read while app in background | MEDIUM |
| R-NEW-DOMAIN | First-ever contact with a domain | INFO |
| R-DIRECT-IP | Connection with no prior DNS for that IP | LOW |
| R-OWN-DOH | App resolves names via its own DoH | INFO |
| R-CROSS-BORDER | Destination country ≠ device region and category ≠ CDN | INFO |
| R-BYPASS | Root: traffic seen outside the VPN | HIGH |
| R-TRACKER-SDK | APK contains tracker SDK and its endpoints were contacted | LOW–MEDIUM |

Every rule ships with unit tests built from fixture sessions.

## Risk score (per app, per day)

`score = Σ severity_weight(finding) × certainty_weight(tier)`, capped at 100.
Weights: HIGH 30, MEDIUM 12, LOW 4, INFO 0; PLAINTEXT 1.0, DECRYPTED 1.0, METADATA/inferred 0.6.
Show the score with its breakdown; never show a score without reasons.

## Daily report (DailyReportBuilder)

Order of sections:
1. One-line summary (apps, domains, companies, countries, trackers).
2. Alerts (HIGH/MEDIUM findings).
3. "While you weren't using it" — background/screen-off activity ranking.
4. Who received your data — companies × apps.
5. 24 h heatmap — apps × hours, with permission-use markers.
6. Countries.
7. New today.
8. Coverage panel.

Report is a stored, versioned object so it renders instantly and can be AI-summarised later.
