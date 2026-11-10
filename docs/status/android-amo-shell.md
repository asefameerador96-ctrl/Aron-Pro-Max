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

## Push 2 (attendance 14-16, login 3, update 5-7, Stock 41 toast, Settings 75-77, release copy name)
- Done: AMO style of the shared attendance screen (`amoStyle`, dome hold button `AronDomeHoldButton`, dashed status boxes, 12-hour Latin chip, "Already Checked-In/Out" in the user's language); login footer "ARON AMO v<version>" (`footerText`); `UpdateContent(amoStyle)` texts; Settings = three tiles (language drop-down, PDA, Log out) + Sunlight/about/printer/exceptions rows; printer-connected toast (`PrinterToast`); PDA "সফল" dialog when the send is already Sent; `release-apks.py --amo-copy-name`, `amo-apk-copy.sh`, release and CI steps + tests; goldens (`AmoShellScreenshotTests`) and `docs/evidence/ui-parity/amo-03,08,09,14,15,16,41,75,77.png` (`tools/ui-kit/amo-side-by-side.py`).
- Opus checker (attendance gate, release name, toast): gate and release PASS; two defects fixed (toast after a print; PDA dialog). The PDA dialog is not polled (NoPollingLintTest): a queued upload shows its status text instead.
- Open: Stock 41 uses the shared Stock screen with the AMO look (panel is solid, the vendor's is a blue-to-indigo gradient); login permission dialogs 3-4 are Android system dialogs (device); update flow 7's forced-after-login uses the same screen; orange box after 17:00 with Sales Submit pending shows a grey Check Out (the notice above explains).
