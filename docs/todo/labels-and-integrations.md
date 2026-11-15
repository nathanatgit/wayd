# To-do · Labels, attention, AppOps / Thanox (P2)

Built: roles, per-host advice with reasons, APK relations, attention, suggestions (D-061 to D-063). Rules: [05-ANALYSIS-RULES.md](../05-ANALYSIS-RULES.md).

## Labels and attention

| # | Task | Notes |
|---|---|---|
| 1 | Measure the label snapshot rebuild on the phone during active capture. | It recomputes every host of the app on each database change (after a 250 ms debounce). If it shows up: recompute only hosts whose records changed, or lengthen the debounce while the VPN runs. |
| 2 | Grow the curated role list (login, location, update, push hosts of common Chinese SDKs). | Facts only, suffix-matched; each entry needs a source. Today: 10 hosts. |
| 3 | Review the attention weights against real apps. | Collect 5–10 apps where the level looks wrong; adjust weights with a test per case. |
| 4 | App-level attention on the Apps list and Insights ("3 destinations to review"). | Uses the same assessments; needs them outside the app page (cache per app and window). |
| 5 | User override of a role or "this is fine". | Display-only; stored per app + host; never fed back as evidence. Needs a small store (file or table). |
| 6 | Relations from decrypted content. | When a request body contains an identifier the rules already detect (R-ID-IN-REQUEST), link that data type to the host as the strongest relation. |
| 7 | Same-site grouping. | Offer "all hosts under example.com" as one row with a combined level, and a subdomain block target. |

## AppOps and Thanox

Today WAYD only names what to restrict and opens the app if installed (`rikka.appops*`, `github.tornaco.android.thanos*`).

| # | Task | Notes |
|---|---|---|
| 8 | Find out what each app accepts from outside: deep links, intents, exported activities, a provider. | Read their manifests on the phone (`dumpsys package`); write the commands for Nat to run and paste. Do not guess. |
| 9 | If a deep link to an app's page exists, open that page for the suggested package. | No privilege needed. |
| 10 | Apply a suggested AppOps mode through WAYD's own Shizuku path. | A new privileged command (`appops set <pkg> <op> ignore`) in the sealed `Command` allowlist, per-app consent, Undo. Opus-level task; ask Nat first. |
| 11 | Thanox 移花接木 / component disable: only via whatever interface task 8 finds; otherwise keep the pointer text with a copy button for the component name. | |
