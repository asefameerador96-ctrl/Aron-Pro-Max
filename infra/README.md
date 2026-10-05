# infra/: Aron on Azure (N-012) and how it is deployed (N-013)

Everything Aron runs on in Azure is Bicep in this folder. It deploys **into one existing resource group** and nothing
else: no subscription-scope resource, no policy, no new group. The subscription is whatever `az` is signed in to;
the region and every name are parameters. Moving to the final account is re-running the same code against a group
there (`docs/23` s6).

```
infra/
  bootstrap-azure.ps1        one-time: group, GitHub OIDC identity with rights on that group only, GitHub settings
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

1. **Once, on the laptop** (already done on Day 0 if `bootstrap-azure.ps1` printed "Done"; re-running is safe and
   adds what is new): `az login`, `gh auth login`, then

   ```powershell
   .\infra\bootstrap-azure.ps1 -AlertEmails "you@aktcl.example,ops@aktcl.example"
   ```

   It registers the resource providers (now including `Microsoft.EventGrid`), creates `rg-aron-dev` in
   `southeastasia`, the GitHub identity `sp-aron-github-dev` with **Contributor + RBAC Administrator on that group
   only**, the OIDC trust for the GitHub environment `azure-dev`, the secrets `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`,
   `AZURE_SUBSCRIPTION_ID` and the variables `AZURE_RESOURCE_GROUP`, `AZURE_LOCATION`, `ARON_ALERT_EMAILS`.
   If you ran it on Day 0 without `-AlertEmails`, either re-run it with the flag or set the repository variable
   `ARON_ALERT_EMAILS` by hand (GitHub > Settings > Secrets and variables > Actions > Variables).
2. **Optional GitHub settings** (the deploy works without them): variable `ARON_BUDGET_AMOUNT` (monthly budget in the
   subscription's currency; default 800 for dev); secret `FCM_SERVICE_ACCOUNT_JSON` (push stays off until it is set);
   secret `MAPS_WEB_KEY` (web maps).
3. **Deploy.** Every push to `claude/wonderful-thompson-k6ejnf` that changes the backend, db, shared, web or infra
   deploys automatically once CI is green. To deploy by hand: GitHub > Actions > **deploy** > Run workflow (branch
   `claude/wonderful-thompson-k6ejnf`, environment `dev`). The **first** run takes about 30 to 45 minutes (a
   zone-redundant PostgreSQL server with its standby is the slow part); later runs about 10 minutes.
4. **Check**: the run summary prints `https://<front-door-host>/v1/health`; open it on the phone. The budget e-mails go
   to `ARON_ALERT_EMAILS`.

Until the backend lane ships the `migrate` role (request `docs/requests/infra-backend-runtime.md`), run the deploy by
hand with **run_migrations** unticked; the automatic deploy stops at the migrations step with a clear error.

## What the deploy workflow does, and why in this order

`.github/workflows/deploy.yml`, called by `ci.yml` only after the contract, JVM, Android and infra jobs passed on a push
to the integration branch, or dispatched by hand from that branch. Pull requests never deploy.

| # | Step | Why here |
|---|---|---|
| 0 | Preflight: branch is the integration branch; Azure secrets and variables exist; the commit is still the branch head | A missing secret fails with a message that says what to run; an older commit never overwrites a newer deploy |
| 1 | OIDC sign-in (`azure/login`, environment `azure-dev`) | No client secret anywhere |
| 2 | **Scope check** (`scripts/scope-check.sh`) | Acceptance of N-012: the identity can read its group, cannot create a group and cannot deploy into any other group; the run fails if it can |
| 3 | `main.bicep` with `params/<env>.bicepparam` | The database admin password is read back from Key Vault (generated only on the very first run, never when the vault refuses the read) |
| 4 | Seed Key Vault (`scripts/seed-secrets.sh`) | Creates `aron-jwt-signing-key` (ES256 PKCS#8) and `aron-jwt-kid` once; sets `aron-fcm-service-account` from the GitHub secret or `{}` |
| 5 | Build `:backend:app:installDist`, image `aron-backend:<sha>` to ACR; web image when `web/package.json` exists | One image for api, worker and migrate (D24-27) |
| 6 | `apps.bicep` with `deployServices=false`, then start the migrate job and wait for `Succeeded` | Migrations run **before** any api or worker revision changes; a failed migration stops the deploy with the old apps still serving |
| 7 | `apps.bicep` with `deployServices=true` | api, worker, web and the Front Door routes (`/v1/*` to api, `/*` to web) |
| 8 | Approve Private Link (Premium only) | Front Door's private endpoint to the environment needs one approval per origin |
| 9 | Smoke test through Front Door: `GET` and `HEAD /v1/health` must be 200 with `X-Aron-Api: 1` | The phone's own path; an edge or WAF page has no marker (docs/24 s3.1.6). Retries up to 10 minutes while a new route propagates |
| 10 | Budget exists | Acceptance of N-012 |

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
| PostgreSQL Flexible Server 16 | `psql-aron-dev-<suffix>` | Private access only; **zone-redundant HA** (synchronous standby in zone 2); **geo-redundant backup** (only settable at creation, so on from the first deploy in both environments); PITR 7 days dev, 35 prod; Premium SSD v2 (no autogrow: the storage alert says when to grow); built-in PgBouncer (pool 20, wait timeout 5 s); maintenance Friday 01:00 Dhaka; prod adds an in-region read replica of the same SKU |
| Container Apps environment | `cae-aron-dev` | Workload profiles (Consumption), VNet-injected, **zone-redundant** (creation-time choice); prod: public access off, Front Door reaches it over Private Link |
| Managed identities | `id-aron-dev-{api,worker,migrate,web}` | Least privilege: all AcrPull; api/worker/migrate read Key Vault secrets; api and worker read/write blobs; only api mints user-delegation SAS; only worker reads the media queue; web nothing else. The deploying identity gets AcrPush and Key Vault Secrets Officer |
| Migrate job | `caj-aron-dev-migrate` | `ARON_ROLE=migrate`, direct connection (Flyway's advisory lock does not survive PgBouncer transaction mode) |
| api app | `ca-aron-dev-api` | `ARON_ROLE=api`, 1 vCPU / 2 GiB, HTTP scale rule 50 concurrent requests per replica, startup/liveness `/v1/health`, readiness `/v1/health/ready` (dev uses `/v1/health` until the backend ships ready) |
| worker app | `ca-aron-dev-worker` | `ARON_ROLE=worker`, no ingress, direct database connection (advisory locks for run-once jobs) |
| web app | `ca-aron-dev-web` | Next.js standalone server (the BFF cookie needs a server runtime), only once `web/` exists |
| Front Door + WAF | `afd-aron-dev`, `fde-aron-dev-<suffix>`, `wafarondev` | Standard dev (custom rules: allowed methods, per-IP backstop), Premium prod (+ Default Rule Set 2.1 in **Log** mode through the pilot and Bot Manager 1.1, Private Link origins). No caching on `/v1/*`. Origin response timeout 60 s |

`<suffix>` is six characters derived from the resource group id, so a second group (the move rehearsal) gets new
globally unique names with no edit.

## Sizing: dev (pilot) and prod (full fleet)

| | dev (`rg-aron-dev`, pilot about 30 users) | prod (8,500 SRs, about 9,850 app users, 130 web) | Reason |
|---|---|---|---|
| PostgreSQL | D2ds_v5, HA, SSD v2 128 GiB / 3,000 IOPS / 125 MB/s, PITR 7 d | D8ds_v5, HA, SSD v2 1,024 GiB / 12,000 IOPS / 300 MB/s, PITR 35 d, read replica | docs/18 s3.1 and s3.5: the D8 IOPS cap (12,800) matches the 12,000 IOPS disk; worst-case 3,333 rows/s at the fleet |
| api | 1..3 replicas | 3..30, pre-scaled to 8 at 06:15 and 16:45 Dhaka | docs/18 s3.4: one replica per zone minimum; 131 req/s peak needs 5 |
| worker | 1..2 | 1..10 | docs/18 s3.1 |
| web | 1..2 | 2..6 | docs/18 s3.1 |
| Front Door | Standard | Premium (managed rules, Private Link) | Managed rules and Private Link are Premium only (Learn) |
| Registry / Storage | Basic / ZRS | Premium / RA-GZRS | Photos must stay readable in a region outage (docs/18 s2.8) |
| Logs | 1 GB/day cap | 8 GB/day cap, 90 days | docs/18 s3.3: 5.5 GB/day estimated for the fleet |
| Approx. list cost a month | about USD 600 to 800 | about USD 5,500 to 6,500 | docs/18 s3.3 (prices 2026-10-04); PostgreSQL HA is the largest line |

The Day 6 load and failover tests can run in `rg-aron-dev` with `params/prod*.bicepparam` and
`environmentName='dev'` overridden, because every creation-time choice (zone redundancy, geo backup, SSD v2) is
already the same in both files; compute scales online. SSD v2 storage only grows, so scale it back down by restoring
into a new server, not by editing the size.

## Validation

`infra/validate.sh` (also the CI job "Infra validation") runs, without any Azure credential:

- `bicep build` and `bicep lint` of every file (a warning fails), `bicep build-params` of the four parameter files;
- `infra/tests/check_infra.py`: every template is resource-group scoped and no module leaves the group; no literal
  subscription, tenant or region; role assignments only from an allow-list (never Owner/Contributor); every component
  of N-012 present; diagnostic settings on Key Vault, ACR, PostgreSQL, Storage, Container Apps and Front Door;
  security defaults (no shared keys, no public blobs, TLS 1.2, RBAC Key Vault, no ACR admin, private PostgreSQL);
  sizing rules of both environments; every workflow action pinned by commit SHA; deploy only from the integration
  branch with OIDC; deploy steps in the order above;
- `actionlint` with `shellcheck` on the workflows, `shellcheck` on the scripts;
- a `what-if` against the group **only** when `az` is signed in and `AZURE_RESOURCE_GROUP` is set.

**Not validated without a subscription** (proved by the first deploy run, whose steps fail loudly): SKU and zone
availability and quota in the region (PostgreSQL D-series vCores, Container Apps cores, Front Door), that ARM accepts
every property value (Bicep only checks types), the RBAC propagation timing, the Private Link approval flow, the
budget API in this subscription type, Event Grid delivery to the queue, and the end-to-end smoke test.

## Not in this version (follow-ups)

- Front Door Standard (dev) cannot use Private Link, so the dev apps also answer on their own `*.azurecontainerapps.io`
  address; the backend can reject requests without the `X-Azure-FDID` header equal to `ARON_FRONT_DOOR_ID`
  (requested). Prod uses Private Link and has no public app address.
- Private endpoints for Key Vault and ACR; the database uses the admin login for the app until the db lane adds
  per-role logins (api, worker, migrate) and the rotation is designed.
- Multiple-revision canary deploys (docs/18 s2.5) and a KEDA rule on the worker backlog: need backend metrics.
- Cross-region replica and the second-group move rehearsal (reserve days, docs/23 s7).
- The prod GitHub environment `azure-prod` needs its own federated credential (bootstrap creates only `azure-dev`).
