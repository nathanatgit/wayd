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
| R-TRACKER-CONTACT | *(added M2)* App sends data to ADS (LOW) / ANALYTICS or CRASH (INFO) domains | LOW / INFO |
| R-CLEARTEXT-HTTP | *(added M2)* Data sent over unencrypted HTTP to a public host (OS checks and LAN excluded) | LOW |

**Implemented in M2 (flow data only):** R-BG-UPLOAD (screen-off only; foreground time unknown until M3), R-UP-RATIO, R-BEACON, R-NEW-DOMAIN, R-DIRECT-IP, R-OWN-DOH, R-TRACKER-CONTACT, R-CLEARTEXT-HTTP. Waiting for other inputs: R-ID-* / R-APPLIST (messages, M4), R-LOC-THEN-UP / R-BOOT-REPORT / R-SCREEN-CAPTURE / R-CLIPBOARD-BG / R-NO-SIM-CARRIER (permission events, M3), R-TRACKER-SDK (APK scan, M5), R-CROSS-BORDER (GeoIP data), R-BYPASS (root, M8).

Every rule ships with unit tests built from fixture sessions.

## Risk score (per app, per day)

`score = Σ severity_weight(finding) × certainty_weight(tier)`, capped at 100.
Weights: HIGH 30, MEDIUM 12, LOW 4, INFO 0; PLAINTEXT 1.0, DECRYPTED 1.0, METADATA/inferred 0.6.
Show the score with its breakdown; never show a score without reasons.

## App-oriented analysis (primary view)

The main analysis view is **per app** (`AppReportBuilder`): what the app did in a window. Destinations are grouped by company *and* purpose (so an ad endpoint is not hidden in a CDN row); connections without a hostname form one bucket; LAN traffic is counted separately. Plain-language insights are structured (`Insight`) and rendered from string resources in en/zh. The daily report is a thin overview that links into apps.

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

## Labels, relations and attention (D-061 to D-063)

Deterministic, in `analysis/LabelRules.kt` and `LabelRelations.kt`. They describe and order; findings keep their own severity. The earlier Danger/Warn/Sensitive/Ad/Normal connection labels (D-051) are removed.

| Output | How it is computed |
|---|---|
| **Role** of a host | curated suffix list → tracker list category → whole-word hostname match ("by name") → UNKNOWN. Vocabulary: FUNCTIONAL, LOGIN, LOCATION, PUSH, ANALYTICS, DIAGNOSTICS, ADS, MEDIA, CONTENT, UPDATE, CARRIER, SYSTEM, UNKNOWN. |
| **Advice** per (app, host) | needs an attributed TCP SNI/HTTP-Host record with bytes. ADS/ANALYTICS/DIAGNOSTICS → try a temporary block; SYSTEM/CARRIER/UNKNOWN → insufficient; other roles → may affect function. Carries reasons (role source, no host evidence, no bytes, unattributed, screen-off share, upload-heavy, already blocked). |
| **Relations** to the APK | permission used while the host's records were open: USED_TOGETHER if it fits the role, else SAME_TIME; declared permission that fits the role: DECLARED; component or SDK sharing role words, host words or company: NAME_MATCH. Never causation. |
| **Attention** | score from rule finding on the host (HIGH 4, MEDIUM 3, LOW 1), tracking role (2, or 1 if by name), relations (2 / 1), screen-off majority (1), upload-heavy on a tracking or unknown host (1). ≥ 5 ACT, ≥ 3 REVIEW, ≥ 1 NOTE. Platform hosts cap at NOTE without a finding; a blocked host is NONE. |
| **Suggestions** | block trial; AppOps restrict <permission/op>; Thanox redirect (ad activity) or disable (tracking/push service or receiver). Pointers only. |

The AI may add its own role, links and advice next to these (06-AI-GEMMA.md); it never changes them.
