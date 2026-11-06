#!/usr/bin/env bash
# Read-only diagnostics of the dev rehearsal environment (D-OPS-DIAGNOSE-01), run by .github/workflows/ops-diagnose.yml
# with the deploy identity: the PostgreSQL server (state, metrics, parameters, server-log excerpts when downloadable,
# activity log, diagnostic settings), the PostgreSQL server log lines streamed to Log Analytics, the migrate job's and the
# worker's console and system logs from Log Analytics, and the worker app's state. Everything printed is metadata, metrics
# or log excerpts; never a secret, connection string or Key Vault value.
# This script changes nothing in Azure: it only lists, shows, queries and downloads logs.
#
# Usage: AZURE_RESOURCE_GROUP=rg-aron-dev infra/scripts/diagnose.sh <since-utc> <until-utc> [job-prefix] [worker-app]
set -uo pipefail
RG="${AZURE_RESOURCE_GROUP:?set AZURE_RESOURCE_GROUP}"
SINCE="${1:?since (UTC, e.g. 2026-11-05T09:40:00Z)}"
UNTIL="${2:?until (UTC)}"
JOB_PREFIX="${3:-caj-aron-dev-migrate}"
WORKER_APP="${4:-ca-aron-dev-worker}"
section() { printf '\n== %s\n' "$*"; }
try() { "$@" 2>&1 || echo "(command failed: $*)"; }

section "az version"
az version -o tsv 2>/dev/null | head -1 || true
section "containerapp start/stop commands available in this az"
az containerapp -h 2>&1 | grep -E '^\s+(start|stop)\b' || echo "(no start/stop subcommands listed)"

section "PostgreSQL server"
server="$(az postgres flexible-server list -g "$RG" --query "[?starts_with(name,'psql-aron-')].name | [0]" -o tsv 2>/dev/null || true)"
if [ -z "$server" ]; then echo "(no psql-aron-* server in $RG)"; else
  echo "server: $server"
  try az postgres flexible-server show -g "$RG" -n "$server" --query '{state:state, version:version, sku:sku.name, tier:sku.tier, storageGb:storage.storageSizeGb, ha:highAvailability.mode, haState:highAvailability.state, maintenance:maintenanceWindow.customWindow}' -o json
  sid="$(az postgres flexible-server show -g "$RG" -n "$server" --query id -o tsv 2>/dev/null || true)"
  section "PostgreSQL diagnostic settings (which log categories stream to Log Analytics)"
  if [ -n "$sid" ]; then try az monitor diagnostic-settings list --resource "$sid" -o json | head -60; fi
  section "PostgreSQL parameters that bound a migration"
  for p in work_mem shared_buffers maintenance_work_mem temp_buffers statement_timeout idle_in_transaction_session_timeout lock_timeout log_min_duration_statement logfiles.download_enable logfiles.retention_days max_connections; do
    v="$(az postgres flexible-server parameter show -g "$RG" -s "$server" -n "$p" --query value -o tsv 2>/dev/null || echo '?')"
    printf '%-40s %s\n' "$p" "$v"
  done
  section "PostgreSQL metrics $SINCE .. $UNTIL (PT1M, max)"
  if [ -n "$sid" ]; then
    try az monitor metrics list --resource "$sid" --metric cpu_percent memory_percent active_connections connections_failed is_db_alive storage_percent iops \
      --start-time "$SINCE" --end-time "$UNTIL" --interval PT1M --aggregation Maximum \
      --query "value[].{metric:name.value, points:timeseries[0].data[?maximum!=null].[timeStamp,maximum]}" -o json | head -400
  fi
  section "PostgreSQL activity log $SINCE .. $UNTIL (platform events: restarts, failovers, maintenance)"
  if [ -n "$sid" ]; then
    try az monitor activity-log list --resource-id "$sid" --start-time "$SINCE" --end-time "$UNTIL" \
      --query "[].{time:eventTimestamp, op:operationName.localizedValue, status:status.localizedValue, sub:subStatus.localizedValue}" -o table
  fi
  section "PostgreSQL server log files (last 24 h) and excerpts in the window"
  files="$(az postgres flexible-server server-logs list -g "$RG" -s "$server" --file-last-written 24 --query "[].name" -o tsv 2>&1)" || { echo "(server-logs list failed: ${files:-no text}; logfiles.download_enable is off unless the error says otherwise)"; files=""; }
  if [ -z "$files" ]; then
    echo "(no downloadable server log files in the last 24 h)"
  else
    tmp="$(mktemp -d)"
    ( cd "$tmp" && for f in $files; do
        az postgres flexible-server server-logs download -g "$RG" -s "$server" -n "$f" -o none 2>&1 || echo "(download of $f failed)"
      done )
    # Severity lines and slow statements only; DETAIL/parameter lines are dropped so no row values are printed. Postgres
    # log lines start "YYYY-MM-DD HH:MM:SS.mmm UTC"; the window bounds are ISO "YYYY-MM-DDTHH:MM", so the key is rebuilt as such.
    find "$tmp" -type f | while read -r lf; do
      echo "-- $(basename "$lf")"
      grep -E 'FATAL|PANIC|terminat|out of memory|Killed|database system|server process|checkpoint|could not|duration: [0-9]{5,}' "$lf" \
        | grep -v -E 'DETAIL|parameters:' | awk -v s="${SINCE:0:16}" -v u="${UNTIL:0:16}" '{ t=$1 "T" substr($2,1,5); if (t >= s && t <= u) print substr($0,1,200) }' | head -80
    done
    rm -rf "$tmp"
  fi
fi

section "Log Analytics: console log of job executions starting with $JOB_PREFIX in the window"
ws="$(az monitor log-analytics workspace list -g "$RG" --query "[0].customerId" -o tsv 2>/dev/null || true)"
if [ -z "$ws" ]; then echo "(no Log Analytics workspace in $RG)"; else
  q="union isfuzzy=true ContainerAppConsoleLogs_CL, ContainerAppConsoleLogs
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | extend exec = coalesce(column_ifexists('ContainerGroupName_s', ''), column_ifexists('ContainerGroupName', '')),
              line = coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', ''))
     | where exec startswith '$JOB_PREFIX' | order by TimeGenerated asc | project TimeGenerated, exec, line | take 400"
  try az monitor log-analytics query -w "$ws" --analytics-query "$q" --query "[].[TimeGenerated, exec, line]" -o tsv | cut -c1-400
  section "Log Analytics: system log (platform reasons) for $JOB_PREFIX and $WORKER_APP in the window"
  q="union isfuzzy=true ContainerAppSystemLogs_CL, ContainerAppSystemLogs
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | where * has '$JOB_PREFIX' or * has '$WORKER_APP'
     | extend line = strcat(coalesce(column_ifexists('Reason_s', ''), column_ifexists('Reason', '')), ': ',
                            coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', '')))
     | order by TimeGenerated asc | project TimeGenerated, line | take 200"
  try az monitor log-analytics query -w "$ws" --analytics-query "$q" --query "[].[TimeGenerated, line]" -o tsv | cut -c1-300
  section "Log Analytics: PostgreSQL server log (AzureDiagnostics, PostgreSQLLogs) in the window, severity lines only"
  # Server-side reasons for a lost session (terminated, reset, out of memory, crash recovery): ERROR/FATAL/PANIC lines and the
  # LOG lines that name such a reason. Slow-statement (duration), STATEMENT, CONTEXT, QUERY, DETAIL and parameter records are
  # dropped, so no SQL text or bind value is printed; an ERROR line is kept as the server wrote it, and a disconnection line
  # names the login role, database and client host (not secrets). The anchored exclusion on -LOG: execute/statement/parse/bind
  # (the prefixes log_statement and log_min_duration_statement produce) keeps SQL text out even if log_statement is ever turned on.
  q="AzureDiagnostics
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | where ResourceProvider == 'MICROSOFT.DBFORPOSTGRESQL' and Category == 'PostgreSQLLogs'
     | extend msg = tostring(coalesce(column_ifexists('Message', ''), column_ifexists('message_s', '')))
     | where msg matches regex '-(ERROR|FATAL|PANIC):' or (msg matches regex '-LOG:' and msg matches regex '(?i)terminat|out of memory|database system|server process|checkpoint|could not|disconnection|reset by peer')
     | where not(msg matches regex @'-LOG:\s+(execute|statement|parse|bind)')
     | where msg !contains 'duration:' and msg !contains 'statement:' and msg !contains 'CONTEXT:' and msg !contains 'QUERY:' and msg !contains 'DETAIL:' and msg !contains 'parameters:'
     | order by TimeGenerated asc | project TimeGenerated, msg = substring(msg, 0, 240) | take 300"
  try az monitor log-analytics query -w "$ws" --analytics-query "$q" --query "[].[TimeGenerated, msg]" -o tsv | cut -c1-300
fi

section "Log Analytics: console log of $WORKER_APP in the window (severity and job-run lines only, 200 chars each)"
# The worker's scheduled jobs (bundle pre-generation at 16:00 UTC, outlet-request lapse, aggregation, geo re-check, retention)
# log one line per run. The console is one JSON object per line (logback), so the query projects level, logger and
# formattedMessage (cut at 200 characters) and not the raw line. Lines naming SQL, parameters, keys or secrets are dropped, but a
# driver message can still carry a value: the output is for operators and the repository is public, so read it before
# pasting it anywhere (D-OPS-DIAGNOSE-WORKER-01). The newest 200 matching lines are kept.
if [ -n "${ws:-}" ]; then
  q="union isfuzzy=true ContainerAppConsoleLogs_CL, ContainerAppConsoleLogs
     | where TimeGenerated between (datetime('$SINCE') .. datetime('$UNTIL'))
     | extend app = coalesce(column_ifexists('ContainerAppName_s', ''), column_ifexists('ContainerAppName', '')),
              line = tostring(coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', '')))
     | where app == '$WORKER_APP'
     | where line matches regex @'(?i)\b(WARN|ERROR|FATAL)\b|\"level\":\"(WARN|ERROR)' or line matches regex @'(?i)bundle|lapsed|worker started|PAUSED|aggregat|geo re-check|retention|risk signal|route-day'
     | where not(line matches regex @'(?i)statement|parameters|bind|password|secret|token|authorization|bearer|key=|sig=|jdbc:')
     | extend j = parse_json(line)
     | extend text = iff(isnotempty(tostring(j.formattedMessage)), strcat(tostring(j.level), ' ', tostring(j.loggerName), ' ', tostring(j.formattedMessage)), line)
     | top 200 by TimeGenerated desc | order by TimeGenerated asc
     | project TimeGenerated, line = substring(text, 0, 200)"
  try az monitor log-analytics query -w "$ws" --analytics-query "$q" --query "[].[TimeGenerated, line]" -o tsv | cut -c1-260
else
  echo "(no Log Analytics workspace, so no worker console log)"
fi

section "worker app $WORKER_APP"
try az containerapp show -g "$RG" -n "$WORKER_APP" --query '{running:properties.runningStatus, provisioning:properties.provisioningState, latest:properties.latestRevisionName, latestReady:properties.latestReadyRevisionName, mode:properties.configuration.activeRevisionsMode}' -o json
try az containerapp revision list -g "$RG" -n "$WORKER_APP" --query "[?properties.active].{name:name, replicas:properties.replicas, running:properties.runningState, created:properties.createdTime}" -o table
echo
echo "diagnose: done (read-only)"
