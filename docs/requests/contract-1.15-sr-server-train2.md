# Contract 1.16.0: SR manual build, sr-server train 2 (lane sr-server, 2026-11-13)

The file is named for the number the lead first gave (1.15.0); the lead then ordered **1.16.0** because the Loyalty lane holds 1.15.0 (it has not landed
on INT). Version 1.16.0 sits on 1.14.0 (INT f9708b594). Approval REQUESTED from the lead as **D-LEAD-CONTRACT-116-01** (not approved until the lead says so).
Everything is additive: new optional members, new schemas, a new report key, a new config key. Spec: part A s7 and part B s6 of
`docs/requests/sr-manual-build-2026-11-part-a.md` / `-part-b.md`; records D-SV2-02 to D-SV2-07 (DECISIONS.md).

## Members and values (push B1)

| change | where | rows |
|---|---|---|
| `Bundle.clusters` (new schema `BundleCluster`: `cluster_id`, `name`, `route_id`, `route_name`): the clusters of the SR's OWN routes, one row per cluster and route, taken from the active outlets of the route; reach = the SR's routes from the token; empty for AMO / TSO | `GET /v1/sync/bundle` (not carried by `GET /v1/sync/delta`: they stay as the day's full bundle gave them until the next full bundle) | SRM-B-35, SRM-B-40 |
| `Bundle.outlet_sku_stats` (new schemas `OutletSkuStat`: `outlet_id`, `previous_day`, `items`; `OutletSkuStatItem`: positional `[sku_delta, prev, ads, sdlw]`, see the shape section below): sales history per outlet and SKU of the SR's own routes | `GET /v1/sync/bundle` | SRM-A-29 |
| report key `posm-survey` (answers per outlet, date, question, photo flag) | `ReportKey`, `POST /v1/reports/posm-survey/query` | SRM-B-08 |
| `RouteDayState.late_rows_after_sales_submit` (integer or null): memos and due collections captured after the same user's Sales Submit record (its own `captured_at`: phone clock against phone clock) and stored before the Final Submit; DERIVED from the stored rows, so it is one number under replay and any arrival order | supervisor route-day reads (`getLoginSubmitStatus` family); not sent in the bundle or the day-state read | SRM-B-30 |
| config key `cfg.sale.journey_repeat_sale_enabled` (bool, default false, global) | config | journey |

## Units and formulas (stated once)

`outlet_sku_stats` is in **base units** (sticks / pieces), like every server quantity; the phone converts to packets with the SKU's `pack_factor`.
`ads` (hundredths) is an integer: average quantity per CALENDAR day x 100 (250 = 2.5 base units per day). ONE rule (D-SV2-05, the lead's ruling on push B): ADS = the SKU's sale quantity in the 30 calendar
days before the bundle date x 100 / the ELAPSED calendar days of that window (30, or the days since the outlet's first selling memo when the outlet is younger), rounded half up; the phone shows it as
"per day until the next visit". A *selling day* is a day the outlet had a live memo (active, with lines, not voided; sale lines only, `drp_reward`/`promo_free`/`free_sample` are left out, the rule of the
Sales Submit digest); it decides `previous_day` (the outlet's most recent selling day before the bundle date, none = no entry) and the first memo of an outlet, not the divisor. `prev` is the SKU's quantity on it
(0 when the SKU was not sold then). `sdlw` is the quantity on bundle date minus 7 days. Only pairs with a sale in the window are listed. Example: 19 sticks over 3 visits in 30 days is 0.63 a day (63), not 6.33.
**Owner question (open):** the field may prefer "per SELLING day" (the same 19 sticks over 3 visits is 6.33 a day: what a visit usually sells). Both are one line of SQL; the contract names only the elapsed-day rule until the owner chooses.

## Journey `repeat_sale`

`getTeamJourney` classifies by the phone's rule (`shared/rules` `JourneyTimeline.build`): `edited_sale` first; else a sold call at an outlet already sold earlier the same
day is `repeat_sale`; else `new_sale`; else `no_sale`. **Shipped off**: `cfg.sale.journey_repeat_sale_enabled` is false, so the value is never emitted until the AMO build that
knows it is the minimum app version (train 2 rule of `contract-1.14-sr-manual.md`); the portal switches it on then.

## oasdiff

No existing member, enum value or operation changes: the new members are optional, `ReportKey` gains a value on a response/request enum the report operations already
carry (`POST /v1/reports/{key}/query` path parameter): the lead's request file is this one. Shipped phones ignore unknown bundle members.

## Database (V0186 to V0188)

V0186: `app.route_day.late_rows_after_sales_submit` (int NOT NULL DEFAULT 0, CHECK NOT VALID), `cfg.sale.journey_repeat_sale_enabled`. V0187: VALIDATE. V0188: dw view
contract 1.8 -> 1.9: `dw.v_route_day.late_rows_after_sales_submit` (dropped and recreated so `is_effective` stays last), new `dw.v_survey_response`.

## Cluster creation (push B2; D-SV2-04)

| change | where | rows |
|---|---|---|
| `OutletRequestType` value `cluster_create`: an `outlet_change_request` record (`request_type: cluster_create`, no `outlet_id`, `proposed: {name}`, the SR's route on the ENVELOPE `route_id`, `fix` as for any request); `proposed.name` 1 to 120 characters, trimmed and whitespace-collapsed | sync record `outlet_change_request`, `OutletRequest`, `OutletRequestSummary`, list filters | SRM-B-35 |
| `OutletProposal.cluster_request_uuid` (uuid): a new-shop request names its own pending `cluster_create` request when the cluster does not exist yet; used at approval when `cluster_id` is absent | `OutletChangeRequestPayload.proposed` | SRM-B-35 |
| `OutletRequest.resulting_cluster_id` (integer or null): the live cluster an approved `cluster_create` request made | outlet request reads | SRM-B-35 |
| record outcome code `cluster_name_exists` (`rejected`, not retryable; Bangla and English text in `reason_texts`): the name repeats a cluster of the route's zone, or another open cluster request of it (case-insensitive); the detail names which; nothing is stored | acks of `POST /v1/sync/batch` | SRM-B-35 |

Flow: the SR files the proposal offline (the phone shows it as pending and may offer it for a new shop through `cluster_request_uuid`); the AMO verifies it (the existing
`outlet_request_verification` record, or the web verify); the DMO / admin approves; the approval creates an active cluster of the route's zone (default **live after approval**, part B owner
question 3) and `Bundle.clusters` lists it for the route at the next bundle. A rejected / lapsed / discarded request makes no cluster; a shop that named it keeps its request, and its approval
answers 400 (`unknown_cluster_request`) or 409 (`cluster_request_pending`) so the approver sees why. The AMO's verification of a `cluster_create` ignores sub-channel and geo class.

**For the Android lanes**

(S2; lead ruling 5 of 2026-11-13) `android/core-database` `CaptureRepository` (line about `OutletRequestType.NEW` and `outletId == null`) asserts "no outlet only for `new`"; a `cluster_create`
also has no outlet. The phone side is theirs; this lane touched no Android file. A shipped phone never sends `cluster_create` and ignores `Bundle.clusters` / `outlet_sku_stats`. Mirrored DTO members (nullable, default null): `RouteDayState.late_rows_after_sales_submit`, `OutletRequestType.CLUSTER_CREATE` in `shared/contract`.

## Database (V0189, V0190)

V0189: `app.outlet_change_request.created_cluster_id` (FK to `app.cluster`, NOT VALID), `request_type` CHECK widened, the outlet rule CHECK `(request_type IN ('new','cluster_create')) = (outlet_id IS NULL)`,
`sync_rejected.code` CHECK widened by `cluster_name_exists`, the immutability trigger recreated with `=created_cluster_id` (write-once). V0190: the four VALIDATE statements. V0180 and V0181 are the
Loyalty lane's; V0182 is unused here.

## What the phone shows, and what it must do (S2) — the lead's conditions of 2026-11-13

- **`cluster_name_exists` (ack `rejected`, final).** Nothing is lost: the server keeps the whole record in `sync_rejected` (helpdesk-visible) and a resend of the same client uuid gets the same final answer. The phone marks its
  local cluster proposal "already exists", shows the Bangla / English text of `reason_texts.cluster_name_exists` ("A cluster with this name already exists; use it instead.") and offers the existing cluster; **nothing needs re-entry
  except choosing that cluster**. A new shop that pointed at the proposal through `cluster_request_uuid` must be re-pointed to the existing cluster by `cluster_id` before it is sent (or, if it was already sent, the approver
  corrects it: see below). Same client uuid = replay only; two DIFFERENT requests for one name in a zone: the first is kept, the second gets this outcome.
- **A shop that names a cluster request (`cluster_request_uuid`) is always accepted by `POST /v1/sync/batch`** (never lost, never parked: the reference is resolved at approval, not at ingest). The 409 `cluster_request_pending`
  / `cluster_request_refused` is the **portal's answer to the approver** on `POST /v1/outlet-requests/{uuid}/approve`; the phone never sees it and has nothing to retry or remember (offline-first). The approver approves the
  cluster request first (then the shop approves in the new cluster) or, when the cluster request was refused, gives the shop another cluster (409 `cluster_request_refused` says so).
- **Reach:** a proposal is judged on the zone of the route on its envelope, from the approver's token (another division's AMO / DMO gets 404).
- **The phone's pending-cluster list:** show a proposal as pending until `my_outlet_requests` / the request read says `approved` (then `resulting_cluster_id` is the cluster id, and it arrives in `Bundle.clusters` at the next bundle), `rejected`, `lapsed` or `discarded`.

## `outlet_sku_stats` shape and budget (lead ruling 2 of 2026-11-13)

`Bundle.outlet_sku_stats` is one compact entry per outlet: `{outlet_id, previous_day, items: [[sku_delta, prev, ads, sdlw], ...]}`: positional integer quadruples, items ascending by SKU id, `sku_delta` = the SKU id in the first item and the difference from the
previous item's id in every later one (decode by a running sum); a zero is written 0; pairs with all three figures zero are not sent (by construction every listed SKU has `ads > 0`). Member meanings and units (all BASE units): `prev` = quantity on `previous_day`; `ads` = average quantity per selling day x 100 (integer hundredths); `sdlw` =
quantity on the same weekday last week; `previous_day` = the outlet's last selling day in the 30-calendar-day window. Budget (docs/04): at most 150 KB raw and 20 KB gzip at 70 outlets x 40 SKUs dense; measured **50 KB raw / 17.6 KB gzip** with every pair present and uniform random
figures (prev and sdlw up to 200, ads up to 20000 hundredths); asserted in `SrServerTrain2ApiTest.theWorstCaseStatsAndClustersSectionsStayInsideTheirBudgets` (the `clusters` maximum of 600 rows: 61 KB raw / 5.5 KB gzip, also asserted).
This replaces the first flat shape (365 KB raw / 35 KB gzip). Without SKU-id deltas the same data is 55 KB / 20.4 KB gzip (just over), which is why the ids are delta-coded.

## Review round on push B (push B3; D-SV2-05 to D-SV2-07)

All additive on 1.16.0; the wire members are in the openapi changelog. Phones (Android lanes) need: `BundleDelta.clusters` / `clusters_truncated` / `outlet_sku_stats` / `outlet_sku_stats_truncated` (nullable: null = unchanged, a list replaces the phone's whole section),
`Bundle.clusters_truncated` / `outlet_sku_stats_truncated` (booleans, absent = false) and, for the portal, `OutletRequestApproveRequest.cluster_id`.

- **R1 late rows.** Derived from the `day_submit` record's own `captured_at` and the stored rows of the same route-day and user (see D-SV2-05); tests through the real ingest.
- **R2 deltas.** A delta carries a changed `clusters` / `outlet_sku_stats` whole (per-section content hash kept with the snapshot's fingerprint), omits an unchanged one; an outlet move stays one outlet upsert; unchanged data keeps the same bundle version.
- **R3 approval.** `cluster_id` on approval (new shop, cluster move), 409 guards `cluster_required` / `cluster_request_pending` / `cluster_request_refused` that say to give it; the trail records who chose which cluster. At ingest a `cluster_request_uuid` must be the same user's `cluster_create` in the same zone, otherwise it is dropped and flagged `cluster_dropped`; `cluster_id` wins over the link.
- **Database V0191.** `app.norm_cluster_name` and the unique index `cluster_zone_name_norm_key (zone_id, norm(name))`; `dw.v_survey_response` joins the visit on the business date, is `pii: personal` and is read by bi_reader without `answer_text`. `dw.view_contract_version()` stays 1.9 (columns unchanged).
- **ADS.** One rule, per elapsed calendar day (see "Units and formulas"); owner question: "per selling day" as the alternative.
- **Caps.** 600 clusters and 400 outlets of stats, with the truncated flags (order: route, name, id; outlet id).

## Push B4 (the Opus re-pass on 4ca4e76ca; D-SV2-08)

- **For the Android lanes.** (1) The online Sales Submit (`POST /v1/day/sales-submit`) body carries the new optional `captured_at`: "the phone's clock at the moment the rep pressed Sales Submit", same format and same clock as the record envelopes. Send it: without it the server stamps its own time, `RouteDayState.late_rows_anchor` reads `server` and a supervisor is told the late-row count may be off by the phone's clock skew. (2) `late_rows_after_sales_submit` is measured per user against the newest non-voided Sales Submit cycle of the route-day, so a second submit can lower it. (3) Nothing else on the wire changed in B4.
- **Late-row classification.** `app.late_row_fact` (V0191): one row per memo, due collection or online Sales Submit, written once under the route-day lock: `stored_closed` (the row was stored while the route-day was Final Submitted: it counts under `late_rows_after_final`, never under `late_rows_after_sales_submit`, whatever a recount or reopen does) and `anchor_clock`.
- **Recount triggers.** A stored memo, due collection or route-day Sales Submit, an admin data void of a route-day, a Final Submit reopen; platform interface `DayInputsChanged` (implemented by `DayStates.recountLateAfterSubmit`).
- **Cluster names.** A name held by an inactive cluster: the SR's cluster request is stored; the approval answers 409 `cluster_name_inactive` (reactivate it). A same-approver retry with another `cluster_id` is 409 `already_approved_other_cluster`.

