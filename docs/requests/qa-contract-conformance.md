# qa: contract conformance findings (N-014 / AUD-TP-2), 2026-10-17

**From:** qa lane. **To:** lead (contract owner), backend-core, backend-admin, backend-reports.

> **Handover (lead-contract, 2026-10-17): all sections are closed.** The three baselines are empty: no contract
> deviation, no unsatisfiable success schema, and every operation the production wiring serves answers 2xx at least once
> (173 of 173), so every success body is validated. What a successor maintains:
> - **A new operation** answers 2xx with the generated call, or gets an entry in `ContractConformanceTest.FX`. An `Fx`
>   says only what the generator cannot know: `body`/`path`/`query`/`headers` edits; `after` (producers that run first;
>   a read that needs a write moves to the write phase); `capture` (keep an id or token from a 2xx answer in `Ctx`,
>   read it with `Ctx.need`, which fails fast); `proof` (`deviceProof(...)`, `BATCH_PROOF`, `REFRESH_PROOF`; the seeded SR
>   phone holds `ApiWorld.phoneKey`); `raw` (multipart); `prepare` (a login or an issue once before the calls). A fixture's
>   body is itself checked against the request schema (`fixture-invalid`). Rows no served operation creates go in
>   `ApiWorld.conformanceSeed` (only the contract suite's world loads them). Blobs go through `FakeBlobs`
>   (`Wiring.production(blobPorts = ...)`, tests only).
> - **A new finding** is fixed in the code or, when the server is right, in the contract (oasdiff additive, or a version
>   bump plus a `contract-*.md` request the lead approves); never added to a baseline. The report
>   `backend/app/build/contract-conformance.txt` lists each caller's refusal under any operation without a 2xx.
> - **A phone batch in any ApiWorld test** sends `X-Device-Proof` (`world.batchProof(device, gzBody)`): the seeded phone
>   and its mirrored copies hold the harness key.
> - **Date bomb (routed to qa):** the harness's seeded config overrides (`cfg_value.effective_from = now() - 1 day`) are
>   activated against the database's real time while the harness clock is fixed at 2027-01-03; after real date
>   2027-01-04 they no longer apply (rate limits, MFA roles, page threshold). A clock-relative date was tried and did not
>   take effect; it needs a deliberate fix.

`backend/app` now has `ContractConformanceTest`. It drives every operation the production wiring serves, as six seeded callers, and validates every answer against `contract/openapi.yaml` with a JSON Schema 2020-12 validator (status declared, body valid against the declared schema, problem `code` in ProblemCode). The run makes 970 calls and validates 902 bodies. It found the deviations below (28 operation and status pairs). They are frozen in `backend/app/src/test/resources/contract-conformance-baseline.txt` (96 keys). A schema finding is keyed per normalised error (`<path> <keyword>[ <property>]`), so a new error on an operation already listed still fails. The list may only shrink: a new deviation fails CI. **When you fix one, delete its lines from the baseline in the same commit**, or the test fails as stale. The full report of a run is `backend/app/build/contract-conformance.txt`.

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
| `POST /v1/admin/rubrics 201`, `POST /v1/admin/surveys 201` | answers carry `status`, `rubric_id`, `version`, `kind`, `valid_from`, `valid_to`, which the declared schema does not allow | **Contract (lead) first**: the same closed-`allOf` defect as `OutletDetail` (section D), then backend-admin if fields remain |
| `PUT /v1/admin/permissions/roles/{} 202 schema` | `changes/*/value` is an array of objects; ConfigValueJson rejects it | backend-admin (config permissions) or the contract |

## D. 2xx response schemas no server can meet (lead: contract)

Each of these rejects even an instance generated from itself. They use `allOf` over a branch that is closed with `additionalProperties: false` or `unevaluatedProperties: false`, so the properties of the other branch are always refused. The test is `everySuccessSchemaOfTheContractIsSatisfiable`; the list is `contract-unsatisfiable-baseline.txt` and may only shrink.

`GET /v1/admin/content`, `GET /v1/admin/outlets/{id}`, `GET /v1/admin/rubrics`, `GET /v1/admin/surveys`, `GET /v1/admin/targets`, `GET /v1/admin/tutorials`; `PATCH` and `POST` of `/v1/admin/content`, `/rubrics`, `/surveys` and `/tutorials`.

**Fix:** keep the closing keyword only on the outermost schema, and leave the shared bases open, as `Outlet` is used inside `OutletDetail`.

## E. Operations validated only on their refusals (qa, with the owning lanes)

63 of 169 served operations never answered 2xx to any of the six callers. Their success bodies are therefore not yet checked against the contract. The list is `contract-no-success-baseline.txt` and may only shrink. Most need a real precondition: a pending config change, a device-proof token, an enrolment release, an outlet request, a cursor, an MFA code, or a blob store. qa adds fixtures in `ContractConformanceTest.FIXTURES` (login is the first) and works the list down. A lane that adds a fixture for its own operation removes the line in the same commit.

## How to reproduce

```
export ARON_TEST_PG_URL='jdbc:postgresql://localhost:5432/aron_test?user=aron&password=aron'
./gradlew :backend:app:test --tests '*ContractConformanceTest'
cat backend/app/build/contract-conformance.txt
```

## Status 2026-10-17 22:00 UTC (lead-contract, lane/lead-contract)

- **A, B, C, D: done.** `contract-conformance-baseline.txt` and `contract-unsatisfiable-baseline.txt` are empty. A: the
  four routes are contract operations (`submitFeedback`, `applyLeave`, `saveVisitPlan`, `listRouteAssignees`). C: the
  report area was a code fix (backend-reports); attrs, config role maps and the null `default_value` are contract
  widenings approved by the lead (contract 1.3.3, `docs/requests/contract-conformance-widening.md`).
- **E: 63 -> 0 (lane/lead-contract, after the lead's go, 2026-10-17).** Every operation the production wiring serves is
  validated on at least one success answer. How (all in the harness, `ContractConformanceTest.FX` and the opt-in
  `ApiWorld(conformance = true)` seed):
  - *Chained ids*: an operation runs after its producers (`Fx.after`) and reads what their answers created
    (`Fx.capture`): outlet request create, verify, approve; content, rubric, survey and tutorial PATCH on the created id;
    route-assignment end on the created cover (the run no longer ends sr1001's seeded primary); enrolment token revoke
    and enrol; feedback triage; task cancel; price decision after the publish; delta after the bundle.
  - *Seed rows no served operation creates*: a second outlet request, an export job, a risk signal, yesterday's
    route-day and the dw dimensions, a pending C3 config change, a published SR release, a throwaway AMO with a zone
    scope, a tutorial asset, quarantine items (a held duplicate of the paper memo with its line, one to discard, one
    resolved for the delta), a stored photo, 501 tasks over the page threshold (set to its floor, 500).
  - *Devices and auth*: the seeded SR phone carries a real P-256 key (`ApiWorld.phoneKey`), so batch, push token and
    refresh are proven with their device proofs (the scope-leak and sync-fault harnesses send batch proofs too); a proof
    phone for policy, nonce and status; a dev-token enrolment with a self-built attestation chain; bind with an unbound
    SR, phone and an admin-issued OTP; MFA enrol and verify for dmo1001 (DMO is MFA-required in this world only).
  - *Blobs*: `Wiring.production(blobPorts = ...)` (tests only) takes an in-memory store, so the SAS, upload and read
    paths answer 2xx; production is unchanged (Azure when ARON_BLOB_ACCOUNT is set, else 503).
  - *Code defect found on the way*: `POST /v1/admin/enrolment-tokens` sent unset optional QR provisioning extras as JSON
    null (contract: optional, not nullable; the setup wizard could read "null"). Fixed in backend devices with a test.
  - The report (`backend/app/build/contract-conformance.txt`) lists each caller's refusal under any operation without a
    2xx, so a new gap is a read away.
