# Contract 1.14.0: SR manual build, sr-server train 1 (lane sr-server, 2026-11-12)

Approval REQUESTED from the lead as **D-LEAD-CONTRACT-114-01** (not approved until the lead says so). Version 1.14.0 sits on 1.13.1 (the
Loyalty portal's description-only patch, which lands on INT first; the `info.version` line, the `AmoManualContractTest` pin and the changelog
position are the conflict points: 1.14.0 is the version, 1.13.1 keeps its entry below mine). Everything is additive: new optional members,
new values of response enums, new operations, new code-list items. Spec: part A s7 and part B s6 of
`docs/requests/sr-manual-build-2026-11-part-a.md` / `-part-b.md`. Records: D-SV1-01 to 13 (DECISIONS.md).

## Members and values

| change | where | rows |
|---|---|---|
| `VisitClosePayload.zero_sale_reason_code` (string or null, code list `zero_sale_reason`; only with `is_zero_sale`; a code that is not an active item on the business date is stored as sent and the visit flagged, never rejected) | sync record `visit_close` | SRM-A-40 |
| code list key `zero_sale_reason` (`CodeListKey`), four items: stock_available, outlet_closed_during_visit, absence_of_owner, insufficient_cash (Bangla labels = the Q12 defaults) | bundle code lists, admin code lists | SRM-A-40 |
| `VisitClosePayload.call_photo_uuid` (uuid or null) and `call_photo_skip_reason_code` (code list `call_photo_skip`: camera_unavailable, permission_denied, storage_full, other; exclusive with the uuid; a code not active on the business date is stored as sent and flagged, never rejected) | sync record `visit_close` (the photo is taken after the visit opened, whose payload is immutable) | SRM-A-27 |
| code list key `call_photo_skip` (`CodeListKey`); config key `cfg.sale.call_photo_required` (default false until the app build that sends the photo is out, then the portal switches it on: part A Q8 owner default is mandatory; global and role scope) | bundle code lists, config | SRM-A-27 |
| `MediaPurpose` value `outlet_call` (a `media_meta` of this purpose must have `ref_type` `visit`) | `media_meta`, `createMediaUploadUrls` | SRM-A-27 |
| `SalesSubmitDigest.business_totals` (new schemas `SubmitBusinessTotals`, `SkuQtyBase`: `outlets_visited`, and `sold`, `stock_issued`, `qc` as lists of {sku_id, qty_base} (per SKU: a category mixing 10s and 20s packs cannot be converted back to packets), and `promotion_mtk`; sold = live memos' sale lines, stock_issued = issue + adjustment) | `GET /v1/sync/totals` digest | SRM-B-25 |
| `CallPhotoReviewRow.reason_code_unlisted` (boolean; the visit_close carried a code the list did not hold); `OutletRequest.possible_duplicate_of` (uuid or null) and the `OutletRequest.flags` values `possible_duplicate`, `cluster_dropped` | review read, outlet request reads | SRM-A-40, SRM-A-27, SRM-B-35, SRM-B-40 |
| `UpdateCheck.notes_en`, `notes_bn` (copies of `latest.notes_*`; the release admin already stores them) | `GET /v1/app/update-check` | SRM-A-09 |
| `JourneyCall.status` value `repeat_sale` | `getTeamJourney` | part B s6 programmes |
| seeded codes: `edit_reason` wrong_sku_qty, promotion_issue, and the so far unseeded wrong_quantity, wrong_price_type (labels for reports; the server never checked the code, only the pattern); `task_type` poor_brand_visibility | bundle code lists | SRM-B-15, SRM-B-49 |
| `getTeamDeviceOtps`: `GET /v1/supervisor/device-otps` (TSO only; schemas `TeamDeviceOtp`, `TeamDeviceOtpPage`) | TSO app | SRM-A-06 |
| `getCallPhotoReview`: `GET /v1/supervisor/call-photos` (AMO, TSO, DMO, WM, support, admin; schemas `CallPhotoReviewRow`, `CallPhotoReviewPage`) | portal / AMO | SRM-A-27 |

Behaviour without a schema change (stated in the changelog): `info` outlet requests accept `cluster_id` and `route_add` keeps its cluster; a
`route_add` without a cluster keeps the outlet's cluster instead of failing the approval with 400 (SRM-B-42). An unknown or retired
optional label is never a reason to lose a field record (lead ruling, offline-first): a `visit_close` with such a code is stored as sent and
flagged; an `info` request whose `cluster_id` is gone or in another zone keeps its changes, the cluster is dropped from the stored proposal
and the request flagged `cluster_dropped` (SRM-B-40); a look-alike `new` shop request (same user, same normalised name and mobile, within 7
days) is stored as its own request, acked `accepted` and flagged `possible_duplicate` for the approver, and only the same client uuid is a
replay (SRM-B-35). `getTeamDeviceOtps` and the admin OTP read answer `Cache-Control: no-store`. `cfg.sale.call_photo_required` ships false.

## oasdiff

`JourneyCall.status` (`getTeamJourney`) and the code-list key (`CodeListKey`: `GET`/`PUT /v1/admin/code-lists`, `GET /v1/sync/bundle`,
`GET /v1/sync/delta`) gain a value in a response enum, and `OutletRequest.flags` gains two values: `response-property-enum-value-added`
lines from `tools/ci/contract-breaking.sh`; this file is the request that lets the push pass. The new `MediaPurpose` value `outlet_call` is on a
request the phones of this build send; oasdiff does not flag it. Shipped clients: a phone ignores a code list it does not know; the shipped
AMO journey parser (android/feature-amo) maps an unknown `JourneyCall.status` to NO_SALE, not to `new_sale`. **Train 2 rule:** the server's
journey classifier must NOT emit `repeat_sale` until the AMO build that knows it is the minimum app version, or the AMO lane ships first; train
1 changes only the enum, the classifier in `TeamApi` is unchanged and still never emits it.

## Database (V0173 to V0175)

V0173: `app.visit.zero_sale_reason_code`, `call_photo_uuid`, `call_photo_skip_reason_code`, `reason_code_unlisted` (nullable, CHECKs NOT VALID),
`visit_immutable` recreated with those columns write-once, `media.purpose`, `code_list.list_key` and `outlet_request_event.event` (the server
flag kinds `flag_cluster_dropped`, `flag_possible_duplicate`) CHECKs widened NOT VALID, the code-list rows, `cfg.sale.call_photo_required`
(default false). V0174: the five VALIDATE statements. V0175: dw view contract 1.7 to 1.8 (`dw.fact_visit.zero_sale_reason_code`,
`call_photo_uuid`; `dw.v_visit.zero_sale_reason_code` and `call_capture_taken`, a boolean: a media id never appears in a reuse view). V0158 to
V0162 and V0170 to V0172 are the Loyalty lane's; V0152 took dw contract 1.6 and V0162 1.7, so this train is 1.8 (the Loyalty train 2 does not
bump it). Not done in train 1: `reason_code_unlisted` is not in the dw views (train 2 may add it).

## Moved to train 2

(e) the bundle `clusters` section and the cluster-creation record with AMO verification and portal approval (needs a new record type,
a table and the portal page); (h) `outlet_sku_stats` (`ads_hundredths` stated in base units, the phone converts by pack_factor; previous day, SDLW) bundle section with its formulas; the
survey report key `posm-survey` with its dw view (SRM-B-08); the late-rows-after-Sales-Submit flag (SRM-B-30: a route-day column
counted at ingest and a member of the route-day status read). The lead asked for the last two in this file; they are declared here
and ship in train 2 with the same contract version number (additive; the changelog names them only when they ship). The targets bundle sections belong to the targets-kpi lane.
