# Infra-final lane status: readiness audit of the move to the FINAL Azure account

Lane F-INFRA1, branch `lane/infra-final` (from INT `a4691a06`, train 120), written 2026-11-18. Public repository: no
subscription ids, tenant ids, e-mail addresses, phone numbers or names appear here; placeholders are written
`<like-this>`.

## HANDOVER (read this first)

- **Scope held.** No Azure access, none attempted. No Azure resource created, changed or deleted. No deploy, promote
  or reset workflow dispatched. No pull request.
- **Order of work and where it stands.** All four deliverables are pushed; see "Progress log" at the bottom. What a successor
  does next: wait for the lead's reaction to the owner questions D1 to D8; compare the lab grant key format with
  `tools/make-lab-grant-key.sh` after train 121; decide with the lead and the apsis-migration lane the loader's network path
  (`docs/requests/apsis-dump-landing.md` s5.2); re-read the live required checks of `main`.
- **Section 2 wording correction.** Rows of the audit table describe the state BEFORE this lane's pushes ("missing"/"stale" =
  at INT `a4691a06`); the code, runbook, quota document and landing template that fix them are in the progress log.
- **Facts about INT that the task text is ahead of.** INT at `a4691a06` has migrations up to **V0204** and contract
  **1.20.0**. V0208 and 1.21.0 arrive only when train 121 lands; this file says "V0204 today, V0208 after train 121".
  `ARON_LAB_GRANT_KEY` and `lab_grant.xml` also arrive with train 121 (today only `docs/reviews/android-integrity-*`
  describe them; `Settings.kt` does not read the key yet and `lab_grant.xml` is not in `android/` yet). The infra
  wiring for the key is therefore written so that it is harmless before the backend reads it.
- **Decision ids** go at the END of `DECISIONS.md` as `D-FINAL-INFRA-NN`.
- **Recycle** a successor at about 450k tokens of context; the successor reads this file, then `git log lane/infra-final`.

## 1. Verdict in one paragraph

The templates (`infra/main.bicep`, `infra/apps.bicep`, `infra/modules/*`) already express the final topology and are
validated offline by `infra/validate.sh`. The **pipeline around them is not ready for the final account**: `stage` does
not exist in `deploy.sh`, `deploy.yml` or the bootstrap; the backend refuses `ARON_ENV=stage`; the read-replica path
cannot run (what-if guard, no variable forwarded); GitHub secrets and variables are repository-wide; four ops
workflows are hard-wired to `azure-dev`; the runbook still describes device-owner re-enrolment; and the committed
`stage` parameter file is prod-shaped (about the price of prod), not the "staging-lite" that `docs/35` costs. None of it
has run against a live final subscription, so "ready" can only mean "executable with the fewest owner steps and
correct in the repository". Everything marked **live-only** below can only be proven in the final account.

## 2. Section-by-section audit of `docs/setup/move-to-final-account.md`

Legend: **current** = still true today; **stale** = wrong or misleading today; **missing** = the runbook step has no
working mechanism in the repository.

| Runbook section | State | Finding (file references) | What this lane does |
|---|---|---|---|
| Intro (profiles, "two working days") | **stale** | Names the rehearsal profile `dev` "kept until 2026-10-10". `docs/status/infra.md` and the dev params show dev has long been the working shared dev environment with `dbPerAppLogins = true` (`infra/params/dev.apps.bicepparam:28`). The final account runs **two** environments (`stage` and `prod`), not one. | Rewrite (deliverable 3). |
| 0.1 hand-over of subscription and Google project | **current** | Owner action. | Keep; made explicit in the owner list (section 3). |
| 0.2 quotas | **stale** | `docs/setup/azure-quota-request.md` still carries the "WITHDRAWN FOR THE TEST ACCOUNT" banner and the numbers disagree with `docs/35` 02 s4.3 (40 vs 64 vCores; 100 vs 128 cores; `docs/33` B13.2 row 18). Stage plus prod in one subscription needs 56 to 64 vCores. | Rewrite with the numbers in section 3(b) below. |
| 0.3 region and host names | **current, open decision** | Default Southeast Asia (`docs/23` Q3). Premium SSD v2 with HA and geo-redundant backup is supported in Southeast Asia and East Asia (Learn: "Premium SSD v2 storage option in Azure Database for PostgreSQL flexible server", supported features). The API host that phones use is the Front Door `*.azurefd.net` name unless a custom domain is chosen; the APK is built against that host, so the host must be known **before** the first `app-v*` release (see 2.3 below). | Decision D3 in section 3(d). |
| 0.4 freeze schema | **stale** | A freeze is not needed and not realistic (migrations land on INT daily). The final database starts **empty** and `migrate` applies every migration in order, so the move is not exposed to schema drift. | Replace with: "start from empty, no dump, unless the owner asks for the pilot data". |
| 1 bootstrap (laptop, once) | **stale** | `infra/bootstrap-azure.ps1` creates one group and one environment per run, writes `AZURE_*` secrets and `AZURE_RESOURCE_GROUP`/`AZURE_LOCATION`/`ARON_ALERT_EMAILS` **repository-wide** (lines 160 to 173), so a second run for the final account would overwrite the test account's values and break dev. It also has a **default `-SubscriptionId` that is a real (test) subscription id** (line 24), which the rule for the public repository forbids. It cannot create `azure-stage`. The OIDC subjects are per environment already (`github-environment-<env>` and the id-qualified twin) which is correct. | Rewrite (deliverable 2): environment-scoped secrets and variables, mandatory `-SubscriptionId`, `-Stage`/`-Prod` friendly parameters. |
| 1 note "move secrets into the environments" | **missing** | Nothing moves them. The workflows read `secrets.AZURE_*`/`vars.AZURE_*` inside a job that has `environment:`, and GitHub resolves environment values before repository values of the same name, so scoping is purely a matter of **where the bootstrap writes them**. | Bootstrap writes with `--env`; a one-off migration step moves the existing test values to `azure-dev` (section 3, owner list step 9). |
| 2.1 final-account switches | **current** | `ARON_FINAL_ACCOUNT` and `ARON_PROD_API_BASE_URL` are read by `promote-prod.yml` (guard), `release-app.yml` (guard) and `deploy.yml` (prod branch of the first step). **`deploy.yml` does not check `ARON_FINAL_ACCOUNT` for stage because stage does not exist yet.** | Stage guarded the same way (deliverable 2). |
| 2.2 GitHub environments | **current, incomplete** | `tools/github-governance.ps1:99-109` creates `azure-stage` (branch `main`) **without a required reviewer** and `azure-prod` (owner reviewer, tag policy `server-v*`). The task requires a reviewer on stage too and a guard that fails closed when it is missing. `release-app.yml` uses no environment, so Maps and Firebase final keys cannot be scoped. | Governance script: reviewer on stage; `app-v*` tag policy on prod; guard job in `deploy.yml` (deliverable 2). |
| 2.3 staging first (docs/30 s2) | **missing** | `deploy.sh:31` rejects `stage` (`case dev\|dev-lite\|prod`); `deploy.yml` offers only `dev`/`dev-lite`, runs prod only through `promote-prod`; the backend rejects `ARON_ENV=stage` (`backend/platform/.../Settings.kt:77-80`; `ServerEnv` has only `DEV` and `PROD`) while `apps.bicep:124,182,441` set `ARON_ENV` to the environment name; so every stage api, worker and migrate replica would exit at start-up. `docs/33` B13.2 rows 2 and 35. | Deliverable 2. Fix without touching backend code: `apps.bicep` sends `ARON_ENV=prod` for any non-dev environment (stage is prod-shaped by design), keeping the Bicep `environmentName` for resource names. |
| 2.3 stage params | **stale** | `infra/params/stage.bicepparam` is **prod-shaped** (D8ds_v5, 1,024 GiB SSD v2, replica, Front Door Premium, RAGZRS replaced by ZRS, budget USD 2,500) which is not the USD 708 "staging-lite" that `docs/35` 03 prices, and the budget (2,500) is below what that shape costs. Stage VNet `10.41.0.0/16` collides with the docs/18 DR plan (B13.2 row 33). | Open decision D3 (size). Deliverable 2 wires the profile whichever size is chosen. |
| 2.4 `promote-prod` | **current** | The guard checks tag on main, CI green, a successful job named exactly `Deploy to stage` at least 24 h old (`promote-prod.yml:84-87`); the job name is `Deploy to ${{ inputs.environment }}` so it matches once stage deploys. | Kept; extended test in `check_infra.py`. |
| 2.5 first prod run without replica | **missing** | Not forwarded: `ARON_PG_READ_REPLICA` is read only through `readEnvironmentVariable` in the params; nothing in `.github`, `deploy.sh` or `infra/scripts` sets it, so the first prod run **would try to create the replica on a fresh SSD v2 server and fail** (Learn: wait for the initial backup before creating in-region replicas). Also `whatif-guard.py:49-51` refuses any second `flexibleServers` Create next to an existing one, which blocks the replica even later (B13.2 rows 36 and 37). | Deliverable 2: `deploy.sh` rule "no replica until the primary has a completed backup", plus a replica exception in the what-if guard with tests. |
| 2.6 Container Apps cores quota | **current** | Quota is per managed environment, requested from the environment's **Quota** page after it exists (Learn: "Request quota changes for Azure Container Apps"); the environment count is a subscription and region quota. | Kept in the quota document. |
| 3 Data: dump and restore | **stale + missing** | (a) Dev data is synthetic by rule (`docs/30` s1: "No real data outside prod"; dev is seeded by `db/seed`), so there is **nothing to carry over**; default: start empty. (b) If the owner still wants it, **no restore job exists**: `infra/apps.bicep` has `migrate` and `dblogins` jobs only; the final database is private (`privateNetworking = true`, `prod.bicepparam`), so a laptop cannot reach it. `devseed.Dockerfile` builds on `${PSQL_IMAGE}` but nothing runs `pg_restore`. (c) `--no-privileges` restore does not re-run `app.apply_db_role_grants()` or reset outbox consumers (B13.2 row 19). (d) The scale-down command uses `rg-aron-dev` names, fine for dev. | Default "start empty"; the optional restore path is documented as a dedicated on-demand `ops` job design, **not built** (see "Not done" below) unless the owner asks. |
| 3.6 photo copy | **current** | `azcopy` with Entra sign-in works on accounts with shared keys disabled (Learn: "Prevent Shared Key authorization", tools table lists AzCopy as Entra-capable). Synthetic photos only; skip. | Folded into the optional path. |
| 3.7 JWT key not copied | **current** | `seed-secrets.sh` creates a fresh `aron-jwt-signing-key` once per vault. | Kept. |
| Apsis history (D-OWNER-APSIS-DUMP-01) | **missing** | The runbook says nothing about it; standing rule 3 was superseded for the final account. No landing place exists. | Deliverable 4: private storage account and `docs/requests/apsis-dump-landing.md`. |
| 4 Google (Maps, Firebase) | **current, one gap** | Names match `ci.yml`/`release-app.yml`: `MAPS_ANDROID_KEY`, `MAPS_WEB_KEY`, `GOOGLE_SERVICES_JSON`, `FCM_SERVICE_ACCOUNT_JSON`, `PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON`. `ci.yml` (dev APKs) reads the **repository** values (test keys) so they must stay repository-wide; the final values must be **environment** scoped to `azure-prod`, which only works for a job that declares that environment. `release-app.yml` declares none. The signing keystore secrets stay repository-wide (the same release key signs both, `docs/24` D24-49). | `release-app.yml` release job declares `environment: azure-prod`; governance adds the `app-v*` tag policy. |
| 5 Phones, steps 1 to 3 | **stale (MDM)** | Step 2 "re-enrol with a provisioning QR" is **obsolete** since D-OWNER-MDM-02 (2026-11-13): no device owner, no QR enrolment, no factory reset. `docs/setup/device-owner-test-phone.md` is likewise obsolete. The truth: install the release APK built for the final API host, sign in again; the apps refuse to run while Developer options are on, and a per-device **lab exemption** (server-signed grant) keeps ADB walks possible. | Rewrite (deliverable 3). |
| 5 lab grant key pair | **missing** | One P-256 key pair **per environment**: the private half is the server secret `ARON_LAB_GRANT_KEY` (PKCS#8, base64), the public half is `android/core-system/src/main/res/values/lab_grant.xml` in the APK. The review `docs/reviews/android-integrity-slice3-2026-11-18.md` already flags that the payload carries no environment claim, so **one shared key across test and final accounts would let a test-server admin sign a grant the production fleet honours**. Not wired anywhere in `infra/` or `.github/` (review slice3 part 2). | Key Vault secret `aron-lab-grant-key` created once per vault by `seed-secrets.sh`; api env `ARON_LAB_GRANT_KEY` through a Key Vault reference; the public half is published as Key Vault secret `aron-lab-grant-public` and as a documented owner step (build-time injection into `lab_grant.xml` lands with train 121, listed under "Not done"). |
| 5 `ARON_PUBLIC_API_URL` = APK host | **stale as written, rule restated** | `apps.bicep:137` computes `publicApiUrl` as the Front Door host (or the Container Apps host without Front Door) and passes it as `ARON_PUBLIC_API_URL` (`apps.bicep:309`). The release APK is built against `vars.ARON_PROD_API_BASE_URL`. **The two must be the same host**, otherwise the server's own self-references (and the integrity checks that compare the host) disagree with the phone. Nothing checks it today. | New check in `release-app.yml` guard and in `deploy.sh` summary (deliverable 2): after a prod deploy the script prints the real host, and `promote-prod`/`release-app` compare it with `ARON_PROD_API_BASE_URL`. |
| 5a rehearsal profile | **stale** | Refers to the 2026-10-10 review; done. Rehearsal proofs (forced failover about 30 s, WAF) are in `docs/status/infra.md`. | Remove; keep a pointer. |
| 6 after the move | **stale** | "Keep the TEST account for a week as a fallback, then delete rg-aron-dev" conflicts with the open decision on keeping the test account for the pilot during the parallel run (D4 below). | Rewrite after the owner's answer; default stated. |

## 2a. The ten known drifts of the task text, each checked against today's tree

| # | Drift | Today's truth | Where |
|---|---|---|---|
| 1 | MDM / provisioning QR in runbook s5 | Obsolete (D-OWNER-MDM-02). Removed in deliverable 3. | `docs/setup/move-to-final-account.md` s5, `docs/setup/device-owner-test-phone.md` |
| 2 | Lab grant key pair per environment | Not in `infra/`; see table above. | `docs/reviews/android-integrity-slice3-part2-2026-11-18.md` |
| 3 | `ARON_PUBLIC_API_URL` must equal the APK API host | True and unchecked. | `infra/apps.bicep:137,309` |
| 4 | Migrations "end at V0208, contract 1.21.0" | **V0204 and 1.20.0 today**; V0208 and 1.21.0 after train 121. Nothing in the infra depends on the number: `migrate` applies what the image carries; `MigrationPreflight` runs first (`docs/30` s4a). | `db/migrations/`, `contract/openapi.yaml:4` |
| 5 | Waiting-item sweep runs in the API | True: V0204 adds `cfg.sync.waiting_sweep_*` keys and the pass runs in the API process (`Wiring.kt`). Infra consequence: the api needs **no extra identity or schedule**, but the api scale-to-N means N sweepers, so the sweep must be idempotent per item (backend's `waiting_since` marker is). Not an infra change. The kill switch is `cfg.sync.waiting_sweep_enabled`, no deploy needed. | `db/migrations/V0204__cfg_waiting_sweep_keys.sql` |
| 6 | ci has five web e2e shards | True (`ci.yml:699` "shard N of 5") plus two analytics shards (`ci.yml:399`). `check_infra.py` and `docs/30` s3 list **8 required checks** for `main`; the e2e shards and analytics shards are not named there, so **the required-checks list on `main` must be re-read from the live repository before the first gate pull request** (laptop operator, read-only). | `.github/workflows/ci.yml`, `tools/github-governance.ps1` |
| 7 | `stage` is only parameter files | True and fixed by deliverable 2. | `infra/params/stage*.bicepparam` |
| 8 | Secrets and variables repository-wide | True (`bootstrap-azure.ps1:160-173`). | deliverable 2 |
| 9 | Prod profile switches | `ARON_FINAL_ACCOUNT` (repo variable, gates `deploy.yml`, `promote-prod.yml`, `release-app.yml`) and `ARON_PROD_API_BASE_URL` (repo variable, gates `release-app.yml`). Both stay repository-level (read by jobs that have no environment); they do not collide because the test account never sets them. | workflows |
| 10 | Key Vault seeding list | See 2b. | `infra/scripts/seed-secrets.sh` |

## 2b. Key Vault: what exists, what the final account needs

Seeded by `infra/scripts/seed-secrets.sh` on every deploy (idempotent): `aron-jwt-signing-key` (ES256 PEM, once),
`aron-jwt-kid`, `aron-fcm-service-account` (from `FCM_SERVICE_ACCOUNT_JSON`, `{}` when unset),
`aron-play-integrity-service-account` (from `PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON`, a single space when unset),
`aron-web-session-secret`, `aron-mfa-key` (once, never replaced: rotation is a runbook). Created by the templates and
`db-password.sh`/`db-login-secrets.sh`: the admin password, `aron-db-url`, `aron-db-direct-url`, `aron-db-read-url`,
per-app login secrets. Dev only: `aron-dev-seed-password`, `aron-dev-smoke-device-key`.

Final account additions, all created by this lane's script change and never printed: `aron-lab-grant-key` (P-256
PKCS#8, base64, once per vault) and `aron-lab-grant-public` (its public half, not a secret). The Maps web key is a
Container Apps secret set by `deploy.sh` from `MAPS_WEB_KEY` (not a vault secret). **Owner-supplied** values for the
final account (GitHub secrets scoped to the `azure-prod` and `azure-stage` environments): `FCM_SERVICE_ACCOUNT_JSON`
(new Firebase project), `PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON` (optional until Play Integrity is live, B13.2 row 24),
`MAPS_WEB_KEY`.

## 2c. Other findings the owner list depends on

- **Runtime database roles.** `docs/requests/db-runtime-roles-gaps.md` is closed (V0029 on INT; `dbPerAppLogins` is
  `true` in `dev` and `dev-lite`) but **`false` in `stage` and `prod`** (`infra/params/stage.apps.bicepparam:27`,
  `prod.apps.bicepparam:25`), so api and worker would run as the admin login. Six weeks of green dev deploys exist.
  Recommendation: flip **stage** to `true` in this lane; flip **prod** only after the stage drill passes (decision D8).
- **V0202 and concurrent index builds.** V0202 (`attendance_event_once_rep`, `attendance_event_once_phone`) builds two
  unique indexes under an `ACCESS EXCLUSIVE` lock with a 60 s NOWAIT retry loop. A shipped migration is never edited
  (`CLAUDE.md`, forward-only; Flyway checksums), so "redo it" cannot mean "edit V0202". On the **final** account it
  runs against an **empty** table at the first migrate, so the lock lasts milliseconds. The risk exists only if V0202
  is applied **after** fleet data exists, which the empty-start order avoids. Rule recorded for the owner and the
  Apsis lane: **apply every migration to the empty final database before the Apsis load or any real traffic.** For
  future index work on big tables the pattern is a new migration with `CREATE INDEX CONCURRENTLY` (Flyway needs
  `executeInTransaction=false` for that file); that is db-lane work, not infra.
- **Private networking and how a job reaches the database.** `privateNetworking = true` (stage, prod) puts
  PostgreSQL on a delegated subnet (`snet-pg`, `/28`) with no public endpoint, and the Container Apps environment in
  `snet-aca` (`/23`). Only things **inside the VNet** reach the database: the api, worker, `migrate` job and `dblogins`
  job already do. A laptop, Cloud Shell and GitHub-hosted runners do not. Consequence: **every one-off database task in
  the final account (a restore, a PITR rehearsal check, the Apsis loader) must run as a Container Apps job in
  `cae-aron-<env>`** (same identity pattern as `migrate`). The Apsis lane's loader needs such a job; the dump itself
  lands in storage (deliverable 4) which **is** reachable from the owner's machine through Entra.
- **Other workflows hard-wired to `azure-dev`**: `drill.yml:37`, `reset.yml:38`, `acr-purge.yml:35`, `cost-report.yml:22`,
  `ops-diagnose.yml:45` (B13.2 row 15). Correct for now (they must not touch the final account); the final registry
  has no scheduled purge and the final cost report does not run. Listed as follow-ups in section 4, not changed here.
- **Budget, Service Health alert, DR, WAF Block, private endpoints for Key Vault/ACR/Storage** are designed but not
  in the templates (`docs/33` B13.2 rows 3, 13, 17); not part of this move.

## 3. What the owner has to do, and what it costs

### (a) Owner steps, in order

Everything that is not a login can be run by the laptop operator session once the owner has done step 3. "Owner
only" means: needs the owner's account, MFA, approval, billing or hands.

| # | Step | Who | Command or portal path |
|---|---|---|---|
| 0 | **Approve the release flow and the first promotion of the integration branch to `main`** (docs/30 s2: daily gate pull request, gate tags, `server-v*` tags). Until then `main` has none of the stage wiring, so steps 10 and 13 cannot run; steps 1 to 9, 11, 12 (key creation), 14 and 16 can. No pull request is opened and `main` is not pushed before this approval. | Owner only | a message to the lead |
| 1 | **Answer the open decisions D1 to D8** (section 3d), at least D1, D3, D5 and the budget. | Owner only | A message to the lead. Defaults are given; "go with the defaults" is a valid answer. |
| 2 | **Hand over the subscription**: the owner holds **Owner** on the final subscription; note the subscription id and tenant id privately (never in the repository). Make sure the cost-management policy for budgets is on (`deploy.sh` warns when Azure refuses budgets). | Owner only | Portal > Subscriptions > the final subscription > Access control (IAM) > confirm "Owner"; Cost Management + Billing > Cost Management > Settings > "Enable cost view/budget" if prompted. |
| 3 | **Sign in on the laptop with MFA**, in the shell the operator will use. | Owner only (MFA) | `az login --tenant <tenant-id>`; `az account set --subscription <final-subscription-id>`; `gh auth login` |
| 4 | **Quota requests** (section 3b). Subscription-level ones can be filed now; the per-environment Container Apps cores request is filed after the environment exists (step 9). Lead time up to about a week; a region can refuse for capacity. | Owner only (portal, billing identity) | Portal > Quotas > Azure Database for PostgreSQL / Azure Container Apps / Azure Load Testing > the region > New limit. Support-request route: portal > Help + support > Create a support request > Service and subscription limits (quotas). Text to paste is in `docs/setup/azure-quota-request.md`. |
| 5 | **Register resource providers** (non-blocking; the bootstrap does it, listed for completeness). | Operator | `.\infra\bootstrap-azure.ps1` does `az provider register` for the list in the script. |
| 6 | **GitHub governance**: create the environments with their reviewers and branch/tag policies. Review the plan the script prints, then approve. | Owner approves; operator runs | `pwsh tools/github-governance.ps1` (creates `azure-stage` with branch `main` and the owner as reviewer, `azure-prod` with tags `server-v*` and `app-v*` and the owner as reviewer). **Must be run before the first workflow run**, otherwise GitHub creates the environment unprotected.  **A re-run leaves an existing `main` and its protection untouched** (re-read the live required checks in Settings > Branches before the first gate pull request). `app-release` (tags `app-v*`, owner reviewer, no Azure identity) is created here too. |
| 7 | **Bootstrap the final subscription twice** (stage and prod are separate resource groups, identities and environments). Needs step 6 first: the script refuses an environment that is missing or has no required reviewer. | Operator (after steps 3 and 6) | `.\infra\bootstrap-azure.ps1 -SubscriptionId <final-subscription-id> -Location <region> -ResourceGroup rg-aron-stage -Environment azure-stage -AppName sp-aron-github-stage -AlertEmails "<monitored-address>"`, then the same with `-ResourceGroup rg-aron-prod -Environment azure-prod -AppName sp-aron-github-prod`. Writes **environment-scoped** secrets (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`) and variables (`AZURE_RESOURCE_GROUP`, `AZURE_LOCATION`, `ARON_ALERT_EMAILS`); the test account's values are not touched. |
| 8 | **Move the TEST account's repository-wide `AZURE_*`/`ARON_ALERT_EMAILS`/budget/suffix values into `azure-dev`** so both accounts coexist (environment values win; either order with step 7 works). The OWNER runs it in their own shell and pastes the values when `gh` asks (no `--body`, so no id reaches a session transcript): `gh secret set AZURE_CLIENT_ID --env azure-dev` (same for `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`), `gh variable set AZURE_RESOURCE_GROUP --env azure-dev --body rg-aron-dev` (same for the others). **Must stay repository-wide:** Maps, Google services, FCM and Play Integrity values (ci.yml reads them), `ANDROID_SIGNING_*`, `ARON_FINAL_ACCOUNT`, `ARON_PROD_API_BASE_URL`, `ARON_DEPLOY_FREEZE_DHAKA`. | Owner |
| 9 | **Switch the final account on** (repository variables, not secrets). First `ARON_FINAL_ACCOUNT=true`; `ARON_PROD_API_BASE_URL` only after the first stage/prod deploy has created the Front Door host (or after a custom domain is chosen: then it is known in advance). | Operator | `gh variable set ARON_FINAL_ACCOUNT --body true`; later `gh variable set ARON_PROD_API_BASE_URL --body https://<api-host>` (must equal the host printed in the prod deploy summary). |
| 10 | **(after step 0)** **Staging deploy**, soak and drills. Dispatch of the `deploy` workflow with profile `stage` from `main`; the reviewer (owner) approves in the GitHub UI. Run the load, failover and restore drills; record results in `docs/status/infra.md`. | Owner approves the gate; operator dispatches | GitHub > Actions > deploy > Run workflow > branch `main`, environment `stage`. Then CLI-run drills with the owner's phrase. After the environment exists: Container Apps cores request from `cae-aron-stage` > Settings > **Quota** (portal). |
| 11 | **Google and Firebase final project**: create it, link billing, create keys (Android Maps restricted to package names and release SHA-1; web Maps by referrer; Firebase Android apps; FCM service account), set daily caps and a budget alert. | Owner only (Google account, billing) | `docs/setup/chrome-google-firebase-github.md` is the step list. Then the operator stores the values: `gh secret set MAPS_ANDROID_KEY --env azure-prod`, `MAPS_WEB_KEY`, `GOOGLE_SERVICES_JSON`, `FCM_SERVICE_ACCOUNT_JSON` (same for `azure-stage` with a separate stage Firebase app if D7 says so). |
| 12 | **Lab grant public key for each environment** (stage and prod; after train 121 lands). The deploy's seed step creates the pair once per vault (public half first); the **deploy summary prints the public half** as a row (not a secret; the vault is RBAC-only and the owner has no data-plane right on it, so no `az keyvault secret show`). Build it into that environment's APK. Owner decides which phones are lab phones. | Operator reads the summary |
| 13 | **(after step 0 and the soak)** **Tag and promote**: tag `server-vX.Y.Z` on the soaked `main` commit, run `promote-prod` from the tag with the staging run id, approve. | Owner approves; operator dispatches | `git tag server-vX.Y.Z <sha> && git push origin server-vX.Y.Z`; GitHub > Actions > promote-prod > Run workflow (Use workflow from the tag) with `staging_run_id`. The first prod deploy runs without the replica; a second deploy adds it after the first backup finished (hours to a day). |
| 14 | **Apsis dump upload** (deliverable 4): from the owner's own machine, never through CI, the repository or an agent container, and **only through `infra/scripts/apsis-landing.sh`** (it refuses the TEST subscription, a wrong subscription id and a missing acknowledgement before anything is created or uploaded). | Owner only | commands in `docs/requests/apsis-dump-landing.md` |
| 15 | **Phones**: build the release APKs for the final host (`app-vX.Y.Z` tag, `release-app`, approve), install on the pilot phones (the release APK from the GitHub Release), sign in again. No QR, no factory reset. | Owner and pilot users (hands) | GitHub > Releases > the `app-v*` release > `aron-*.apk`; Android: allow install from the browser or file manager (or `adb install -r <apk>` on lab phones, which needs a lab grant, step 12). |
| 16 | **Cost and budget yes**: confirm the monthly budget values that `deploy.sh` will deploy (`ARON_BUDGET_AMOUNT` per environment). | Owner only | `gh variable set ARON_BUDGET_AMOUNT --env azure-prod --body <usd>`. |
| 17 | **Custom domains** (if D2 = yes): add the DNS records Front Door asks for; certificates are managed. Separate lane work later; not blocking the first deploy. | Owner (DNS) | Front Door > Domains > Add; DNS TXT validation record at the registrar. |

### (b) Quota requests for the final subscription and region

Region **Southeast Asia** unless D1 changes it. Figures from `docs/35` 02 s4.3 and `docs/33` B13.2 row 18; the
arithmetic is replayed in `docs/setup/azure-quota-request.md`.

| Quota | Request | Scope | Why |
|---|---|---|---|
| PostgreSQL flexible server vCores, General Purpose Ddsv5, Southeast Asia, **with zonal access** | **64** if staging is prod-shaped (the committed `stage.bicepparam`); **40** if staging is "lite" (D2ds_v5) | subscription and region | prod 8 primary + 8 standby + 8 replica = 24, plus a restore-drill server 8 = 32, headroom 8 = 40; stage prod-shaped adds 24 = 64. Staging-lite adds 4 (2 + its 2 standby; no replica), 36 of 40. |
| PostgreSQL vCores, East Asia | 24 (only when the DR tier is approved, before wave 2) | subscription and region | cross-region replica 8, after promotion 8 + 8 standby |
| Container Apps **Managed Environment Consumption Cores** | **100** for `cae-aron-prod`; **100** for `cae-aron-stage` during test windows | per managed environment, requested from the environment's Quota page after it exists | replica maxima api 30 + worker 10 + web 3 + migrate 0.5 = 43.5 vCPU; a revision swap briefly doubles the api (Multiple revision mode) |
| Container Apps **Managed Environment Count** | **2** in Southeast Asia (stage + prod), **1** in East Asia (DR) | subscription and region (portal > Quotas > Azure Container Apps) | one environment per stage and region |
| Azure Load Testing: concurrent engine instances / instances per run / test duration | **40 / 40 / 24 h** | subscription and region | 1.5 x 9,842 users at about 500 per engine = 30 engines. Needed only for the one certified run (about USD 1,905); the Locust-on-Jobs alternative needs no quota. |
| Total Regional vCPUs (VMs), Front Door, Key Vault, Storage, ACR, Log Analytics, Event Grid | **none** | | Aron runs no virtual machines; defaults are far above one of each. |

A quota request is not a capacity guarantee: Learn ("Troubleshoot environment capacity errors in Azure Container
Apps") says capacity errors are not quota errors and are solved by retrying later or by another region.

### (c) Monthly cost by stage (USD, list price, 730-hour month, `docs/35` 03, prices read 2026-10-29)

| Stage | USD a month | What it is | Source |
|---|---:|---|---|
| **Staging only**, "staging-lite" | **about 708** | D2ds_v5 zone-redundant HA, 128 GiB, three small apps, Front Door Standard, ACR Standard, 1 GB/day logs; scaled to prod shape only inside a test window (about 75 per 8-hour window); the edge proof with Front Door Premium and Private Link adds about 295 | 03, "Optional and non-production lines" #1 |
| **Staging as committed** (prod-shaped `stage.bicepparam`) | **not modelled**; upper bound is prod core 5,490 if run 24 hours a day | D8ds_v5 with HA and replica, SSD v2 1,024 GiB, Front Door Premium, ACR Premium | 03 does not price it; ceiling taken from prod core |
| **Prod core** (day 1, in the templates) | **about 5,490** | database 2,683, compute 1,233, logs 552, storage 534, edge 403, plus about 28 for items designed but not yet templated | 03 headline |
| **Prod + DR tier** (East Asia replica, before wave 2) | **about 6,702** | core 5,490 + DR 1,212 | 03 totals |
| Spec: prod + DR + buffer | about 8,702 | budget recommendation USD 9,000 with alerts at 50/90/100 % | 03 totals |
| Spec+: plus the existing web properties moved into the account | about 9,544 | assumptions until inventoried | 03 totals |
| 1-year PostgreSQL reservations (24 vCores SEA, 8 East Asia), after 30 stable days at D8 | Spec+ falls to about 8,374 | | 03 reservation lever |
| One certified load-test run | about 1,905 per run | Locust runs about 20 each | 03 one-off |

Note: `infra/params/prod.bicepparam` sets `budgetAmount` default **7,000** and `stage.bicepparam` **2,500**; with DR and
the buffer the recommended 9,000 needs the owner's yes (step 16).

### (d) Open decisions, each with a recommended default

| # | Decision | Recommended default | Why |
|---|---|---|---|
| D1 | **Region.** | **Southeast Asia** for prod and stage; **East Asia** for DR (later). | Premium SSD v2 with HA and geo-redundant backup is supported there (Learn, Premium SSD v2 article) and the quota text already names it. Legal position on data in Singapore is open (B13.3 OD-1, docs/21); the owner decides with counsel **before any real data**. |
| D2 | **Custom domains** (`api.`, `app.`). | **Yes for the API host**, decided **before** the first `app-v*` release, because the APK bakes the API host and a hash-named `*.azurefd.net` host is only known after the first infra deploy. No for the web portal at first. | Avoids rebuilding the fleet's APK if the host changes; the Front Door managed certificate is free. If no domain: stage deploys first, the Front Door host is read from the prod deploy summary and set as `ARON_PROD_API_BASE_URL` before tagging the app. |
| D3 | **Staging size.** | **Staging-lite (about 708 a month)**, scaled to prod shape only in the 8-hour test windows; keep a **separate** `stage-lite` parameter file so the committed prod-shaped `stage` stays available for the load windows. | Prod-shaped 24x7 staging would nearly double the bill for a rehearsal environment (docs/28 rule 2 spirit). Needs the owner's yes before the first final-account deploy. |
| D4 | **Keep the test account for the pilot during the parallel run?** | **Yes, for the pilot until prod passes its first drills**, then run the reset to `dev-lite` (not delete). | Pilot phones keep working while prod is rehearsed; environment-scoped secrets make coexistence safe; the test budget is small. |
| D5 | **Google and Firebase final project.** | **Create a new final project now**, separate Firebase apps for stage and prod, keys restricted to package names and the release SHA-1; keep test keys for dev APKs. | `docs/28` rule 4; Firebase config is per package and per environment; avoids a pilot push token mixing with prod. |
| D6 | **Start empty or restore the pilot data.** | **Start empty.** | Dev data is synthetic by rule; real data comes from the Apsis load. Removes the missing restore job and B13.2 row 19. |
| D7 | **Separate Firebase/FCM for stage.** | **Reuse the test project for stage**, new project for prod. | Stage has no real phones. |
| D8 | **Flip `dbPerAppLogins` in stage and prod.** | **Stage true now, prod true after the stage failover and restore drills pass.** | Green on dev since 2026-10-07; flipping prod without a drill is the B13.2 row 1 risk. |

## 4. Not done in this lane, and not verifiable without the live account

- Nothing here was run against Azure. `infra/validate.sh` proves the templates compile, lint, and meet
  `check_infra.py`; the **what-if, quota, RBAC, Private Link approval, Front Door, replica creation after the first
  backup, WAF Block, DR, load, failover and restore** can only be proven in the final subscription.
- Not built (needs a decision or another lane): a restore/loader Container Apps job (D6 default avoids the need; the
  Apsis lane needs one); DR tier; private endpoints for Key Vault, ACR and Storage; per-secret Key Vault RBAC;
  scheduled ACR purge and cost report for the final account; the `ops-diagnose`/`drill`/`reset` workflows for stage
  and prod (deliberately dev-only until the owner asks).
- The lab grant public key reaching `lab_grant.xml` at release build time depends on train 121 (the file does not
  exist on INT yet).
- Re-read the live required-checks list of `main` (e2e and analytics shards) before the first gate pull request.

## 5. Progress log (updated per push)

| Deliverable | State |
|---|---|
| 1. This audit | pushed (this commit) |
| 2. `stage` wired end to end | pushed (D-FINAL-INFRA-02 to -05); `infra/validate.sh` green locally (bicep build and lint, `check_infra.py` incl. the new `FinalAccountStage` tests, actionlint, shellcheck) |
| 3. Runbook and quota document | pushed (docs-only: ci's path filter starts no run) |
| 4. Apsis dump landing place | pushed (head 2f95a5687): `infra/apsis-landing.bicep`, `ApsisLanding` tests, `docs/requests/apsis-dump-landing.md` (layout proposal for the apsis-migration lane, owner's azcopy commands); D-FINAL-INFRA-06. Open: the loader's network path (private endpoint recommended). |
