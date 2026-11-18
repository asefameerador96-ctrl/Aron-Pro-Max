# Request (document only, NOT built): profile the Apsis dump through a throwaway MySQL server

From: lane M-APSIS1. To: lead, lane/infra-final. Status: a build-ready plan for the day the hand-written reader refuses the real dump. **Nothing here is built or run; no step may start before the owner and the lead say so.** The hand-written reader is closed (lead decision 2026-11-18); this is the remedy for anything it refuses (see the "what the reader accepts and refuses" table in `docs/status/apsis-migration.md`, section 0).

## 1. When to choose it
- The owner's FTP file listing shows a tool or layout the reader refuses (mydumper split layout, compressed archives, `--tab`, a single whole-database file with shapes outside the table), or
- a first `profile` run refuses a table for a shape that is not already in the table of accepted constructs.
MySQL itself is the only complete reader of its own dumps. Output rules are unchanged: counts, structure and numbers only, never a row, a value list or an exact minimum or maximum.

## 2. Container image
- Base: the **official `mysql` image from Docker Hub, pinned by digest** (never by tag alone). The server version must equal the dump header's `Server version` / `Distrib` (the file listing and the first lines of a file, read by the owner, say which: 5.7.x or 8.0.x). The digest is recorded at build time from the registry (`docker buildx imagetools inspect mysql:<version>`), copied into `infra/` as `sha256:<digest>`, and mirrored into the private registry (ACR) of the final account; the job pulls only from ACR. (The digest is not written here: this lane has not looked at the registry, and a made-up digest would be worse than none.)
- On top of the base, one thin layer, built by CI and scanned: `/opt/profile/run.sh`, `/opt/profile/filter.py` (the quote/comment-aware block scanner from `mysqldump.py`, reused as a module), `/opt/profile/profile.sql`, `/opt/profile/check_output.py`, Python 3 stdlib only. No other packages; no `curl`.
- `mysqld` configuration file baked in: `skip-networking`, `socket=/var/run/mysqld/mysqld.sock`, `event-scheduler=OFF`, `skip-log-bin`, `local-infile=0`, `secure-file-priv=NULL`, `general_log=OFF`, `slow_query_log=OFF`, `log_error_verbosity=1`, `innodb_buffer_pool_size=` about 60 percent of the job memory, `innodb_flush_log_at_trx_commit=2`, `innodb_doublewrite=OFF`, `max_allowed_packet=1G`, `sql_mode=` the value named in the dump's own `SET sql_mode` line (read by the filter, not guessed), `character-set-server=` the character set the owner decided (Q2), `default-time-zone='+00:00'`.

## 3. Job spec for lane/infra-final
| Item | Value |
|---|---|
| Kind | Azure Container Apps job (manual trigger, replica timeout 24 h, 1 replica, no retries) with an Azure Files premium share as scratch, **or** an ACI group with a managed disk if Files premium cannot hold the I/O |
| Network | the private VNet of the final account, a **dedicated subnet** with an NSG that denies **all outbound** except the private endpoints of the landing storage (read) and the output storage (write) and the log sink; no public IP, no ingress; `mysqld` listens on a socket only |
| Identity | `id-apsis-migration`: Blob Data **Reader** on the landing container, Blob Data **Contributor** on a **separate output container** (never the landing container); no database role, no Key Vault, no other access |
| Compute | 8 vCPU, 32 GB RAM (buffer pool about 18 GB) |
| Scratch | 200 GB premium SSD (data about 40 to 90 GB for a 52.5 GB dump, plus index build and undo; restore must never be disk-starved) |
| Timeout and abort | 24 h hard stop; abort criteria after a 1 GB trial slice: projected over 12 h or over 150 GB |
| Logs | job stdout/stderr only; the run script prints step names, counts of statements and exit codes, **never a statement or a row** |
| Teardown | the scratch volume is deleted when the job ends (success or failure); nothing is kept but the output files |
| Environment | final account only (never the test account, CI or the container this lane works in) |

## 4. Exact steps (all inside the job)
1. **Start** `mysqld --defaults-file=/etc/mysql/apsis.cnf --initialize-insecure` (empty datadir on the scratch volume), then start it with the socket only; set a random root password kept in the job's memory, create the working schema `apsis`.
2. **Filter the stream, never executing foreign code.** Stream each file from private storage (`.sql`, `.sql.gz`, per-table or whole-database) through `filter.py`, which:
   - drops every `DELIMITER ;;` block (triggers, routines, events) using the block scanner, and **refuses the file** if a block cannot be scanned (same rule as the reader);
   - drops `CREATE ... TRIGGER`, `CREATE ... PROCEDURE|FUNCTION|EVENT`, `CREATE ... VIEW` / view stand-ins (views are not data) and `/*!50106 ... EVENT ... */` statements;
   - **rewrites definers**: removes every `DEFINER=...` clause (`/*!50017 DEFINER=...*/`, `DEFINER=` inside `/*!50013 ... */`) so no object is bound to a vendor account;
   - removes `CREATE DATABASE`, `USE`, `ALTER DATABASE` lines and forces a single `USE apsis;` at the top;
   - passes through only: `SET` of session variables on an allow-list (`NAMES`, `TIME_ZONE`, `UNIQUE_CHECKS`, `FOREIGN_KEY_CHECKS`, `SQL_MODE`, `SQL_NOTES`, `SQL_LOG_BIN`), `DROP TABLE IF EXISTS`, `CREATE TABLE`, `LOCK/UNLOCK TABLES`, `ALTER TABLE ... KEYS`, `INSERT`. **Any other statement is dropped and counted (a number in the log), never executed.**
3. **Restore** the filtered stream with `mysql --defaults-file=... --default-character-set=<decision> --init-command="SET foreign_key_checks=0, unique_checks=0, sql_log_bin=0"` as the job's local root; one file at a time, tables in the order the reader's `ORDER` list gives (masters first). The statement count and the exit code of each file are logged; a failure stops the run (no silent partial restore).
4. **Verify the restore by counts.** `SELECT COUNT(*)` per table equals the number of INSERT rows counted by the filter (the filter counts tuples with the reader's tokenizer in counting-only mode) and, when the owner supplies the vendor's exact counts (Q18), equals those; any difference stops the run.
5. **Run `profile.sql`** (section 5) as read-only queries; results go into a temporary table in the same server.
6. **Render the four profile files** with the same layout and the same identifier rule as `profiler.py` (`apsis-profile.json/.md` full, `apsis-profile.sanitised.json/.md`), through `check_output.py` (section 6).
7. **Upload** the four files to the output container only if `check_output.py` exits 0; otherwise upload nothing and exit non-zero with the step name. Delete the scratch volume.

## 5. The aggregate SQL that produces today's profile fields
Every statement is generated from `information_schema` (table and column names validated by the identifier rule before they are put into SQL; never string-concatenated from dump text) and is `SELECT` only. `t` is a validated table, `c` a validated column.

| Field | Statement |
|---|---|
| table, engine, charset, partitioned, partition count | `SELECT TABLE_NAME, ENGINE, CCSA.CHARACTER_SET_NAME FROM information_schema.TABLES T JOIN information_schema.COLLATION_CHARACTER_SET_APPLICABILITY CCSA ON CCSA.COLLATION_NAME=T.TABLE_COLLATION WHERE TABLE_SCHEMA='apsis'`; `SELECT COUNT(*) FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA='apsis' AND TABLE_NAME=t AND PARTITION_NAME IS NOT NULL` (never `PARTITION_DESCRIPTION`, which holds the bounds) |
| column names, declared types, order | `SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA='apsis' AND TABLE_NAME=t ORDER BY ORDINAL_POSITION` |
| primary key, indexes, foreign keys | `information_schema.STATISTICS` (INDEX_NAME, NON_UNIQUE, COLUMN_NAME, SEQ_IN_INDEX) and `KEY_COLUMN_USAGE` (REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME); referenced tables not on the allow-list print as `[table-not-on-allow-list]` |
| exact row count | `SELECT COUNT(*) FROM t` |
| null share | `SELECT AVG(c IS NULL) FROM t` |
| min and max length (characters; bytes for binary types) | `SELECT MIN(CHAR_LENGTH(c)), MAX(CHAR_LENGTH(c)) FROM t WHERE c IS NOT NULL` (`LENGTH(c)` for binary types) |
| shape histogram (16 classes) | one statement per column: `SELECT SUM(LEFT(c,256) REGEXP '^$'), SUM(LEFT(c,256) REGEXP '<datetime-like>'), SUM(... '<date-like>'), ..., COUNT(*) FROM t WHERE c IS NOT NULL`, with the same class patterns as `profiler.shape` (evaluated in priority order by `CASE`, so each value lands in exactly one class); binary columns get `COUNT(*)` as class `binary` and nothing else |
| distinct count and near-unique test (columns with 100+ non-null values, tables of 1000+ rows) | `SELECT COUNT(DISTINCT c) FROM t` (capped: for tables over 20 M rows use `COUNT(DISTINCT c)` on a hash-partitioned sample of 50,000 distinct hashes, as the reader caps at 50,000) |
| enum candidate (boolean only) | one statement returning a single boolean: `SELECT (COUNT(*)>=1000 AND COUNT(DISTINCT c)<30 AND MAX(CHAR_LENGTH(c))<=24 AND (SELECT MIN(n) FROM (SELECT COUNT(*) n FROM t WHERE c IS NOT NULL GROUP BY c) g)>=20 AND SUM(c NOT REGEXP '^[A-Za-z0-9_.+-]{1,24}$')=0) FROM t WHERE c IS NOT NULL`. The per-value counts exist only inside the query and are never returned |
| number descriptor (full file; 1000+ rows, 100+ non-null, not near-unique) | `SELECT MIN(CHAR_LENGTH(CAST(ABS(c) DIV 1 AS CHAR))), MAX(...), SUM(c<0)>0, SUM(c>=0)>0, MAX(c<>FLOOR(c)) FROM t WHERE c IS NOT NULL` (digit-count range, sign, integer or decimal; decimal places from `NUMERIC_SCALE`) |
| year-month range (full file; same conditions) | `SELECT DATE_FORMAT(MIN(c),'%Y-%m'), DATE_FORMAT(MAX(c),'%Y-%m') FROM t WHERE c IS NOT NULL AND c>'0000-01-01'`; zero dates: `SELECT SUM(c='0000-00-00') FROM t` (the full date is never selected into the output) |
| hour-of-day histogram (full file only; datetime columns with 100+ non-null) | `SELECT HOUR(c), COUNT(*) FROM t WHERE c IS NOT NULL GROUP BY HOUR(c)` |
| reference checks (shares only) | `SELECT AVG(m.k IS NOT NULL), AVG(n.k IS NOT NULL) FROM fact f LEFT JOIN master m ON m.key=f.key LEFT JOIN master n ON n.nkey=norm(f.key)` (the normalisation rule as a SQL expression equal to `norm_code`), counts and shares only |
| `profile-values` (owner-run, one named column) | not part of this job's default run; a separate owner-run job with the same confirmation rule, the loud warning, an output file mode 0600 and a directory different from the profile files |

The SQL file is checked in, reviewed like code, and tested (when built) against a synthetic MySQL database by the **same leak tests** as the reader's profile (SECRETS list, 1/5/40-row tables, PINs, 4-digit values, names in a `type` column).

## 6. How the output is checked for leaks before it leaves the account
`check_output.py` runs inside the job after rendering and before the upload; it exits non-zero on any finding, and nothing is uploaded then.
1. **Closed schema.** The JSON must match a fixed schema: only the keys the reader's profile has; every number a non-negative integer or a rounded share in [0,1]; every string either an identifier that passes the identifier rule (no run of 8 or more digits), a placeholder `[name-N]`, a type word on an allow-list, or one of the 17 class labels. **Any other string refuses**, which is what would carry a value.
2. **No exact extremes.** No field named min/max/first/last other than lengths; dates only as `YYYY-MM`; no key with a list of strings longer than the class labels allow.
3. **Floors.** Every descriptor and histogram must be backed by a table of 1000+ rows and a column of 100+ non-null values (the checker recomputes the row counts from the profile itself and drops nothing silently: it fails).
4. **Value scan.** A scan of the four files for patterns that look like personal data or secrets (phone numbers, e-mail addresses, 17-digit ids, UUIDs, hashes, Bengali text) and a **canary check**: before the real run, the same pipeline runs on a synthetic dump seeded with known canary values; the check must find none of them in the outputs (this runs in CI when the pipeline is built).
5. **Size.** Each file at most 2 MB (a profile of 26 tables has no reason to be larger).
6. **Sanitised pair.** The sanitised files must carry no descriptor and no hour histogram (the reader's rule).
7. **Human gate.** The lead reads the sanitised pair first; the owner alone holds the full files (outside the repository, in the output container).

## 7. Cost, time and risks
- **Disk and time (to be measured on a 1 GB slice first):** 150 to 200 GB scratch; a restore of 52.5 GB / about 122 M rows in roughly 3 to 8 hours with the settings above (comparable to the reader at about 3.4 MB/s, 4 to 5 hours: MySQL is not faster, only complete); profile queries one full scan per table, minutes to about an hour. Compute cost a few currency units for the run; test-account pilot sizing does not apply (final account only).
- **Vendor triggers, routines and events executing:** removed by the filter before the server sees them; `event_scheduler=OFF`; the loader runs as a local root with no network; definers rewritten away. A statement the filter does not know is dropped and counted, never executed.
- **The server is isolated, not trusted:** no egress is the control that matters; there is nowhere to send anything.
- **Character set (Q2):** the owner decides; wrong choices show as mojibake counts in the shape histogram.
- **`sql_mode` and zero dates:** the restore session uses the dump's own `sql_mode`; zero dates are counted before `MIN/MAX`.
- **Version differences (5.7 vs 8.0, `utf8mb4_0900_ai_ci`):** use the server version named in the dump header.
- **Aggregates over tiny groups:** the floors in section 6 apply; no exact min/max is ever selected.
- **Scale surprises:** abort criteria in section 3.

## 8. What it needs before it can be built
1. The owner's FTP listing (names and sizes) and the dump header's server version (decides 5.7 or 8.0 and the layout).
2. lane/infra-final: the job, subnet, identity and scratch storage in section 3; the pinned image mirrored into ACR.
3. The owner's character-set decision (Q2) and, if available, the vendor's exact row counts (Q18).
4. A lane to build `filter.py`, `profile.sql`, `run.sh` and `check_output.py` and their synthetic-data tests (about one day), then a review with the same leak hunt as the reader had.
