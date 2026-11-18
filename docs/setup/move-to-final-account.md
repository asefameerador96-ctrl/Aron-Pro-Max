# Moving Aron from the TEST account to the FINAL account (runbook)

Owner: infra lane. Binding context: `docs/28-environment-profiles.md`, `docs/30-environments-git-release.md`. Rewritten
2026-11-18 (D-FINAL-INFRA-07) against the repository as it is today; the audit that led to it, the owner's ordered step table,
the quota numbers, the monthly cost by stage and the open decisions are in **`docs/status/infra-final.md`**: read that first.
Keep this file current with every infra change. Nothing here was run against a live final subscription; "live-only" marks what only
the final account can prove.

**Placeholders.** The repository is public: `<final-subscription-id>`, `<tenant-id>`, `<monitored-address>`, `<region>` are
filled in locally. Never commit or paste them.

## Where things stand

| Account | Environments | Profile files | Size |
|---|---|---|---|
| TEST (now) | `dev` (GitHub environment `azure-dev`, group `rg-aron-dev`); `dev-lite` is the cheap profile of the same environment after an owner-ordered reset | `infra/params/dev*.bicepparam` | 5 to 10 pilot users, no Front Door, no replica |
| FINAL (the move) | `stage` (`azure-stage`, group `rg-aron-stage`) and `prod` (`azure-prod`, group `rg-aron-prod`) | `infra/params/stage*.bicepparam`, `prod*.bicepparam` | `prod` sized for 8,500 SRs; `stage` is prod-shaped as committed (see D3 in the status file for the cheaper "staging-lite") |

The templates are the same everywhere; only the parameter file and the GitHub environment differ. The move is: new subscription,
quotas, two bootstrap runs, switches on, stage then prod, new Google keys, the release APKs for the final host, sign in again. It
needs two working days of operator time plus the quota lead time (up to a week).

## 0. The order, honestly: what can start now and what waits for the owner's approval of the release flow

`main` is still the 2026-10-07 baseline: it has **none** of the stage wiring (no `stage` choice in `deploy.yml`, no replica gate,
no lab-grant seeding). Staging deploys only from `main`, and `promote-prod` needs a soaked `main` commit with a `server-v*` tag. So
the staging and prod steps (s2) **cannot run until the owner approves the release flow (docs/30: the daily gate pull request from
the integration branch to `main`, gate tags, `server-v*` tags) and the first promotion to `main`**. Nobody opens that pull request or
pushes `main` before that approval (CLAUDE.md rule 11).

| Can be done NOW (needs no `main` change) | Waits for step 0 (the owner's approval and the first promotion to `main`) |
|---|---|
| quota requests (`docs/setup/azure-quota-request.md`); the Google / Firebase final project; provider registration | the `stage` deploy, the staging proofs and soak, the `server-v*` tag, `promote-prod`, the prod deploy |
| `tools/github-governance.ps1` and both `bootstrap-azure.ps1` runs (run from the laptop; the scripts are files, they do not need `main`) | the `app-v*` release run of `release-app.yml` for the final host needs the prod host, so it comes after prod |
| a **laptop what-if** of the final profile against the bootstrapped group: `AZURE_RESOURCE_GROUP=rg-aron-stage ARON_ENV=stage infra/validate.sh` (prod: `rg-aron-prod` and `ARON_ENV=prod`; `validate.sh` downloads Linux binaries, so run it on Linux or in WSL) (it runs `az deployment group what-if` when `az` is signed in; changes nothing) | the `whatif` and `ops-diagnose` workflows for stage and prod (they too run from `main` only) |
| the Apsis landing place and the dump upload (`docs/requests/apsis-dump-landing.md`) | |

**Step 0 itself (owner):** approve the release flow and the first promotion of the integration branch to `main` (the lead then opens
the gate pull request with the gate report). The bootstrap refuses to run before `tools/github-governance.ps1` has created the
GitHub environments, and the governance script leaves an existing `main` and its protection exactly as they are.

## 0a. Before the move day (one to two weeks ahead)

1. The owner holds **Owner** on the final subscription and has a final Google Cloud / Firebase project (D5).
2. **Quotas**: file every request in `docs/setup/azure-quota-request.md` (subscription-level ones now; the per-environment Container
   Apps cores after the environment exists). They can take days and can be refused for capacity.
3. Decide the region (default Southeast Asia) and whether the API gets a custom host name (D2). **The release APK bakes the API
   host**, so decide before the first `app-v*` release.
4. No schema freeze is needed: the final database starts **empty** and `migrate` applies every migration in order (the pre-flight
   of `docs/30` s4a runs first). Rule: **apply every migration to the empty database before any real data** (the Apsis load, real
   traffic). V0202 builds two unique indexes under a short exclusive lock; on an empty table that is milliseconds, and a shipped
   migration is never edited.

## 1. GitHub: environments first, then the bootstrap

Order matters: GitHub creates an environment unprotected the first time a job names it.

```powershell
az login --tenant <tenant-id>                 # as an Owner of the FINAL subscription (MFA)
gh auth login
az account set --subscription <final-subscription-id>

pwsh tools/github-governance.ps1              # azure-stage (main, owner is reviewer) and azure-prod (server-v* tags only, owner is reviewer)

.\infra\bootstrap-azure.ps1 -SubscriptionId <final-subscription-id> -Location <region> -ResourceGroup rg-aron-stage `
  -Environment azure-stage -AppName sp-aron-github-stage -AlertEmails "<monitored-address>"
.\infra\bootstrap-azure.ps1 -SubscriptionId <final-subscription-id> -Location <region> -ResourceGroup rg-aron-prod `
  -Environment azure-prod -AppName sp-aron-github-prod -AlertEmails "<monitored-address>"
```

Each bootstrap run creates its resource group and the (shared, empty) scope-probe group, one GitHub identity per environment limited
to that group (Contributor plus role-conditioned RBAC Administrator), the OIDC trust for that environment only (both the classic and
the id-qualified subject), and writes `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID` (secrets) and
`AZURE_RESOURCE_GROUP`, `AZURE_LOCATION`, `ARON_ALERT_EMAILS` (variables) **into that GitHub environment, never repository-wide**.
A job that names an environment reads its own values before any repository value of the same name, so both accounts coexist. The
script **refuses** `azure-stage` or `azure-prod` while the environment is missing or has no required reviewer (it must not
overwrite the governance script's protection).

**Move the TEST account's values into `azure-dev` once (the OWNER runs this in their own shell; no agent session handles the ids).**
Environment values win over repository values of the same name, so either order with the bootstrap works. GitHub cannot return a
secret's value, so the owner re-supplies the three `AZURE_*` secrets of the dev identity from the portal; `gh` prompts for the
value when `--body` is left out, so it never lands in a command line or transcript:

```bash
gh secret set AZURE_CLIENT_ID --env azure-dev            # paste when asked; same for AZURE_TENANT_ID and AZURE_SUBSCRIPTION_ID
gh variable set AZURE_RESOURCE_GROUP --env azure-dev --body rg-aron-dev    # same for AZURE_LOCATION, ARON_ALERT_EMAILS, ARON_BUDGET_AMOUNT, ARON_NAME_SUFFIX
# check with one manual `deploy` run of profile dev, THEN remove only these repository-wide values:
#   AZURE_CLIENT_ID, AZURE_TENANT_ID, AZURE_SUBSCRIPTION_ID, AZURE_RESOURCE_GROUP, AZURE_LOCATION, ARON_ALERT_EMAILS,
#   ARON_BUDGET_AMOUNT, ARON_NAME_SUFFIX.
# MUST STAY repository-wide: MAPS_ANDROID_KEY, MAPS_WEB_KEY, GOOGLE_SERVICES_JSON, FCM_SERVICE_ACCOUNT_JSON,
#   PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON (ci.yml and the dev deploy still read the TEST values), ANDROID_SIGNING_* (one release key
#   for both accounts), ARON_FINAL_ACCOUNT, ARON_PROD_API_BASE_URL, ARON_DEPLOY_FREEZE_DHAKA (repository-only by design).
```

Until the owner has set the final Google/Firebase values in the final environments, a stage or prod deploy falls back to the
repository-wide TEST values of the same name (it would copy the test FCM account into the final Key Vault): set them **before the
first stage deploy** (s4).

**If an earlier version of `tools/github-governance.ps1` (before 2026-11-18, this lane) was ever run live**, `azure-prod` may carry an
`app-v*` tag policy. Remove it by hand (owner): GitHub > Settings > Environments > azure-prod > Deployment branches and tags > delete `app-v*`
(or `gh api -X DELETE repos/<owner>/<repo>/environments/azure-prod/deployment-branch-policies/<id>` with the id from the GET of the same path). The script never deletes policies.

## 2. Final-account switches, staging, then prod (after step 0: needs the code on `main`)

1. Repository variable `ARON_FINAL_ACCOUNT=true`. Until it is true: `deploy.yml` refuses `stage` and `prod`, `infra/deploy.sh`
   refuses them again, `promote-prod.yml` and `release-app.yml` do nothing.
2. **Stage** (final account, from `main`): GitHub > Actions > `deploy` > Run workflow, branch **main**, environment **stage**. The
   run waits for the `azure-stage` reviewer. Before any Azure sign-in the first step checks `ARON_FINAL_ACCOUNT` and that the
   environment has a required reviewer (fails closed). `deploy.sh stage` runs `main.bicep` with `params/stage.bicepparam`,
   seeds Key Vault (including the **per-environment lab grant key**, see s5), builds and pushes the images, runs the migrations on
   the empty database, runs the `dblogins` job (`dbPerAppLogins` is on in stage), deploys the apps (the backend sees
   `ARON_ENV=prod`; stage is prod-shaped, so everything the backend keys on prod (strictness, alerts, integrity defaults) also applies in staging: expect it in the drills) and smoke-tests through Front Door.
3. **Read replica**: the first run of a server never asks for the replica (Premium SSD v2 must finish its first backup first:
   Microsoft Learn, "Premium SSD v2 storage option in Azure Database for PostgreSQL flexible server"). `deploy.sh` asks for it when
   it exists already or the primary has a completed backup; the what-if guard allows exactly the `<server>-r1` Create. So a **second
   deploy a few hours later** adds it. No variable to set.
4. Run the staging proofs and soak (load, failover, restore; `docs/23` s5), write the timings to `docs/status/infra.md`, request the
   Container Apps cores from `cae-aron-stage` > **Quota** (live-only: `docs/setup/azure-quota-request.md`).
5. Tag the soaked `main` commit `server-vX.Y.Z`; GitHub > Actions > **promote-prod** > Run workflow from that tag with the staging
   run's id. It checks tag on main, CI green, a successful job `Deploy to stage` of the same commit at least 24 h old, waits for the
   `azure-prod` approver, then `deploy.yml` runs `infra/deploy.sh prod` (`params/prod.bicepparam`: zone-redundant D8ds_v5 on SSD v2
   with geo backup, Front Door Premium + WAF + Private Link, private networking; per-app logins stay **off** in prod until the stage
   drills pass: D8).
6. **The phones' API host.** The prod run prints `Phone API origin` in its summary. Set the repository variable
   `ARON_PROD_API_BASE_URL` to exactly that `https://` origin (or to the custom host chosen in D2) **before tagging an app
   release**; the server publishes the same host as `ARON_PUBLIC_API_URL`, and a prod deploy warns loudly when the two differ.
7. Live-only, not yet proven anywhere: Front Door Premium with Private Link, direct-origin refusal, WAF false positives on
   `/v1/sync`, D8 on SSD v2 at 12,000 IOPS, the replica, the Multiple-revision fallback, the 1.5x fleet load.

### Prod preview and diagnose: from the owner's laptop only

`whatif` and `ops-diagnose` never run for prod (azure-prod admits `server-v*` tags only). With the owner signed in (`az login`, `az account set --subscription <final-subscription-id>`), on Linux or WSL:

```bash
AZURE_RESOURCE_GROUP=rg-aron-prod ARON_ENV=prod infra/validate.sh        # builds everything offline, then az deployment group what-if of main.bicep; changes nothing
AZURE_RESOURCE_GROUP=rg-aron-prod infra/scripts/diagnose.sh <since-utc> <until-utc> caj-aron-prod-migrate ca-aron-prod-worker metadata   # metadata and metrics only
```

## 3. Data

The final database starts **empty** (D6 in the status file; dev and the pilot hold synthetic data by rule). Nothing is dumped or
restored. Real data arrives by the Apsis history load and by real traffic.

- **Apsis history** (D-OWNER-APSIS-DUMP-01): `infra/apsis-landing.bicep` and `docs/requests/apsis-dump-landing.md` give the owner a
  private landing container in its own resource group and the exact `azcopy` commands to copy the dump from the organisation's FTP
  server through the owner's machine. The loader is the apsis-migration lane's.
- **Any one-off database task in the final account** (a restore, a rehearsal check, the loader) runs as a **Container Apps job
  inside `cae-aron-<env>`**: the database is on a delegated subnet with no public endpoint, so a laptop, Cloud Shell or a hosted
  runner cannot reach it. (Not built for a general restore; ask before relying on one.)
- Media: photos start empty. If synthetic pilot photos are ever wanted: `azcopy copy` between accounts with Entra sign-in (the
  accounts have shared keys disabled).
- The JWT signing key is created fresh in the final vault: every user signs in again once on the new host.

## 4. Google (Maps, Firebase)

1. In the final Google Cloud project create new keys: an Android Maps key restricted to the package names and the release
   certificate SHA-1, and a web Maps key restricted by referrer to the final web host. Daily caps and a budget alert as in `docs/28` rule 4.
2. New Firebase project: download the new `google-services.json` and a new FCM service account.
3. Store them in the GitHub environments (the owner runs these; the value is prompted for, not put on the command line):
   server side in **`azure-stage` and `azure-prod`**: `gh secret set FCM_SERVICE_ACCOUNT_JSON --env azure-prod`, same for `MAPS_WEB_KEY`
   (and `PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON` when Play Integrity is live); the app side in **`app-release`**:
   `gh secret set MAPS_ANDROID_KEY --env app-release`, same for `GOOGLE_SERVICES_JSON`. `release-app.yml`'s release job runs in
   `app-release` (owner approves each app release; tags `app-v*` only; it has **no Azure identity**, so an app tag can never sign in
   to Azure), `ci.yml` keeps reading the repository-wide TEST values for the dev APKs. The signing keystore secrets stay
   repository-wide (the same release key signs both: `docs/24` D24-49). Redeploy prod: the FCM account goes to the prod Key Vault,
   the web key becomes the Container Apps secret `maps-web-key`.
4. Revoke the test keys after the parallel run.

## 5. Phones (no MDM since D-OWNER-MDM-02)

There is **no device owner, no provisioning QR, no factory reset and no scheduled app blocking**; the old re-enrolment steps are
gone and `docs/setup/device-owner-test-phone.md` is obsolete. What a phone needs:

1. The **release APKs** from `release-app.yml` (tag `app-vX.Y.Z`, release notes in `docs/releases/<tag>.md`), built against the
   final API host (`ARON_PROD_API_BASE_URL`) and the final Firebase config, signed with the same release key. The run waits for the
   `azure-prod` reviewer. APKs are on the GitHub Release with their SHA-256.
2. **Install and sign in again.** On the pilot phone: install the release APK over the old one (same package and signing key) or
   after uninstalling it; the app opens against the final host; the user signs in again (new JWT key, new host). A phone with an
   unsent outbox must first upload to the TEST server (Sales Submit shows "reconciled" when that is safe); the TEST server stays
   until the parallel run ends (D4).
3. **Developer options must be off.** The apps refuse to run while Developer options (or USB debugging) are on, with a Bangla and
   English screen that says how to switch them off. Our own ADB walks need a **lab exemption**: a per-device grant signed by the
   server and cached on the phone.
4. **Lab grant key pair, one per environment.** The private half is the server secret `ARON_LAB_GRANT_KEY`; the public half is built
   into the APK (`android/core-system/src/main/res/values/lab_grant.xml`, present after train 121). For `stage` and `prod`,
   `infra/scripts/seed-secrets.sh` creates the pair once per Key Vault (`aron-lab-grant-key`, `aron-lab-grant-public`; never printed)
   and the api reads the private half through a Key Vault reference. The public half is not a secret and the vault is RBAC-only (the owner holds no data-plane right on it), so each
   deploy prints it in its summary as the row **"Lab grant public key (stage|prod; not a secret)"**: take it from there and build it
   into **that environment's** APK; **never reuse one key across the test and final servers**, or a test-server admin could sign a grant a
   production phone honours. Until the public half is built in, no release build honours any grant (fail closed) and ADB walks on
   release builds stay impossible. Injecting the per-environment public key at release build time is not done yet (follow-up after
   train 121; check the key format against `tools/make-lab-grant-key.sh`).
5. The server-side host rule: the APK's API host must equal `ARON_PUBLIC_API_URL` (s2.6).

## 6. After the move

1. Run the Day 6 proofs in the final account (`docs/23` s5): load test at 1.5 times the fleet, burst, failover and restore drills;
   flip `dbPerAppLogins` to `true` in `params/prod.apps.bicepparam` once they pass on the per-app logins in stage.
2. Keep the TEST account for the pilot during the parallel run (D4 default), then order the reset to `dev-lite` (GitHub > Actions >
   reset, profile `dev-lite`, confirm `owner approved reset`) rather than deleting it; delete the Apsis landing group at sign-off.
   `drill`, `reset`, `acr-purge` and `cost-report` are hard-wired to `azure-dev` on purpose and never touch the final account; `ops-diagnose` and `whatif` are read-only and run for dev and stage only (stage: metadata and metrics, no log lines); prod read-only checks run from the laptop; the final registry has no scheduled purge yet.
3. Update `docs/status/infra.md` with the final host names, the date of the move and the drill timings.
