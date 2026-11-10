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

## HANDOVER (2026-11-07; A1 #2 continues from branch lane/android-amo-shell at the sha of this commit)
Both trains of A1 are done and checked (push 1 9baf3f67 = INT train 81; push 2 40ef66c7 = train 82 with the lead's Opus PASS). Merge INT before any push; one push per CI run; `grep BUILD` the gradle log; goldens only on Linux (`-Proborazzi.record=true --tests '*Amo*'`); `python3 tools/ui-kit/amo-side-by-side.py` after re-recording. Not polling anything (NoPollingLintTest).
Open, none blocking (lead's checker notes):
1. `checkoutAfterLabel` (feature-attendance AttendanceScreen.kt): use `String.format(Locale.ROOT, ...)` and the same `require(h in 0..23 && m in 0..59)` as `clock12`.
2. app-amo MainActivity.kt login footer: `"ARON AMO v" + version` is joined in Kotlin and `LoginContent`'s `localizedDigits` turns the version Bangla; use a format string and keep the version digits Latin (e.g. do not pass the version through `localizedDigits` when `footerText` is set).
3. `values-bn/strings_shell.xml` repeats `amo_shell_lang_en translatable="false"` (lint warning): drop the bn copy; "en" stays the item label (R16 over R2).
4. Golden matrix: add the 200 % shot for the attendance "waiting" state and the Sunlight shot for "done".
5. Doc nits: `AronDomeHoldButton` KDoc ("white arc along the rim" is really the bottom bar); the SystemScreens.kt comment ("Settings stays behind it" = the Support screen); `AmoUpdateRibbon` hard-coded colours to tokens.
6. Wire the real call-targets client when lane/amo-foundation F1 lands (shape: `{as_of, business_date, period:"YYYY-MM", today|month|control|joint:{done,target}}`, target null = "—"); a failed load keeps the last good values and never blanks to "—". Source interface: `CallTargetsSource` (MainActivity `callTargetsSource`). Also read `zone_name` / `territory_name` from the supervisor section when the server adds it.
7. Stock panel is solid (vendor: blue-to-indigo vertical gradient); a kit change in `AronScaffold`/`panel` would be needed. Device-only: screens 3-4, 10-11 (system dialogs), 76 Sent dialog.
