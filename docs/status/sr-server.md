# Lane sr-server (SV): SR manual build, server side

Branch `lane/sr-server`, from INT `claude/wonderful-thompson-k6ejnf` at ab78d0d4 (INT has since moved to ce9d92bf7 with contract 1.13.0 and V0158..V0162: NOT yet merged here). **WIP: not "checked".** Brief: `docs/requests/sr-manual-lane-brief.md` (lead docs branch). Request file: `docs/requests/contract-1.14-sr-manual.md`. Records: D-SV1-01 to 10 (DECISIONS.md).

## State of train 1

| item | rows | state |
|---|---|---|
| (a) zero-sale reason: `VisitClosePayload.zero_sale_reason_code`, code list `zero_sale_reason` (stock_available, outlet_closed_during_visit, absence_of_owner, insufficient_cash), ingest + code-list re-check, dw | SRM-A-40 | done, tested (SrManualSv1IngestTest) |
| (b) call photo: `VisitClosePayload.call_photo_uuid` + `call_photo_skip_reason_code` (code list `call_photo_skip`), media purpose `outlet_call`, `cfg.sale.call_photo_required`, review read `getCallPhotoReview` | SRM-A-27 | done, tested |
| (c) digest `business_totals`: outlets_visited, sold / stock_issued / qc as lists of {sku_id, qty_base}, promotion_mtk | SRM-B-25 | done, tested |
| (d) outlet requests: `info` cluster_id (same zone), `route_add` keeps cluster, duplicate new-shop request acked `duplicate` | SRM-B-35, 40, 42 | done, tested incl. end-to-end approval |
| (f) edit reasons wrong_sku_qty, promotion_issue (+ wrong_quantity, wrong_price_type seeded), task type poor_brand_visibility, `JourneyCall.status` repeat_sale | SRM-B-15, 49 | done |
| (g) `UpdateCheck.notes_en/bn`; `GET /v1/supervisor/device-otps` (TSO) | SRM-A-06, 09 | done, tested |
| (e) bundle `clusters`, cluster-creation record, AMO verify, portal approval | SRM-B-33, 43, 44 | NOT started (train 2) |
| (h) `outlet_sku_stats` (`ads_hundredths`, base units, phone converts by pack_factor) | SRM-A-29 | NOT started (train 2) |
| survey report `posm-survey` + dw view; late-rows-after-Sales-Submit flag | SRM-B-08, 30 | NOT started (train 2; declared in the request file) |

## Half done / open on this branch

- The last full run (command below) found real misses, now being fixed: docs/24 s9.5 config-key registry (`cfg.sale.call_photo_required`), docs/24 Appendix A operation list (`getTeamDeviceOtps`, `getCallPhotoReview`), `AmoManualContractTest` (pins version 1.12.2 and the journey status enum), `CodeListsTest`, `DataDictionaryTest`, `AdminMasterTest` (code-list counts / dictionary regeneration). Not claimed green.
- Rebase: contract commit is separate (6447ac76d and later contract-only edits are in `contract/`, `web/src/contract`, `shared/contract`); merge INT ce9d92bf7 and renumber nothing (V0163 to V0165 are free there). The dw view contract is 1.7 (V0165) because V0152 took 1.6; if the Loyalty lane raised it too, the number must be ordered by the lead.

## Migrations used

V0163 (visit columns, guards, code lists, `cfg.sale.call_photo_required`), V0164 (VALIDATE), V0165 (dw contract 1.7: `fact_visit.zero_sale_reason_code`, `call_photo_uuid`; `v_visit.zero_sale_reason_code`, `call_capture_taken`). squawk clean.

## Gates run and results

- Green: `python3 tools/slice-contract.py --check`; `tools/ci/money-float-scan.py`; `tools/ci/date-pin-scan.py`; `python3 -m unittest discover` in tools/ci (104 tests); `tools/ci/migrations-check.sh <INT ab78d0d> squawk`; `tools/ci/contract-breaking.sh <INT ab78d0d> oasdiff` (9 enum-value warnings, approved by the request file); Redocly `npx @redocly/cli@2.59.0 lint contract/openapi.yaml --config contract/redocly.yaml` (0 problems); `./gradlew :backend:app:test --tests '*SrManualSv1*'` (10 tests green).
- Last full run: `ARON_TEST_PG_URL=jdbc:postgresql://localhost:5432/postgres?user=aron&password=aron ./gradlew --no-daemon -q --continue :shared:contract:jvmTest :db:test :backend:platform:test :backend:sync:test :backend:masterdata:test :backend:config:test :backend:media:test :backend:analytics:test :backend:app:test` -> NOT green (failures listed above); a first attempt was void (stale Gradle JVMs collided).
- Not yet run: the Android modules (none touched), `ScopeLeak` / `RouteInventory` / `ContractConformance` inside `:backend:app:test` (part of the full run, results pending), three-times-green repeat.

## Open questions (none needs the owner for train 1)

1. Late-row flag and survey report: lead asked for them in this contract; shipping them in train 2 under the same version (additive). OK?
2. dw view contract number 1.7 may collide with the Loyalty lane.
3. Task type `poor_brand_visibility` has no Bangla label (part B owner question 4).
