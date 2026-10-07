#!/usr/bin/env bash
# Release marker (N-062): writes an Application Insights release annotation for a deploy that passed the health gate,
# so every chart in Performance, Failures and the ops workbook shows where a build went live. Learn: "Release
# annotations" (PUT <component id>/Annotations?api-version=2015-05-01, Category must be "Deployment").
# Never fails the deploy: a marker that cannot be written is a warning (the deploy identity is Contributor on the group).
# Usage: infra/scripts/release-marker.sh <resource-group-id> <env> <commit> <run-url> [rollback]
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
rg_id="${1:?resource group id}"
env_name="${2:?environment}"
sha="${3:?commit}"
run_url="${4:-}"
kind="${5:-deploy}"
ai_id="${rg_id}/providers/Microsoft.Insights/components/appi-aron-${env_name}"
body="$(python3 - "$sha" "$env_name" "$run_url" "$kind" <<'PY'
import datetime, json, sys, uuid
sha, env, run, kind = sys.argv[1:5]
print(json.dumps({
    "Id": str(uuid.uuid4()),
    "AnnotationName": f"{kind} {sha[:7]}",
    "EventTime": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "Category": "Deployment",
    "Properties": json.dumps({"Commit": sha, "Environment": env, "Kind": kind, "Run": run}),
}))
PY
)"
if az rest --method put --uri "${ai_id}/Annotations?api-version=2015-05-01" --body "$body" -o none; then
  note "release marker written: ${kind} ${sha:0:7} on appi-aron-${env_name}"
else
  echo "::warning::release marker not written on appi-aron-${env_name} (the deploy itself is fine)"
fi
