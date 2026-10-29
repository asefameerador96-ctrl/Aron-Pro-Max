# TSO manual build, lane X2 (integration round: backend): status

Lane X2 of `docs/requests/tso-manual-build-2026-10.md` s10 (lead rulings R-W-06 server part, R-W-08, R-W-09, R-W-17;
step 2 adds R-W-03, R-W-04 and analytics). Branch `tso-man/X2`, worktree `.claude/worktrees/tso-X2`, base `tso-man/X1`
(`f4ba4501d`, contract amendments and V0141). 2026-10-29. No pull request, no push.

Step 1 of 2: platform, auth, sync, masterdata, media, notify. Analytics (R-W-03, R-W-04, the R-W-06 analytics members)
and the full `:backend:app:test` are step 2.

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

## Deviations (files outside the step's modules)

- `backend/analytics/src/test/.../SegmentShareTest.kt`: the BRAND expectation R-W-08 names (analytics is step 2; only this
  test line changes in step 1).
- `backend/app/src/test/...` (`SupportTicketTest`, `SupportTicketSyncFuzzTest`, `TicketHarness`, `DayApiTest`,
  `AmoZoneBundleCheckerTest`, `TsoMyCallEndToEndTest`, `LeaveHandlerTest`): the proving tests of this step live in the app
  module (production wiring); the full `:backend:app:test` is step 2.
- `docs/16-data-platform.md`, `docs/17-sync-engine.md`, `docs/24-build-spec.md`: R-W-17.

## Asks

1. **X1 / lead (contract 1.10.0 wording):** `Me.support_desk`'s description says "Whether the caller holds the support-desk
   permission"; after R-W-09 (1) it is "may work the desk (write)": the permission, and for an ADMIN module `support` at `w`.
   A one-line description change in place (1.10.0 unreleased), regenerated slices and web types.
2. **Lead:** D-X2-01 (a SUPPORT user granted the desk permission also gets `support_desk` true, since it may write).

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

Not run here: `:backend:analytics:test` (only SegmentShareTest) and the full `:backend:app:test` (step 2), CI, web
(no web or contract file changed).

## Not done

- Step 2: R-W-03 (one final-submit formula, the end-to-end proof), R-W-04 (docs/10 basis), the analytics side of R-W-06
  (`DailyTrackingRow.tilldate_target_achievement_bp`, `TargetVsAchievement.monthly_target_qty` / `working_days`), X1 asks 2
  and 6, and the full `:backend:app:test` and `:backend:analytics:test`.
- `RoleGrantsTest` stays red on this branch until the integration branch's `a1b5041b3` is merged in (see Verify).
- The `Me.support_desk` contract description (ask 1) is unchanged here (contract files are X1's).

## Next step

Step 2 on this branch: R-W-03 (the end-to-end proof through B3's job close and reopen, the worker, `queryNationalFinalSubmit`
and the summary; OpsApi `zones[]` and sync-health `by_zone` by the `zoneSubmittedSql` rule), R-W-04 (docs/10), the
analytics members of R-W-06 (X1 ask 2: the till-date bp, the route card's `monthly_target_qty` / `working_days` and its
exact bp, a whole ".000" test), then `:backend:analytics:test` and the full `:backend:app:test`.
