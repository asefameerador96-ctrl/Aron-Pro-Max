# tso-manual B3 backend-day: lane status

Lane B3 of `docs/requests/tso-manual-build-2026-10.md` (s5 B3). Branch `tso-man/B3`, worktree
`.claude/worktrees/tso-B3`, base 95a35b7d (contract 1.10.0, V0130-V0140, F3 rules). No pull request. Written 2026-10-29.

The lane ran in two steps. **Step 1**: day and Final Submit (FS-06, 07, 11, 13, 14 backend; CR-3; CR-5 server part;
D-TSO-MAN-27, 28, 29, 31, 32). **Step 2**: the midnight AutoFinalSubmitJob (D-TSO-MAN-30), leave (LV-16, 17, 19, 21,
22), feedback (FB-09, 11, 12 backend; DP-03, 04), My Call (SP-03, XA-01..03, MC-T-01), periphery (PER-08, 11, 17), the
bundle's TSO code lists (with `ticket_issue_type` and `ticket_resolution_code`, on behalf of B1) and R-F-07. Both steps
are done; see "Verify" and "Not done".

## Done (step 1)

| item | where | what |
|---|---|---|
| D-TSO-MAN-27 preview | `backend/sync/.../DayApi.kt` `previewIn` | every route carries `route_class`, `visit_kind`, `ff_role` and `upload_state` (uploaded = sales submitted and not voided; web_entry = a live `app.web_entry_route_day`; not_uploaded = due; not_due); a dummy BR or Institutional route without data is never due; `warn_route_ids` = the not_uploaded routes; `warnings` keep their 1.7.0 meaning over those routes (a Web Entry route no longer warns); the submit is never blocked |
| D-TSO-MAN-28 (CR-3) | `DayApi.kt` `replay`, `checked` | `submit_seq` = reopened closes of the zone-day; a replay succeeds only while the matched close is live, else 409 `ERR_DAY_FINAL_SUBMIT_REOPENED` (context `submitted_at`, `reopened_at`); a job close (`submitted_by` NULL) replays as kind `auto` and is never matched to a caller |
| D-TSO-MAN-29 reopen | `DayApi.kt` `reopen`, route `POST /v1/day/reopen`; `DayStates.reopen` | check order body (400) -> own replay by `client_uuid` -> role in `cfg.day.reopen_roles` (403) -> reach (403) -> window `cfg.day.reopen_window_days`, not after today (409 `ERR_REQUEST_STATE`) -> live close (409 `ERR_DAY_NOT_FINAL_SUBMITTED`); locks: reopen uuid, zone-day lock, route-days in route_id order; one transaction: the close carries the reopen, route-days lose `final_submitted_at` and go back to their own state, dirty keys, audit row (entity `zone_day`), event `zone_day.reopened`; the same uuid by another user or for another zone-day is 409 `ERR_CONFLICT` (never a 500 on the unique index) |
| D-TSO-MAN-31 day lock | `IngestService.kt` step 9b `dayLock`, `DAY_LOCKED_TYPES`, `DAY_LOCKED_ROLES`, `Ctx.latePolicy` | for SR and AMO uploaders, figure records (visit, visit_close, visit_skip, sale_abort, memo, memo_line, memo_discount, qc_line, memo_void, due_collection, stock_movement, redemption, redemption_line) of a closed route-day: policy quarantine (default) holds a row captured before the lock `after_final_submit` and refuses one captured at or after it `day_final_submitted` (final); `reject` refuses both; `accept_and_flag` stores and counts as before; a reviewer's accept skips the check and the row is counted late; a child of a parent refused `day_final_submitted` is refused with it (never parked); the route-day row is created when missing and held FOR NO KEY UPDATE before it is read (KD-1 order), so a close never splits a family |
| late counters | `DayStates.afterStored` | only the figure types count late; `final_submit.late_rows` counts only while the route-day is closed |
| push after commit | `DayApi.kt` `finalSubmit`, `closeLocked`; `Wiring.kt` (one line) | after the commit, `sync_nudge` reason `day_final_submitted` to every active SR and AMO user with a route-day (assigned or acting) in the zone; never on a replay |
| event | `DayApi.kt` `emitZoneDay` | `zone_day.final_submitted` v1 in the close's transaction (zone_id, business_date, final_submit_uuid, via, submitted_by, submit_seq, route_count, not_uploaded_route_count) |
| job-ready close | `DayApi.kt` `closeLocked`, `liveClose`, `zoneDayLock` | the locked part of a close is one internal function (by = null, via = job for step 2's AutoFinalSubmitJob); `route_states` now also carry `upload_state` |
| D-TSO-MAN-32 | test only | the cascade reads filter-options (B2's endpoint); FinalSubmitCascadeReadTest proves the chain and that every offered zone previews |

Tests (backend:app):
- `DayApiTest`: the old late-row case now expects the hold (was accept and count); new
  `thePreviewNamesTheNotUploadedRoutesAndTheSubmitNeverBlocks` (upload_state with a Web Entry, a dummy BR route, an
  unplanned route, an AMO route; warn_route_ids; card fields; warn-not-block; the event; CR-5 next zone),
  `aCloseNudgesTheSrPhoneOfTheZoneAfterTheCommit`, `reopenIsAuditedIdempotentAndBoundToRoleWindowAndReach` (role, reason,
  window, not submitted, idempotent replay, uuid of another user, audit once, event, route-days reopened and dirty,
  409 REOPENED replay, submit_seq 1, the next uuid closes, reach with TSO in the roles).
- `LateAfterFinalTest` (new): before lock -> quarantined whole, resend still held, ADMIN accept -> stored and counted
  late, resend duplicate; after lock -> refused whole and final; memo_void after -> refused (memo stays active);
  day_submit after -> accepted, no late row; `reject` and `accept_and_flag` policies; after a reopen rows are ordinary,
  a second close locks again.
- `DayCloseRaceTest` (additions): reopen through the real route; held rows accepted by ADMIN after wave 1 and after the
  post-close batch; new clauses H1 (nothing counted before the accept) and H2 (held == counted), each with a seeded
  fault; R3 allows 409 REOPENED only for a reopened close's uuid after the reopen; S1 compares with the live close.
- `FinalSubmitCascadeReadTest` (new): see D-TSO-MAN-32.

## Done (step 2)

Merged first: `tso-man/B1-inbox` (8dd73088, `backend/platform/Inbox.kt`: `Inbox.add(h, InboxItem)`, ON CONFLICT (user_id,
dedupe_key) DO NOTHING) as merge 1503bfb1.

| item | where | what |
|---|---|---|
| D-TSO-MAN-30 auto Final Submit | `backend/sync/.../AutoFinalSubmitJob.kt` (new); `Main.kt` (one worker line) | at start a pass over the reopen window (`cfg.day.reopen_window_days`, default 3: the dates today-3..today-1), then one pass at every 00:00:05 Asia/Dhaka; closes every never-submitted service zone-day (a planned route-day of a normal SR route not removed by an approved day exception, the national figure's rule) through `DayService.closeLocked` under the zone-day advisory lock: `via = 'job'`, `submitted_by` NULL, client uuid `derivedV4("aron.final-submit.job:<zone>:<date>")` (replays as kind `auto`), route-days closed, `zone_day.final_submitted` in the same transaction, no `audit_log` row (R-F-07); switch `cfg.day.auto_final_submit_enabled` resolved at the zone's wing, then global; inbox rows `final_submit_auto` to every active TSO whose reach covers the zone on that date (dedupe key `final_submit_auto:<zone>:<date>:<recipient>`, R-F-04), after the commit push `notification` to those TSOs and `day_final_submitted` to the zone's SR and AMO users; each zone in its own transaction, a failure logs and the pass goes on |
| DayService | `DayApi.kt` | `ingest` nullable (only online Sales Submit needs it), so the job builds a DayService without an IngestService |
| LV-16, 17 leave rules, both paths | `backend/masterdata/.../LeaveApplicationHandler.kt` (new: `LeaveRules`, `LeaveApplicationHandler`, `LeaveDecided`, `CaptureRefusal`, `HandleConfig`); `LeaveApi.kt` POST; `Wiring.kt` (handler line) | order: applicant TSO (403 / `scope_out_of_reach`), leave type valid on the first day (400 `unknown_code` / `schema_invalid` final), first day within `cfg.leave.allow_past_days` (31) back and `cfg.leave.allow_ahead_days` (365) ahead of the day applied (400 `out_of_window` / `business_date_out_of_window` held), overlap only with `cfg.leave.block_overlap` (default false; 409 `overlap` / `content_duplicate` held); to_date = from + days - 1 calendar days; replay first on both paths; the ack's code and `message_key` `sync.outcome.<code>` carry the reason the phone shows ("Not accepted: <reason>") |
| LV-19 (backend) | `LeaveApi.kt` | `LeaveApplication.applicant_username`, `applicant_name`, `territory_name` (current territory scope, else the home zone's territory) on every read |
| LV-22, D-TSO-MAN-26/38 leave decided | `LeaveApi.kt` decide; `LeaveDecided` | in the decision's transaction: event `leave.decided` v1 (leave_uuid, user_id, status, decided_by, leave_type_code, from_date, to_date, days) and the applicant's inbox row `leave_decided` (bn and en texts, Bengali digits in Bangla, dedupe `leave_decided:<uuid>`); push `notification` after the commit; a replayed decision writes nothing; `LeaveDeps.nudger` (Wiring passes `push`) |
| FB-11, R-F-06 feedback precheck | `backend/masterdata/.../FeedbackChecks.kt` (new: `FeedbackRules`, `FeedbackChecks`); `FeedbackApi.kt` POST; `Wiring.kt` | author TSO (403 / `scope_out_of_reach`), category a `feedback_category` code valid on the business date (400 `unknown_code` / `schema_invalid`), an image only while `cfg.feedback.max_images` >= 1 (400 `not_allowed` / `schema_invalid`); replay first |
| FB-09 (backend) | `FeedbackApi.kt` | `Feedback.business_date`, `captured_at`, `username`, `zone_id` (the author's home zone) |
| SP-03 (backend) | `backend/sync/.../VisitPlanRecords.kt` (new); `VisitPlansApi.kt`; `Wiring.kt` | sync: uploader TSO (else `scope_out_of_reach`), `visit_plan.plan_date` >= the record's business date (else `business_date_out_of_window`), a `visit_plan_outlet` outlet on the envelope route or, without one, the stored plan's route (else `scope_out_of_reach`); the generic writer already stores the envelope route on `visit_plan.route_id` (V0137); POST stores `route_id`; `loadPlans` uses `coalesce(p.route_id, min outlet route)` |
| XA-01 (backend) | `BundleService.kt` | bundle `TaskDto.route_id` = the task's route, else its outlet's (as GET /v1/tasks) |
| bundle code lists | `BundleService.kt` | every contract code list (1.10.0's `ticket_issue_type` and `ticket_resolution_code` included); an item whose `attrs.roles` names roles reaches those roles only (V0027's rule; "active task_type items for TSO") |
| XA-02 task planner | `ReportHandlersA.kt` | + `label` (task_type label), `assigned_by` (creator username), `route` (route name of the task's route, else its outlet's), `comment` (description), bn labels, W1's column order |
| XA-03 `tso-visit-log` | `ReportHandlersB.kt` (new `TsoVisitLogReport`), registry line in `ReportHandlersA.kt` | one row per planned outlet whose plan date is in the period, scope by the plan's route: plan_date, tso, zone, route, outlet_code, outlet_name, status (completed/pending), completed_at (first Visit Query), `sr_visits_regularly`, `sr_prints_memo` (answers by rubric key), delegate_task, task (title of the TSO's task for that outlet on the Visit Query's day when delegated), task_status; W1's keys and labels |
| PER-08 nearby margin | `NearbyApi.kt` `bbox` | box half side `r / (EARTH_RADIUS_M * pi / 180) * 1.01`, east-west divided by cos(lat); the haversine decides |
| PER-17 | `TeamApi.kt` | `TeamLocation.mock_fixes_today`: the user's fixes of the date flagged mock in the three location sources (attendance, visit, breadcrumb); last_fix stays non-mock |
| DomainEventProducer (append) | `DomainEventProducer.kt` | `SERVICE_TYPES` (zone_day.final_submitted, zone_day.reopened, leave.decided) and `emitService` (used by `LeaveDecided`) |

Tests (backend:app, new unless noted): `TsoWorld` (harness: production wiring, push recorder, movable clock, mirrors,
phones), `AutoFinalSubmitJobTest` (5, run as the worker login `SET ROLE worker_rw`), `LeaveHandlerTest` (4),
`FeedbackIngestTest` (5), `VisitPlanRecordsTest` (4), `TsoMyCallEndToEndTest` (1), `TsoPeripheryTest` (1),
`TsoSyncFaultFuzzTest` (3), `NearbyOutletsTest` (+1: 0.9995 r north and east for 50, 100, 300).

## Decisions (D-B3-NN; the lead may move them to DECISIONS.md)

- **D-B3-01 Which rows the lock covers.** The figure types above, uploaded by SR or AMO, with an envelope route_id.
  State records (day_open, day_submit, attendance_event), reprints (print_event: printing stays allowed) and telemetry
  stay accepted; TSO records (My Call visits, plans) are not locked, so a TSO's evening My Call work after closing a zone
  is never refused. Reason: D-TSO-MAN-31 names "memo, memo edit, void, return, collection" and "state only, no
  figures"; the lock exists for the field apps (the push goes to SR and AMO).
- **D-B3-02 The lock time** is the route-day's `final_submitted_at` (set to the close's `submitted_at`; a reopen clears
  it), compared with the envelope `captured_at` (the phone's trusted time; a capture more than 600 s in the future is
  already quarantined out of window).
- **D-B3-03 Late counters count figure rows only**, under every policy (before: every stored record with a route-day,
  including a day_submit or a reprint after the close). `final_submit.late_rows` counts only while the route-day is
  closed (an accept after a reopen is an ordinary row).
- **D-B3-04 Policies.** `quarantine` (default) as D-TSO-MAN-31; `reject` refuses every late figure row
  `day_final_submitted`; `accept_and_flag` keeps the pre-1.10 behaviour (stored and counted late). An unreadable value is
  `quarantine`.
- **D-B3-05 A reviewer's accept is the admin's decision** on the late row: `acceptQuarantined` (any quarantine code)
  skips the lock, so an admin can still accept, for example, a device-gate hold of a sale after the zone closed; the
  row is counted late.
- **D-B3-06 Child of a refused parent.** A child whose parent was refused `day_final_submitted` is refused with the
  same code (as memo_no_invalid does), never parked `parent_missing`.
- **D-B3-07 upload_state.** uploaded = `sales_submitted_at IS NOT NULL AND NOT submit_voided` (a route still at
  submit_pending_rows is not uploaded); web_entry = a live (not replaced, not voided) `app.web_entry_route_day` row (a
  paper backfill of single memos is not a Web Entry); uploaded wins over web_entry. After a close, `upload_state` stays
  truthful (a route closed without upload is still not_uploaded) while `warnings` keep their state-based meaning (empty
  after a close, as before).
- **D-B3-08 Reopen details.** Audit entity `zone_day`, entity id `<zone_id>:<business_date>`, action `reopen`, before =
  the live close (uuid, submitted_at, via, submitted_by, late_rows), after = reopened_at, reopen_uuid, route_day_count;
  via `web` (`device` from a phone token). The window refusal is 409 `ERR_REQUEST_STATE` with
  `context.reopen_window_days`; a date after today is refused the same way. `cfg.day.reopen_roles` is read at global
  scope (the wing scope of the registry is not resolved here). No push on reopen (D-TSO-MAN-31 names the close only; the
  phones see the open day on their next pull).
- **D-B3-10 Paper backfill after a close.** A paper backfill keys records as the SR (role SR), so the lock applies: a
  backfilled memo of a closed zone-day is held `after_final_submit`, and because a backfill is all or nothing nothing
  is written and the web answer names the hold. Reopen the zone-day first (manual s8: "no memo can be edited after
  Final Submit").
- **D-B3-09 Push recipients**: active SR and AMO users who are the assigned or acting user of a route-day of the zone
  on the date (after the close created the missing route-days); reason `day_final_submitted`, no business data.

- **D-B3-11 The job never re-closes a reopened zone-day.** It closes only zone-days with no `final_submit` row at all.
  D-TSO-MAN-30 says "without a live submit", but a reopen is an audited ADMIN decision to let late rows in; a job that
  re-closed it would undo that decision at a moment set by the worker's passes. A reopened zone-day is closed again by
  the TSO's or a delegate's Final Submit. If the owner wants the job to re-close it, it is one predicate in
  `AutoFinalSubmitJob.openServiceZones` (ask to the lead below).
- **D-B3-12 Service zone** = a zone with a planned route-day of a normal SR route (`kind sr`, `route_class normal`) on
  the date that no approved, unvoided day exception removes: the rule of the national final-submit figure
  (`DashboardSummaries.autoSubmit`'s rule, on app tables). A worker down all day plans no route-day, so that zone-day is
  not a service zone for the job either (consistent with the national figure).
- **D-B3-13 Schedule.** One pass at start (the catch-up), then one at 00:00:05 Asia/Dhaka every day; each pass covers
  `today - window .. today - 1` (window = `cfg.day.reopen_window_days`, 0..31), so a missed midnight is caught by the
  next pass as well as by a restart. `submitted_at` is the job's clock when it closes (truthful; a catch-up close days
  later carries that day). Each zone has its own transaction; a failure is logged and the pass goes on.
- **D-B3-14 Job notices.** Recipients = active TSO users whose reach on the zone-day's date covers the zone. Texts en/bn
  with the zone name and the date (Bengali digits in Bangla); dedupe key `final_submit_auto:<zone>:<date>:<recipient>`.
  Pushes go after the commit and only for new inbox rows (a repeat pass pushes nothing).
- **D-B3-15 Leave refusal codes** (no leave code exists in the 1.10.0 catalogue): unknown type `schema_invalid` (final),
  first day outside the window `business_date_out_of_window` (held for review), overlap with `cfg.leave.block_overlap`
  `content_duplicate` (held), not a TSO `scope_out_of_reach` (held, the D-TSO-MAN-17 pattern). The ack's `message_key`
  is `sync.outcome.<code>`, the reason the phone prints after "Not accepted:" (LV-16). The window is measured from the
  day the TSO applied (the record's business date on sync, today on the POST). The POST refuses an overlap only with
  `cfg.leave.block_overlap` (default false, D-TSO-MAN-33; before, it always refused).
- **D-B3-16 One config source for both paths.** `cfg.leave.*` and `cfg.feedback.max_images` are read on the
  transaction's own handle (`HandleConfig`: the newest global `cfg_value` row in force, else the registry default), so
  an upload and a POST at the same moment judge the same value; a record handler gets no ServerConfig from
  `recordHandlers(db, clock)` either.
- **D-B3-17 Feedback.** Refusals: category `schema_invalid`, image while `cfg.feedback.max_images` is 0 `schema_invalid`,
  not a TSO `scope_out_of_reach`. `Feedback.zone_id` = the author's home zone; `username` = the author's login.
- **D-B3-18 Set Plan refusals.** Plan date before the record's business date: `business_date_out_of_window` (held);
  planned outlet not on the envelope route (or the stored plan's route when the record carries none):
  `scope_out_of_reach` (held, so an outlet moved since the bundle is never lost); not a TSO: `scope_out_of_reach`. The
  server does not enforce `cfg.tso.plan_max_days_ahead` (the phone does; a config change between capture and upload
  would otherwise refuse an honest offline plan).
- **D-B3-19 Code lists by role.** An item with `attrs.roles` reaches only those roles in every bundle (V0027's
  documented rule; today only `force_reason/manual_override` [AMO] has it, so SR and TSO bundles lose that one AMO
  item). Otherwise the lists are unchanged; `ticket_issue_type` and `ticket_resolution_code` were already sent once F1
  added them to `CodeListKey`; TsoMyCallEndToEndTest pins them in the TSO bundle.
- **D-B3-20 `tso-visit-log`.** One row per planned outlet with plan date in the period; scope by the plan's route
  (else the outlet's zone); `status` completed by the first `retailer_questionnaire` assessment of that planned outlet;
  answers by the rubric version's criterion `key` (text, else yes/no, else the score); `task` and `task_status` = the
  TSO's own task on that outlet created on the Visit Query's business date, only when `delegate_task` (the app sends no
  link from the task to the plan); W1's keys and labels (bn included).
- **D-B3-21 Task planner columns.** `label` = the `task_type` label valid on the task's date (else the newest, else the
  code); `assigned_by` = creator username; `route` = route name of the task's route, else its outlet's; `comment` = the
  task's description. W1's column order.
- **D-B3-22 `mock_fixes_today`** counts the fixes flagged mock in the same three sources the team map reads (attendance,
  visit, breadcrumb) on the business date; `last_fix` stays the newest non-mock fix.
- **D-B3-23 `leave.decided`** is written by the decision (aggregate `leave_application`, id the leave uuid,
  business_date the decision's Dhaka date, payload as docs/data-events.md v1). `LeaveApplication.territory_name` = the
  applicant's current territory scope, else the home zone's territory.
- **D-B3-24 Rubrics in the bundle (F2's note).** Not changed: `BundleContent.rubrics` (not a B3 file) sends every active
  rubric; with the V0139 seed plus an admin-created one there can be two active `retailer_questionnaire` rubrics. The
  admin flow or the owner of BundleContent should keep one active per kind (ask below).

## Deviations (files outside the lane's Owns list)

- `backend/app/src/main/kotlin/.../Wiring.kt`: the existing `dayRoutes(...)` line passes `nudger = push` (marked
  `// tso-manual B3`). The hot-file rule allows appends only; DayService is built on that line, so it is a one-line
  change in place (no new line could carry it).
- `backend/app/src/test/resources/scope-cases.txt`: appended `POST /v1/day/reopen -> case DayApiTest#reopenIs...`
  (append-only file, as the s5 header allows).
- `backend/app/src/test/kotlin/.../DataFlowTraceTest.kt` hop 12: the late sale is now held `after_final_submit`, the
  admin accepts it, then it is counted once (was accept-and-flag); at the end of hop 12 ADMIN reopens the zone-day, so
  hops 14 and 15 (memo number rule, idempotency edges on the same route-day) sell on an open day as before. Required by
  D-TSO-MAN-31 (V0134 made quarantine the default); without it hops 12, 14 and 15 fail (seen: "edge base sale
  accepted: memo = quarantined after_final_submit").
- `backend/app/src/test/kotlin/.../ContractConformanceTest.kt`: one fixture line `"POST /v1/day/reopen" to
  Fx(after = listOf("POST /v1/day/final-submit"))` (marked `// tso-manual B3`), so the newly served operation is
  called after the Final Submit it reopens and answers 2xx once (`contract-no-success-baseline.txt` may only shrink).

- Step 2: `Wiring.kt` line `leaveRoutes(LeaveDeps(...))` gains `nudger = push` in place (marked `// tso-manual B3`):
  LeaveDeps is built on that line, so no appended line could carry it (as step 1's dayRoutes line). The three handler
  lines are appends to `recordHandlers`.
- Step 2: `backend/analytics/src/test/.../SupportReportAccessTest.kt`: `assertEquals(32, ...)` -> 33 (one line, marked):
  the registry gains `tso-visit-log`, which the lane must add (s5 B3 appends "report registry").
- Step 2, masterdata tests that encoded the pre-D-TSO-MAN-33 leave rules (each change marked `tso-manual B3`):
  `CheckerBackendAdminTest.overlappingLeaveOfTheSameTsoIsRefused` and the last line of
  `ReplayFirstTest.leaveReplayAfterAnOverlapAppears` switch `cfg.leave.block_overlap` on for their overlap (a
  `cfg_value` row, closed after the assertion), because overlap is allowed by default now; `ReplayFirstTest` and
  `ReplayFirstGapsTest.anotherUsersReplayDoesNotLeak` move their fixed first days 2028-03-01 and 2028-06-01 (more than
  365 days ahead of the system clock) to today + 120 and today + 200 (`// wall-clock-ok`). The assertions are unchanged.
- Step 2, `backend/app/src/test/.../ScopeLeakAcrossEndpointsTest.kt`: the other division's leave and feedback rows are
  now seeded fixtures of its TSO `lkxtso1001` (as its task and plan rows are), because the generated
  `leave_application` and `feedback` records come from the other division's SR phone and an SR's leave or feedback is
  held `scope_out_of_reach` (D-B3-15, 17; the POST already refused an SR). Without it the class stops at setup
  ("other-division rows missing for [leave_uuid, feedback_uuid]").

## Asks

- **F2 (1.10.x batch, V0141+):** `app.domain_event_type.status` of `zone_day.final_submitted`, `zone_day.reopened` and
  (step 2) `leave.decided` to `live` (their producers ship in B3: DayService, AutoFinalSubmitJob, LeaveDecided);
  DataEventsTest's planned list loses them. Also the V0099 comment of `dw.agg_daily_zone.auto_submitted` ("app.final_submit
  is not written"): the job now writes it (F2's own note).
- **F1 / lead (1.10.x):** leave refusals ride on generic codes (D-B3-15), so the phone's "Not accepted: <reason>" reads
  "The record is malformed; please contact support." for an unknown leave type and "The same sale arrived twice; held
  for review." for a blocked overlap. Either add `leave_invalid` / `leave_overlap` (reason texts bn/en) or make the
  `content_duplicate` text neutral ("The same entry arrived twice"). `ReasonTexts.kt` is not B3's file. Also D-F1-01 ask
  5: `LeaveApplication.applicant_*`, `Feedback.business_date/captured_at/username/zone_id`,
  `TeamLocation.mock_fixes_today` are now always served and can become required.
- **Lead (D-B3-11):** confirm that the midnight job leaves a reopened zone-day open (closed again by a person); the other
  reading of "without a live submit" is one predicate in `AutoFinalSubmitJob.openServiceZones`.
- **masterdata owner (LeaveTest):** `LeaveTest` posts fixed dates 2027-03-01 .. 2027-06-24 with the system clock; with
  `cfg.leave.allow_past_days` 31 (D-TSO-MAN-33) they start failing from 2027-04 (`out_of_window`). Make them relative to
  today. Not edited here (not a B3 file).
- **BundleContent owner / admin rubric flow (D-B3-24):** keep one active rubric per kind (V0139 seed plus an
  admin-created `retailer_questionnaire` would both reach the phone).
- **A3:** leave and plan refusals arrive as the codes of D-B3-15 and D-B3-18 (message_key `sync.outcome.<code>`);
  `TaskDto.route_id` is now filled in the SR bundle; code items with `attrs.roles` reach their roles only; leave decided
  and auto Final Submit inbox rows come with push reason `notification` (B1's Inbox).
- **W1:** `tso-visit-log` and `task-planner` serve the column keys of W1's mock (`label_bn` included); `Feedback.username`
  and `zone_id`, `LeaveApplication.applicant_username`, `applicant_name`, `territory_name` are served.
- **B2:** the job now writes `app.final_submit` rows (`via = 'job'`, `submitted_by` NULL), so the unified final-submit %
  of D-TSO-MAN-30 can read live rows only.
- **masterdata owner:** `AuditRead` allowlist entry `"zone_day" to setOf("final_submit_uuid", "submitted_at", "via",
  "submitted_by", "late_rows", "reopened_at", "reopen_uuid", "route_day_count")`, so the audit page shows the reopen
  images (they are written; the reader hides unknown entities' fields).
- **A1 (PushMessage):** reason `day_final_submitted` arrives as `kind = sync_nudge` with no other data; A3's SR and AMO
  lock refreshes the day state on it.
- **W1:** `/admin/day` reopen form: 409 `ERR_REQUEST_STATE` = outside the window (`context.reopen_window_days`), 409
  `ERR_DAY_NOT_FINAL_SUBMITTED` = nothing to reopen; the quarantine page's `after_final_submit` help ("Accept as late
  sale") is W1's.
- **Lead:** docs/24 s4.9 rule 4 and docs/16 DQ-57 / docs/17 still say late rows after Final Submit are accepted and
  never rejected (D-55, D-579); D-TSO-MAN-31 supersedes them (the reject code `day_final_submitted` is in the 1.10.0
  catalogue). Only the lead edits those docs.

## Verify (step 2, 2026-10-29, through gl.sh: JDK 21, UTC, local PostgreSQL 16)

- Full Verify `gl.sh :backend:sync:test :backend:masterdata:test :backend:analytics:test :backend:app:test --continue`
  at 1677408e (all step 2 code and tests; another lane's Gradle run shared the laptop for part of it, 2 h 13 min):
  - `:backend:analytics:test` 40 classes, 252 tests, 0 failed.
  - `:backend:app:test` 98 classes, 490 tests, 2 skipped, 1 failed: `ScopeLeakAcrossEndpointsTest` stopped at setup
    ("other-division rows missing for [leave_uuid, feedback_uuid]"), caused by this lane (the harness's generated
    leave and feedback come from an SR phone, now held); fixed in a9530c280 (see Deviations) and re-run green (7 tests).
    Every new class green in this run: AutoFinalSubmitJobTest 5, LeaveHandlerTest 4, FeedbackIngestTest 5,
    VisitPlanRecordsTest 4, TsoMyCallEndToEndTest 1, TsoPeripheryTest 1, TsoSyncFaultFuzzTest 3, NearbyOutletsTest 4;
    and the step 1 classes (DayApiTest, LateAfterFinalTest, DayCloseRaceTest, FinalSubmitCascadeReadTest),
    ContractConformanceTest, DataFlowTraceTest, BundleAcceptanceTest.
  - `:backend:masterdata:test` 48 classes, 242 tests, 4 failed: `CheckerBackendAdminTest.overlappingLeaveOfTheSameTsoIsRefused`,
    `ReplayFirstTest.leaveReplayAfterAnOverlapAppears`, `ReplayFirstGapsTest.anotherUsersReplayDoesNotLeak` (they encoded
    the old leave rules: overlap always refused, no window; fixed in a9530c280, re-run green: 8 + 3 + 3 tests, LeaveTest 3)
    and `AuditWriteRedactionTest.noAuditWriteBypassesThePlatformWriter`: laptop path separator, not this lane. Its
    message lists the one writer it found, `backend\platform\...\AuditLog.kt`, against the expected
    `backend/platform/.../AuditLog.kt` (`relativeTo(root).path` gives backslashes on Windows); this lane adds no
    `INSERT INTO app.audit_log` (`git diff 95a35b7d -- backend | grep -c 'INSERT INTO app.audit_log'` = 0). CI (Linux)
    is not affected.
  - `:backend:sync:test` 7 classes, 37 tests, 1 failed: `JcsTest.payloadShapesMatchTheContractSlices` shells out to
    `python3` and the test JVM gets the Windows Store alias (exit 9009 "Python was not found"), as in step 1. Run
    directly: `python backend/sync/tools/gen_payload_shapes.py --check` exit 0, and with gl.sh's own
    `tools/bin/python3.exe` exit 0; this lane changes neither `PayloadShapes.kt` nor the contract slices.
- Re-run after the fixes, `gl.sh :backend:masterdata:test --tests *CheckerBackendAdminTest --tests *ReplayFirstTest
  --tests *ReplayFirstGapsTest --tests *AuditWriteRedactionTest --tests *LeaveTest :backend:app:test --tests
  *ScopeLeakAcrossEndpointsTest --continue`: ScopeLeakAcrossEndpointsTest 7, CheckerBackendAdminTest 8, ReplayFirstTest
  3, ReplayFirstGapsTest 3, LeaveTest 3 green; AuditWriteRedactionTest the same laptop-only failure.
- Earlier targeted runs: `:backend:masterdata:test` LeaveTest, FeedbackTest, VisitPlansTest and `:backend:analytics:test`
  TeamApiTest, ReportBatchATest, SupportReportAccessTest, ReportAreaContractTest green on the step 2 code.
- TsoSyncFaultFuzzTest report (150 schedules, 3 phones, seed 20261029, 13 s): kills_fired=40, kill-partial=10,
  parked=1273, reorder-parked=128, concurrent=85, drop=71, replay=80, truncate=61, partial=444, ride-along=289; rows=967
  (visit_plan 150, visit_plan_outlet 297, call_assessment 142, call_assessment_answer 205, task 68, leave_application
  57, feedback 48); every schedule ended with device count == server count; the seeded-fault test proves each clause
  (A1, A2, A3, B, B2, C, D, F, R, I, J) reports its fault.
- `python tools/ci/wallclock-scan.py`: none of the lane's files reported.
- Screenshot goldens: none (a backend lane writes no screens).

## Verify (step 1, 2026-10-29, through gl.sh: JDK 21, UTC, local PostgreSQL 16)

- `gl.sh --no-daemon :backend:sync:test :backend:app:test --tests DayApiTest --tests LateAfterFinalTest --tests
  DayCloseRaceTest --tests FinalSubmitCascadeReadTest --tests RouteInventoryTest --tests QuarantineAcceptTest --tests
  BatchAcceptanceTest --tests ScopeLeakAcrossEndpointsTest --tests SyncConvergenceFuzzTest --tests IngestRetryTest --tests
  CheckoutWithoutSubmitTest --tests PushTest --continue` at a92411f0: every app class green (DayApiTest 9, LateAfterFinal 4,
  DayCloseRace 3, FinalSubmitCascadeRead 1, RouteInventory 9, QuarantineAccept 6, BatchAcceptance 22, ScopeLeak 7,
  SyncConvergenceFuzz 1, IngestRetry 6, CheckoutWithoutSubmit 3, Push 2); `:backend:sync:test` 37 tests, 36 passed, 1
  failed: `JcsTest.payloadShapesMatchTheContractSlices`, which shells out to `python3` and got exit 9009 "Python was not
  found" (the Windows Store alias in the test JVM's PATH; laptop environment, also without the daemon). The same check
  run directly, `python backend/sync/tools/gen_payload_shapes.py --check`, exits 0, and this lane changes neither
  `PayloadShapes.kt` nor the contract slices.
- `:backend:app:test --tests ContractConformanceTest --tests DataFlowTraceTest --tests FinalSubmitCascadeReadTest` at
  a92411f0's content: BUILD SUCCESSFUL (6 + 16 + 1 tests).
- Earlier at fcb4a74f (same production code): DayCheckerTest 10, DayStateTest 3, ManualIngestTest 10, PaperBackfillTest
  4 green.
- DayCloseRaceTest fuzz report (30 rounds, seed 20261017): failed_rounds=0, reopen_routed=true, reopen-rounds=8,
  held-accepted=94, late_rows=331, tolerated=none.

## Not done

- `docs/24-build-spec.md`, `docs/data-events.md` and DECISIONS.md are the lead's or F2's: the D-B3-NN decisions and the
  `leave.decided` producer are recorded here only (see Asks).
- B1's InboxApi (`GET /v1/me/notifications`) is not on this branch, so the inbox rows are proven by their table rows
  (kind, ref, dedupe key, texts) and the push, not through B1's read API.
- No device or dev-environment check of the job (the worker on the test account): the proof is AutoFinalSubmitJobTest
  under the worker role on the local PostgreSQL 16.

## Next step

Step 2 is complete. For the next agent on this lane: answer the lead's rulings on the asks (D-B3-11 re-close rule,
leave refusal codes), then merge order B1, B2, B3 (s6): rebase on B1's branch (Inbox.kt is the same commit 8dd73088;
Wiring.kt and Main.kt take appended lines from both lanes, trivial conflicts) and on B2's (`DashboardSummaries.autoSubmit`
is B2's to remove; the job now writes the `app.final_submit` rows B2's unified % reads). After the rebase run the same
full Verify; CI on Linux also settles the two laptop-only failures (JcsTest python3 alias, AuditWriteRedactionTest
path separator).
