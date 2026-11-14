# 09 · UI — Compose + Material 3 Expressive

## Library status (check before starting — this moves fast)

- As of Aug 2026, Compose Material3 **1.4.x stable exposes almost none of Expressive**; the Expressive APIs (`MaterialExpressiveTheme`, `MotionScheme.expressive()`, new components) are in **1.5.0-alpha** and need `@OptIn(ExperimentalMaterial3ExpressiveApi::class)`.
- Decision (see D-004): use the 1.5 alpha line, pin an exact version, and **wrap every experimental component in `core/ui`** (e.g. `WaydLoadingIndicator`, `WaydButtonGroup`) so an API change touches one file.
- Dynamic colour on Android 12+; branded seed colour fallback below 12.

## Theme

```kotlin
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun WaydTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val scheme = if (Build.VERSION.SDK_INT >= 31)
        if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    else if (dark) WaydDarkScheme else WaydLightScheme
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        typography = WaydTypography,   // include emphasized styles
        shapes = WaydShapes,
        content = content
    )
}
```

### Semantic colours (on top of the scheme)
| Token | Meaning |
|---|---|
| `severityHigh` | HIGH findings (error container family) |
| `severityMedium` | MEDIUM (tertiary/amber family) |
| `tierPlaintext` | PLAINTEXT badge |
| `tierDecrypted` | DECRYPTED badge |
| `tierMetadata` | METADATA badge (neutral) |
| `aiOpinion` | AI cards — visually distinct from rule severity |

Never encode meaning with colour alone: pair with icon + label.

## Navigation

Bottom navigation (or navigation rail on large screens), 5 destinations:

| Tab | Screen | Purpose |
|---|---|---|
| 总览 Home | Dashboard | Start/stop WAYD VPN, independent recording toggle, rules and evidence summary |
| 报告 Reports | Daily report list → report detail | Insights |
| 应用 Apps | App list → app detail → message inspector / APK facts | Per-app evidence |
| 管控 Controls | Unified rule inventory | All effective/inactive blocking rules; future alterations |
| 设置 Settings | Privilege, AI model, retention, Danger zone, about | Configuration |

AI entry points are contextual (selection action bar, finding card, app detail), plus an "Ask" FAB on Reports.

## Screens and Expressive components

### Home dashboard (implemented)
- One scrollable dashboard: engine status/Start or Stop WAYD, feature switches, rule counts with Controls link, current/last-run aggregate stats, retained-history coverage/reports/export and five recent saved connections.
- Record traffic is an independent opt-in switch; VPN forwarding and blocks continue when recording is off. Choose the mode before startup or change it while running. Default off after process restart; retain preference across VPN stop/start in the same process.
- Record only flows opened and completed in one uninterrupted enabled window. No partial flows spanning a toggle or retroactive capture. Existing history remains; completed queued records may finish writing. Disabling does not revoke research consent or terminate existing intercepted connections; their new messages are not retained. New interception decisions are declined while recording is off.
- Clash routing is a disabled, explicitly unavailable switch pending integration. No extra VPN or new route is started.
- Distinguish processed aggregate counters from saved evidence and avoid claims of complete coverage. Engine controls remain reachable by scrolling at large font sizes.

### Reports
- `LargeFlexibleTopAppBar` with date + one-line summary.
- Alert cards (severity), "While you weren't using it" list, companies Sankey, 24 h heatmap, countries, new today, coverage.
- Charts: Vico (Compose charts) or custom Canvas for heatmap/Sankey.

### Apps
- Search + `ButtonGroup` filter (All · System · User · Flagged).
- App detail: header with risk score + breakdown; tabs (Timeline · Destinations · Messages · Permissions · APK).
- `HorizontalFloatingToolbar` for actions (Block · Analyse with AI · Export).

### Message inspector
- List of messages: time, ↑/↓, host+path, size, tier badge.
- Detail sheet: key/value view, personal-data highlights, raw/hex toggle, "Follow stream".
- Multi-select → contextual bar → **Analyse with AI**.

### AI analysis result
- Header card: summary + verdict ("AI opinion" label, `aiOpinion` colour).
- Data-type chips (certainty-coloured) → tap opens evidence.
- Destinations list/Sankey; timeline markers; suggested actions as buttons (each confirms).
- While running: `LoadingIndicator` + cancel; show model + tokens/s when done.

### Controls (implemented M7 inventory)
- Dedicated Controls / 管控 bottom tab; Apps also links here. Inventory spans all apps and is independent of history time windows.
- Effective/total count, capture state and coverage limits; literal package/host/IP search and All / Effective / Inactive filters.
- Each rule shows action, typed target, session/durable lifetime and acknowledged activation status. Expand for rule ID, Android user, creation time and bound signer fingerprint; open app history or confirm removal.
- Effective means acknowledged by the current native policy snapshot, subject to displayed coverage. Rules with recording stopped are inactive.
- Alteration section explicitly states response replacement/local replies are not implemented.

### Settings
- Privilege; AI model manager (download size, device check, delete); retention; export; wipe.
- **HTTPS decryption** (D-058): directly visible risk notice and shared enable/app controls, followed by manual user-CA installation and root system-wide trust as separate method cards. No extra Danger zone disclosure; consent and operation confirmations stay mandatory.

## Descriptive markers (D-061)

Connection rows show roles rather than risk-verdict markers. APK permission markers explain capabilities; component markers explain possible features; endpoints/connections may link declared permissions or components through AI hypotheses. Every generated marker starts with `AI:` and links say possible association. Known local classifications remain visible when AI disagrees. Rule findings keep their own separate section and severity.

Labels and evidence expands the latest APK version/source context, bounded-input notice, AI model/time/citations, and separate algorithm/AI blocking suggestions. Generation is user-started for the selected item. Connection history and connection detail retain explicit blocking confirmation and Undo; AI never chooses the action target. Both English and Chinese resources are supplied. No declaration is described as a current permission grant, and foreground state is not inferred from the placeholder flag.

## Motion

- Use `MotionScheme.expressive()` springs for shared transitions (list → detail), record button morph, chip appearance.
- Respect "Remove animations" accessibility setting.

## Strings

- `values/strings.xml` (English) and `values-zh-rCN/strings.xml` (简体中文) from the first screen.
- App name: `在干嘛` (zh), `WAYD` (en).

## Accessibility

2026-11-10 Controls additions: reviewed persistent-rule JSON transfer and optional
Clash profile import/app-consent/enable/delete. Routing edits are disabled while
WAYD runs; import saves disabled and unsupported YAML is rejected. Home links to
configuration and shows actual transport outcome counters. Both locales disclose
the initial protocol/rule subset, numeric bootstrap, Direct scope, DNS gaps,
same-UID boundary and credential handling. This supersedes the disabled future
Clash placeholder; broader tab redesign is still unsettled.

- Content descriptions on all icon buttons; charts have a text summary alternative.
- Minimum touch target 48 dp; test with TalkBack and font scale 200%.
