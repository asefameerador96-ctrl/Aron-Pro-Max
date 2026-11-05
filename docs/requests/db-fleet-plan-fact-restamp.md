# db: fleet-size plan for the fact-key restamp (V0147 and `dw.restamp_fact_keys`), db session 15, 2026-11-05

Lead checker should-fix 3 on the V0144 replacement (items: written plan, not a migration). Status: plan; nothing here is measured at fleet size. Pilot numbers: V0147 touched 0 / 8 / 116 rows on dev; the old V0144 tail took ~105 s on dev dominated by the rekey, not by the restamp (docs/status/db.md, deploy #309 reading).

## What scales badly
`dw.restamp_fact_keys(dim, ids, from)` runs, for `dw.fact_memo` and `dw.fact_visit`, one `UPDATE ... WHERE <id> = ANY($1) AND business_date >= $2 AND <key> IS DISTINCT FROM dw.<dim>_key_on(<id>, business_date)`. V0147 calls it with every id in the facts and `-infinity`:
- cost ~ F x 3 keys x (one `*_key_on` index probe) over **all** fact rows F, whether or not a key changed, plus the `= ANY(huge array)` filter (array of every route/outlet/user id: tens of thousands of elements at fleet size);
- the SKIP LOCKED form adds one row-locking subquery pass over the wrong rows only (V0144 round-2 fix);
- V0147's completeness count repeats six full-table `*_key_on` comparisons per pass.
At fleet size F is hundreds of millions of rows over the retained years (fact_memo and fact_visit are partitioned by business date), so a single-transaction full scan is not acceptable there.

## Plan for the final account
1. **The final account migrates from empty** (docs/28): V0144-V0148 then run on empty fact tables in seconds. No full restamp ever runs at fleet size from a migration. This is the main mitigation and needs no code.
2. **Runtime path stays incremental.** `dw.rekey_dim_versions` restamps only the ids whose versions changed, from the earliest changed date (`a_from`), and the projector's 7-day key fix-up covers new facts. Both are bounded by the change, not by F.
3. **If a full restamp is ever needed on a populated fleet database** (a bulk history correction), do it as a job, not a migration: loop over month partitions (`business_date` range per call, so partition pruning keeps each statement to one partition), commit per batch, SKIP LOCKED as in V0147, a lease/advisory lock so one runs at a time, and a progress row. Needs a new `p_to date` parameter on `dw.restamp_fact_keys` (new function, additive). Not built now: no current need.
4. **Make the key lookup set-based before then.** Replace the per-row `dw.*_key_on()` with a join to the version table on `(id, valid_from <= business_date < valid_to)`, using the `(id, valid_from)` btrees from V0144; the planner can then merge-join per partition. Test: same result as the function form on the seed and a loadgen database.
5. **Server settings, not migration settings.** `work_mem` (4 MB on dev) belongs as an infra server parameter for the final account (D2ds_v5 or larger: 64 MB), not as `SET LOCAL` in migrations (lead checker note); the heavy-migration `SET LOCAL` stays only for dev.
6. **Gate.** Before the final account, measure a restamp of one month partition on `tools/loadgen` at `srs=500` and record the time in this file.

## Migration body budget (added after deploys #308/#309/#311)
- Root cause of those three failures (found by db #15, recorded by the lead as D-OPS-MIGRATE-IDLE-TIMEOUT-01): Flyway's second connection (lock and history insert) sits idle in a transaction for the length of ONE migration script, and the server's `idle_in_transaction_session_timeout` (30 s on dev) terminated it. The migrate connections are now exempt from that timeout, so a long body no longer fails for that reason.
- Still true: any migration body must either finish well inside the migrate job's `replicaTimeout` (1800 s) or be batched. Batching inside one script does not help with the idle timeout (tested: a non-transactional script fails the same way without the exemption); it helps with the job timeout, lock windows and resumability. Heavy backfills on a populated final account run as batched jobs (item 3 above), not as one migration.

Not claimed: any fleet-size timing. The plan's numbers are to be measured in step 6.
