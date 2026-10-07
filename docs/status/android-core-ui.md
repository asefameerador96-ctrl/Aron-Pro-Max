# Status: lane android-core-ui

## In progress
- **N-023** shared UI kit, published in slices. Slices 1 and 2 are green in CI and merged to INT (commit 4546821). Slice 3 (tokens, glass tiers, status chip) is on `lane/android-core-ui` awaiting CI. Compiled by CI only (Maven Central 429 locally, no mirror used).

## Kit for feature lanes (`com.aktcl.aron.core.ui`, module `:android:core-ui`)
All components are 48 dp minimum, wrap (never truncate) at font scale 1.3, and take already-resolved strings: **you pass `stringResource(...)` from your own module** (kit strings never carry feature text).
```kotlin
AronTheme(language, dark = isSystemInDarkTheme()) { ... }          // light + dark, Bengali/Latin fonts
AronPrimaryButton(stringResource(R.string.save), onClick = { ... })   // also AronSecondaryButton
AronListRow(title = outlet.name, subtitle = "${code}-${phone}", trailing = localizedNumber(total), onClick = { ... })
AronBanner(text, kind = BannerKind.Warning)                         // Info | Warning | Error; OfflineBanner() = stock offline message
AronTileGrid(items = tiles, columns = 4) { tile, mod -> AronTile(label = stringResource(tile.label), badge = tile.badge, onClick = tile.go, modifier = mod) }
AronStepper(value, { value = it }, minusLabel = stringResource(R.string.less), plusLabel = stringResource(R.string.more), min = 0, max = 999)
```
Digits: use `localizedNumber(n)` / `localizedDigits(text)`; never for identifiers.

## Design: Calm Glass (docs/32), slice 3
Components read token names only (`AronTokens`, `LocalAronColors`: bg gradient, surface glass/solid, hairline, text primary/secondary, accent, success, warning, danger, offline; radii 12/20/28; touch 48, primary 56; motion 150/220/320).
```kotlin
val tier = rememberGlassTier(UiGlassConfig.parse(cfg.app.ui_glass))   // A real blur slot, B glass-lite default, C solid
AronTheme(language, tier = tier) { GlassSurface { ... }; StatusChip(SyncChipState.Waiting(n)) }
```
`GlassPolicy.resolve(config, GlassSignals)` is pure: battery saver, reduce-transparency, high contrast or a failed frame probe force C; `lite` caps at B; A needs Android 12+, 4 GB+. No blur dependency yet (tier A blur plugs into `GlassSurface` later, needs a request file). Previews: `KitPreviews.kt` (tiers A/B/C, light/dark). `AronPrimaryButton` is now 56 dp and pill-shaped.

## Outdoor-first (docs/32 s2a), slice 4
- Tokens are the docs/design/tokens.md v1 values in three themes: `AronMode.Light` (default), `Dark`, `Sunlight`. `AronTheme(language, dark = false, sunlight = false, tier)`; sunlight forces tier C.
- `AronCard` is the OPAQUE content surface: put every number, status and primary action on it. `GlassSurface` is chrome only (bars, sheets, tile backdrops with a solid label plate).
- `StatusChip`, banners and tile labels use opaque container roles. `LocalSunlight`, hairline 2 dp in sunlight.
- Sunlight setting: `SunlightPreference(context, userId).enabled` (per user, SharedPreferences), `SunlightToggle(on, onToggle)` for the top bar, `rememberSunlightSuggestion(sunlightOn)` reads the system brightness once per resume (85 percent or more) and `SunlightSuggestionChip`.
- `TokenContrastTest` computes WCAG contrast for every pair: key figures 7:1 in every mode (10:1 in sunlight), body 7:1 on tier B surfaces, secondary 4.5:1, 35 percent glare proxy (4.5 key, 3 body).
- Deviation: docs/32 asks for Bangla body weight 500 in sunlight; only 400 and 700 are bundled, so sunlight sets Bangla body in bold (log for the lead).
- Not done: screenshot tests of home, sale entry, review and memo (needs a screenshot library and those screens; request file to follow). Battery-saver and reduce-transparency engagement of sunlight mode: tier C is already forced by `GlassPolicy`; the sunlight colours themselves stay a user switch.

## Next
Scope change (lead, 2026-10-07): F-SYS-023, 030, 010, 037, 019, 022, 020, 021 moved to the Opus lane `android-sys`. This lane keeps N-023 (kit, gallery, overflow tests), the outdoor-first additions, design v1 adoption and tokens. Remaining: Sonnet checker on the outdoor-first slice, screenshot tests (request file), and kit components android-sys asks for (permission rationale, language switch screen).

## Traps
- Local Gradle cannot resolve (Maven Central 429): CI is the compiler. Push to `lane/android-core-ui`, merge INT only when green.
- Press-and-hold: the long-click (accessibility) path is unit-tested; the timed 1.2 s hold is checked on the phone (DEVICE-PENDING: hold shorter than 1.2 s must not confirm, full hold must).
