# 08 — TSO App

The Territory Sales Officer works one territory. Darker themed app (as today). Login = username + password. **Logout wipes all local app data** ("your all app data will be removed") — keep that behaviour. Same offline/sync rules.

## Dashboard (territory, today)

- Sales by category with achievement %: Cigarette (sticks), Bidi, Lighter (pieces/box — confirm unit, `docs/13`), Match (dozen).
- By Channel STD (pie, e.g. GT 100%); CPR card (target outlets, successful calls, strike rate %); By Segment Value Contribution (value vs volume); By Brand Call/Memo Ratio.
- Login & Sales-submit status: login % = total login / target routes; submit % = total submitted / login count. Drill-downs "Not Logged In" / "Not Uploaded" listing user (SR/AMO name, username, route, dep name).

## Drawer menu

Dashboard, Leave, Final Submit, My Periphery (My Team, Retailer), My Call (My Visit Plan, Set Plan), Target Status, My Feedback.

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

## My Feedback

Category (e.g. Suggestion), Title, Description, image from gallery → Save.

## Notes for the rebuild

- The TSO app is mostly **read + a few control actions** (final submit, visit plans, leave, feedback, task assignment). It reads the same aggregates as the web dashboard, scoped to the territory — build those reads once and feed both (`docs/10`).
- "Digonto" (paan-masala) SKU assets appear in the current TSO build — the territory view may span product lines beyond tobacco. Confirm product scope for the TSO role in `docs/13`.
