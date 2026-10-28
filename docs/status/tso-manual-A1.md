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

## Deviations

- The DEVICE_OTP branch of TsoApp is gone with the old enum. It was unreachable (the tile was filtered out,
  D-AUTH-TSO-NOBIND). `webOrigin` stays in the signature (callers compile) and is unused.
- Settings > Change Password and Settings > PDA to Support are now pushed screens (CHANGE_PASSWORD, SUPPORT_UPLOAD)
  instead of steps inside Settings. Same back paths (back returns to Settings, then Home); the switch is animated like
  any other screen change.

## Asks

- **A2:** build your ports from `TicketsHostDeps.host` (`host.components.apiClient`, `host.outbox`, `host.mediaFor`,
  `host.media()`, `host.nowMs`, `host.online`, `host.userId`, `host.userKey`) inside TicketsHost.kt; MainActivity
  already merges `ticketScreens(...)` into `screens`. Use the `nav` overload of `TsoFrame`.
- **A3:** the flows and `AreasHost.kt` are yours from this commit. `PeripheryDeps` now lives in
  `com.aktcl.aron.feature.tso.periphery`. When you split the hubs, keep MY_TEAM and MY_VISIT_PLAN and FEEDBACK_LIST
  working from the legacy tiles until A1's shell step lands (or coordinate the order through the lead).

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

## Next step

A1 step 2: the shell (TsoChrome drawer, top bar, bell, logout dialog; Dashboard as root; tile Home and `TsoTiles`
retired; TsoShellTest), then TsoNight tokens and the kit bodies, the dashboard cards on `/v1/dashboards/national/*`,
Target Status, the inbox, the login variant, the release name. Answer the F1 asks 15 and 18 (decode
`NationalSalesCard.achievement_pct_bp` and every widened `*_bp` as Long) and the F3 ask (format only through
`Percent`/`Qty`/`Targets`; assert 33114 for a card built from the eight Cigarette items, R-F-01).
