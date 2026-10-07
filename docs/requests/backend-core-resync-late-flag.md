# Request to db (from backend-core, 2026-10-07): store the `resync_late` flag (F-SYS-089)

After a failover or point-in-time restore the phone re-sends rows the lost lineage had acknowledged (trigger
`resync`, docs/24 s4.8). backend-core now accepts such a row even when its business date is older than
`cfg.sync.max_backdate_days` (bounded: captured before the new generation started, business date at most
`max_backdate_days + 1` before that day), and the acceptance F-SYS-089 says it is "flagged resync_late".
There is nowhere to store the flag today: `risk_signal.code` has a closed CHECK list and `ingest_registry` has no flag
column. Until then the flag is a structured log line (`aron.sync`, `resync_late client_uuid=...`).

## Ask (pick one, forward-only)
- `ingest_registry.flags text[]` (default `'{}'`), api_rw UPDATE allowed on it; backend-core writes `{resync_late}`
  on the accepted row. Preferred: the flag belongs to the record, not to a person's risk.
- Or a `RESYNC_LATE` code in `risk_signal.code` (severity 1, subject = device).

Then backend-core writes the flag and adds the assertion to its test.
