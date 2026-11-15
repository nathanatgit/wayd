# 07 · APK scanner

## Implementation status (2026-11-14)

`apkscan/` runs a user-started WorkManager scan (two APKs at a time) over visible installed packages plus packages seen in retained traffic. Cancel keeps completed packages. A finished batch does not mean every installed app was visible; unavailable counts are shown.

| | |
|---|---|
| **Reads** | base/split binary manifests and receiver actions; PackageManager permissions, components (Activities, services, receivers, providers), exported flags, shared UID, current signing certificates; declared NSC XML (trust anchors, domains, pins); SDKs by defined-class prefix; possible host literals; native library names; protector heuristics |
| **DEX** | formats 035, 037–040; non-ASCII string evidence skipped; hosts kept without path, credentials or query |
| **Not claimed** | reflection type presence is not a hidden-API call-site finding; certificates show SHA-256 and subject with no verified company attribution; static presence is not execution, a grant, or proof of which component made a connection |
| **Not implemented** | root odex/VDEX extraction, non-ASCII endpoints, hidden-API call sites, complete licensed tracker signatures, automatic NSC/MITM feasibility verdicts |
| **Open** | all-app performance and carrier/vendor signer identification need device evidence; full-inventory scan through Shizuku |

**Bounds per package:** 64 APK paths; 50,000 ZIP entries per path; 4 MiB manifest; 64 DEX entries; 32 MiB per DEX and 96 MiB DEX budget; 500,000 string IDs; 4,096 characters per string; 256 hosts per DEX; about 4,096 facts plus bounded coverage records; NSC 10,000 parser events. Malformed entries and hit limits become coverage facts. No extracted APK files are kept.

**Cache:** keyed by scanner version, app version/update time and file metadata; a per-app rescan bypasses it. Cache v2 (D-061) added Activities (`ApkFactKind.ACTIVITY`, existing TEXT column, no schema change); older snapshots need a rescan to show them.

## How the result is shown and used

- **APK page** (D-063): overview tiles, sensitive-capability chips (marked "used" from recorded permission use and "N connections" from today's related destinations), endpoint purpose share, SDK chips; every fact list collapsed under its own header. A package-name field allows a lookup when the ROM hides the app list.
- **Links to behaviour:** rules relate each destination host to declared permissions, components and SDKs (`LabelRelations`, see [06-AI-GEMMA.md](06-AI-GEMMA.md) and D-063). Only exact static/observed host matches are highlighted as "contacted". No causal claim is made.
- **AI:** each eligible fact has "Ask AI about this item" (one item per request, bounded context, exact fact ids, links marked as possible). No whole-APK batch inference; model output never changes severity, consent or policy.
- **Export:** JSON keeps a 400-fact bound per app but samples across fact kinds, with `apkFactCount` and `apkFactsTruncated`; each fact carries `id`, `versionCode`, `kind`, `key`, `value`, `evidence`. Truncation is not a negative result. Links describe the latest stored scan; which APK version produced past traffic is unknown.

The extraction table below remains the broader specification.

## Inputs

When Shizuku is already authorized, discovery also reuses its fixed read-only current-user package inventory (`Command.ListPackages`). No automatic Shizuku permission request or root APK access is added. Ordinary PackageManager lookups still determine which APK paths can be read. Discovery does not guarantee access to a hidden package.

- `ApplicationInfo.sourceDir` + `splitSourceDirs` for every installed package (system apps included; needs `QUERY_ALL_PACKAGES`, justified by core functionality).
- Root: `/data/dalvik-cache`, `oat/` dirs for odexed system apps.

## Extraction (on device, no LLM)

| Step | Output (`ApkFact.kind`) |
|---|---|
| Binary AXML manifest | PERMISSION, RECEIVER (+ intent filters e.g. BOOT_COMPLETED), SERVICE, PROVIDER (exported?), `sharedUserId`, `usesCleartextTraffic` |
| Network security config | NSC: trusts user CAs? cleartext domains? pins? → predicts MITM feasibility |
| Signing certificate | signer subject/SHA-256 (e.g. `O=chinaunicom`) → AppInfo |
| DEX scan (dexlib2) | SDK by package prefix (tracker signature DB), reflection to hidden APIs |
| Strings | ENDPOINT (URLs, domains, IP literals), suspicious keys |
| Native libs | NATIVE_LIB names; PACKER detection (known protector libs/classes) |
| No `classes.dex` | look for vdex/oat; extract DEX (version-specific); else flag "odexed – limited" |

## Tracker signature DB

- Format: `{ "id": "umeng", "name": "Umeng", "company": "Alibaba", "code_prefixes": ["com.umeng."], "hosts": ["*.umeng.com"], "category": "ANALYTICS" }`.
- Seed from public sources (check licenses: Exodus Privacy data, etc.) plus a curated China list (Umeng, Getui/个推, JPush/极光, Baidu location/mobstat, Tencent Bugly/MTA, Huawei/Xiaomi/OPPO/vivo push, carrier SDKs).

## Linking static ↔ dynamic

For each app page show three columns:
1. **Declared** — permissions/components/SDKs in the APK.
2. **Used** — AppOps events, alarms.
3. **Sent** — observed destinations / data types.

Highlight rows present in all three (strong evidence) and endpoints found in APK but never contacted (latent capability).

## AI assistance

- "Explain this component": pass ≤ 150 lines of smali/decompiled code for one flagged class, wrapped as untrusted `<data>`.
- Expect modest accuracy from E2B/E4B on code; present as an explanation, not proof.

## Limits (state in UI)

- Packed/obfuscated apps hide most code.
- Odexed system apps may need root for full analysis.
- Static presence ≠ behaviour; always pair with dynamic evidence.

## Performance

- Scan incrementally (versionCode change); cache facts.
- Run in WorkManager with low priority; cap parallelism to 2.
