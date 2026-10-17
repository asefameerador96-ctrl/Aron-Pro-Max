# RB-14: Point-in-time restore of the database (and the generation mint)

Owner: infra, with backend-core for step 5. Drilled: the restore itself by `infra/scripts/drill.sh pitr` (TEST account);
the mint by `db/src/test/.../ProductionReadinessMigrationsTest` and `IngestRetryTest.aResyncRowOlderThanTheWindowIsAcceptedOnlyAfterARestore`
(CI, every push). Not yet timed end to end on dev (HD-09: waits on "owner approved restore drill").

## When

Data was lost or corrupted on the primary after an instant T (a bad migration that `rollback-bad-migration.md` cannot
undo, an operator error, a storage fault). Azure keeps 7 days of PITR on dev and 35 on prod (`postgres.bicep`).

## Why the mint matters (DD-02, FM-20)

Every phone keeps a row until the server acknowledged it, then purges it three days later. A restore to T drops every
row acknowledged after T. If the restored server kept the old `app.server_generation`, the phones would see nothing
changed, keep trusting those acknowledgements, and purge rows the server no longer has. `app.mint_server_generation`
(V0087) closes the old generation and opens a new one with `lost_after_utc = T`; the next sync answers with the new
generation and the phones re-send everything acknowledged after T (`trigger = resync`, F-SYS-089; a re-sent row older
than the backdate window is accepted and flagged `resync_late`, never quarantined).

## Steps

1. **Freeze writes.** Scale the api to 0 replicas (or set `cfg.ops.sync_hold_s` so phones hold):
   `az containerapp update -g <rg> -n <api app> --min-replicas 0 --max-replicas 0`. Note the instant.
2. **Pick T** (UTC, ISO 8601). Everything acknowledged after T will be re-sent by the phones; everything written
   on the web after T is gone and must be re-keyed from the audit log (`app.audit_log` of the old server, step 8).
3. **Restore to a new server** (never in place): `az postgres flexible-server restore -g <rg> --name <server>-restored-<mmddhhmm> --source-server <server> --restore-time T`.
   Expect 20 to 60 minutes (the drill records the real number in `docs/status/infra.md`).
4. **Verify the copy**: row counts per table against the frozen primary (`drill.sh pitr` does this from inside the
   VNet with the dblogins job), and `SELECT app.audit_verify()` returns null.
5. **Mint the generation on the restored server** (admin login, from the dblogins job or a VNet shell):
   `ARON_DB_ADMIN_URL=postgresql://... infra/scripts/mint-generation.sh pitr_restore T T "RB-14 <ticket>"`.
   It prints the old and the new generation and checks exactly one is current. Do this before step 6: an api that
   serves with the old generation acknowledges new rows into the wrong lineage.
6. **Repoint and restart.** Update the Key Vault secrets `db-url`, `db-direct-url`, `db-read-url` (and the per-app
   login URLs when `dbPerAppLogins` is on) to the restored server's host, then restart the api and worker
   (`az containerapp revision restart` or a deploy): both read the current generation at start and the api answers
   it in `X-Server-Generation` and the bundle meta.
7. **Reopen.** Scale the api back up. Phones re-send within their next sync; watch `IngestMetrics`
   (`accepted` rising, `resync_late` flags in `app.ingest_registry.flags`) and the sync-health page.
8. **Web writes after T.** List `app.audit_log` rows of the old server after T (keep the old server until done) and
   re-key them. Then delete the old server (it costs money) and record the restore in `docs/status/infra.md` with
   T, the durations of steps 3 and 6, and the generation ids.

## Failover

A zone failover (`database-failover.md`) that may have lost acknowledged writes gets the same mint with
`kind = failover` and `lost_after_utc` = the last replicated instant Azure reports; one that lost nothing needs none.
