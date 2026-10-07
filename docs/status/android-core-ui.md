# Status: lane android-core-ui

## In progress
- **N-023** shared UI kit, published in slices. Slice 1 (theme light/dark, button, list row, banner, tile grid, stepper) pushed to `lane/android-core-ui`; compiled by the CI Android job only (Maven Central answers 429 locally, no mirror used). Merged to INT when green.

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

## Next slices
press-and-hold button, dialogs (confirm/info), empty / error states, kit gallery screen + font-scale 1.3 test; then F-SYS-023, F-SYS-030, F-SYS-010, F-SYS-019, F-SYS-022, F-SYS-020, F-SYS-021, F-SYS-037.

## Traps
- Local Gradle cannot resolve (Maven Central 429): CI is the compiler. Push to `lane/android-core-ui`, merge INT only when green.
- Press-and-hold: the long-click (accessibility) path is unit-tested; the timed 1.2 s hold is checked on the phone (DEVICE-PENDING: hold shorter than 1.2 s must not confirm, full hold must).
