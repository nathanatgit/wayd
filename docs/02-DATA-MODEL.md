# 02 · Data model

All IDs are `Long` auto-increment. Evidence references use typed string IDs in the UI and AI layer:

| Prefix | Entity | Example |
|---|---|---|
| `f:` | Flow | `f:812` |
| `m:` | Message | `m:33` |
| `d:` | DnsRecord | `d:120` |
| `p:` | PermEvent | `p:7` |
| `a:` | ApkFact | `a:51` |
| `x:` | Finding | `x:9` |

## Entities (Room)

```kotlin
@Entity data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long, val endedAt: Long?,
    val privilege: Privilege,          // NONE, ADB, ROOT at start
    val coverageJson: String           // CoverageSnapshot serialized
)

@Entity(indices = [Index("sessionId"), Index("pkg"), Index("host"), Index("startAt")])
data class Flow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val uid: Int, val pkg: String, val userId: Int,
    val proto: Proto,                  // TCP, UDP
    val ipVersion: Int,
    val dstIp: String, val dstPort: Int,
    val host: String?, val hostSource: HostSource?,   // DNS, SNI, HTTP_HOST, QUIC_SNI
    val bytesUp: Long, val bytesDown: Long, val pktsUp: Int, val pktsDown: Int,
    val startAt: Long, val endAt: Long?,
    val screenOn: Boolean, val appForeground: Boolean, val netType: NetType,
    val tlsVersion: String?, val alpn: String?, val ja4: String?,
    val country: String?, val asn: String?, val company: String?,
    val category: TrackerCategory?,    // ADS, ANALYTICS, CRASH, PUSH, CDN, OS, CARRIER, FIRST_PARTY, UNKNOWN
    val source: CaptureSource          // VPN, NFLOG(root), BYPASS(root-detected)
)

@Entity(indices = [Index("flowId")])
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val flowId: Long, val dir: Dir,    // UP, DOWN
    val ts: Long, val tier: Tier,      // PLAINTEXT, DECRYPTED, METADATA
    val tierSource: TierSource?,       // HTTP, MITM, EBPF, FRIDA
    val method: String?, val url: String?, val status: Int?,
    val headersJson: String?,
    val bodyBlobId: String?, val bodySize: Int, val contentType: String?
)

@Entity data class DnsRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long, val uid: Int, val ts: Long,
    val qname: String, val qtype: Int, val answersJson: String, val viaDoh: Boolean
)

@Entity data class PermEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String, val op: String,   // e.g. FINE_LOCATION, WIFI_SCAN, READ_CLIPBOARD, PROJECT_MEDIA
    val ts: Long, val durationMs: Long?, val background: Boolean, val source: String
)

@Entity data class AlarmInfo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String, val tag: String, val type: String, val repeatMs: Long?, val seenAt: Long
)

@Entity data class AppInfo(
    @PrimaryKey val pkg: String,
    val label: String, val isSystem: Boolean, val versionName: String?,
    val signerSha256: String?, val signerSubject: String?,
    val installer: String?, val firstSeen: Long, val lastScanned: Long?
)

@Entity data class ApkFact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String, val versionCode: Long,
    val kind: ApkFactKind,             // PERMISSION, RECEIVER, SERVICE, SDK, ENDPOINT, NSC, NATIVE_LIB, PACKER
    val key: String, val value: String?, val evidence: String?   // e.g. class path or string location
)

@Entity data class Finding(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long?, val pkg: String,
    val ruleId: String, val severity: Severity,   // INFO, LOW, MEDIUM, HIGH
    val title: String, val dataTypes: String,     // CSV of DataType
    val evidenceJson: String,                     // ["f:812","p:7"]
    val createdAt: Long, val userState: UserState // NEW, SEEN, IGNORED
)

@Entity data class AiAnalysis(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val inputRefsJson: String, val model: String, val promptVersion: Int,
    val resultJson: String, val validated: Boolean, val createdAt: Long, val durationMs: Long
)

@Entity data class BlockRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scope: BlockScope, val pkg: String?, val host: String?, val enabled: Boolean, val createdAt: Long
)

@Entity data class ConsentRecord(
    @PrimaryKey val feature: String,   // MITM, ROOT_PLAINTEXT, AI_MODEL
    val statementVersion: Int, val acceptedAt: Long, val revokedAt: Long?
)
```

## Enums worth fixing early

- `DataType`: `DEVICE_ID, ADVERTISING_ID, ANDROID_ID, IMEI_SERIAL, PHONE_NUMBER, LOCATION_PRECISE, LOCATION_COARSE, WIFI_BSSID, APP_LIST, APP_USAGE, CONTACTS, SMS, CLIPBOARD, SCREEN_CONTENT, FILES_MEDIA, ACCOUNT, NETWORK_INFO, OTHER`
- `Tier`: `PLAINTEXT, DECRYPTED, METADATA` — never upgrade a tier without a real source.

## Blob storage

- Payload bodies stored as encrypted files: `files/blobs/<sha256>` (AES-GCM, key in Keystore).
- Deduplicate by hash.
- Max blob size per message (default 256 KB); truncated flag in `Message`.

## Retention defaults

| Data | Default |
|---|---|
| Message bodies | 7 days |
| Flows, DNS, perm events | 30 days |
| Daily aggregates, findings | 180 days |
| AI analyses | follow their findings |

`RetentionWorker` runs daily. "Wipe all" deletes DB + blobs + CA (if any) and resets consents.

## Backup

`android:allowBackup="false"`, plus `dataExtractionRules` excluding everything.

## M7 schema v5 · 2026-11-10

Room auto-migrates 4→5 and exports schema 5. BlockRule adds nullable `portableId` (uniquely indexed), `portableRevision` (default 1), and `needsImportReview` (default false). New persistent rules receive a UUID; migrated rules have null portable IDs until a future export assigns them transactionally. Pending-review rules are excluded from native policy even when enabled and an app consent exists. Existing rules retain their prior eligibility. The importer, review/consent workflow and export are not implemented by this schema change; a future importer must always mark every imported rule for review and must not treat the file as a consent grant.

## M7 schema v4 · 2026-11-10

Room exports v4 and auto-migrates 3→4 with additive columns. BlockRule adds `matchKind TEXT NOT NULL DEFAULT 'HOST'`, `signingSha256 TEXT NOT NULL DEFAULT ''`, `userId INTEGER NOT NULL DEFAULT 0`. Existing scope/package/host fields remain. Unbound legacy rules remain stored but inactive; current installation identity and explicit per-app consent are required before native installation. Exact APP/HOST/IP kinds are the current supported subset. Persistent positive IDs are database IDs; negative session IDs exist only in memory and are never backed up.

Flow adds nullable `policyOutcome TEXT`, `policyRevision INTEGER`, `blockRuleId INTEGER`. Historical rows retain null policy evidence. Outcomes distinguish blocked, passed current policy, unknown owner/host and failed enforcement gate; they do not imply comprehensive coverage or change risk severity. No retained message content is rewritten.
