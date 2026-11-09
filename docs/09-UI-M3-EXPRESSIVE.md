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

Bottom navigation (or navigation rail on large screens), 4 destinations:

| Tab | Screen | Purpose |
|---|---|---|
| 录制 Record | Home | Big record control, live stats, coverage |
| 报告 Reports | Daily report list → report detail | Insights |
| 应用 Apps | App list → app detail → message inspector / APK facts | Per-app evidence |
| 设置 Settings | Privilege, AI model, retention, Danger zone, about | Configuration |

AI entry points are contextual (selection action bar, finding card, app detail), plus an "Ask" FAB on Reports.

## Screens and Expressive components

### Home (Record)
- Large shape-morphing record button (custom: circle → rounded square on start, spring motion). Built with `MaterialShapes`/`Morph` if available, else animated `RoundedCornerShape`.
- `LoadingIndicator` while VPN is starting.
- Live stats as large emphasized numbers (flows · apps · MB up/down).
- Coverage card with checklist and fix buttons.
- Privilege card: None / Shizuku / Root with "unlocks" list.

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

### Settings
- Privilege; AI model manager (download size, device check, delete); retention; export; wipe.
- **Danger zone** (collapsed, red outline): MITM, root plaintext capture, developer console (research).

## Motion

- Use `MotionScheme.expressive()` springs for shared transitions (list → detail), record button morph, chip appearance.
- Respect "Remove animations" accessibility setting.

## Strings

- `values/strings.xml` (English) and `values-zh-rCN/strings.xml` (简体中文) from the first screen.
- App name: `在干嘛` (zh), `WAYD` (en).

## Accessibility

- Content descriptions on all icon buttons; charts have a text summary alternative.
- Minimum touch target 48 dp; test with TalkBack and font scale 200%.
