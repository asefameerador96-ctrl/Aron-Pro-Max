#!/usr/bin/env bash
# Front Door Premium reaches the Container Apps environment over Private Link; each new origin creates a pending
# private endpoint connection on the environment that must be approved (Learn: how-to-integrate-with-azure-front-door).
# Approves every pending connection whose request message is ours. Idempotent.
# Usage: infra/scripts/approve-private-link.sh <resource-group> <container-apps-environment-name>
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
rg="${1:?resource group}"; env="${2:?environment name}"

for _ in $(seq 1 20); do
  pending="$(az network private-endpoint-connection list --resource-group "$rg" --name "$env" \
    --type Microsoft.App/managedEnvironments \
    --query "[?properties.privateLinkServiceConnectionState.status=='Pending' && properties.privateLinkServiceConnectionState.description=='aron-frontdoor'].id" -o tsv)"
  approved="$(az network private-endpoint-connection list --resource-group "$rg" --name "$env" \
    --type Microsoft.App/managedEnvironments \
    --query "length([?properties.privateLinkServiceConnectionState.status=='Approved'])" -o tsv)"
  for id in $pending; do
    note "approving $id"
    az network private-endpoint-connection approve --id "$id" --description "approved by deploy" --only-show-errors >/dev/null
  done
  if [ -z "$pending" ] && [ "${approved:-0}" -gt 0 ]; then note "private link approved ($approved connection(s))"; exit 0; fi
  sleep 15
done
die "no approved Front Door private endpoint connection on $env after 5 minutes"
