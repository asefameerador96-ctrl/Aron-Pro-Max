# Contract v1.8 queue (lead; applied as one batch after 1.7.0, D-CONTRACT-BATCH-01)

Lanes needing a new value in a response enum (a new `ProblemCode`, a new role or level, ...) or a new member, operation or tag add one row here (same shape as `contract-v1.7-queue.md`); the lead applies the batch in one approved head with every generator and mirror (checklist in `contract-v1.7-queue.md`). Until a row ships, the server keeps the documented interim answer (for a new code: the nearest existing code with a stable detail). Opened 2026-10-23 when 1.7.0 was applied.

| # | Ruled | Source | Shape |
|---|---|---|---|
| 1 | 2026-10-23 (lead, backend-authz #8 checker note 1) | D-AUTHZ-1-7-WIRING-01 | `getUserAccess` (GET /v1/admin/users/{id}/access) description names `ERR_AUTHZ_ROLE_ABOVE_GRANT` for an ADMIN reading another ADMIN's access (the server answers it since the 1.7 wiring; description-only, additive). |
| (next) | | | Expected: mdm's packages catalogue endpoint (docs/status/mdm.md), when mdm writes it up. |
