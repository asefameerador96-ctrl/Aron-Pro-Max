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
  pulled down (no timer) and kept on the phone for offline viewing with their "as of" time.
- **Push:** `ticket_updated` (support changed, replied to or resolved a ticket) opens the Ticket Dashboard.
