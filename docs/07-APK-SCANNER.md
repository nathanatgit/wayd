# 07 · APK scanner

## Inputs

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
