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
| Tests | `ContractDriftTest` (+4 enum mirrors), `WireDtosDriftTest` (+18 DTOs), new `TsoManualContractTest` (15 tests: operations, statuses, parameters, closed schemas, `TicketNumber` pattern, payloads, read schemas, desk writes, inbox, dashboard cards, Target Status, changed members, optional-until-shipped members, new codes). `contract/fixtures` holds no schema examples (only the money corpus), so no example pairs were added there |

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

## Verify (this worktree, 2026-10-29, local laptop; CI not run, nothing pushed)

| Command | Result |
|---|---|
| `python tools/slice-contract.py` then `--check` | 890 files written; "contract/slices is up to date" |
| `python shared/contract/tools/gen_wire_dtos.py` then `--check` | exit 0 |
| `python backend/sync/tools/gen_payload_shapes.py --check` | exit 0 |
| `cd web && npm run gen:contract`; `shared/contract/tools/check-web-contract-drift.sh` | "web/src/contract/openapi.d.ts matches contract/openapi.yaml" |
| `cd web && npm run typecheck`; `npx vitest run tests/contract-drift.test.ts tests/report-keys.test.ts tests/checker-adm1-contract.test.ts` | tsc clean; 3 files, 5 tests passed |
| `gl.sh :shared:contract:jvmTest` | BUILD SUCCESSFUL; 68 tests, 0 failures (ContractDriftTest 31, WireDtosDriftTest 11, SpecCrossCheckTest 8, TsoManualContractTest 15, BacklogCoverageTest 2, UnknownEnumToleranceTest 1) |
| `npx --yes @redocly/cli@2.59.0 lint contract/openapi.yaml --config contract/redocly.yaml` | "Your API description is valid", 0 errors, 0 warnings |
| Extra: `gl.sh :backend:sync:test --tests *BundleUnitTest* --tests *JcsTest* :backend:app:test --tests *ContractConformanceTest* --tests *BundleAcceptanceTest* --tests *RouteInventoryTest*` | ContractConformanceTest 6/6 (every new 2xx schema satisfiable, no new finding), BundleAcceptanceTest 8/8, BundleUnitTest 5/5, JcsTest 3/4, RouteInventoryTest 8/9. The two failures are this laptop, not the change: JcsTest runs `python3` (absent here; the same check passes with `python`), and RouteInventoryTest's scope-case lookup filters test files on `"/src/test/"`, which a Windows path never contains (all 180 problems are "scope case ... not found under backend/*/src/test" for existing routes) |

Not run locally: `tools/ci/contract-breaking.sh` (the pinned oasdiff 1.33.0 is a Linux binary and this laptop has no
Linux runtime; downloading a Windows build was not done). Its approval conditions hold in the lane head: `info.version`
1.9.0 to 1.10.0 (the script's regex reads both) and `git diff --diff-filter=AM eb7b8eb5 HEAD -- docs/requests/contract-*.md`
names `docs/requests/contract-tso-manual-1.10.md`. The breaking candidates were listed by a schema diff: the four
response-enum additions of the gate file s1, no removed path, operation or schema, no new required request member.
CI runs oasdiff on the push.
