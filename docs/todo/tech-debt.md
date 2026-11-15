# To-do · Housekeeping and older open items (P3)

## Repository hygiene

| # | Task | Notes |
|---|---|---|
| 3 | Rust warnings on the Windows host build (unused `tcp`/`udp`, `set_mitm`). | They are Android-only entry points; gate with `cfg` or `allow(dead_code)` so real warnings stand out. |
| 5 | CI: upload the lint report as an artifact. | `ci.yml` runs lint but keeps no report. |
| 4 | Release commits: bump the version in the `chore: release` commit, not in the feature commit. | v0.5.0's release commit is empty. |
| 6 | Unused code after the history rewrite: `RelatedTcp` (analysis) if nothing else uses it. | Check with a search before deleting. |
| 7 | `docs/00`–`04`, `08`, `14` were not reviewed line by line in the 2026-11-14 doc pass. | Read them against the code once; fix stale statements. |

## Older open items (from earlier handoffs)

| # | Task | Area |
|---|---|---|
| 8 | Whole-app, durable and reinstall cases for per-app rules through the UI; consent/identity edge cases. | M7 |
| 9 | Clash: blacklist save, live routed run with per-app consent, decryption over a routed flow from the installed app. | routing |
| 10 | Collectors: R-BOOT-REPORT, alarms, netstats, package events. | M3 |
| 11 | APK scan of the full inventory through Shizuku; cold scan under 5 min. | M5 |
| 12 | Message inspector device check. | M4 |
| 13 | Overnight summary. | M6 |
| 14 | Router / IPv6 comparison for capture coverage. | M1 |
| 15 | Delete the dead tag `v0.1.1` when Nat agrees. | release |
