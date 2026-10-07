#!/usr/bin/env bash
# Database drills on the dev rehearsal server (docs/setup/move-to-final-account.md s5a, AUD-REL-05). Timings go to the
# run summary and then into docs/status/infra.md and the runbooks (RB-02, RB-14).
#   failover  forced HA failover (az postgres flexible-server restart --failover Forced), then the time until the api
#             answers /v1/health/ready 200 through the public address again. Dev is unavailable for that time.
#   pitr      point-in-time restore of the server as it was 10 minutes ago into a NEW server, row counts of every app
#             table compared on both (inside the VNet, through the dblogins job), then the restored server is DELETED in
#             the same run (also on failure). It costs money while it exists (summary states the estimate).
# Usage: infra/scripts/drill.sh <resource-group> <failover|pitr>
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
RG="${1:?resource group}"; MODE="${2:?failover or pitr}"
summary() { [ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "$*" >> "$GITHUB_STEP_SUMMARY"; echo "$*"; }
lock="$(az group show -n "$RG" --query 'tags."aron-deploy-lock"' -o tsv 2>/dev/null || true)"
if [ -n "$lock" ] && [ "$lock" != None ]; then die "a deploy holds the lock on $RG ($lock); run the drill after it"; fi
server="$(az postgres flexible-server list -g "$RG" --query "[?starts_with(name, 'psql-aron-')] | [0].name" -o tsv)"
if [ -z "$server" ] || [ "$server" = None ]; then die "no Aron PostgreSQL server in $RG"; fi
host="$(az deployment group show -g "$RG" -n aron-apps --query properties.outputs.apiHost.value -o tsv)"
ready_url="https://${host}/v1/health/ready"
now() { date +%s; }

case "$MODE" in
failover)
  ha="$(az postgres flexible-server show -g "$RG" -n "$server" --query highAvailability.mode -o tsv)"
  [ "$ha" = ZoneRedundant ] || [ "$ha" = SameZone ] || die "$server has no HA ($ha): a forced failover is not possible"
  before="$(az postgres flexible-server show -g "$RG" -n "$server" --query availabilityZone -o tsv)"
  t0="$(now)"
  az postgres flexible-server restart -g "$RG" -n "$server" --failover Forced -o none || die "forced failover failed"
  t1="$(now)"
  t2=""
  for _ in $(seq 1 180); do
    code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$ready_url" || echo 000)"
    if [ "$code" = 200 ]; then t2="$(now)"; break; fi
    sleep 5
  done
  after="$(az postgres flexible-server show -g "$RG" -n "$server" --query availabilityZone -o tsv)"
  summary "### Forced failover drill ($server, $(date -u +%Y-%m-%dT%H:%MZ))"
  summary "- primary zone: ${before} -> ${after}"
  summary "- failover command: $(( t1 - t0 )) s"
  if [ -n "$t2" ]; then
    summary "- api ready again (GET /v1/health/ready 200 via ${host}): $(( t2 - t0 )) s after the start"
  else
    summary "- api NOT ready 15 minutes after the failover: the app does not reconnect (REL-01); restart the api revision (RB-02)"
    exit 1
  fi
  ;;
pitr)
  restored="${server}-drill-$(date -u +%m%d%H%M)"
  point="$(date -u -d '-10 minutes' +%Y-%m-%dT%H:%M:%SZ)"
  cleanup() {
    if az postgres flexible-server show -g "$RG" -n "$restored" -o none 2>/dev/null; then
      if az postgres flexible-server delete -g "$RG" -n "$restored" --yes -o none; then
        summary "- restored server $restored deleted"
      else
        summary "- **could NOT delete $restored: delete it now (it costs money)**"
      fi
    fi
  }
  trap cleanup EXIT
  t0="$(now)"
  az postgres flexible-server restore -g "$RG" --name "$restored" --source-server "$server" --restore-time "$point" -o none \
    || die "point-in-time restore failed"
  t1="$(now)"
  summary "### Point-in-time restore drill ($server at $point -> $restored)"
  summary "- restore duration: $(( t1 - t0 )) s ($(( (t1 - t0 + 59) / 60 )) min)"
  # Row counts of every app table on both servers, from inside the VNet (the dblogins job with an overridden command;
  # the admin URL is its db-direct-url secret, the restored server shares login and password).
  job="$(az deployment group show -g "$RG" -n aron-apps-migrate --query properties.outputs.dbLoginsJobName.value -o tsv)"
  image="$(az containerapp job show -g "$RG" -n "$job" --query 'properties.template.containers[0].image' -o tsv)"
  restored_fqdn="$(az postgres flexible-server show -g "$RG" -n "$restored" --query fullyQualifiedDomainName -o tsv)"
  # shellcheck disable=SC2016 # expanded by the job's shell, not here
  script='set -e
q="SELECT table_name, (xpath(\$\$/row/c/text()\$\$, query_to_xml(format(\$\$SELECT count(*) AS c FROM app.%I\$\$, table_name), false, true, \$\$\$\$)))[1]::text FROM information_schema.tables WHERE table_schema = \$\$app\$\$ AND table_type = \$\$BASE TABLE\$\$ ORDER BY 1"
live="${ARON_DB_URL#jdbc:}"
copy="$(printf "%s" "$live" | sed "s#//[^:/]*#//$RESTORED_HOST#")"
psql "$live" -XAtc "$q" > /tmp/live.txt
psql "$copy" -XAtc "$q" > /tmp/copy.txt
echo "tables: $(wc -l < /tmp/live.txt) live, $(wc -l < /tmp/copy.txt) restored"
if diff /tmp/live.txt /tmp/copy.txt > /tmp/diff.txt; then echo "ROWCOUNTS EQUAL"; else echo "ROWCOUNTS DIFFER (rows written after the restore point):"; cat /tmp/diff.txt; fi'
  # The documented override: a YAML template for this one execution (the job's own template stays unchanged).
  tpl="$(mktemp --suffix .yaml)"
  python3 - "$image" "$restored_fqdn" "$script" > "$tpl" <<'PY'
import json, sys
image, host, script = sys.argv[1:4]
# JSON is valid YAML, so no YAML library is needed.
print(json.dumps({"containers": [{
    "name": "dblogins", "image": image, "resources": {"cpu": 0.25, "memory": "0.5Gi"},
    "command": ["/bin/sh", "-c", script],
    "env": [{"name": "ARON_DB_URL", "secretRef": "db-direct-url"}, {"name": "RESTORED_HOST", "value": host}]}]}))
PY
  execution="$(az containerapp job start -g "$RG" -n "$job" --yaml "$tpl" --query name -o tsv)" \
    || { summary "- row counts: the comparison job could not start"; exit 1; }
  status=""
  for _ in $(seq 1 60); do
    status="$(az containerapp job execution show -g "$RG" -n "$job" --job-execution-name "$execution" --query properties.status -o tsv 2>/dev/null || echo unknown)"
    case "$status" in Succeeded|Failed|Stopped|Degraded) break ;; esac
    sleep 10
  done
  summary "- row-count comparison job $execution: $status (output in Log Analytics, ContainerAppConsoleLogs, ContainerGroupName startswith $execution)"
  hours=$(( ( $(now) - t0 + 3599 ) / 3600 ))
  summary "- cost estimate: about USD $(python3 -c "print(round($hours * 0.244 + 0.05, 2))") (D2ds_v5 at USD 0.244 per hour, $hours h billed, no HA on the copy, plus storage)"
  [ "$status" = Succeeded ] || exit 1
  ;;
*) die "mode must be failover or pitr" ;;
esac
