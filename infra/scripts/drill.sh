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
  # The probe runs in the background from BEFORE the Azure call until the api is ready again after it: the call itself
  # returns minutes after the switch (429 s on 2026-10-07 for about 30 s of outage), so timing from its return, or the
  # call's own duration, overstates what users saw. One line per probe: "<epoch at probe start> <http code>".
  probe_s="${DRILL_PROBE_S:-5}"; max_s="${DRILL_MAX_S:-900}"
  [[ "$probe_s" =~ ^[0-9]+(\.[0-9]+)?$ ]] || die "DRILL_PROBE_S must be a number of seconds"
  [[ "$max_s" =~ ^[0-9]+$ ]] || die "DRILL_MAX_S must be whole seconds"
  probes="$(mktemp)"
  ( while :; do
      t="$(now)"; code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 10 "$ready_url" 2>/dev/null)" || true
      echo "$t ${code:-000}" >> "$probes"
      sleep "$probe_s"
    done ) &
  prober=$!
  trap 'kill "$prober" 2>/dev/null || true' EXIT
  t0="$(now)"
  az postgres flexible-server restart -g "$RG" -n "$server" --failover Forced -o none || die "forced failover failed"
  t1="$(now)"
  # Ready again: a 200 probe that started after the call returned (waits at most max_s after the call; the call itself
  # can take longer than that, so the deadline counts from its return, and the probe log is read once more at the end).
  ready=""
  ready_after_call() { awk -v t="$t1" '$1 >= t && $2 == 200 { f = 1 } END { exit !f }' "$probes"; }
  while [ "$(( $(now) - t1 ))" -lt "$max_s" ]; do
    if ready_after_call; then ready=1; break; fi
    sleep 1
  done
  [ -n "$ready" ] || ! ready_after_call || ready=1
  kill "$prober" 2>/dev/null || true; wait "$prober" 2>/dev/null || true
  after="$(az postgres flexible-server show -g "$RG" -n "$server" --query availabilityZone -o tsv)"
  # Outage window: from the first failed probe after the start to the first 200 after the last failed probe.
  read -r first_fail last_fail back failed total < <(awk -v t0="$t0" '
    $1 >= t0 { n++; if ($2 != 200) { if (ff == "") ff = $1; lf = $1; nf++; bk = "" } else if (lf != "" && bk == "") bk = $1 }
    END { print (ff == "" ? "-" : ff), (lf == "" ? "-" : lf), (bk == "" ? "-" : bk), nf + 0, n + 0 }' "$probes")
  summary "### Forced failover drill ($server, $(date -u +%Y-%m-%dT%H:%MZ))"
  summary "- primary zone: ${before} -> ${after}"
  summary "- Azure failover call returned after $(( t1 - t0 )) s (this is NOT the outage)"
  summary "- readiness probes (GET /v1/health/ready via ${host}, every ${probe_s} s from before the call): ${total}, of them ${failed} not 200"
  if [ -z "$ready" ]; then
    summary "- api NOT ready $(( max_s / 60 )) minutes after the Azure call returned: the app does not reconnect (REL-01); restart the api revision (RB-02)"
    exit 1
  fi
  if [ "$back" = - ] && [ "$first_fail" != - ]; then
    summary "- user-visible outage: readiness failed again after it came back (last failed probe $(( last_fail - t0 )) s after the start); read the probe log and RB-02"
    exit 1
  elif [ "$first_fail" = - ]; then
    summary "- user-visible outage: no failed probe (shorter than the ${probe_s} s probe interval)"
  else
    summary "- user-visible outage: about $(( back - first_fail )) s (first failed probe $(( first_fail - t0 )) s after the start, ready again at $(( back - t0 )) s; resolution ${probe_s} s plus the 10 s probe timeout)"
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
