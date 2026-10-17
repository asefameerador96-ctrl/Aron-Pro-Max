# qa → infra (and backend-core): a restore must mint a new server generation, or phones never re-send the lost tail (N-059), 2026-10-17

**Finding.** Phones re-send writes a restore lost only when the server generation changes (docs/24 s4.8:
`X-Server-Generation`, then `GET /v1/sync/generation`, then a re-send of everything acked after `lost_after_utc`).
Nothing mints that generation today:
- `app.server_generation` gets only the row V0008 creates.
- No code, no `infra/scripts/drill.sh` step and no runbook inserts a new one. RB-14
  (`docs/runbooks/rollback-bad-migration.md` section 2, "Switch") ends with "let the phones re-send", and they will
  not.
- A point-in-time restore copies the old row along with everything else, so the restored server names the **same**
  generation as before.

Every sale, visit and due collection acknowledged between the restore point and the restore is then lost for good: the
phones dropped them from their outbox as acknowledged, and nothing asks for them again.

**Proof (local PostgreSQL, CI):** `backend/app/src/test/kotlin/com/aktcl/aron/backend/app/RestoreDrillReconciliationTest.kt`.
Three phones sell before and after a restore point. The database is copied as it was at the restore point, and a second
production wiring serves the copy. The phones then run the s4.8 protocol.
- Without a new generation (`aRestoreWithoutANewGenerationLosesTheTail`), the row-by-row reconciliation reports every
  row acknowledged after the restore point as missing.
- With the mint below (`aRestoreWithANewGenerationReconcilesToZeroMissingAndZeroDuplicated`), it shows zero missing,
  zero duplicated and zero extra rows. Memo balances, ledger rows and server money totals equal the devices', and a
  second re-send changes nothing.

**Ask 1, infra (restore runbook and the drill).** Right after an Azure restore, before the API is pointed at the
restored server, run on it:
```sql
BEGIN;
UPDATE app.server_generation SET is_current = false WHERE is_current;
INSERT INTO app.server_generation (generation, kind, started_at, restore_point_utc, lost_after_utc, note)
VALUES (gen_random_uuid(), 'pitr_restore', now(), :'restore_point', :'restore_point', 'RB restore ' || :'ticket');
COMMIT;
```
- Put this into RB-14 section 2: before the Key Vault URLs are switched, and also on the "Repair" path if the restored
  server is ever served.
- Put it into `drill.sh pitr` against the temporary server. The drill should also read the generation back
  (`SELECT generation, kind FROM app.server_generation WHERE is_current`) and log it next to the row counts.
- After a failover that may have lost acknowledged writes (asynchronous replica promotion; zone-redundant HA is
  synchronous), use kind `failover` and set `lost_after_utc` to the last known-good replication time.
- The API caches the generation for 30 s, so phones see the change within a minute of the re-point.

**Ask 2, backend-core (optional, defence in depth).** The API or worker could detect a new lineage on its own. Store the
cluster's `system_identifier` (`pg_control_system()`) and timeline (`pg_control_checkpoint().timeline_id`) on the
current generation row. At start, when they differ, mint a new generation with `lost_after_utc` = the newest
`ingest_registry` time minus a safety margin. That covers a restore done without the runbook. Check first that the
app's database role can call these functions on Azure Flexible Server.

The Azure half of N-059 (the drill on the test account) still needs the owner's phrase in `drill.yml`
("owner approved restore drill"), because the temporary server costs money.
