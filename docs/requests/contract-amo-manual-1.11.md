# Contract 1.11.0: AMO manual build, trains F1 and F2 (gate file)

Written by lane amo-foundation on 2026-11-06 for `docs/requests/amo-manual-build-2026-11.md` s3. `contract/openapi.yaml`
`info.version` goes 1.10.0 to 1.11.0 in the same push. `tools/ci/contract-breaking.sh` (oasdiff) reports 0 breaking changes:
everything below is additive. F2 appends its items to this file.

## Additive changes

- `VisitPayload.force_sale_reason` (optional, nullable enum `internet_problem | location_change`; allowed only with `geo.action` `force_sale`, else the record is a final `schema_invalid`).
- `RubricDef` / `RubricWrite` criteria: optional nullable `section`, `section_bn`, `guidance_en`, `guidance_bn` (so also `RubricAdmin`; the bundle sends a member only when set). The required lists are unchanged.
- New operation `getCallTargets` (`GET /v1/supervisor/call-targets`, AMO only) with schemas `CallTargets` and `CallTargetCard` (docs/24 Appendix A: 269 operations on 218 paths).
- `TeamStockList.items[]`: optional `route_names`, `total_outlets`, `phone` (null unless the caller is an AMO of the zone); the list now also holds SRs with no stock rows.
- `OutletRequestVerificationPayload`: optional `route_id`, `cluster_id` (`fix` and `photo_uuid` already existed).

No enum a response carries gained a value; no `RecordType`, `RecordOutcomeCode` or `ProblemCode` change. `schema_invalid` already exists.
Shared: `WireDtos` gains `CallTargets`, `CallTargetCard`, `VisitPayload.forceSaleReason`; `web/src/contract/openapi.d.ts` regenerated.

## Train F2 additions (lane amo-foundation, 2026-11-06; all additive)

- New operations `getTeamJourney` (`GET /v1/team/journey?user_id&route_id&business_date`), `getTeamMembers` (`GET /v1/team/members?business_date`) and `getOutletSalesByDate` (`GET /v1/outlets/{outlet_id}/sales-by-date?business_date`), with schemas `TeamJourney`, `JourneyCall`, `JourneyOutlet`, `TeamMemberList`, `TeamMemberRow`, `SalesByDate`, `SalesByDateLine` (docs/24 Appendix A: 272 operations on 221 paths). Readers: AMO (own zone), TSO, DMO, WM and the national read roles; SR, SUPPORT and BRAND get 403.
- `LoginSubmitStatus`: optional `geo_valid_visits`, `photo_valid_visits`, `visits_total`, `sales_by_category` (`CategoryVolume`, member `category_code`), present only for an AMO caller. `getLoginSubmitStatus` is now open to the AMO (reach = own zone); the national dashboard endpoints stay closed to the AMO.
- Field names the phones build against: sales-by-date `sku` is an object `{sku_id, sku_code, short_name}`, then `qty_report` (Decimal3 string), `report_unit`, `qty_base`, `value_mtk`, and `total_qty_base`, `total_value_mtk`; members rows carry `user_id`, `full_name` (both null when `vacant`), `route_id`, `route_name`, `route_days` (ISO weekdays, 1 = Monday), `phone` (clear only for an AMO on routes of its home zone), `phone_masked`, `total_outlets`, `vacant`.
- Report `sales-summary` gains the columns `target_qty_base`, `target_value_mtk` (the brand's target on the route), `route_target_qty_base`, `route_target_value_mtk` (the route's whole target, repeated per row), null when there is no target. Report column lists are data (`ReportDefinition`), not contract schema, so no schema change; the access set of D-AMO-REPORT-02 is unchanged.
- Shared: `WireDtos` gains the seven read DTOs; `web/src/contract/openapi.d.ts` regenerated; slices regenerated.

## 1.11.1 (F2.1, lane amo-foundation, 2026-11-10; additive)

- `CategoryVolume.memos` (optional, integer >= 0, nullable): accepted memos with at least one sale line of the category in the window, each memo once per category (D-AMO-MAN-18). Filled by `DashboardSummary.by_category` and so by `LoginSubmitStatus.sales_by_category` (the AMO Reports category Memo column); null on the per-route rows of Daily Tracking. `info.version` 1.11.0 to 1.11.1; oasdiff 0 breaking.
- Report columns (data, not schema): `route_id` (integer) beside `route_code` on `sales-summary`, `route-memo`, `route-std` and `std-memo`, the reports the AMO reads per route (D-AMO-MAN-18). Test `ReportBatchBTest.everyAmoReadableRouteReportCarriesRouteIdBesideRouteCode`.
- `TeamMemberRow.route_code` (required string, new schema of F2): beside `route_id`, so the team screens never match routes by name (D-AMO-MAN-18). The target reads already carry both keys (`NodeRef.id` and `code`; Daily Tracking rows `route_id` and `route_code`); `call-targets` has no route node.
