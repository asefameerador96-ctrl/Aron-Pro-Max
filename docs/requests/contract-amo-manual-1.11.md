# Contract 1.11.0: AMO manual build, train F1 (gate file)

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
