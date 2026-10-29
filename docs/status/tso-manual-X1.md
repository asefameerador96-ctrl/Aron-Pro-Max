# TSO manual build, lane X1 (integration round: contract and database): status

Lane X1 of `docs/requests/tso-manual-build-2026-10.md` s10 (lead rulings R-W-06 and R-W-07). Branch `tso-man/X1`,
worktree `.claude/worktrees/tso-X1`, base `1e9ab8ee4` (the integration branch after the feature wave). 2026-10-29. No
pull request, no push. X2 (server), X3 (web) and X4b (TSO app) build the behaviour of the new members from this branch;
X1 implements no server or client behaviour.

## Done

| Item | What | Proving test |
|---|---|---|
| R-W-06 (1) | `FinalSubmitPreview.can_submit`: boolean, **optional** until the server sends it (fix round: R-W-06 "the required lists do not change" and the 1.10.0 rule; 1.10.x makes it required): true when the caller's role is TSO or listed in `cfg.day.final_submit_delegate_roles`, the rule `postFinalSubmit` enforces; the role rule only (`already_submitted` stays separate); a reader of a server that does not send it falls back to the TSO rule (R-W-13) | `TsoManualContractTest.finalSubmitPreviewSaysWhetherTheCallerMaySubmit` (not required; required list unchanged) |
| R-W-06 (2) | `DailyTrackingRow.tilldate_target_achievement_bp`: int64, nullable, optional, uncapped; round_half_up(10000 x A / T') once (D-B2-13), null without a cigarette target, when n = 0 and when T' = 0. `tilldate_target_achievement_pct` described as deprecated (the same figure, bp / 100) and kept | `dailyTrackingTillDateIsAnIntegerBasisPointMember` (also: the required list is unchanged; 12345/20000 gives 6173 bp, "61.73") |
| R-W-06 (3) | `TargetVsAchievement.monthly_target_qty` (Decimal3, nullable) and `working_days` (0..31), both optional; `achievement_pct_bp` defined only by the Sales-card rule (fix round: no "sales / daily_target" definition, not conditional on the members), round_half_up(10000 * sales * working_days / monthly_target_qty) from the integers, null when W = 0, no target or a 0 target; `daily_target` = monthly / W rounded once, for display only | `routeMapTargetCardUsesTheSalesCardRule` (also the case 50 / 1991 / 27: 6781 exact, 6780 through the rounded daily target 73.741) |
| R-W-06 (4) | `RouteSnapshot.sr_user_id` (int64, nullable, min 1) and `sr_name` (nullable, <= 120), both optional: the route's SR on the bundle date, the same SR `GET /v1/routes` names (first `assignees` entry with role SR, cover before primary); null when vacant; sent in the TSO bundle (Assign Task offline, R-W-12). `RouteSnapshot` is the item of `Bundle.routes` and `BundleDelta.routes_added` | `bundleRouteEntryNamesItsSr` (WireDtos `srUserId: Long?`, `srName: String?`) |
| R-W-06 descriptions | `updateSupportTicket`: inbox kind `ticket_updated`, push reason `notification`, a desk reply also writes an inbox row (D-B1-07, D-B1-12); `addSupportTicketEvent`: its inbox row and push, none on a replay; `CategoryVolume.qty_entered`: the basis of its item's `qty_base` (fix round), sold only in `DashboardSummary.by_category` (D-TSO-MAN-09), and `DailyTrackingRow.by_category` states its own sold-plus-free basis (as built); `NationalChannelCalls` share over `total` + `unclassified`, `NationalChannelStd` over `total` (both checked against `NationalDashboard.shares`) | `featureWaveDescriptions` |
| Changelog | `info.description` 1.10.0 paragraph names the integration-round amendments (additive, every new member optional, no required list changed); version stays 1.10.0 (additive, unreleased) | `theChangelogNamesTheIntegrationRound` (fix round; `versionIs110` checks only the version string) |
| Regeneration | `contract/slices/**` + `INDEX.md` (`tools/slice-contract.py`), `WireDtos.kt` (`gen_wire_dtos.py`: `RouteSnapshot` gains two members), `web/src/contract/openapi.d.ts` (`npm run gen:contract`); `PayloadShapes.kt` unchanged (`gen_payload_shapes.py --check` clean: no payload changed). Every `--check` clean | see Verify |
| R-W-07 (1) | `V0141`: `app.domain_event_type.status = 'live'` for `support_ticket.created`, `support_ticket.status_changed`, `support_ticket.commented`, `zone_day.final_submitted`, `zone_day.reopened`, `leave.decided` (producers checked in `backend/masterdata/.../DomainEventProducer.kt`, `backend/sync/.../DayApi.kt`, `LeaveApplicationHandler.kt`); schemas, versions and `enforce_required` unchanged. `docs/data-events.md` regenerated: the six are live | `DataEventsTest.theCatalogueSaysWhichTypesAreLive` (live 10, planned 8), `theCommittedEventCatalogueMatchesTheDatabase`; `TsoIntegrationV0141Test.theSixTsoEventsAreLiveAndTheirSchemasAreUnchanged`; `TsoManualV0130Test.theBuildsSettingsPermissionAndEventsAreRegistered` (now expects live) |
| R-W-07 (2) | `COMMENT ON COLUMN dw.agg_daily_zone.auto_submitted`: retired, always false, `app.final_submit` is the source; also `dw.v_route_day.zone_auto_submitted` (the view column that reads it). The column stays (stable view contract). Fix round: the sibling comments of the old model reworded in V0141 too: `dw.agg_daily_zone.final_submitted_at`, `dw.v_route_day.zone_final_submitted_at` (first live `app.final_submit` row, via app, web or job), `dw.v_route_day.zone_final_submitted` (the midnight job sets it; no pointer to the retired column), `dw.v_final_submit_log.first_via` (job since V0135) | `TsoIntegrationV0141Test.autoSubmittedIsRetiredInTheComments` (now also the four sibling comments); `DataDictionaryTest` (`docs/data-dictionary.md` regenerated) |
| R-W-07 (3) | The one-off clean-up, B2 ask 10's UPDATE verbatim: every `auto_submitted` row gets `final_submitted_at` = the first live `app.final_submit` row (null when none) and `auto_submitted` false; rows already false untouched | `TsoIntegrationV0141Test.theCleanUpGivesEveryOldStampTheProjectionOfTheLiveRows` (migrate to V0140, seed, five zone-days: live submit, none, reopened then live, only reopened, an unstamped row; then V0141) |

## Decisions (D-X1-NN; the lead may move them to DECISIONS.md)

- **D-X1-01 `can_submit` is optional (revised in the fix round).** The first pass made it required on the lane brief's
  word; the checker held that against R-W-06 ("The required lists do not change") and the contract's own 1.10.0 rule
  (a member added to a response the server already sends stays optional until the server lanes ship it). The binding
  s10 ruling wins: `can_submit` is out of `FinalSubmitPreview.required`, described "optional until the server sends it
  (1.10.x makes it required)", like `submit_seq` and `warn_route_ids`. So X1 merges green before X2 (s10 order "X1,
  then X2"): `ContractConformanceTest` no longer reports a missing member on today's server. The web types it
  `can_submit?: boolean`, which matches the R-W-13 fallback.
- **D-X1-02 Deprecated by description only.** `tilldate_target_achievement_pct` is described as deprecated, without the
  OpenAPI `deprecated: true` flag: R-W-06 says "described as deprecated", and the flag would put `@deprecated` on the web
  type, which W1's code still reads until X3 switches to the bp.
- **D-X1-03 `TargetVsAchievement.monthly_target_qty` is Decimal3 and nullable.** Decimal3 as ruled (R-W-06 and the brief),
  although `app.route_monthly_target.qty_base` is bigint: the value is whole and carries ".000", as the Decimal3 members of
  `TargetAchievementRow` do (D-B2-12). Nullable like `daily_target`: null means no target. The checker (low) notes that
  `NationalSalesCard.monthly_target_qty`, the other card of the "one rule", is int64, so a reader parses two wire types
  for one figure; it prefers int64 here while 1.10.0 is unreleased. Kept as Decimal3 because R-W-06 names the type
  "(Decimal3)" and it is binding; the switch is the lead's call (ask 5).
- **D-X1-10 One quantity basis per list (fix round).** `CategoryVolume` is the item of `DashboardSummary.by_category`
  (sold only, D-TSO-MAN-09) and of `DailyTrackingRow.by_category`, whose server sums sold plus free
  (`DailyTrackingApi`: `sum(a.sold_qty_base + a.free_qty_base)`). `qty_entered` now follows its item's `qty_base`, and
  each list states its basis; the daily-tracking list is described as built (sold plus free), since R-W-06 names the
  summary only. Whether daily tracking moves to sold only is open (ask 6).
- **D-X1-11 `first_via` reworded too (fix round).** Beside the three comments the checker named,
  `dw.v_final_submit_log.first_via` still said "app or web" although V0135 added `job` (V0135 reworded only
  `first_submitted_by`). Same defect class, same migration, one more line.
- **D-X1-04 The route card's W is at the route's zone.** D-F1-18 left the route map's W node open; the description now
  states what the server computes (`NationalMaps.kt`: `WorkingCalendarRepo.zonePaths` of the route's zone, D-TSO-MAN-08),
  so the web can print exactly from `monthly_target_qty` and `working_days` (R-W-13 (5)).
- **D-X1-05 The bundle's route SR equals the online read.** `sr_user_id` is defined as the SR `GET /v1/routes` names
  (its `assignees` are ordered by kind, so a cover assignment valid that date comes before the primary one), taken on the
  bundle date, so Assign Task shows the same SR offline and online (A3's `PlanModel` takes the first SR assignee). It is
  on `RouteSnapshot` (the bundle's route entry for every role); "other bundles may omit it" leaves the SR and AMO bundles
  to X2.
- **D-X1-06 `addSupportTicketEvent` described too.** The ruling names `updateSupportTicket`; the reply side (its inbox row,
  its push, nothing on a replay) is stated on the reply operation as well, so each operation describes its own effects.
- **D-X1-07 The till-date bp's null cases** follow `DailyTrackingApi.tillDatePct`: no cigarette target, n = 0, and
  T' = 0 (Percent.bp gives null for a 0 denominator).
- **D-X1-08 The view column comment.** `dw.v_route_day.zone_auto_submitted` reads the retired column, so its comment is
  reworded in V0141 as well (comments are not part of the dw view column contract, DwViewContractTest).
- **D-X1-09 V0141 status update is guarded** (`AND status <> 'live'`) so it changes only what it means to; the guard
  trigger (V0117) allows the status column.

## Deviations (files outside the X1 list)

- `docs/requests/contract-tso-manual-1.10.md` (the 1.10.0 gate file): a short "Integration round" entry in s2 listing the
  R-W-06 amendments and the regeneration. Not a gate input here (the version does not change); it keeps the record whole.
- `docs/data-dictionary.md`: regenerated by `DataDictionaryTest` (`-Paron.writeDictionary=true`) from V0141's
  comments (two in the first pass, four more in the fix round); generated, never hand-edited. Without it `:db:test` fails.

## Asks

1. **X2:** send `FinalSubmitPreview.can_submit` on every preview (TSO, or the caller's role in
   `cfg.day.final_submit_delegate_roles` as `DayService.finalSubmit` reads it). It is optional in 1.10.0, so the
   conformance gate is green either way; 1.10.x makes it required once it is sent.
2. **X2:** `DailyTrackingRow.tilldate_target_achievement_bp` (the `Percent.bp` already computed in `tillDatePct`, before
   the `toDouble`), keep the float for older readers; `TargetVsAchievement.monthly_target_qty` ("<n>.000") and
   `working_days` (the `wd` of `NationalMaps`) on **every** route card, and the bp from them exactly: today
   `NationalMaps.kt` computes `ratioBp(sales, daily)` from the rounded daily target, which differs by 1 bp on the case
   50 / 1991 / 27 (6780 against the sales card's 6781); `RouteSnapshot.sr_user_id` / `sr_name` in the TSO bundle (and
   the delta's `routes_added`). If `monthly_target_qty` stays Decimal3, a server test that it is always a whole ".000".
3. **X3:** Submit follows `can_submit` with the TSO fallback; the till-date % from the bp at 2 dp; route-map bars from
   `monthly_target_qty` and `working_days`; channel legends from the counts with the stated denominators.
4. **X4b:** read `sr_user_id` / `sr_name` from the bundle's route entry for Assign Task offline (generated
   `RouteSnapshot.srUserId`, `srName`).
5. **Lead:** confirm the wire type of `TargetVsAchievement.monthly_target_qty`: Decimal3 as R-W-06 says (kept), or
   int64 like `NationalSalesCard.monthly_target_qty` (the checker's preference; 1.10.0 is unreleased, so the change is
   one schema line, one test line and a regeneration).
6. **Lead / X2:** `DailyTrackingRow.by_category.qty_base` is sold plus free today (described as built); say whether it
   moves to sold only per D-TSO-MAN-09, and whether X2 fills `qty_entered` there.

## Verify (this worktree, local laptop, JDK 21, UTC, local PostgreSQL 16; CI not run, nothing pushed)

First pass (head `0f687ba3`):

`gl.sh` is the lock wrapper of the brief; `PY` is the brief's python3.exe.

| Command | Result |
|---|---|
| `PY tools/slice-contract.py` then `--check` | 890 files written; "contract/slices is up to date" (rerun on the committed head: up to date) |
| `PY shared/contract/tools/gen_wire_dtos.py` then `--check` | exit 0 (`WireDtos.kt`: `RouteSnapshot.srUserId`, `srName`) |
| `PY backend/sync/tools/gen_payload_shapes.py --check` | exit 0 (no change) |
| `cd web && npm ci`; `npm run gen:contract`; `bash shared/contract/tools/check-web-contract-drift.sh` | 444 packages; "web/src/contract/openapi.d.ts matches contract/openapi.yaml"; regenerating again on the committed head leaves no diff |
| `cd web && npm run lint` / `npm run typecheck` / `npm test` | eslint exit 0; `tsc --noEmit` exit 0; vitest 155 files, 1923 passed, 1 skipped |
| `cd web && npx vitest run tests/contract-drift.test.ts tests/report-keys.test.ts tests/checker-adm1-contract.test.ts tests/checker-adm2-products.test.ts` | 4 files, 16 tests passed |
| redocly 2.59.0 (already in the npx cache) `lint contract/openapi.yaml --config contract/redocly.yaml` | "Your API description is valid" |
| `PY tools/ci/kpi-format-corpus.py --check` | "up to date (300 seeded cases plus the s4 fixtures; oracle reproduces the contract)" |
| `PY tools/ci/money-float-scan.py` | 13 money lines with floating point, 13/13 allowed, 0 open, 0 stale |
| `PY tools/ci/date-pin-scan.py .` | 106 literals, 106 baselined, 0 new |
| `gl.sh :db:test --tests DataDictionaryTest --tests DataEventsTest --tests DwViewContractTest --tests TsoIntegrationV0141Test -Paron.writeDictionary=true --rerun` | BUILD SUCCESSFUL; 3 + 6 + 2 + 3 tests, 0 failures; `docs/data-events.md` and `docs/data-dictionary.md` rewritten (the six live; the two comments) |
| `gl.sh :shared:contract:jvmTest :db:test :backend:app:compileTestKotlin :backend:app:test --tests *ContractConformanceTest* --tests *RouteInventoryTest* --tests *BundleAcceptanceTest* :backend:sync:test --tests *JcsTest* --tests *BundleUnitTest* --continue` | BUILD FAILED in 1h 10m (three failures, below). `:shared:contract:jvmTest` 84 tests, 0 failures (TsoManualContractTest 29, +5 new; ContractDriftTest 32; WireDtosDriftTest 12; SpecCrossCheckTest 8; BacklogCoverageTest 2; UnknownEnumToleranceTest 1). `:backend:app:compileTestKotlin` ok. RouteInventoryTest 9/9, BundleAcceptanceTest 8/8, BundleUnitTest 5/5. **ContractConformanceTest 5/6**: the one failure is "new: GET /v1/day/final-submit/preview 200 schema / required can_submit", today's server not sending the new required member (D-X1-01, ask 1; the test forbids a baseline line, so none was added). **JcsTest 3/4**: "python3 was not found" (gl.sh was called with a `C:/` path, so its `bin` entry broke on the colon in PATH; rerun below). **`:db:test` 320 tests, 1 failure**: `TsoManualV0130Test.theBuildsSettingsPermissionAndEventsAreRegistered` still expected the six V0134 events planned; fixed in `9b5eaa7a` |
| `bash /c/.../gl.sh /c/.../tso-X1 :db:test --tests *TsoManualV0130Test* --tests *DataEventsTest* --tests *TsoIntegrationV0141Test* :backend:sync:test --tests *JcsTest* --continue` | BUILD SUCCESSFUL; TsoManualV0130Test 17/17, DataEventsTest 6/6, TsoIntegrationV0141Test 3/3, JcsTest 4/4 (PayloadShapes drift and "every record type has an ingest rule" included) |

So at the first-pass head (`0f687ba3`): everything above was green except `ContractConformanceTest`'s `can_submit`
finding. The fix round (below) clears it.

### Fix round verify (head after the fix round; same laptop)

| Command | Result |
|---|---|
| Negative proof: the pre-fix `contract/openapi.yaml` and `V0141` restored from `HEAD` (`0f687ba3`) beside the new tests, then `bash /c/.../gl.sh /c/.../tso-X1 :shared:contract:jvmTest --tests *TsoManualContractTest* :db:test --tests *TsoIntegrationV0141Test* --continue --rerun` | BUILD FAILED as intended: TsoManualContractTest 30 tests, 4 failed (`finalSubmitPreviewSaysWhetherTheCallerMaySubmit`: can_submit required; `routeMapTargetCardUsesTheSalesCardRule`: no "computed by the server from the integers"; `featureWaveDescriptions`: qty_entered basis; `theChangelogNamesTheIntegrationRound`: changelog says "required"); TsoIntegrationV0141Test 3 tests, 1 failed (`autoSubmittedIsRetiredInTheComments`: "or of the midnight auto-submit"). The fixed files were then put back |
| `bash /c/.../gl.sh /c/.../tso-X1 :db:test --tests *DataDictionaryTest* --tests *TsoIntegrationV0141Test* -Paron.writeDictionary=true --rerun` | BUILD SUCCESSFUL; `docs/data-dictionary.md` rewritten (four comments) |
| `bash /c/.../gl.sh /c/.../tso-X1 :shared:contract:jvmTest :db:test :backend:app:test --tests *ContractConformanceTest* --tests *RouteInventoryTest* --tests *BundleAcceptanceTest* --tests *DayApiTest* :backend:sync:test --tests *JcsTest* --tests *BundleUnitTest* --continue --rerun` (on `ecf972e4`) | BUILD FAILED in 18m 21s, only in `:shared:contract:jvmTest` (85 tests, 2 failed): two wording slips in my new bp description ("Null when" capitalised; the R-F-13 phrase "null when the route's monthly target is 0" dropped, `noWorkingDayMakesTheDailyTargetAndTheRingNull`). Fixed in the description (both rules kept). The rest of the run was green: **`:db:test` 320 tests in 37 classes, 0 failures** (DataDictionaryTest 2, DataEventsTest 6, TsoIntegrationV0141Test 3, TsoManualV0130Test 17); **`ContractConformanceTest` 6/6** (the can_submit finding is gone), RouteInventoryTest 9/9, BundleAcceptanceTest 8/8, DayApiTest 9/9; JcsTest 4/4, BundleUnitTest 5/5 |
| `bash /c/.../gl.sh /c/.../tso-X1 :shared:contract:jvmTest --rerun` (after the wording fix) | BUILD SUCCESSFUL; 85 tests, 0 failures (TsoManualContractTest 30, ContractDriftTest 32, WireDtosDriftTest 12, SpecCrossCheckTest 8, BacklogCoverageTest 2, UnknownEnumToleranceTest 1) |
| `PY tools/slice-contract.py` then `--check`; `PY shared/contract/tools/gen_wire_dtos.py --check`; `PY backend/sync/tools/gen_payload_shapes.py --check` | slices regenerated, "up to date"; wire exit 0 (no change: `FinalSubmitPreview` is not a wire DTO); shapes exit 0 |
| `cd web && npm run gen:contract`; `bash shared/contract/tools/check-web-contract-drift.sh` | "matches"; `can_submit?: boolean` |
| `cd web && npm run lint` / `npm run typecheck` / `npm test` (before the last description-only regeneration) | eslint exit 0; `tsc --noEmit` exit 0; vitest 155 files, 1923 passed, 1 skipped |
| `cd web && npm run typecheck`; `npx vitest run tests/contract-drift.test.ts` (after it) | exit 0; 1 file, 3 tests passed |
| `npx -y @redocly/cli@2.59.0 lint contract/openapi.yaml --config contract/redocly.yaml` | "Your API description is valid" |
| `PY tools/ci/kpi-format-corpus.py --check`; `PY tools/ci/money-float-scan.py`; `PY tools/ci/date-pin-scan.py .` | up to date; 13/13 allowed, 0 open, 0 stale; 106 baselined, 0 new |

The checker's own fresh full `:db:test --rerun` on the first-pass head (320 tests in 37 classes, 0 failures) is
recorded here as theirs; the fix-round run above is mine.

## Fix round (checker report on `0f687ba3`)

| Severity | Defect | Fix | Proving test (fails without the fix, see the negative proof) |
|---|---|---|---|
| medium | `can_submit` required against R-W-06 and the 1.10.0 rule; `ContractConformanceTest` red until X2 | Out of `FinalSubmitPreview.required`; "optional until the server sends it (1.10.x makes it required)"; changelog and gate file reworded; D-X1-01 revised | `finalSubmitPreviewSaysWhetherTheCallerMaySubmit`; `ContractConformanceTest` 6/6 |
| low | V0141 left the sibling comments on the old model | Four `COMMENT ON COLUMN` lines in V0141 (three named plus `v_final_submit_log.first_via`, D-X1-11); data dictionary regenerated | `autoSubmittedIsRetiredInTheComments` |
| low | Route-map bp still defined as sales / daily_target, exact rule conditional | The exact rule is the only definition; `daily_target` for display only; a note on older servers; ask 2 to X2 | `routeMapTargetCardUsesTheSalesCardRule` |
| low | `qty_entered` "sold only" contradicts `DailyTrackingRow.by_category` (sold plus free) | `qty_entered` follows its item's `qty_base`; `DailyTrackingRow.by_category` states its basis as built (D-X1-10, ask 6) | `featureWaveDescriptions` |
| low | `monthly_target_qty` Decimal3 here, int64 on the sales card | **Not changed**: R-W-06 names the type "(Decimal3)" and is binding; lead to confirm (ask 5); X2 asked for a whole ".000" server test if it stays (ask 2) | none (no change) |
| low | Changelog row proved by `versionIs110` only; `:db:test` not rerun | New `theChangelogNamesTheIntegrationRound`; full `:db:test` rerun 320/320 | `theChangelogNamesTheIntegrationRound` |

## Not done

- Server and client behaviour for the new members (X2, X3, X4b by design).
- `TargetVsAchievement.monthly_target_qty` stays Decimal3 (checker low 5): the ruling names the type; the lead decides
  (ask 5).
- The full web `npm test` and `npm run lint` were not rerun after the last regeneration of `openapi.d.ts`; it changed
  only one `@description` comment (typecheck and the drift test were rerun).
- The Android modules that read the generated `RouteSnapshot` (core-database `RecordPayloadContractTest`) were not built
  here; the change adds two defaulted members at the end of the class (unchanged in the fix round).
- `tools/ci/contract-breaking.sh` and `tools/ci/migrations-check.sh` (oasdiff and squawk are Linux binaries, CI only,
  R-F-22).

## Next step

The lead merges X1 before X2, X3 and X4b, which branch from it; asks 5 (the wire type of the route card's monthly
target) and 6 (the daily-tracking quantity basis) are open.
