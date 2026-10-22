# backend-core → lead: contract v1.7 queue line (Final Submit preview `planned`)

From backend-core session 14, 2026-10-22, lead ruling of the same day (QA-FLOW-04, `backend-core-qa-1-answer.md` item 3;
D-BC-PREVIEW-01). For the lead to copy into `docs/requests/contract-v1.7-queue.md`; no contract edit by the lane
(D-CONTRACT-BATCH-01).

| # | Ruled | Source | Shape |
|---|---|---|---|
| n | 2026-10-22 (lead, backend-core #14) | QA-FLOW-04, D-BC-PREVIEW-01, `backend-core-qa-1-answer.md` s3 | `FinalSubmitPreview.routes[]` (GET /v1/day/final-submit/preview) gains `planned`: optional, `type: [boolean, 'null']`, additive (response property added; not an enum change). `true`/`false` from `app.route_day.planned` on the business date, `null` when the route has no route-day row yet. Why: every active route is listed (F-TSO-010), but a route not planned that day and without data is "not due"; the TSO app and web grey it ("not due") instead of showing it as a route that failed to submit. Server warnings already count only due routes (done in backend-core #14 under the current contract). With the batch: `FinalSubmitPreviewRouteDto.planned` in `DayApi.kt` (filled from the column `previewIn` already reads), the slice and WireDtos, web `openapi.d.ts`; android-tso and web-dashboard show "not due" for `planned == false && !had_data`. No migration. |
