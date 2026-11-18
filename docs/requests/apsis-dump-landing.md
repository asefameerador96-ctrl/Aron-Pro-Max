# Request: where the Apsis dump lands and how the job reads it (consumer side: lane M-APSIS1; provider side: lane infra-final)

Status: PROPOSAL from the consumer, to be agreed with infra-final. Nothing here touches the test account.

## Layout (final account only)
- A **private** Storage account (no public network access, private endpoint into the job's VNet, shared-key access disabled), container `apsis-dump`.
- Blobs, one per vendor table, flat, exactly the table name:
  `master_outlets.sql` ... (26 names of docs/reference/apsis-db-inventory-2026-11.md), or `<table>.sql.gz`. Largest: `live_geo_data_by_outlets.sql` (31.1 GB), `trans_sales_by_outlet.sql`. Total about 52.5 GB (the owner's screenshot of the dump folder).
- Format assumed: mysqldump with a `CREATE TABLE` and extended `INSERT INTO t VALUES (...),(...);` in the same file, UTF-8. If the export is something else (mydumper, CSV) the owner says so (Q1 of the status file) and the loader gets a small adapter; the container layout does not change.
- A second container `apsis-migration-reports` receives `profile.json` and `reconciliation-<run>.json` (numbers only).
- Nothing is copied to a laptop, the repository, CI or the test account: the owner moves the files from the organisation FTP server to the container **directly** (AzCopy from the server that can reach both, or Storage Explorer on the owner's machine), and states the blob names and sizes in the runbook sign-off.

## How the job reads it
- A Container Apps **job** `apsis-migration` (manual trigger, 8 vCPU / 16 GB, same VNet as the private PostgreSQL, one replica, timeout 24 h), image built from this repository's `tools/apsis-migration` (Python 3 + postgresql-client; no secrets in the image).
- Identity and database login (for lane/infra-final to wire): a **user-assigned managed identity** `id-apsis-migration` with `Storage Blob Data Reader` on container `apsis-dump` only and `Storage Blob Data Contributor` on `apsis-migration-reports` only. **Database login: the Entra principal of that identity, created as a PostgreSQL role named `id-apsis-migration` that is a plain member of the NOLOGIN role `apsis_migrator`** (`GRANT apsis_migrator TO "id-apsis-migration"`, run by the migrate role at runbook step 0.3; it has no SUPERUSER, CREATEROLE, CREATEDB or BYPASSRLS). **No secret is needed**: the job gets an Entra access token for PostgreSQL at start (`az account get-access-token --resource-type oss-rdbms`) and passes it as `PGPASSWORD`. If infra cannot use Entra authentication, the fallback is a password login `apsis_migrator_login` whose password is a Key Vault secret named `apsis-migrator-pg-password`, injected as `PGPASSWORD` by Container Apps secret reference; the secret is never in the image, the job definition's plain environment, the command line or a log.
- **The loader never defaults to the admin URL.** The tool refuses to start without `--db-role apsis_migrator`, runs `SET ROLE apsis_migrator`, and refuses if the login holds superuser/createrole/createdb/bypassrls. `--dsn` carries host, database and user only. The role's exact rights: `apsis_migrator` owns nothing; it reads (column-limited) the live masters it matches against and writes only `apsis_ctl`, `apsis_map`, `apsis_stage` and the archive `apsis_hist` (V0260/V0263 of the provisional migrations); it has no write on any live table; the optional coordinate fill is a time-boxed UPDATE grant opened and closed by the migrate role.
- The job first runs `azcopy` (identity login) to stream each blob to the job's ephemeral volume **one table at a time**, runs the tool on it, then deletes the local copy: ephemeral storage need = the largest table (31.1 GB) plus 20 GB. (If infra prefers, Blob mounted read-only through the blob CSI/NFS path: the tool only needs a readable file path.)
- Network: the job needs the storage private endpoint and the PostgreSQL private endpoint; **no internet egress**.
- Logs: container stdout to Log Analytics. The tool prints table names, chunk keys, counts and statement numbers only; infra must not enable request/response body capture for the job.

## Retention
The dump stays in `apsis-dump` until the owner signs the reconciliation, then it is deleted by the owner's decision (soft delete 7 days is acceptable; no geo-replication of the dump).

## Needs from infra-final
Storage account and two containers, the job and its identity, the Entra principal as a PostgreSQL role (member of `apsis_migrator`, which the migrations create), a PITR-capable PostgreSQL SKU with storage autogrow and 250 GB free before the run, `max_wal_size` and `checkpoint_timeout` server parameters settable by the runbook for the load window, and the allow-list for the extensions the repository already needs (`btree_gist`); no new extension is required by this tool.
