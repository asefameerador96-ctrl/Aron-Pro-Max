# Status: lane android-amo-shell (A1), started 2026-11-06

Branch `lane/android-amo-shell` (from lane/lead-amo-manual bcff2953). Push only that branch; merge INT before every push; one push per CI run.

## Push 1 (look, Home, navigation, strings)
- **Done:** `AronLook.AmoPurple` / `AmoSunlight` + AMO-only roles in `AronLookColors` (null in SR/TSO), `AronTheme(amo = true)` on the signed-in AMO branch of `MainActivity`; `TokenContrastTest.amo*` (7:1 button labels, bar title, ink, key figures, target-card figure); Home = 17 tiles in the manual's order (`AmoMenu.tiles`), header "<name> (<username>)" over "<zone> AMO, <date>", Outlet badge in locale digits, chevron folding the grid, four target cards fed by `CallTargetsSource` (fixture `UnsetCallTargets`: "—"); `AmoScreen` has one entry per tile, Team and Sale hosts open on their leaf, Sales Journey empty state, Exceptions as a Settings row; strings split (`strings_shell.xml`, empty `strings_calls/team/outlet.xml`); tests `AmoMenuTest`, `AmoAppTest`, `AmoNavigationCoverageTest`, `CallTargetsTest`; goldens `AmoShellScreenshotTests` (Home bn/en/fs2/Sunlight, target cards set/unset) and every other `amo_*` golden re-recorded in the AMO look.
- **Shared-module changes (named for owners):** core-ui `Tokens.kt` (new optional roles, two looks), `AronTheme(amo)`, `Chrome.kt` (`AronPrimaryButton` uses `primaryStart` when set, `AronTopBar` round back chip when `roundBack`, `AronHomeHeader(showSettings)`), feature-amo `AmoMenu`/`AmoScreen`; app-amo `AmoTeamHub`/`AmoSaleHub` gained a `start` leaf parameter.

## Open (push 2) and assumptions
See DECISIONS.md D-AMO-SHELL-01..04. Not started: attendance parity 14–16, login/permission texts 3–4, update flows 5–7, Stock 41 and printer icon, Settings 75–77, release copy name, side-by-side PNGs.

## Traps
- A failed gradle pipe through tail reports exit 0: grep `BUILD`. Gradle online; `echo sdk.dir=/opt/android-sdk > local.properties` after `tools/android-sdk.sh`.
- Goldens only on Linux: `-Proborazzi.record=true --tests '*Amo*'`.
