# Contract v1.3 queue (lead; additive only; applied as one batch by an Opus agent, then an independent check)

Rule (docs/26 s2): lanes never edit `contract/openapi.yaml`; they file `docs/requests/<lane>-<name>.md`, the lead rules, approved items wait here and are applied together (one regeneration of slices, DTOs and web types, one oasdiff run). Apply the batch when 5 items are queued or a lane is blocked on one, whichever is first. Each item: ruling date, source request, exact shape.

**Batch checklist (lesson of v1.2, lead 2026-10-07):** v1.2 passed `:shared:contract:jvmTest`, lint, oasdiff and the drift checks, and still turned INT's Android job red because a hand-written Android contract test (`AuthDtoContractTest`, `LoginResponse.password_change_token`) was not updated. After regenerating slices, WireDtos and web types the agent MUST also run every dependent suite before pushing: `:shared:contract:jvmTest`, `:android:core-network`, `:android:core-sync`, `:android:core-database`, `:android:core-geo`, `:android:dpc`, `:backend:app`, `:backend:auth`, `:backend:masterdata`, `:backend:config`, `:backend:analytics`, web typecheck and vitest, and fix or request the fix of every test that pins the old shape (grep `android/` and `backend/` for the changed schema names). A DTO type change is a breaking change for dependents even when the YAML is additive. Tell each owning lane in one message listing the changed schemas.

| # | Ruled | Source | Shape (all optional or additive) |
|---|---|---|---|
| 1 | 2026-10-07 | web-admin-acting-scope.md | `PUT /v1/admin/users/{id}/scope`: each node entry gets optional `valid_to` (date, Asia/Dhaka business date); returned on read; the server treats the node as out of scope after that date (the `app.user_scope` daterange already supports it). |
| 2 | 2026-10-07 | web-admin-outlet-web-request.md | `POST /v1/outlet-requests` (web source): body `OutletProposal` plus client `request_uuid`; idempotent by `request_uuid` (same service as the phone's `outlet_change_request` record); allowed for TSO and DMO within own scope; ADMIN and SUPERADMIN keep direct edit. |
| 3 | 2026-10-07 | web-admin-sub-channel-ids.md | `CodeItem` gets read-only `id` (integer) for the code lists that outlets reference (sub_channel at least), so forms can show a select with Bangla labels. |
| 4 | 2026-10-07 | contract v1.2 independent check (R18) | Description-only: `password_change_token` has `aud` `aron-pwchange`; a web `ok` from change-password delivers the refresh token via `Set-Cookie: aron_rt`; the enrolment marker precedence (top-level wins over nested `status`); unknown vs clean for missing `root_hints` and marker (docs/24 s14a R18). Bump info.version to 1.3.0 for the batch. |
| 5 | 2026-10-07 | web-admin-sku-image.md | `SkuWrite` and `SkuPatch` get `image_asset_id` (Uuid or null); `Sku` gets `image_url` (uri or null), like `GiftWrite.image_asset_id` and `Gift.image_url`; the 300 KB image limit stays with the asset upload. |
| 6 | 2026-10-07 | web-admin-definition-reads.md | The admin reads round-trip into the write schemas: `SurveyAdmin.questions[]` returns `key`, `required`, `show_if_key`, `show_if_bool`, `photo`, `option_codes`; `RubricAdmin.criteria[]` returns `key`; `ContentAdmin` returns `asset_id`; one `answer_type` enum for rubric read and write (use the enum the phone-facing bundle schema already uses; if they differ, keep every existing value readable). All additive. |
| 7 | 2026-10-07 | web-admin-tutorial-asset-id.md | `TutorialAdmin` returns `asset_id` (so a PATCH can keep the current file). |
| 8 | 2026-10-07 | web-admin-feedback-filters.md | `listFeedback` gets optional query parameters `category_code` (pattern of `FeedbackPayload.category_code`) and `status` (`FeedbackStatus`); newest first. |

Not queued (decided no contract change): password policy hint (web keeps the contract text, R14 note), F-API-083/037/065 (R16).

## Next batch (v1.4), queued after v1.3 (committed on lane/lead-contract-v1-3)
| # | Ruled | Source | Shape |
|---|---|---|---|
| 9 | 2026-10-07 | backend-core-change-password-statuses.md | `changePassword` documents the responses the server really returns: 403, 409 and 503 in addition to 204/200, 400, 401, 429 (reasons in the request file); additive. Apply with the next batch of 5 items or when a lane is blocked. |
