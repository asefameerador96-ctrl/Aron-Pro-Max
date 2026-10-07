# Request: drop `columns = 4` from AronTileGrid callers
Date 2026-10-07. From android-core-ui to android-sr-a (feature-home) and the outlet owner (feature-outlet).
`AronTileGrid(columns = null)` now picks 3 columns at 360 dp (2 at font scale 1.5 and above) so Bangla labels do not break mid-word. Please remove the `columns = 4` argument in:
- android/feature-home/src/main/kotlin/com/aktcl/aron/feature/home/HomeScreen.kt:88
- android/feature-outlet/src/main/kotlin/com/aktcl/aron/feature/outlet/OutletMenu.kt:26
After that, the 3 Home goldens in android/ui-screenshots must be re-recorded (docs/status/android-core-ui.md, Screenshot tests); ping android-core-ui.
