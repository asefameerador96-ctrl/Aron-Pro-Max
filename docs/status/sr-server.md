# Lane sr-server (SV): SR manual build, server side

Branch `lane/sr-server`, from INT `claude/wonderful-thompson-k6ejnf` (ab78d0d4). Brief: `docs/requests/sr-manual-lane-brief.md` (lead docs branch). Request file: `docs/requests/contract-1.14-sr-manual.md`. Records: D-SV1-01 to 08.

## Train 1 (T1: sync, money, security)

| item | rows | state |
|---|---|---|
| (a) zero-sale reason: `zero_sale_reason_code`, code list `zero_sale_reason`, ingest + server re-check, dw | SRM-A-40 | done (needs the Android reason picker, lane S4) |
| (b) call photo: `call_photo_uuid`, `call_photo_skip_reason_code`, media purpose `outlet_call`, review read `getCallPhotoReview` | SRM-A-27 | done (needs the camera step, lane S4) |
| (c) Sales Submit business totals in the digest | SRM-B-25 | done (needs lane S5 to show them) |
| (d) outlet requests: `info` cluster_id, `route_add` keeps cluster, duplicate new-shop protection | SRM-B-35, 40, 42 | done server side (app must also reuse the request uuid on reopen: lane S2) |
| (e) bundle `clusters`, cluster-creation record | SRM-B-33, 43, 44 | train 2 (new record type, table, portal approval) |
| (f) edit reasons, `poor_brand_visibility`, `repeat_sale` | SRM-B-15, 49, journey | done |
| (g) update-check notes, TSO device-OTP read | SRM-A-06, 09 | done |
| (h) `outlet_sku_stats` (ADS, SDLW, previous day) | SRM-A-29 | train 2 |
| survey report key; late-rows-after-submit flag | SRM-B-08, 30 | train 2 |

## What the Android lanes can use now

- `visit_close.zero_sale_reason_code`: values `stock_available`, `outlet_closed`, `owner_absent`, `insufficient_cash`; labels come from the bundle code list `zero_sale_reason`.
- `visit.call_photo_uuid` (+ `media_meta` purpose `call_start`, `ref_type` `visit`, `client_uuid` = that UUID) or `call_photo_skip_reason`.
- `GET /v1/sync/totals` -> `digest.business_totals` (base units per category; convert for display).
- `edit_reason` codes `wrong_sku_qty`, `promotion_issue`; `task_type` `poor_brand_visibility`; `GET /v1/supervisor/device-otps` for the TSO app.
