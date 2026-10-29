# 08 — TSO App

The Territory Sales Officer works one territory. Darker themed app (as today). Login = username + password. **Logout wipes all local app data** ("your all app data will be removed") — keep that behaviour. Same offline/sync rules.

## Dashboard (territory, today)

- Sales by category with achievement %: Cigarette (sticks), Bidi, Lighter (pieces/box — confirm unit, `docs/13`), Match (dozen).
- By Channel STD (pie, e.g. GT 100%); CPR card (target outlets, successful calls, strike rate %); By Segment Value Contribution (value vs volume); By Brand Call/Memo Ratio.
- Login & Sales-submit status: login % = total login / target routes; submit % = total submitted / login count. Drill-downs "Not Logged In" / "Not Uploaded" listing user (SR/AMO name, username, route, dep name).

## Drawer menu

Dashboard, Leave, Final Submit, My Periphery (My Team, Retailer), My Call (My Visit Plan, Set Plan), Target Status, My Feedback.

## Leave

- Leave applications list (screenshot 19): oldest first (the screenshot wins over the manual's "newest first", D-TSO-MAN-33).
  Each card: calendar icon with "from to to", "Reason: ...", the day count in a circle ("Day"/"Days"), and the orange status
  pill ("DMO approval pending"); no leave type. TSO leave is approved by the DMO. An application still on the phone says
  "Not sent yet"; one the server refused on sync says "Not accepted: <reason>". Apply For Leave at the bottom.
- Apply (screenshots 20 to 22): Leave Type dropdown with nothing chosen (types from the bundle code list `leave_type`, else
  Casual, Sick, Earn), Date empty until picked from the calendar, typed Number of days (starts at 0), Reason, and the lime Apply
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
`final_submit`. After it, the SR and AMO phones of the zone lock capture for the route-day (printing stays allowed) and show
a banner (D-TSO-MAN-31).

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
  never a stream. Online the server answers (`GET /v1/outlets/nearby`, reach from the token); offline the phone works
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

## Target Status

- Monthly Target | Till Date Target; Territory card + Zone cards with Cigarette/Bidi/Lighter/Match bars (achieved/target, %). Details → item table (Item at SKU/variant level e.g. "Ananda Bidi 25", "Special Abul Bidi 25", "Maxim Double Burst"; Target, Achievement, Remaining, %).

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
