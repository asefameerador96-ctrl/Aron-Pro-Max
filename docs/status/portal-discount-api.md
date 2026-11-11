# Status: lane portal-discount-api (Lane A), started 2026-11-11

Branch `lane/portal-discount-api` from INT `claude/wonderful-thompson-k6ejnf` 05a50235b. Push only that branch; no pull request. Scope: `docs/status/portal-discount-brief.md` (on `lane/lead-docs-1022`); its s8 defaults are the answers until the owner says otherwise. The field does not change (memo offer line zero, bundle `offers` empty).

## Done (train 1, schema and contract; D-DISC-API-01, -02)
- V0152 (schema, views, comments, grants) and V0153 (validate the status CHECK).
- Contract 1.12.0: three new operations, new schemas, regenerated slices and web types.
- `DiscountPortalV0152Test` (db), DwViewContractTest history "1.6", regenerated docs/data-dictionary.md and docs/dw-view-contract.md.

## Not done
- Train 2: Ktor routes, `discounts` permission module, audit, tests (scope matrix, idempotency).
- oasdiff breaking check not run (tool not installed here); squawk not run locally (CI runs it).

## HANDOVER
Next: train 2 per the lane brief (AdminPrices.kt pattern, AdminModules.kt `discounts` module off `targets`). Local DB tests need a PostgreSQL 16 and `ARON_TEST_PG_URL=jdbc:postgresql://localhost:5432/postgres?user=aron&password=aron` (start with `service postgresql start`, create a SUPERUSER role `aron`). Run one Gradle at a time (two runs collide on db/build/kotlin).
