#!/usr/bin/env bash
# Removes, from ONE resource group, the resources whose creation-time settings cannot be changed in place to the
# profile in infra/params/<env>.bicepparam (docs/28), so the next deploy can create them right:
#   - PostgreSQL servers whose tier, storage type, geo-redundant backup or network mode differ (fixed at creation);
#   - a Container Apps environment whose VNet setting differs (fixed at creation), with the apps and jobs on it;
#   - Front Door profiles and WAF policies when the profile has no Front Door (they cost money while unused);
#   - storage accounts whose replication cannot be converted in place (ZRS/GZRS <-> LRS), with their Event Grid topic;
#   - the VNet, its NSGs and the private DNS zone when the profile has no private networking.
# Key Vault, Log Analytics, Application Insights, the registry, identities, alerts and the budget are kept (compatible,
# and a deleted Key Vault would reserve its name for 90 days).
#
# DRY RUN by default: prints what it would delete. Deletes only with CONFIRM=<the resource group name>.
# CHECK_ONLY=1: fail (change nothing) when anything would be deleted; deploy.sh runs this before main.bicep.
# Usage: CONFIRM=rg-aron-dev infra/scripts/reset-to-profile.sh <resource-group> <env>
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
rg="${1:?resource group}"; env_name="${2:?dev or prod}"
cd "$(dirname "$0")/../.." || exit 1

params="$(ARON_DB_ADMIN_PASSWORD=reset-only-not-a-secret-000 az bicep build-params \
  --file "infra/params/${env_name}.bicepparam" --stdout)" || die "cannot compile infra/params/${env_name}.bicepparam"
want() { python3 -c 'import json,sys; p=json.loads(json.loads(sys.argv[1])["parametersJson"])["parameters"]; v=p[sys.argv[2]]["value"]; print(str(v).lower() if isinstance(v,bool) else v)' "$params" "$1"; }
tier="$(want postgresSkuTier)"; storage_type="$(want postgresStorageType)"; geo="$(want postgresGeoRedundantBackup)"
private="$(want privateNetworking)"; front_door="$(want deployFrontDoor)"; storage_sku="$(want storageSku)"
note "profile $env_name: postgres $tier/$storage_type geo=$geo, privateNetworking=$private, frontDoor=$front_door, storage $storage_sku"

doomed=()
add() { doomed+=("$1"); echo "   will delete: $1 ($2)"; }

for id in $(az postgres flexible-server list -g "$rg" --query "[].id" -o tsv); do
  read -r t st g pna <<<"$(az postgres flexible-server show --ids "$id" \
    --query "[sku.tier, storage.type, backup.geoRedundantBackup, network.publicNetworkAccess]" -o tsv | tr '\n' ' ')"
  want_geo=Disabled; [ "$geo" = true ] && want_geo=Enabled
  want_pna=Enabled; [ "$private" = true ] && want_pna=Disabled
  st="${st:-Premium_LRS}"
  if [ "$t" != "$tier" ] || [ "$st" != "$storage_type" ] || [ "$g" != "$want_geo" ] || [ "$pna" != "$want_pna" ]; then
    add "$id" "postgres $t/$st geo=$g public=$pna, profile wants $tier/$storage_type geo=$want_geo public=$want_pna"
  fi
done

for id in $(az containerapp env list -g "$rg" --query "[].id" -o tsv); do
  subnet="$(az containerapp env show --ids "$id" --query "properties.vnetConfiguration.infrastructureSubnetId" -o tsv 2>/dev/null || true)"
  has_vnet=false; [ -n "$subnet" ] && has_vnet=true
  if [ "$has_vnet" != "$private" ]; then
    for app in $(az containerapp list -g "$rg" --query "[?properties.managedEnvironmentId=='$id'].id" -o tsv); do add "$app" "app on that environment"; done
    for job in $(az containerapp job list -g "$rg" --query "[?properties.environmentId=='$id'].id" -o tsv); do add "$job" "job on that environment"; done
    add "$id" "container apps environment vnet=$has_vnet, profile wants $private"
  fi
done

if [ "$front_door" != true ]; then
  for id in $(az resource list -g "$rg" --resource-type Microsoft.Cdn/profiles --query "[].id" -o tsv); do add "$id" "Front Door, profile has none"; done
  for id in $(az resource list -g "$rg" --resource-type Microsoft.Network/FrontDoorWebApplicationFirewallPolicies --query "[].id" -o tsv); do add "$id" "WAF policy, profile has none"; done
fi

for id in $(az storage account list -g "$rg" --query "[].id" -o tsv); do
  sku="$(az storage account show --ids "$id" --query sku.name -o tsv)"
  zonal() { case "$1" in *ZRS*) echo z ;; *) echo l ;; esac; }
  if [ "$sku" != "$storage_sku" ] && [ "$(zonal "$sku")" != "$(zonal "$storage_sku")" ]; then
    for t in $(az resource list -g "$rg" --resource-type Microsoft.EventGrid/systemTopics --query "[].id" -o tsv); do add "$t" "Event Grid topic of the storage account"; done
    add "$id" "storage $sku cannot become $storage_sku in place"
  fi
done

if [ "$private" != true ]; then
  for type in Microsoft.Network/virtualNetworks Microsoft.Network/networkSecurityGroups Microsoft.Network/privateDnsZones; do
    for id in $(az resource list -g "$rg" --resource-type "$type" --query "[].id" -o tsv); do add "$id" "network resource, profile has no VNet"; done
  done
fi

if [ "${#doomed[@]}" -eq 0 ]; then note "nothing to reset: $rg matches the $env_name profile"; exit 0; fi
if [ -n "${CHECK_ONLY:-}" ]; then
  die "$rg holds ${#doomed[@]} resource(s) the $env_name profile cannot adopt (listed above; creation-time settings differ). Nothing was changed. Run the 'reset' workflow (GitHub > Actions > reset, type the group name) or infra/scripts/reset-to-profile.sh, then deploy again."
fi
if [ "${CONFIRM:-}" != "$rg" ]; then
  note "DRY RUN: ${#doomed[@]} resource(s) listed above would be deleted. Re-run with CONFIRM=$rg to delete them."
  exit 0
fi
note "deleting ${#doomed[@]} resource(s) from $rg"
for id in "${doomed[@]}"; do
  echo "   deleting $id"
  az resource delete --ids "$id" -o none || echo "::warning::could not delete $id (deleted with its parent, or retry)"
done
note "reset done; run the deploy again"
