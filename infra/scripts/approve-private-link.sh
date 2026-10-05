#!/usr/bin/env bash
# Front Door Premium reaches the Container Apps environment over Private Link; each origin creates a pending private
# endpoint connection on the environment that must be approved (Learn: how-to-integrate-with-azure-front-door).
# Approves every pending connection carrying our request message, until at least <expected> are approved. Idempotent.
# Usage: infra/scripts/approve-private-link.sh <resource-group> <container-apps-environment-name> <expected-origins>
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
rg="${1:?resource group}"; env="${2:?environment name}"; expected="${3:-1}"

list() {
  az network private-endpoint-connection list --resource-group "$rg" --name "$env" \
    --type Microsoft.App/managedEnvironments --query "$1" -o tsv
}
for _ in $(seq 1 40); do
  for id in $(list "[?properties.privateLinkServiceConnectionState.status=='Pending' && properties.privateLinkServiceConnectionState.description=='aron-frontdoor'].id"); do
    note "approving $id"
    az network private-endpoint-connection approve --id "$id" --description "approved by deploy" -o none
  done
  approved="$(list "length([?properties.privateLinkServiceConnectionState.status=='Approved'])")"
  if [ "${approved:-0}" -ge "$expected" ]; then note "private link approved ($approved connection(s), $expected expected)"; exit 0; fi
  sleep 15
done
die "fewer than $expected approved Front Door private endpoint connections on $env after 10 minutes"
