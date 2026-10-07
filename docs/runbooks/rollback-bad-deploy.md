# Rollback of a bad deploy (RB-01, deploy part)

**Trigger:** the deploy run's health gate failed, or after a green deploy the API or the web dashboard misbehaves
(5xx alert, wrong answers, the field apps cannot sync) and the cause is the new code, not the database or Azure.
**Who:** the infra lane or the lead, from a session; the owner only approves if asked. Never deploy by hand from a
laptop: everything below is a GitHub Actions run.
**Time:** dev, not drilled yet (design figure: 8 to 15 minutes, most of it Front Door and revision start-up).

## What is safe to assume

- Migrations are forward-only and expand/contract (docs/24), so the previous image runs on the newer schema. A
  rollback therefore **never** runs migrations and never touches the database. If the migration itself is the
  problem, use [rollback-bad-migration.md](rollback-bad-migration.md).
- Every deploy records in its run summary: the commit, both images **by digest**, and the PITR restore point.
- The registry keeps the newest 30 images per repository (`acr-purge.yml`), so the last 30 deployed commits can be
  rolled back to without a build.
- Phones keep working offline during all of this; nothing is lost while the API is down (offline-first, idempotent
  sync). Pending uploads retry.

## 1. Decide (2 minutes)

1. Open the failed or suspect `deploy` run: the summary shows `Commit` and the images.
2. Find the last good commit: the previous green `deploy` run's summary, or
   `curl -s https://<front-door-host>/v1/health` (field `build`) before the bad deploy.
3. If the bad deploy is still running, let it finish or fail; a deploy is never cancelled mid-flight.

## 2a. Stage and prod (Multiple revision mode)

If the health gate failed, the deploy already put all api traffic back on the previous revision (the summary says
"api traffic is back on ..."). If it could not, or the problem shows after a green gate:

```
az containerapp revision list -g <rg> -n ca-aron-<env>-api --query "[?properties.active].{name:name, created:properties.createdTime, traffic:properties.trafficWeight}" -o table
az containerapp ingress traffic set -g <rg> -n ca-aron-<env>-api --revision-weight <previous-revision>=100
```

This takes effect in seconds; then continue with step 3 (revert) so the next deploy does not bring it back.
(Runs from the deploy identity in a workflow in the final account; proven only there.)

## 2b. Dev and TEST profile (Single revision mode): redeploy the last good commit

Actions > **deploy** > Run workflow, on branch `claude/wonderful-thompson-k6ejnf`:

- `environment`: `dev` (or `dev-lite` after a reset)
- `rollback_sha`: the full 40-character id of the last good commit
- leave `run_migrations` as it is (a rollback never migrates) and `force_infra` unticked

The run checks that the commit is an earlier commit of the integration branch, takes the deploy lock, skips the
infrastructure stage and the migrations, reuses that commit's images from the registry by digest (no build), deploys
the apps and runs the same health gate, expecting `build` = the rollback commit. The summary says `(ROLLBACK)`.

If it stops with "is no longer in the registry (purged)": the commit is older than the last 30 images; go to step 3
and roll forward with the revert instead.

## 3. Revert on the integration branch (always)

A rollback only changes what runs. The next push would deploy the bad change again, so revert it at once:

```
git revert <bad-commit>        # or the merge commit with -m 1
git push -u origin claude/wonderful-thompson-k6ejnf
```

CI then deploys the revert like any commit; the ordering guard accepts it because it is newer than the rollback.

## 4. Verify and record

- `curl -s https://<front-door-host>/v1/health` shows the expected `build`; `/v1/health/ready` is 200; the web
  `/login` page loads.
- Note in `docs/status/infra.md`: time detected, time rolled back, commits, measured minutes.

## Not covered here

- The database failing or failing over: RB-02.
- A deploy refused by `ARON_DEPLOY_FREEZE_DHAKA` (the selling-hours freeze, set only once real users exist): a
  rollback is always allowed; a normal deploy waits for the window to end.
