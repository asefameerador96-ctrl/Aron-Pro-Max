# tso-manual B3 backend-day: lane status

Lane B3 of `docs/requests/tso-manual-build-2026-10.md` (s5 B3). Branch `tso-man/B3`, worktree
`.claude/worktrees/tso-B3`, base 95a35b7d (contract 1.10.0, V0130-V0140, F3 rules). No pull request. Written 2026-10-29.

The lane runs in two steps. **Step 1 (this file's "Done")**: day and Final Submit (FS-06, 07, 11, 13, 14 backend; CR-3;
CR-5 server part; D-TSO-MAN-27, 28, 29, 31, 32). **Step 2 (open)**: the midnight AutoFinalSubmitJob (D-TSO-MAN-30),
leave, feedback, My Call, periphery and bundle items (see "Next step").

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

## Asks

- **F2 (1.10.x batch, V0141+):** `app.domain_event_type.status` of `zone_day.final_submitted` and `zone_day.reopened`
  to `live` (their producer ships in B3); DataEventsTest's planned list loses them.
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

- Step 2 items (see "Next step").
- Not run for step 1: the whole `:backend:app:test` (only the classes above), `:backend:masterdata:test` and
  `:backend:analytics:test` (step 1 changes neither module's code).

## Next step

Step 2 (s5 B3, second half): `AutoFinalSubmitJob` (new, `backend/sync`) using `DayService.closeLocked` under
`zoneDayLock` with `via = 'job'`, `by = null`, kind `auto`, idempotent by a derived uuid per zone-day, catch-up of
`cfg.day.reopen_window_days`, switch `cfg.day.auto_final_submit_enabled`, inbox rows `final_submit_auto` to the TSOs whose
reach covers the zone (B1's `Inbox.add`, ON CONFLICT per recipient), push after commit; one line in `Main.kt`; tests
AutoFinalSubmitJobTest. Then leave (LeaveApplicationHandler, LV-16/17/19/21/22), feedback (FeedbackChecks, FB-09/11/12,
DP-03/04), My Call (VisitPlanRecords, SP-03, XA-01..03, MC-T-01, report `tso-visit-log`), periphery (PER-08, 11, 17:
NearbyApi margin, TeamApi `mock_fixes_today`), bundle code lists incl. `ticket_issue_type` and `ticket_resolution_code`,
and the tests TsoMyCallEndToEndTest, TsoSyncFaultFuzzTest, LeaveHandlerTest, FeedbackIngestTest, VisitPlanRecordsTest,
NearbyOutletsTest additions.
