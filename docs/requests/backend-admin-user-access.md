# Request: user access (portal level, module grants, brand scope) — web-admin wave 4

Lane: web-admin. For: backend-authz (owns the claims) / backend-admin (owns the admin routes). Binding spec: `docs/34` s1 (D-ROLES-02).

The users page (`/admin/users`) and the access page (`/admin/user-access/{id}`) are built and tested against the mock in `web/mock/custom.ts`.
Nothing in the contract carries the shapes yet, so the portal uses provisional types (`web/src/lib/admin/access-grants.ts`, marked `REQUEST`).
`web/tests/requests/user-access-contract.test.ts` is an `it.fails` pair: it passes while the contract lacks them and turns red the day they land.

## 1. Role enum
`Role` gains `NATIONAL` and `BRAND` (docs/34 s1; `TOP` retired, existing TOP users migrate). `UserWrite.role`, `UserPatch.role`, `User.role`, the `role` list filter, token claim `role`.
SR and AMO have no portal login (web login refuses with a clear message): the portal only labels them "app only".

## 2. `GET /v1/admin/users/{id}/access`  (ADMIN_PORTAL_ROLES read; SUPERADMIN only sees ADMIN/SUPERADMIN targets as today)
```yaml
UserAccess:
  type: object
  required: [user_id, role, version, modules, brand_ids]
  properties:
    user_id: {$ref: Id}
    role: {$ref: Role}
    version: {type: integer, minimum: 1}        # also the ETag ("<version>")
    modules:                                     # ADMIN only; only granted modules appear
      type: object
      propertyNames: {enum: [masterdata, routes, outlets, products, prices, users, targets, credentials, mdm, reports, config, data_entry, indent, leaderboard, daily_tracking]}  # 15 modules (lead, 2026-10-22)
      additionalProperties: {type: string, enum: [r, w]}
    brand_ids: {type: array, items: {$ref: Id}}  # BRAND only; product-tree brand nodes
```
Unknown user: 404.

## 3. `PUT /v1/admin/users/{id}/access`  (SUPERADMIN only; `If-Match: "<version>"`; audited, entity `user_access`)
Body `UserAccessWrite`: `{ modules }` **or** `{ brand_ids }` (exactly one) plus `change_reason` (ChangeReason). Rules the server enforces (the portal mirrors them):
`modules` only for an ADMIN, `brand_ids` only for a BRAND user, otherwise 400 `role_mismatch` at the member pointer; unknown module name 400 `unknown_member`; level not `r|w` 400 `invalid`;
empty `brand_ids` 400 `required` (a brand user without a brand would see nothing); unknown or non-brand node id 400 `unknown`; stale `If-Match` 412. Response: the new `UserAccess`.
The new grants reach the user's next token (`modules` and `brands` claims, docs/34 s1); a revoked module answers 403 on its API.

## 4. Menu
The portal sidebar for ADMIN is built from the `modules` claim: a module without a grant is not listed. Which menu entry belongs to which module is the lead's call (a table in `docs/34` s1 would do); web-admin keeps today's menu until `Me` carries `modules`.
