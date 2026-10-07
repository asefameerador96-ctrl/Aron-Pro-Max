# Contract v1.3 queue (lead; additive only; applied as one batch by an Opus agent, then an independent check)

Rule (docs/26 s2): lanes never edit `contract/openapi.yaml`; they file `docs/requests/<lane>-<name>.md`, the lead rules, approved items wait here and are applied together (one regeneration of slices, DTOs and web types, one oasdiff run). Apply the batch when 5 items are queued or a lane is blocked on one, whichever is first. Each item: ruling date, source request, exact shape.

| # | Ruled | Source | Shape (all optional or additive) |
|---|---|---|---|
| 1 | 2026-10-07 | web-admin-acting-scope.md | `PUT /v1/admin/users/{id}/scope`: each node entry gets optional `valid_to` (date, Asia/Dhaka business date); returned on read; the server treats the node as out of scope after that date (the `app.user_scope` daterange already supports it). |
| 2 | 2026-10-07 | web-admin-outlet-web-request.md | `POST /v1/outlet-requests` (web source): body `OutletProposal` plus client `request_uuid`; idempotent by `request_uuid` (same service as the phone's `outlet_change_request` record); allowed for TSO and DMO within own scope; ADMIN and SUPERADMIN keep direct edit. |
| 3 | 2026-10-07 | web-admin-sub-channel-ids.md | `CodeItem` gets read-only `id` (integer) for the code lists that outlets reference (sub_channel at least), so forms can show a select with Bangla labels. |
| 4 | 2026-10-07 | contract v1.2 independent check (R18) | Description-only: `password_change_token` has `aud` `aron-pwchange`; a web `ok` from change-password delivers the refresh token via `Set-Cookie: aron_rt`; the enrolment marker precedence (top-level wins over nested `status`); unknown vs clean for missing `root_hints` and marker (docs/24 s14a R18). Bump info.version to 1.3.0 for the batch. |

Not queued (decided no contract change): password policy hint (web keeps the contract text, R14 note), F-API-083/037/065 (R16).
