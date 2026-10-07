#!/usr/bin/env bash
# Bisects a dblogins job execution that failed with no replica (deploy runs 37624445094 to 37649162760: status Failed,
# "No replicas found for execution", no console or system log). Starts two short executions of the SAME job with a
# per-execution template override (the job's own template is unchanged), then prints which one ran:
#   A  same image, `psql --version`, no environment           -> image and registry pull
#   B  A plus the four Key Vault secret references             -> secret resolution
# If both succeed, what is left is the job's real command and its SQL (an env value until run 37659152959 showed it;
# now a mounted file). Never prints a secret value.
# Usage: infra/scripts/dblogins-probe.sh <resource-group> <job-name>
set -euo pipefail
RG="${1:?resource group}"; JOB="${2:?job name}"
image="$(az containerapp job show -g "$RG" -n "$JOB" --query 'properties.template.containers[0].image' -o tsv)"
probe() { # label with-secrets
  local tpl execution status
  tpl="$(mktemp --suffix .yaml)"
  python3 - "$image" "$2" > "$tpl" <<'PY'
import json, sys
image, secrets = sys.argv[1], sys.argv[2] == "yes"
env = [{"name": n, "secretRef": r} for n, r in (("ARON_DB_URL", "db-direct-url"), ("ARON_PW_APP_API", "pw-app-api"),
       ("ARON_PW_APP_WORKER", "pw-app-worker"), ("ARON_PW_APP_JOBS", "pw-app-jobs"))] if secrets else []
# JSON is valid YAML. The command never echoes a value: it only reports which variables are set.
print(json.dumps({"containers": [{"name": "dblogins", "image": image, "resources": {"cpu": 0.25, "memory": "0.5Gi"},
    "command": ["/bin/sh", "-c", "psql --version; for v in ARON_DB_URL ARON_PW_APP_API ARON_PW_APP_WORKER ARON_PW_APP_JOBS; do eval \"x=\\${$v:-}\"; [ -n \"$x\" ] && echo \"$v set\" || echo \"$v unset\"; done"],
    "env": env}]}))
PY
  execution="$(az containerapp job start -g "$RG" -n "$JOB" --yaml "$tpl" --query name -o tsv 2>&1)" \
    || { echo "probe $1: could not start: $execution"; return 0; }
  status=""
  for _ in $(seq 1 30); do
    status="$(az containerapp job execution show -g "$RG" -n "$JOB" --job-execution-name "$execution" --query properties.status -o tsv 2>/dev/null || echo unknown)"
    case "$status" in Succeeded|Failed|Stopped|Degraded) break ;; esac
    sleep 10
  done
  echo "probe $1 ($execution): ${status:-unknown}"
  az containerapp job logs show -g "$RG" -n "$JOB" --execution "$execution" --container dblogins --tail 20 --format text 2>&1 \
    | sed "s/^/  probe $1: /" | tail -n 20 || true
}
probe A no
probe B yes
