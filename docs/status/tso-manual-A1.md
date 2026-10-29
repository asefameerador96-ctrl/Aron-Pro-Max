# tso-manual A1 android-shell-dashboard-targets: lane status

Lane A1 of `docs/requests/tso-manual-build-2026-10.md` (s5 A1). Branch `tso-man/A1`, worktree
`.claude/worktrees/tso-A1`, base `95a35b7d` (foundation F1+F2+F3 merged). No push, no pull request. Written 2026-10-29.

## Step 1: a1-stubs (done)

The stub commit s5 A1 asks for, behaviour unchanged. Branch pointer `tso-man/A1-stubs` marks it: A2 and A3 merge that
ref, not the moving `tso-man/A1`.

| what | where |
|---|---|
| `enum class TsoScreen` (the 21 entries, contract order), `interface TsoNav { open(screen, arg = null); back(); home() }`, `typealias TsoScreenContent = @Composable (nav: TsoNav, arg: String?) -> Unit`, `TsoRoute`, the saved back stack `TsoNavState` (internal) | `android/feature-tso/.../TsoNav.kt` (new) |
| `TsoApp(…, changePassword, support, screens: Map<TsoScreen, TsoScreenContent>)`: the root is still the tile Home; Settings, Change Password and PDA to Support are built in (A1); every other screen comes from `screens`; a missing entry shows the designed placeholder | `android/feature-tso/.../TsoApp.kt` |
| Area flows moved out of TsoApp.kt with their bodies unchanged (same retained keys, same back paths): `LeaveFlow`, `FinalSubmitFlow`, `PeripheryFlow` + `PeripheryDeps` (moved to the `periphery` package), `MyCallFlow`, `FeedbackFlow` | `feature-tso/.../leave/LeaveFlow.kt`, `finalsubmit/FinalSubmitFlow.kt`, `periphery/PeripheryFlow.kt`, `mycall/MyCallFlow.kt`, `feedback/FeedbackFlow.kt` (new, A3 owns them from now on) |
| `class TsoHost` (one per signed-in user: context, session components, databases, the TSO outbox, read models, user id and key, `online`, `media`, `mediaFor`, `today`, `nowMs`, `nowIso`) | `android/app-tso/.../MainActivity.kt` (end of file) |
| `class AreasHostDeps(val host: TsoHost)` (ports created lazily once per user, as the old `remember(userId)` params were) and `fun areaScreens(deps: AreasHostDeps): Map<TsoScreen, TsoScreenContent>` | `android/app-tso/.../AreasHost.kt` (new, A3 owns it from now on) |
| `class TicketsHostDeps(val host: TsoHost)` and `fun ticketScreens(deps: TicketsHostDeps): Map<TsoScreen, TsoScreenContent> = emptyMap()` | `android/app-tso/.../TicketsHost.kt` (new, A2 owns it from now on) |
| MainActivity builds `TsoHost` once per user and passes `screens = dashboard + areaScreens(...) + ticketScreens(...)`; the outbox is now its own lazy (same instance the read models use) | `android/app-tso/.../MainActivity.kt` |
| Kit signatures with minimal working bodies: `TsoFrame(title, nav, homeButton, bottomBar = null, modifier, onBack = nav.back, content)` (an overload; the old `TsoFrame(title, onBack, …, tile, bottomBar: RowScope)` stays for the screens not migrated yet), `TsoDropdownField`, `TsoGlowField`, `TsoGradientButton(style = TsoButtonStyle.Lime/Blue/Cyan)`, `TsoCard`, `TsoSegmentedTabs`, `TsoPillTabs`, `TsoStatusPill(tone = TsoPillTone.*)`, `TsoKitTags` | `android/feature-tso/.../TsoKit.kt` |
| Three placeholder labels (bn + en): `tso_drawer_tickets`, `tso_drawer_target_status`, `tso_chrome_notifications` | `feature-tso/src/main/res/values/strings_a1.xml`, `values-bn/strings_a1.xml` (new) |
| Tests: `TsoNavTest` (new: the 21 names in order, push/pop/home, double tap, save and restore with arguments, unknown name dropped, frame back and Home button, no Home button when false); `TsoNavigationTest`, `TsoAppTest`, `TsoChromeTest`, `TsoFeedbackFlowTest` follow the new wiring (screens map built with the moved flows exactly as AreasHost builds it) | `android/feature-tso/src/test/...` |

### Kit signatures (for A2 and A3)

```kotlin
fun TsoFrame(title: String, nav: TsoNav, homeButton: Boolean, bottomBar: (@Composable () -> Unit)? = null,
             modifier: Modifier = Modifier, onBack: () -> Unit = { nav.back() }, content: @Composable BoxScope.() -> Unit)
enum class TsoButtonStyle { Lime, Blue, Cyan }
fun TsoGradientButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, style: TsoButtonStyle = TsoButtonStyle.Blue,
                      enabled: Boolean = true, busy: Boolean = false)
fun TsoCard(modifier: Modifier = Modifier, glow: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit)
fun TsoGlowField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, label: String? = null,
                 placeholder: String? = null, enabled: Boolean = true, singleLine: Boolean = true, minLines: Int = 1,
                 isError: Boolean = false, supportingText: String? = null, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
                 visualTransformation: VisualTransformation = VisualTransformation.None)
fun <T> TsoDropdownField(options: List<T>, selected: T?, onSelect: (T) -> Unit, optionLabel: (T) -> String,
                         modifier: Modifier = Modifier, label: String? = null, placeholder: String = "", enabled: Boolean = true)
fun TsoSegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier)
fun TsoPillTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier)
enum class TsoPillTone { Neutral, Info, Success, Warning, Danger }
fun TsoStatusPill(text: String, modifier: Modifier = Modifier, tone: TsoPillTone = TsoPillTone.Neutral)
object TsoKitTags { HOME; segment(i); pill(i) }
```

A1's next step restyles these bodies (TsoNight tokens, glow, HomeFab, AronDropdown) without changing the signatures.

## Step 2: look, shell and Dashboard (done, except the items under "Not done")

Base of the step: `5e9c52b0` (a1-stubs). Commits, oldest first: `4b79aff9` core-ui TSO look, `9757874f` TSO login,
`b6e21b0e` logout wipes `aron_ui_<id>.xml`, `2e10c2c8` shell and Dashboard, `699fdc6c` release copy name, `df337d6e`
pie angle from integers, `adf55b55` docs, `6e8d2c3c` fit at 200 percent and the drawer widths, then the screenshot-test
commit and this status commit.

| item | what | where |
|---|---|---|
| D-TSO-MAN-13 look | `AronNightRoles` (Night, Sunlight) sampled from `docs/ui-reference/tso/05-10, 17, 24, 39, 44`; `AronLook.TsoNight` (the shared kit's navy roles plus `night`) and `AronLook.TsoSunlight`; `AronTheme(tso = true)` | core-ui `Tokens.kt`, `AronTheme.kt` |
| TSO kit primitives | `NightTitle` (gradient), `NightBackButton`, `NightGlowCard`, `NightOutlineCard`, `NightRowCard`, `NightGlowField`, `NightButton` (Cyan, Lime, Blue, Cancel, LogOut), `NightRing`, `NightBar`; `AronNightTopBar` (Chrome.kt, appended); `AronDropdown`; `AronHomeFab`; icons for the drawer and chrome | core-ui `aron/Night.kt`, `aron/Chrome.kt`, `aron/AronDropdown.kt`, `aron/HomeFab.kt`, `Icons.kt` |
| contrast | `TokenContrastTest`: six TSO tests (figures, ink, titles and button labels 7:1; captions, chips, segment, badge 4.5:1; graphics 3:1; Sunlight 10:1; the kit roles of TsoNight) | core-ui test |
| D-TSO-MAN-15/16 login | `LoginScreen(variant = LoginVariant.Tso)` draws `TsoLoginContent`: mint-to-pink plate, labels above glow fields, centred cyan Login, eye, language toggle at the bottom; no sun switch or version (Settings has them); no OTP | feature-auth `TsoLogin.kt`, `LoginScreen.kt`, `strings_a1.xml` |
| D-TSO-MAN-14 shell | `TsoRootTopBar` (menu, "Good day, <full_name>", bell + badge, "99+"), `TsoDrawerContent` (header TSO + name + gear; rows, labels and subtitles from screenshots 9, 10, 27, 30, 44; one group open at a time; the last open group is kept), `TsoDrawer.entries`, `TsoFab` (the s8 column), `TsoLogoutDialog` (+ `TsoLogoutDialogContent` for goldens) | feature-tso `TsoChrome.kt` (new) |
| root and back | Dashboard is the root (empty stack); a drawer row opens its screen on the Dashboard (`TsoNavState.openFromDrawer`), so back from any drawer screen is the Dashboard; the gear opens Settings; the bell opens NOTIFICATIONS (placeholder until the inbox step); Log Out asks, then `onLogout` (the host refuses while unsent). The tile Home (`TsoHome.kt`) is deleted | feature-tso `TsoApp.kt`, `TsoNav.kt` |
| kit bodies | `TsoFrame` (both overloads) on the TSO top bar and navy page, the HomeFab when `homeButton`; `TsoGradientButton`, `TsoCard` (glow / outline), `TsoGlowField`, `TsoDropdownField` (on `AronDropdown`), `TsoSegmentedTabs`, `TsoPillTabs`, `TsoStatusPill`. Signatures unchanged; `TsoFabPadding` added | feature-tso `TsoKit.kt` |
| theme | `TsoLoginTheme` is the TSO look for the login and every signed-in screen; Sunlight overrides it; light bar icons unless Sunlight | feature-tso `TsoLoginTheme.kt` |
| Settings | TSO top bar; language, Sunlight, version, update, PDA to Support, Change Password; no logout button (the drawer has it) | feature-tso `TsoSettings.kt` |
| RoleHome | `tsoTiles()` deprecated: the TSO app no longer calls it; kept only for the shared role-home goldens (ask below); `TsoTile` ids stay as icon ids of A3's frames | feature-home `RoleHome.kt` |
| Dashboard cards | one parser per national card; every figure through `Percent`, `Qty`, `Targets` from the integers: Sales (`Qty.text(A)`, `Qty.text(T, W)`, `Targets.dailyRingTenths`), Geo (tenths), By Channel STD / Successful Call (bp, 2 dp; pie angles from integers), CPR (tenths), Brand CPR (whole over the configured whole; axis ticks `Percent.whole(maxBp x k, steps x 10000)`), Login (whole of target routes), Bikroy Joma (submitted / target routes, D-TSO-MAN-07), Segment (value bp, volume bp inside the unit family). `achievement_pct_bp` decoded as Long, never displayed (F1 ask 15) | feature-tso `TsoDashboard.kt` |
| Dashboard screen | screenshots 6 and 7 card by card; pull-to-refresh; "As of hh:mm" (oldest stamp) or "Offline: showing the copy of hh:mm"; a card without figures says why (offline, refused, none); the ▸ switch (D-TSO-MAN-41); Login & Bikroy Joma Status (screenshot 8: tabs, rows, "SR Not Set", truncated note) | feature-tso `TsoDashboardScreens.kt` |
| reads | `TsoApi.post` (per-card cache key `nat_<card>_<day>`, body `{"date":"<day>"}` only, 403/404/501 drop the copy), `card()`, `loginSubmitUsers(tab)`; `summary()` and the GET login-submit are gone. `TsoDashboardModel` (one per signed-in user: reads on open and pull-to-refresh, the eight cards in parallel, a second pull while one runs is ignored; the ▸ choice in `aron_ui_<id>`); `dashboardScreens(host)` | app-tso `TsoApi.kt`, `TsoDashboardHost.kt` |
| MainActivity | TSO login variant; `screens = dashboard + areas + tickets`; `webOrigin` dropped; after a logout that was not refused, the dashboard copies are deleted and `viewModelStore.clear()` drops every retained screen state (SH-17) | app-tso `MainActivity.kt` |
| SH-17 wipe | `DatabaseLogoutPorts.userFiles` includes `aron_ui_<id>.xml` (`uiPreferences`) | core-system `logout/DatabaseLogoutPorts.kt` |
| D-TSO-MAN-42 | `release-apks.py --tso-copy-name <code> [<date>]` (Dhaka build date); `release-app.yml` copies the signed TSO APK under that name before `SHA256SUMS`; `test_gates.py` `TsoReleaseCopyName` (5 tests) | tools/ci, workflow |
| docs | docs/08 header, Dashboard, Drawer menu, new Settings and Log Out sections (Bikroy Joma corrected to ÷ target routes, D-TSO-MAN-07); PARITY-SPEC lines 5 and 47 (TSO superseded by D-TSO-MAN-13); delta-tso.md header note and "A1 shell deltas" section (enrolment gate, location notice, CR-1, CR-2 recorded) | docs |

### Tests added or changed in step 2

TsoShellTest (13: drawer order and children, labels and subtitles of screenshots 9/10/27/30/44 in English and Bangla,
Dashboard root with greeting, badge, bell and Home, back from a drawer screen and from a pushed sub-screen, the
Dashboard row, gear to Settings, the s8 FAB table and the button on Y/N screens, placeholder frame, stack semantics);
LogoutDialogTest (3); TsoDashboardCardsTest (every s4.2 fixture of `kpi-format-corpus.json`: 26 fixtures in en and bn
through the card parsers and printers, plus screenshot 6 order and figures, Long `achievement_pct_bp`, W = 0, Brand CPR
denominators, the screenshot 7 axis, unit families, pie angles, users rows, malformed answers, date-only body, Dhaka
"as of", oldest stamp); TsoApiTest (6: eight national POSTs with the date only and no summary call, users tab query,
kept copy per card and day, refusal drops the copy, the ▸ choice in `aron_ui_<id>`, no read without a call);
TsoLoginScreenTest (6); LogoutWipeUiPrefsTest (3); TokenContrastTest (+6); TsoLoginThemeTest, TsoAppTest, TsoChromeTest,
TsoNavigationTest, TsoFeedbackFlowTest rewritten for the drawer. Removed with the summary model: TsoDashboardsTest,
TsoCompactCorpusTest (lakh/crore are gone from the phone, D-TSO-MAN-10).

## Decisions

- **D-A1-01 Root during the stubs.** The empty back stack is the tile Home, so behaviour is unchanged until A1's shell
  step makes DASHBOARD the root (D-TSO-MAN-14). `TsoNav.home()` empties the stack; `back()` at the root does nothing (the
  system back then leaves the activity, as before).
- **D-A1-02 Tile to screen map (legacy, internal `TsoTiles`).** Dashboard -> DASHBOARD, Leave -> LEAVE, Final Submit ->
  FINAL_SUBMIT, My Periphery -> MY_TEAM, My Call -> MY_VISIT_PLAN, My Feedback -> FEEDBACK_LIST. The hub tiles open the
  screen whose entry hosts the hub today (the hub, then My Team / Retailer, Set Plan / My Visit Plan, list / new). The
  map and the tile Home go with A1's shell step.
- **D-A1-03 One entry per former tile in `areaScreens`.** RETAILER, SET_PLAN and FEEDBACK_NEW have no entry yet; A3
  splits the flows per screen when the drawer replaces the hubs (A1 shell step). Until then the drawer is not there, so
  nothing opens those three.
- **D-A1-04 Back stack.** `open` pushes (the same route on top is a no-op: a double tap never stacks a screen twice);
  the stack is saved as plain strings and an unknown name is dropped on restore. Arguments are plain strings (a ticket
  uuid, a node id), never scope ids.
- **D-A1-05 Hosts get a `TsoHost`.** `AreasHostDeps` and `TicketsHostDeps` wrap the per-user `TsoHost`, so A2 and A3
  build their ports inside their own host file and never edit MainActivity (A1's file).
- **D-A1-06 Old TsoFrame kept.** The screens A3 owns still call `TsoFrame(title, onBack, modifier, tile, bottomBar)`;
  both overloads live in TsoKit.kt until those screens move to the `nav` frame.
- **D-A1-07 TSO roles live in core-ui (step 2).** `AronLookColors.night: AronNightRoles?` carries the TSO-only roles;
  TSO components read `nightRoles()`; Sunlight swaps in `AronNightRoles.Sunlight` (solid). The shared kit roles of
  `AronLook.TsoNight` are navy too, so a TSO screen still drawn with the old kit (A3's) turns navy without an edit.
  Supersedes D-A1-01 and D-A1-02 (the tile Home and `TsoTiles` are gone).
- **D-A1-08 Labels on bright buttons.** Navy (`onBright`) on cyan, lime, green and orange; white on the blue button; all
  7:1 on both gradient stops. Two stops were lifted for that (cyan end `#78A8F2`, orange end `#E8930F`) and the blue was
  deepened (`#2244D0`). See Deviations.
- **D-A1-09 Drawer navigation.** A drawer row replaces the stack with that one screen (`openFromDrawer`), so back from
  any drawer screen is the Dashboard and a screen's own sub-screens push on top. Swipe-to-open only on the Dashboard.
  The drawer keeps its last open group and its scroll while the user is signed in (one group open at a time, as on
  the screenshots).
- **D-A1-10 Dashboard reads.** On open and on pull-to-refresh the eight card reads run in parallel (both channel cards,
  so the ▸ switch shows the other card without a read); a second pull while one runs is ignored. Each card is cached
  per day (key `nat_<card>_<day>`, 3 days, 60 files, as before); the model is kept per signed-in user, so returning to
  the Dashboard shows the last figures while it reads again.
- **D-A1-11 "As of".** One line under the top bar with the OLDEST stamp among the cards on screen ("As of 14:30"), or
  "Offline: showing the copy of 14:30" when any card is a kept copy. A card with no figures says why (offline with no
  copy, refused, nothing yet).
- **D-A1-12 Screens not wired yet** (RETAILER, SET_PLAN, FEEDBACK_NEW, the five ticket screens, TARGET_STATUS,
  TARGET_DETAILS, NOTIFICATIONS) open a placeholder frame (TSO top bar, "<screen> is coming soon", the Home button per
  the s8 table) until their lane adds the map entry.
- **D-A1-13 Home button.** Drawn by the root and by `TsoFrame(title, nav, homeButton = TsoFab.of(screen))`; `TsoFab`
  holds the s8 column (Y: Dashboard, Login & Bikroy Joma Status, My Team, Retailer, Set Plan, My Visit Plan and its
  sub-screens, Add new feedback, and My feedback with it). On the Dashboard it is a no-op (already home).
- **D-A1-14 TSO login button.** Pressable with empty fields (the screenshot draws it enabled); pressing then shows the
  shared empty-fields message. Not pressable while a login runs.
- **D-A1-15 Settings has no logout.** Log Out is the last drawer row with the D-TSO-MAN-37 dialog; `TsoSettingsContent`
  keeps `logoutMessage` and `onLogout` in its signature (unused) so callers compile.
- **D-A1-16 Drawing from integers.** Ring arcs, bar widths, pie angles (`sweepTenths`) and chart heights are drawing
  only, computed from the integer tenths / whole / bp; every printed figure goes through `Percent`/`Qty`/`Targets`.
- **D-A1-17 Retained state at logout (SH-17).** After a logout that was not refused, MainActivity deletes the kept
  dashboard copies and clears the activity's `ViewModelStore` (every `rememberRetained` holder); `aron_ui_<id>.xml` is
  in the wiped files.
- **D-A1-18 `RoleHomes.tsoTiles` deprecated, not deleted.** The TSO app no longer calls it; two shared golden files
  that are not A1's still do (ask below). `TsoTile` ids stay: A3's old frames pass them.

## Deviations

- The DEVICE_OTP branch of TsoApp is gone with the old enum. It was unreachable (the tile was filtered out,
  D-AUTH-TSO-NOBIND). `webOrigin` stays in the signature (callers compile) and is unused.
- Settings > Change Password and Settings > PDA to Support are now pushed screens (CHANGE_PASSWORD, SUPPORT_UPLOAD)
  instead of steps inside Settings. Same back paths (back returns to Settings, then Home); the switch is animated like
  any other screen change.
- Step 2, against the screenshots (rule 14: legibility wins; all recorded in delta-tso.md "A1 shell deltas"): navy
  labels on the bright buttons (screenshots show white, about 2:1); three colour stops moved (D-A1-08); the channel pie
  labels sit in a legend under the pie instead of on the slice (readable at 360 dp and in Bangla, read by TalkBack);
  chart x labels are slanted 30 degrees; the blue bottom glow is drawn only with the Home button (screenshots 24 and 39
  show it without one); an "as of" line and pull-to-refresh (contract); the drawer header gear (D-TSO-MAN-14).
- Step 2: `TsoApp` lost the tile-only parameters (`hero`, `health`, `badges`, `banners`, `syncedAt`, `onSync`, `webOrigin`)
  and gained `unread`; the first six positional parameters are unchanged, so A3's tests compile.
- Step 2: the old summary model (`TsoDash`, `CategorySales`, `SliceRow`, `ZoneRef`, `RouteDayRow`, the zone filter, lakh and
  crore) is removed with `/v1/dashboards/summary`; the class names `TsoDashboards`, `TsoDashboardState` and
  `TsoDashboardContent` are reused, so the unused imports of `AmoTsoScreenshotTests` (not A1's) still resolve.

## Asks

- **Lead (money gate, blocking CI on this branch):** removing the summary model made four entries of
  `tools/ci/money-float-allow.txt` stale (lines 18 to 21, all `android/feature-tso/.../TsoDashboard.kt`: `val qtyReport:
  Double?`, `val ratioPct: Double?`, `qty_report")?.toDoubleOrNull()`, `pos.map { it * 100.0 / total }`). Please delete them
  and lower `CEILING` in `tools/ci/money-float-scan.py` from 17 to 13. Neither file is A1's. Until then
  `python3 tools/ci/money-float-scan.py` exits 1 with "4 stale"; it reports 0 not-allowed lines.
- **Owner of the shared role-home goldens (lead):** `RoleHomeShot.kt` (`tso_home_bn_light`, `tso_home_bn_sunlight`) and
  `ScreenshotTests.kt` (`TsoHome`) still draw the retired TSO tile Home through `RoleHomes.tsoTiles` (now `@Deprecated`).
  Retire those cases and their PNGs; A1 then deletes `tsoTiles()`.
- **A3:** (1) Move your screens to `TsoFrame(title, nav, homeButton = TsoFab.of(TsoScreen.X))`: s8 wants the Home button on
  MY_TEAM, RETAILER, SET_PLAN, MY_VISIT_PLAN (and its outlets, query, task) and FEEDBACK_NEW / FEEDBACK_LIST; none on LEAVE
  and FINAL_SUBMIT. (2) The drawer already opens RETAILER, SET_PLAN and FEEDBACK_NEW: add their `areaScreens` entries (a
  placeholder frame shows until then); MY_TEAM, MY_VISIT_PLAN and FEEDBACK_LIST open your current flows. (3) The old
  `TsoFrame(title, onBack, ...)` now draws the TSO top bar (its `tile` is ignored) and the kit bodies are restyled; your
  logic is untouched. (4) `TsoScreenshotTests.shot()` now renders `AronTheme(tso = true)`: your cases there need
  re-recording (you move them to TsoAreaScreenshotTests anyway). (5) `TsoFeedbackFlowTest` and `TsoNavigationTest` now
  open My Feedback, Leave, Final Submit, My Team and My Visit Plan through the drawer.
- **A2:** the five ticket screens open from the drawer's Tickets group (`TsoDrawer`); s8 gives them no Home button
  (`TsoFab.of(...)` is false); use the `nav` overload of `TsoFrame`. `TsoDropdownField` is now core-ui `AronDropdown`
  (tags `AronDropdownTags`).
- **A2 and A3 (earlier asks still stand):** build ports from `TsoHost` inside your host files; never edit MainActivity.

## Verify (this worktree, local laptop; CI not run, nothing pushed)

Step 1, 2026-10-29 04:48 to 04:51 (Dhaka), one Gradle call through gl.sh:

| command | result |
|---|---|
| `gl.sh … :android:feature-tso:testDebugUnitTest :android:app-tso:testDebugUnitTest :android:app-tso:assembleDebug :android:ui-screenshots:compileDebugUnitTestKotlin --continue` | BUILD SUCCESSFUL in 2m 36s; every listed task executed (none from cache) |
| feature-tso unit tests | 32 classes, 154 tests, 0 failures, 0 errors (TsoNavTest 5, TsoNavigationTest 2, TsoAppTest 4, TsoChromeTest 5, TsoFeedbackFlowTest 2) |
| app-tso unit tests | 4 classes, 23 tests, 0 failures, 0 errors |
| app-tso debug APK | built (`app-tso-debug.apk`) |
| ui-screenshots test sources (TsoScreenshotTests, TsoLargeTextScreenshotTests, AmoTsoScreenshotTests) | compile; not run (no goldens recorded or changed in this step) |

Goldens needed: none new in step 1 (no screen's pixels changed on purpose; the kit bodies are not used by any screen
yet).

Step 2, 2026-10-29 (Dhaka; the Gradle lock was held by other lanes for most of the time between runs):

| command | result |
|---|---|
| `gl.sh … :android:core-ui:testDebugUnitTest :android:feature-tso:testDebugUnitTest :android:feature-auth:testDebugUnitTest :android:app-tso:testDebugUnitTest :android:core-system:testDebugUnitTest --tests '*Logout*' --tests '*UnsentItems*' --tests '*CheckerF022*' :android:ui-screenshots:testDebugUnitTest --tests '*.TsoScreenshotTests' --tests '*.TsoLargeTextScreenshotTests' -Paron.screenshots.recordTo=<scratch> --continue` (07:34 to 07:36, on `6e8d2c3c` plus the uncommitted goldens sources) | BUILD FAILED only by `:android:core-ui:testDebugUnitTest` (1 test, see below); every other task passed |
| feature-tso | 32 classes, 165 tests, 0 failures, 0 errors (TsoShellTest 13, TsoDashboardCardsTest 15, LogoutDialogTest 3, TsoNavigationTest 2, TsoAppTest 4, TsoChromeTest 4, TsoLoginThemeTest 4, TsoFeedbackFlowTest 2, TsoNavTest 5, the A3 classes unchanged) |
| app-tso | 5 classes, 29 tests, 0 failures (TsoApiTest 6) |
| feature-auth | 7 classes, 47 tests, 0 failures (TsoLoginScreenTest 6, LoginScreenLocaleTest 5) |
| core-system (filtered) | 4 classes, 14 tests, 0 failures (LogoutWipeUiPrefsTest 3, LogoutFlowTest, CheckerF022Test, UnsentItemsDialogTest) |
| core-ui | 22 classes, 151 tests, 1 failure: `ScannerSourceSetsTest.aLiteralInMainIsFlaggedButNotInAnyTestSourceSet` (line 28 asserts `file.contains("src/main/")`; on Windows the scanner's path has backslashes). Not A1's: no A1 commit touches the scanner or that test (`git log 5e9c52b0..HEAD -- …ScannerSourceSetsTest.kt …HardcodedStringScanner.kt` is empty); TokenContrastTest 22/22 and HardcodedStringScanTest pass |
| ui-screenshots, record mode into the scratch dir (no PNG committed) | 2 classes, 45 tests, 0 failures; the A1 PNGs looked at against screenshots 5 to 10, 27, 30, 44 (login, Dashboard top and full in en/bn/Sunlight, Login & Bikroy Joma Status, drawer with each group, logout dialog, 200 percent); fixes from that look are in `6e8d2c3c` |
| `python tools/ci/money-float-scan.py` | 0 lines not allowed; exit 1 for 4 stale allow entries of the removed summary model (ask to the lead above) |
| `python tools/ci/kpi-format-corpus.py --check` | up to date |
| `python -m unittest test_gates.TsoReleaseCopyName test_gates.ReleaseApks` (tools/ci) | 8 tests OK. The full `test_gates` run on the laptop also shows 2 ReleaseManifest failures (they fail the same way with A1's changes stashed) and 3 MigrationsCheck errors (the bash migrations script; A1 does not touch it) |

CI not run, nothing pushed.

Goldens needed (record on the Linux runner, `-Proborazzi.record=true`, TsoScreenshotTests and TsoLargeTextScreenshotTests):
- New, A1: `tso_login_en`, `tso_login_refused_bn`, `tso_login_sunlight_en`, `tso_dashboard_top_en`, `tso_dashboard_full_en`,
  `tso_dashboard_full_bn`, `tso_dashboard_full_sunlight_en`, `tso_dashboard_calls_kept_en`, `tso_dashboard_offline_empty_en`,
  `tso_dashboard_loading_en`, `tso_login_status_en`, `tso_login_status_bn`, `tso_login_status_offline_en`, `tso_drawer_en`,
  `tso_drawer_tickets_en`, `tso_drawer_periphery_bn`, `tso_drawer_my_call_sunlight_en`, `tso_logout_dialog_en`,
  `tso_logout_dialog_bn`, `tso_dashboard_200_bn`, `tso_drawer_200_bn`, `tso_login_200_en`.
- Re-record (the look changed to TsoNight): `tso_settings_parity_bn` and every A3 case still in the two files
  (`tso_leave_*`, `tso_feedback_*`, `tso_set_plan_*`, `tso_visit_query_bn`, `tso_final_submit_*`, `tso_change_password_bn`,
  `tso_my_call_hub_bn`, `tso_periphery_hub_en`, `tso_leave_apply_200_bn`, `tso_final_submit_pick_200_bn`,
  `tso_final_submit_review_200_en`, `tso_visit_query_3line_bn`), unless A3 has moved them first.
- Obsolete (their tests are gone; delete the PNGs): `tso_home_parity_bn`, `tso_home_parity_offline_en`,
  `tso_home_parity_sunlight_bn`, `tso_home_200_bn`, `tso_dashboard_en`, `tso_dashboard_bn`, `tso_dashboard_empty_offline_en`,
  `tso_dashboard_sunlight_bn`, `tso_dashboard_routes_bn`, `tso_dashboard_routes_empty_en`, `tso_dashboard_zones_en`.

## Not done in step 2

- The inbox: the bell shows `unread` (MainActivity passes 0) and opens NOTIFICATIONS, which is the placeholder frame;
  TsoInboxHost, the NOTIFICATIONS screen, TsoInboxTest and the push reasons (`notification`, `ticket_updated`,
  `day_final_submitted` in core-sync PushMessage / PushNotices) are the next step (D-TSO-MAN-26, SH-08).
- Target Status and Details (T-01..11, D-TSO-MAN-39/40, TargetStatusTest with the s4.3 fixtures and the 33114 card of
  R-F-01): next step; the drawer row opens the placeholder today.
- Goldens are not committed (Windows laptop); device checks (s8 rows 2, 3, 5 to 10 on a pilot phone) not done.

## Next step

A1 step 3: (1) Target Status (`targets/*`, `TsoTargetsHost.kt`): `getTargetAchievement?month&product_level=category&children=zone`
and Details `level&node_id&product_level=variant`, each cached per month; cards per D-TSO-MAN-40 through `Targets.cardPct`,
`Targets.band`, `Targets.detailsBp`, `Targets.remaining` only; TargetStatusTest with every s4.3 fixture, tab state that keeps
the node, the category filter, and the F3 ask: a card built from the eight Cigarette items is 33114 at 21/26 (R-F-01);
R-F-24 per-node `period_days` = 0 prints "—"; decode every widened `*_bp` as Long (F1 asks 15 and 18; the generated
`TargetAchievementRow` already has `Long?`). Goldens for screenshots 39 to 42. (2) The inbox (`notifications/*`,
`TsoInboxHost.kt`): unread count read on Dashboard open and pull-to-refresh and on push reason `notification` (no
polling), the plain list, mark read up to the newest id, offline cache; TsoInboxTest; push reasons in core-sync. (3) After
the lead retires the shared role-home goldens, delete `RoleHomes.tsoTiles`. The shell, the look, the login, the Dashboard
and Login & Bikroy Joma Status, the logout and the release copy are done (this step).
