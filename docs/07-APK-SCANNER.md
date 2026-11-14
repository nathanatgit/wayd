# 07 · APK scanner

## Implementation status · 2026-11-10

`apkscan/` implements a user-started WorkManager scan (maximum two APKs concurrently) over visible installed packages plus packages observed in retained traffic. Apps → APK scanner lists stored results (including system apps); a package-name field permits an explicit lookup when the ROM hides the installed-app list. Individual app pages link to their static snapshot. Cancel preserves completed packages. Unavailable package counts are shown; a successful batch does not mean every installed app was visible.

Implemented: base/split binary manifests and receiver actions; PackageManager permissions/components/exported flags, shared UID and current signing certificates; declared NSC XML (including trust anchors, domains and pin declarations); defined-class SDK prefixes; possible host literals; native library names and protector heuristics. DEX readers accept 035/037–040, skip non-ASCII string evidence and retain hosts without paths/credentials/query values. Reflection type presence is not a hidden-API call-site finding. Certificates show SHA-256 plus subject, with no verified company attribution claim.

Bounds per package: 64 APK paths, 50,000 ZIP entries per path, 4 MiB manifest, 64 DEX entries, 32 MiB per DEX / 96 MiB DEX read budget, 500,000 string IDs, 4,096 characters per examined string, 256 hosts per DEX, about 4,096 facts plus bounded coverage records. Malformed entries and limits become coverage facts. No extracted APK files are retained. NSC is limited to 10,000 parser events. Cache uses scanner/version/update/file metadata; explicit per-app rescan bypasses cache. WorkManager concurrency is capped at two.

The original extraction table below remains the broader specification. Not implemented: root odex/VDEX extraction, non-ASCII endpoint extraction, hidden-API call-site analysis, complete licensed tracker signatures, automatic NSC/MITM feasibility verdicts or AI component explanation. The Declared/Used/Sent panel shows independent observations over retained history, with up to 100 operation types / 200 hosts; only exact static/observed host matches are highlighted. No three-way causal claim is made. All-app performance and carrier/vendor signer identification require device acceptance evidence.

## Descriptive markers (D-061, 2026-11-12)

The scanner now also reads declared Android Activities (`GET_ACTIVITIES`) into `ApkFactKind.ACTIVITY`; this is an additional value in the existing TEXT-backed fact kind, not a Room table/column change. Cache v2 invalidates the earlier scanner cache. Existing snapshots need a user-started rescan to show Activities. Activities, services, receivers, providers and SDKs can be explained as possible features; static presence is not execution evidence or proof of which component initiated a connection.

Each eligible fact has Labels and evidence → Explain labels with AI. Permissions get a built-in capability explanation and an optional AI explanation. Endpoints get a controlled role and optional AI permission/feature relationships. AI relationships use exact fact IDs, are marked possible associations, and open the referenced APK evidence. Connections in app history/details use the same markers and separate neutral algorithm/AI blocking suggestions. No model output changes severity, consent or policy. Inference is explicit per item, not an automatic whole-APK batch; its context is a disclosed bounded selection. See docs/06-AI-GEMMA.md.

JSON exports retain the 400-fact bound but select across fact kinds so endpoint-heavy APKs cannot omit all permission/SDK/component evidence. Each app includes `apkFactCount` and `apkFactsTruncated`; selected facts include `id`, `versionCode`, `kind`, `key`, `value` and `evidence`. Truncation is not a negative scan result. APK links describe the latest stored scan; historical APK-version identity remains unknown.

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
