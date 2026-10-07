# infra/: Aron on Azure (N-012) and how it is deployed (N-013)

Everything Aron runs on in Azure is Bicep in this folder. It deploys **into one existing resource group** and nothing
else: no subscription-scope resource, no policy, no new group. The subscription is whatever `az` is signed in to;
the region and every name are parameters. Moving to the final account is re-running the same code against a group
there (`docs/23` s6).

```
infra/
  bootstrap-azure.ps1        one-time: group, GitHub OIDC identity with rights on that group only, GitHub settings
  deploy.sh                  THE one command that deploys everything (the workflow runs it; so can a person)
  main.bicep                 stage 1: everything except the apps (network, data, edge, monitoring, identities, budget)
  apps.bicep                 stage 2: migrate job, api, worker, web apps, Front Door origins and routes
  lib/naming.bicep           every resource name, the Key Vault secret names, the role ids (one place)
  modules/*.bicep            one file per component
  params/dev*.bicepparam     pilot sizing for rg-aron-dev
  params/prod*.bicepparam    full-fleet sizing (8,500 SRs)
  docker/                    backend image (one image, roles api/worker/migrate) and web image
  scripts/                   deploy helpers: db password, Key Vault seeding, scope check, Private Link, smoke test
  tests/check_infra.py       offline acceptance checks on the compiled templates and the workflows
  validate.sh                runs every offline validation (CI job "Infra validation")
```

## Sponsor steps (in this order)

1. **Once, on the laptop** (re-run it even if you ran the Day-0 version: it is idempotent and now also locks the
   identity down further): `az login`, `gh auth login`, then

   ```powershell
   .\infra\bootstrap-azure.ps1 -AlertEmails "you@aktcl.example,ops@aktcl.example"
   ```

   Re-run it after pulling a newer version: it is idempotent. Since 2026-10-06 it trusts both forms of the GitHub
   sign-in subject (classic `repo:<owner>/<repo>:environment:azure-dev` and the ID-qualified
   `repo:<owner>@<id>/<repo>@<id>:environment:azure-dev` that this repository presents).

   It registers the resource providers (now including `Microsoft.EventGrid`), creates `rg-aron-dev` in
   `southeastasia` and an empty `rg-aron-scope-probe` (the identity gets no rights there; every deploy proves a test
   deployment into it is denied), and the GitHub identity `sp-aron-github-dev` with, **on that group only**, Contributor plus
   Role Based Access Control Administrator **limited by a condition** to the eight data-plane roles the templates
   grant (it can never hand out Owner or Contributor). The identity trusts only the GitHub environment `azure-dev`,
   and that environment accepts deployments only from `claude/wonderful-thompson-k6ejnf` (the Day-0
   pull-request credential is removed). It writes the secrets `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`,
   `AZURE_SUBSCRIPTION_ID` and the variables `AZURE_RESOURCE_GROUP`, `AZURE_LOCATION`, `ARON_ALERT_EMAILS`.
2. **Quotas** (once, early: they take days): follow `docs/setup/azure-quota-request.md`.
3. **Optional GitHub variables and secrets** (the deploy works without them): variable `ARON_BUDGET_AMOUNT` (monthly
   budget in the subscription's currency; default 800 dev, 7000 prod); variable `ARON_NAME_SUFFIX` (only to rebuild a
   deleted group within 90 days, see below); secret `FCM_SERVICE_ACCOUNT_JSON` (push stays off until it is set);
   secret `MAPS_WEB_KEY` (web maps).
4. **Deploy.** Every push to `claude/wonderful-thompson-k6ejnf` that changes backend, db, shared, web or infra deploys
   automatically once CI is green. By hand: GitHub > Actions > **deploy** > Run workflow (branch
   `claude/wonderful-thompson-k6ejnf`). From a laptop after `az login`:
   `AZURE_RESOURCE_GROUP=rg-aron-dev ARON_ALERT_EMAILS=you@aktcl.example infra/deploy.sh dev`.
   The **first** run takes about 30 to 45 minutes (the zone-redundant PostgreSQL server and its standby). Later runs
   skip `main.bicep` when nothing under `infra/` changed and take about 6 to 8 minutes after CI.
5. **Check**: the run summary prints `https://<front-door-host>/v1/health`; open it on the phone.

The migrations job always runs before the apps on an automatic deploy; only a manual dispatch can untick it (to
redeploy apps without a schema change). Changing a GitHub variable (`ARON_ALERT_EMAILS`, `ARON_BUDGET_AMOUNT`,
`ARON_NAME_SUFFIX`, `AZURE_LOCATION`) takes effect at the next deploy: `deploy.sh` compares the compiled parameters
with the last infra deployment and re-runs `main.bicep` when they differ.

**Rebuilding a deleted group.** A deleted Key Vault keeps its name for 90 days. Either purge it (sponsor, subscription
rights: `az keyvault purge -n <name>`) or set the GitHub variable `ARON_NAME_SUFFIX` to a new short value before the
deploy, which gives every globally unique resource a new name.

## What the deploy does, and why in this order

`.github/workflows/deploy.yml` is called by `ci.yml` only after the contract, JVM, web, Android and infra jobs passed on
a push to the integration branch, or dispatched by hand from that branch. Pull requests never deploy and have no
Azure identity. The workflow checks the branch and the settings, signs in with OIDC, runs the scope check, then
`infra/deploy.sh`:

| # | Step | Why here |
|---|---|---|
| 0 | Preflight: integration branch; Azure secrets and variables exist | A missing secret fails with a message naming what to run |
| 1 | OIDC sign-in (`azure/login`, environment `azure-dev`) | No client secret anywhere |
| 2 | **Scope check** (`scripts/scope-check.sh`) | Acceptance of N-012: a test deployment into `rg-aron-scope-probe` must fail with `AuthorizationFailed`, creating a group must fail, and no other visible group accepts a deployment |
| 3 | Lock: wait while another `aron-*` deployment runs in the group | One deploy at a time without a GitHub concurrency group (which would cancel pending CI runs) |
| 4 | Ordering guard: skip when this commit is an ancestor of the deployed one (the live commit is the api's `ARON_BUILD`); re-run right before step 8 and step 9 | CI runs finish out of order; an older commit never replaces a newer one, and a commit whose newer sibling did not deploy still deploys. A rollback (`ROLLBACK_SHA`) bypasses it on purpose |
| 5 | `main.bicep` (skipped when `infra/` and the compiled parameters are unchanged since the deployed commit) | The database password is read back from Key Vault, generated only when the vault or the secret does not exist; the budget start date is read back, or the 1st of the current month |
| 6 | Seed Key Vault | `aron-jwt-signing-key` (ES256 PKCS#8) and `aron-jwt-kid` once; `aron-web-session-secret` once; `aron-fcm-service-account` from the GitHub secret or `{}` |
| 7 | Images: `aron-backend:<sha>` (and `aron-web:<sha>`) pushed once, tag locked, deployed **by digest** (`repo@sha256:...`) | One backend image for api, worker and migrate (D24-27); a moved tag never changes what runs; a re-run or rollback reuses the image. `acr-purge.yml` keeps the newest 30 per repository plus anything in use |
| 8 | Summary records the PITR restore point; `apps.bicep` with `deployServices=false`, start the migrate job, wait for `Succeeded` | Migrations run **before** any app revision changes; a failure stops the deploy with the old apps serving |
| 8b | dblogins job (`infra/sql/runtime-logins.sql`, psql image imported into ACR by digest) | Per-app logins `app_api` (api_rw), `app_worker` (worker_rw), `app_jobs` (jobs_rw), passwords in Key Vault (`scripts/db-login-secrets.sh`), never superuser or CREATEROLE; skipped on a rollback |
| 9 | `apps.bicep` with `deployServices=true` | api, worker, web and the Front Door routes (`/v1/*` to api, `/*` to web). api connects as `app_api`, worker as `app_jobs` (`dbPerAppLogins`); only the migrate job uses the admin login |
| 10 | Approve Private Link (Premium) | Until every origin's connection is approved |
| 11 | Health gate through Front Door (`scripts/smoke.sh`): `GET` and `HEAD /v1/health` 200 with `X-Aron-Api: 1` and `build` = this commit, `/v1/health/ready` 200, web `/login` 200 | The phone's own path; an edge or WAF page has no marker (docs/24 s3.1.6). In Multiple revision mode (stage, prod) a failed gate puts api traffic back on the previous revision; in dev see `docs/runbooks/rollback-bad-deploy.md` |
| 12 | Budget exists | Acceptance of N-012 |

The three debug APKs are uploaded by `ci.yml` on every successful Android run (artifact `aron-debug-apks-<sha>`).

## Resources (one environment)

| Resource | Name (dev) | Settings and reason |
|---|---|---|
| Virtual network | `vnet-aron-dev` (10.51.0.0/16) | `snet-aca` /24 delegated to Container Apps; `snet-pg` /28 delegated to PostgreSQL, NSG lets only `snet-aca` reach 5432/6432 |
| Log Analytics, Application Insights | `log-aron-dev`, `appi-aron-dev` | Workspace-based; daily cap 1 GB dev, 8 GB prod; every service sends `allLogs` + metrics by diagnostic setting |
| Action group, alerts | `ag-aron-dev`, `aron-dev-*` | docs/24 s13.1: batch 5xx > 1 % in 5 min, batch p95 > 3 s, dashboards p95 > 1 s; PostgreSQL CPU > 80 %, storage > 70 %, failed connections |
| Budget | `budget-aron-dev` | Monthly, on the group only; e-mail at 80 % and 100 % actual and 100 % forecast |
| Key Vault | `kv-aron-dev-<suffix>` | RBAC, soft delete 90 days; purge protection on in prod. Secrets: `aron-jwt-signing-key`, `aron-jwt-kid`, `aron-fcm-service-account`, `aron-db-admin-password`, `aron-db-url` (PgBouncer 6432), `aron-db-direct-url` (5432), `aron-db-read-url` |
| Container registry | `crarondev<suffix>` | Basic dev, Premium (zone-redundant) prod; no admin user; apps pull with managed identity |
| Storage | `starondev<suffix>` | Containers `media` (photos) and `bundles`; queue `media-events` fed by Event Grid on BlobCreated; shared keys **off** (only user-delegation SAS works); TLS 1.2; soft delete 14 days; photos Cool at 30 days, Cold at 90; bundles deleted after 3 days; ZRS dev, RA-GZRS prod |
| PostgreSQL Flexible Server 16 | `psql-aron-dev-<suffix>` | Private access only; **zone-redundant HA** (synchronous standby in zone 2); **geo-redundant backup** (only settable at creation, so on from the first deploy in both environments); PITR 7 days dev, 35 prod; Premium SSD v2 (no autogrow: the storage alert says when to grow); built-in PgBouncer (pool 20, wait timeout 5 s); maintenance Friday 01:00 Dhaka; prod adds an in-region read replica of the same SKU with its own PgBouncer (first prod deploy with `ARON_PG_READ_REPLICA=false`: SSD v2 needs its first backup before a replica) |
| Container Apps environment | `cae-aron-dev` | Workload profiles (Consumption), VNet-injected, **zone-redundant** (creation-time choice); prod: public access off, Front Door reaches it over Private Link |
| Managed identities | `id-aron-dev-{api,worker,migrate,web}` | Least privilege: all AcrPull and Key Vault Secrets User (each reads only the secrets its app references); api and worker read/write blobs; only api mints user-delegation SAS; only worker reads the media queue. The deploying identity gets AcrPush and Key Vault Secrets Officer |
| Migrate job | `caj-aron-dev-migrate` | `ARON_ROLE=migrate`, direct connection (Flyway's advisory lock does not survive PgBouncer transaction mode) |
| api app | `ca-aron-dev-api` | `ARON_ROLE=api`, 1 vCPU / 2 GiB, HTTP scale rule 50 concurrent requests per replica, startup/liveness `/v1/health`, readiness `/v1/health/ready` (database check) |
| worker app | `ca-aron-dev-worker` | `ARON_ROLE=worker`, no ingress, direct database connection (advisory locks for run-once jobs) |
| web app | `ca-aron-dev-web` | Next.js standalone server (the BFF cookie needs a server runtime); `ARON_SESSION_SECRET` from Key Vault, `ARON_API_BASE_URL` = the Front Door host |
| Front Door + WAF | `afd-aron-dev`, `fde-aron-dev-<suffix>`, `wafarondev` | Standard dev (custom rules: allowed methods, per-IP backstop), Premium prod (+ Default Rule Set 2.1 in **Log** mode through the pilot and Bot Manager 1.1, Private Link origins). No caching on `/v1/*`. Origin response timeout 60 s |

`<suffix>` is six characters derived from the resource group id, so a second group (the move rehearsal) gets new
globally unique names with no edit.

## Sizing: dev = TEST profile, prod = FINAL profile (docs/28, binding)

| | dev (TEST account, 5 to 10 pilot users) | prod (FINAL account, 8,500 SRs) |
|---|---|---|
| PostgreSQL | Burstable B1ms, single zone, no HA, no replica, no geo backup, 32 GiB SSD v1, PITR 7 d, public endpoint limited to Azure services (TLS) | D8ds_v5, zone-redundant HA, read replica, SSD v2 1,024 GiB / 12,000 IOPS, geo backup, PITR 35 d, private |
| Network | no VNet (no environment load balancer or public IPs) | VNet, private PostgreSQL, zone-redundant Container Apps environment |
| Edge | no Front Door/WAF; clients use the api Container Apps address | Front Door Premium + WAF (managed rules) + Private Link |
| api / worker / web | 0-2 (0.5 vCPU) / 1 (0.25 vCPU) / 0-1 | 3-30 (8 pre-scaled at 06:15 and 16:45 Dhaka) / 1-10 / 2-6 |
| Connection pools per replica | api 4 + 2 read, worker 3 + 2 (B1ms admits about 35) | 10 + 10 behind PgBouncer |
| Registry / Storage | Basic / LRS | Premium / RA-GZRS |
| Logs / alerts | cap 0.5 GB/day, 30 d; metric alerts only | cap 8 GB/day, 90 d; log-search alerts too |
| Budget | USD 70 (Azure share of the owner's USD 100), e-mails at 50/90/100 % | set for the final account |
| Approx. list cost a month | about USD 35 to 50 (`docs/status/infra.md`) | about USD 5,500 to 6,500 (docs/18 s3.3) |

The move to the final account is `docs/setup/move-to-final-account.md`. A group created with other creation-time
settings (for example an earlier profile) is brought back in line with the **reset** workflow
(`infra/scripts/reset-to-profile.sh`): dry run by default, deletes only what the profile cannot adopt.

## Validation

`infra/validate.sh` (also the CI job "Infra validation") runs, without any Azure credential:

- `bicep build` and `bicep lint` of every file (a warning fails), `bicep build-params` of the four parameter files;
- `infra/tests/check_infra.py`: every template is resource-group scoped and no module leaves the group; no literal
  subscription, tenant or region; role assignments only from an allow-list (never Owner/Contributor); every component
  of N-012 present; diagnostic settings on Key Vault, ACR, PostgreSQL, Storage, Container Apps and Front Door;
  security defaults (no shared keys, no public blobs, TLS 1.2, RBAC Key Vault, no ACR admin, private PostgreSQL);
  sizing rules of both environments; every workflow action pinned by commit SHA; deploy only from the integration
  branch with OIDC; deploy steps in the order above;
- `actionlint` with `shellcheck` on the workflows, `shellcheck` on `deploy.sh` and the scripts, and the executable bit
  of every script the workflow runs;
- a `what-if` against the group **only** when `az` is signed in and `AZURE_RESOURCE_GROUP` is set.

**Not validated without a subscription** (proved by the first deploy run, whose steps fail loudly): SKU and zone
availability and quota in the region (PostgreSQL D-series vCores, Container Apps cores, Front Door), that ARM accepts
every property value (Bicep only checks types), the RBAC propagation timing, the Private Link approval flow, the
budget API in this subscription type, Event Grid delivery to the queue, and the end-to-end smoke test.

## Not in this version (follow-ups)

- Front Door Standard (dev) cannot use Private Link, so the dev apps also answer on their own `*.azurecontainerapps.io`
  address; the backend can reject requests without the `X-Azure-FDID` header equal to `ARON_FRONT_DOOR_ID`
  (requested). Prod uses Private Link and has no public app address.
- Private endpoints for Key Vault and ACR. Password rotation of the per-app database logins (today: generated once,
  re-applied every deploy; rotate = delete the `aron-db-pw-*` secret and deploy, then restart the apps).
- Weighted canary steps (docs/18 s2.5) and a KEDA rule on the worker backlog: need backend metrics. Stage and prod
  already run the api in Multiple revision mode (previous revision kept active at 0 % for an instant switch back,
  automatic on a failed health gate); proven only in the final account.
- Build once, promote: the deploy builds its own images (by commit, then runs them by digest); the CI images job
  builds, boots and Trivy-scans an image from the same commit and Dockerfiles, but not the same bytes (the web runtime
  stage applies Debian updates at build time). Promoting the CI-built digest is planned for the final-account move
  (AUD-DG-06).
- Cross-region replica and the second-group move rehearsal (reserve days, docs/23 s7).
- `budgetStartDate` in the parameter files is only a fallback for offline builds; a manual `az deployment group create`
  outside `deploy.sh` must pass `ARON_BUDGET_START_DATE` (the first day of the current month on a new budget).
- The prod GitHub environment `azure-prod` needs its own identity, federated credential and branch policy (bootstrap
  creates only `azure-dev`); run the bootstrap against the final subscription with `-Environment azure-prod`.
- Normal-run time: CI (Android is the longest job) plus about 6 to 8 minutes of deploy. The 15-minute target holds
  when the Gradle cache is warm; if the Android job grows, the deploy can be decoupled from it later.
