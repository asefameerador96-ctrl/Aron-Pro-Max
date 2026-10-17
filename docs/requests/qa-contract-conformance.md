# qa: contract conformance findings (N-014 / AUD-TP-2), 2026-10-17

**From:** qa lane. **To:** lead (contract owner), backend-core, backend-admin, backend-reports.

`backend/app` now has `ContractConformanceTest`. It drives every operation the production wiring serves, as six seeded callers, and validates every answer against `contract/openapi.yaml` with a JSON Schema 2020-12 validator (status declared, body valid against the declared schema, problem `code` in ProblemCode). The run made 971 calls and validated 897 bodies. It found the 28 deviations below. They are frozen in `backend/app/src/test/resources/contract-conformance-baseline.txt`, a list that may only shrink: a new deviation fails CI. **When you fix one, delete its line from the baseline in the same commit**, or the test fails as stale. The full report of a run is `backend/app/build/contract-conformance.txt`.

For each finding, decide whether the code or the contract is wrong. The column "likely fix" is qa's reading, not a ruling.

## A. Served routes the contract does not have (lead: add the operation, or the owning lane removes the route)

| Route | Likely owner | Note |
|---|---|---|
| `POST /v1/feedback` | backend-admin | contract has only GET /v1/feedback and the status PATCH |
| `POST /v1/leave` | backend-admin | contract has GET and the decision POST under /v1/leave/{leave_uuid} |
| `POST /v1/visit-plans` | backend-admin | contract has only GET |
| `GET /v1/routes/{id}/assignments` | backend-admin | no such path in the contract |

## B. Status codes the server answers that the operation does not declare (lead: declare them; each is a legitimate refusal)

| Key | Server answer |
|---|---|
| `GET /v1/admin/code-lists 403` | ERR_FORBIDDEN "code lists is not available to this role" |
| `GET /v1/app/home 403` | ERR_FORBIDDEN "node is outside your reach" |
| `GET /v1/sync/bundle/page 403` | ERR_FORBIDDEN "the bundle is for the field apps" |
| `GET /v1/sync/delta 400` | ERR_VALIDATION "since must be a delta cursor" |
| `GET /v1/sync/delta 403` | ERR_FORBIDDEN "the bundle is for the field apps" |
| `PATCH /v1/admin/clusters/{} 409` | ERR_MASTER_IN_USE |
| `POST /v1/admin/assets 503`, `POST /v1/admin/content 503`, `POST /v1/media/sas 503`, `POST /v1/support/pda-upload 503` | ERR_SERVICE_UNAVAILABLE "blob storage is not configured in this environment" |
| `POST /v1/admin/enrolment-tokens 404` | ERR_NOT_FOUND "no published release of this flavour to provision" |
| `POST /v1/auth/mfa/enrol 403` | ERR_FORBIDDEN "MFA is for web sign-in only" |
| `POST /v1/client-errors 403` | ERR_FORBIDDEN "phones report errors as app_error records" |
| `POST /v1/support/pda-upload 403` | ERR_FORBIDDEN "PDA to Support is a phone call" |
| `PUT /v1/devices/me/push-token 403` | ERR_FORBIDDEN "only an enrolled phone registers a push token" |

Suggested contract change: add `'403': { $ref: '#/components/responses/Forbidden' }` (and the others) to these operations. A phone or web client generated from the contract would otherwise treat these answers as unknown.

## C. Response bodies that do not validate (code or contract wrong)

| Key | Validator message (first lines) | Likely fix |
|---|---|---|
| `GET /v1/admin/outlets/{} 200 schema` | `placement_history`, `location_history`, `id`, `code` "not evaluated" | **Contract bug (lead):** `OutletDetail` is `allOf: [Outlet, {...}]`, but `Outlet` itself ends with `unevaluatedProperties: false`. Any instance carrying `placement_history` fails the `Outlet` branch, so `OutletDetail` can never validate. Drop `unevaluatedProperties: false` from `Outlet` (keep it on `OutletDetail`), or build both from an open `OutletCore`. The same pattern may exist in other `allOf` compositions over a closed base. |
| `GET /v1/admin/code-lists 200`, `GET /v1/sync/bundle 200`, `PUT /v1/admin/code-lists/{} 200` | `items/*/attrs/roles` and `attrs/applies_to` are arrays; the schema allows only scalars (JsonScalar) | Contract (lead) if attrs may hold arrays; else backend-admin (code-list attrs) and backend-core (bundle code lists). The phone parses the bundle, so decide before the field apps rely on it. |
| `GET /v1/admin/config/keys 200` | `items/46/default_value` is an object (`{"SR": ...}`) where ConfigValueJson allows a scalar or an array | backend-admin (config) or the contract's ConfigValueJson (a role map) |
| `GET /v1/reports 200` | `items/*/area` outside the enum [sales, field_force, outlet, day_control, geo, finance, ops] | backend-reports (or extend the enum) |
| `POST /v1/admin/rubrics 201`, `POST /v1/admin/surveys 201` | answers carry `status`, `rubric_id`, `version`, `kind`, `valid_from`, `valid_to`, which the declared schema does not allow | backend-admin, or the contract adds them |
| `PUT /v1/admin/permissions/roles/{} 202 schema` | `changes/*/value` is an array of objects; ConfigValueJson rejects it | backend-admin (config permissions) or the contract |

## How to reproduce

```
export ARON_TEST_PG_URL='jdbc:postgresql://localhost:5432/aron_test?user=aron&password=aron'
./gradlew :backend:app:test --tests '*ContractConformanceTest'
cat backend/app/build/contract-conformance.txt
```
