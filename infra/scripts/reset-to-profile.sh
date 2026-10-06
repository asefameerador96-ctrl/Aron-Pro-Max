#!/usr/bin/env bash
# Removes, from ONE resource group, the resources whose creation-time settings cannot be changed in place to the
# profile in infra/params/<env>.bicepparam (docs/28), so the next deploy can create them right:
#   - PostgreSQL servers whose tier, storage type, geo-redundant backup or network mode differ (fixed at creation);
#   - a Container Apps environment whose VNet setting differs (fixed at creation), with the apps and jobs on it;
#   - Front Door profiles and WAF policies when the profile has no Front Door (they cost money while unused);
#   - storage accounts whose replication cannot be converted in place (ZRS/GZRS <-> LRS/GRS), with their Event Grid
#     system topic;
#   - the VNet, its NSGs and the private DNS zone (with its VNet links) when the profile has no private networking;
#   - log-search alert rules when the profile turns them off (incremental deployments never delete them; they bill).
# Key Vault, Log Analytics, Application Insights, the registry, identities, metric alerts and the budget are kept
# (compatible, and a deleted Key Vault would reserve its name for 90 days).
#
# FAIL-CLOSED: every Azure read must succeed, or the script stops without deleting anything; decisions are made from
# one JSON snapshot. Only resources inside <resource-group> are read or deleted.
# DRY RUN by default: prints the full inventory (KEEP / DELETE). Deletes only with CONFIRM=<the resource group name>.
# CHECK_ONLY=1: fail (change nothing) when anything would be deleted; deploy.sh runs this before main.bicep.
# Usage: CONFIRM=rg-aron-dev infra/scripts/reset-to-profile.sh <resource-group> <env>
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
rg="${1:?resource group}"; env_name="${2:?dev or prod}"
cd "$(dirname "$0")/../.." || exit 1
snap="$(mktemp -d)"; trap 'rm -rf "$snap"' EXIT

ARON_DB_ADMIN_PASSWORD=reset-only-not-a-secret-000 az bicep build-params \
  --file "infra/params/${env_name}.bicepparam" --stdout > "$snap/params.json" \
  || die "cannot compile infra/params/${env_name}.bicepparam"

# Wait while a deployment runs in the group (deploy.sh takes the same lock), so nothing is deleted under it.
for i in $(seq 1 90); do
  running="$(az deployment group list -g "$rg" --query "[?properties.provisioningState=='Running'] | length(@)" -o tsv)" \
    || die "cannot list deployments in $rg"
  [ "${running:-0}" -eq 0 ] && break
  [ "$i" -eq 90 ] && die "a deployment is still running in $rg after 45 minutes"
  note "a deployment is running in $rg; waiting"; sleep 30
done

# One snapshot, every read checked.
grab() { # file command...
  local f="$snap/$1"; shift
  "$@" -o json > "$f" || die "Azure read failed (nothing was deleted): $*"
}
sub="$(az account show --query id -o tsv)" || die "not signed in to Azure"
grab resources.json az rest --method get \
  --url "https://management.azure.com/subscriptions/${sub}/resourceGroups/${rg}/resources?\$expand=createdTime&api-version=2021-04-01"
grab postgres.json az postgres flexible-server list -g "$rg"
grab envs.json az containerapp env list -g "$rg"
grab apps.json az containerapp list -g "$rg"
grab jobs.json az containerapp job list -g "$rg"
grab storage.json az storage account list -g "$rg"

# Decide. Prints the inventory and writes the ordered delete plan (one resource id per line) to plan.txt.
python3 - "$snap" "$rg" "$env_name" <<'PY' | tee "$snap/inventory.txt"
import json, os, sys
snap, rg, env_name = sys.argv[1:]
load = lambda f: json.load(open(os.path.join(snap, f)))
params = json.loads(load("params.json")["parametersJson"])["parameters"]
want = lambda k: params[k]["value"]
tier, storage_type, geo = want("postgresSkuTier"), want("postgresStorageType"), want("postgresGeoRedundantBackup")
private, front_door, storage_sku = want("privateNetworking"), want("deployFrontDoor"), want("storageSku")
log_alerts = want("enableLogAlerts")
print(f"profile {env_name}: postgres {tier}/{storage_type} geo={geo}, privateNetworking={private}, "
      f"frontDoor={front_door}, storage {storage_sku}, logAlerts={log_alerts}")

resources = load("resources.json")["value"]
lid = lambda s: (s or "").lower()
plan = {}  # id -> (order, reason)

def doom(rid, order, reason):
    if lid(rid) not in {lid(k) for k in plan}:
        plan[rid] = (order, reason)

# PostgreSQL: creation-time settings (a missing storage type is SSD v1; nulls count as Disabled).
for s in load("postgres.json"):
    t = (s.get("sku") or {}).get("tier")
    st = (s.get("storage") or {}).get("type") or "Premium_LRS"
    g = (s.get("backup") or {}).get("geoRedundantBackup") or "Disabled"
    pna = (s.get("network") or {}).get("publicNetworkAccess") or "Enabled"
    want_geo = "Enabled" if geo else "Disabled"
    want_pna = "Disabled" if private else "Enabled"
    if not t:
        sys.exit(f"cannot read the tier of {s.get('id')}; nothing deleted")
    if (t, st, g, pna) != (tier, storage_type, want_geo, want_pna):
        doom(s["id"], 4, f"postgres {t}/{st} geo={g} public={pna}; profile wants {tier}/{storage_type} geo={want_geo} public={want_pna}")

# Container Apps environment: VNet or not is fixed at creation; its apps and jobs go first.
for e in load("envs.json"):
    has_vnet = bool(((e.get("properties") or {}).get("vnetConfiguration") or {}).get("infrastructureSubnetId"))
    if has_vnet != private:
        for a in load("apps.json"):
            p = a.get("properties") or {}
            if lid(p.get("managedEnvironmentId") or p.get("environmentId")) == lid(e["id"]):
                doom(a["id"], 1, "app on an environment that is being replaced")
        for j in load("jobs.json"):
            if lid((j.get("properties") or {}).get("environmentId")) == lid(e["id"]):
                doom(j["id"], 1, "job on an environment that is being replaced")
        doom(e["id"], 2, f"container apps environment vnet={has_vnet}; profile wants {private}")

by_type = lambda t: [r for r in resources if lid(r.get("type")) == lid(t)]
if not front_door:
    for r in by_type("Microsoft.Cdn/profiles"):
        doom(r["id"], 3, "Front Door; the profile has none")
    for r in by_type("Microsoft.Network/FrontDoorWebApplicationFirewallPolicies"):
        doom(r["id"], 5, "WAF policy; the profile has no Front Door")

zonal = lambda sku: "ZRS" in (sku or "")
for a in load("storage.json"):
    sku = (a.get("sku") or {}).get("name")
    if sku != storage_sku and zonal(sku) != zonal(storage_sku):
        for t in by_type("Microsoft.EventGrid/systemTopics"):
            doom(t["id"], 5, "Event Grid topic of a storage account being replaced")
        doom(a["id"], 6, f"storage {sku} cannot become {storage_sku} in place")

if not private:
    for r in by_type("Microsoft.Network/privateDnsZones"):
        doom(r["id"], 7, "private DNS zone; the profile has no VNet (its VNet links are removed first)")
    for r in by_type("Microsoft.Network/virtualNetworks"):
        doom(r["id"], 8, "VNet; the profile has none")
    for r in by_type("Microsoft.Network/networkSecurityGroups"):
        doom(r["id"], 9, "NSG of the VNet")
if not log_alerts:
    for r in by_type("Microsoft.Insights/scheduledQueryRules"):
        doom(r["id"], 1, "log-search alert rule; the profile turns them off (billed per rule)")

doomed = {lid(k) for k in plan}
print(f"\ninventory of {rg} (only this group is read or changed): action, type, created UTC, name")
for r in sorted(resources, key=lambda r: r.get("createdTime") or ""):
    action = "DELETE" if lid(r["id"]) in doomed else "KEEP"
    print(f"{action:7} {r['type']:58} {(r.get('createdTime') or '')[:19]:20} {r['name']}")
for rid, (order, reason) in sorted(plan.items(), key=lambda kv: kv[1][0]):
    print(f"   will delete: {rid.rsplit('/', 1)[-1]} ({reason})")
with open(os.path.join(snap, "plan.txt"), "w") as f:
    for rid, _ in sorted(plan.items(), key=lambda kv: kv[1][0]):
        f.write(rid + "\n")
PY
[ -f "$snap/plan.txt" ] || die "could not build the plan; nothing was deleted"
if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  { echo "### Inventory of $rg (profile $env_name)"; echo '```'; cat "$snap/inventory.txt"; echo '```'; } >> "$GITHUB_STEP_SUMMARY"
fi

mapfile -t doomed < "$snap/plan.txt"
if [ "${#doomed[@]}" -eq 0 ]; then note "nothing to reset: $rg matches the $env_name profile"; exit 0; fi
if [ -n "${CHECK_ONLY:-}" ]; then
  die "$rg holds ${#doomed[@]} resource(s) the $env_name profile cannot adopt (listed above; creation-time settings differ). Nothing was changed. Run the 'reset' workflow (GitHub > Actions > reset, type the group name) or infra/scripts/reset-to-profile.sh, then deploy again."
fi
if [ "${CONFIRM:-}" != "$rg" ]; then
  note "DRY RUN: ${#doomed[@]} resource(s) marked DELETE would be deleted. Re-run with CONFIRM=$rg to delete them."
  exit 0
fi

note "deleting ${#doomed[@]} resource(s) from $rg, in dependency order"
for id in "${doomed[@]}"; do
  case "${id,,}" in
    */providers/microsoft.network/privatednszones/*)
      # A zone cannot be deleted while it has VNet links.
      zone="${id##*/}"
      links="$(az network private-dns link vnet list -g "$rg" -z "$zone" --query "[].name" -o tsv)" || die "cannot list the links of $zone"
      for l in $links; do az network private-dns link vnet delete -g "$rg" -z "$zone" -n "$l" --yes -o none || true; done ;;
  esac
  for attempt in 1 2 3 4 5 6; do
    echo "   deleting $id (attempt $attempt)"
    if az resource delete --ids "$id" -o none 2>"$snap/err"; then break; fi
    # Subnet links (Container Apps, PostgreSQL delegation) are often released minutes after their owner is gone.
    cat "$snap/err"; [ "$attempt" -lt 6 ] && sleep 60
  done
done

left=0
for id in "${doomed[@]}"; do
  if az resource show --ids "$id" -o none 2>/dev/null; then echo "::error::still present: $id"; left=$((left + 1)); fi
done
[ "$left" -eq 0 ] || die "$left resource(s) could not be deleted; run the reset again in a few minutes"
note "reset done; run the deploy again"
