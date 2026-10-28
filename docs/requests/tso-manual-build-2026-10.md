# TSO manual build (October 2026): build contract for 10 lanes

Status: lead build contract, 2026-10-28. Binding for lanes F1, F2, F3, B1, B2, B3, W1, A1, A2, A3.

- Sources: owner manual `docs/evidence/manuals/manual-tso-2026-10.md`; screenshots `docs/ui-reference/tso/NN-*.jpg`
  (D-TSO-MAN-01: these screenshots are the TSO visual reference); D-TSO-MAN-02 (targets built now; loyalty and
  discounts stay deferred); D-TSO-MAN-03 (push through a lane branch); D-TARGET-MIN-01; D-51.
- Gap item IDs come from the lead's gap analysis: SH (shell), DASH, TK (tickets), LV (leave), FS (final submit),
  PER (periphery), SP/VP/VQ/AT/XA/MC-T (my call), T (target), FB/DP (feedback, data platform). CR-1..CR-8 are the
  critic findings in this order: TSO login OTP, extra gates before the Dashboard, reopen replay bug, Visit Query back,
  next zone after OK, global-only holidays, free qty in Total Sales, ticket comments.
- Environment: test account at pilot size (rule 1). Nothing here creates Azure resources. Every proof runs in CI or
  local tests, plus the device-lab pilot phones against dev (test account). Nothing here claims fleet-scale proof.
- Process: each lane works on branch `tso-man/<lane-id>` (D-TSO-MAN-03) and opens no pull request (rule 11). Each
  lane writes its decisions, its contract or DB asks and its deviations in `docs/status/tso-manual-<lane-id>.md`.
  Only the lead writes `DECISIONS.md`.
- Precedence inside this file: §1 decisions, then §4 formulas, then §3 contract, then lane text.

## 1. Decisions D-TSO-MAN-04 and on

"Screenshot" means the current Aron app (mission: match current behaviour first; rule 14a). Each row ends with its
reason.

- **D-TSO-MAN-04 Conflict rule.** Where the manual text and the screenshot numbers disagree on a formula, the
  screenshot (current behaviour) wins. The server returns the raw integers for both readings, so a switch is one
  config change. Every conflict is listed in §7.2. Reason: CLAUDE.md mission, rule 14a, lead ruling 1.
- **D-TSO-MAN-05 Till-date target (TSO).** Basis `cfg.kpi.tilldate_basis.tso` ∈ {calendar_days, working_days},
  default calendar_days.
  - calendar_days: e = day-of-month(D), n = days in the month. working_days: e = working days in [1st..D] (D included
    when it is a working day), n = working days of the month. Past month e = n; future month e = 0.
  - Item (variant) row TD = round_half_up(T·e/n); a category or card figure is the sum of the item rows of that node.
    This amends D-51 ("rounded up"): all 11 screenshot figures reproduce only with 25/31 half up (§4.3).
  - AMO Team Performance uses `cfg.kpi.tilldate_basis.amo_team`, default calendar_days. Reason: ruling 1a.
- **D-TSO-MAN-06 Brand CPR.** Numerator: distinct (route, outlet) pairs with an effective sale line of the brand.
  Denominator: `cfg.kpi.brand_cpr_denominator.tso` ∈ {successful_calls, target_outlets}, default successful_calls
  (screenshot: 67% and 33% with 3 successful calls). The response carries both bp values and both denominators.
  Reason: ruling 1b.
- **D-TSO-MAN-07 Bikroy Joma %.** On the TSO app: submitted / target routes (manual text and screenshot 7 agree:
  1 of 5 = 20%), new field `submit_of_target_pct_bp`. `submit_pct_bp` (÷ successful login) stays for the web brief.
  B2 corrects docs/10:26; A1 corrects docs/08:9. Reason: ruling 1c.
- **D-TSO-MAN-08 Today's target.**
  - Σ monthly target (routes × SKUs in scope, month of D) / W(node), Fridays and holidays excluded. On a Friday or
    holiday the literal formula applies (no zeroing).
  - Node = lowest common ancestor of the effective scope (filter ∩ token reach): one zone gives the zone, one
    territory the territory, mixed or national gives global.
  - Holidays: global plus wing, division, territory and zone holidays on the node's path, plus make-up days on that
    path. Weekend = `cfg.calendar.weekend_days` resolved at the node.
  - One implementation, `com.aktcl.aron.rules.WorkingDays` (F3), used by NationalDashboard, AdminCalendar,
    TargetAchievementApi and DailyTrackingApi. `dw.v_calendar` / `v_calendar_month` stay global with a Friday weekend,
    are documented as such and are tested equal to WorkingDays for global scope. Reason: ruling 2, CR-6.
- **D-TSO-MAN-09 Total Sales.** Sold quantity of sale lines only (no free or DRP lines), base units (cigarette and
  bidi sticks, lighter pieces, match dozens). `summary.by_category` becomes sold-only. Summary CPR, login and geo use
  the national route set (normal SR route-days, planned, not excused). Every TSO dashboard card reads
  `/v1/dashboards/national/*`. One parity test proves summary == national for each card on one fixture day.
  Reason: ruling 3, CR-7.
- **D-TSO-MAN-10 Numbers.**
  - Every percentage is rounded once from integers (§4.1); no figure comes from a rounded intermediate. One formatter
    (F3), mirrored on the web; a shared corpus proves they agree.
  - Quantities: one decimal half up; from 1,000 they print as value/1000 with one decimal plus " K", whole part grouped
    ("1,234.6 K"). No M and no lakh/crore on the phone. Bengali digits in Bangla; "K" and "%" stay Latin.
  - Bar bands `cfg.kpi.bar_bands` default [40,80] on the printed integer %: red < 40 ≤ amber < 80 ≤ green.
    Reason: ruling 4.
- **D-TSO-MAN-11 Segment shares.** Value share = segment sale-line gross milli-taka / total (discounts are zero under
  docs/27). Volume share is computed within the segment's unit family (sticks: cigarette + bidi; pieces; dozens);
  different units are never added. X axis ordered by value descending. Reason: ruling 5.
- **D-TSO-MAN-12 Not Logged In / Not Uploaded.**
  - Rows are (route, user) over the card's target routes. Not Logged In: routes without a successful login. Not
    Uploaded: routes with a successful login and no submit.
  - Per route: one row for the route-day SR (vacant: user null, shown "SR Not Set"), then one row per active AMO whose
    scope covers the route's zone on D, by username. AMO rows follow the route state, not the AMO's own login
    (screenshot 8 shows AMO-6334 under several routes and in both tabs).
  - Fields: display name = `full_name` ("SR - Testing Banani"), User Name = `username`, Route = route name, Dep Name =
    zone name (screenshot 8 "Banani - Test" is the zone). Reason: ruling 6.
- **D-TSO-MAN-13 TSO look.** Core-ui `AronLook.TsoNight` (navy page, purple-gradient glow cards, cyan figures,
  green-to-yellow gradient title, grey round back button, glow fields, gradient buttons) for the login and every
  signed-in TSO screen. Sunlight mode overrides it; key figures meet 7:1 (TokenContrastTest). PARITY-SPEC lines 5 and
  47 ("TSO = SR look") are superseded for TSO. Reason: ruling 7, D-TSO-MAN-01.
- **D-TSO-MAN-14 Navigation.**
  - Dashboard is the root after login. Top bar: ☰, "Good day, <full_name>", bell with an unread badge.
  - Drawer header: "TSO" over the bold name, plus a gear that opens Settings (language, Sunlight, version, update, PDA
    to Support, Change Password; small improvement).
  - Drawer rows, exactly: Dashboard; Tickets ˅ (Ticket Dashboard, All Tickets, Quick Ticket, Create Ticket); Leave;
    Final Submit; My Periphery ˅ (My Team, Retailer); My Call ˅ (My Visit Plan, Set Plan); Target Status;
    My Feedback ˅ (Add new feedback, My feedback); Log Out. Titles and subtitles from screenshots 9, 10, 27, 30;
    "My feedback" (own list) is an improvement.
  - The tile Home and `RoleHomes.tsoTiles` are retired for TSO. Back from any drawer screen goes to the Dashboard. Home
    button per the §8 table. No zone filter on the TSO dashboard (none in the screenshots; scope is the token reach).
    Reason: ruling 7.
- **D-TSO-MAN-15 Login screen (screenshot 5).** Round mint-to-pink logo plate, "Username" and "Password" labels above
  glow fields, centred cyan-to-blue Login. Kept as small improvements: a language text toggle at the bottom
  (constraint 8: choose the language before login) and the password eye. Sun switch and version line move to
  Settings. The launcher icon keeps the D-BRAND-02 plate; the screenshot's light plate is a recorded difference
  (§7.2). Reason: ruling 7.
- **D-TSO-MAN-16 Login and gates.** The TSO login stays without an OTP (D-AUTH-TSO-NOBIND, CR-1); the "Login OTP issue"
  ticket type serves SR and AMO. The enrolment gate (side-loaded pilot phones only; fleet phones are QR-provisioned)
  and the one-time location notice stay, for security and consent (CR-2). A1 records both as deltas in
  `docs/evidence/manuals/delta-tso.md`.
- **D-TSO-MAN-17 Tickets module.** New shared module `android/feature-tickets`; TSO wires it now, SR and AMO later. The
  server takes requester roles from `cfg.support.ticket_requester_roles`, default ["TSO"]; other roles are
  quarantined `scope_out_of_reach` (catalogue class; amended by R-F-05). Reason: ruling 8.
- **D-TSO-MAN-18 Ticket identity.** Number "INC" + at least 6 digits from `app.support_ticket_no_seq` START 100001, so
  the vendor's numbers (INC0107xx), which TSOs will quote, never collide. Assigned at first insert; 7 digits after
  INC999999. The client generates the UUID v4. Tickets are created only through the sync record `support_ticket`
  (offline outbox, idempotent upsert by UUID); there is no online create endpoint, so there is one ingest path.
- **D-TSO-MAN-19 Issue types.** Code list `ticket_issue_type`: the 31 Annex A names verbatim, in Annex A order, attrs
  `{priority:"p1", category:"tickets", urgency:"high", icon:(sort-1)%8, hint_en:<Annex A "use it when">}`. All 31
  default to P1 (screenshot 15 shows "Tickets · High" on every tile; every ticket in 11–14 is "P1 - Highest"). Labels
  P1 - Highest, P2 - High, P3 - Medium, P4 - Low; support may change the priority. The Astha, Discount, Campaign and
  Target Panel types are labels only (docs/27 unaffected). `label_bn` stays NULL until AKTCL supplies it (V0027 rule).
- **D-TSO-MAN-20 Ticket transitions (server-enforced).**
  - Support: new→in_progress (Start, or the first support reply on a New ticket).
  - Support: new|in_progress→resolved, with `resolution_code` (list `ticket_resolution_code`, seed resolved =
    "Resolved") and notes 1..2000. It writes the support message
    `[TicketID: <no>] - Resolved:\nResolution Code: <label>\nResolution Notes:\n\n<notes>` (screenshot 14).
  - Support: new|in_progress→cancelled, reason 3..500. Reporter: resolved→closed by the `close_review` event ("Submit
    Review & Close", one tap, no rating). Support may also close a resolved ticket.
  - No reopen and no auto-close: if not solved, the reporter raises a new ticket quoting the old number (manual).
- **D-TSO-MAN-21 Ticket comments.** The reporter (sync event `comment`) and support (REST) may comment only while the
  ticket is new or in_progress. After that the app shows "This ticket is resolved. No further comments can be added."
  (screenshot 14) and the server refuses (CR-8). Every support role is shown as "Support", never by name.
- **D-TSO-MAN-22 Ticket scope.** The reporter sees own tickets (requester = token user). The support desk is the
  permission `support.ticket.manage` in `app.role_grant_map`, seeded for ADMIN and SUPERADMIN; SUPPORT can be granted it
  on the permission page without code. New `Me.support_desk` boolean drives the web menu.
- **D-TSO-MAN-23 Ticket photos.** At most `cfg.support.ticket_max_photos` (default 3) per ticket and per event; media
  purpose `support`, ref_type `support_ticket` / `support_ticket_event`. Gallery via `PickVisualMedia` (no storage
  permission) or camera, both through the existing PhotoCompressor.
- **D-TSO-MAN-24 Affected user.** Optional `affected_username` (3..40) for a ticket raised from another user's phone;
  the server resolves it to `affected_user_id` for the desk only. The ticket stays the reporter's.
- **D-TSO-MAN-25 Ticket counts.** Total = all statuses; Open = new + in_progress; Resolved % = whole(resolved, total);
  Open % = whole(open, total); total 0 → 0%. Offline: cached list plus unsent rows counted as New, merged by UUID. Age
  text: <1 min "just now", <1 h "Nm ago", <24 h "Nh ago", else "Nd ago", from captured_at and trusted now.
- **D-TSO-MAN-26 Notifications inbox.**
  - `app.user_notification`, kinds ticket_updated, leave_decided, final_submit_auto (the TSO-to-SR task is out of scope
    for the bell). Unread count for the bell, read on Dashboard open, pull-to-refresh and push reason `notification`;
    no polling.
  - The bell opens a plain inbox list (no screenshot; improvement); opening it marks everything up to the newest id
    read. Announcements stay push-only (no compose page; not in the manual). Retention
    `cfg.notify.inbox_retention_days` (90).
- **D-TSO-MAN-27 Final Submit flow (screenshots 23–26).**
  - The preview gives each route an `upload_state`: uploaded, web_entry, not_uploaded, not_due. A saved Web Entry
    counts as uploaded; dummy BR and Institutional routes without data are not_due.
  - Not-uploaded due routes give a warning dialog that names them ("Submit anyway" / "Cancel"); it never blocks. No
    confirm dialog when nothing is due. Success: "Success" / "Final Submit Done Successfully..." / OK. OK returns to
    the selectors with the zone cleared, ready for the next zone (CR-5).
  - Card title `<route name> <kind label>` (Daily/3F/2F/AMO; the space is an improvement over "Apsis RouteDaily");
    FF line "FF: <full_name>" or "FF: SR Not Set". Dropped: the memo/net line and the success totals (screenshot).
    Added: an amber "Not uploaded" chip on due routes (improvement serving manual s8). The red dot is not drawn.
    `cfg.day.final_submit_allow_not_set_routes` is not read by this flow.
- **D-TSO-MAN-28 Reopen replay fix (CR-3).** The preview returns `submit_seq` = number of reopened `final_submit` rows
  for the zone-day. Client UUID = name-based UUID of `aron.tso.final-submit:<user>:<zone>:<date>:<submit_seq>`. A
  server replay succeeds only when the matched row is live (`reopened_at IS NULL`); otherwise 409
  `ERR_DAY_FINAL_SUBMIT_REOPENED`, and the app re-previews.
- **D-TSO-MAN-29 Reopen.** `POST /v1/day/reopen` is implemented, idempotent by the optional `client_uuid`. Roles
  `cfg.day.reopen_roles` (default changed to ["ADMIN","SUPERADMIN"]), window `cfg.day.reopen_window_days` (3), reason
  10..500. Lock order: zone-day advisory lock, then route-days by route_id. Audited, emits `zone_day.reopened`, marks
  the aggregates dirty. The web `/admin/day` form is fixed (it posts to a 404 today).
- **D-TSO-MAN-30 Midnight auto Final Submit.**
  - `AutoFinalSubmitJob` runs at 00:00 Asia/Dhaka and, at start, catches up the last `cfg.day.reopen_window_days` days.
    It covers every service zone (planned normal SR route on D-1) without a live submit.
  - It uses DayService's locked path: `via='job'`, `submitted_by` NULL, audit via job with actor "system". Idempotent
    (`final_submit_once`); inbox rows to the TSOs whose reach covers the zone; emits `zone_day.final_submitted`. Switch
    `cfg.day.auto_final_submit_enabled` (true).
  - `DashboardSummaries.autoSubmit` (dw-only stamp) is removed. Every final-submit % reads live app.final_submit rows
    (one formula: zones submitted, auto included / service zones).
- **D-TSO-MAN-31 Day lock after Final Submit.**
  - A record whose trusted `captured_at` is before the zone-day's `submitted_at` is quarantined `after_final_submit`:
    stored, held out of the day's figures, counted (flagged late) only when ADMIN accepts it in `/admin/quarantine`.
    No offline sale is lost (constraint 2).
  - A record captured at or after the lock (memo, memo edit, void, return, collection) is rejected
    `day_final_submitted` (final, not retryable). `day_submit` and `attendance_event` after the lock are accepted
    (state only, no figures). `cfg.day.late_sync_after_final_policy` defaults to quarantine.
  - Push `day_final_submitted` goes to the SR and AMO devices of the zone; those apps lock capture for the route-day
    (printing stays allowed) and show a banner. `cfg.day.submit_grace_h` cannot unlock an auto-submitted zone.
    Reason: ruling 10, constraint 2; §7.1 Q1.
- **D-TSO-MAN-32 Cascade.** Wing > Division > Territory > House > Zone from `GET /v1/dashboards/national/filter-options`
  (FilterNode parent ids; reach from the token). A level with one option is preselected.
- **D-TSO-MAN-33 Leave (screenshots 19–22).**
  - Card: range with a calendar icon, day count in a circle ("Day"/"Days"), "Reason:", orange status pill, no leave
    type. Order: ascending by from_date (screenshot 19, conflict rule).
  - Form: Leave Type dropdown with no preselection (types from the bundle code list), date empty until picked, typed
    days field, Apply under the form; after Apply, back to the list with a toast.
  - to_date = from + days − 1 in calendar days, Fridays and holidays counted (fixes the vendor's one-day limit that
    testers reported in screenshot 19). A refused row shows "Not accepted: <reason>". The same checks run on sync and
    POST (LeaveApplicationHandler). Config `cfg.leave.allow_past_days` 31, `allow_ahead_days` 365, `block_overlap`
    false.
- **D-TSO-MAN-34 Periphery (screenshots 27–29).**
  - My Team: last synced non-mock fix, "last seen HH:mm (n min ago)", Refresh action. No live tracking and no locate
    ping (constraint 3); D-170 kept.
  - My Team and Retailer maps are interactive (zoom, pan, tap), loaded only while open, tilt off, no my-location layer.
    This amends D-08 for TSO; AMO stays on the lite map.
  - Retailer: one balanced fix per zone or radius pick, plus a refresh icon; offline it computes nearby outlets from
    the bundle with the server's rule. Stale grey-out after `cfg.tso.team_location_max_age_min` (120);
    `mock_fixes_today` chip; group by route; bbox margin; roundToInt.
- **D-TSO-MAN-35 My Call (screenshots 30–38).**
  - Separate screens: Set Plan, Select Outlets, My Visit Plan, Visit Plan Outlets, Visit Query, Assign Task. Dropdowns;
    Set Plan defaults to today; tab counts removed; Select all kept (improvement). The 5-line outlet card shows
    Channel. `visit_plan.route_id` is stored.
  - Visit Query: rubric `retailer_questionnaire` v1 seeded (two text questions; bn from screenshot 37, en from the
    manual); answers ≤ 1000 characters (amends D-199's 500); delegate Yes/No radio, default No. Task types: active
    `task_type` items for TSO. The SR card shows the label, not the code; the bundle carries `TaskDto.route_id`. System
    back and on-screen back go to the same place (CR-4).
- **D-TSO-MAN-36 Feedback (screenshot 43).** Categories suggestion and feedback (supersedes D-197), as a vertical list;
  placeholders "Enter title" / "Enter description"; label "Descriptions"; Browse Gallery only,
  `cfg.feedback.max_images` 1; after Save, a toast and back; `business_date` shown; ingest precheck shared with POST.
- **D-TSO-MAN-37 Logout.** Last drawer row. Dialog title "Log Out!", body "All app data on this phone will be removed."
  (grammar fix of "Your all app data will removed."; §7.1 Q6), green Cancel, orange Log Out. Logout is still refused
  while items are unsent (G-man-tso-01). The wipe also deletes `aron_ui_<id>.xml` and clears retained state (SH-17).
- **D-TSO-MAN-38 Data platform (rule 12).** Views `dw.v_support_ticket`, `v_leave_application`, `v_feedback` (no free
  text), `v_visit_plan_outlet`, `v_call_assessment`, `v_task`. Events, all v1: `support_ticket.created`,
  `support_ticket.status_changed`, `support_ticket.commented`, `zone_day.final_submitted`, `zone_day.reopened`,
  `leave.decided`.
- **D-TSO-MAN-39 Target Status API.** `getTargetAchievement` is built with `children=zone`, integer fields, and nodes
  from reach (never from client ids). The Decimal3 and `achievement_pct` fields stay filled for the AMO app. The basis
  follows the caller's role: TSO the `.tso` key, AMO the `.amo_team` key.
- **D-TSO-MAN-40 Target Status UI (screenshots 39–42).** Cards: the territory plus each zone in reach. Card % whole,
  capped at 100; Details % 2 decimals, uncapped. Details rows are variants in catalogue order; tapping a category bar
  filters the table. Offline: the cached copy with "as of".
- **D-TSO-MAN-41 ▸ on By Channel STD.** Switches between By Channel STD and By Channel Successful Call (the older
  manual's callout) and back; the choice is saved.
- **D-TSO-MAN-42 Release copy.** CI also writes `aron_tso_app_<dd_mm_yyyy>_v<versionCode>.apk` (Dhaka build date) with
  a line in SHA256SUMS. The existing names stay for the updater.
- **D-TSO-MAN-43 Contract 1.10.0.** A minor bump. The response-enum additions trip the breaking gate, so F1 adds
  `docs/requests/contract-tso-manual-1.10.md`, which this file approves. Only F1 edits `contract/openapi.yaml`; later
  asks are batched by F1 into 1.10.x.
- **D-TSO-MAN-44 Migrations.** Block V0130–V0149 is reserved for this build; only F2 writes it.
- **D-TSO-MAN-45 Map config keys.** `cfg.map.provider` and `cfg.map.tile_cache_mb` are not added: one provider exists
  and the SDK owns its cache. They return when a second provider exists. Reason: smallest correct.

## 2. Data model (F2). Forward-only, block V0130–V0149

Rules for the block:
- Another lane may land V0123–V0129 concurrently; this build never uses a number below V0130. Flyway out-of-order is
  off: a lane that lands below V0130 after this block merges must renumber above V0149.
- Every file starts with `SET lock_timeout = '5s';` and ends with `SELECT app.apply_db_role_grants();` when it adds
  objects. A new FK or CHECK on an existing table is added NOT VALID and validated in the next file (V0075/V0076,
  V0112/V0113 pattern).
- Every new table, column and view gets a COMMENT; tables end with
  `owner: <lane> | capture: OFFLINE|ONLINE | retention: <class as RetentionPolicyTest> | pii: <none|low>`. Then
  `tools/data-dictionary/render.sh` regenerates `docs/data-dictionary.md`, `docs/data-events.md`,
  `docs/dw-view-contract.md`.
- New capture tables follow V0116: a `data_origin app.data_origin` column, a row in `app.data_origin_capture`, the
  `app.stamp_data_origin` trigger, and `app.guard_synced_row('=voided_at')`.

| File | Content |
|---|---|
| V0130__support_ticket.sql | Sequence and the three ticket tables (§2.1) |
| V0131__code_lists_tso_manual.sql | code_list CHECK replaced NOT VALID (+`ticket_issue_type`, `ticket_resolution_code`); `app.code_list` rows; 31 issue items (§2.2); resolution code `resolved`/'Resolved'; `feedback_category` item `feedback`/'Feedback' sort 2 (label_bn NULL); all ON CONFLICT DO NOTHING |
| V0132__code_lists_validate.sql | `VALIDATE CONSTRAINT code_list_list_key_check` |
| V0133__user_notification.sql | `app.user_notification` (§2.3) |
| V0134__tso_manual_config.sql | cfg keys (§2.4); `UPDATE app.cfg_key` defaults: `cfg.day.late_sync_after_final_policy` → "quarantine", `cfg.day.reopen_roles` → ["ADMIN","SUPERADMIN"]; `app.role_grant_map` ('ADMIN','support.ticket.manage'), ('SUPERADMIN','support.ticket.manage'); `app.domain_event_type` rows for the 6 events of D-TSO-MAN-38 (v1) |
| V0135__final_submit_job_and_codes.sql | `app.final_submit`: `via` CHECK replaced ('app','web','job') NOT VALID; `ALTER COLUMN submitted_by DROP NOT NULL`; `final_submit_job_actor` CHECK ((via='job') = (submitted_by IS NULL)) NOT VALID. `app.sync_rejected.code` CHECK replaced NOT VALID (+`day_final_submitted`, `ticket_state_invalid`, `issue_type_unknown`). `app.sync_quarantine.code` CHECK replaced NOT VALID (+`after_final_submit`), keeping every existing code. Fix the comments of `final_submit.late_rows` and `route_day.late_rows_after_final` (V0016:861 is wrong): "rows captured before the submit that arrived after it" |
| V0136__final_submit_job_validate.sql | VALIDATE the four constraints of V0135 |
| V0137__visit_plan_route.sql | `app.visit_plan.route_id bigint`, FK to app.route NOT VALID, comment "route chosen in Set Plan; NULL on rows before V0137" (RecordWriter stores the envelope route_id) |
| V0138__visit_plan_route_validate.sql | VALIDATE |
| V0139__retailer_questionnaire_seed.sql | `app.rubric` (kind 'retailer_questionnaire', active) + `app.rubric_version` v1, criteria `[{criterion_id:1,key:"sr_visits_regularly",label_bn:"SR আপনার দোকান নিয়মিত ভিজিট করে?",label_en:"Does the SR visit your shop regularly?",answer_type:"text"},{criterion_id:2,key:"sr_prints_memo",label_bn:"SR নিয়মিত মেমো প্রিন্ট করে দেয়?",label_en:"Does the SR give you a printed memo regularly?",answer_type:"text"}]`, idempotent |
| V0140__dw_tso_views.sql | The six dw views (§2.5), grants as other dw.v_* (bi_reader, lake_export), `data_origin` last column; `COMMENT ON VIEW dw.v_calendar`: "global holidays and the default Friday weekend; scoped holidays and a configured weekend are applied by the API (WorkingDays); tested equal for global scope" |

### 2.1 Ticket tables (V0130)

```
CREATE SEQUENCE app.support_ticket_no_seq START 100001 NO CYCLE;

app.support_ticket                      -- capture row, write-once (record type support_ticket)
  id bigint identity PK, client_uuid uuid NOT NULL UNIQUE,
  <envelope columns exactly as app.feedback V0007:1447-1469: family_uuid .. external_ref, incl. business_date date
   NOT NULL, user_id NOT NULL (token), captured_at timestamptz NOT NULL, voided_at>,
  ticket_no text NOT NULL UNIQUE DEFAULT ('INC' || lpad(nextval('app.support_ticket_no_seq')::text, 6, '0'))
            CHECK (ticket_no ~ '^INC[0-9]{6,}$'),
  issue_type_code text NOT NULL CHECK (issue_type_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  title text NOT NULL CHECK (length(title) BETWEEN 1 AND 120),
  description text NOT NULL DEFAULT '' CHECK (length(description) <= 2000),
  affected_username text CHECK (affected_username ~ '^[A-Za-z0-9._-]{3,40}$'),
  source text NOT NULL CHECK (source IN ('quick','manual')),
  data_origin app.data_origin
  INDEX (business_date), INDEX (user_id, created_at DESC)

app.support_ticket_state                -- mutable state, one row per ticket (handler + desk write it)
  ticket_uuid uuid PK REFERENCES app.support_ticket(client_uuid),
  requester_user_id bigint NOT NULL REFERENCES app.app_user(id), requester_role text NOT NULL REFERENCES app.role_def(role),
  affected_user_id bigint REFERENCES app.app_user(id),
  status text NOT NULL DEFAULT 'new' CHECK (status IN ('new','in_progress','resolved','closed','cancelled')),
  priority text NOT NULL CHECK (priority IN ('p1','p2','p3','p4')),
  assignee_user_id bigint REFERENCES app.app_user(id),
  first_response_at timestamptz, resolved_at timestamptz, resolved_by bigint REFERENCES app.app_user(id),
  resolution_code text CHECK (resolution_code ~ '^[a-z][a-z0-9_]{1,40}$'),
  resolution_notes text CHECK (length(resolution_notes) BETWEEN 1 AND 2000),
  closed_at timestamptz, closed_by bigint REFERENCES app.app_user(id),
  cancelled_at timestamptz, cancelled_by bigint REFERENCES app.app_user(id),
  cancel_reason text CHECK (length(cancel_reason) BETWEEN 3 AND 500),
  version int NOT NULL DEFAULT 1 CHECK (version >= 1), updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK (status NOT IN ('resolved','closed') OR closed_at IS NOT NULL OR (resolution_code IS NOT NULL AND resolution_notes IS NOT NULL)),
  CHECK ((status = 'cancelled') = (cancelled_at IS NOT NULL))
  INDEX (requester_user_id, status), INDEX (status, updated_at), INDEX (assignee_user_id) WHERE assignee_user_id IS NOT NULL

app.support_ticket_event                -- append-only thread (device rows via sync, desk/job rows via API)
  id bigint identity PK, client_uuid uuid NOT NULL UNIQUE,
  ticket_uuid uuid NOT NULL REFERENCES app.support_ticket(client_uuid),
  <envelope columns as app.feedback; desk rows: family_uuid = client_uuid, business_date = app.dhaka_date(now()),
   captured_at = now(), config_version = current, user_id = actor>,
  via text NOT NULL DEFAULT 'device' CHECK (via IN ('device','web','job')),
  kind text NOT NULL CHECK (kind IN ('created','comment','close_review','status_change','resolution','priority_change','assign')),
  body text CHECK (length(body) <= 2000), from_status text, to_status text, resolution_code text,
  data_origin app.data_origin
  INDEX (ticket_uuid, created_at); registry: child of app.support_ticket (child_col ticket_uuid, parent_col client_uuid)
```

### 2.2 Issue type codes (V0131). Labels are the Annex A text verbatim; sort 1..31

app_crash_order_submit, app_version_update, astha_gift_disbursement, astha_target, auto_logout, camera_black_screen,
campaign, change_request, check_in_out, cluster_mapping, discount, ff_geo_location_map, final_submission,
gps_inaccuracy, login_otp, memo_printing, new_application_needed, others, outlet_related, outlet_request,
outlet_request_approval, password_reset, printer_connection, reporting, sales_adjustment, sales_order_failing,
sales_submit, sync_stuck, target_panel, user_login, web_entry.

attrs per D-TSO-MAN-19. Icon index 0..7 maps to: key, fingerprint, screen_off, sync, navigation, receipt, cart,
battery (screenshot 15).

### 2.3 Notifications (V0133)

```
app.user_notification
  id bigint identity PK, user_id bigint NOT NULL REFERENCES app.app_user(id),
  kind text NOT NULL CHECK (kind IN ('ticket_updated','leave_decided','final_submit_auto')),
  ref_type text NOT NULL CHECK (ref_type IN ('support_ticket','leave_application','zone_day')), ref_id text NOT NULL CHECK (length(ref_id) <= 64),
  title_en text NOT NULL CHECK (length(title_en) <= 120), title_bn text CHECK (length(title_bn) <= 120),
  body_en text NOT NULL CHECK (length(body_en) <= 300), body_bn text CHECK (length(body_bn) <= 300),
  dedupe_key text NOT NULL CHECK (length(dedupe_key) <= 160),  UNIQUE (user_id, dedupe_key),  -- per recipient (R-F-04)
  created_at timestamptz NOT NULL DEFAULT now(), business_date date NOT NULL DEFAULT app.dhaka_date(now()),
  read_at timestamptz
  INDEX (user_id, id DESC), INDEX (user_id) WHERE read_at IS NULL
  -- owner: backend:notify | capture: ONLINE | retention: cfg.notify.inbox_retention_days | pii: low
```

### 2.4 Config keys (V0134). Columns as V0086; risk class 1 unless noted; editor permission in brackets

| key | type | default | bounds | scope | delivery |
|---|---|---|---|---|---|
| cfg.kpi.tilldate_basis.tso | enum | "calendar_days" | [calendar_days, working_days] | global | server [cfg.edit.field] |
| cfg.kpi.tilldate_basis.amo_team | enum | "calendar_days" | same | global | server |
| cfg.kpi.brand_cpr_denominator.tso | enum | "successful_calls" | [successful_calls, target_outlets] | global | server |
| cfg.kpi.bar_bands | list | [40,80] | 2 ints 1..99 ascending | global | both |
| cfg.support.ticket_requester_roles | list | ["TSO"] | subset of SR, AMO, TSO | global | server [cfg.edit.security], risk 2 |
| cfg.support.ticket_max_photos | int | 3 | 0..3 | global | both |
| cfg.notify.ticket_push_enabled | bool | true | | global | server [cfg.edit.ops] |
| cfg.notify.inbox_retention_days | int | 90 | 30..365 | global | server [cfg.edit.ops] |
| cfg.day.auto_final_submit_enabled | bool | true | | global, wing | server [cfg.edit.field], risk 3 |
| cfg.tso.team_location_max_age_min | int | 120 | 15..720 | global | both |
| cfg.tso.plan_max_days_ahead | int | 60 | 1..90 | global | both |
| cfg.leave.allow_past_days | int | 31 | 0..90 | global | both |
| cfg.leave.allow_ahead_days | int | 365 | 1..365 | global | both |
| cfg.leave.block_overlap | bool | false | | global | server |
| cfg.feedback.max_images | int | 1 | 0..1 (R-F-06) | global | both |

### 2.5 dw views (V0140). Stable, additive, grants as other dw.v_*

- `dw.v_support_ticket`: ticket_uuid, ticket_no, business_date, created_at, user_id, requester_role, issue_type_code,
  source, priority, status, first_response_at, resolved_at, closed_at, cancelled_at, resolution_code,
  has_affected_user, is_effective (voided_at IS NULL), data_origin. No title or description (free text).
- `dw.v_leave_application`: leave_uuid, user_id, leave_type_code, from_date, to_date, days, status, applied_at,
  decided_at, decided_by_user_id, is_effective, data_origin.
- `dw.v_feedback`: feedback_uuid, business_date, user_id, category_code, status (feedback_status or 'new'),
  has_photo, is_effective, data_origin.
- `dw.v_visit_plan_outlet`: plan_uuid, plan_date, planner_user_id, route_id (coalesce of plan route and the outlet's
  min route), outlet_id, completed_at, assessment_uuid, is_effective, data_origin.
- `dw.v_call_assessment`: assessment_uuid, kind, business_date, user_id, route_id, outlet_id, rubric_id,
  rubric_version, total_score, max_score, delegate_task, is_effective, data_origin.
- `dw.v_task`: task_uuid, business_date, task_type_code, creator_user_id, assignee_user_id, outlet_id, route_id,
  due_date, status, status_changed_at, is_effective, data_origin.

## 3. Contract 1.10.0 (F1 only)

Changes to `contract/openapi.yaml`, info.version 1.9.0 → 1.10.0. Existing naming applies: snake_case, `*_bp` integer
basis points, `*_qty_base` int64 base units, `*_mtk` int64, BusinessDate, Timestamp, Uuid, Id.

### 3.1 New operations

| operationId | method, path | request | 200 response | errors |
|---|---|---|---|---|
| listSupportTickets | GET /v1/support/tickets | q (2..40, ticket_no prefix or text), status, issue_type_code, priority, requester_user_id\*, assignee_user_id\*, FromDate, ToDate (business_date), Limit (1..200, def 50), Cursor | SupportTicketPage | 400, 401, 403 (\* = desk only) |
| getSupportTicketSummary | GET /v1/support/tickets/summary | — | SupportTicketSummary (caller's own; desk: all) | 401 |
| getSupportTicket | GET /v1/support/tickets/{ticket_uuid} | — | SupportTicketDetail | 401, 404 (not own and not desk) |
| updateSupportTicket | PATCH /v1/support/tickets/{ticket_uuid} | SupportTicketUpdate | SupportTicket | 400, 403, 404, 409 ERR_CONFLICT (version), 409 ERR_TICKET_TRANSITION_INVALID |
| addSupportTicketEvent | POST /v1/support/tickets/{ticket_uuid}/events | SupportTicketEventWrite | SupportTicketEvent (200 replay, 201 new) | 403, 404, 409 ERR_TICKET_COMMENTS_CLOSED, 409 ERR_CONFLICT (uuid reused, other body) |
| listMyNotifications | GET /v1/me/notifications | before_id (Id), limit (1..100, def 50) | NotificationInbox | 401 |
| markMyNotificationsRead | POST /v1/me/notifications/read | NotificationReadRequest | NotificationReadResult | 400, 401 |
| queryNationalBrandCpr | POST /v1/dashboards/national/brand-cpr | NationalDashboardFilter | NationalBrandCpr | as queryNationalCpr |
| queryNationalSegmentShare | POST /v1/dashboards/national/segment-share | NationalDashboardFilter | NationalSegmentShare | as queryNationalSales |
| queryNationalLoginSubmitUsers | POST /v1/dashboards/national/login-submit/users?tab=not_logged_in\|not_uploaded (required) | NationalDashboardFilter | NationalLoginSubmitUsers | as queryNationalLoginSubmit |

### 3.2 New schemas (`additionalProperties: false` everywhere)

- **Enums.** SupportTicketStatus [new, in_progress, resolved, closed, cancelled]; SupportTicketPriority [p1, p2, p3,
  p4]; SupportTicketEventKind [created, comment, close_review, status_change, resolution, priority_change, assign];
  NotificationKind [ticket_updated, leave_decided, final_submit_auto]. TicketNumber: string `^INC[0-9]{6,}$`.
- **SupportTicketPayload**: issue_type_code `^[a-z][a-z0-9_]{1,40}$`, title 1..120, description 0..2000,
  affected_username string|null `^[A-Za-z0-9._-]{3,40}$`, source [quick, manual]. **SupportTicketRecord**:
  RecordEnvelope + `type: support_ticket` + payload (as FeedbackRecord).
- **SupportTicketEventPayload**: ticket_uuid Uuid, kind [comment, close_review], body 0..2000 (a comment needs 1..2000;
  server check). **SupportTicketEventRecord**: as SupportTicketRecord with `type: support_ticket_event`.
- **SupportTicket**:
  - ticket_uuid, ticket_no (TicketNumber|null), issue_type_code, issue_type_label_en, issue_type_label_bn|null,
    category_label_en ("Tickets"), title, description, source, priority, status.
  - requester {user_id, username, full_name, role}, affected_username|null, assignee_user_id|null, assignee_name|null
    (desk only).
  - business_date, captured_at, created_at, updated_at, first_response_at|null, resolved_at|null, closed_at|null,
    cancelled_at|null.
  - resolution_code|null, resolution_label_en|null, resolution_notes|null, cancel_reason|null, version (int ≥ 1),
    attachment_count (int ≥ 0).
- **SupportTicketPage** {items (max 200), next_cursor|null}. **SupportTicketDetail** {ticket, events (max 500),
  attachments (Uuid[] max 3)}.
- **SupportTicketEvent**: event_uuid, kind, body|null, actor_is_support (bool), actor_display ("Support" for desk
  roles, else the reporter's full_name), from_status|null, to_status|null, resolution_code|null, created_at,
  attachments (Uuid[] max 3).
- **SupportTicketSummary**: as_of, total, new, in_progress, resolved, closed, cancelled, open (ints ≥ 0),
  (no percentage members: displays compute whole percent once from the counts, R-F-14).
- **SupportTicketUpdate**: op_uuid (Uuid, idempotency key), expected_version (int), optional status ∈ [in_progress,
  resolved, closed, cancelled], priority, assignee_user_id (Id|null), resolution_code, resolution_notes (1..2000),
  cancel_reason (3..500). **SupportTicketEventWrite**: {event_uuid (Uuid), body (1..2000)}.
- **NotificationItem**: id, kind, ref_type ∈ [support_ticket, leave_application, zone_day], ref_id (≤64), title_en
  (≤120), title_bn|null, body_en (≤300), body_bn|null, created_at, business_date, read_at|null.
  **NotificationInbox** {as_of, unread_count, items (max 100), next_before_id|null}. **NotificationReadRequest**
  {up_to_id (Id)}. **NotificationReadResult** {unread_count}.
- **NationalBrandCpr**: date, till_time, as_of, last_updated_at, target_outlets, successful_calls, denominator
  [successful_calls, target_outlets], brands[] (max 100: brand_code, brand_name, outlets_bought, cpr_bp|null,
  cpr_of_successful_bp|null, cpr_of_target_bp|null), sorted by outlets_bought desc, then brand_code.
- **NationalSegmentShare**: date, till_time, as_of, last_updated_at, total_value_mtk, families[] {base_unit,
  total_qty_base}, segments[] (max 50: segment_code, segment_name, category_code, base_unit, value_mtk,
  value_share_bp|null, qty_base, volume_share_bp|null), sorted by value_mtk desc.
- **LoginSubmitUserRow**: route_id, route_code, route_name, zone_id, dep_name, user_id|null, username|null,
  display_name|null, role ∈ [SR, AMO]. **NationalLoginSubmitUsers**: date, till_time, as_of, last_updated_at, tab,
  route_count, items (max 2000, order per D-TSO-MAN-12).
- **TargetAchievementNode** {node: NodeRef, rows: as TargetAchievement.rows}.

### 3.3 Changed schemas and operations (additive)

- **RecordType** + `support_ticket`, `support_ticket_event`; SyncRecord oneOf + the two records.
- **CodeListKey** + `ticket_issue_type`, `ticket_resolution_code`.
- **RecordOutcomeCode** + `after_final_submit` (quarantined), `day_final_submitted` (rejected, not retryable),
  `ticket_state_invalid` (rejected, not retryable), `issue_type_unknown` (rejected, not retryable); docs/24 s4.5
  wording goes in the descriptions.
- **ProblemCode** + `ERR_TICKET_TRANSITION_INVALID`, `ERR_TICKET_COMMENTS_CLOSED`, `ERR_DAY_FINAL_SUBMIT_REOPENED`.
- **NationalSalesCard** + `monthly_target_qty` (int64|null: Σ monthly target in scope). `achievement_pct_bp`
  description: "exact: round_half_up(10000·total_sales·working_days / monthly_target_qty)".
- **NationalLoginSubmit** + `submit_of_target_pct_bp` (int|null: submitted / target_routes).
- **DashboardSummary**: by_category description becomes "sold quantity of sale lines only, base units".
- **getTargetAchievement / TargetAchievement**: new query `children` (enum [zone], optional). Response + day
  (BusinessDate), elapsed_days (0..31), period_days (0..31; 0 makes the till-date members null, R-F-15), last_updated_at, children (TargetAchievementNode[], max
  200). rows + category_code, target_qty_base, achieved_qty_base, tilldate_target_qty_base, remaining_qty_base,
  remaining_tilldate_qty_base (int64 ≥ 0), achievement_pct_bp, tilldate_achievement_pct_bp (int|null). The Decimal3
  fields and `achievement_pct` keep their meaning, for the AMO app.
- **FinalSubmitPreview** + submit_seq (int ≥ 0), warn_route_ids (Id[]); routes[] + route_class|null, visit_kind|null
  (≤20), ff_role|null (Role), upload_state ∈ [uploaded, web_entry, not_uploaded, not_due]. FinalSubmitRequest
  describes the `client_uuid` rule of D-TSO-MAN-28.
- **DayReopenRequest** + `client_uuid` (Uuid, optional).
- **LeaveApplication** + applicant_username|null, applicant_name|null, territory_name|null (read-only).
- **Feedback** + business_date, captured_at, username|null, zone_id|null.
- **TeamLocation** + `mock_fixes_today` (int ≥ 0, default 0).
- **Me** + `support_desk` (bool).

### 3.4 Regeneration (F1, in this order, all committed)

1. Run `python3 tools/slice-contract.py`, then `python3 tools/slice-contract.py --check` (writes
   `contract/slices/**` and INDEX.md).
2. Add the two record payloads and the read DTOs the phone uses to `GROUPS` in
   `shared/contract/tools/gen_wire_dtos.py`; run `python3 shared/contract/tools/gen_wire_dtos.py`, then with `--check`
   (writes WireDtos.kt).
3. Update the hand mirror in `shared/contract/.../ContractEnums.kt` and `ContractCatalogues.kt` for RecordType,
   CodeListKey, RecordOutcomeCode, ProblemCode and the new enums (ContractDriftTest enforces it).
4. Run `cd web && npm run gen:contract` (writes `web/src/contract/openapi.d.ts`), then
   `shared/contract/tools/check-web-contract-drift.sh`.
5. Write `docs/requests/contract-tso-manual-1.10.md` listing every breaking-gate item of §3.3; run
   `tools/ci/contract-breaking.sh <base>`.

## 4. Formulas (integer arithmetic; F3 implements them, every layer calls them)

### 4.1 Primitives (`shared/rules` commonMain; n ≥ 0, d > 0; d = 0 gives null and prints "—")

| function | value | use |
|---|---|---|
| `Percent.whole(n,d)` | (200·n + d) div (2·d) | Login/Bikroy bars, Target card (then `min(·,100)`), ticket % |
| `Percent.tenths(n,d)` | (2000·n + d) div (2·d) | dashboard rings and cards, CPR, Geo |
| `Percent.bp(n,d)` | (20000·n + d) div (2·d) | details table (2 dp), channel pie (2 dp), server `*_bp` |
| `Percent.text(v, decimals, locale)` | v / 10^decimals with exactly `decimals` digits + "%" | all |
| `Qty.text(n, d=1, locale)` | t = (20·n + d) div (2·d) tenths; if t < 10000 → "t/10.t%10"; else k = (2·n + 100·d) div (200·d) tenths of K → "<grouped k/10>.<k%10> K" | quantities and targets |
| `Targets.tillDate(T,e,n)` | (2·T·e + n) div (2·n), e clamped 0..n | item till-date |
| `Targets.remaining(T,A)` | max(T − A, 0) | details |
| `Targets.band(p, [lo,hi])` | p < lo red; p < hi amber; else green | bars |
| `WorkingDays.month(ym, weekend, off, makeup)` / `.elapsed(D, …)` | days of month − weekend days − off days + make-up days; elapsed counts [1st..D] | D-TSO-MAN-08 |
| `TicketMath.ageText(nowMs, capturedMs, locale)` | per D-TSO-MAN-25 | tickets |

Long arithmetic everywhere; the bound is 2000·A·W with A ≤ 10^10 and W ≤ 31, which fits in a Long. No Double appears
in these paths (tools/ci/money-float-scan stays green). The corpus generator `tools/ci/kpi-format-corpus.py` writes
`contract/fixtures/kpi-format-corpus.json` (300 seeded cases plus every fixture below, en and bn). It is an
independent Python-integer oracle like money-format-corpus.py; `KpiFormatCorpusTest` (shared/rules) and
`web/tests/kpi-format-corpus.test.ts` read it.

### 4.2 Dashboard cards (server sends raw integers; the phone formats; the web prints from the same integers)

| card | numbers | display | fixtures (screenshot 6/7 or synthetic) |
|---|---|---|---|
| Sales (per category) | A = Σ sold_qty_base (sale lines); T = monthly_target_qty; W = working_days | Total Sales `Qty.text(A)`, Total Target `Qty.text(T, W)`, ring `Percent.tenths(A·W, T)` uncapped | Cigarette A=40,T=42000,W=25 → "40.0", "1.7 K", "2.4%". Bidi A=2991,T=26000,W=25 → "3.0 K", "1.0 K", "287.6%". Lighter A=0,T=100,W=25 → "0.0", "4.0", "0.0%". Match T=175 → "7.0". T null → "—" |
| Geo Fencing | completed_geo_valid_calls / completed_calls (national/geo-fencing) | tenths | 4/4 → "100.0%" |
| By Channel STD | cigarette sold sticks by channel / total (bp) | pie label `<bp 2dp>% <Channel> Channel` | one channel → "100.00% Astha Channel" |
| CPR | successful_calls / target_outlets | figures + ring tenths | 3/119 → "2.5%"; 29/1186 → "2.4%" (never 2.5) |
| Brand CPR | outlets_bought / denominator (cfg) | line, y = whole % | 2/3 → 67%, 1/3 → 33% |
| Login Status | successful_login / target_routes | whole | 2/5 → "40%" |
| Bikroy Joma | submitted / target_routes | whole; captions "Login Count" = successful_login, "Total Bikroy Joma" = submitted | 1/5 → "20%" |
| Segment | value_mtk / total_value_mtk; qty / family total | two lines, bp → whole on the axis | 750/1000 mtk → 7500 bp |
| Working days | WorkingDays | — | Oct 2026: 31 − 5 Fridays = 26; global holiday 2026-10-21 → 25; plus zone holiday 2026-10-12 → zone 24, territory 25; Friday 2026-10-30 declared selling → 26 |
| K boundary | Qty.text | — | 999 → "999.0"; 19999/20 → "1.0 K"; 1234567 → "1,234.6 K"; bn 40 → "৪০.০" |

### 4.3 Target Status (screenshots 39–42; D = 2026-10-25, e = 25, n = 31, calendar basis)

| figure | formula | fixtures |
|---|---|---|
| monthly T (card) | Σ item `qty_base` of route_monthly_target in reach, month(D) | Cigarette 40999 = 1000+1000+10000+10000+5000+6000+2999+5000 |
| achieved A (month to date) | Σ dw.agg_daily_route_sku.sold_qty_base for business_date in [1st..D] | Cigarette 1030 = 350+330+20+200+30+30+40+30 |
| till-date item | Targets.tillDate | 1000→806, 10000→8065, 5000→4032, 6000→4839, 2999→2419, 100→81, 170→137, 11000→8871 |
| till-date card | Σ item till-date | 806+806+8065+8065+4032+4839+2419+4032 = 33064 |
| card % | min(100, Percent.whole(A, T')) | monthly 1030/40999 → 3, 50/11000 → 0, 85/100 → 85, 72/170 → 42; till 1030/33064 → 3, 50/8871 → 1, 85/81 → 100, 72/137 → 53 |
| bar colour | Targets.band(card %, [40,80]) | 0, 1, 3 red; 42, 53 amber; 85, 100 green |
| details % | Percent.bp(A, T') as 2 dp, uncapped | 350/1000 → 35.00%; 20/10000 → 0.20%; 40/2999 → 1.33%; 350/806 → 43.42%; 20/8065 → 0.25%; 85/81 → 104.94% |
| remaining | Targets.remaining(T', A) | 1000−350 → 650; 2999−40 → 2959; 81−85 → 0 |
| working basis (switch check) | e = 20, n = 25 (holiday 21st) | 40999 → 32799; without holiday 21/26 → 33114 (Σ item rows 808+808+8077+8077+4038+4846+2422+4038; 33115 = tillDate(40999) is never a card figure, R-F-01) |

### 4.4 Tickets

| figure | formula | fixtures |
|---|---|---|
| Total / New / In Progress / Resolved | counts by status (own tickets) | 723 / 0 / 0 / 18 |
| Resolved % | Percent.whole(resolved, total) | 18/723 → "2%" |
| Open % | Percent.whole(new + in_progress, total) | 0/723 → "0%"; total 0 → "0%" |
| age | D-TSO-MAN-25 | 4 d → "4d ago"; 23 h 59 m → "23h ago"; 59 s → "just now" |

## 5. Lanes

Hot-file rules for every lane:
- Only the owner edits a file. "Append" means lines at the end of the named block, with a `// tso-manual <lane>`
  comment on the first one, no reformatting and no reordering.
- Android strings: each lane has `values/strings_<lane>.xml` and `values-bn/strings_<lane>.xml`. Existing keys may be
  edited only under the lane's prefixes: A1 `tso_dash_ tso_drawer_ tso_chrome_ tso_unit_ tso_cat_ tso_role_
  tso_offline_ tso_date_ tso_as_ tso_cp_`; A3 `tso_mc_ tso_leave_ tso_fs_ tso_fb_ tso_pe_`. A2 uses
  `feature-tickets/src/main/res`.
- docs/08 has section owners: A1 the header, Dashboard, Menu, Target Status, Settings and Log Out sections; A2
  appends a "Tickets" section; A3 Leave, Final Submit, My Periphery, My Call and My Feedback.
  `docs/status/android-tso.csv` and `docs/25-build-backlog.*` take appended rows only.
- `Wiring.kt`: B1, B2 and B3 append one line per route group to the routes block and one line per handler to
  `recordHandlers`. `Main.kt`: B1 and B3 add one job line each. `AdminModules.kt`: B1 adds one line.
  `DomainEventProducer` types, `scope-cases.txt`, `scope-gaps-baseline.txt` and the route inventory are append-only.
- `settings.gradle.kts`: A2 adds exactly `include(":android:feature-tickets")` after the feature-tso line.
- A1's first commit `a1-stubs` (TsoScreen entries, `TsoNav`, empty host files, kit signatures, area flows moved out of
  TsoApp.kt and MainActivity.kt with unchanged behaviour) lands before A2 and A3 merge anything. Target: A1's first
  two hours.

### F1 foundation-contract
- **Items:** §3 entirely, D-TSO-MAN-43. Contract parts of TK-02..06/09/10/15, SH-08, DASH-09/11/12/13,
  T-03/04/09/10, FS-06/14, LV-19, FB-09/12, PER-17.
- **Owns:** contract/openapi.yaml, contract/slices/**, shared/contract/src/** (WireDtos.kt generated, ContractEnums.kt,
  ContractCatalogues.kt), shared/contract/tools/gen_wire_dtos.py (GROUPS), web/src/contract/openapi.d.ts,
  docs/requests/contract-tso-manual-1.10.md.
- **Tests:** ContractDriftTest and WireDtosDriftTest green; one valid and one invalid example per new schema in
  contract/fixtures if that folder holds schema examples.
- **Verify:** the §3.4 steps, `./gradlew :shared:contract:jvmTest`, and redocly lint with contract/redocly.yaml (as CI).

### F2 foundation-db
- **Items:** §2 entirely. TK-02/03/05/20/21, SH-08, FS-11/13, SP-03 (column and config), VQ-01, FB-03, DP-01..05,
  LV-21/22, PER-07, T-05, DASH-04 (dw view comment).
- **Owns:** db/migrations/V0130..V0149, db/src/test/kotlin/com/aktcl/aron/db/TsoManualV0130Test.kt (new),
  docs/data-dictionary.md, docs/data-events.md, docs/dw-view-contract.md (regenerated), db/schema.sql if the repo keeps
  it in sync.
- **Tests:** TsoManualV0130Test: ticket_no format and 6-digit padding; uniqueness under 50 concurrent inserts;
  immutability triggers; state CHECKs; event child origin; 31 code_list items with attrs; feedback category present;
  final_submit job CHECK; sync code CHECKs accept the new codes. Existing DataDictionaryTest, DataEventsTest,
  DataOriginTest, DwViewContractTest, CodeListsTest, DbRolesTest and RetentionPolicyTest stay green.
- **Verify:** `./gradlew :db:test`, `tools/ci/migrations-check.sh <base>` (squawk), `tools/data-dictionary/render.sh`
  (needs ARON_TEST_PG_URL).

### F3 foundation-rules
- **Items:** §4.1 primitives. DASH-14, T-05/06/07/09, T-12 (pure part), TK-07, ticket age; the corpus.
- **Owns:** new files Percent.kt, Qty.kt, Targets.kt, WorkingDays.kt, TicketMath.kt under
  shared/rules/src/commonMain/kotlin/com/aktcl/aron/rules/ (Formats.kt may only gain one-line delegates); tests
  PercentTest, QtyTest, TargetsTest, WorkingDaysTest, TicketMathTest, KpiFormatCorpusTest under shared/rules/src/commonTest/...;
  tools/ci/kpi-format-corpus.py and contract/fixtures/kpi-format-corpus.json.
- **Tests:** every fixture in §4.2–4.4, the double-rounding cases (29/1186), the K boundary, Bengali digits, d = 0 →
  null, Long bounds.
- **Verify:** `./gradlew :shared:rules:allTests` (or jvmTest) and `python3 tools/ci/kpi-format-corpus.py --check`.

### B1 backend-tickets (+ notifications inbox)
- **Items:** TK-02, 05, 06, 09, 10, 14 (desk read of photos), 15, 17, 18, 20, 21; SH-08 inbox; D-TSO-MAN-17..26;
  Me.support_desk.
- **Owns:** backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/SupportTicketApi.kt,
  SupportTicketHandler.kt, SupportTicketService.kt (new); backend/platform/src/main/kotlin/com/aktcl/aron/backend/platform/Inbox.kt
  (new); backend/notify/src/main/kotlin/com/aktcl/aron/backend/notify/InboxApi.kt (new) and Push.kt (reasons); tests
  SupportTicketTest.kt, SupportTicketSyncFuzzTest.kt, InboxTest.kt (new).
- **Appends:** Wiring.kt (supportTicketRoutes, inboxRoutes, SupportTicketHandler), Main.kt (InboxPruneJob),
  AdminModules.kt (`/v1/support/tickets/**` → module "support"), backend/sync TypeRules.kt (2 TypeRules:
  support_ticket; support_ticket_event with parent ticket_uuid), PayloadShapes.kt (2), DomainEventProducer (3 types),
  the Me builder (one field), media read-url authz (desk may read `support_ticket*` refs), and entries in
  ScopeLeakAcrossEndpointsTest, ContractConformanceTest, AuthzAuditTest and RouteInventoryTest.
- **Fixed API for other lanes** (B1's first commit): `com.aktcl.aron.backend.platform.Inbox.add(h: Handle, item:
  InboxItem)`. InboxItem has userId, kind, refType, refId, titleEn, titleBn, bodyEn, bodyBn and dedupeKey. It is an
  INSERT … ON CONFLICT (user_id, dedupe_key) DO NOTHING (R-F-04) in the caller's transaction; after commit the caller nudges with reason
  `notification`.
- **Rules:**
  - Handler check order: replay first (a stored UUID answers as stored); requester role ∈ cfg (else
    `scope_out_of_reach`); issue type active on business_date (else `issue_type_unknown`); for an event: ticket exists,
    owned by the token user, state allows the kind (else `ticket_state_invalid`).
  - afterStored writes the state row (priority from attrs), the `created` event and the domain event in the same
    transaction.
  - Desk PATCH checks `expected_version`, applies the D-TSO-MAN-20 matrix, writes an event row, inbox and push after
    commit (when cfg.notify.ticket_push_enabled), and audits via AuditWriter. List sort: created_at desc, id desc; `q`
    matches a ticket_no prefix or ILIKE on title or issue label.
- **Tests:** replay same → duplicate, same UUID with another payload → quarantined payload_conflict; number format and
  uniqueness; reporter sees own only (another reporter → 404), desk sees all; full transition matrix incl. refused
  pairs; comment after resolved refused on both paths; close from new refused; close by a non-requester refused;
  summary counts and bp; out-of-order event before ticket → parent held, then accepted; inbox: idempotent producers,
  unread count, read up_to_id idempotent, own rows only, prune.
- **Verify:** `./gradlew :backend:masterdata:test :backend:notify:test :backend:app:test`.

### B2 backend-analytics
- **Items:** DASH-03 (backend), 04, 08 (backend), 09, 11, 12, 13 (backend), 17 (parity test); T-03, 04, 08, 09, 10,
  11 (backend), 12; CR-6, CR-7; D-TSO-MAN-05..12 and 39; the final-submit % unification of D-TSO-MAN-30; the
  DailyTrackingApi till-date %.
- **Owns:** backend/analytics/src/main/kotlin/com/aktcl/aron/backend/analytics/NationalDashboard.kt, NationalDto.kt,
  NationalDashboardApi.kt, DashboardApi.kt, Aggregator.kt, DashboardSummaries.kt, DailyTrackingApi.kt,
  TargetAchievementApi.kt (new), NationalMaps.kt (WorkingDays call only); backend/platform/.../WorkingCalendarRepo.kt
  (new: loads path holidays and the weekend at the node, calls WorkingDays); backend/masterdata/.../AdminCalendar.kt
  (WorkingDays call only); docs/10-kpis-and-programs.md (line 26); tests (new) TsoDashboardParityTest,
  WorkingDaysScopeTest, WorkingDaysViewParityTest, NationalExactPctTest, BrandCprTest, SegmentShareTest,
  LoginSubmitUsersTest, TargetAchievementTest, DailyTrackingTillDateTest, FinalSubmitPctUnifiedTest.
- **Appends:** Wiring.kt (targetAchievementRoutes), AuthzAuditTest (flip `/v1/dashboards/targets` from 404),
  scope-cases.txt.
- **Rules:** national SQL accepts route-keyed reach (a route-only TSO); every TSO-used national op answers 200 for role
  TSO (filter-options included); remove `DashboardSummaries.autoSubmit`, so final-submit figures read live
  `app.final_submit` rows.
- **Tests:** parity fixture day (AMO, manual and dummy routes, free and DRP lines, abandoned visits, an excused route, a
  zone-scoped holiday): summary == national for sales, CPR, login and geo; Not Logged In distinct routes = target −
  successful_login; Not Uploaded distinct routes = successful_login − submitted. Every §4.2 and §4.3 fixture end to end
  through SQL. Basis switch via cfg; children = every zone in reach; a client node outside reach → 403.
- **Verify:** `./gradlew :backend:analytics:test :backend:app:test`.

### B3 backend-day (+ leave, feedback, my call, periphery server gaps)
- **Items:** FS-06, 07, 11, 13, 14 (backend), CR-3, CR-5 (server part), D-TSO-MAN-27..32; LV-16 (ack reason), 17, 19
  (backend), 21, 22 (event); FB-09 (backend), FB-11, FB-12 (backend), DP-03, DP-04; SP-03 (backend), XA-01, XA-02,
  XA-03, MC-T-01; PER-08, PER-11, PER-17 (backend); bundle TSO code lists + `ticket_issue_type`,
  `ticket_resolution_code` (on behalf of B1).
- **Owns:** backend/sync/src/main/kotlin/com/aktcl/aron/backend/sync/DayApi.kt, DayStates.kt, IngestService.kt,
  BundleService.kt, NearbyApi.kt, Tasks.kt, AutoFinalSubmitJob.kt (new), VisitPlanRecords.kt (new);
  backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/LeaveApi.kt, LeaveApplicationHandler.kt (new),
  FeedbackApi.kt, FeedbackChecks.kt (new), VisitPlansApi.kt; backend/analytics/.../TeamApi.kt, ReportHandlersA.kt,
  ReportHandlersB.kt; tests DayApiTest.kt, DayCloseRaceTest.kt, NearbyOutletsTest.kt and new LateAfterFinalTest,
  AutoFinalSubmitJobTest, LeaveHandlerTest, FeedbackIngestTest, VisitPlanRecordsTest, TsoMyCallEndToEndTest,
  TsoSyncFaultFuzzTest, FinalSubmitCascadeReadTest.
- **Appends:** Wiring.kt (LeaveApplicationHandler, FeedbackChecks handler, VisitPlanRecords), Main.kt
  (AutoFinalSubmitJob), DomainEventProducer (3 types), report registry (tso-visit-log).
- **Rules:**
  - Late-after-final per D-TSO-MAN-31 inside IngestService. Accepting the quarantine (existing acceptQuarantined) counts
    the row and increments the late counters. Push `day_final_submitted` after the submit commit, to SR and AMO devices
    with a route-day in the zone.
  - The auto job uses the manual submit's locked code path; inbox and push go to the TSOs of the territory. Leave
    decision: inbox row `leave_decided` and event `leave.decided`.
  - Visit plan handler checks plan_date ≥ business_date, uploader role TSO and outlet on the envelope route; loadPlans
    uses `coalesce(p.route_id, min outlet route)`. Bundle TaskDto carries route_id. Nearby bbox uses
    `r / (EARTH_RADIUS_M·π/180)` with a 1% margin. TeamApi `mock_fixes_today` = count of today's mock fixes per user.
  - Report `tso-visit-log`: plan_date, TSO, zone, route, outlet, status, completed_at, both answers by key,
    delegate_task, task and status. Task planner gains assigned_by, comment, route and label columns.
- **Tests:** warn-not-block; upload_state with web entry and dummy routes; submit_seq, replay of a reopened UUID → 409;
  reopen audited, idempotent, role and window enforced; job: midnight, restart catch-up, idempotent, skips submitted
  zones, inbox rows; late cases: before lock → quarantined then accepted and counted, after lock → rejected, memo_void
  after → rejected, day_submit after → accepted; leave on both paths with the same refusals; TSO → SR task end to end
  through /v1/sync and the bundle; fuzz over all TSO record types (duplicates, out-of-order, partial batches; device
  count == server count); nearby at 0.9995·r north and east.
- **Verify:** `./gradlew :backend:sync:test :backend:masterdata:test :backend:analytics:test :backend:app:test`.

### W1 web
- **Items:**
  - TK-14 (web), TK-16 (support console). FS-14 (reopen form), FS-18 (Submit button only for roles the API accepts).
  - DASH-13 (drill columns User Name, Dep Name, role from login-submit/users). LV-19 (applicant name, username and
    territory, type label, optional reject note).
  - FB-03 (mock seed), FB-12 (category label, username + zone, photo thumbnail via media read-url, date filter,
    SUPPORT status action aligned with the API). XA-02, XA-03 (tso-visit-log catalogue entry and labels; task planner
    columns).
  - Quarantine page: label and help for `after_final_submit` ("Accept as late sale"). Web percentages from integers
    through the corpus (fmt.ts; pctBp from bp only where the figure is already bp at the target precision).
    tso-daily-tracking shows the till-date %.
- **Owns:** web/src/app/admin/tickets/page.tsx and [ticket_uuid]/page.tsx (new); web/src/components/admin/tickets/*
  (new: list, thread, reply, resolve form, cancel form); web/src/lib/admin/ops.ts (ticket.update, ticket.event,
  day.reopen), web/src/app/admin/menu.ts, web/src/lib/admin/modules.ts; web/src/app/(dashboards)/drill/[kind]/page.tsx,
  (dashboards)/leave/page.tsx, (dashboards)/tso-daily-tracking/page.tsx; web/src/app/admin/_entities/feedback.ts,
  web/src/components/admin/config/day-control-view.tsx, final-submit-page.tsx, quarantine-page.tsx;
  web/src/lib/national/fmt.ts, web/src/lib/reports/catalog.ts, web/src/lib/i18n/messages-*.ts (keys under `tickets.`,
  `tsoman.`), web/src/contract/types.ts, web/mock/**; tests (new) web/tests/checker-tickets.test.tsx,
  kpi-format-corpus.test.ts, day-reopen-form.test.tsx, final-submit-roles.test.tsx, drill-columns.test.tsx,
  leave-applicant.test.tsx.
- **Rules:** the "Tickets" menu entry shows when `Me.support_desk`. Resolve needs code and notes; cancel needs a
  reason; every action sends `op_uuid` and `expected_version`. The thread shows "Support" for desk rows. bn and en
  labels for every string.
- **Verify:** `cd web && npm run lint && npm run typecheck && npm test`.

### A1 android-shell-dashboard-targets
- **Items:** SH-04, 06, 07, 08 (app), 09, 12 (drawer part), 13–20, SH-01 (release name), CR-1, CR-2 (docs);
  DASH-01..14, DASH-17 (app); T-01, 02, 04 (app), 06, 08, 10, 11 (app); D-TSO-MAN-07 (docs/08), 10, 13–16, 26 (app),
  37, 40–42.
- **Owns:**
  - android/core-ui/** (Tokens.kt `AronLook.TsoNight`, AronTheme.kt, Chrome.kt TSO top bar, new `aron/AronDropdown.kt`,
    `aron/HomeFab.kt`, TokenContrastTest); android/feature-auth/** (TSO login variant);
    android/feature-home/.../RoleHome.kt (retire TSO tiles).
  - feature-tso: TsoApp.kt, TsoNav.kt (new), TsoChrome.kt (new: drawer, top bar, bell, logout dialog), TsoKit.kt,
    TsoLoginTheme.kt, TsoHome.kt (deleted), TsoDashboard.kt, TsoDashboardScreens.kt, TsoSettings.kt, password/*,
    targets/* (new), notifications/* (new).
  - app-tso: MainActivity.kt, TsoApi.kt, TsoDashboardHost.kt, TsoTargetsHost.kt (new), TsoInboxHost.kt (new).
  - android/core-sync/.../push/PushMessage.kt and PushNotices.kt (reasons `notification`, `ticket_updated`,
    `day_final_submitted`; fixed bn/en text, no business data); android/core-system/.../logout/* (SH-17).
  - android/ui-screenshots/.../TsoScreenshotTests.kt, TsoLargeTextScreenshotTests.kt; tools/ci/release-apks.py,
    .github/workflows/release-app.yml (one copy step); docs/ui-reference/PARITY-SPEC.md (TSO lines);
    docs/evidence/manuals/delta-tso.md (header and shell section).
- **Stub commit `a1-stubs`:**
  - `enum class TsoScreen { DASHBOARD, LOGIN_SUBMIT_STATUS, NOTIFICATIONS, SETTINGS, CHANGE_PASSWORD, SUPPORT_UPLOAD,
    TICKET_DASHBOARD, TICKET_LIST, TICKET_QUICK, TICKET_CREATE, TICKET_DETAIL, LEAVE, FINAL_SUBMIT, MY_TEAM, RETAILER,
    MY_VISIT_PLAN, SET_PLAN, TARGET_STATUS, TARGET_DETAILS, FEEDBACK_NEW, FEEDBACK_LIST }`
  - `interface TsoNav { fun open(screen: TsoScreen, arg: String? = null); fun back(); fun home() }`;
    `typealias TsoScreenContent = @Composable (nav: TsoNav, arg: String?) -> Unit`;
    `TsoApp(…, screens: Map<TsoScreen, TsoScreenContent>)`.
  - Empty hosts `app-tso/.../TicketsHost.kt` (`fun ticketScreens(deps: TicketsHostDeps): Map<TsoScreen,
    TsoScreenContent> = emptyMap()`, owned by A2) and `AreasHost.kt` (`fun areaScreens(deps: AreasHostDeps)`, owned by
    A3). MyCallFlow, LeaveFlow, FeedbackFlow, FinalSubmit and Periphery wiring move out of TsoApp.kt and
    MainActivity.kt into feature-tso subpackages and AreasHost.kt, behaviour unchanged.
  - Kit signatures: `TsoFrame(title, nav, homeButton: Boolean, bottomBar: @Composable (() -> Unit)?)`,
    `TsoDropdownField`, `TsoGlowField`, `TsoGradientButton(style = Lime|Blue|Cyan)`, `TsoCard`, `TsoSegmentedTabs`,
    `TsoPillTabs`, `TsoStatusPill`.
- **Rules:**
  - Every card reads `/v1/dashboards/national/*` (sales, geo-fencing, channel-std, channel-calls, cpr, brand-cpr,
    login-submit, segment-share, login-submit/users). TsoApi gains a POST helper with a per-card cache key and drops
    `summary`. Reads happen on open and pull-to-refresh only, never on a timer, with an "as of" line from the server
    stamp.
  - Formatting only through F3. Tokens are sampled from the JPGs (approximate start: page #040C3A, card
    #3A1F6B→#24204F with a #7B3FE4 glow, figure #2ED3C6, title #6EE7A0→#E8E35A, badge #E53935, FAB #2F80ED); contrast
    is computed, 7:1 for key figures. Home FAB per the §8 column.
  - Target Status reads `getTargetAchievement?month&product_level=category&children=zone`; Details reads
    `level&node_id&product_level=variant`; each answer is cached per month.
- **Tests:** TsoShellTest (drawer order, labels and subtitles from screenshots 9, 10, 27, 30; Dashboard as root; back →
  Dashboard; the FAB table; gear → Settings); TsoDashboardCardsTest (every §4.2 fixture from JSON fixtures);
  TargetStatusTest (every §4.3 fixture, tab state, category filter); TsoInboxTest (unread minus pending reads, mark
  read, offline cache); LogoutDialogTest; TsoApiTest (no `/v1/dashboards/summary` call); TokenContrastTest(TsoNight);
  goldens bn/en/200% for screenshots 5–10, 39–42, 44 and the inbox; `tools/ci/test_gates.py` case for the release
  name.
- **Verify:** `./gradlew :android:feature-tso:testDebugUnitTest :android:core-ui:testDebugUnitTest
  :android:feature-auth:testDebugUnitTest :android:app-tso:testDebugUnitTest :android:ui-screenshots:testDebugUnitTest`.
  Goldens are recorded with `-Proborazzi.record=true` and then looked at.

### A2 android-tickets
- **Items:** TK-01 (screens; the drawer is A1), TK-02..14, TK-19 (module shape), TK-22; CR-8; D-TSO-MAN-17..25.
- **Owns:**
  - android/feature-tickets/** (new, role-agnostic): host ports `TicketSource` (list, summary, detail, cached),
    `TicketQueue` (support_ticket and support_ticket_event through the host outbox), `TicketPhotos`, `now`; screens
    TicketDashboardScreen, TicketListScreen, TicketDetailScreen, QuickTicketScreen (with the sheet),
    CreateTicketScreen; states, TicketModel, strings en and bn.
  - app-tso: TicketsHost.kt (after a1-stubs), TicketApi.kt (new, own cache like TsoApi), the build.gradle.kts
    dependency line.
  - core-media: android/core-media/.../GalleryImport.kt (new) and `MediaComponents.importFromGallery(mediaUuid:
    String, uri: Uri, ref: MediaRef): MediaItem` (append). This is A2's first commit, because A3 uses it.
  - settings.gradle.kts (one line). Tests: feature-tickets tests, app-tso TicketWriteReplayTest.kt (new), core-media
    GalleryImportTest.kt (new), android/ui-screenshots/.../TicketScreenshotTests.kt (new).
- **Rules:** one UUID per form, kept across recreation; a double tap is a no-op. Quick: title = issue label_en,
  description optional. Create: title and description required. Unsent rows show "Not sent yet" instead of the INC
  number (mirror key `tso.unsent.ticket.<uuid>`). Local search (number, title, issue label). Tabs All, New, In
  Progress, Resolved, Closed (All also shows Cancelled). Recent tickets: 10. Times in Asia/Dhaka, 12-hour clock. The
  composer shows only for new and in_progress; a server refusal shows its reason. Push `ticket_updated` deep-links to
  TICKET_DASHBOARD.
- **Tests:** TicketModelTest (validation, counts); TicketFlowTest (offline quick and manual create, double tap, kill
  mid-save, offline close and comment, replay after reconnect, device count == server count with a fake server);
  TicketListStateTest (search, tabs, unsent merge); GalleryImportTest (compressed to ≤ cap, EXIF stripped, no storage
  permission); goldens bn/en/200% for screenshots 10–18.
- **Verify:** `./gradlew :android:feature-tickets:testDebugUnitTest :android:core-media:testDebugUnitTest
  :android:app-tso:testDebugUnitTest :android:ui-screenshots:testDebugUnitTest`.

### A3 android-areas
- **Items:** LV-02..07, 09..12, 14, 16, 23, 24; FS-02, 03, 05, 06, 07, 08, 09, 12, 19; CR-3, CR-4, CR-5; PER-02..07,
  09, 10, 12, 13, 14, 18; MC-01, SP-01..03 (app), VP-01..03, VQ-02, AT-01, AT-02, XA-01 (SR card); FB-01..10, FB-13;
  D-TSO-MAN-27, 28 (app), 31 (SR and AMO lock), 32..36.
- **Owns:**
  - android/feature-tso/.../leave/**, finalsubmit/**, periphery/**, mycall/**, feedback/** (including the flows moved by
    a1-stubs); app-tso TsoReadModels.kt, TsoBundleReads.kt, AreasHost.kt (after a1-stubs).
  - android/core-map/** (interactive mode opt-in, MapPoint kind, radius circle, localized sources).
  - android/core-database/.../repo/RouteDayLock.kt and its test; android/app-sr/.../SrDay.kt (lock banner, ack display);
    android/app-amo/.../AmoDay.kt (lock banner); android/feature-tasks/.../TaskScreen.kt (label, not code).
  - android/ui-screenshots/.../TsoAreaScreenshotTests.kt (new). A3 moves its area cases out of TsoScreenshotTests.kt in
    its first commit; that is the only edit A3 makes there.
- **Rules:**
  - Cascade from filter-options, single options preselected. Final submit UUID per D-TSO-MAN-28; on 409 REOPENED,
    re-preview. Leave: ascending order and the card layout.
  - Offline nearby from the bundle (active, location_confirmed, route set, zone, `Geo.haversineM ≤ r`, nearest first,
    ties by id). Radius from cfg, falling back to [50,100,300].
  - My Call: system back == on-screen back (CR-4); Set Plan date defaults to today; days ahead from
    `cfg.tso.plan_max_days_ahead`. Feedback gallery uses A2's GalleryImport.
  - SR and AMO lock: day_state final_submitted (bundle or delta, refreshed on push `day_final_submitted`) closes
    capture for the route-day; printing stays allowed.
- **Tests:** LeaveTest; FinalSubmitTest (next zone after OK, seq UUID, warn list, no confirm when nothing is due);
  FinalSubmitCascadeTest; PeripheryTest (offline nearby, rounding, grouping, stale grey-out); PlanTest and
  MyCallScreenTest (default date, back paths); FeedbackTest (categories, gallery, return after save); RouteDayLockTest
  (lock, print allowed); SR TaskScreen label test; goldens bn/en/200% for screenshots 19–38 and 43.
- **Verify:** `./gradlew :android:feature-tso:testDebugUnitTest :android:core-map:testDebugUnitTest
  :android:core-database:testDebugUnitTest :android:app-sr:testDebugUnitTest :android:app-amo:testDebugUnitTest
  :android:feature-tasks:testDebugUnitTest :android:ui-screenshots:testDebugUnitTest`.

## 6. Order and dependencies

1. **Wave F:** F1, F2 and F3 run in parallel. Each merges into its lane branch, then into the integration branch only
   when green (D-TSO-MAN-03). The lead merges F1, then F2, then F3 (they share no files).
2. **Wave B, W and A** starts from the merged F result, every lane in parallel. Inside the wave:
   - A1's `a1-stubs` comes first (about 2 hours). A2 and A3 may build module internals before it but merge only after
     it.
   - A2's GalleryImport commit lands before A3's feedback gallery.
   - B1's `Inbox.kt` commit lands before B3's leave and auto-submit producers; the signature is fixed in §5 B1, so B3
     codes against it at once.
   - W1 and the A lanes build against the contract and mock fixtures; their device-lab checks run once B1, B2 and B3
     are on dev.
3. **Merge order in the wave:** B1, B2, B3, W1, A1, A2, A3, each rebased on the previous one. The hot-file append rules
   make conflicts trivial.
4. **Asks found during the wave** go to `docs/status/tso-manual-<lane>.md`. F1 and F2 re-open for one batch (1.10.1,
   V0141+).
5. **End:** integration CI green; the signed TSO APK `aron_tso_app_<dd_mm_yyyy>_v<code>.apk` installed on a pilot
   phone; the §8 device checks done on dev (test account).

## 7. Owner report

### 7.1 Questions (each with the default the build uses)

1. **Late uploads after Final Submit.** Default: rows captured before the lock are held (quarantine) and counted only
   when admin accepts them; rows captured after the lock are refused. Alternatives: refuse all, or accept and flag
   (D-55).
2. **Ticket numbers.** Default: start at INC100001, so new numbers never collide with the vendor's INC0107xx.
   Alternative: continue the vendor's sequence.
3. **Ticket Dashboard scope.** Default: own tickets only (the 723 in screenshot 11 may be every ticket the test
   account could see). Alternative: all tickets of the TSO's area.
4. **Support desk roles.** Default: ADMIN and SUPERADMIN. Granting SUPPORT is one switch on the permission page, and
   should come with the owner's yes.
5. **Bangla labels for the 31 issue types, both feedback categories, the leave types and the task types.** Default:
   English is shown until AKTCL supplies them (V0027 rule).
6. **Logout text.** Default: "All app data on this phone will be removed." (grammar fixed). Alternative: the
   screenshot's words verbatim.

### 7.2 Conflicts and differences (decided; reported, not asked)

- Till-date basis: the manual text says working days passed; the screenshots prove calendar days (25/31, half up).
  The build uses calendar days; working_days is one cfg switch (33064 → 32799 on 2026-10-25).
- Brand CPR: the manual text gives ÷ target outlets; the screenshot gives ÷ successful calls. The build uses
  successful calls; the other is one cfg switch.
- Bikroy Joma %: manual and screenshot agree (÷ target routes); docs/08 and docs/10 were wrong and are corrected.
- Leave order: the text says newest first; screenshot 19 shows oldest first. The build follows the screenshot.
- Leave end date: the vendor shows a 2-day leave as one date (a tester complaint in screenshot 19); the build counts
  the days.
- Launcher plate stays D-BRAND-02 red (the screenshot shows a light plate). The login keeps a language toggle and the
  password eye.
- The drawer header gains a Settings gear; My Feedback gains "My feedback"; Set Plan keeps Select all.
- Final Submit: the route title has a space before the kind, and due routes get a "Not uploaded" chip.
- The TSO login has no OTP (D-AUTH-TSO-NOBIND); the enrolment and location notice screens stay.
- Logout is refused while items are unsent; the manual wipes regardless.
- Maps: My Team shows the last synced position, not live tracking (battery); the two TSO maps are interactive (amends
  D-08).

## 8. Acceptance per screenshot

FAB = the Home button is present (Y or N, per the screenshot). Proofs are unit or golden tests in CI unless "device"
is named; device checks run on a pilot phone against dev (test account).

| # | screenshot | lane | FAB | proof |
|---|---|---|---|---|
| 1 | find APK | A1 | – | CI artefact name `aron_tso_app_<dd_mm_yyyy>_v<code>.apk` + SHA256SUMS line (test_gates.py) |
| 2 | install dialog "ARON TSO" | A1 | – | AppIconTest label; device |
| 3 | installed | – | – | device |
| 4 | app icon | A1 | – | AppIconTest (D-BRAND-02 plate, difference listed) |
| 5 | login | A1 | N | golden bn/en/200%; LoginScreen TSO variant test |
| 6 | dashboard top + sales + geo | A1, B2 | Y | TsoDashboardCardsTest (§4.2 sales and geo fixtures); NationalExactPctTest; golden |
| 7 | channel, CPR, brand CPR, login/bikroy, segment | A1, B2 | Y | TsoDashboardCardsTest; BrandCprTest; SegmentShareTest; TsoDashboardParityTest; ▸ toggle test |
| 8 | Not Logged In / Not Uploaded | A1, B2 | Y | LoginSubmitUsersTest (fan-out, order, Dep Name = zone); golden |
| 9 | main menu | A1 | – | TsoShellTest drawer order and subtitles; golden |
| 10 | tickets menu | A1, A2 | – | TsoShellTest Tickets group; golden |
| 11 | ticket dashboard | A2, B1, F3 | N | TicketMathTest (18/723 → 2%); SupportTicketTest summary; golden |
| 12 | all tickets | A2, B1 | N | TicketListStateTest (tabs, search, age); golden |
| 13 | filtered Resolved | A2 | N | TicketListStateTest Resolved tab; golden |
| 14 | ticket details | A2, B1 | N | resolution message format test; comments refused after resolved (both paths); close_review only from resolved; golden |
| 15 | quick report tiles | A2, F2 | N | CodeListsTest 31 items and attrs; tile caption test; golden |
| 16 | quick ticket sheet | A2 | N | TicketFlowTest offline quick create, gallery and camera; golden |
| 17 | create ticket | A2 | N | TicketFlowTest validation; golden |
| 18 | issue types list | A2, F2 | N | 31 labels verbatim in Annex A order |
| 19 | leave list | A3 | N | LeaveTest order ascending, card fields; golden |
| 20 | leave form | A3 | N | form empty states; golden |
| 21 | leave type | A3, B3 | N | types from bundle; LeaveHandlerTest |
| 22 | completed form | A3 | N | to_date = from + days − 1; toast and back; golden |
| 23 | select zone | A3, B3 | N | FinalSubmitCascadeTest; FinalSubmitCascadeReadTest; golden |
| 24 | get sales data | A3 | N | button enabled only after 5 levels |
| 25 | routes | A3, B3 | N | DayApiTest upload_state and warn list; card title and FF line test; golden |
| 26 | success | A3, B3 | N | FinalSubmitTest next zone after OK; submit_seq / 409 REOPENED; LateAfterFinalTest; AutoFinalSubmitJobTest; RouteDayLockTest; device (SR phone shows lock) |
| 27 | periphery menu | A1 | – | TsoShellTest |
| 28 | my team | A3, B3 | Y | PeripheryTest grouping, stale, mock chip; TeamApiTest; golden; device (zoom and tap) |
| 29 | retailer | A3, B3 | Y | PeripheryTest offline nearby == server rule; NearbyOutletsTest margin; golden |
| 30 | my call menu | A1 | – | TsoShellTest |
| 31 | set plan | A3 | Y | PlanTest default today, cfg days ahead; golden |
| 32 | choose route | A3 | Y | dropdown test |
| 33 | select outlets | A3, B3 | Y | VisitPlanRecordsTest route_id; golden |
| 34 | my visit plan | A3 | Y | golden |
| 35 | pending / completed | A3 | Y | no tab counts, "No Outlets Found"; golden |
| 36 | planned outlet | A3 | Y | 5-line card with channel; golden |
| 37 | visit query | A3, F2 | Y | rubric seed test; MyCallScreenTest back paths (CR-4); golden |
| 38 | assign task | A3, B3 | Y | TsoMyCallEndToEndTest (task reaches the SR bundle with route_id); SR TaskScreen label test; device (SR phone) |
| 39 | monthly target | A1, B2 | N | TargetAchievementTest and TargetStatusTest monthly fixtures; golden |
| 40 | till-date target | A1, B2, F3 | N | 33064 / 8871 / 81 / 137, 85/81 → 100% capped; golden |
| 41 | details | A1, B2 | N | 35.00%…1.33%, 43.42%, 0.25%, 104.94%, remaining 0; golden |
| 42 | details till-date | A1 | N | tab switch keeps the node; category filter; golden |
| 43 | create feedback | A3, B3 | Y | FeedbackTest categories suggestion and feedback, gallery max 1; FeedbackIngestTest; golden |
| 44 | log out | A1 | – | LogoutDialogTest (text, refusal with unsent items, wipe incl. aron_ui_<id>.xml); golden |
| – | inbox and bell | A1, B1, B3 | – | TsoInboxTest; InboxTest; leave decided and auto-submit rows |
| – | web support console | W1, B1 | – | checker-tickets.test.tsx (resolve needs code and notes, cancel needs reason, version conflict) |
| – | web reopen, final submit, drill, leave, feedback | W1, B3 | – | day-reopen-form, final-submit-roles, drill-columns, leave-applicant tests |
| – | numbers everywhere | F3, W1, B2 | – | KpiFormatCorpusTest (Kotlin) == kpi-format-corpus.test.ts (web) |
| – | data platform | F2, B1, B3 | – | DwViewContractTest, DataEventsTest; events emitted in the same transaction |

## 9. Lead rulings after the foundation wave (2026-10-29)

The F1, F2 and F3 checkers found 25 points. Each ruling below is binding for the fix round and for every later lane.
Where a ruling changes a line above, that line is amended and names the ruling.

| id | ruling | who acts |
|---|---|---|
| R-F-01 | A Target Status card figure is the sum of its item rows, rounded per item. At 21/26 the card is 33114. 33115 is tillDate(40999), which is never a card figure. | F3 tests, B2, A1 |
| R-F-02 | `SupportTicketEvent.body` is at most 2400 characters, because the longest resolution message is 2204. The `app.support_ticket_event.body` CHECK matches (V0130, unshipped). Notes, comments and replies stay 1..2000. | F1 (done), F2 |
| R-F-03 | Desk PATCH replay: the op's first event row has `event_uuid = op_uuid` and stores `op_sha256` (bytea, 32 bytes, nullable, only on that row) of the request body as canonical JSON. Same op_uuid and same hash: 200, nothing written. Same op_uuid with another hash or ticket: 409 ERR_CONFLICT. | F2 column, B1 |
| R-F-04 | Inbox rows are unique per recipient: `UNIQUE (user_id, dedupe_key)`. Producers insert `ON CONFLICT (user_id, dedupe_key) DO NOTHING`, so a zone auto-submit reaches every TSO whose reach covers the zone. | F2, B1, B3 |
| R-F-05 | A ticket from a role outside `cfg.support.ticket_requester_roles` is quarantined `scope_out_of_reach`, as the code catalogue says. When an admin accepts such a quarantined `support_ticket` or `support_ticket_event`, B1 runs the role check again and refuses the accept if the role is still not allowed. No new outcome code. | B1 |
| R-F-06 | `cfg.feedback.max_images` is bounded 0..1, because the feedback record holds one `photo_uuid`. | F2 |
| R-F-07 | The worker login gets UPDATE on `app.push_token`, so revoking an unregistered token does not stop the other pushes. It does not get INSERT on `app.audit_log`: the midnight job's trace is the `via='job'` final_submit row plus its `zone_day.final_submitted` domain event. DbRolesTest keeps the audit grant forbidden. | F2, B3 |
| R-F-08 | `support_ticket_state`: `(status = 'closed') = (closed_at IS NOT NULL)` and `status NOT IN ('resolved','closed') OR resolved_at IS NOT NULL`. | F2 |
| R-F-09 | A job Final Submit (`submitted_by` NULL) takes the most synthetic `data_origin` of the routes it closes, in the insert stamp and in `app.restamp_data_origin`. `derive_data_origin` is not changed for other tables. | F2 |
| R-F-10 | The V0139 rubric seed changes `BundleRubricsTest` expectations. F2 adjusts that test in the same branch and runs `:backend:sync:test` and `:backend:app:test` before merge. | F2 |
| R-F-11 | Admin module `support` is added in 1.10.0 (`/v1/support/tickets/**` maps to it, §5 B1). F1: contract enum and catalogue. F2: any module CHECK that lists modules. W1: GRANT_MODULES. B1: AdminModules line. The desk permission stays `support.ticket.manage`. | F1, F2, W1, B1 |
| R-F-12 | Report key `tso-visit-log` is added in 1.10.0, with its catalogue entry, the docs/24 s12.3 row and `web/src/lib/admin/report-keys.ts`, so B3 and W1 stay green. | F1 |
| R-F-13 | When working days W = 0, the daily target and the ring are null and print "—" on phone and web; the server sends `NationalSalesCard.achievement_pct_bp` null. The corpus pins a W = 0 case. | F1, F3, B2, W1 |
| R-F-14 | `SupportTicketSummary` drops `resolved_pct_bp` and `open_pct_bp` (1.10.0 is unreleased). Phone and web compute the whole percent once from the counts (TicketMath; the web corpus formatter); total 0 prints "0%". | F1, A2, W1 |
| R-F-15 | `TargetAchievement.period_days` is 0..31. When it is 0 (a working-days month with no working day), the till-date members are null. | F1, B2 |
| R-F-16 | Every uncapped `*_bp` member is `format: int64`, so the phone decodes large percentages. | F1 |
| R-F-17 | `NationalSales.working_days` and `daily_target` descriptions follow D-TSO-MAN-08: working days at the lowest common node of the scope, with the node's weekend, path holidays and make-up days. | F1 |
| R-F-18 | `children=zone` is allowed only when the node is a territory or below; otherwise 400 ERR_VALIDATION. | F1, B2 |
| R-F-19 | CI runs `tools/ci/kpi-format-corpus.py` in the Money gates step, so a hand-edited corpus cannot turn a test green. | lead (done) |
| R-F-20 | The Match fixture uses A = 0 (screenshot 6: "0.0", "7.0", "0.0%"). The null case is its own entry without a screenshot label. | F3 |
| R-F-21 | Accepted as built: the ticket number function (no lpad truncation), `data_origin` on the state table, `data_origin` before `is_effective` in views, dw grants as other views, the five db test edits, the 15 cfg keys in the SchemaV1aTest ruled set, the F1 backend/sync companion commit. | lead |
| R-F-22 | squawk and oasdiff are Linux binaries and run in CI only. No download on the laptop. | lead |
