# TSO manual build, lane F1 (foundation-contract): status

Lane F1 of `docs/requests/tso-manual-build-2026-10.md`, branch `tso-man/F1`, 2026-10-29. Scope: s3 entirely
(contract 1.10.0), D-TSO-MAN-43, and the contract parts of TK-02..06/09/10/15, SH-08, DASH-09/11/12/13, T-03/04/09/10,
FS-06/14, LV-19, FB-09/12 and PER-17. No pull request, no push (rule 11, D-TSO-MAN-03).

## Done

| Build contract | What is in `contract/openapi.yaml` 1.10.0 |
|---|---|
| s3.1 (10 operations) | `listSupportTickets`, `getSupportTicketSummary`, `getSupportTicket`, `updateSupportTicket`, `addSupportTicketEvent` (tag `support`); `listMyNotifications`, `markMyNotificationsRead` (tag `notifications`); `queryNationalBrandCpr`, `queryNationalSegmentShare`, `queryNationalLoginSubmitUsers` (`tab` required) |
| s3.2 (26 schemas) | the four enums and `TicketNumber`; ticket payloads and records; `SupportTicket` (+ inline `requester`), `SupportTicketPage`, `SupportTicketDetail`, `SupportTicketEvent`, `SupportTicketSummary`, `SupportTicketUpdate`, `SupportTicketEventWrite`; the four inbox schemas; `NationalBrandCpr`, `NationalSegmentShare`, `LoginSubmitUserRow`, `NationalLoginSubmitUsers`; `TargetAchievementNode` and `TargetAchievementRow`. All closed (`additionalProperties: false`, records `unevaluatedProperties: false`) |
| s3.3 (changed) | `RecordType`, `SyncRecord`, `CodeListKey`, `RecordOutcomeCode` (with the s4.5 wording), `ProblemCode`, `NationalSalesCard`, `NationalLoginSubmit`, `DashboardSummary.by_category`, `getTargetAchievement` (`children`) and `TargetAchievement`, `FinalSubmitPreview`, `FinalSubmitRequest` (the D-TSO-MAN-28 UUID rule), `DayReopenRequest`, `LeaveApplication`, `Feedback`, `TeamLocation`, `Me`; descriptions of `postFinalSubmit`, `getFinalSubmitPreview`, `postDayReopen` |
| s3.4 step 1 | `contract/slices/**` and `INDEX.md` regenerated; `--check` clean |
| s3.4 step 2 | `gen_wire_dtos.py` GROUPS: "Support tickets and the notifications inbox" (2 payloads + 9 read DTOs) and "TSO dashboard cards and Target Status" (7 DTOs); `WireDtos.kt` regenerated (`Me.support_desk` too); `--check` clean |
| s3.4 step 3 | `ContractEnums.kt` (`RecordType`) and `ContractCatalogues.kt` (`CodeListKey`, `RecordOutcomeCode` with status and retryability, `ProblemCode` 409s, new `SupportTicketStatus`, `SupportTicketPriority`, `SupportTicketEventKind`, `NotificationKind`) |
| s3.4 step 4 | `web/src/contract/openapi.d.ts` by `npm run gen:contract`; `check-web-contract-drift.sh` clean; web `tsc --noEmit` clean |
| s3.4 step 5 | `docs/requests/contract-tso-manual-1.10.md` (the four response-enum additions, every additive change, deploy order) |
| Tests | `ContractDriftTest` (+4 enum mirrors), `WireDtosDriftTest` (+18 DTOs), new `TsoManualContractTest` (18 tests: operations, statuses, parameters, closed schemas, `TicketNumber` pattern and length, payloads, read schemas, desk writes, the resolution message bound, the desk PATCH replay key, inbox, dashboard cards with `truncated`, Target Status and its item-sum rule, changed members, optional-until-shipped members, new codes). `contract/fixtures` holds no schema examples (only the money corpus), so no example pairs were added there |

## Decisions taken in the lane (D-F1-xx; the lead may move them to DECISIONS.md)

- **D-F1-01 Optional until shipped.** Members added to responses the server already sends are optional in 1.10.0
  (`NationalSalesCard.monthly_target_qty`, `NationalLoginSubmit.submit_of_target_pct_bp`, the `FinalSubmitPreview`
  members, `LeaveApplication`, `Feedback`, `TeamLocation`, `Me` members). Reason: `ContractConformanceTest` validates every
  served answer, so required members would turn backend:app red until B1/B2/B3 ship; 1.9.0 did the same with `digest`.
  `TsoManualContractTest.membersAddedToServedResponsesAreOptional` pins it; 1.10.x makes them required (ask 5).
  New operations and `TargetAchievement` (404 today) carry their s3 members as required.
- **D-F1-02 No schema `default`** on `Me.support_desk` and `TeamLocation.mock_fixes_today`; "absent means false / 0" is in
  the description. Reason: openapi-typescript 7 makes a defaulted member required (`defaultNonNullable`), which broke
  `web/mock/dash.ts` (a W1 file) at type check.
- **D-F1-03 Declared statuses.** s3.1 lists the operation-specific errors; the platform can also answer 401 everywhere,
  400 where a query or body is validated (`listMyNotifications`, `addSupportTicketEvent`, `getTargetAchievement`) and 403
  where the ADMIN module guard can refuse (`getSupportTicketSummary`, `getSupportTicket`). They are declared so the
  conformance suite never sees an undeclared status.
- **D-F1-04 `TargetAchievementRow`.** The inline row of `TargetAchievement` became a named schema shared with
  `TargetAchievementNode` ("rows: as TargetAchievement.rows"); same members, plus the integer ones.
- **D-F1-05 `TargetAchievement.day`.** Today (Dhaka) in the current month, the last day of a past month, the first day of a
  future month (with e = n and e = 0 of D-TSO-MAN-05).
- **D-F1-06 `NotificationItem.ref_id`.** ticket_uuid, leave_uuid, or `<zone_id>:<business_date>` for a zone-day
  (producers: B1, B3).
- **D-F1-07 Login / Submit users order.** Routes by route name, then route id; per route the SR row first, then the AMO
  rows by username (D-TSO-MAN-12 fixes only the per-route part).
- **D-F1-08 `SupportTicketUpdate.minProperties: 3`** (`op_uuid`, `expected_version` and at least one change).
- **D-F1-09 Ticket payload.** `description` is required and may be empty (the V0130 column is `NOT NULL DEFAULT ''` and
  the generic writer maps a missing key to NULL); `affected_username` is optional and nullable;
  `SupportTicketEventPayload.body` is an optional string (no null).
- **D-F1-10 Final Submit UUID.** The "name-based UUID" of D-TSO-MAN-28 is the version-4-shaped `StableIds.v4` the TSO app
  already uses, because `Uuid` and the server accept version 4 only.
- **D-F1-11 No new cfg key names in the contract.** `SpecCrossCheckTest` requires every `cfg.*` key named in
  `openapi.yaml` to be in docs/24 s9.5, and the s2.4 keys are F2's; descriptions name the decision instead (only keys
  already in s9.5, `cfg.day.reopen_roles` and `cfg.day.reopen_window_days`, are named).
- **D-F1-12 Generated DTOs** cover the phone's reads (tickets, inbox, the three new cards, Target Status), not the
  desk-only writes (`SupportTicketUpdate`, `SupportTicketEventWrite`), which only the web sends.

## Deviations (files outside the lane's Owns list)

- `docs/24-build-spec.md`: s3.4 409 row (+3 codes), s4.2 (+2 record types), s4.5 (+4 codes), Appendix A (+10 rows, count
  268 operations on 217 paths). Required by `SpecCrossCheckTest` in `:shared:contract:jvmTest`, the lane's Verify.
- `backend/sync`: `PayloadShapes.kt` regenerated (generated from the slices), two `TypeRules` lines (`support_ticket`;
  `support_ticket_event` with parent `ticket_uuid`, as s5 B1 says), four `ReasonTexts` entries (bn and en). Reason: without
  them `JcsTest` (PayloadShapes drift and "every contract record type has an ingest rule"), `BundleUnitTest` and
  `BundleAcceptanceTest` (a text per outcome code) go red for B1, B2 and B3 the moment 1.10.0 merges; the 1.8.0 batch
  added its `ReasonTexts` entry the same way. B1: the rules are in, only the handler is yours; change the lines if your
  handler needs another table or parent. Separate commit, so the lead can drop it.
- `WireDtosDriftTest.everyGeneratedClassRedactsItsSecretMembers` normalises CRLF before its LF-only class pattern (it
  found 0 classes on this Windows checkout, `core.autocrlf=true`; CI on Linux was not affected).

## Asks (for the lead; candidates for 1.10.1, D-TSO-MAN-43 batching)

1. **`ReportKey` + `tso-visit-log`** (B3 XA-03, W1 catalogue): not in s3, so not in 1.10.0. The B3 report would fail the
   conformance suite (`listReports` answers a key outside the enum). Needs the enum value, `ContractCatalogues.ReportKey`,
   a docs/24 s12.3 row and `web/src/lib/admin/report-keys.ts` (`report-keys.test.ts` compares it with the enum).
2. **`AdminModule` + `support`** (s5 B1 "`/v1/support/tickets/**` -> module support"): `support` is not an `AdminModule`
   value; `Me.modules` uses the enum as `propertyNames`, so a SUPERADMIN `Me` would fail conformance, and V0089's grant
   CHECK, `AdminModules.ALL` and the web `GRANT_MODULES` would need it too. Today `/v1/support/**` maps to module `mdm`.
   Either add `support` in 1.10.1 with F2 and W1, or keep the existing mapping for the desk routes.
3. **docs/24 s9.5 rows for the 15 cfg keys of s2.4**: db `SchemaV1aTest.registryHoldsEveryKeyOfDocs24Section95` asserts
   DB keys == s9.5 keys plus a ruled list, so F2's V0134 keys need s9.5 rows (or the ruled list). F1 left s9.5 alone to
   avoid a conflict with F2.
4. **`docs/requests/contract-v1.10-queue.md`** (rows 1 to 4) was planned as "1.10"; this build took 1.10.0, so those rows
   need a new number (1.10.x or 1.11.0).
5. **1.10.x: make required** the D-F1-01 members once B1, B2 and B3 serve them.
6. **docs/24 s14a** has an R-row per contract batch (lead rulings); an R24 for 1.10.0 is the lead's to write
   (`docs/requests/contract-tso-manual-1.10.md` holds the text).
7. Heads-up for the A and B lanes: a BUILD row appended to `docs/25-build-backlog.csv` with lane backend, android-* or web-*
   needs a coverage row in `docs/24-build-spec-verification.md` s10, or `BacklogCoverageTest` (in `:shared:contract:jvmTest`)
   fails.

## Checker round 1 (2026-10-29): four defects, all real, all fixed in 1.10.0 (nothing released yet)

| # | Defect | Fix in the contract | Test (TsoManualContractTest) |
|---|---|---|---|
| 1 | The D-TSO-MAN-20 resolution message (62 fixed characters + ticket number + label up to 120 + notes up to 2000) did not fit `SupportTicketEvent.body` maxLength 2000 (nor the V0130 CHECK), so a valid resolve with long notes would break the response or the insert | `SupportTicketEvent.body` maxLength 2400 (longest message 2204); `TicketNumber` maxLength 22 (bigint sequence: 19 digits); the comment and reply inputs stay 1..2000; notes stay 1..2000 (D-TSO-MAN-20 unchanged) | `theLongestResolutionMessageFitsTheEventBody` builds the longest message from the template in the operation's description and the schemas' bounds |
| 2 | PATCH idempotency had no replay key and no rule for a reused `op_uuid` | `updateSupportTicket`: check order (403, 400, 404, `op_uuid` replay, `expected_version`, transitions); a replay of an applied op answers the ticket as it is now and writes nothing; the same `op_uuid` with another body, on another ticket or used by any other event is 409 `ERR_CONFLICT`; one event per change in a fixed order, the first with `event_uuid` = `op_uuid`, the others StableIds.v4 of `aron.support.ticket-op:<op_uuid>:<kind>`; a status equal to the current one is 409 `ERR_TICKET_TRANSITION_INVALID`; an op with no change answers 200 and records nothing | `deskPatchIdempotencyHasADefinedReplayKey` (rules, check order, derived UUIDs valid `Uuid` v4, stable and distinct) |
| 3 | `NationalLoginSubmitUsers.items` capped at 2000 with no flag (a national web reach has about 8,500 SR rows) | required `truncated` (first 2000 rows in the D-TSO-MAN-12 order; `route_count` still counts every route) | `dashboardSchemasOfSection32` |
| 4 | Build contract s4.3 fixture "21/26 -> 33115" is the forbidden card-level rounding; the item sum is 33114 | F1 cannot edit the lead's file (ask 8). The `TargetAchievement` and `TargetAchievementRow` descriptions now state "never the card total rounded once" with the example 25/31 -> 33064, 21/26 -> 33114 (a rounded total gives 33115) | `tillDateCardIsTheSumOfItemRows` recomputes 33064, 32799, 33114 and 33115 with the s4.1 formula and checks the description's numbers |

New decisions:

- **D-F1-13 Event body bound 2400.** Of the two options (raise the event body, or cut notes to 1800), the event body is
  raised: precedence puts D-TSO-MAN-20 ("notes 1..2000") above the s2.1 column bound, so the bound gives way, not the
  decision. 2400 leaves room over the longest message (2204).
- **D-F1-14 Desk op replay key.** The first event of an op carries `op_uuid` as its `client_uuid` (unique, so a lookup
  finds the op and any reuse by another event or ticket); the later events of the op use StableIds.v4 names, so a retried
  op can never write a second event. The same-body check needs the op's body fingerprint stored with it: see ask 9.
- **D-F1-15 Login / Submit users cut.** A plain row cut (the first 2000 rows in order) with a `truncated` flag rather
  than paging: a TSO's territory is far below the cap, the web drill is a look-up, and a flag matches the other capped
  lists (`truncated`, `rejected_truncated`, `errors_truncated`).

More asks (round 1):

8. **Lead: build contract s4.3 "working basis (switch check)"** reads "without holiday 21/26 -> 33115"; by D-TSO-MAN-05
   (item sum) it is **33114** (items 808, 808, 8077, 8077, 4038, 4846, 2422, 4038). 33115 is round_half_up(40999 x 21 /
   26 = 33114.5), the card-level rounding the rule forbids. Correct it before F3 (`TargetsTest`, `KpiFormatCorpusTest`,
   `tools/ci/kpi-format-corpus.py`) and B2 (`TargetAchievementTest`) encode it. The other s4.2 to s4.4 fixtures check out.
9. **F2 (V0130, still unmerged; else V0141+ with NOT VALID then VALIDATE):** `app.support_ticket_event.body` CHECK
   `length(body) <= 2400` (today `<= 2000` in the F2 worktree), and its column comment. For the op fingerprint, either a
   nullable `op_sha256 bytea CHECK (length(op_sha256) = 32)` on `app.support_ticket_event` (set on the op's first event
   row only; the MDM `device_command.request_sha256` pattern) or, with no migration, B1 keeps it in the op's audit row
   (`request_id` = `op_uuid`, the hash in `after`). B1 and F2 choose; the contract only fixes the behaviour.
10. **B1:** implement the `updateSupportTicket` rules above (`SupportTicketTest`: replay after a lost answer returns 200
    with no new event even when `version` moved; reused `op_uuid` with another body, on another ticket, or equal to a
    reply's `event_uuid` -> 409 `ERR_CONFLICT`; a three-change op writes three events with the derived UUIDs; a no-change
    op writes nothing). The resolution message must be built from the label and notes exactly as the template.
11. **B2:** `NationalLoginSubmitUsers.truncated` (`LoginSubmitUsersTest`: 2001 rows -> 2000 rows and true; 2000 -> false;
    `route_count` counts every route). **W1:** show a "list cut, narrow the filter" note (bn and en) when `truncated`.

## Verify (this worktree, 2026-10-29, local laptop; CI not run, nothing pushed)

Round 1 rerun (after the checker fixes): every row below except "Extra" was run again on the new head with the same
result, except `:shared:contract:jvmTest`, now 71 tests, 0 failures (TsoManualContractTest 18: +3 new tests, two
extended). The "Extra" backend row was not rerun (the round-1 changes are descriptions, larger maxLength bounds and a
required member on an operation no server answers yet).

| Command | Result |
|---|---|
| `python tools/slice-contract.py` then `--check` | 890 files written; "contract/slices is up to date" |
| `python shared/contract/tools/gen_wire_dtos.py` then `--check` | exit 0 |
| `python backend/sync/tools/gen_payload_shapes.py --check` | exit 0 |
| `cd web && npm run gen:contract`; `shared/contract/tools/check-web-contract-drift.sh` | "web/src/contract/openapi.d.ts matches contract/openapi.yaml" |
| `cd web && npm run typecheck`; `npx vitest run tests/contract-drift.test.ts tests/report-keys.test.ts tests/checker-adm1-contract.test.ts` | tsc clean; 3 files, 5 tests passed |
| `gl.sh :shared:contract:jvmTest` | BUILD SUCCESSFUL; 71 tests, 0 failures (ContractDriftTest 31, WireDtosDriftTest 11, SpecCrossCheckTest 8, TsoManualContractTest 18, BacklogCoverageTest 2, UnknownEnumToleranceTest 1); first run 68 with TsoManualContractTest 15 |
| `npx --yes @redocly/cli@2.59.0 lint contract/openapi.yaml --config contract/redocly.yaml` | "Your API description is valid", 0 errors, 0 warnings |
| Extra: `gl.sh :backend:sync:test --tests *BundleUnitTest* --tests *JcsTest* :backend:app:test --tests *ContractConformanceTest* --tests *BundleAcceptanceTest* --tests *RouteInventoryTest*` | ContractConformanceTest 6/6 (every new 2xx schema satisfiable, no new finding), BundleAcceptanceTest 8/8, BundleUnitTest 5/5, JcsTest 3/4, RouteInventoryTest 8/9. The two failures are this laptop, not the change: JcsTest runs `python3` (absent here; the same check passes with `python`), and RouteInventoryTest's scope-case lookup filters test files on `"/src/test/"`, which a Windows path never contains (all 180 problems are "scope case ... not found under backend/*/src/test" for existing routes) |

Not run locally: `tools/ci/contract-breaking.sh` (the pinned oasdiff 1.33.0 is a Linux binary and this laptop has no
Linux runtime; downloading a Windows build was not done). Its approval conditions hold in the lane head: `info.version`
1.9.0 to 1.10.0 (the script's regex reads both) and `git diff --diff-filter=AM eb7b8eb5 HEAD -- docs/requests/contract-*.md`
names `docs/requests/contract-tso-manual-1.10.md`. The breaking candidates were listed by a schema diff: the four
response-enum additions of the gate file s1, no removed path, operation or schema, no new required request member.
CI runs oasdiff on the push.

## Fix round 2 (lead rulings, build contract s9, 2026-10-29)

Contract stays 1.10.0 (unreleased, changed in place). Every F1 ruling of s9 is applied; the ones for other lanes are
listed under asks.

| Ruling | What changed in `contract/openapi.yaml` | Pinned by (`TsoManualContractTest` unless noted) |
|---|---|---|
| R-F-05 | `SupportTicketPayload`: a requester role outside the configured requester roles (default TSO) is quarantined `scope_out_of_reach`; when an ADMIN accepts it (`resolveQuarantine`) the server runs the role check again and refuses the accept while the role is still not allowed (no new outcome code). `SupportTicketEventPayload` says the same for the event | `ticketFromARoleOutsideTheRequesterRolesIsQuarantinedAndReCheckedOnAccept` |
| R-F-11 | `AdminModule` + `support` (appended; `Me.modules`, `UserAccess.modules` and `UserAccessWrite.modules` key on it); description: `/v1/support/tickets` and every path under it belong to it, the desk permission stays `support.ticket.manage`. New hand mirror `ContractCatalogues.AdminModule` | `ContractDriftTest.adminModules`, `adminModuleSupportIsInTheContract` |
| R-F-12 | `ReportKey` + `tso-visit-log` (appended); `ContractCatalogues.ReportKey.TSO_VISIT_LOG`; docs/24 s12.3 row (the B3 columns: plan date, TSO, zone, route, outlet, status, completed at, both answers by key, delegate task, the task and its status); `web/src/lib/admin/report-keys.ts` | `ContractDriftTest.reportKeys`, `SpecCrossCheckTest.riskSignalsAndReportsMatch`, web `report-keys.test.ts`, `reportKeyTsoVisitLogIsInTheContract` |
| R-F-13 | `NationalSalesCard.daily_target`, `remaining`, `achievement_pct_bp`: null when W = 0 (all three were already nullable; the ring and the target print "—"). Same for the other per-card daily target, `TargetVsAchievement` (`getRouteMap` cards): `daily_target` and `achievement_pct_bp` described, null when the month has no working day | `noWorkingDayMakesTheDailyTargetAndTheRingNull` |
| R-F-14 | `SupportTicketSummary` drops `resolved_pct_bp` and `open_pct_bp`; its description and `getSupportTicketSummary`: the displays compute the whole percent once from the counts, total 0 prints "0%". WireDtos: no percentage members (the `SupportTicketSummaryWire` encoder is gone: no required-nullable member is left) | `ticketReadSchemasOfSection32` (the members are exactly the eight counts) |
| R-F-15 | `TargetAchievement.period_days` 0..31; `TargetAchievementRow.tilldate_target_qty_base` and `remaining_tilldate_qty_base` nullable (as `tilldate_achievement_pct_bp`), all three null when `period_days` is 0; the `TargetAchievement` and row descriptions say so. WireDtos: `Long?`, and the three stay explicit nulls on the wire (`TargetAchievementRowWire`) | `targetAchievementOfSections32And33` |
| R-F-16 | `format: int64` on `TargetAchievementRow.achievement_pct_bp`, `tilldate_achievement_pct_bp`, `NationalSalesCard.achievement_pct_bp`, `TargetVsAchievement.achievement_pct_bp` (the four described as uncapped) and on the new 1.10.0 members without a `maximum`: `NationalBrandCpr.brands[].cpr_bp`, `cpr_of_successful_bp`, `cpr_of_target_bp`, `NationalLoginSubmit.submit_of_target_pct_bp`. WireDtos: `Long?` | `uncappedBasisPointMembersAreInt64` (walks every schema: a `*_bp` described as uncapped must be int64; every new 1.10.0 `*_bp` is capped at 10000 or int64; the generated fields are `java.lang.Long`) |
| R-F-17 | `NationalSales.working_days`: W at the lowest common node of the effective scope (one zone, one territory, else global): days of the month - the node's weekend days (`cfg.calendar.weekend_days` at the node) - the holidays on the node's path + the make-up days on that path, one implementation (WorkingDays). `NationalSalesCard.daily_target`: today's target = `monthly_target_qty` / W, the literal formula on a Friday or holiday | `noWorkingDayMakesTheDailyTargetAndTheRingNull` |
| R-F-18 | `getTargetAchievement` and its `children` parameter: `children=zone` only at a territory or zone node (a territory answers its zones, a zone itself as its one child); at a national, wing or division node 400 `ERR_VALIDATION` (`query.children` invalid_value); the check runs on the resolved node, the default top reach node included. 400 was already declared | `childrenZoneOnlyAtATerritoryOrBelow` |

Regenerated in the s3.4 order: `contract/slices/**` and `INDEX.md`, `WireDtos.kt`, `web/src/contract/openapi.d.ts`.
`PayloadShapes.kt` is unchanged (`gen_payload_shapes.py --check` clean: the payload changes are descriptions only). The
gate file `docs/requests/contract-tso-manual-1.10.md` lists the two new enum values and the int64 format on two 1.9.0
response members in s1 (breaking-gate candidates, approved by the version bump and the file) and every other change of
this round in s2, the removed `SupportTicketSummary` members included.

New decisions:

- **D-F1-16 R-F-05 wording.** The role re-check is stated on both record payloads (`support_ticket` and
  `support_ticket_event`, as R-F-05 names both). The HTTP answer of a refused accept is B1's: `resolveQuarantine` already
  declares 400, 403, 404 and 409, its description is unchanged, and no outcome or problem code is added. The cfg key is
  not named in the contract (D-F1-11: it is not in docs/24 s9.5).
- **D-F1-17 `AdminModule` mirror, no companion edit.** A new enum `ContractCatalogues.AdminModule` (it had no mirror
  before) with a `ContractDriftTest` row; `support` is appended last so the existing order holds. No backend or web test
  pins `AdminModules.ALL` or `GRANT_MODULES` against the contract: `AdminModulesTest` compares `ALL` with its own
  per-module sample routes, `UserAccessTest` with the API's answer, web `user-access.test.ts` pins its own 15 entries and
  `checker-adm1-contract.test.ts` checks only list filters. So no backend/web companion commit was needed and none was
  made; adding `support` there is B1's (`AdminModules` line, its route mapping, an `AdminModulesTest` sample) and W1's
  (`GRANT_MODULES`, labels, the length in `user-access.test.ts`), per R-F-11. The only web file touched is
  `report-keys.ts`, which R-F-12 gives to F1.
- **D-F1-18 Other per-card daily target (R-F-13).** `TargetVsAchievement` (the Map Details cards) is the only other card
  with a daily target; it gets the W = 0 null rule in its descriptions. Its W node is not fixed by the contract, because
  D-TSO-MAN-08 names NationalDashboard, AdminCalendar, TargetAchievementApi and DailyTrackingApi, not the route map.
- **D-F1-19 "Uncapped" (R-F-16).** A `*_bp` member is uncapped when the contract describes it as uncapped or able to
  exceed 10000 (four members), or when it is new in 1.10.0 and declares no `maximum` (four members). The 1.9.0 ratios
  without a declared maximum that are bounded by their meaning (`NationalCpr.cpr_pct_bp`, both `share_pct_bp`,
  `login_pct_bp`, both `submit_pct_bp`, both `geo_pct_bp`, `miss_pct_bp`, `RouteMap.cpr.cpr_pct_bp`) are unchanged: no
  generated Kotlin DTO decodes them, and none can leave the 32-bit range (int32 holds 2,147,483,647 bp, that is about
  21.5 million %, a ratio of about 214,748x; the first text said "about 214,748 %", which was wrong). The lead may
  widen them in 1.10.x. **Superseded by D-F1-25 (checker round after the rulings): all ten are int64 in 1.10.0.**
- **D-F1-20 `children=zone` at a zone node (R-F-18).** "Territory or below" includes a zone node; it answers itself as
  its one child, so the zone card list is never empty for a single-zone reach. The check runs on the resolved node, so
  a national or wing caller without `level` gets 400 for `children=zone`.
- **D-F1-21 `tilldate_target_qty` with n = 0.** R-F-15 names the integer members only; the AMO app's Decimal3
  `tilldate_target_qty` stays required and non-null (ask 12). **Completed by D-F1-23: it is "0.000" when the node's n is
  0, stated in the contract; ask 12 is closed.**

Asks (round 2):

12. **B2 (and the lead):** with `period_days` = 0, `TargetAchievementRow.tilldate_target_qty` (Decimal3, required, the AMO
    app's) has no defined value. Proposal: B2 sends "0.000" in 1.10.0 (a working-days basis is a TSO setting; the AMO
    team key defaults to calendar days), or 1.10.x makes it nullable.
13. **F2 (R-F-11):** the module CHECK of `app.user_module_grant` is in V0089, which is shipped (at or below V0122), so
    `support` needs a new migration in the V0130..V0149 block (drop and re-add the CHECK, NOT VALID then VALIDATE).
14. **B1 (R-F-11):** `AdminModules.ALL` + `support`, `ROUTES` maps `/v1/support/tickets/**` to `support` (today
    `/v1/support/**` maps to `mdm`), and an `AdminModulesTest` sample pair; `UserAccessTest` follows `ALL`. **W1:**
    `GRANT_MODULES` + `support` with `grant.module.support` labels (bn and en) and `user-access.test.ts` 15 -> 16; the
    `tso-visit-log` catalogue entry and labels (R-F-12).
15. **A1, A2 (generated types changed):** `TargetAchievementRow.tilldateTargetQtyBase`, `remainingTilldateQtyBase`,
    `achievementPctBp`, `tilldateAchievementPctBp` and `NationalBrandCprBrands.cprBp`, `cprOfSuccessfulBp`, `cprOfTargetBp`
    are `Long?`; `SupportTicketSummary` has no `resolvedPctBp` / `openPctBp` (A2 computes them with TicketMath). A1: decode
    `NationalSalesCard.achievement_pct_bp` as a Long (int64 now).
16. **B2:** `children=zone` 400 on the resolved node (R-F-18); the three till-date members null when n = 0 (R-F-15);
    `NationalSalesCard.daily_target`, `remaining`, `achievement_pct_bp` null when W = 0 (R-F-13).

Earlier asks settled by s9: 1 (R-F-12), 2 (R-F-11), 8 (R-F-01), 9 (R-F-02, R-F-03); ask 3 is ruled by R-F-21 (the 15 cfg
keys sit in the `SchemaV1aTest` ruled set).

### Verify, round 2 (this worktree, local laptop; CI not run, nothing pushed)

| Command | Result |
|---|---|
| `python tools/slice-contract.py` then `--check` | 890 files written; "contract/slices is up to date" (rerun on the committed head too) |
| `python shared/contract/tools/gen_wire_dtos.py` then `--check` | exit 0 (`WireDtos.kt`: 9 lines in, 18 out) |
| `python backend/sync/tools/gen_payload_shapes.py --check` | exit 0 (no change) |
| `cd web && npm run gen:contract`; `bash shared/contract/tools/check-web-contract-drift.sh` | "web/src/contract/openapi.d.ts matches contract/openapi.yaml" |
| `cd web && npm run typecheck` | `tsc --noEmit` clean, exit 0 |
| `cd web && npx vitest run tests/contract-drift.test.ts tests/report-keys.test.ts tests/checker-adm1-contract.test.ts tests/user-access.test.ts tests/admin-modules.test.ts` | 5 files, 36 tests passed (the last two added because they hold the module lists; neither changed) |
| `npm_config_ignore_scripts=true npx --yes @redocly/cli@2.59.0 lint contract/openapi.yaml --config contract/redocly.yaml` | "Your API description is valid", no error or warning line |
| `gl.sh :shared:contract:jvmTest` | BUILD SUCCESSFUL; 78 tests, 0 failures (ContractDriftTest 32, WireDtosDriftTest 11, SpecCrossCheckTest 8, TsoManualContractTest 24, BacklogCoverageTest 2, UnknownEnumToleranceTest 1) |
| `gl.sh :backend:app:test --tests '*ContractConformanceTest*' --tests '*BundleAcceptanceTest*' :backend:sync:test --tests '*BundleUnitTest*'` | BUILD SUCCESSFUL in 1m 3s; ContractConformanceTest 6/6, BundleAcceptanceTest 8/8, BundleUnitTest 5/5 (it waited about 17 minutes for the Gradle lock held by lane F2's runs) |

Not run: `JcsTest` (it calls `python3`, which this laptop does not have; not chased, as instructed);
`tools/ci/contract-breaking.sh` (oasdiff is a Linux binary, CI only, R-F-22).

## Fix round 2 (lead rulings): checker findings after the rulings round (2026-10-29)

An independent checker found one medium and six low defects in the round-2 contract. All seven are fixed in 1.10.0,
which is still unreleased and changed in place. Nothing is parked.

| # | Sev | Defect | Fix in `contract/openapi.yaml` (or generator) | Pinned by |
|---|---|---|---|---|
| 1 | medium | R-F-15: only the parent carried e/n, but on the working-days basis a child zone's W comes from its own path (D-TSO-MAN-08), so a child can have n = 0 while the territory has n > 0 | `TargetAchievementNode` gains required `elapsed_days` and `period_days` (0..31) with the null rule per node. `TargetAchievement` (description, `elapsed_days`, `period_days`) and the `TargetAchievementRow` description say that the parent's pair governs `rows` and each child's own pair governs its rows; on the calendar-days basis every node has the parent's pair | `targetAchievementOfSections32And33` (required list, ranges, the per-node texts, `TargetAchievementNode.elapsedDays`/`periodDays` are `Int` in WireDtos) |
| 2 | low | R-F-17 text "days - weekend - holidays + make-up days" counts a date twice when declarations overlap | `NationalSales.working_days`: W = the number of dates in the month that are working days at the node; a make-up day works, otherwise a date works when it is neither a weekend day nor an off day; per date the most specific declaration on the path decides, and each date counts once (WorkingDays.resolve, WorkingDays.month). Example: October 2026, global, Friday weekend = 26; a global holiday on Friday 2 October leaves 26; a make-up day on a weekday adds nothing | `noWorkingDayMakesTheDailyTargetAndTheRingNull` (new phrases, the old "- the holidays on the node's path" is gone, the example recomputed per date with java.time: 26, 26, 26) |
| 3 | low | R-F-13: `monthly_target_qty` did not state the W = 0 rule; `achievement_pct_bp` did not cover a target that sums to 0 | `NationalSalesCard.monthly_target_qty`: when `working_days` is 0 the phone and the web print "—" for every figure derived from it and never divide by it. `NationalSalesCard.achievement_pct_bp`: also null when `monthly_target_qty` is 0. Same for `TargetVsAchievement.achievement_pct_bp`, which is null when the route's monthly target is 0. This matches the server (`daily?.takeIf { it.signum() > 0 }` in NationalDashboard.kt and NationalMaps.kt) | same test |
| 4 | low | R-F-18 said nothing about a house node; the test comment was wrong | `getTargetAchievement`: a house node answers its zones in the reach. A house is a group of zones inside one territory, and it is the default top reach node of a house TSO or AMO (D-AUTHZ-1-7-WIRING-01, NodeScope). An explicit `level=house` stays 400 (`query.level`, GeoLevelQuery) with or without `children`. The `children` parameter reads "territory, house or zone" | `childrenZoneOnlyAtATerritoryOrBelow` (house texts; ReachLevel above a territory = national, wing, division; at or below = territory, house, zone, route; the GeoLevelQuery 400 text). Comment corrected |
| 5 | low | `tilldate_target_qty` (AMO, Decimal3, required) had no value when n = 0 | Description: "0.000" when the row's node has `period_days` 0. The member stays required and non-null in 1.10.0. Description-only, not breaking | `targetAchievementOfSections32And33` |
| 6 | low | R-F-16: ten 1.9.0 `*_bp` members without a `maximum` stayed int32; D-F1-19 misstated the int32 bound | `format: int64` on `NationalCpr.cpr_pct_bp`, `NationalChannelCalls.channels[].share_pct_bp`, `NationalChannelStd.channels[].share_pct_bp`, `NationalFinalSubmit.submit_pct_bp`, `NationalLoginSubmit.login_pct_bp`, `.submit_pct_bp`, `NationalGeoFencing.geo_pct_bp`, `FfGeoPin.geo_pct_bp`, `FfGeoRoutePopup.miss_pct_bp`, `RouteMap.cpr.cpr_pct_bp`. Gate file s1 has a second int64 row for them; the 1.10.0 changelog line says "every `*_bp` member without a `maximum` is int64". D-F1-19 figure corrected | `uncappedBasisPointMembersAreInt64` now walks every schema: each `*_bp` without a `maximum` is int64 (the ten listed by name), and a member described as uncapped declares no maximum |
| 7 | low | The R7 explicit-null serializer was not applied to `List<T>` members, and not to nested classes | `gen_wire_dtos.py`: a `List<T>` of an R7 class gets `List<@Serializable(with = TWire::class) T>`; a nested class with required-nullable members gets its own `…Wire`, and its owner's field uses it. Regenerated WireDtos: `TargetAchievement.rows`, `TargetAchievementNode.rows`, `SupportTicketPage.items`, `SupportTicketDetail.events`, `NotificationInbox.items`, `NationalLoginSubmitUsers.items`, `NationalBrandCpr.brands` (new `NationalBrandCprBrandsWire`), `NationalSegmentShare.segments` (new `NationalSegmentShareSegmentsWire`). The doc line of every `…Wire` object now states what it covers | `WireDtosDriftTest.wireNullsHoldInListsAndNestedClasses` (encodes with `explicitNulls = false`: the four row keys are explicit nulls in `rows[]` and `children[].rows[]`, also through the plain `TargetAchievementNode` serializer; the three brand keys in `brands[]`; the value decodes back unchanged). Mutation check: with the type-argument serializer removed from WireDtos.kt the test fails with "tilldate_target_qty_base must be an explicit null"; the file was regenerated afterwards and `--check` is clean |

New decisions:

- **D-F1-22 Per-node e and n (defect 1).** I chose the first option the checker gave, the members on
  `TargetAchievementNode`, over "children use the parent's calendar". The reason is D-TSO-MAN-08: W is computed at the
  node from the node's own path, and a zone card shows the zone. Both members are required: the schema is new in 1.10.0
  and the operation answers 404 today.
- **D-F1-23 `tilldate_target_qty` when n = 0 (defect 5).** It is "0.000" (nothing is due when no working day exists or
  has elapsed), and it stays required and non-null. Making it nullable stays a 1.10.x option for the lead. This closes
  ask 12 and completes D-F1-21.
- **D-F1-24 House under R-F-18 (defect 4).** "Territory or below" includes a house, because a house groups zones inside
  one territory. A house answers its zones, as NodeScope already resolves it. The house case can only come from the
  default top node, because an explicit `level=house` is 400 on every dashboard (GeoLevelQuery).
- **D-F1-25 R-F-16 read literally (defect 6, supersedes the narrowed reading of D-F1-19).** A `*_bp` member without a
  `maximum` is uncapped and therefore int64. The ten 1.9.0 count ratios are widened in 1.10.0, which is unreleased, the
  same way as the two members already in s1. On the wire, the server's `Int?` values are unchanged; the web type stays
  `number`.
- **D-F1-26 R7 at every depth (defect 7).** For lists, the serializer goes on the type argument
  (`List<@Serializable(with = …) T>`). This needs no extra list serializer objects, and the plain serializer of the
  owner class already includes it. The same generator change also covers nested inline classes, which had the same
  gap: `NationalBrandCprBrands` has three required-nullable members, `NationalSegmentShareSegments` has two.

Asks (checker round after the rulings):

17. **B2:** `TargetAchievementNode.elapsed_days` / `period_days` per child. On the working-days basis, compute them with
    `WorkingDays.resolve` at the zone's path. For each node, apply the n = 0 null rule to that node's rows, and send
    `tilldate_target_qty` "0.000" there. With `children=zone` at a house node (the default top node of a house TSO),
    answer the house's zones.
18. **A1, A2 (generated types changed):** `TargetAchievementNode` has two new `Int` members (`elapsedDays`, `periodDays`).
    The list members of R7 classes now carry the Wire serializer on the type argument, which needs no source change in
    a reader. A1: if the TSO app decodes any of the ten widened `*_bp` members with its own DTO, use `Long`.
19. **B2 (optional):** `NationalDto.kt` may move the ten members from `Int?` to `Long?` to match the contract. Values in
    the Int range stay valid against the int64 format, so this is not required.

### Verify, checker round after the rulings (this worktree, local laptop; CI not run, nothing pushed)

| Command | Result |
|---|---|
| `python tools/slice-contract.py` then `--check` | 890 files written; "contract/slices is up to date" |
| `python shared/contract/tools/gen_wire_dtos.py` then `--check` | exit 0 |
| `python backend/sync/tools/gen_payload_shapes.py --check` | exit 0 (no change) |
| `cd web && npm run gen:contract`; `bash shared/contract/tools/check-web-contract-drift.sh` | "web/src/contract/openapi.d.ts matches contract/openapi.yaml" |
| `cd web && npm run typecheck` | `tsc --noEmit` clean, exit 0 |
| `cd web && npx vitest run tests/contract-drift.test.ts tests/report-keys.test.ts tests/checker-adm1-contract.test.ts tests/user-access.test.ts tests/admin-modules.test.ts` | 5 files, 36 tests passed |
| `npm_config_ignore_scripts=true npx --yes @redocly/cli@2.59.0 lint contract/openapi.yaml --config contract/redocly.yaml` | "Your API description is valid" |
| `gl.sh :shared:contract:jvmTest --rerun` | BUILD SUCCESSFUL; 79 tests, 0 failures (ContractDriftTest 32, WireDtosDriftTest 12, SpecCrossCheckTest 8, TsoManualContractTest 24, BacklogCoverageTest 2, UnknownEnumToleranceTest 1) |
| `gl.sh :shared:contract:jvmTest --tests '*WireDtosDriftTest*'` with the list serializer removed from WireDtos.kt by hand (mutation check) | BUILD FAILED as expected: `wireNullsHoldInListsAndNestedClasses` "tilldate_target_qty_base must be an explicit null"; WireDtos.kt then regenerated, `--check` exit 0 |
| `gl.sh :shared:contract:jvmTest :backend:app:test --tests '*ContractConformanceTest*'` | BUILD SUCCESSFUL in 41s; ContractConformanceTest 6/6 (every operation's answers checked with the networknt validator, format assertions on, against the changed schemas) |

Not run: `JcsTest`, because it calls `python3` and this laptop does not have it. `tools/ci/contract-breaking.sh`, because
oasdiff is a Linux binary that runs in CI only (R-F-22). BundleAcceptanceTest and BundleUnitTest were not rerun,
because no payload or bundle schema changed (`gen_payload_shapes.py --check` is clean).
