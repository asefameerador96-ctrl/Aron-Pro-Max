# Rollback of a bad migration (RB-14, migration part)

**Trigger:** a migration that ran in a deploy damaged or lost data (wrong UPDATE, dropped column still in use), or
it succeeded but the new schema breaks the previous image so a plain rollback is impossible.
**Who:** the infra lane with the lead; the owner approves a restore (it creates a server that costs money while it
exists). **Time:** not drilled yet; docs/18 s7 assumes RTO about 2 hours for a point-in-time restore. The restore
drill is N-059 (qa) and docs/setup/move-to-final-account.md s5a (infra).

## What is safe to assume

- Migrations are forward-only (never edit a shipped one); the fix is normally a **new** migration (fix forward).
- Every deploy that runs migrations writes `PITR restore point (before the migrations): <UTC time> on server <name>`
  into its run summary before the migrate job starts.
- Rows the server ACKed after the restore point are re-sent by the phones (D-63: devices keep their outbox until the
  server confirms; sync is idempotent by client UUID), within the re-sync window.

## 1. Can it be fixed forward? (preferred, 15 to 60 minutes)

If no data is lost (a wrong default, a missing index, a constraint too strict): write the corrective migration in the
db lane, push, and CI deploys it. Meanwhile, if the apps fail, roll the apps back
([rollback-bad-deploy.md](rollback-bad-deploy.md)) only when the old image still works on the new schema.

## 2. Data lost or corrupted: point-in-time restore into a NEW server

Never restore over the live server. With the owner's approval:

```
# restore point = the time from the deploy summary (UTC), minus a minute for safety
az postgres flexible-server restore -g <rg> --name <server>-r<yyyymmddhhmm> \
  --source-server <server> --restore-time <yyyy-mm-ddThh:mm:ssZ>
```

The restored server comes up in the source server's network (private access) with the same admin login. The
**drill** workflow (`mode: pitr`, `confirm: owner approved restore drill`) runs exactly this, compares the row counts
of every app table on both servers from inside the VNet, and deletes the copy in the same run; its summary gives the
restore duration and the cost estimate.

Then either:
- **Repair** (usual): compare the damaged tables between the live and the restored server and copy the lost rows
  back with a reviewed script, as a forward migration or a one-off job inside the VNet; or
- **Switch** (whole database lost): write the restored server's URLs into Key Vault (`aron-db-url`,
  `aron-db-direct-url`, `aron-db-read-url`), deploy the apps again (`deploy` dispatch with `rollback_sha` = the last
  good commit if the new code needs the damaged schema), and let the phones re-send.

## 3. Clean up and record

- Delete the restored server the same day once it is no longer needed, and tell the owner its cost.
- Record in `docs/status/infra.md`: restore point, restore duration, rows repaired, measured RTO.
