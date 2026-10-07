# Infra lane status

Updated 2026-10-07 (Day 3, morning, Dhaka midday).

## Day 3 (2026-10-07): CI gates of docs/31 s2, stage profile, promotion workflows, cost reading

Lead decisions applied: the nightly PostgreSQL stop/start is **not** approved and not built; the GitHub governance
script is not run and no branch settings changed.

**CI gates (all green on the real runner, run of c22aa48; full run: checks 3 to 5 min, deploy 5 to 10 min):**

| Gate | Where | Fails on |
|---|---|---|
| gitleaks 8.30.1 (pinned SHA-256) | ci.yml `gates`, every push and PR | any secret in the pushed commits (whole history when the base is unknown); 10 reviewed false positives pinned by fingerprint (`tools/ci/gitleaksignore`), generated `contract/slices/` skipped (its source is scanned) |
| Migrations | ci.yml `gates` when db/migrations changes | an edited, renamed or deleted shipped migration; duplicate or out-of-order versions; squawk 2.67.0 findings on NEW migrations (`tools/ci/squawk.toml`: lock_timeout, CONCURRENTLY, NOT VALID, no drop/rename) |
| Contract | ci.yml `gates` when contract/ changes | an oasdiff 1.33.0 breaking change, unless the same push bumps `info.version` AND adds `docs/requests/contract-*.md` |
| SR release + APK size (F-SYS-036) | ci.yml `android-release` | `assembleRelease` breaking (android-core ask); 30 MB per ABI, 70 MB installed (estimate), +15 % over `tools/ci/apk-size-baseline.json` (warn +5 %). SR release today: **10.2 MB universal, 4.3 MB arm64-v8a, 3.2 MB armeabi-v7a** |
| SBOM | ci.yml `images` | SPDX for aron-backend and aron-web (syft 1.54.1 pinned), artifact `sbom-<sha>`, 90 days |
| CodeQL | codeql.yml (separate, never blocks the deploy) | java-kotlin (shared, backend, the three apps; real compile) and javascript-typescript, `security-extended`; push, PR, weekly. First run: 3.3 min and 1.2 min |

Each gate is proven to fail on a deliberate violation: `tools/ci/test_gates.py` (15 tests, run in the gates job with
the real binaries and in `infra/validate.sh`). Two fresh checkers: 5 + 3 confirmed defects, all fixed.

**Concurrency, verified:** every push-triggered workflow groups by ref and commit and never cancels (test over all
workflows); only pull requests cancel their own older run. The deploy is serialised in Azure, not by GitHub: since
today a **lock held for the whole deploy** (tag `aron-deploy-lock` on the group), after two runs interleaved
(`DeploymentActive` on `aron-apps`, run of 4385442). An older commit waiting for the lock skips once a newer one is live.

**Migrate failure followed up:** the repeat on e2bcabc showed its cause (the console log is now in the deploy log):
the job's first database connection timed out after 5 s on a fresh replica (`total=0`), not a migration problem. The
deploy runs the job once more on exactly that error; the root fix is requested from backend-core
(`docs/requests/backend-migrate-connect-retries.md`).

**Stage and promotion (docs/30 s1, s2):** `infra/params/stage*.bicepparam`, prod-shaped, own VNet 10.41/16, a parameter
file only. `promote-prod.yml` (a server-v* tag on main, CI green, a successful staging deploy soaked, then deploy.yml
prod) and `release-app.yml` (app-v* tag: three release APKs against the final origin, signed with apksigner from the
four `ANDROID_SIGNING_*` secrets, size gate, GitHub Release with SHA256SUMS) are **inert** until
`ARON_FINAL_ACCOUNT=true`. deploy.yml refuses prod outside promote-prod, outside the final account and off a server-v*
tag. Open for the lead: `tools/github-governance.ps1` creates environments `staging` and `prod`, but the workflows
and the OIDC subjects use `azure-dev` / `azure-prod`; `azure-prod` needs the owner as reviewer and a tag policy
`server-v*` before the first promotion.

**Cost reading (g):** `cost-report.yml` (daily 08:05 Dhaka, read-only) asked Azure for the month-to-date cost.
Answer: `SubscriptionCostDisabled: Cost views are disabled for subscription users because the cost policy is turned
off by your account admin` (run 37570748133). **No Azure cost figure is readable** by the deploy identity until the
owner turns that billing policy on (the same switch as the budget); the owner can read it in the portal.
Estimate from list prices (Southeast Asia retail API, 2026-10-07), with the apps now running:

| Item | USD/day |
|---|---|
| PostgreSQL D2ds_v5 zone-redundant HA (2 x 0.244/h) + SSD v2 128 GiB x 2 | 12.9 |
| Container Apps: api (0.5 vCPU, 1 GiB), worker (0.25, 0.5), web (0.25, 0.5) always on; active 0.000034/vCPU-s, idle 0.000004, memory 0.000004/GiB-s, minus the monthly free grant | 1.0 to 3.6 |
| Front Door Standard + WAF policy | 1.4 |
| Log Analytics ingestion after the free 5 GB a month (cap 1 GB/day at 2.99/GB) | 0 to 3.0 |
| Container Apps environment network, registry, DNS, alerts, storage, Key Vault | about 1.2 |
| **Total** | **about 16.5 to 22 a day (central about 18; about 560 a month)** |

This is about USD 2.5 a day above the 15.5 reported before the apps went live. For 2026-10-10: about 4 days since
2026-10-06 13:09 UTC, so roughly USD 65 to 85 spent on this group. These are estimates, not readings.

## Owner decision 2026-10-06: HOLD (docs/28 "Exception approved")

The full-size resources the first deploy created in `rg-aron-dev` are **kept** for up to one week to rehearse the final
topology; **review date 2026-10-10** (spend report, then keep / shrink / delete). Nothing is deleted; no quota
requests; no fleet-sized additions.

- **Two profiles, same templates.** `infra/params/dev*.bicepparam` = **rehearsal profile**: exactly what exists
  (PostgreSQL GeneralPurpose D2ds_v5 zone-redundant HA, SSD v2 128 GiB, geo backup; Front Door Standard + WAF;
  VNet-injected Container Apps environment; ZRS storage; budget USD 130 with 50/90/100 % and forecast 100 %).
  `infra/params/dev-lite*.bicepparam` = the cheap **TEST profile** (Burstable B1ms, no VNet, no Front Door, scale to
  zero; about USD 35 to 55 a month), used only after an owner-ordered reset. `prod*` = FINAL profile.
- **Adopt, never recreate.** `infra/deploy.sh dev` runs `az deployment group what-if` first;
  `infra/scripts/whatif-guard.py` stops the deploy (nothing changed) if a PostgreSQL server would be deleted, created
  next to the existing one, or changed in sku, storage, HA, backup, network, version, zone or admin login. The
  reset check (`reset-to-profile.sh` in check mode) confirms the group matches the dev profile (nothing to delete).
- **Reset locked.** The `reset` workflow deletes only when the profile is `dev-lite` AND "confirm" is exactly
  `owner approved reset`; the script itself also refuses deletion without `OWNER_APPROVED=yes` and a `*-lite`
  profile. Everything else is a dry run.

- **CI GREEN on `dev`: run 37489113157 (commit aac06e0), 2026-10-06 15:45 UTC.** Migrations succeeded; apps updated;
  the deploy's own smoke test passed through Front Door (`GET /v1/health` -> 200 with `X-Aron-Api: 1`, build aac06e0;
  `HEAD` -> 200). One earlier run (37484856611, commit dd3fb58, same code) had its migrate job execution end `Failed`;
  the cause was not captured (the log was only in Log Analytics). The next run migrated fine; deploy.sh now prints a
  failed execution's console log, so a repeat will show its cause. Not called a flake: open until it repeats or not.
- **Nightly PostgreSQL stop/start (lead approved 2026-10-06): NOT built yet.** The lane's tool permissions blocked
  writing the stop logic in this session; it waits for the owner's direct confirmation. The server runs 24 h.

- **DEPLOYED on profile `dev` (CI run 37479574147, commit db50ae9), adopting the existing resources; nothing was
  deleted or recreated, and PostgreSQL was untouched by the what-if guard.** The run did: infra (adopted), Key Vault
  secrets (JWT key, FCM, web session secret), images, **migrations succeeded**, api + worker + web + Front Door routes.
  **Front Door address: `https://fde-aron-dev-7i7g53-gpgaa3fpgrhmdgaz.z03.azurefd.net`.** The run's own smoke
  test gave up after 10 minutes on Front Door's edge 404 (the routes were still propagating), so the CI job is red.
  Checked from outside at 15:09 UTC (about 23 minutes after the routes were created):
  `GET /v1/health` -> **200**, `X-Aron-Api: 1`, body `{"status":"ok","api":"/v1",...,"build":"db50ae94..."}`;
  `HEAD /v1/health` -> 200; `GET /v1/health/ready` -> 200; `GET /` -> 307 to `/login`, and `/login` -> 200 (web);
  `http://` -> 307 to `https://`. Smoke timeout on Front Door hosts raised to 45 minutes (commit after db50ae9).
  **No budget** yet (cost policy off, below).

- **Deploy of `dev` (CI run 37474197061, commit 9e5abfa): stopped at the what-if, nothing changed.** The reset check
  passed (all 28 resources KEEP, "nothing to reset: rg-aron-dev matches the dev profile"). Azure then refused the
  what-if: `401 - Budget experiences are disabled for subscription users because the cost policy is turned off by your
  account admin`. **No budget can exist in this subscription until the billing account admin (the owner, or the
  partner if the subscription is bought through a CSP) allows subscription users to view charges** (Cost Management +
  Billing > Policies; for a CSP the partner's "Azure usage" customer policy). Fix in the deploy: it reads the budgets
  first; on exactly that refusal it deploys without the budget and prints a warning (`::warning::No budget`). Any other
  failure stops the deploy. Once the policy is on, the next deploy creates the USD 130 budget. **Until then, spend
  e-mails do not exist**; the 2026-10-10 spend report is read from Cost Management by the owner or estimated from the
  inventory (about USD 15.5/day).

- **Second try (CI run 37477074848, commit 54f734b): the what-if guard stopped it, nothing changed.** The budget was
  skipped with its warning as designed. The guard refused `psql-aron-dev-7i7g53: properties.network.delegatedSubnetResourceId
  (Modify)`. Cause: the subnet id came from the network module's output, which what-if cannot evaluate, so every
  adopted server and environment looked as if it moved subnet. Fix: main.bicep builds the VNet and subnet ids from the
  names (`resourceId(...)`, with `dependsOn: [network]`); the guard now ignores case-only id differences and prints
  before -> after for anything it refuses. The other what-if lines (role assignment principalId, diagnostic-setting
  retention, ACR/VNet/storage read-only defaults) are what-if noise on unchanged resources, not PostgreSQL.

### Can the PostgreSQL server be stopped overnight with HA on? Yes (Learn), not done without the lead's word

- Learn, *High availability concepts*: "You perform operations such as stop, start, and restart on both primary and
  standby database servers at the same time." *Stop compute of a server*: compute billing stops immediately; the
  server can be briefly restarted for monthly maintenance and starts automatically after 7 days; no other management
  operation (including a deploy that touches the server) works while it is stopped. Storage keeps billing.
- Saving: compute is 2 x USD 0.244/h = USD 0.488/h (primary + standby; storage about USD 1.2/day continues).
  Stopped 20:00 to 08:00 Dhaka (12 h): **about USD 5.9 a day**, the day cost falls from about USD 15.5 to about
  USD 9.6; also stopping on Fridays (weekly off-day): about USD 11.7 more per Friday. For the rest of the rehearsal
  week (to 2026-10-10): about USD 25 to 35 saved.
- Effect: while stopped the api's readiness check fails, so Front Door answers 503; phones keep selling offline and
  upload when it is back (offline-first); the worker's jobs pause; a start takes a few minutes (5 to 8 when maintenance
  is pending). If ordered, a scheduled GitHub workflow can stop at 20:00 and start at 07:30 Dhaka using the deploy
  identity (Contributor on the group); not built yet.

## TEST profile = `dev-lite` (docs/28), used only after an owner-ordered reset

`infra/params/dev-lite*.bicepparam` = TEST profile; `dev*` = rehearsal profile; `prod*` = FINAL profile; same templates, every size and switch a
parameter (`privateNetworking`, `deployFrontDoor`, `enableLogAlerts`, `postgresSkuTier`, `postgresStorageType`, app
sizes, connection pools). TEST: PostgreSQL **Burstable B1ms** (B2s is USD 0.104/h = USD 76 a month on its own, so
B1ms), single zone, no HA, no replica, no geo backup, 32 GiB SSD v1, 7-day backup, public endpoint limited to Azure
services by the firewall, TLS; Container Apps without a VNet (no environment load balancer or public IPs), api 0-2
(scale to zero, 0.5 vCPU), worker 1 x 0.25 vCPU, web 0-1; **no Front Door/WAF** (the deploy smoke-tests the Container
Apps address); Storage LRS, ACR Basic; Log Analytics cap 0.5 GB/day, 30 days; no log-search alert rules; budget USD
70 (the Azure share of the owner's USD 100) with e-mails at 50/90/100 % and forecast 100 %. No Load Testing resource.

**Monthly estimate (list prices, Southeast Asia, Azure Retail Prices API 2026-10-06):**

| Line | USD/month |
|---|---|
| PostgreSQL B1ms compute (0.026/h) | 19 |
| PostgreSQL storage 32 GiB (0.138/GB) and backup (within the free 100 %) | 4.4 |
| Container Apps: worker 0.25 vCPU / 0.5 GiB always on (about 6 at the idle rate, about 20 if the JVM keeps it above 0.01 vCPU and it bills active), api and web scale to zero; monthly free grant 180,000 vCPU-s / 360,000 GiB-s / 2 M requests | 6 to 20 |
| Container Registry Basic (0.1666/day) | 5 |
| Log Analytics + Application Insights (pilot volume under the 5 GB/month free; cap 0.5 GB/day) | 0 to 5 |
| Key Vault, Storage LRS, Event Grid, metric alerts | about 1 |
| **Total** | **about 35 to 55** |

No environment management fee: it applies only to private endpoints, planned maintenance or dedicated profiles, none
of which the TEST profile uses (Learn, Container Apps billing).

## Inventory of rg-aron-dev (reset dry run, 2026-10-06 13:27 UTC, run 37470818234)

The ARM deployment of the cancelled run **completed** after the cancel. Nothing outside `rg-aron-dev` was read or
changed by the dry run (it lists only that group). Estimated cost at Southeast Asia list prices:

| Action | Type | Created (UTC) | Name | Est. USD/day |
|---|---|---|---|---|
| DELETE | PostgreSQL flexible server (GeneralPurpose D2ds_v5, zone-redundant HA standby, SSD v2 128 GiB, geo backup) | 13:09:48 | psql-aron-dev-7i7g53 | **12.9** (2 x 0.244/h compute + 2 x 128 GiB x 0.138/month) |
| DELETE | Container Apps environment (VNet-injected; its platform load balancer and 2 public IPs) | 13:08:11 | cae-aron-dev | about 0.8 |
| DELETE | Front Door Standard profile (+ endpoint fde-aron-dev-7i7g53, deleted with it) | 13:08:09 | afd-aron-dev | about 1.2 |
| DELETE | Front Door WAF policy | 13:08:09 | wafarondev | about 0.2 |
| DELETE | Storage account Standard_ZRS (empty) | 13:08:08 | starondev7i7g53 | about 0 |
| DELETE | Event Grid system topic | 13:08:30 | evgt-aron-dev-storage | 0 |
| DELETE | Virtual network | 13:07:43 | vnet-aron-dev | 0 |
| DELETE | NSG x 2 | 13:07:41 | vnet-aron-dev-snet-aca-nsg, vnet-aron-dev-snet-pg-nsg | 0 |
| DELETE | Private DNS zone (+ its VNet link) | 13:08:08 | aron-dev-7i7g53.private.postgres.database.azure.com | about 0.02 |
| DELETE (fixed script) | Log-search alert rules x 3 (billed per rule; off in the TEST profile) | 13:21:01 | aron-dev-batch5xx, aron-dev-batchP95, aron-dev-dashboardsP95 | about 0.15 |
| KEEP | Key Vault | 13:21:02 | kv-aron-dev-7i7g53 | about 0 |
| KEEP | Log Analytics workspace | 13:07:39 | log-aron-dev | about 0 (free 5 GB/month) |
| KEEP | Application Insights (+ its automatic "Failure Anomalies" rule) | 13:08:01 | appi-aron-dev | 0 |
| KEEP | Container Registry Basic | 13:08:08 | crarondev7i7g53 | 0.17 |
| KEEP | Managed identities x 4 | 13:21:31 | id-aron-dev-api/-worker/-migrate/-web | 0 |
| KEEP | Metric alerts x 3, action group | 13:21:01 | aron-dev-pg-cpu, -pg-storage, -pg-connections-failed; ag-aron-dev | about 0.01 |
| KEEP | Budget (not listed by the resource API; it is a group-scope Consumption resource) | | budget-aron-dev | 0 |
| **Total now** | | | | **about 15.5 a day (about 465 a month)** |

Confirmed by a second dry run on the fail-closed script (run 37471894786, 13:35 UTC): 13 resources marked DELETE
(the rows above, including the 3 log-search rules); everything listed as KEEP above is KEEP. Two child rows show as
KEEP in the raw listing (the DNS zone's `vnet-link` and the Front Door endpoint `fde-aron-dev-7i7g53`); they are
removed with their parents (the link explicitly before the zone).

After the reset and the TEST-profile deploy: about USD 35 to 55 a month. The three metric alerts point at the
PostgreSQL server; they are re-pointed at the new server by the next deploy (same names).

## URGENT: resources created by the cancelled run 37467503909 (old dev values)

Run 37467503909 (commit 0f90002) passed the OIDC sign-in and the **scope check** (a test deployment into
`rg-aron-scope-probe` was denied, creating a group was denied: N-012 acceptance proven), then submitted `main.bicep`
at 13:07 UTC with the **old** dev values (PostgreSQL General Purpose D2ds_v5 zone-redundant HA on SSD v2 with geo
backup, Front Door Standard, VNet-injected Container Apps environment, ZRS storage). Cancelling the GitHub job does
**not** cancel the ARM deployment, so these resources were most likely created (about USD 12 a day for PostgreSQL
alone). Their creation-time settings cannot be changed to the TEST profile in place.

- `infra/deploy.sh` now runs `infra/scripts/reset-to-profile.sh` in check mode before `main.bicep` and **stops without
  changing anything** when the group holds resources the profile cannot adopt, listing them.
- New manual workflow **reset** (GitHub > Actions > reset): dry run by default; deletes only the incompatible resources
  (PostgreSQL server, the VNet-injected environment with its apps and jobs, Front Door and WAF, ZRS storage and its
  Event Grid topic, VNet/NSGs/private DNS zone) when the resource group name is typed into "confirm". Key Vault, logs,
  registry, identities, alerts and budget are kept.
- **Owner decision: HOLD** (no reset). The reset now needs profile `dev-lite` and the words `owner approved reset`.

## Move runbook

`docs/setup/move-to-final-account.md` (new subscription, bootstrap, quotas, prod parameters, dump/restore and
reconciliation, blob copy, new Maps/Firebase keys, re-enrol phones, Day 6 proofs in the final account).

## First real deploy (CI run 35, commit 1991868): failed at the Azure sign-in, nothing reached Azure

- Container images and infra validation passed on GitHub's runner; the deploy job then failed at `azure/login`:
  `AADSTS700213: No matching federated identity record found for presented assertion subject
  'repo:asefameerador96-ctrl@252441038/Aron-Pro-Max@1403881436:environment:azure-dev'`.
- Cause: GitHub presents the **ID-qualified** subject for this repository; the bootstrap trusted only the classic
  `repo:<owner>/<repo>:environment:azure-dev`. Fixed in `infra/bootstrap-azure.ps1` (trusts both forms, exact match on
  this repository and environment only). **Owner action: re-run `infra/bootstrap-azure.ps1 -AlertEmails ...` once**
  (idempotent; it only adds the credential `github-environment-azure-dev-ids`). Then re-run the failed deploy job
  (GitHub > Actions > the latest ci run > Re-run failed jobs) or push any infra/backend change.
- The deploy now prints an explanation when the sign-in fails.

## Quotas (urgent owner action): `docs/setup/azure-quota-request.md`

The 10-vCPU snapshot is the VM quota, which Aron does not use. What the design needs: PostgreSQL Flexible Server
General Purpose Ddsv5 vCores 40 with zonal access, Container Apps consumption cores 100 per environment (after the
first deploy creates the environment), environment count 2, Azure Load Testing 40 engines. Dev stays pilot-sized
(PostgreSQL D2ds_v5 + standby = 4 vCores) until the load test.

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

### What waited for `infra/bootstrap-azure.ps1` (done 2026-10-06; re-run needed, see the top of this file)

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

- Next: N-062 observability (workbooks, sync-health and error alerts), the data-dictionary CI check when
  `docs/requests/db-data-dictionary-ci.md` arrives, N-057 as parameters for prod only, N-064 release candidate.

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
