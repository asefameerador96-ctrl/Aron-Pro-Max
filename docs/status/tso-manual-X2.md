# TSO manual build, lane X2 (integration round: backend): status

Lane X2 of `docs/requests/tso-manual-build-2026-10.md` s10 (lead rulings R-W-06 server part, R-W-08, R-W-09, R-W-17;
step 2 adds R-W-03, R-W-04 and analytics). Branch `tso-man/X2`, worktree `.claude/worktrees/tso-X2`, base `tso-man/X1`
(`f4ba4501d`, contract amendments and V0141). 2026-10-29. No pull request, no push.

Step 1 of 2: platform, auth, sync, masterdata, media, notify. Step 2 of 2 (sections marked step 2): analytics, R-W-03,
R-W-04 and the analytics members of R-W-06, then the full backend verify.

## Done (step 1)

| Item | What | Proving test |
|---|---|---|
| R-W-08 | `PortalLevels.OWN_SESSION` gains `POST /v1/me/notifications/read` (so TOP and NATIONAL mark their own inbox read as an own-session write, and BRAND may too); `BRAND_ROUTES` gains `GET /v1/me/notifications`; `BRAND_DASHBOARD` gains `POST /v1/dashboards/national/segment-share` | `PortalLevelsTest.theTable` (new rows: allowed and still-refused neighbours), `InboxTest.nationalTopAndBrandReadAndMarkTheirOwnInbox` (NATIONAL, a legacy TOP token and BRAND read and mark their own rows through the real guard, nobody else's), `SegmentShareTest.overHttpTheScopeComesFromTheTokenAndTheClientCannotWidenIt` (BRAND now 200 with only Maxim's `SEG-MED`, the foreign `SEG-BID` absent even when the body names every zone) |
| R-W-09 (1) | `Me.support_desk` true only for a caller who may write: the desk permission, and for an ADMIN module `support` at `w` (was: any `support` grant) | `SupportTicketTest.reporterSeesOwnTicketsOnlyAndTheDeskSeesAll` (new ADMIN `admindeskr` with `support:r`: flag false, reads every ticket, PATCH and reply 403) |
| R-W-09 (2) | A reused `op_uuid` / `event_uuid` racing on two tickets: the unique violation on `support_ticket_event_client_uuid_key` (SQLSTATE 23505) is mapped to 409 `ERR_CONFLICT` in `SupportTicketService.update` and `reply` (`uuidRaceAsConflict`); any other unique violation stays an error | `SupportTicketTest.aUuidReusedAtTheSameMomentOnTwoTicketsIsAConflictNeverAServerError` (two concurrent HTTP requests on two connections, made to overlap by holding `LOCK TABLE app.audit_log IN SHARE MODE` until both wait in PostgreSQL: PATCH 200 + 409, reply 201 + 409, the loser writes no event, version, audit, inbox or domain-event row) |
| R-W-09 (3) | `q` also matches the issue type's `label_bn` | `SupportTicketTest.qAlsoMatchesTheBanglaIssueLabel` |
| R-W-09 (4) | The ticket and event photo lists stop at `cfg.support.ticket_max_photos` (0..3, never above the contract's 3); the server stores every photo that arrives; no outcome code | `SupportTicketTest.theTicketListsStopAtThePhotoCap` (4 + 4 `media_meta` via sync all accepted; lists 3 and 3, then 1 and 1 with the cap at 1; `attachment_count` 8) |
| R-W-09 (5) | Ticket fuzz: random in-batch duplicates (one batch in three), and an ADMIN accept of a quarantined SR comment while SR is not a requester role (409 `ERR_REQUEST_STATE`, the role re-check's message, nothing stored, item open, the phone's resend still held). The harness `send` maps acks by position | `SupportTicketSyncFuzzTest.duplicateAndOutOfOrderUploadsNeverDoubleAndConverge` (end-of-run guard: both cases ran at least once) |
| R-W-09 (6) | `MediaRead`: a feedback photo (`ref_type` `feedback`) is read by the feedback triage roles (SUPPORT, ADMIN, SUPERADMIN) and the desk permission regardless of geography; others keep the captor rule. New `platform/SupportRoles` holds the desk permission and the triage set for masterdata (SupportTickets, FeedbackApi) and media | `MediaReadTest.theTriageRolesAndTheDeskReadFeedbackPhotos` |
| R-W-09 (7) | `AuditRead` allowlist entity `zone_day` with B3's eight members | `AuditReadTest.aZoneDayReopenIsListedWithItsImages` |
| R-W-09 (8) | Confirmed: `task-planner` and `tso-visit-log` already send `label_bn` on every column (B3); now pinned | `TsoMyCallEndToEndTest` (both query answers and the `GET /v1/reports` catalogue: W1's keys in order, every `label_bn` present and Bangla) |
| R-W-09 (9) | Leave dates relative to today: `LeaveTest` (system clock: `today` marked `// wall-clock-ok`), the two remaining 2027 leave dates of `CheckerBackendAdminTest.oddInputIsNever500`, and `LeaveHandlerTest` (relative to its fixed world clock's today, no wall-clock read) | the three classes |
| R-W-06 (1) | `DayService.submitKind` is the one rule (TSO "manual", a role in `cfg.day.final_submit_delegate_roles` "delegated", else null); `finalSubmit` and `preview` both read it; `FinalSubmitPreviewDto.can_submit` is always sent | `DayApiTest.canSubmitIsTheSubmitsOwnRoleRule` (TSO true; DMO and WM false, then true when delegated; ADMIN and SUPERADMIN false; for each caller the submit is 403 exactly when `can_submit` is false; still true after the close with `already_submitted`) |
| R-W-06 (4) | `RouteSnapshot.sr_user_id` / `sr_name` on every bundle route entry (TSO, AMO and SR bundles, and so `BundleDelta.routes_added`): the first SR assignee as `GET /v1/routes` lists them (not ended, valid on the bundle date, cover before primary), null when vacant | `AmoZoneBundleCheckerTest.theTsoBundleNamesEachRoutesSrOnTheBundleDate` (primary, a cover that wins, a vacant route with both members null; the SR bundle carries the same) |
| R-W-17 | docs/24 s4.9 rule 4 (and its two outcome-code rows and the cfg default row), docs/16 DQ-57 (and K-15, s-Final Submit row, G-qa-115), docs/17 s4.9 item 4 and the late-row rule: held before the lock, refused after it, untrusted clock held, midnight-straddle held | docs only |
| R-W-01 / R-W-03 | Not broken: no change to IngestService, DayStates, AutoFinalSubmitJob or the projection; `finalSubmit` reads the same rule through `submitKind` | DayApiTest (full class) in the verify run |

## Done (step 2)

| Item | What | Proving test |
|---|---|---|
| R-W-03 (ops) | `OpsService` login-submit `zones[]` and sync-health `by_zone` say a zone is final-submitted by `zoneSubmittedSql` (NationalDashboard.kt), the one fragment the national Final Submit card and the summary use (the zone-day projection of the live `app.final_submit` rows; an old dw-only stamp never counts), for zone callers and route-only callers; was the route-day states (`agg_daily_zone.final_submitted`, `v_route_day.zone_final_submitted`). The fragment is called, not copied | `OpsApiTest.zoneFinalSubmitFollowsTheLiveRowsLikeTheNationalCard` (R3's state alone: false; a live row of zone 1 whose routes are not final_submitted: true, each change carried by one route-day dirty mark and the worker; reopened: false; an old `auto_submitted` stamp: false; both lists equal the national card for the national caller and both zone TSOs). Negative proof in Verify. `OpsApiTest.v12AdditionsAreScopedAndConsistent` now expects no final-submitted zone on seed_day (it has no `app.final_submit` row) |
| R-W-03 (proof) | The end-to-end test B2 asked for (asks 8 and 11), new in the app module (production wiring) | `FinalSubmitProjectionEndToEndTest` (below) |
| R-W-04 | docs/10: the Geo-validation row states the dashboard Geo Fencing card's basis (completed SR calls of the national route set, D-TSO-MAN-09 / D-B2-16, so the web card equals the TSO summary; FF Geo pins, the route pop-up and the route map stay per route). docs/09 s-Dashboard names the same in one clause. docs/10's Final-submit row also states R-W-03's one rule and its four readers | docs only |
| R-W-06 (2) | `DailyTrackingRow.tilldate_target_achievement_bp` (Long?, always sent): the till-date `Percent.bp(A, T')` itself; the deprecated float is that bp / 100, so the two never disagree and are null together | `DailyTrackingTillDateTest` (all 3): 433, 162 and 809 bp (calendar basis), 783 bp (working basis at Banani), null without a cigarette target (R3) and when n = 0; float x 100 == bp exactly (BigDecimal, no second rounding) |
| R-W-06 (3) | `TargetVsAchievement.monthly_target_qty` (Decimal3 "<T>.000", null without a target) and `working_days` (W at the route's zone, always sent) on every route-map card; the bp by the Sales card's rule through the same functions the Sales card now calls (`dailyTargetThousandths`, `salesAchievementBp` in NationalDashboard.kt): round_half_up(10000 x sales x W / T) once; W = 0 or no target gives null, T = 0 gives null and daily "0.000" | `NationalMapsTest.routeMapTargetCardUsesTheSalesCardRule` (W 26 by a zone make-up day, T 2530, sales 45: daily "97.308", bp 4625, where the old 3-dp daily-target rule gives 4624, asserted beside it; T 0; W 0; no target) and `routeMapShowsMarkersPathAndTheThreeCards` ("2500.000", 25: the whole ".000" X1 ask 2 asked for); the Sales card's refactor by `NationalExactPctTest` 8/8 and `WorkingDaysScopeTest` 2/2, unchanged |

`FinalSubmitProjectionEndToEndTest` (R-W-03's proof): the mirrored division FSE, one planned route-day of Z-FSE on
2027-01-02, clock 10:00 Dhaka on 01-03. AutoFinalSubmitJob and the AggregationWorker both run as the worker login
(`SET ROLE worker_rw`), as Main.kt wires them; after the setup's one whole-day rebuild the worker only drains what each
change marked. For the TSO `fsetso1001` and `superadmin1001`, over HTTP: the national Final Submit card (service zones,
submitted, bp), the summary (`zones_with_target_routes`, `zones_final_submitted`), login-submit `zones[]` and sync-health
`by_zone`. (1) `job.runOnce()` closes the zone-day (via job, cycle 0 uuid), its route-day is dirty with reason
`final_submit` from the close's own transaction, nothing moves before the worker, after the worker all four count it (1 of
1, 10000 bp); (2) `POST /v1/day/reopen` as `admin1001` (DayService.reopen), route-day dirty `reopen`, the worker, all four
stop (0 of 1, 0 bp); (3) the second `runOnce()` re-closes it as cycle 1 (`jobUuid(zone, day, 1)`, R-W-01 / D-B3-11), dirty,
the worker, all four count it again; a third pass closes nothing. Each read moves the clock 6 s past the summary's 5 s cache.

## Decisions (D-X2-NN; the lead may move them to DECISIONS.md)

- **D-X2-01 `Me.support_desk` follows the write gate, not a role list.** R-W-09 (1) names "SUPERADMIN, or a holder of module
  `support` at `w`". The server's desk writes need the permission `support.ticket.manage` and, for an ADMIN only, the module
  gate at `w`; a SUPPORT user granted the permission (V0134: "SUPPORT can be granted it") writes too. The flag is computed
  from that same rule, so it is true exactly when the writes are allowed (SUPERADMIN and a `support:w` ADMIN as ruled, plus
  a granted SUPPORT); an ADMIN without the module claim is false (fail closed).
- **D-X2-02 The uuid race is mapped, not locked.** R-W-09 (2) allows either. A per-uuid advisory lock would serialise the
  two requests, but the unique index is the real guard and also covers the derived uuids (the reply's status change, the op's
  second and third events); mapping its violation covers every path with one rule. The match is the constraint name in the
  driver message (as RecordWriter does).
- **D-X2-03 The photo lists follow the cap value, bounded by the contract.** "The lists stop at the cap" is read as the
  configured cap (`cfg.support.ticket_max_photos`, 0..3), so lowering it shortens the lists; `attachment_count` still counts
  every stored photo (D-B1-16 unchanged), the server stores what arrives.
- **D-X2-04 Feedback photos: role and permission, not reach.** The triage roles and the desk read feedback photos whatever
  their reach (today they are all national, so nothing changed for the seed; the rule makes it explicit and independent of
  geography). The ADMIN module gate still applies before MediaRead (`/v1/media/**` -> `reports`), as it does for the feedback
  list itself (`/v1/feedback/**` -> `reports`).
- **D-X2-05 `sr_user_id` / `sr_name` in every bundle.** R-W-06 (4) and the task ask it for the TSO bundle "and wherever the
  schema is shared, consistently". `RouteSnapshot` is one type for the SR, AMO and TSO bundles and the delta's
  `routes_added`, so every route entry carries it with one meaning (the SR of `GET /v1/routes`). Cost: two members per route.
- **D-X2-06 `can_submit` is the role rule only.** As the contract describes it: the preview is already 403 outside the
  caller's reach, a future date and an existing close are separate answers (`already_submitted`).
- **D-X2-07 Leave probes kept literal.** The parse probes of `LeaveTest` (`2027-02-30`, not a date) and of
  `CheckerBackendAdminTest` (`2100-12-31` far out of window, and the `+/-999999999` year bounds) stay literal: they test
  parsing and bounds, not a date inside the leave window, and never drift into or out of it.

- **D-X2-08 The ops lists keep their rows (step 2).** R-W-03 rules how a zone counts as final-submitted, so only the boolean
  moved to `zoneSubmittedSql`; `zones[]` still lists the zones with target routes in the zone rollup and `by_zone` every zone
  of the node (or of a route-only caller's routes). Changing which zones are listed would change the pages' rows, which no
  ruling asks.
- **D-X2-09 The one Sales-card function lives in analytics.** "The same shared function the sales card uses" is read as one
  function called by both cards: `dailyTargetThousandths` and `salesAchievementBp` (NationalDashboard.kt) on shared/rules'
  `Percent.bp`. shared/rules has no bp form of `Targets.dailyRingTenths` (tenths only) and is F3's; adding one there is not
  needed for one rule. The route card's daily target comes from the same function too (same value as before: T / W half up
  to 3 dp for T >= 0, now from integers instead of BigDecimal).
- **D-X2-10 `working_days` always, `monthly_target_qty` nullable.** The contract types `working_days` as a plain integer
  0..31, so the server always sends it (0 included); `monthly_target_qty` is null exactly when the category has no target.
- **D-X2-11 The end-to-end proof runs at the worker's grants.** Both the job and the aggregation worker run as `worker_rw`
  in the test (the production wiring), so a missing grant on the close, the reopen's dirty marks or the projection would
  fail it; the reads go through the production HTTP wiring with real tokens.
- **D-X2-12 Left as is, named here.** `DashboardApi.compute`'s `sums` / `routeSums` still compute `zf` from the route-day
  states, but `withSet` overwrites every final-submit member from `setFigures` (`zoneSubmittedSql`), so no reader sees it;
  removing it is a cleanup, not a rule change. The `final-submit-status` report's `zone_final` column (ReportHandlersB,
  `bool_and(day_state = 'final_submitted')`) and `dw.v_route_day.zone_final_submitted` (a stable dw view column) also read
  route-day states; R-W-03 names the cards, the summary and the two ops lists, so they are ask 3, not changed.

## Deviations (files outside the step's modules)

- `backend/analytics/src/test/.../SegmentShareTest.kt`: the BRAND expectation R-W-08 names (analytics is step 2; only this
  test line changes in step 1).
- `backend/app/src/test/...` (`SupportTicketTest`, `SupportTicketSyncFuzzTest`, `TicketHarness`, `DayApiTest`,
  `AmoZoneBundleCheckerTest`, `TsoMyCallEndToEndTest`, `LeaveHandlerTest`): the proving tests of this step live in the app
  module (production wiring); the full `:backend:app:test` is step 2.
- `docs/16-data-platform.md`, `docs/17-sync-engine.md`, `docs/24-build-spec.md`: R-W-17.
- Step 2: `backend/app/src/test/.../FinalSubmitProjectionEndToEndTest.kt` (new; the proof needs the sync job, DayService
  and the analytics reads in one production wiring, which only the app module has); `docs/09-web-and-api.md` (one clause,
  R-W-04 "docs/09 if it describes the card").

## Asks

1. **X1 / lead (contract 1.10.0 wording):** `Me.support_desk`'s description says "Whether the caller holds the support-desk
   permission"; after R-W-09 (1) it is "may work the desk (write)": the permission, and for an ADMIN module `support` at `w`.
   A one-line description change in place (1.10.0 unreleased), regenerated slices and web types.
2. **Lead:** D-X2-01 (a SUPPORT user granted the desk permission also gets `support_desk` true, since it may write).
3. **Lead (R-W-03 reach, step 2):** the `final-submit-status` report's `zone_final` column still reads the route-day
   states. Say whether "one formula everywhere" covers it: then it is one line (`zoneSubmittedSql` per group, grouped by
   `g.zone_id`) plus the report tests that pin it; `dw.v_route_day.zone_final_submitted` would need a view change (V0142+).
4. **Lead (X1 ask 6, still open):** `DailyTrackingRow.by_category.qty_base` stays sold plus free (as built, as the contract
   describes it); not changed in step 2.

## Verify (this worktree, local laptop, JDK 21, UTC, local PostgreSQL 16; CI not run, nothing pushed)

`gl.sh` is the lock wrapper of the brief, called as `bash /c/.../gl.sh /c/Users/User/Aron/.claude/worktrees/tso-X2 ...`
(a `/c/` path, so its python3 PATH entry works); `PY` is the brief's python3.exe.

| Command | Result |
|---|---|
| `gl.sh <wt> :backend:platform:test --tests *PortalLevelsTest :backend:notify:test --tests *InboxTest :backend:media:test --tests *MediaReadTest :backend:masterdata:test --tests *AuditReadTest --tests *LeaveTest --tests *CheckerBackendAdminTest :backend:analytics:test --tests *SegmentShareTest :backend:app:test --tests *SupportTicketTest --tests *SupportTicketSyncFuzzTest --tests *DayApiTest --tests *AmoZoneBundleCheckerTest --tests *TsoMyCallEndToEndTest --tests *LeaveHandlerTest --continue` | BUILD FAILED in 3m 32s, only `:backend:app:compileTestKotlin` (my `DayApiTest` called the `HttpClient.previewOf` extension without `client.`; fixed). Everything else ran green: PortalLevelsTest 2/2, InboxTest 9/9, MediaReadTest 3/3, AuditReadTest 8/8, LeaveTest 3/3, CheckerBackendAdminTest 8/8, SegmentShareTest 4/4 |
| `gl.sh <wt> :backend:app:test --tests *SupportTicketTest --tests *SupportTicketSyncFuzzTest --tests *DayApiTest --tests *AmoZoneBundleCheckerTest --tests *TsoMyCallEndToEndTest --tests *LeaveHandlerTest --continue` | BUILD SUCCESSFUL in 4m 25s: SupportTicketTest 17/17, SupportTicketSyncFuzzTest 1/1 (51 s; both new cases ran, the end guard holds), DayApiTest 10/10, AmoZoneBundleCheckerTest 7/7, TsoMyCallEndToEndTest 1/1, LeaveHandlerTest 4/4 |
| Negative proof: the mapping disabled (`if (race && false)`) in `SupportTicketService.uuidRaceAsConflict`, then `gl.sh <wt> :backend:app:test --tests *SupportTicketTest.aUuidReusedAtTheSameMomentOnTwoTicketsIsAConflictNeverAServerError` | BUILD FAILED as intended: "expected: <409 Conflict> but was: <500 Internal Server Error>" (`ERR_INTERNAL`), so the test reaches the real race; the file was restored from a copy before any commit |
| **Module suites** (on head `c613a1998`): `gl.sh <wt> :backend:platform:test :backend:auth:test :backend:masterdata:test :backend:notify:test :backend:media:test :backend:sync:test :backend:app:test --tests *ContractConformanceTest --tests *BundleDeltaTest --tests *BundleAcceptanceTest --tests *ScopeLeakAcrossEndpointsTest --tests *RouteInventoryTest --tests *AuthzAuditTest --continue` | BUILD FAILED in 21m 29s with one failure, not this lane's: platform 93/93, masterdata 243/243 (AuditWriteRedactionTest green after R-W-14), notify 9/9, media 17/17, sync 37/37 (JcsTest green), app inventory 41/41 (ContractConformanceTest 6/6 with `can_submit` and the bundle's `sr_user_id`/`sr_name` sent, BundleDeltaTest 7/7, BundleAcceptanceTest 8/8, ScopeLeakAcrossEndpointsTest 7/7, RouteInventoryTest 9/9, AuthzAuditTest 4/4); **auth 78/79**: `RoleGrantsTest.aGrantAndARevokeTakeEffectWithinTheCacheWindow` expects the ADMIN grant list without V0134's `support.ticket.manage`. That test is fixed on the integration branch by `a1b5041b3` ("RoleGrantsTest knows V0134's support desk grant"), which is after this branch's base (`tso-man/X1` from `1e9ab8ee4`); not cherry-picked here (not this lane's file; the merge brings it) |
| `PY tools/ci/date-pin-scan.py .` | "106 literal(s) in seed-dependent tests, 106 baselined, 0 new" |
| `PY tools/ci/wallclock-scan.py .` | 14 offenders in 5 modules (report only), none in a line of this lane: the new reads (`LeaveTest.today`, the `AuditReadTest` token expiry) carry `// wall-clock-ok:`; the race test waits with `System.nanoTime` |
| `PY tools/ci/money-float-scan.py .` | "13/13 allowed (0 open finding(s))" |

Not run in step 1: `:backend:analytics:test` (only SegmentShareTest) and the full `:backend:app:test` (both in step 2
below), CI, web (no web or contract file changed).

### Verify, step 2 (same laptop; head `b3e3832bc` for the code, CI not run, nothing pushed)

| Command | Result |
|---|---|
| `gl.sh <wt> :backend:analytics:test --tests *OpsApiTest --tests *NationalMapsTest --tests *DailyTrackingTillDateTest --tests *RouteKeyedDashboardTest --tests *NationalExactPctTest --tests *WorkingDaysScopeTest --tests *FinalSubmitPctUnifiedTest --tests *NationalDashboardApiTest :backend:app:test --tests *FinalSubmitProjectionEndToEndTest --tests *ContractConformanceTest --continue` (before the commits, same code) | BUILD SUCCESSFUL in 11m 33s: OpsApiTest 11/11, NationalMapsTest 8/8, DailyTrackingTillDateTest 3/3, RouteKeyedDashboardTest 5/5, NationalExactPctTest 8/8, WorkingDaysScopeTest 2/2, FinalSubmitPctUnifiedTest 4/4, NationalDashboardApiTest 5/5; app FinalSubmitProjectionEndToEndTest 1/1, ContractConformanceTest 6/6 (the route map and daily tracking answers validate against 1.10.0 with the new members) |
| Negative proof: `OpsApi.kt` put back to step 1's version (`git show 24946af6d:...`), then `gl.sh <wt> :backend:analytics:test --tests *OpsApiTest.zoneFinalSubmitFollowsTheLiveRowsLikeTheNationalCard` | BUILD FAILED as intended: "R3 final_submitted by state, no live row ==> expected: <({1=false, 2=false}, {1=false, 2=false}, 0)> but was: <({1=false, 2=true}, {1=false, 2=true}, 0)>" (the old rule counts zone 2 from its route-day state while the card says 0); the file was restored with `git checkout` before anything else ran. The route-map test carries its own contrast (the old `ratioBp` over the 3-dp daily target asserted at 4624 beside the new 4625) |
| **Full backend verify, one run** (head `b3e3832bc`): `gl.sh <wt> :backend:analytics:test :backend:app:test :backend:sync:test :backend:masterdata:test :backend:platform:test --continue` | **BUILD SUCCESSFUL in 1h 10m 16s**, 0 failing tests: analytics 50 classes, **301 tests, 0 failures** (295 at B2 + B2's 4 fix-round tests + OpsApiTest +1 and NationalMapsTest +1; SuspiciousLocationTest 1/1, no fixture timeout this time); app 101 classes, **520 tests, 0 failures, 2 skipped** (FinalSubmitProjectionEndToEndTest 1/1, AutoFinalSubmitJobTest 5/5, DayApiTest 10/10, DataFlowTraceTest 16/16, ScopeLeakAcrossEndpointsTest 7/7, ContractConformanceTest 6/6). `:backend:sync:test`, `:backend:masterdata:test` and `:backend:platform:test` were **UP-TO-DATE** (Gradle: inputs unchanged since step 1's run on `c613a1998`, whose results stand: sync 37/37, masterdata 243/243, platform 93/93); they were not re-executed. "43 actionable tasks: 5 executed, 1 from cache, 37 up-to-date". No laptop-only failure showed (Room MigrationTestHelper and SyncConvergenceFuzzTest are in Android and sync modules not executed here) |
| `PY tools/ci/money-float-scan.py .` | "13/13 allowed (0 open finding(s))" (the route card's BigDecimal division is gone; no new float) |
| `PY tools/ci/date-pin-scan.py .` | "106 literal(s) in seed-dependent tests, 106 baselined, 0 new" (the new app test uses 2027 dates on a mirrored division) |
| `PY tools/ci/wallclock-scan.py .` | 14 offenders in 5 modules (report only), none in a step 2 line (the new tests read movable or fixed clocks only) |

Not run: CI on Linux, the web suite (no web, contract or generated file changed in step 2), `:backend:auth:test` (not in
step 2's list; step 1's RoleGrantsTest note stands), device checks. No Android screen: no goldens.

## Not done

- `RoleGrantsTest` (auth) stays red on this branch until the integration branch's `a1b5041b3` is merged in (step 1 Verify).
- The `Me.support_desk` contract description (ask 1) is unchanged here (contract files are X1's).
- Ask 3 (the `final-submit-status` report's `zone_final` column and the `v_route_day.zone_final_submitted` view column still
  read route-day states) and ask 4 (X1 ask 6, the daily-tracking quantity basis) wait for the lead.
- The web reads of the new members (`tilldate_target_achievement_bp`, `monthly_target_qty`, `working_days`) are X3's (R-W-13).
- `DashboardApi.compute`'s unused route-day-state `zf` (D-X2-12) is not removed.

## Next step

Lane X2 is complete for its two steps. The lead merges `tso-man/X2` (after X1, with `a1b5041b3` from the integration
branch for RoleGrantsTest) and rules asks 1 to 4; X3 prints the till-date % from the bp and the route-map bars from
`monthly_target_qty` and `working_days`.
