# Infra lane status

Updated 2026-10-06 (Day 2).

## Done

- **Lead request (CI concurrency).** `ci.yml`: push runs are grouped by branch and commit and never cancelled; only
  `pull_request` runs cancel their predecessor. A dependency-free `changes` job gates every heavy job, so a docs-only
  push builds nothing heavy (a `docs/24` edit still re-runs the JVM drift tests). The Android job uploads the three
  debug APKs (`aron-debug-apks-<sha>`) on every successful run.
- **N-012 Azure IaC** (`infra/`, Bicep, one resource group, no subscription scope). Front Door (Standard dev / Premium
  prod) with WAF; zone-redundant Container Apps environment (workload profiles, VNet); ACR; PostgreSQL 16 Flexible
  Server with zone-redundant HA, PITR, geo-redundant backup, built-in PgBouncer, private access, optional read replica
  (with its own PgBouncer); Storage (media + bundles, user-delegation SAS only, Event Grid BlobCreated to a queue);
  Key Vault (RBAC) with the secret names of docs/24; Log Analytics + App Insights; alerts of docs/24 s13.1; budget on
  the group; four least-privilege managed identities; diagnostic settings everywhere. Dev (pilot) and prod (full
  fleet) parameter files. One command: `infra/deploy.sh <env>`.
- **N-013 CI/CD.** `ci.yml`: all actions pinned by SHA; new `web` job (web lane request) and `infra` job
  (`infra/validate.sh`); `deploy` job calls `deploy.yml` only on a push to the integration branch after every check
  passed. `deploy.yml`: branch and settings preflight (clear error when Azure is not set up), OIDC sign-in, scope check,
  then `infra/deploy.sh` (lock, ordering guard, infra, Key Vault seeding, images, migrate job first, apps, Private
  Link, smoke test through Front Door, budget check). No GitHub concurrency group (it would cancel pending CI runs).
- Bootstrap hardened: environment `azure-dev` accepts deployments only from the integration branch; the pull-request
  federated credential is removed; RBAC Administrator is limited by an ABAC condition to the eight data-plane roles.

Validation: `infra/validate.sh` passes (bicep build + lint with warnings as errors, 4 parameter files plus the
empty-variables case, 28 template/workflow checks, actionlint + shellcheck). Mutation run of the checker's list:
see the commit message. **Not validated (no Azure credentials in this session):** what-if, SKU/zone/quota
availability, ARM acceptance of property values, RBAC propagation timing, Private Link approval, budget API, Event
Grid delivery, the end-to-end smoke test. The first deploy run proves them.

## Checker findings (fresh reviewer, 13 confirmed, all fixed)

1 empty `ARON_BUDGET_AMOUNT` broke the first deploy; 2 push deploys blocked by the missing migrate role and the
exiting worker (now detected and skipped with a warning); 3 superseded-skip plus path filter could drop a deploy
(replaced by an ancestor check against the deployed commit); 4 job concurrency cancelled pending runs, push group
lacked the ref; 5 environment had no branch policy; 6 pull-request federated credential; 7 unconditional RBAC
Administrator; 8 scope check never tried another group; 9 replica without PgBouncer; 10 fixed budget start date;
11 Key Vault name reuse after a teardown (`ARON_NAME_SUFFIX`); 12 checks did not guard HA, geo backup, PgBouncer,
zone redundancy, budget notifications, WAF mode, workflow conditions (now asserted on compiled resources and exact
workflow expressions); 13 docs/24 edits skipped the JVM drift tests. Lower-confidence items also handled: infra stage
skipped when unchanged, password generation only on NotFound, self-repair of the deployer's Key Vault role, replica
deferred on the first prod deploy, Private Link waits for every origin, preflight inside the environment job, trimmed
alert e-mails.

## Day 2 (lead re-prioritisation, docs/27 scope change: nothing to provision for deferred programmes)

1. **Backend image and deploy** (`docs/requests/infra-backend-runtime.md`): new CI job **Container images** builds the
   backend and web images and boots them without Azure (`infra/scripts/image-smoke.sh`): migrate twice against
   PostgreSQL 16, api `/v1/health` and `/v1/health/ready` with `X-Aron-Api: 1`, worker still running after 15 s, web
   serving. The deploy now also needs this job. Locally (no Docker daemon here) the same sequence ran on the Gradle
   distribution against PostgreSQL 16 with a **non-superuser** database owner: 11 migrations then 0 (idempotent,
   `btree_gist` created), health and readiness 200 with the marker, worker alive, api healthy with the App Insights
   agent attached and no connection string. `ARON_BUILD` = image tag (commit) is now set for every backend role.
2. **btree_gist** (`docs/requests/db-azure-btree-gist.md`): already allow-listed since N-012
   (`azure.extensions = BTREE_GIST,PGCRYPTO,PG_STAT_STATEMENTS,POSTGIS`, asserted by the template checks). Flyway's
   `public` schema: the migrate job connects as the server admin login, which owns the `aron` database it creates;
   verified locally that a non-superuser owner can run all migrations. Residual risk until the first Azure run: the
   ownership of an ARM-created database (if it fails, the migrate job stops the deploy before any app changes).
3. **Web standalone and CI job** (`infra-web-standalone.md`, `web-infra-ci-job.md`): done on Day 1 (web job in
   `ci.yml`, web image, session secret from Key Vault); the image now also boots in CI.

### What waits for `infra/bootstrap-azure.ps1` (sponsor, once)

Everything that needs a subscription; nothing else does:
- the first `deploy` run (infra, Key Vault seeding, images to ACR, migrate job in Azure, apps, Front Door smoke test);
- what-if of `main.bicep` (run by `validate.sh` only when `az` is signed in);
- the N-012 acceptance checks that need Azure: scope check against `rg-aron-scope-probe`, budget exists;
- SKU, zone and quota availability in Southeast Asia, ARM acceptance of every property, RBAC timing, Private Link
  approval (prod), Event Grid delivery.
Until then CI is green without Azure except the deploy job, which stops at its preflight with "Azure is not set up for
this repository" (no Azure call is made).

## Second review (fresh re-checker on the fixes, 6 confirmed, all fixed)

1 `infra/deploy.sh` committed without the executable bit (validate.sh now checks modes); 2 web session secret seeding
died on SIGPIPE under pipefail (now `openssl rand`); 3 the migrations gate grepped for a string the new backend no
longer has (source sniffing removed: migrations always run; the backend now has the migrate role, a blocking worker
and `/v1/health/ready`, so dev probes readiness too); 4 `deploy.sh` was not shellchecked; 5 the infra-skip ignored
changed GitHub variables (now compares compiled parameters with the last deployment); 6 checks did not pair app
secret references with their creation, nor guard the replica PgBouncer loop. Also: scope probe uses an existing empty
group `rg-aron-scope-probe` created by the bootstrap; password read keeps stderr apart; bootstrap creates the
conditional role assignment before deleting an old one and checks every `az` call. Mutation run: 14 of 14 mutations
of guarded properties fail validation.

## In progress

- Nothing. Next (Day 2+ per backlog): observability workbooks, load-test infrastructure (Day 6).

## Blocked / waiting

- First real deploy: needs the sponsor to re-run `infra/bootstrap-azure.ps1 -AlertEmails ...` (README "Sponsor steps").
- Backend runtime items 1 to 3 (migrate role, readiness, blocking worker) and the Front Door id have landed; items 5 to 8 of
  `docs/requests/infra-backend-runtime.md` (queue, client id, FCM placeholder, metric names) remain open.

## Requests filed

- `docs/requests/infra-backend-runtime.md` (backend lane; architect for docs/24 s6.4).
- `docs/requests/infra-web-standalone.md` (web lane; mostly already satisfied by web/: standalone output and lockfile
  exist; the session secret is now provided from Key Vault).

## Decisions taken (infra)

| Date | Decision | Reason |
|---|---|---|
| 2026-10-05 | Two Bicep stages: `main.bicep` (infra) and `apps.bicep` (apps, run twice: migrate job, then services) | Migrations must finish before any app revision changes; apps need an image that only exists after the registry |
| 2026-10-05 | Database credentials as complete JDBC URLs in Key Vault: pooled (6432) for api, direct (5432) for worker and migrate | Flyway and run-once jobs take session advisory locks that PgBouncer transaction mode breaks |
| 2026-10-05 | App uses the PostgreSQL admin login in Phase 1 | Per-role logins need db-lane migrations and a rotation design; logged as a follow-up |
| 2026-10-05 | Front Door Standard in dev, Premium + Private Link in prod | Managed WAF rules and Private Link are Premium only; Premium costs about USD 330/month more |
| 2026-10-05 | Premium SSD v2 for PostgreSQL in both environments | Supports HA + geo backup in Southeast Asia (Learn); IOPS independent of size; no autogrow, so a storage alert at 70 % |
| 2026-10-05 | Geo-redundant backup and zone redundancy on in dev too | Both are creation-time only; dev hosts the Day 6 failover drill |
| 2026-10-05 | No GitHub concurrency for deploys; an Azure-side wait plus an ancestor check | GitHub cancels pending jobs in a group, which would cancel CI runs |
| 2026-10-05 | Blob-created events via Event Grid to a storage queue drained by the worker | The contract has no webhook endpoint for it; a queue needs no endpoint validation and is at-least-once |
| 2026-10-05 | Temporary source-based detection of the missing migrate role and placeholder worker in `deploy.sh` | Keeps push deploys green on Day 1 without hiding the gap (warnings in every run) |
