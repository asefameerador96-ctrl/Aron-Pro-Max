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

## Next slices
press-and-hold button, dialogs (confirm/info), empty / error states, kit gallery screen + font-scale 1.3 test; then F-SYS-023, F-SYS-030, F-SYS-010, F-SYS-019, F-SYS-022, F-SYS-020, F-SYS-021, F-SYS-037.

## Traps
- Local Gradle cannot resolve (Maven Central 429): CI is the compiler. Push to `lane/android-core-ui`, merge INT only when green.
- Press-and-hold: the long-click (accessibility) path is unit-tested; the timed 1.2 s hold is checked on the phone (DEVICE-PENDING: hold shorter than 1.2 s must not confirm, full hold must).
