# 08 — TSO App

The Territory Sales Officer works one territory. Darker themed app (as today): the TSO look `AronLook.TsoNight` (navy page, purple glow cards, cyan figures, green-to-yellow titles; D-TSO-MAN-13) on the login and every signed-in screen; the one-tap Sunlight theme overrides it. Login = username + password, no OTP (D-AUTH-TSO-NOBIND, D-TSO-MAN-16). **Logout wipes all local app data**, but only on a fully reconciled phone: it is refused while anything is unsent (G-man-tso-01, D-TSO-MAN-37; see Log Out below). Same offline/sync rules; every read screen shows "as of hh:mm" and reads only when opened and on pull-to-refresh (no timer).

## Dashboard (territory, today; screenshots 6 to 8)

The Dashboard is the root after login (D-TSO-MAN-14). Every card reads its own `POST /v1/dashboards/national/<card>` with the
business date only (scope = the token's reach; no zone filter), and the phone formats the server's raw integers through the shared
rules (`Percent`, `Qty`, `Targets`; build contract s4.2, D-TSO-MAN-10). Cards, top to bottom:

- **Sales** (one purple card, D-TSO-MAN-09): Cigarette, Bidi, Lighter in Pieces, Match in Dozen. Total Sales = sold quantity of sale
  lines only, base units (`Qty.text(A)`: "40.0", "3.0 K"); Total Target = today's target `Qty.text(T, W)` (T = the month's target in
  scope, W = working days at the scope's node, D-TSO-MAN-08); the ring = `Targets.dailyRingTenths(A, T, W)`, uncapped ("287.6%").
  No target or W = 0 prints "—" (R-F-13).
- **Geo Fencing**: geo-valid of completed calls, tenths ("100.0%").
- **By Channel STD ▸**: cigarette sticks by outlet channel as a pie, label `<share 2 dp>% <Channel> Channel`; the ▸ switches to
  By Channel Successful Call and back, and the choice is kept (D-TSO-MAN-41).
- **CPR**: Target Outlet, Successful Calls and the ring (tenths; 29 of 1,186 is 2.4%, never 2.5%).
- **Brand CPR**: one point per brand, outlets bought over successful calls by default (`cfg.kpi.brand_cpr_denominator.tso`,
  D-TSO-MAN-06), y axis in whole percent.
- **Login & Bikroy Joma Status**: Login Status = successful logins / target routes; **Bikroy Joma Status = submitted / target routes**
  (D-TSO-MAN-07: the manual text and screenshot 7 agree, 1 of 5 = 20%; this corrects the earlier "÷ login count" line). Captions
  Target Route, Total Login, Login Count, Total Bikroy Joma. Tapping opens **Login & Bikroy Joma Status** (screenshot 8): tabs Not
  Logged In / Not Uploaded, one row per (route, user): display name ("SR Not Set" on a vacant route), User Name, Route, Dep Name
  (the zone), AMO rows following the route state (D-TSO-MAN-12).
- **By Segment Value Contribution**: value share and volume share (within the unit family) as two lines (D-TSO-MAN-11).

The Home button (bottom centre) is on the Dashboard and on Login & Bikroy Joma Status (build contract s8).

## Drawer menu (screenshots 9, 10, 27, 30, 44; D-TSO-MAN-14)

Top bar of the Dashboard: ☰, "Good day, <full_name>", the bell with the unread badge (opens the inbox). Drawer header "TSO" over the
bold name, with a gear that opens Settings. Rows, exactly, each with its subtitle: Dashboard (View Dashboard); Tickets ˅ (Raise and
manage tickets: Ticket Dashboard, All Tickets, Quick Ticket, Create Ticket); Leave (Manage your leaves); Final Submit (Submit Sales
Data); My Periphery ˅ (Check your team and outlet: My Team, Retailer); My Call ˅ (Create and manage plans: My Visit Plan, Set Plan);
Target Status (Check targets); My Feedback ˅ (Manage your feedbacks: Add new feedback, My feedback); Log Out (Logout from the app).
A drawer screen opens on the Dashboard, so back from it returns to the Dashboard. The tile Home is retired for TSO.

## Leave

- Leave applications list (date range, days, reason, status e.g. "DMO approval pending"). TSO leave is approved by the DMO.
- Apply: Leave Type (Casual, Sick, Earn), date(s), number of days, reason.

## Final Submit (per zone per day — a core control)

Select Wing → Division → Territory → House → Zone → "Get Sales Data" → sales date + routes with FF (SR) name or "Not Set" → Submit → success. A second attempt the same day is refused ("already given FINAL SUBMIT for today"). → `final_submit`.

## My Periphery

- **My Team:** zone → live SR locations on a map (last synced fixes).
- **Retailer:** zone + radius (50 / 100 / 300 m) → outlets around the TSO's current location on a map.

## My Call (TSO market visit plan)

- **Set Plan:** date, zone, route → Show Outlet → multi-select outlets (name, code, cluster, owner, phone) → Set Plan.
- **My Visit Plan:** date, zone, route → Visit Plan Outlets tabs Pending / Completed (outlet card: code e.g. DHK-344-011, owner, contact, channel, cluster).
- **Visit:** "Visit Query" questionnaire to the retailer (Does the SR visit regularly? Does the SR print memos regularly?) + "Delegate task?" Yes/No → Submit; Assign Task (type e.g. Irregular Visit, date, comment) → Assign. → `call_assessment(kind=retailer_questionnaire)`, `task`.

## Target Status

- Monthly Target | Till Date Target; Territory card + Zone cards with Cigarette/Bidi/Lighter/Match bars (achieved/target, %). Details → item table (Item at SKU/variant level e.g. "Ananda Bidi 25", "Special Abul Bidi 25", "Maxim Double Burst"; Target, Achievement, Remaining, %).

## Settings (behind the drawer gear)

Language (Bangla / English), the Sunlight theme, version, app update, PDA to Support, Change Password (small improvement: the
screenshots show no settings page; the sun switch and the version line moved here from the login, D-TSO-MAN-15).

## Log Out (screenshot 44, D-TSO-MAN-37)

The last drawer row. Dialog "Log Out!", "All app data on this phone will be removed." (grammar fix of the screenshot's "Your all app
data will removed."), green Cancel, orange Log Out. Refused while items are unsent ("N items not yet sent", Sync now or Cancel); on a
reconciled phone it wipes the user's database, photos, per-user preference files including `aron_ui_<id>.xml`, the kept dashboard
copies and the retained screen state (SH-17).

## My Feedback

Category (e.g. Suggestion), Title, Description, image from gallery → Save.

## Notes for the rebuild

- The TSO app is mostly **read + a few control actions** (final submit, visit plans, leave, feedback, task assignment). It reads the same aggregates as the web dashboard, scoped to the territory — build those reads once and feed both (`docs/10`).
- "Digonto" (paan-masala) SKU assets appear in the current TSO build — the territory view may span product lines beyond tobacco. Confirm product scope for the TSO role in `docs/13`.
