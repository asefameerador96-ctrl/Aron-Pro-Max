#!/usr/bin/env bash
# Shared helpers for the deploy scripts. Source it; never run it.
set -euo pipefail

die() { echo "::error::$*" >&2; exit 1; }
note() { echo "== $*"; }
need() { [ -n "${!1:-}" ] || die "environment variable $1 is required ($2)"; }

# Object id of the signed-in principal, read from its ARM access token (works for a service principal without Graph).
my_object_id() {
  az account get-access-token --query accessToken -o tsv | cut -d. -f2 | python3 -c '
import base64, json, sys
p = sys.stdin.read().strip(); p += "=" * (-len(p) % 4)
print(json.loads(base64.urlsafe_b64decode(p))["oid"])'
}

# Gives the signed-in principal Key Vault Secrets Officer on one vault (allowed by its role-assignment condition) and
# waits until data-plane reads work. Used when a first run failed between creating the vault and granting the role.
ensure_secrets_officer() { # vault-id vault-name
  local oid; oid="$(my_object_id)"
  az role assignment create --assignee-object-id "$oid" --assignee-principal-type ServicePrincipal \
    --role b86a8fe4-44ce-4948-aee5-eccb2c155cd7 --scope "$1" -o none 2>/dev/null || true
  for _ in $(seq 1 30); do
    az keyvault secret list --vault-name "$2" --maxresults 1 -o none 2>/dev/null && return 0
    sleep 10
  done
  return 1
}

# Deploy freeze (docs/18 selling hours): returns 0 when minute-of-day $2 (default: now in Asia/Dhaka) lies inside the
# window $1 "HH:MM-HH:MM" (start inclusive, end exclusive; a window past midnight such as 22:00-06:00 wraps).
# Exits through die on a malformed window, so a typo never silently disables the freeze.
in_freeze_window() { # window [minute-of-day]
  [[ "$1" =~ ^([01][0-9]|2[0-3]):([0-5][0-9])-([01][0-9]|2[0-3]):([0-5][0-9])$ ]] \
    || die "the deploy freeze window must look like 07:00-19:00 (00:00 to 23:59), was '$1'"
  local from=$(( 10#${BASH_REMATCH[1]} * 60 + 10#${BASH_REMATCH[2]} )) to=$(( 10#${BASH_REMATCH[3]} * 60 + 10#${BASH_REMATCH[4]} ))
  local now="${2:-$(( 10#$(TZ=Asia/Dhaka date +%H) * 60 + 10#$(TZ=Asia/Dhaka date +%M) ))}"
  if [ "$from" -le "$to" ]; then
    [ "$now" -ge "$from" ] && [ "$now" -lt "$to" ]
  else
    [ "$now" -ge "$from" ] || [ "$now" -lt "$to" ]
  fi
}
