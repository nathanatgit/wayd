# To-do · Device pass and merge (P0)

Everything built after v0.4.0 has only unit tests and builds behind it. This is the list to tap through on the NX733J with Nat. Rules: [HANDOFF.md](../HANDOFF.md) "Device testing" (Nat starts the VPN, never the production package, no changes to his rules).

## Setup

1. Build without `-PskipNative` and install over `com.nathanhanapps.wayd.d049` (check `output-metadata.json`). The build on the phone from 2026-11-14 lacks D-066 to D-068.
2. `setprop log.tag I` while testing, `setprop log.tag S` afterwards.

## Checks

| Area | What to do | Pass when |
|---|---|---|
| App page | Open an app with traffic | AI card first, chat card second, dashboard below; no jank while capture runs |
| History (D-063) | Expand 连接历史与阻止 | Hosts sorted by attention; role / attention / relation chips readable in both languages and at large font; no "已通过本地策略检查" lines |
| Block button | Tap 阻止 on a host row | Existing confirm dialog; rule appears; row shows 已阻止 |
| Per-item AI | "Ask AI about this item" on one host, on-device | Answer within about 30 s on E2B; saved; shown on other records of the host |
| History check | "AI 检查全部连接" on an app with 20+ hosts | Progress per group; time per group noted; notes on rows; cancel works |
| APK page | Scan one app, open it | Tiles, capability chips, purpose bar; lists collapsed; rescan adds Activities |
| AI settings | On-device page; external page | On-device shows Auto/Small/Medium only; external shows effort and Large; profile save, switch, delete; preview shown again after a switch |
| Chat | Insights and app page: text, photo, video, audio file, voice | Mic permission asked at first tap only; if Gemma cannot take media the chat says so; external preview lists attachments |
| Block lists (D-064) | Import a small file and a URL list; enable one | Counts shown; consent dialog; engine entry count shown; a listed host is blocked for a live app and marked "被阻止列表阻止" |
| DNS stage (D-065) | App that uses QUIC to a listed name | Name resolves to 0.0.0.0 in the DNS record; no QUIC connection |
| Hostname wait | Use a non-HTTP app (SSH, mail, game) with a list on | No visible connect delay beyond 0.6 s; note any breakage |
| Large list | Import a 100k+ list | Load time at VPN start; memory before/after; battery over a normal hour |
| Rewrite (D-066) | App under decryption: one local-answer rule, one header-remove, one query-remove | App gets the local answer, server gets nothing; inspector shows `X-WAYD-Rewrite`; hit counts rise; other requests on the same connection unaffected |
| Inferred names (D-067) | Compare coverage panel before/after; open a QUIC flow | Unnamed flows to a known address show a name with source INFERRED; no Block-by-host offered for it |
| AppOps / Thanox | Open a host with suggestions | Buttons appear only when the app is installed and open it |

## After the pass

- Fix findings on the same branch; re-run `test`, both assemblies, both lints.
- Push `feat/dns-list-blocking`, merge to `main` with `--no-ff`.
- Version: D-065 to D-067 add user-visible features, so the next tag is `v0.6.0` (versionCode 9) unless Nat prefers a patch.
- Update the handoff's "Not checked on a device" list with what passed.
