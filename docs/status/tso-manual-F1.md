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
