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
  Logged In / Not Uploaded, one row per (route, user): display name (the username when the user has no full name; "SR Not Set" only
  on a vacant route, one with no user at all), User Name, Route, Dep Name (the zone), AMO rows following the route state
  (D-TSO-MAN-12). The list is lazy (a tab may carry up to 2,000 rows).
- **When it reads**: on open and on pull-to-refresh, never on a timer. The Dashboard shown again within a minute of its last read of
  the same day (back from another screen) keeps that read; a pull always reads. Each card or tab is read once at a time: a second
  pull waits for the read running, and a tab switch during a slow read reads the new tab at once (the first answer still lands).
- **By Segment Value Contribution**: value share and volume share (within the unit family) as two lines (D-TSO-MAN-11).

The Home button (bottom centre) is on the Dashboard and on Login & Bikroy Joma Status (build contract s8).

## Drawer menu (screenshots 9, 10, 27, 30, 44; D-TSO-MAN-14)

Top bar of the Dashboard: ☰, "Good day, <full_name>", the bell with the unread badge (opens the inbox, below). Drawer header "TSO" over the
bold name, with a gear that opens Settings. Rows, exactly, each with its subtitle: Dashboard (View Dashboard); Tickets ˅ (Raise and
manage tickets: Ticket Dashboard, All Tickets, Quick Ticket, Create Ticket); Leave (Manage your leaves); Final Submit (Submit Sales
Data); My Periphery ˅ (Check your team and outlet: My Team, Retailer); My Call ˅ (Create and manage plans: My Visit Plan, Set Plan);
Target Status (Check targets); My Feedback ˅ (Manage your feedbacks: Add new feedback, My feedback); Log Out (Logout from the app).
A drawer screen opens on the Dashboard, so back from it returns to the Dashboard. The tile Home is retired for TSO.

**Notifications inbox (the bell; D-TSO-MAN-26, no screenshot: an improvement).** The badge is the server's unread count
(`GET /v1/me/notifications`) minus the reads this phone has not sent yet; "99+" above 99. It is read when the Dashboard opens, on its
pull-to-refresh and when a push with the reason `notification` (or `ticket_updated`) arrives; never on a timer. The bell opens a plain
list, newest first (50 rows, "Show older" for more): a dot on the rows that were new, title and body in the app's language (English
when the producer gave no Bangla), the age ("2h ago"). Opening it marks everything up to the newest id read (`POST
/v1/me/notifications/read {up_to_id}`, idempotent); offline the read is kept on the phone, already counted off the badge, and sent
first on the next read. The bell tapped while the Dashboard is still reading the inbox waits for that read and then marks its newest
id. A ticket row opens that ticket, a leave decision opens Leave, a midnight auto Final Submit opens Final Submit.
The newest page is kept for offline with its "as of". Kinds: ticket_updated, leave_decided, final_submit_auto; announcements stay
push-only. A tapped push notice opens the inbox (`notification`) or the Ticket Dashboard (`ticket_updated`); the push itself carries
the reason only (fixed Bangla and English text, no business data). `day_final_submitted` is the SR and AMO day-lock push (silent
"Day closed" notice).

## Leave

- Leave applications list (screenshot 19): oldest first (the screenshot wins over the manual's "newest first", D-TSO-MAN-33).
  Each card: calendar icon with "from to to", "Reason: ...", the day count in a circle ("Day"/"Days"), and the orange status
  pill ("DMO approval pending"); no leave type. TSO leave is approved by the DMO. An application still on the phone says
  "Not sent yet"; one the server refused on sync says "Not accepted: <reason>"; one it holds for an admin's review
  (quarantined) says "Held for review: <reason>". Apply For Leave at the bottom.
- Apply (screenshots 20 to 22): Leave Type dropdown with nothing chosen (types from the bundle code list `leave_type`, else
  Casual, Sick, Earn), Date empty until picked from the calendar, typed Number of days (starts at 0; Bengali digits from a
  Bangla keyboard count), Reason, and the lime Apply
  under the form. to_date = from + days - 1 in calendar days. Apply saves on the phone (offline) and returns to the list with
  a toast. Window `cfg.leave.allow_past_days` (31) and `allow_ahead_days` (365).

## Final Submit (per zone per day — a core control)

Select Wing → Division → Territory → House → Zone (dropdowns from `GET /v1/dashboards/national/filter-options`, the reach
from the token; a level with one option is preselected; D-TSO-MAN-32) → "Get Sales Data" (enabled once the zone is chosen)
→ the Sales Date box and the routes: "<route name> <kind>" (Daily, 3F, 2F, AMO), "FF: <full name>" or "FF: SR Not Set", an
amber "Not uploaded" chip on a due route without its upload (a Web Entry counts as uploaded; dummy BR and Institutional
routes without data are not due) → Submit. Due routes not uploaded give a warning that names them ("Submit anyway" /
"Cancel"); it never blocks; with nothing due there is no confirm. Success: "Success" / "Final Submit Done Successfully..." /
OK, which returns to the selectors with the zone cleared for the next zone (CR-5). A second attempt the same day is refused
("already given FINAL SUBMIT for today"). The client uuid carries the preview's `submit_seq`, so a submit after a reopen is
new and a replay of a reopened one (409 `ERR_DAY_FINAL_SUBMIT_REOPENED`) reads the preview again (D-TSO-MAN-28). →
`final_submit`. The system back and the on-screen back do the same on every step (routes → pickers, pickers → out, and
leaving starts a fresh screen next time); Get Sales Data asks for today's date when it is tapped. After it, the SR and AMO
phones of the zone lock capture for the route-day and show a banner (D-TSO-MAN-31): no new call starts on a closed
route-day, a call in progress when the lock arrives ends on the phone (the server refuses its close) and is not restored
after a relaunch; a late Sales Submit, attendance and printing stay allowed (the server accepts them as state). Nothing
of this throws in a screen (D-A3-22).

## My Periphery

Screenshots 28 and 29, D-TSO-MAN-34. Both are drawer rows of their own (My Team, Retailer); each has the Home button.

- **My Team ("My Teams"):** zone dropdown on top, then an interactive map (zoom, pan, tap a pin; tilt and rotation off,
  no my-location layer) of the SRs' **last synced non-mock fixes**, never live tracking and no locate ping (constraint 3,
  D-170). Pins in route order with a legend "route: count"; each bubble says "Last seen HH:mm (n min ago) · source"
  (check-in, visit, check-out, on the move); a fix older than `cfg.tso.team_location_max_age_min` (120) is greyed; an SR
  with mock fixes today carries the chip "Mock GPS today: n" (`TeamLocation.mock_fixes_today`). Read on open and on
  Refresh only; offline the last answer and the list stay with "as of".
- **Retailer ("Outlets"):** Zone and Radius dropdowns side by side (radii from `cfg.tso.periphery_radius_options_m`,
  50 / 100 / 300 by default); the map shows the outlets inside the radius circle around the TSO's own fix (green pin).
  One balanced-power fix is taken when the screen opens, when the zone or radius is picked and on the refresh icon,
  never a stream; a pick while a fix is still running takes one more fix for the new choice once that one ends. Online the server answers (`GET /v1/outlets/nearby`, reach from the token); offline the phone works
  the answer out from the bundle with the server's rule (active, coordinates confirmed, route set, zone, haversine ≤ r,
  distance rounded to 0.1 m, nearest first, ties by outlet id) and says so. Pins show the distance in whole metres
  (rounded). A mock fix is flagged and a fix outside Bangladesh is never sent.
- The maps load only while their screen is open (D-TSO-MAN-34 amends D-08 for the TSO; the AMO stays on the lite map).

## My Call (TSO market visit plan)

Screenshots 31 to 38, D-TSO-MAN-35. My Visit Plan and Set Plan are drawer rows of their own; every screen has the Home
button. The system back and the on-screen back always go to the same place (CR-4).

- **Set Plan:** Select Date (today by default, up to `cfg.tso.plan_max_days_ahead` days ahead, 60), Select Zone (chosen
  when there is one), Select Route (dropdowns) → Show Outlet ("Choose a route" without one) → **Select Outlets**: cards
  "name / code | cluster / owner | phone" with a tick, the outlets already planned for that date ticked (a second save is
  a union), Select all (kept as an improvement) → Set Plan: queued on the phone (`visit_plan` with `route_id`,
  `visit_plan_outlet`; deterministic uuids), a toast, back to the form. Back: Select Outlets → form → out.
- **My Visit Plan:** Select Date (today), Select Zone, Select Route → Show Outlet → **Visit Plan Outlets**: Pending and
  Completed tabs without counts; "No Outlets Found" for an empty tab or no plan; the 5-line card "Code, Owner, Contact,
  Channel (`GT Channel`), Cluster". A pending card opens the **Visit Query**: the questions of the seeded
  `retailer_questionnaire` (two free-text questions, Bangla from screenshot 37, English from the manual; answers optional,
  at most 1000 characters), "Do you want to delegate the task?" Yes / No (No by default), Submit → `call_assessment` +
  answers, the outlet moves to Completed at once. Yes goes on to **Assign Task**: the outlet card (Outlet Name, Cluster,
  Route), the task type dropdown (active `task_type` items, labels not codes), the due date (today), the comment → Assign
  Task → `task` for the route's SR (never a free pick; "SR Not Set" blocks it), a toast, back to the outlets. Back: Visit
  Query and Assign Task → outlets → form → out.

## Target Status (screenshots 39 to 42; D-TSO-MAN-05, 39, 40)

Drawer > Target Status. No Home button (build contract s8). Reads `GET /v1/dashboards/targets?month=<YYYY-MM>&product_level=category&children=zone`
when the screen opens and on pull-to-refresh (never on a timer; a screen shown again within a minute of its last read, e.g. back
from Details, keeps that read). The nodes come from the token's reach on the server; the phone names none. Every answer is kept per
month (and per node for Details) so the screens open offline with "Offline: showing the copy of hh:mm"; a refusal never serves a copy.

- **Tabs** Monthly Target | Till Date Target (one answer carries both; switching reads nothing). Details opens on the tab Status shows.
- **Cards**: the TSO's territory (or house) card, then one card per zone in reach (a zone TSO sees one card). Title "Territory: <name>",
  "Zone: <name>". One bar per category, Cigarette, Bidi, Lighter, Match (base units: sticks, pieces, dozens): `achieved/target` as
  plain integers ("1030/40999", as the screenshots), the card percent `min(100, whole(A, T'))` (`Targets.cardPct`; 85 of 81 is
  100%), the bar coloured by `cfg.kpi.bar_bands` (default [40, 80]: red below 40, amber below 80, green from 80) on the printed
  percent (`Targets.band`). Till Date uses T' = the sum of the item rows, each `round_half_up(T x e / n)` (calendar basis by default,
  `cfg.kpi.tilldate_basis.tso`; R-F-01). A node with no working day in a working-days month (its own `period_days` 0, R-F-24) prints
  "—" for its till-date figures while its parent does not. "Details →" opens Target Details for that node.
- **Target Details**: title = the node's name ("Apsis"); the same tabs; the node's card with one bar per category, each the sum of its
  item rows; the item table (variant rows in catalogue order): Item, Target, Achievement, Remaining = `max(T - A, 0)`, % =
  `Percent.bp(A, T)` with 2 decimals, uncapped ("104.94%"). Tapping a category bar shows only that bar and that category's items
  (screenshot 41); tapping it again or "Show all categories" clears the filter; switching the tab keeps the node and the filter.
- Every percent is computed once on the phone from the integers (`Targets`, `Percent`); the server's `*_pct_bp` members are not
  printed. A month with no target prints "—".

## Settings (behind the drawer gear)

Language (Bangla / English), the Sunlight theme, version, app update, PDA to Support, Change Password (small improvement: the
screenshots show no settings page; the sun switch and the version line moved here from the login, D-TSO-MAN-15).

## Log Out (screenshot 44, D-TSO-MAN-37)

The last drawer row. Dialog "Log Out!", "All app data on this phone will be removed." (grammar fix of the screenshot's "Your all app
data will removed."), green Cancel, orange Log Out. Refused while items are unsent ("N items not yet sent", Sync now or Cancel); on a
reconciled phone it wipes the user's database, photos, per-user preference files including `aron_ui_<id>.xml`, the kept dashboard
copies and the retained screen state (SH-17).

## My Feedback

Screenshot 43, D-TSO-MAN-36. Two drawer rows: Add new feedback and My feedback (the own list, an improvement).

- **Create New Feedback:** "Feedback Category" as a vertical list from the code list (suggestion and feedback; D-197
  superseded), "Enter title", "Descriptions" / "Enter description", **Browse Gallery** (one picture from the phone's
  gallery through the system photo picker, no storage permission, compressed and EXIF-free like a camera photo; at most
  `cfg.feedback.max_images`, 0..1; no camera on this form), Save. Save queues the feedback on the phone at once (offline
  included), shows a toast and goes back; the picture is claimed for the feedback by Save, so a removed picture or an
  abandoned form never uploads.
- **My feedback:** title, status pill, category and the business date; "Not sent yet" for a row still in the outbox;
  "Add new feedback" at the bottom.

## Notes for the rebuild

- The TSO app is mostly **read + a few control actions** (final submit, visit plans, leave, feedback, task assignment). It reads the same aggregates as the web dashboard, scoped to the territory — build those reads once and feed both (`docs/10`).
- "Digonto" (paan-masala) SKU assets appear in the current TSO build — the territory view may span product lines beyond tobacco. Confirm product scope for the TSO role in `docs/13`.

## Tickets

<!-- tso-manual A2 (build contract docs/requests/tso-manual-build-2026-10.md s5 A2; D-TSO-MAN-17..25; screenshots 10 to 18) -->

New in the October 2026 manual (section 6): the TSO reports app, data, target, outlet and device problems to the support
desk and follows them until they are solved. The screens live in the role-agnostic module `android/feature-tickets`
(TSO wires it now, SR and AMO later); the TSO host is `app-tso` `TicketsHost.kt` and `TicketApi.kt`.

- **Menu (screenshot 10, A1's drawer):** Tickets opens Ticket Dashboard, All Tickets, Quick Ticket and Create Ticket. No
  Home button on any ticket screen (s8).
- **Ticket Dashboard (11):** Total, New, In Progress, Resolved cards; Ticket Resolution Status with Resolved % (resolved /
  total) and Open % ((new + in progress) / total), whole percents from the counts (`TicketMath`, R-F-14; total 0 is "0%");
  "Need help?" opens Quick Ticket; the 10 most recent tickets, "See all" opens All Tickets.
- **All Tickets (12, 13):** search by number, title or issue type (on the phone, Bengali digits match); tabs All, New, In
  Progress, Resolved, Closed (All also shows Cancelled); card "INC… · issue type", title, priority, status, age ("4d ago").
- **Ticket Details (14):** the thread (the reporter's description first; every support person is shown as "Support"),
  times in Asia/Dhaka on the 12-hour clock. Comments only while New or In Progress; a Resolved ticket shows "This ticket is
  resolved. No further comments can be added." and **Submit Review & Close** (one tap, no rating; record
  `support_ticket_event` kind `close_review`). No reopen: if not solved, raise a new ticket quoting the old number.
- **Quick Ticket (15, 16):** the 31 issue types (Annex A, bundle code list `ticket_issue_type`) as tiles; a tile opens the
  sheet: description optional, up to 3 photos (`cfg.support.ticket_max_photos`, gallery or camera), Submit Ticket. The
  title is the issue type's English label.
- **Create Ticket (17, 18):** issue type (the full list), title (1..120) and description (1..2000) required, photos as
  above; an optional affected username for the account issue types (a login problem raised from another user's phone).
- **Offline-first:** a ticket is saved to the outbox at once (record `support_ticket`, client UUID, no online create,
  D-TSO-MAN-18) and shows "Not sent yet" in place of its INC number until the server lists it (mirror row
  `tso.unsent.ticket.<uuid>`); a refused one shows "Not accepted: <reason>". One UUID per form survives rotation and a
  kill; a double tap or a retry never makes a second ticket. Lists and threads are read only when a screen opens or is
  pulled down (no timer) and kept on the phone for offline viewing with their "as of" time. To save data, the list is not
  read again when a screen opens within 3 minutes of the last read (moving between the Dashboard, All Tickets and
  Details); pulling down always reads.
- **Photos:** a photo is only staged until its ticket or comment is saved. Closing the Quick sheet (X, tapping outside,
  back) or leaving a form or an unsent comment drops its photos, so they are never sent; a rotation or a kill keeps them
  with the form.
- **Push:** `ticket_updated` (support changed, replied to or resolved a ticket) opens the Ticket Dashboard, which then
  reads online even within the 3 minutes.
