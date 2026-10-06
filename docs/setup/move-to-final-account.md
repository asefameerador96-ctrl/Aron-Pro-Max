# Moving Aron from the TEST account to the FINAL account (runbook)

Owner: infra lane. Binding context: `docs/28-environment-profiles.md`. Keep this file current with every infra change.

The test subscription runs one of two profiles: the **rehearsal profile** `dev` (`infra/params/dev*.bicepparam`: the
full-size topology kept by the owner's 2026-10-06 exception until the review on 2026-10-10: zone-redundant PostgreSQL
D2ds_v5 with geo backup, Front Door + WAF, VNet) or the cheap **TEST profile** `dev-lite`
(`infra/params/dev-lite*.bicepparam`, 5 to 10 pilot users), used after an owner-ordered reset. The final account runs
the **FINAL profile** (`infra/params/prod*.bicepparam`, 8,500 SRs). The templates are the same; the move
is: new subscription and identity, quotas, `prod` parameters, restore the data, new Google keys, re-point and re-enrol
the phones. Plan **two working days** plus the quota lead time (up to a week).

## 0. Before the move day (one to two weeks ahead)

1. The sponsor hands over the final Azure subscription (Owner rights for the setup person) and a final Google Cloud /
   Firebase project.
2. **Quotas**: submit every request in `docs/setup/azure-quota-request.md` in the final subscription and region; they
   can take days and can be refused for capacity.
3. Decide the region (default Southeast Asia, `docs/23` Q3) and the public host names (API, web) if custom domains are
   wanted; Front Door's own `*.azurefd.net` host works without one.
4. Freeze schema changes for the move week (migrations must apply to the restored database unchanged).

## 1. New subscription: bootstrap (laptop, once)

```powershell
az login                      # as an Owner of the FINAL subscription
gh auth login
.\infra\bootstrap-azure.ps1 -SubscriptionId <final-subscription-id> -Location southeastasia `
  -ResourceGroup rg-aron-prod -Environment azure-prod -AppName sp-aron-github-prod `
  -AlertEmails "<owner>,<ops>"
```

This creates `rg-aron-prod`, the probe group, the GitHub identity limited to that group (Contributor plus role-
conditioned RBAC Administrator), both OIDC subjects for the `azure-prod` environment, the environment's branch policy,
and the GitHub secrets and variables. **Note:** the GitHub secrets `AZURE_*` and variables `AZURE_RESOURCE_GROUP` /
`AZURE_LOCATION` are repository-wide today; before the move, move them into the `azure-dev` and `azure-prod`
environments (GitHub > Settings > Environments) so both accounts can coexist during the parallel run.

## 2. Infrastructure in the final account

1. GitHub > Actions > **deploy** > Run workflow: branch `claude/wonderful-thompson-k6ejnf` (or `main` once it is the
   release branch), environment **prod**. This runs `infra/deploy.sh prod`: `main.bicep` with
   `params/prod.bicepparam` (zone-redundant PostgreSQL D8ds_v5 on SSD v2 with geo backup, Front Door Premium + WAF +
   Private Link, private networking), seeds Key Vault, builds and pushes the images, runs migrations on the empty
   database, deploys the apps and smoke-tests through Front Door.
2. The first prod run sets `ARON_PG_READ_REPLICA=false` (SSD v2 must finish its first backup before a replica can be
   created); the next run adds the replica.
3. Container Apps cores: request the environment quota from `cae-aron-prod` > **Quota** (see the quota document).

## 3. Data

The final database starts empty; the pilot's data comes over with a dump and restore during a short write freeze.

1. Announce a window (pilot users finish their day and sync; phones keep selling offline during the window anyway).
2. Stop writes in the TEST environment: scale the test api to 0 and the worker to 0
   (`az containerapp update -n ca-aron-dev-api -g rg-aron-dev --min-replicas 0 --max-replicas 0`, same for the worker).
3. Dump from the test server (Burstable has a public endpoint for Azure services only, so run this from Azure Cloud
   Shell or a temporary container job in `rg-aron-dev`):
   `pg_dump --format=custom --no-owner --no-privileges --dbname "<aron-db-direct-url from the test Key Vault>" -f aron.dump`
4. Restore into the final server through a Container Apps job in `rg-aron-prod` (the final database is private):
   `pg_restore --no-owner --no-privileges --clean --if-exists --dbname "<aron-db-direct-url from the prod Key Vault>" aron.dump`,
   then run the migrate job once more (it applies nothing if the versions match).
5. Reconcile: row counts per table and the per-day money totals (`docs/24` s4.12) must match between the two servers.
6. Blob photos: copy the `media` container with `azcopy copy` (source and destination authenticated with Entra ID; the
   accounts have shared keys disabled).
7. Do **not** copy the JWT signing key: the final Key Vault gets a new key, so every user signs in again once on the
   new host (the offline unlock keeps working for already-captured data; the outbox uploads after the new login).

## 4. Google (Maps, Firebase)

1. In the final Google Cloud project create new keys: an Android Maps key restricted to the package names and the
   release certificate SHA-1, and a web Maps key restricted by referrer to the final web host. Daily caps and a budget
   alert as in `docs/28` rule 4.
2. New Firebase project: download the new `google-services.json` and a new FCM service account.
3. Update the GitHub secrets `MAPS_ANDROID_KEY`, `MAPS_WEB_KEY`, `GOOGLE_SERVICES_JSON`, `FCM_SERVICE_ACCOUNT_JSON`
   (environment-scoped for prod), redeploy prod (the FCM account goes to the prod Key Vault), rebuild the APKs.
4. Revoke the test keys after the parallel run.

## 5. Phones

1. Release APKs built against the final API host (`aron.apiBaseUrl`) and the new Firebase config, signed with the same
   release key (the provisioning QR pins its certificate digest, `docs/24` D24-49).
2. Enrolled phones: re-enrol with a new provisioning QR from the final admin portal that carries the final
   `aron.api_base_url` (`docs/24` s10.4). A phone must upload its outbox to the TEST server before it is wiped; the
   Sales Submit screen shows "reconciled" when it is safe.
3. Pilot users sign in again on the new host.

## 5a. What the rehearsal profile already proves (before the move)

While `dev` keeps the final topology (until 2026-10-10 or the owner's decision), rehearse here, at pilot load only:
a forced PostgreSQL failover (`az postgres flexible-server restart --failover Forced`) with the app reconnecting, a
point-in-time restore into a new server name and a row count comparison, the WAF in Prevention mode in front of real
phone traffic, and the private networking path (api to PostgreSQL inside the VNet). Write the timings into
`docs/status/infra.md`; the 8,500-user load proof still runs only in the final account.

## 6. After the move

1. Run the Day 6 proofs in the final account (`docs/23` s5): load test at 1.5 times the fleet, burst, failover and
   restore drills.
2. Keep the TEST account for a week as a fallback, then delete `rg-aron-dev` (the budget alert keeps watching until
   then). If the owner keeps the test account for further pilots, order the reset to `dev-lite` instead (GitHub >
   Actions > reset, profile `dev-lite`, confirm `owner approved reset`), then deploy with profile `dev-lite`.
3. Update `docs/status/infra.md` with the final host names, the date of the move and the reconciliation result.
