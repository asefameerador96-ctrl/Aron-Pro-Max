#!/usr/bin/env bash
# Last green ci run on the integration branch (INT) and its age (CI audit s5 items 5 and 8; evening report).
# Usage: tools/ci/last-green-int.sh [owner/repo]   (needs gh signed in, or GH_TOKEN; read-only)
# Prints one line, e.g. "last green INT run: #352 0c63e39 finished 2026-10-07T07:10:00Z, 42 min ago; INT head 97feb99: failure".
# Exit 0 always for the report; exit 3 when INT has had no green run for more than LAST_GREEN_ALERT_MIN minutes
# (default 30) so a scheduled check can alert on it.
set -euo pipefail
repo="${1:-${GITHUB_REPOSITORY:-asefameerador96-ctrl/Aron-Pro-Max}}"
int="${INTEGRATION_BRANCH:-claude/wonderful-thompson-k6ejnf}"
alert_min="${LAST_GREEN_ALERT_MIN:-30}"
runs="repos/${repo}/actions/workflows/ci.yml/runs?branch=${int}&event=push"
green="$(gh api "${runs}&status=success&per_page=1" --jq '.workflow_runs[0] | select(.) | "\(.run_number) \(.head_sha[0:7]) \(.updated_at)"')"
latest="$(gh api "${runs}&per_page=1" --jq '.workflow_runs[0] | select(.) | "\(.head_sha[0:7]) \(.status) \(.conclusion // "-")"')"
read -r head_sha head_status head_conclusion <<< "${latest:-- - -}"
verdict="${head_conclusion}"; [ "${head_status}" = completed ] || verdict="${head_status}"
if [ -z "${green}" ]; then
  echo "last green INT run: none found; INT head ${head_sha}: ${verdict}"
  exit 3
fi
read -r num sha finished <<< "${green}"
age=$(( ( $(date -u +%s) - $(date -u -d "${finished}" +%s) ) / 60 ))
echo "last green INT run: #${num} ${sha} finished ${finished}, ${age} min ago; INT head ${head_sha}: ${verdict}"
if [ "${verdict}" != success ] && [ "${age}" -gt "${alert_min}" ]; then exit 3; fi
