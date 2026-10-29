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

- **My Team:** zone → live SR locations on a map (last synced fixes).
- **Retailer:** zone + radius (50 / 100 / 300 m) → outlets around the TSO's current location on a map.

## My Call (TSO market visit plan)

- **Set Plan:** date, zone, route → Show Outlet → multi-select outlets (name, code, cluster, owner, phone) → Set Plan.
- **My Visit Plan:** date, zone, route → Visit Plan Outlets tabs Pending / Completed (outlet card: code e.g. DHK-344-011, owner, contact, channel, cluster).
- **Visit:** "Visit Query" questionnaire to the retailer (Does the SR visit regularly? Does the SR print memos regularly?) + "Delegate task?" Yes/No → Submit; Assign Task (type e.g. Irregular Visit, date, comment) → Assign. → `call_assessment(kind=retailer_questionnaire)`, `task`.

## Target Status

- Monthly Target | Till Date Target; Territory card + Zone cards with Cigarette/Bidi/Lighter/Match bars (achieved/target, %). Details → item table (Item at SKU/variant level e.g. "Ananda Bidi 25", "Special Abul Bidi 25", "Maxim Double Burst"; Target, Achievement, Remaining, %).

## My Feedback

Category (e.g. Suggestion), Title, Description, image from gallery → Save.

## Notes for the rebuild

- The TSO app is mostly **read + a few control actions** (final submit, visit plans, leave, feedback, task assignment). It reads the same aggregates as the web dashboard, scoped to the territory — build those reads once and feed both (`docs/10`).
- "Digonto" (paan-masala) SKU assets appear in the current TSO build — the territory view may span product lines beyond tobacco. Confirm product scope for the TSO role in `docs/13`.
