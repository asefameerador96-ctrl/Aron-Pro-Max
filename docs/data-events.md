# Domain events

Generated from `app.domain_event_type` by `tools/data-dictionary/render.sh` (db lane); do not edit by hand.
The outbox `app.domain_event` is read in `id` order by the aggregate projector and Phase 2 consumers (docs/24 s6.3, s12.5).

## Versioning rules (V0017)

- Every outbox row has `event_type` and `payload_version` (default 1); the insert trigger refuses a pair that is
  not a catalogue row and a payload that is not a JSON object. A version's required keys are enforced once its
  `enforce_required` is on (a db migration after the producer sends them, V0018); until then the schema is the
  target shape and consumers read the source row through `source_client_uuid`.
- Payloads carry ids, codes, counts and amounts, never names, phone numbers, NIDs or coordinates.
- Adding an optional key keeps the version. Removing or renaming a key, changing its type or meaning, or making
  a key required adds a new version (a db migration; ask through docs/requests). A published schema never changes.
- Producers write the newest version that is not deprecated; consumers handle every version that is not
  deprecated and ignore keys they do not know.
- Events are thin notices (D-DATA-OUTBOX-ROLE-01): a consumer that needs the figures reads the source row or
  the `dw` views, and the data lake reads only the views. **Status** `live` means a backend producer writes the
  type today; `planned` means it is catalogued with no producer yet, so no consumer may wait for it (V0117).

| Event | Version | Aggregate | aggregate_id | Producer | Status | Required keys enforced |
|---|---|---|---|---|---|---|
| [`day_exception.decided`](#day_exceptiondecided-v1) | 1 | `day_exception` | `day_exception.client_uuid` | backend:masterdata | planned | not yet |
| [`due.collected`](#duecollected-v1) | 1 | `due_collection` | `due_collection.client_uuid` | backend:sync | planned | not yet |
| [`leave.decided`](#leavedecided-v1) | 1 | `leave_application` | `leave_application.client_uuid` | backend:masterdata | planned | not yet |
| [`memo.created`](#memocreated-v1) | 1 | `memo` | `memo.client_uuid` | backend:sync | live | not yet |
| [`memo.superseded`](#memosuperseded-v1) | 1 | `memo` | `memo.client_uuid` | backend:sync | planned | not yet |
| [`memo.voided`](#memovoided-v1) | 1 | `memo` | `memo.client_uuid` | backend:sync | live | not yet |
| [`outlet.changed`](#outletchanged-v1) | 1 | `outlet` | `outlet.id` | backend:masterdata | planned | not yet |
| [`risk_signal.changed`](#risk_signalchanged-v1) | 1 | `risk_signal` | `risk_signal.id` | backend:masterdata, backend:sync | planned | not yet |
| [`route_day.state_changed`](#route_daystate_changed-v1) | 1 | `route_day` | `route_day.id` | backend:sync | planned | not yet |
| [`stock.moved`](#stockmoved-v1) | 1 | `stock_movement` | `stock_movement.client_uuid` | backend:sync | live | not yet |
| [`support_ticket.commented`](#support_ticketcommented-v1) | 1 | `support_ticket` | `support_ticket.client_uuid` | backend:masterdata | planned | not yet |
| [`support_ticket.created`](#support_ticketcreated-v1) | 1 | `support_ticket` | `support_ticket.client_uuid` | backend:masterdata | planned | not yet |
| [`support_ticket.status_changed`](#support_ticketstatus_changed-v1) | 1 | `support_ticket` | `support_ticket.client_uuid` | backend:masterdata | planned | not yet |
| [`target.revised`](#targetrevised-v1) | 1 | `target_set` | `target_set.id` | backend:analytics | planned | not yet |
| [`tracking_action.created`](#tracking_actioncreated-v1) | 1 | `tracking_action` | `tracking action uuid (audit_log.entity_id)` | backend:analytics | planned | not yet |
| [`visit.closed`](#visitclosed-v1) | 1 | `visit` | `visit.client_uuid` | backend:sync | live | not yet |
| [`zone_day.final_submitted`](#zone_dayfinal_submitted-v1) | 1 | `zone_day` | `<zone_id>:<business_date>` | backend:sync | planned | not yet |
| [`zone_day.reopened`](#zone_dayreopened-v1) | 1 | `zone_day` | `<zone_id>:<business_date>` | backend:sync | planned | not yet |

## day_exception.decided v1

A day exception was approved or rejected; one event per affected route. Introduced in V0036; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `from_date` | string (date) | yes |  |
| `route_id` | integer | yes |  |
| `status` | string | yes | one of approved, rejected |
| `to_date` | string (date) | yes |  |

## due.collected v1

A due collection was accepted; the route-day and outlet dues are recomputed. Introduced in V0036; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `amount_mtk` | integer | yes | collected amount, integer milli-taka |
| `business_date` | string (date) | yes |  |
| `outlet_id` | integer | yes |  |
| `route_id` | integer or null | no | null when the collection has no route; consumers fall back to source_client_uuid |

## leave.decided v1

A leave application was approved or rejected on the web. The reason and the decision note are not in the payload. Introduced in V0134; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `decided_by` | integer | yes |  |
| `leave_uuid` | string (uuid) | yes | leave_application.client_uuid |
| `status` | string | yes | one of approved, rejected |
| `user_id` | integer | yes | the applicant |
| `days` | integer | no |  |
| `from_date` | string (date) | no |  |
| `leave_type_code` | string | no | code list leave_type |
| `to_date` | string (date) | no |  |

## memo.created v1

A memo was accepted (ingest or web entry). The projector adds it to the day aggregates of its route, outlet and SKUs. Introduced in V0017; live.

| Key | Type | Required | Description |
|---|---|---|---|
| `memo_kind` | string | yes | memo.memo_kind |
| `memo_uuid` | string (uuid) | yes | memo.client_uuid |
| `net_mtk` | integer | yes | net amount, milli-taka |
| `outlet_id` | integer | yes |  |
| `route_id` | integer | yes |  |
| `user_id` | integer | yes | the SR who sold |
| `acting_for_user_id` | integer or null | no |  |
| `due_mtk` | integer | no |  |
| `gross_mtk` | integer | no |  |
| `line_count` | integer | no |  |
| `paid_mtk` | integer | no |  |
| `visit_uuid` | string or null (uuid) | no | memo.visit_client_uuid |

## memo.superseded v1

An edit replaced a memo: the superseded memo leaves the day figures and the superseding memo counts once; both business dates are recomputed. Introduced in V0117; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `business_date` | string (date) | yes | business date of the superseded memo |
| `memo_uuid` | string (uuid) | yes | the superseded memo |
| `route_id` | integer or null | yes | null for a memo without a route (contract 1.9 MemoView.route_id) |
| `superseded_by_uuid` | string (uuid) | yes | the memo whose supersedes_client_uuid is memo_uuid (app.memo.superseded_by_client_uuid) |
| `superseding_business_date` | string or null (date) | no | business date of the superseding memo when it differs |

## memo.voided v1

A memo was voided (data-void tombstone or Final Submit void). The projector removes it from the day aggregates. Introduced in V0017; live.

| Key | Type | Required | Description |
|---|---|---|---|
| `memo_uuid` | string (uuid) | yes |  |
| `route_id` | integer | yes |  |
| `voided_at` | string (date-time) | yes |  |
| `reason_code` | string or null | no | code_list void_reason |

## outlet.changed v1

An outlet was created, edited, moved, merged or closed. Names, phones and coordinates are not in the payload. Introduced in V0017; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `change` | string | yes | one of created, updated, location_confirmed, route_changed, merged, closed, reopened |
| `outlet_id` | integer | yes |  |
| `fields` | array | no | outlet columns that changed |
| `merged_into_id` | integer or null | no |  |
| `route_id` | integer or null | no |  |
| `zone_id` | integer or null | no |  |

## risk_signal.changed v1

A risk signal was raised (masterdata risk rules) or reviewed (sync risk_review records); the subject's day figures and integrity views are recomputed. Introduced in V0036; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `business_date` | string (date) | yes |  |
| `code` | string | yes | risk_signal.code |
| `subject_id` | integer | yes |  |
| `subject_type` | string | yes |  |
| `route_id` | integer or null | no |  |
| `status` | string or null | no | risk_signal.status after the change |
| `user_id` | integer or null | no |  |

## route_day.state_changed v1

A route-day moved state (logged in, in field, synced, submitted, final submitted, voided). Introduced in V0017; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `from_state` | string or null | yes |  |
| `route_day_id` | integer | yes |  |
| `route_id` | integer | yes |  |
| `to_state` | string | yes |  |
| `acting_user_id` | integer or null | no |  |
| `submit_cycle` | integer | no |  |

## stock.moved v1

A stock ledger row was accepted (issue, return, adjustment, damaged, short). Introduced in V0017; live.

| Key | Type | Required | Description |
|---|---|---|---|
| `kind` | string | yes | stock_movement.kind |
| `movement_uuid` | string (uuid) | yes |  |
| `qty_base` | integer | yes | signed quantity in base units |
| `route_id` | integer | yes |  |
| `sku_id` | integer | yes |  |
| `user_id` | integer | yes |  |

## support_ticket.commented v1

A comment was added to an open support ticket by the reporter (sync) or the desk (web). The comment text is not in the payload. Introduced in V0134; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `actor_is_support` | boolean | yes |  |
| `event_uuid` | string (uuid) | yes | support_ticket_event.client_uuid |
| `ticket_uuid` | string (uuid) | yes |  |
| `actor_user_id` | integer or null | no |  |
| `attachment_count` | integer | no |  |

## support_ticket.created v1

A support ticket was accepted from the phone (record support_ticket) with its INC number and default priority. Title and description are not in the payload. Introduced in V0134; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `business_date` | string (date) | yes |  |
| `issue_type_code` | string | yes | code list ticket_issue_type |
| `priority` | string | yes | one of p1, p2, p3, p4 |
| `requester_user_id` | integer | yes |  |
| `ticket_no` | string | yes | INC number |
| `ticket_uuid` | string (uuid) | yes | support_ticket.client_uuid |
| `has_affected_user` | boolean | no | true when the ticket names another user (affected_username) |
| `requester_role` | string | no |  |
| `source` | string | no | one of quick, manual |

## support_ticket.status_changed v1

A support ticket moved status (start, resolve, cancel, close review or desk close). Resolution notes and cancel reasons are not in the payload. Introduced in V0134; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `from_status` | string | yes | one of new, in_progress, resolved, closed, cancelled |
| `ticket_uuid` | string (uuid) | yes |  |
| `to_status` | string | yes | one of new, in_progress, resolved, closed, cancelled |
| `actor_is_support` | boolean | no |  |
| `actor_user_id` | integer or null | no |  |
| `event_uuid` | string or null (uuid) | no | support_ticket_event.client_uuid of the change |
| `resolution_code` | string or null | no | code list ticket_resolution_code, on a resolve |

## target.revised v1

A target revision was committed (Phase 2 target engine; deferred programme, docs/27). Introduced in V0017; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `revision_id` | integer | yes |  |
| `revision_no` | integer | yes |  |
| `target_set_id` | integer | yes |  |
| `months` | array | no |  |

## tracking_action.created v1

A supervisor recorded a daily-tracking action on a route-day; the notify module nudges the route's TSO and AMO. Introduced in V0022; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `business_date` | string (date) | yes |  |
| `notified_user_ids` | array | yes | TSO and AMO users nudged |
| `route_id` | integer | yes |  |
| `note` | string or null | no | free text (to be removed in v2: personal data does not belong in the outbox) |

## visit.closed v1

A visit was accepted with its geo verdict. The projector counts calls, strike rate and geo validity. Introduced in V0017; live.

| Key | Type | Required | Description |
|---|---|---|---|
| `outlet_id` | integer | yes |  |
| `route_id` | integer | yes |  |
| `user_id` | integer | yes |  |
| `verdict` | string | yes | visit.verdict |
| `visit_kind` | string | yes | visit.visit_kind |
| `visit_uuid` | string (uuid) | yes |  |
| `distance_m` | number or null | no |  |
| `fix_is_mock` | boolean or null | no |  |
| `planned` | boolean | no |  |

## zone_day.final_submitted v1

A zone-day was final-submitted by a TSO (app or web) or by the midnight AutoFinalSubmitJob (via job); its route-days lock and later rows are held or refused (D-TSO-MAN-31). Introduced in V0134; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `business_date` | string (date) | yes |  |
| `final_submit_uuid` | string (uuid) | yes | final_submit.client_uuid |
| `via` | string | yes | one of app, web, job |
| `zone_id` | integer | yes |  |
| `not_uploaded_route_count` | integer | no |  |
| `route_count` | integer | no |  |
| `submit_seq` | integer | no | reopened submits before this one |
| `submitted_by` | integer or null | no | null for the midnight job |

## zone_day.reopened v1

A final-submitted zone-day was reopened by an admin within the reopen window; its aggregates are recomputed. The reason is not in the payload. Introduced in V0134; planned.

| Key | Type | Required | Description |
|---|---|---|---|
| `business_date` | string (date) | yes |  |
| `final_submit_uuid` | string (uuid) | yes | the reopened final_submit.client_uuid |
| `reopened_by` | integer | yes |  |
| `zone_id` | integer | yes |  |
| `reopen_uuid` | string or null (uuid) | no | final_submit.reopen_client_uuid |
| `route_day_count` | integer | no |  |
