#!/usr/bin/env bash
# Read-only deploy-summary cells about the Key Vault (D-INFRA-LAKE-05/06): evidence for the what-if's recurring
# "Modify networkAcls" on the vault. Prints exactly ONE line and always exits 0, so a summary row can never stop the
# deploy; every failure says which step failed, with az's exit status and first output line (ids, resource paths and
# long tokens masked). Never prints a secret: only control-plane fields, role names and counts.
#   kv-acl-row.sh acls  <resource-group> <vault>   network ACLs of the vault as the control plane returns them
#   kv-acl-row.sh roles <resource-group> <vault>   role names the signed-in (deploy) identity holds on the vault scope
set -uo pipefail
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
set +e
mode="${1:-}"; rg="${2:-}"; kv="${3:-}"
tmp="$(mktemp -d 2>/dev/null)" || tmp=""
[ -n "$tmp" ] || { echo "not read: no temp directory"; exit 0; }
trap 'rm -rf "$tmp"' EXIT

mask() { # first non-empty line of a file, masked, single line, no table separators
  grep -m1 -v '^[[:space:]]*$' "$1" 2>/dev/null | sed -E \
    -e "s#(^|[ '\"(])/[Ss]ubscriptions/[^ '\")]*#\\1[resource]#g" \
    -e 's/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}/[id]/g' \
    -e 's/[A-Za-z0-9+=_-]{32,}/[masked]/g' -e 's/[|`]/ /g' | cut -c1-200
}
why() { local m; m="$(mask "$1")"; echo "${m:-no error text}"; }

if [ -z "$rg" ] || [ -z "$kv" ]; then echo "not read: resource group or vault name empty"; exit 0; fi
command -v az >/dev/null 2>&1 || { echo "not read: az not found"; exit 0; }
az account show --query id -o tsv >"$tmp/sub" 2>"$tmp/err"; rc=$?
[ "$rc" -eq 0 ] || { echo "not read: not signed in (az account show exit $rc): $(why "$tmp/err")"; exit 0; }

case "$mode" in
  acls)
    az keyvault show -g "$rg" -n "$kv" --query properties.networkAcls -o json >"$tmp/out" 2>"$tmp/err"; rc=$?
    if [ "$rc" -ne 0 ]; then
      echo "not read: az keyvault show exit $rc: $(why "$tmp/err")"; exit 0
    fi
    python3 - "$tmp/out" <<'PY' 2>"$tmp/py" || echo "not read: parse failed: $(why "$tmp/py")"
import json, sys
try:
    text = open(sys.argv[1]).read().strip()
    if not text or text == "null":
        # az prints nothing for a null query result: the vault carries no networkAcls block.
        print("none returned (networkAcls is null on the vault: no rules, so public access follows publicNetworkAccess)")
        sys.exit(0)
    a = json.loads(text)
    print("default {}, bypass {}, {} IP rules, {} VNet rules".format(a.get("defaultAction"), a.get("bypass"),
          len(a.get("ipRules") or []), len(a.get("virtualNetworkRules") or [])))
except Exception as e:  # one line for the row, never a traceback
    print(f"{type(e).__name__}: {e}", file=sys.stderr)
    sys.exit(1)
PY
    ;;
  roles)
    oid="$(my_object_id 2>"$tmp/err")"; rc=$?
    if [ "$rc" -ne 0 ] || [ -z "$oid" ]; then echo "not read: deploy identity unknown (exit $rc): $(why "$tmp/err")"; exit 0; fi
    scope="/subscriptions/$(cat "$tmp/sub")/resourceGroups/${rg}/providers/Microsoft.KeyVault/vaults/${kv}"
    az role assignment list --scope "$scope" --include-inherited \
      --query "[?principalId=='${oid}'].roleDefinitionName" -o tsv >"$tmp/out" 2>"$tmp/err"; rc=$?
    if [ "$rc" -ne 0 ]; then
      echo "not read: az role assignment list exit $rc: $(why "$tmp/err")"; exit 0
    fi
    roles="$(sort -u "$tmp/out" | grep -v '^[[:space:]]*$' | paste -sd ',' - | sed 's/,/, /g')"
    echo "${roles:-none}"
    ;;
  *)
    echo "not read: unknown mode '${mode}'"
    ;;
esac
exit 0
