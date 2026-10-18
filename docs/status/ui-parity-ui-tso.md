# UI parity, lane ui-tso (TSO app: android/feature-tso, android/app-tso)

Binding: CLAUDE.md rule 14a, `docs/ui-reference/PARITY-SPEC.md`. The TSO app has **no screenshots in git**, so every row is "layout from manual, needs owner screenshot". Style bible: `docs/ui-reference/sr/` (home-1-tiles, stock, summary, outlet-menu, sale-select). Presentation only; behaviour, domain logic, tags and tests stay.

## Handover block
(empty: lane started 2026-10-18; no recycle yet)

## Gap seen at start (before any change)
Current TSO output (`android/ui-screenshots/.../tso_*.png`) is Calm Glass: pastel gradient page, chips, hamburger + "Good day" bar, navy pills, letter-circle tiles, a drawer. Target look: red-to-purple gradient top bar with centered gradient icon and white bold title, `#E8E8E8` page, white 12 dp cards, 4-column tile Home with gradient line-art icons, dark-grey table headers, red minus / purple plus, gradient action buttons.

## Kit pieces used (lane ui-kit owns core-ui; none edited here)
AronTopBar, AronScaffold, AronHomeHeader, AronTile/AronTileGrid, SummaryTable, TotalsPanel, AronPrimaryButton/AronSecondaryButton, RetailerPicker, PackCard/PackshotImage (TSO stock views, if any), icon set. Status: waiting for `kit skeleton <sha>`.

## Screen inventory and planned composition

| # | Screen (TSO-S in manual-tso.md) | Source | Planned composition (SR visual language) | Status | Differs / notes | sha |
|---|---|---|---|---|---|---|
| 1 | Home shell: header + tile grid (S-04 landing, S-06 drawer) | `TsoApp.kt` HOME, `TsoChrome.kt` (TsoChromeBar, TsoDrawer), shared `RoleHomeContent` in feature-home | `AronHomeHeader` (avatar, `TSO - Name (login)`, date line, gear) + `AronTileGrid` 4 cols white page: Dashboard, Leave (badge), Final Submit, My Periphery, My Call, My Feedback; sync/offline line and device health as slim rows below the grid; drawer and Home FAB removed in favour of the back chevron (manual drawer entries become tiles; same destinations) | NOT STARTED | Target Status tile hidden (docs/27). Device OTP tile stays filtered (D-AUTH-TSO-NOBIND). | |
| 2 | Shell for every sub-screen (Home button strip under content) | `TsoApp.kt` | `AronScaffold` + `AronTopBar` (back chevron, gradient icon, title) per screen; the blue Home strip goes (back chevron returns) | NOT STARTED | system back behaviour unchanged | |
| 3 | Dashboard: sales tiles, channel, CPR, segment, brand, login status (S-04) | `TsoDashboardScreens.kt`, `TsoDashboardHost.kt` | white cards on grey page; purple `TotalsPanel`-style category rows for the four sales categories (units: sticks, boxes, dozens); channel/segment/brand as white cards with bars; login/Bikroy Joma status card with progress track `#EFEFEF`/fill red; no target % (deferred) | NOT STARTED | achievement rings show no live target data (docs/27) | |
| 4 | Login and Bikroy Joma status detail, not-logged-in / not-uploaded route lists (S-05) | `TsoRouteListContent`, `TsoZoneFilterContent` | `SummaryTable` (dark header) for routes; zone filter as `RetailerPicker`-style pill + dark letter/zone panel | NOT STARTED | | |
| 5 | Leave list (S-07) | `leave/LeaveScreen.kt` | white `LeaveCard` rows on grey, status chip as small coloured text, gradient "Apply" button | NOT STARTED | | |
| 6 | Leave apply form with date pick (S-08) | `leave/LeaveApplyScreen.kt` | white form card, `TsoDateField` restyled, gradient primary button | NOT STARTED | | |
| 7 | Final Submit: pick, review, success/already-submitted (S-09..S-12) | `finalsubmit/FinalSubmitScreen.kt` | selector on `RetailerPicker` style, route list as `SummaryTable`, gradient Submit; success dialog stays | NOT STARTED | online only (unchanged) | |
| 8 | My Periphery hub (S-06 group) | `periphery/PeripheryScreens.kt` PeripheryHub | 2 tiles (My Team, Retailer) in an `AronTileGrid` on grey (Outlet-menu look: grey tile, red label) | NOT STARTED | | |
| 9 | My Team map (S-13) | `TeamScreen` | top bar + zone pill picker; map card white rounded; SR list below as white rows | NOT STARTED | map content unchanged | |
| 10 | Retailer map by zone and radius (S-14) | `RetailerScreen` | top bar + picker pill + radius control in white card; map card | NOT STARTED | | |
| 11 | My Call hub (S-15/S-19 group) | `mycall/MyCallScreens.kt` MyCallHub | 2 tiles (Set Plan, My Visit Plan), Outlet-menu look | NOT STARTED | | |
| 12 | Set Plan: selector, outlet multi-select (S-19, S-20) | `SetPlanScreen` | `RetailerPicker`-style pills for zone/route/date, white outlet rows with check, gradient Set Plan button | NOT STARTED | | |
| 13 | My Visit Plan: selector, Pending / Completed outlets (S-15, S-16) | `VisitPlanScreen` | pills + two-tab strip (gradient selected), `OutletCard` as white rows | NOT STARTED | | |
| 14 | Visit Query (S-17) | `VisitQueryScreen` | white question cards, Yes/No as purple/grey pills, gradient Save | NOT STARTED | | |
| 15 | Assign Task (S-18) | `AssignTaskScreen` | white form card, gradient Assign button | NOT STARTED | | |
| 16 | My Call unavailable state | `MyCallUnavailable` | top bar + centered white card | NOT STARTED | | |
| 17 | My Feedback list and Create New Feedback (S-23) | `feedback/FeedbackScreens.kt` | white list rows with status text; form card; gradient Submit | NOT STARTED | | |
| 18 | Settings (language, version, update, support, change password, logout) | `RoleSettingsContent` (feature-home), `TsoApp.kt` SETTINGS | TSO-owned settings page: top bar, white rows on grey, red Logout button | NOT STARTED | shared feature-home composable not edited; TSO composes its own from the kit | |
| 19 | Change Password | `password/ChangePasswordScreen.kt` | white form card, gradient primary | NOT STARTED | | |
| 20 | Logout confirm (S-24), app update, support host | `AronConfirmDialog`, `UpdateHost.kt`, `SystemScreens.kt` | kit dialog colours; update/support pages get top bar + grey page | NOT STARTED | | |
| 21 | Login (S-03) | `TsoLoginTheme.kt`, feature-auth | out of this lane's modules; reported to the lead | NOT STARTED | | |

Target Status (S-21, S-22) stays hidden: deferred (docs/27).

## Evidence
`docs/evidence/ui-parity/tso-<group>-noref.png` (left: closest SR reference, right: ours). None yet.
