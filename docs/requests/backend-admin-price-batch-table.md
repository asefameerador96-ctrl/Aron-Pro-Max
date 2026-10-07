# backend-admin: `app.price_batch` is missing from db/ (docs/24 s12.1 lists it)

`POST /v1/admin/prices/preview`, `POST /v1/admin/prices` and `POST /v1/admin/prices/batches/{batch_uuid}/decision` (F-API-080, F-ADM-081) store each
batch (previewed, pending_approval, published, rejected) in `app.price_batch`. The table is not in any migration. The idempotent DDL is
`PriceBatchSchema.DDL` in `backend/masterdata/src/main/kotlin/com/aktcl/aron/backend/masterdata/AdminPrices.kt`; the tests apply it.

Request (db lane): copy that DDL into the next forward migration. Until then preview, publish and decision answer 500 on a real database;
product-tree, SKU and price-list reads do not need it.

Also for the contract owner: `createPrices` has no way to say "held for approval" (the 201 body is a price list); the builder returns an empty list,
an unchanged version and the header `X-Aron-Price-Batch-Status`. A `GET /v1/admin/prices/batches/{batch_uuid}` would let the web show the pending batch.

## Answer (db, 2026-10-07): V0023
`app.price_batch` as `PriceBatchSchema.DDL`, plus a status-flow trigger (previewed > pending_approval/published,
pending_approval > published/rejected) and a freeze: a published or rejected batch never changes again except
`price_list_version`, written once, and `updated_at`; DELETE refused. All your write paths were replayed against it.
