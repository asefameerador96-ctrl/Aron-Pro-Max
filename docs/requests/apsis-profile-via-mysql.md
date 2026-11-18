# Request (document only, NOT built): profile the Apsis dump through a throwaway MySQL server

From: lane M-APSIS1. To: lead, lane/infra-final. Status: proposal for the day the hand-written reader refuses the real dump. Nothing here is built, and no part of it may be run before the owner and the lead say so.

## 1. Why this exists
`tools/apsis-migration` reads mysqldump files with a hand-written, strict, every-line-accounted reader. It is safe (four reviews found no leak) but it can only read shapes it names, and each review round has found another standard mysqldump shape. MySQL itself is the only complete reader of its own dumps. If the real dump (about 52.5 GB, 26 tables, about 122 M rows) has shapes the reader refuses, restoring it into a throwaway MySQL server inside the final account's private network and profiling it with SQL is the fallback. **Choose it only if the real file listing or a first `profile` run shows refusals that are not a one-line reader fix.** The file listing (names and sizes) the lead is asking the owner for decides per-table vs one file, compression and tool before anyone touches content.

## 2. What it would do
1. A Container Apps **job** (no ingress, **no egress** at all: the network policy allows only the private storage and its own log sink) starts a `mysqld` in the same container (or a sidecar) on a scratch volume.
2. The dump is streamed from private storage into `mysql` (`zcat | mysql`), as the job's only input. Nothing leaves the container except the outputs in section 3.
3. The job runs the profile SQL (section 3) and writes the four profile files (same names, same rules as today) to the private output container. It then destroys the volume. The server's data directory is never persisted and never mounted anywhere else.

## 3. The SAME no-value profile, from SQL
Output rules are identical to phase 1: **no row, no value list, no exact minimum or maximum; names pass the identifier rule or print as `[name-N]`; descriptors only for tables of 1000+ rows and columns with 100+ non-null values that are not near-unique; hour histograms in the full file only.** Only aggregate SQL is used, and the profile SQL is a checked-in file reviewed like code (a test runs it against a synthetic database and applies the same leak tests).

| Output | Source |
|---|---|
| table, column, type, key, index, foreign key, engine, charset, partitioned and partition count | `information_schema.TABLES/COLUMNS/STATISTICS/KEY_COLUMN_USAGE/PARTITIONS` (never `PARTITION_DESCRIPTION`, which holds the bounds) |
| exact row count (Q18) | `SELECT COUNT(*)` per table (not `TABLE_ROWS`, which is an estimate) |
| null share, min/max length | `AVG(c IS NULL)`, `MIN/MAX(CHAR_LENGTH(c))` (`LENGTH` for binary columns; binary columns get shape `binary` and no more) |
| shape histogram | counts of `c REGEXP '<class pattern>'` per class (the same classes: empty, datetime-like, date-like, uuid-like, e-mail-like, phone-like, digits-only, decimal, bengali, latin-text, ...), computed on `LEFT(c, 256)` |
| distinct count, near-unique test | `COUNT(DISTINCT c)` (a count, with the 100 non-null floor; large tables may use a sampled or HLL-style count capped like today) |
| year-month range | `DATE_FORMAT(MIN(c),'%Y-%m')`, `DATE_FORMAT(MAX(c),'%Y-%m')` only; the full date is never selected into the output |
| digit-count range, sign, decimal places | `MIN/MAX(CHAR_LENGTH(CAST(ABS(c) DIV 1 AS CHAR)))`, `SUM(c<0)`, scale from the declared type |
| hour-of-day histogram (full file only) | `COUNT(*) GROUP BY HOUR(c)` |
| enum candidate | `COUNT(DISTINCT c) < 30 AND COUNT(*) >= 1000 AND MIN(per-value count) >= 20 AND MAX(CHAR_LENGTH(c)) <= 24` as one boolean; the per-value counts never leave the query |
| reference checks | `COUNT` of fact keys found in the master key column, exact and after the normalisation rule |

`profile-values` (owner-run, one named column, re-derives the test, mode 0600, loud warning) keeps the same rules and is the only path that may print values.

## 4. Cost and sizing (estimates, to be measured on a 1 GB slice first)
- **Disk:** a 52.5 GB dump restores to roughly 40 to 90 GB of InnoDB data plus indexes (indexes in the dump's CREATE TABLE are rebuilt). Plan 150 GB of scratch (premium SSD or an ephemeral volume) so a restore is never disk-starved; a Container Apps job's ephemeral storage is too small, so this needs an Azure Files premium share or a small VM/ACI with a managed disk. Open question for infra-final.
- **Time:** a plain restore of this size is about 3 to 8 hours (single-threaded, per-row INSERTs; faster with `innodb_flush_log_at_trx_commit=2`, a large buffer pool, `skip-log-bin`, `foreign_key_checks=0`, `unique_checks=0`); the profile queries add a full scan per table (the 122 M rows in minutes to an hour, depending on the regex counts and sampling). The reader today does about 3.4 MB/s (about 4 to 5 hours for 52 GB), so the two are comparable; MySQL is not faster, only complete.
- **Compute:** 4 to 8 vCPU and 16 to 32 GB RAM for the job while it runs; deleted afterwards. Money: a few currency units for the run itself; the test-account rules (pilot size) do not apply because this runs only in the final account.

## 5. What it needs from lane/infra-final
1. A job (Container Apps job with a volume, or ACI) in the private VNet, **egress denied**, identity `id-apsis-migration` with Blob Data Reader on the landing container and Blob Data Contributor on a separate output container (never the landing one).
2. A pinned `mysql:8.0` (and, if the file listing shows a 5.7 dump, 5.7) image in the private registry; a scratch volume as in section 4; log sink with no query logging (`general_log=OFF`, `slow_query_log=OFF`, `log_error_verbosity=1`).
3. `mysqld` configured with: `--skip-networking` (socket only), `--event-scheduler=OFF`, `--skip-grant-tables` is NOT used (a local root with a random password kept only in the job's memory), `--skip-log-bin`, `--local-infile=0`, `sql_mode` set to a permissive value that matches the dump's `SET sql_mode` lines (see risks), `innodb_buffer_pool_size` about 60 percent of RAM.
4. The profile SQL runner (a small script in `tools/apsis-migration`, built only when this path is chosen) and the output writer that applies the identifier rule and the same four-file layout.

## 6. Risks and how each is handled
- **Vendor triggers, routines and events executing.** A dump can contain triggers that run on INSERT, routines and events. Mitigation: pre-filter the stream with a *dumb* line filter that drops `DELIMITER` blocks (using the same quote/comment-aware block scanner the reader has, which refuses on anything it cannot scan), set `event_scheduler=OFF`, run with a user that has no `SUPER`/`EVENT`/`TRIGGER`, and **neutralise `DEFINER=`** (`sed` on `/*!50017 DEFINER=...*/` and `DEFINER=` clauses in the pre-filter, or import with `--force` as a user that does not exist so definer-bound objects fail rather than run). Triggers and routines are not needed for the profile; they are removed before the data is loaded.
- **The restore runs foreign code in principle.** It is only the vendor's own dump of our own data, and the job has no egress and no secrets, so a malicious statement has nowhere to send anything; the SQL-injection surface is the dump itself, which the owner already trusts enough to keep. The no-egress rule is the control that matters.
- **Character set.** Q2 is unchanged: the owner decides the character set; the restore uses `--default-character-set=<decision>` and never guesses. A wrong choice shows as mojibake counts in the shape histogram, as today.
- **`sql_mode` and zero dates.** `NO_ZERO_DATE`/`STRICT_TRANS_TABLES` would reject the vendor's `0000-00-00` rows; the restore session sets the dump's own `sql_mode` and the profile counts zero dates (as the reader does) with `'0000-00-00'` handled before `MIN/MAX`.
- **Version differences (5.7 vs 8.0, collations such as `utf8mb4_0900_ai_ci`).** Use the server version named in the dump header; the file listing tells us.
- **Scale and cost surprises.** Measure a 1 GB slice first; abort criteria: more than 12 hours or more than 200 GB.
- **Output leakage.** The profile SQL only produces aggregates, but an aggregate over a tiny group can still be a value (a min/max of one row). Same rules as today: no min/max exposed, the 100 non-null floor, tables under 1000 rows get only type, null share, lengths and shapes. The same leak tests run against the SQL path before it is used.

## 7. When we would choose it
- The real file listing shows a tool or layout the reader refuses (mydumper split layout, compressed parts, `--tab`, a single file with routines and views we cannot scan), or
- a first `profile` run refuses a table for a shape that is not a one-line reader fix, or
- the owner prefers the complete answer to the fast one.

Otherwise the hand-written reader stays the phase-1 tool, and this document stays a plan. This lane stops extending the reader after the round-4 constructs (lead decision, 2026-11-18).
