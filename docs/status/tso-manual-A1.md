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

## Step 3: Target Status, the bell inbox, push reasons (done, except the items under "Not done")

Base of the step: `3ce6af81` (end of step 2). Items: T-01, 02, 04 (app), 06, 08, 10, 11 (app); SH-08 (app); D-TSO-MAN-26 (app),
39, 40; R-F-01, R-F-24; F1 asks 15 and 18 (A1 part); F3 ask (33114). SH-01 (release name) was done in step 2 (`699fdc6c`) and is
re-verified here.

| item | what | where |
|---|---|---|
| Target model | `TargetTab`, `TargetRow` (integers only: T, A, T'), `TargetNode` (own `elapsed_days` / `period_days`, R-F-24), `TargetAnswer` (`cards`: the node, then each zone; a zone answering itself is one card), `TargetNodeKey` (`top` or `<level>:<id>`), `TargetStatus` (parser, `ordered`, `categoryCards` = the sums of the item rows, `items` with the filter, and the printers: `fraction`, `cardPct` / `cardPctText` (`Targets.cardPct`), `band` (`Targets.band`), `detailsBp` / `detailsText` (`Targets.detailsBp`, 2 dp), `remaining` (`Targets.remaining`), `qty` (`Formats.digits`), `bandsOf` (`cfg.kpi.bar_bands`)) | feature-tso `targets/TargetStatus.kt` (new) |
| Target screens | `TargetTabs`, `TargetStatusContent` (screenshots 39, 40: tabs, "as of", territory card tinted teal, zone cards, bars, "Details ->"), `TargetDetailsContent` (41, 42: tabs, the node card with the category bars, the item table; a bar toggles the filter, "Show all categories" while filtered), `TargetTags` | feature-tso `targets/TargetStatusScreens.kt` (new) |
| Target reads | `TsoApi.get`, `targetStatus(month)` = `GET /v1/dashboards/targets?month&product_level=category&children=zone`, `targetDetails(node, month)` = `month[&level&node_id]&product_level=variant` (the top node without level/node_id); cache keys `tgt_status_<month>`, `tgt_details_<node>_<month>` | app-tso `TsoApi.kt` |
| Target host | `TsoTargetsModel` (one per user: shared tab, Details filter, bands from the bundle, a screen shown again within 60 s keeps its read, pull always reads, a new month reads), `TsoTargetStatusHost`, `TsoTargetDetailsHost` (title = the node's name), `targetScreens(host, api)` | app-tso `TsoTargetsHost.kt` (new) |
| Band colours | `AronNightRoles.bandRed / bandAmber / bandGreen` (night: the screenshot's E53935 / FFB300 / 2E8B3A; Sunlight: deep solid B0001C / 8A5A00 / 00642F); the printed percent uses the danger / warning / success inks | core-ui `Tokens.kt` (appended members with defaults), TokenContrastTest `tsoTargetBandsReachThreeToOne` |
| Inbox model | `TsoNotification`, `TsoInboxPage`, `TsoInboxTarget`, `TsoInboxes` (parser, `readResult`, `readBody`, `badge` = unread minus pending reads, `target` of a row, `age` via `TicketMath.ageText`) | feature-tso `notifications/TsoInbox.kt` (new) |
| Inbox screen | `TsoInboxContent` (newest first, teal dot on new rows, title and body in the app's language, age, "as of" / kept copy, the pending-read line, "Show older"), `TsoInboxTags` | feature-tso `notifications/TsoInboxScreen.kt` (new) |
| Inbox reads | `TsoApi.notifications()` (`limit=50`, kept as `inbox`), `olderNotifications(beforeId)` (never kept), `markNotificationsRead(upTo)` -> `MarkRead.Done(unread)` / `Rejected` (4xx other than 401, 408, 429) / `NoAnswer` | app-tso `TsoApi.kt` |
| Inbox host | `TsoInboxModel` (`refresh`: pending read first, then the page; `open`: refresh then mark up to the newest id; the pending read in `aron_ui_<id>` key `tso_inbox_pending_read_up_to`; `unread` for the bell), `TsoInboxHost`, `inboxScreens(host, model)` | app-tso `TsoInboxHost.kt` (new) |
| Bell reads | `TsoDashboardModel(..., alsoOnLoad)`: the inbox is read with the cards on Dashboard open and pull-to-refresh (in parallel; the cards do not wait for it, a failure never stops them); `dashboardScreens(host, api, inbox)` | app-tso `TsoDashboardHost.kt` |
| Push reasons | `PushNotice.InboxUpdated / TicketUpdated / DayFinalSubmitted`; `PushMessage.REASON_NOTIFICATION / REASON_TICKET_UPDATED / REASON_DAY_FINAL_SUBMITTED`, `noticeFor(reason)`; every `sync_nudge` still pulls the bundle with the ordinary spread | core-sync `push/PushMessage.kt` |
| Push notices | channels `aron_updates` (default) and `aron_day` (low, silent); fixed bn/en texts (`strings_a1.xml`); a tap opens the inbox (`OPEN_INBOX`) or the ticket dashboard (`OPEN_TICKETS`), the day notice opens the app; `PushNotices.opens(intent)`; every notice is announced in process on `PushNotices.arrived` before it is shown | core-sync `push/PushNotices.kt`, `res/values*/strings_a1.xml` (new) |
| Shell | `TsoApp(..., openRequest, onOpenRequestHandled)`: a tapped push opens its screen on the Dashboard (back returns to it) | feature-tso `TsoApp.kt` |
| MainActivity | one `TsoApi` per user shared by the Dashboard, targets and the inbox; `screens = dashboard + targets + inboxScreen + areas + tickets`; `unread = inbox.unread`; `PushNotices.arrived` (`InboxUpdated`, `TicketUpdated`) re-reads the bell while the app is shown; `takePushIntent` in `onCreate` and `onNewIntent` | app-tso `MainActivity.kt` |
| Strings | `tso_dash_tgt_*` (22) and `tso_chrome_inbox_*` (6), en and bn | feature-tso `values*/strings_a1.xml` |
| Docs | docs/08 Target Status section rewritten, the inbox under the Drawer menu section; PARITY-SPEC line 47 (Target Status and the inbox look); delta-tso.md shell section: 8 rows (figures, bar colours, filter, table header, "as of", per-node dash, inbox, push notices) | docs |

### Tests added or changed in step 3

TargetStatusTest (feature-tso, 12: the cards of screenshots 39 and 40 in en and bn, every till-date item fixture and the card
sums, R-F-01 33114 at 21/26 and 32799 at 20/25, every Details fixture (35.00%, 33.00%, 0.20%, 1.33%, 0.60%; 43.42%, 40.94%,
0.25%, 2.48%, 0.74%, 0.62%, 1.65%; 104.94% uncapped vs 100% capped; remaining 650, 9980, 2959, 456, 8045, 0), bands from the
config, R-F-24 per-node dash, one card for a zone, the node key; screens: tabs switch the figures, "Details ->" gives `zone:9`
and `top`, offline note, the filter and the tab keeping node and filter, Bangla); TsoInboxScreenTest (feature-tso, 4);
TsoShellTest +1 (a tapped push opens the inbox on the Dashboard once, no Home button, back to the Dashboard); TsoInboxTest
(app-tso, 8: unread from the server and no read without a call, open marks up to the newest id, offline kept page + pending
read off the badge + sent first online + survives a new model, offline without a copy, a refused read is dropped, the
Dashboard reads the bell, the badge rule, parsing and row targets); TsoTargetsTest (app-tso, 5: the Status query, Details top
and zone queries, the 60 s rule and a new month, kept per month and node and a refusal drops the copy, Details opens with no
filter on the shared tab); TsoPushReasonsTest (core-sync, 3: the three reasons, the Bangla texts, channels and taps, the
in-process announcement); TokenContrastTest +1.

## Fix round (independent checker, 2026-10-29): every high and medium defect, and every low one

Base of the round: `e2e5d5f6` (end of step 3). Nine defects reported; all nine fixed.

| sev | defect | fix | where |
|---|---|---|---|
| high | logout then login: `viewModelStore.clear()` after `logout()` cleared the LoginViewModel the logged-out screen already held; the next login spun for ever | per-phase ViewModel stores (D-A1-17 amended): `SessionPhaseScope(signedInKey)` around the logged-out branch and the signed-in branch; the activity's store is never cleared; a logged-out phase on screen clears the day and the old form | core-ui `SessionScope.kt` (new); app-tso `MainActivity.kt` |
| medium | `if (xRefreshing) return` guards dropped the read a new tab, node or the bell needed (Login & Bikroy Joma tabs, inbox `open()` then `markAllRead()` on a stale page, Details nodes) | `TsoReadFlights<K>`: one read per key, awaitable (a second call joins it), another key reads in parallel, the read runs in the sign-in's scope (`rememberCoroutineScope` of the signed-in branch: a screen or tab that leaves only stops waiting; the logout cancels it). Dashboard cards, each users tab, the inbox page and "Show older", Target Status per month, Details per (node, month). The refresh indicators follow their own key (`usersRefreshing` = the selected tab, `detailsRefreshing(node)`). `open()` joins the running page read before marking. A pending read is cleared only if no higher one was set meanwhile (D-A1-29) | app-tso `TsoApi.kt`, `TsoDashboardHost.kt`, `TsoInboxHost.kt`, `TsoTargetsHost.kt`, `MainActivity.kt` |
| low | users list and inbox composed every row (up to 2000 a tab; inbox grows with "Show older") | `LazyColumn`; users keyed `<route_id>:<username or user_id or role>` (a repeated key gets its index), inbox keyed by id | feature-tso `TsoDashboardScreens.kt`, `notifications/TsoInboxScreen.kt` |
| low | goldens missing for 8 and 44 at 200 percent, drawer 10 bn, 27 en, 30 bn and en | added `tso_login_status_200_bn`, `tso_logout_dialog_200_bn`, `tso_drawer_tickets_bn`, `tso_drawer_periphery_en`, `tso_drawer_my_call_en`, `tso_drawer_my_call_bn` | ui-screenshots `TsoScreenshotTests.kt`, `TsoLargeTextScreenshotTests.kt` |
| low | Details item names broke mid-word at 360 dp ("Advance" / "d") | the Item column is as wide as the longest word of any item name (measured with the row's style), at least 30 percent (screenshot 41's share) and at most 42 percent of the table; the figure columns share the rest (weights 1, 1.3, 1.15, 1.2) and still shrink in their cells; rows and header both 8 dp sides (screenshot: about 9 dp) | feature-tso `targets/TargetStatusScreens.kt` |
| low | the Dashboard re-read 9 calls on every back navigation | `TsoDashboardModel.show()`: shown again within 60 s of its last read of the same business day keeps it; a pull always reads; a new day reads (D-A1-30) | app-tso `TsoDashboardHost.kt` |
| low | the cache's file I/O ran on the caller's (main) thread | `remember`, `cached` and the refusal's delete run in `withContext(Dispatchers.IO)` | app-tso `TsoApi.kt` |
| low | "as of" took the oldest stamp by string order; "SR Not Set" whenever `display_name` was null | `TsoDashboards.oldest` compares instants (`OffsetDateTime.parse`: "Z" or an offset, any fraction digits; unreadable stamps skipped; `asOfClock` uses the same parse); `LoginSubmitUser.userId` parsed, `vacant` = no `user_id` and no `username`; a real user without a full name shows the username (D-A1-31) | feature-tso `TsoDashboard.kt`, `TsoDashboardScreens.kt` |
| low | status said `TsoDashboardCardsTest 15` | corrected to 13 (the class had 13 @Test methods at every step-2 and step-3 commit); the fix round adds 2 (15 now) | this file |
| found by the new golden | `tso_logout_dialog_200_bn` showed the Log Out label broken inside its word ("লগ / আ / উট") beside Cancel | the two buttons sit in a `FlowRow` (end-aligned, as on screenshot 44): when both do not fit, Log Out moves to the next line whole; at the default size both stay side by side (looked at: `tso_logout_dialog_en` unchanged) | feature-tso `TsoChrome.kt` |

Docs: docs/08 Dashboard (when it reads, the row name rule, the lazy list) and the inbox (the bell joins the running read).

### Tests added or changed in the fix round

- core-ui `SessionViewModelStoresTest` (new, 4): a phase is stable while it lasts and every change is a new phase; a logged-out phase
  on screen clears the day and the old form while signing in never clears the form; another user's day is cleared at a new sign-in;
  the activity finishing clears every phase.
- feature-auth `LogoutThenLoginTest` (new, 2), built as MainActivity builds the shell: log in, sign in, log out, log in again: the
  submit reaches the login port, the day's retained state is cleared, the form is fresh (no last user's name), the same user signs in
  to a fresh day; three rounds in a row. A temporary control test (not committed) rebuilt the old shape (form in the activity's store,
  the whole store cleared after the logout) and confirmed it strands the form (see Verify).
- app-tso `TsoApiTest` +3: a tab switch during a slow read still reads the new tab (no spinner) and the first answer lands; a second
  pull waits for the running read and never doubles it; the Dashboard shown again within a minute keeps its read, a pull always
  reads, a new day reads. `TsoInboxTest` +1: the bell tapped while the Dashboard reads the inbox joins that read (one GET) and marks
  the newest id (`{"up_to_id":3}`). `TsoTargetsTest` +2: opening another node while one reads reads it and ends its spinner, the
  first node's answer still lands; a second Status pull waits for the running read. The models now take the day's scope (tests pass
  their `runBlocking` scope).
- feature-tso `TsoDashboardCardsTest` +2 (oldest stamp by time with fractions and offsets; vacancy on the user, unique list keys);
  `TsoLoginStatusScreenTest` (new, 2: username shown without a full name and "SR Not Set" only on the vacant route; a 2000-row tab
  is lazy and scrolls to its last row); `TsoInboxScreenTest` +1 (a long inbox is lazy, an older page repeating a row shows it once);
  `targets/DetailsTableLayoutTest` (new, 1, native graphics: every screenshot 41 item name breaks only after a space at 360 dp, and
  the long names still wrap).

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
  the Dashboard shows the last figures while it reads again. Amended in the fix round by D-A1-29 (a second pull waits for the
  running read) and D-A1-30 (shown again within 60 s keeps the read).
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
  dashboard copies; `aron_ui_<id>.xml` is in the wiped files. Superseded in the fix round (checker, high): the activity's
  `ViewModelStore` is never cleared. Each session phase has its own store (core-ui `SessionPhaseScope` /
  `SessionViewModelStores`, a ViewModel of the activity so a phase survives rotation and `recreate()`): the login form lives in
  the logged-out phase's store, every `rememberRetained` holder of the day in the sign-in's store. A logged-out phase on screen
  clears every other store (the day that ended and the previous login form); entering a signed-in phase clears only other
  signed-in stores (never the form whose `login` call let the user in). Every change of phase is a new store, so the same user
  signing in again starts fresh. Clearing the activity's store after `logout()` returned had cleared the login ViewModel the
  logged-out screen already held: the next login spun for ever.
- **D-A1-18 `RoleHomes.tsoTiles` deprecated, not deleted.** The TSO app no longer calls it; two shared golden files
  that are not A1's still do (ask below). `TsoTile` ids stay: A3's old frames pass them.
- **D-A1-19 Target figures as the screenshots print them.** `achieved/target`, Target, Achievement and Remaining are plain
  integers in base units (`Formats.digits`: "1030/40999", "10000"; Bengali digits in Bangla, no grouping), because screenshots
  39 to 42 print them so (rule 14a). Percents are computed once on the phone from the integers (`Targets.cardPct`,
  `Targets.detailsBp`, `Percent.text`); the parser does not read the server's `*_pct_bp` members at all.
- **D-A1-20 The Details card is the sum of its item rows.** Each category bar on Target Details sums the variant rows of
  that category (T, A, and T' per item as the server rounded it), so the card always equals its table (R-F-01: 33114 at
  21/26). The four card categories always show (0 of 0 prints "—"); T' is null when the node's `period_days` is 0 or any item
  has none. Status cards print the server's category rows as sent, ordered Cigarette, Bidi, Lighter, Match, then others.
- **D-A1-21 Node key.** Details opens with `top` (read without `level`/`node_id`: the caller's top reach node; this also
  serves a house TSO, since an explicit `level=house` is refused by the dashboards) or `zone:<id>` (an id the server
  returned; 403 outside the reach). An unreadable argument is `top`. Never a list of ids.
- **D-A1-22 When Target Status reads.** On show and on pull-to-refresh; a screen shown again within 60 s of its last read
  of the same month (back from Details) keeps it; a new month reads. No timer. The tab switch reads nothing.
- **D-A1-23 Tab and filter.** One tab for both screens (Details opens on the tab Status shows; switching on Details keeps
  the node and the filter). "Details ->" clears the filter. A bar toggles its filter; while filtered the card shows that
  bar only (screenshot 41) and "Show all categories" under it.
- **D-A1-24 Bands.** `cfg.kpi.bar_bands` from this user's bundle (`ReferenceRepository.config`), read with every Status
  read; a missing or malformed value is [40, 80] (`Bands.of`). The bar fill is the band colour with an 8 dp stub even at 0%
  (screenshot 39 shows a red stub at 0%); the percent is printed in the 4.5:1 status ink of its band.
- **D-A1-25 The inbox read.** One page of 50, kept as `inbox` (3 days, like the cards); "Show older" pages are never kept.
  Opening the inbox reads it and marks up to the newest id; offline the read is kept (`aron_ui_<id>`, wiped at logout) and
  the badge counts it off: a pending read at or above the page's newest id clears the badge, below it the page's unread rows
  up to it come off. The pending read goes first on the next read and is dropped on success or on a 4xx refusal (other than
  401, 408, 429). The dot shows the rows that were unread when the list was read (for this visit).
- **D-A1-26 Bell triggers.** Dashboard open and pull-to-refresh (`alsoOnLoad`, parallel to the cards), the inbox itself, and
  the push reasons `notification` and `ticket_updated` while the app is shown (`PushNotices.arrived`). In the background the
  push shows its notice; the next Dashboard open reads the count.
- **D-A1-27 Push notices.** `notification` and `ticket_updated` show on a new "Updates" channel (default importance) and
  open the inbox or the Ticket Dashboard; `day_final_submitted` shows on a new silent "Day status" channel (low importance:
  the midnight auto Final Submit must not ring SR phones at 00:00) and opens the app (A3's lock banner explains). All three
  keep the bundle pull with the ordinary 0 to 120 s spread (the midnight fan-out is the large one). The in-process signal is
  emitted from `PushNotices.show` because `PushShell` and `PushDispatcher` are not A1's files.
- **D-A1-28 Where an inbox row leads.** `support_ticket` -> TICKET_DETAIL with the ticket uuid as the argument,
  `leave_application` -> LEAVE, `zone_day` -> FINAL_SUBMIT; other rows are not tappable.
- **D-A1-29 One read per key (fix round).** Every read of the Dashboard, Login & Bikroy Joma Status, the inbox and Target Status runs
  through `TsoReadFlights`: a call for a key being read joins it, other keys read in parallel, and the read runs in the sign-in's
  scope, not the caller's, so leaving a screen or tab never cancels it and never strands the next read. A failed read throws to
  every caller and frees its key. Mark-read is not a flight (each open marks the newest id it read; idempotent on the server).
- **D-A1-30 Dashboard freshness (fix round).** The Dashboard shown again within 60 s of its last read of the same business day keeps
  that read (back from Login & Bikroy Joma Status, Settings or a drawer screen); a pull always reads; the bell is read with the
  cards, so it follows the same rule (pushes and the inbox still read it).
- **D-A1-31 Vacant route (fix round).** A row is vacant when it has neither `user_id` nor `username` (contract
  `LoginSubmitUserRow`); its name is "SR Not Set". Otherwise the name is the full name, else the username, else "—".

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
- Step 3, against the screenshots (all in delta-tso.md "A1 shell deltas"): "Show all categories" while a Details filter is
  on; the table header words hyphenate instead of breaking mid-word, figures shrink in their cells, and from 150 percent
  font scale each item is a block (name, then label and figure lines) because five columns do not fit 360 dp;
  "as of" and pull-to-refresh on Target Status, Details and the inbox; the per-node "—" (R-F-24); the inbox itself (no
  screenshot). The bright amber bar keeps the screenshot colour on the light track (1.4:1); its percent carries the figure.
- Step 3: `PushNotices.show` also announces the notice in process (a side effect of a function named show), see D-A1-27.

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
- **A2 (step 3):** (1) an inbox row about a ticket opens `TsoScreen.TICKET_DETAIL` with the ticket uuid (`ref_id`) as the
  `arg`; please read it there. (2) A tapped `ticket_updated` push opens `TICKET_DASHBOARD` on the Dashboard (A1 wired it
  through `TsoApp(openRequest)`); `ticketScreens` only needs the map entry. (3) If the ticket screens want to refresh on a
  ticket push while shown, collect `PushNotices.arrived` for `PushNotice.TicketUpdated` (no polling).
- **B1 (information, no change needed):** the phone accepts both `reason = notification` (what B1 sends for a desk update
  today: opens the inbox) and `reason = ticket_updated` (opens the Ticket Dashboard). Either way the push carries no
  business data and the bell re-reads `GET /v1/me/notifications`.
- **A3 (information):** `day_final_submitted` shows a silent "Day closed" notice on SR and AMO phones and still schedules
  the bundle pull; your lock refresh needs nothing else from core-sync. To react while the app is shown, collect
  `PushNotices.arrived` for `PushNotice.DayFinalSubmitted`.

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
| feature-tso | 32 classes, 165 tests, 0 failures, 0 errors (TsoShellTest 13, TsoDashboardCardsTest 13 (corrected in the fix round: this line said 15; the class had 13 @Test methods at every step-2 and step-3 commit), LogoutDialogTest 3, TsoNavigationTest 2, TsoAppTest 4, TsoChromeTest 4, TsoLoginThemeTest 4, TsoFeedbackFlowTest 2, TsoNavTest 5, the A3 classes unchanged) |
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

Step 3, 2026-10-29 (the Gradle lock was held by other lanes' backend runs for most of the step; laptop clock):

| command | result |
|---|---|
| `gl.sh … :android:core-ui:testDebugUnitTest --tests '*TokenContrastTest*' :android:core-sync:testDebugUnitTest --tests '*.push.*' :android:feature-tso:testDebugUnitTest --tests '*.targets.*' --tests '*.notifications.*' --tests '*TsoShellTest*' :android:app-tso:testDebugUnitTest --tests '*TsoInboxTest*' --tests '*TsoTargetsTest*' --tests '*TsoApiTest*' :android:ui-screenshots:compileDebugUnitTestKotlin --continue` (first compile of the step) | BUILD FAILED by 1 test: `TsoInboxScreenTest.rowsTheNewDotAndARowOpensItsTicket` (the dot sits inside the merged row; fixed with `useUnmergedTree = true`). Everything else green: TokenContrastTest 23/23, TsoPushReasonsTest 3/3 and the other push classes, TargetStatusTest 12/12, TsoShellTest 13/13, TsoInboxTest 8/8, TsoTargetsTest 5/5, TsoApiTest 6/6; ui-screenshots test sources compile |
| `gl.sh … :android:feature-tso:testDebugUnitTest --tests '*TsoInboxScreenTest*' :android:ui-screenshots:testDebugUnitTest --tests '*.TsoScreenshotTests' --tests '*.TsoLargeTextScreenshotTests' -Paron.screenshots.recordTo=<scratch>/goldens/A1 --continue` | BUILD SUCCESSFUL; TsoInboxScreenTest 4/4; TsoScreenshotTests 54/54 and TsoLargeTextScreenshotTests 10/10 written to the scratch dir only (no PNG committed). Looked at against screenshots 39 to 42: the cards, bars, colours, tabs, "Details →", the node title and the table match; fixes from the look: the table header ("Achie / ve- / ment" became "Achieve- / ment") and the 200 percent table (now one block per item), commit `bc67b047` |
| Final Verify: `gl.sh … :android:feature-tso:testDebugUnitTest :android:core-ui:testDebugUnitTest :android:feature-auth:testDebugUnitTest :android:app-tso:testDebugUnitTest :android:core-sync:testDebugUnitTest :android:app-tso:assembleDebug :android:ui-screenshots:testDebugUnitTest --tests '*.TsoScreenshotTests' --tests '*.TsoLargeTextScreenshotTests' -Paron.screenshots.recordTo=<scratch> --continue` (on `fa599028` plus the table change) | BUILD FAILED by 4 tests, see below; `:android:app-tso:assembleDebug` built `app-tso-debug.apk` (29.4 MB) |
| feature-tso | 34 classes, 182 tests, 0 failures, 0 errors |
| feature-auth | 7 classes, 47 tests, 0 failures |
| ui-screenshots (record mode, scratch dir) | 2 classes, 64 tests, 0 failures |
| core-ui | 22 classes, 152 tests, 1 failure: `ScannerSourceSetsTest.aLiteralInMainIsFlaggedButNotInAnyTestSourceSet`, the Windows path-separator failure already recorded in step 2 (not A1's: A1 never touched the scanner or that test) |
| app-tso | 7 classes, 42 tests, 1 failure: `TsoApiTest.everyCardIsANationalPostWithOnlyTheDateAndNeverTheSummary` saw 7 of the 8 card paths. Cause: the test's fake server appended to a plain `mutableListOf` from parallel OkHttp threads and lost one entry (the same test passed in the first run of this step). A1's test; fixed in `f88a46ee` (thread-safe request logs in TsoApiTest, TsoInboxTest, TsoTargetsTest) |
| core-sync | 39 classes, 292 tests, 2 failures, both `DeviceTelemetryTest` (`onlyARegainAfterALossIsKeptAndOldDaysArePrunedWithoutABatch`: "The process cannot access the file because it is being used by another process" on its `telemetry-test.json`; `aProcessStartedOfflineRecordsItsFirstConnectionAsARegain`: a 10 s timeout). Not A1's: `git log 95a35b7d..HEAD -- android/core-sync/src/main/kotlin/com/aktcl/aron/core/sync/DeviceTelemetry.kt android/core-sync/src/test/kotlin/com/aktcl/aron/core/sync/DeviceTelemetryTest.kt` is empty; the push classes (incl. TsoPushReasonsTest 3/3) pass |
| Re-run after the fixes: `gl.sh … :android:app-tso:testDebugUnitTest :android:feature-tso:testDebugUnitTest --tests '*.targets.*' :android:core-sync:testDebugUnitTest --tests '*DeviceTelemetryTest*' --continue` (on `f88a46ee`) | BUILD FAILED by 2 tests that are not A1's: app-tso 7 classes, 42 tests, 1 failure, `AppCoexistenceTest.nothingInTheManifestCollidesWithAnotherPackage` with `java.nio.file.FileSystemAlreadyExistsException` inside Robolectric's `DefaultNativeRuntimeLoader.maybeCopyFonts` (sandbox set-up, before the test body; it passed in the final verify above); TsoApiTest 6/6, TsoInboxTest 8/8, TsoTargetsTest 5/5. DeviceTelemetryTest 12 tests, 1 failure (`onlyARegainAfterALossIsKeptAndOldDaysArePrunedWithoutABatch`, its `telemetry-test.json` not found this time: the same Windows file race, a different test of the pair). TargetStatusTest 12/12 |
| `python tools/ci/money-float-scan.py` | 0 lines not allowed; exit 1 only for the 4 stale allow entries of step 2 (ask to the lead, unchanged) |
| `python tools/ci/kpi-format-corpus.py --check` | up to date |
| `python -m unittest test_gates.TsoReleaseCopyName` (tools/ci) | 5 tests OK (SH-01: `aron_tso_app_<dd_mm_yyyy>_v<code>.apk`, the release-app.yml copy step from step 2 is unchanged) |

CI not run, nothing pushed.

Fix round, 2026-10-29 (laptop clock; the Gradle lock was held by B1's backend run for the first wait):

| command | result |
|---|---|
| `gl.sh … :android:core-ui:testDebugUnitTest --tests '*SessionViewModelStoresTest*' :android:feature-auth:testDebugUnitTest --tests '*LogoutThenLoginTest*' :android:app-tso:testDebugUnitTest --tests '*TsoApiTest*' --tests '*TsoInboxTest*' --tests '*TsoTargetsTest*' :android:feature-tso:testDebugUnitTest --tests '*TsoDashboardCardsTest*' --tests '*TsoLoginStatusScreenTest*' --tests '*TsoInboxScreenTest*' --tests '*DetailsTableLayoutTest*' --tests '*TargetStatusTest*' :android:app-tso:compileDebugKotlin :android:ui-screenshots:compileDebugUnitTestKotlin --continue` (development run, uncommitted tree) | BUILD SUCCESSFUL in 3m 25s: SessionViewModelStoresTest 4/4, LogoutThenLoginTest 3/3 (the two kept tests plus a temporary control that rebuilt the old shape, the login form in the activity's store and the whole store cleared after the logout: it confirmed the old shape strands the form, submit never reaches the port and `busy` stays true; the control was deleted before the commit), TsoApiTest 9/9, TsoInboxTest 9/9, TsoTargetsTest 7/7, TsoDashboardCardsTest 15/15, TsoLoginStatusScreenTest 2/2, TsoInboxScreenTest 5/5, DetailsTableLayoutTest 1/1, TargetStatusTest 12/12 |
| s5 Verify: `gl.sh … :android:feature-tso:testDebugUnitTest :android:core-ui:testDebugUnitTest :android:feature-auth:testDebugUnitTest :android:app-tso:testDebugUnitTest :android:app-tso:assembleDebug :android:ui-screenshots:testDebugUnitTest --tests '*.TsoScreenshotTests' --tests '*.TsoLargeTextScreenshotTests' -Paron.screenshots.recordTo=<scratch>/goldens/A1fix --continue` (tree = commits `a23f39c4` to `38696637`) | BUILD FAILED in 4m 7s by 1 test that is not A1's (below); every other task passed, all executed (none from cache) |
| feature-tso | 36 classes, 188 tests, 0 failures, 0 errors |
| core-ui | 23 classes, 156 tests, 1 failure: `ScannerSourceSetsTest.aLiteralInMainIsFlaggedButNotInAnyTestSourceSet` (line 28, the Windows path-separator failure recorded in steps 2 and 3; `git log e2e5d5f6..HEAD` on the scanner and its test is empty); SessionViewModelStoresTest 4/4, TokenContrastTest and HardcodedStringScanTest pass |
| feature-auth | 8 classes, 49 tests, 0 failures (LogoutThenLoginTest 2/2) |
| app-tso | 7 classes, 48 tests, 0 failures (TsoApiTest 9, TsoInboxTest 9, TsoTargetsTest 7); `assembleDebug` built `app-tso-debug.apk` (29.4 MB) |
| ui-screenshots, record mode into the scratch dir (no PNG committed) | 2 classes, 70 tests, 0 failures. Looked at: `tso_target_details_cigarette_en` and `tso_target_details_bn` ("Black / Diamond / Advanced" by words, as screenshot 41), `tso_login_status_en` (SR Not Set on the vacant row only), `tso_login_status_200_bn`, `tso_drawer_my_call_bn`, `tso_logout_dialog_200_bn` (Log Out broken inside its word: fixed, next row) |
| after the dialog fix: `gl.sh … :android:feature-tso:testDebugUnitTest :android:ui-screenshots:testDebugUnitTest --tests '*.TsoScreenshotTests' --tests '*.TsoLargeTextScreenshotTests' -Paron.screenshots.recordTo=<scratch>/goldens/A1fix2 --continue` | BUILD SUCCESSFUL in 1m 43s: feature-tso 36 classes, 188 tests, 0 failures; ui-screenshots 70/70. `tso_logout_dialog_200_bn` now shows Cancel, then Log Out whole on the next line; `tso_logout_dialog_en` side by side as before |
| `python tools/ci/money-float-scan.py` | 0 lines not allowed; exit 1 only for the 4 stale allow entries of step 2 (ask to the lead, unchanged) |
| `python tools/ci/kpi-format-corpus.py --check` | up to date |

CI not run, nothing pushed.

Goldens needed, fix round (record on the Linux runner, `-Proborazzi.record=true`; none committed from this laptop):
- New, TsoScreenshotTests: `tso_drawer_tickets_bn`, `tso_drawer_periphery_en`, `tso_drawer_my_call_en`, `tso_drawer_my_call_bn`.
- New, TsoLargeTextScreenshotTests: `tso_login_status_200_bn`, `tso_logout_dialog_200_bn`.
- Pixels changed by the fix round (record these after it, not before): `tso_target_details_cigarette_en`,
  `tso_target_details_lighter_till_en`, `tso_target_details_till_en`, `tso_target_details_bn` (Item column width, 8 dp row sides),
  `tso_login_status_en`, `tso_login_status_bn`, `tso_login_status_offline_en` (lazy list: same look), `tso_inbox_*` (lazy list: same
  look). The step 2 and step 3 lists below still stand otherwise.

Goldens needed, step 3 (record on the Linux runner, `-Proborazzi.record=true`; none committed from this laptop):
- New, TsoScreenshotTests: `tso_target_monthly_en`, `tso_target_till_date_en`, `tso_target_monthly_bn`, `tso_target_sunlight_en`,
  `tso_target_kept_en`, `tso_target_offline_empty_en`, `tso_target_loading_en`, `tso_target_details_cigarette_en`,
  `tso_target_details_lighter_till_en`, `tso_target_details_till_en`, `tso_target_details_bn`, `tso_inbox_en`, `tso_inbox_bn`,
  `tso_inbox_offline_pending_en`, `tso_inbox_empty_en`, `tso_inbox_sunlight_bn`.
- New, TsoLargeTextScreenshotTests: `tso_target_status_200_bn`, `tso_target_details_200_en`, `tso_inbox_200_bn`.
- The step 2 list above still stands (no step 3 change moves those pixels).

## Not done

- Goldens are not committed (Windows laptop; the lead records them on the Linux runner). Device checks (s8 rows 2, 3, 5 to 10,
  39 to 42 and the inbox on a pilot phone against dev) not done: they need B1, B2 and B3 on dev.
- `RoleHomes.tsoTiles` is still `@Deprecated`, not deleted: it waits for the lead to retire the shared role-home goldens (ask).
- The money-float allow list still carries the 4 stale step-2 entries (lead's file; ask).
- Not checked against a live server: the exact `children` answer of B2 for a house TSO and the B1 inbox page were built from
  the contract and the B1/B2 status files, tested with fakes.
- Fix round: logout then login is proven in Robolectric with the shell's shape (`LogoutThenLoginTest`), not on a phone; the
  single-flight reads are proven with fake slow servers, not on 2G. Both belong in the next device check (Next step).

## Next step

A1 has no build item left in s5; the fix round's nine defects are fixed (section "Fix round"). For the next agent on this lane:
(1) when the lead retires the shared role-home goldens, delete `RoleHomes.tsoTiles` (feature-home `RoleHome.kt`) and its
`@Deprecated` callers; (2) when B1, B2 and B3 are on dev, run the s8 device checks for rows 2 to 10, 39 to 42, 44 and the inbox on
a pilot phone (Target Status against real `children=zone` answers, a ticket update reaching the bell and the inbox, a
`day_final_submitted` notice on an SR phone), and add a device check of logout then login on the same phone (the fix round's high
defect: the second login must not spin); (3) answer A2's and A3's questions on the inbox `TICKET_DETAIL` argument and
`PushNotices.arrived` if they ask. The merge order of the wave (s6) puts A1 after W1 and before A2 and A3; `TsoScreenshotTests.kt`
has A3's move pending on A3's branch, so expect a conflict there: keep A3's removal of the area cases and A1's appended step 2, step
3 and fix-round cases. A2 and A3 (information): `MainActivity` now wraps the signed-in branch in `SessionPhaseScope`, so every
`rememberRetained` holder of their screens lives in the sign-in's store and is cleared by the next logged-out phase; nothing to
change on their side.
