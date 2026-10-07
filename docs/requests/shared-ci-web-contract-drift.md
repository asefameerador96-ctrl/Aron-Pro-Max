# Request: CI check that web/src/contract/openapi.d.ts matches contract/openapi.yaml (from shared, 2026-10-07)

**What.** In the web CI job, before `typecheck`, run `npm run gen:contract` and fail on `git diff --exit-code web/src/contract/openapi.d.ts`.

**Why.** `web/package.json` has `gen:contract` and the generated file is committed, but ci.yml only runs `typecheck`; a contract change that
is not regenerated passes CI and the web client silently drifts from the Kotlin mirror (which `ContractDriftTest` already guards). N-002 acceptance
("a deliberate field rename breaks both builds") needs the regenerate-and-diff step.

**Owner.** infra (`.github/`).
