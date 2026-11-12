# Contract 1.14.0: SR manual build, sr-server train 1 (lane sr-server, 2026-11-12)

For approval by the lead as **D-LEAD-CONTRACT-114-01**. Version 1.14.0 sits on INT's 1.12.2: 1.13.0 (Loyalty portal, lane
portal-loyalty-api) is not on INT yet, so this change is its own commit set (contract, slices, generated files) to be rebased
onto 1.13.0 when the lead says it has landed; the `info.version` line and the changelog position are the only conflict points.
Everything is additive: new optional members, one new request enum value on a record, one new response enum value, new operations,
new code-list items. Spec: part A s7 and part B s6 of `docs/requests/sr-manual-build-2026-11-part-a.md` / `-part-b.md`.
Records: D-SV1-01 to 12 (DECISIONS.md).

## Members and values

| change | where | rows |
|---|---|---|
| `VisitClosePayload.zero_sale_reason_code` (string or null, code list `zero_sale_reason`; only with `is_zero_sale`, an active item on the business date) | sync record `visit_close` | SRM-A-40 |
| code list key `zero_sale_reason` (`CodeListKey`), four items: stock_available, outlet_closed_during_visit, absence_of_owner, insufficient_cash (Bangla labels = the Q12 defaults) | bundle code lists, admin code lists | SRM-A-40 |
| `VisitClosePayload.call_photo_uuid` (uuid or null) and `call_photo_skip_reason_code` (code list `call_photo_skip`: camera_unavailable, permission_denied, storage_full, other; exclusive with the uuid; an active item) | sync record `visit_close` (the photo is taken after the visit opened, whose payload is immutable) | SRM-A-27 |
| code list key `call_photo_skip` (`CodeListKey`); config key `cfg.sale.call_photo_required` (default true; global and role scope) | bundle code lists, config | SRM-A-27 |
| `MediaPurpose` value `outlet_call` (a `media_meta` of this purpose must have `ref_type` `visit`) | `media_meta`, `createMediaUploadUrls` | SRM-A-27 |
| `SalesSubmitDigest.business_totals` (new schemas `SubmitBusinessTotals`, `SkuQtyBase`: `outlets_visited`, and `sold`, `stock_issued`, `qc` as lists of {sku_id, qty_base} (per SKU: a category mixing 10s and 20s packs cannot be converted back to packets), and `promotion_mtk`; sold = live memos' sale lines, stock_issued = issue + adjustment) | `GET /v1/sync/totals` digest | SRM-B-25 |
| `UpdateCheck.notes_en`, `notes_bn` (copies of `latest.notes_*`; the release admin already stores them) | `GET /v1/app/update-check` | SRM-A-09 |
| `JourneyCall.status` value `repeat_sale` | `getTeamJourney` | part B s6 programmes |
| seeded codes: `edit_reason` wrong_sku_qty, promotion_issue, and the so far unseeded wrong_quantity, wrong_price_type (labels for reports; the server never checked the code, only the pattern); `task_type` poor_brand_visibility | bundle code lists | SRM-B-15, SRM-B-49 |
| `getTeamDeviceOtps`: `GET /v1/supervisor/device-otps` (TSO only; schemas `TeamDeviceOtp`, `TeamDeviceOtpPage`) | TSO app | SRM-A-06 |
| `getCallPhotoReview`: `GET /v1/supervisor/call-photos` (AMO, TSO, DMO, WM, support, admin; schemas `CallPhotoReviewRow`, `CallPhotoReviewPage`) | portal / AMO | SRM-A-27 |

Behaviour without a schema change (stated in the changelog): `info` outlet requests accept `cluster_id` (OutletProposal already
had it; the server now allows and applies it, same zone as the outlet); a `route_add` without a cluster keeps the outlet's
cluster instead of failing the approval with 400 (SRM-B-42); a second `new` shop request of the same user for the same shop (same
normalised name and mobile, 7 days) is acked `duplicate` with the first request's id (SRM-B-35).

## oasdiff

`JourneyCall.status` (`getTeamJourney`) and the code-list key (`CodeListKey`: `GET`/`PUT /v1/admin/code-lists`, `GET /v1/sync/bundle`,
`GET /v1/sync/delta`) gain a value in a response enum: nine `response-property-enum-value-added` lines from
`tools/ci/contract-breaking.sh`; this file is the request that lets the push pass. The new `MediaPurpose` value `outlet_call` is on a
request the phones of this build send; oasdiff does not flag it. No shipped client reads the new response values: the AMO journey
parser maps an unknown status to `new_sale` (the AMO lane adds `repeat_sale`), and a phone ignores a code list it does not know.

## Database (V0173 to V0175)

V0173: `app.visit.zero_sale_reason_code`, `call_photo_uuid`, `call_photo_skip_reason_code` (nullable, CHECKs NOT VALID), `visit_immutable`
recreated with `zero_sale_reason_code` write-once, `media.purpose` and `code_list.list_key` CHECKs widened NOT VALID, the code-list
rows. V0174: the four VALIDATE statements. V0175: dw view contract 1.7 to 1.8 (`dw.fact_visit.zero_sale_reason_code`,
`call_photo_uuid`; `dw.v_visit.zero_sale_reason_code` and `call_capture_taken`, a boolean: a media id never appears in a reuse view).
V0158 to V0162 are the Loyalty lane's (on INT); V0152 took dw contract 1.6 and V0162 1.7, so this train is 1.8.

## Moved to train 2

(e) the bundle `clusters` section and the cluster-creation record with AMO verification and portal approval (needs a new record type,
a table and the portal page); (h) `outlet_sku_stats` (`ads_hundredths` stated in base units, the phone converts by pack_factor; previous day, SDLW) bundle section with its formulas; the
survey report key `posm-survey` with its dw view (SRM-B-08); the late-rows-after-Sales-Submit flag (SRM-B-30: a route-day column
counted at ingest and a member of the route-day status read). The lead asked for the last two in this file; they are declared here
and ship in train 2 with the same contract version (additive). The targets bundle sections belong to the targets-kpi lane.
