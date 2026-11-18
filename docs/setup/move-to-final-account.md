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

## 0. Before the move day (one to two weeks ahead)

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

pwsh tools/github-governance.ps1              # azure-stage (main, owner is reviewer) and azure-prod (server-v* and app-v* tags, owner is reviewer)

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

**Move the TEST account's values into `azure-dev` once** (so the test deploys keep working and nothing repository-wide is left to
collide): re-supply the three `AZURE_*` secrets of the dev identity (GitHub cannot return a secret's value) and set the variables
on the environment, then delete the repository-wide copies:

```bash
gh secret set AZURE_CLIENT_ID --env azure-dev            # paste when asked; same for AZURE_TENANT_ID, AZURE_SUBSCRIPTION_ID
gh variable set AZURE_RESOURCE_GROUP --env azure-dev --body rg-aron-dev    # same for AZURE_LOCATION, ARON_ALERT_EMAILS, ARON_BUDGET_AMOUNT, ARON_NAME_SUFFIX
# check with one manual `deploy` run of profile dev, then remove the repository-wide values (Settings > Secrets and variables)
```

## 2. Final-account switches, staging, then prod

1. Repository variable `ARON_FINAL_ACCOUNT=true`. Until it is true: `deploy.yml` refuses `stage` and `prod`, `infra/deploy.sh`
   refuses them again, `promote-prod.yml` and `release-app.yml` do nothing.
2. **Stage** (final account, from `main`): GitHub > Actions > `deploy` > Run workflow, branch **main**, environment **stage**. The
   run waits for the `azure-stage` reviewer. Before any Azure sign-in the first step checks `ARON_FINAL_ACCOUNT` and that the
   environment has a required reviewer (fails closed). `deploy.sh stage` runs `main.bicep` with `params/stage.bicepparam`,
   seeds Key Vault (including the **per-environment lab grant key**, see s5), builds and pushes the images, runs the migrations on
   the empty database, runs the `dblogins` job (`dbPerAppLogins` is on in stage), deploys the apps (the backend sees
   `ARON_ENV=prod`; stage is prod-shaped) and smoke-tests through Front Door.
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
3. Store them **in the GitHub environment `azure-prod`** (and `azure-stage` if stage gets its own Firebase app):
   `gh secret set MAPS_ANDROID_KEY --env azure-prod`, same for `MAPS_WEB_KEY`, `GOOGLE_SERVICES_JSON`, `FCM_SERVICE_ACCOUNT_JSON`
   (and `PLAY_INTEGRITY_SERVICE_ACCOUNT_JSON` when Play Integrity is live). `release-app.yml`'s release job runs in `azure-prod`, so
   it reads these; `ci.yml` keeps reading the repository-wide TEST values for the dev APKs. The signing keystore secrets stay
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
   and the api reads the private half through a Key Vault reference. Read the public half on the laptop
   (`az keyvault secret show --vault-name <kv> --name aron-lab-grant-public --query value -o tsv`) and build it into **that
   environment's** APK; **never reuse one key across the test and final servers**, or a test-server admin could sign a grant a
   production phone honours. Until the public half is built in, no release build honours any grant (fail closed) and ADB walks on
   release builds stay impossible. Injecting the per-environment public key at release build time is not done yet (follow-up after
   train 121; check the key format against `tools/make-lab-grant-key.sh`).
5. The server-side host rule: the APK's API host must equal `ARON_PUBLIC_API_URL` (s2.6).

## 6. After the move

1. Run the Day 6 proofs in the final account (`docs/23` s5): load test at 1.5 times the fleet, burst, failover and restore drills;
   flip `dbPerAppLogins` to `true` in `params/prod.apps.bicepparam` once they pass on the per-app logins in stage.
2. Keep the TEST account for the pilot during the parallel run (D4 default), then order the reset to `dev-lite` (GitHub > Actions >
   reset, profile `dev-lite`, confirm `owner approved reset`) rather than deleting it; delete the Apsis landing group at sign-off.
   `drill`, `reset`, `acr-purge`, `cost-report` and `ops-diagnose` are hard-wired to `azure-dev` on purpose and never touch the final
   account; the final registry has no scheduled purge yet.
3. Update `docs/status/infra.md` with the final host names, the date of the move and the drill timings.
