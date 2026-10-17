#!/usr/bin/env bash
# DD-02 (docs/24 s4.8, F-SYS-089, db V0087): mint the new server generation after a point-in-time restore or a failover
# that may have lost acknowledged writes, BEFORE the API serves again. Phones that see a new generation re-send the rows
# the lost lineage acknowledged (trigger `resync`); without it they purge them three days later (the FM-20 silent loss).
#
# Usage: mint-generation.sh <pitr_restore|failover> <lost_after_utc> [restore_point_utc] [note]
#   lost_after_utc     ISO 8601 UTC instant after which acknowledged writes may be lost (the restore point, or the
#                      failover's last replicated instant); the function refuses a future instant.
#   restore_point_utc  the restore point (pitr_restore); defaults to lost_after_utc.
# Needs ARON_DB_ADMIN_URL: a psql URL to the server that will serve (after a restore: the restored server), as the admin
# login (the function is revoked from PUBLIC). Prints the new generation id. Runbook: docs/runbooks/pitr-restore.md.
set -euo pipefail
kind="${1:?kind: pitr_restore or failover}"
lost_after="${2:?lost_after_utc (ISO 8601)}"
restore_point="${3:-$lost_after}"
note="${4:-minted by mint-generation.sh on $(date -u +%Y-%m-%dT%H:%M:%SZ)}"
url="${ARON_DB_ADMIN_URL:?ARON_DB_ADMIN_URL (psql URL of the serving database, admin login)}"
case "$kind" in pitr_restore|failover) ;; *) echo "kind must be pitr_restore or failover" >&2; exit 2 ;; esac
before="$(psql "$url" -XAtc "SELECT generation || ' (' || kind || ', started ' || started_at || ')' FROM app.server_generation WHERE is_current")"
gen="$(psql "$url" -XAtc "SELECT app.mint_server_generation('$kind', TIMESTAMPTZ '$lost_after', TIMESTAMPTZ '$restore_point', \$aron\$${note}\$aron\$)")"
current="$(psql "$url" -XAtc "SELECT count(*) FROM app.server_generation WHERE is_current")"
[ "$current" = "1" ] || { echo "expected exactly one current generation, found $current" >&2; exit 1; }
echo "previous generation: $before"
echo "new generation:      $gen ($kind, lost after $lost_after, restore point $restore_point)"
echo "next: restart the api and worker replicas so they read the new generation (docs/runbooks/pitr-restore.md step 6)"
