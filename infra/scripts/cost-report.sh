#!/usr/bin/env bash
# Read-only cost reading of ONE resource group (owner's review, docs/28): month-to-date cost per day and per resource
# from Azure's own cost data, without the budget API. Tries, in order:
#   1. Cost Management query  (Microsoft.CostManagement/query, actual cost, daily, grouped by resource)
#   2. Consumption usage details (Microsoft.Consumption/usageDetails, the older API)
# Both are refused while the billing account's cost policy is off for subscription users (the same policy that
# blocks budgets); the script then says so plainly and changes nothing. Writes the result to the run summary.
# Usage: infra/scripts/cost-report.sh <resource-group>
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
rg="${1:?resource group}"
sub="$(az account show --query id -o tsv)" || die "not signed in to Azure"
scope="/subscriptions/${sub}/resourceGroups/${rg}"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
summary() { echo "$*"; [ -z "${GITHUB_STEP_SUMMARY:-}" ] || echo "$*" >> "$GITHUB_STEP_SUMMARY"; }

body='{"type":"ActualCost","timeframe":"MonthToDate","dataset":{"granularity":"Daily",
  "aggregation":{"cost":{"name":"Cost","function":"Sum"}},
  "grouping":[{"type":"Dimension","name":"ResourceId"}]}}'
summary "### Cost reading of ${rg} ($(date -u +%Y-%m-%dT%H:%MZ))"
if az rest --method post --url "https://management.azure.com${scope}/providers/Microsoft.CostManagement/query?api-version=2023-11-01" \
     --body "$body" -o json > "$tmp/q.json" 2> "$tmp/q.err"; then
  python3 - "$tmp/q.json" <<'PY' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
import collections, json, sys
d = json.load(open(sys.argv[1]))["properties"]
cols = [c["name"] for c in d["columns"]]
rows = [dict(zip(cols, r)) for r in d["rows"]]
cur = rows[0].get("Currency", "") if rows else ""
by_day, by_res = collections.defaultdict(float), collections.defaultdict(float)
for r in rows:
    by_day[str(r["UsageDate"])] += r["Cost"]
    by_res[r["ResourceId"].rsplit("/", 1)[-1]] += r["Cost"]
print("Source: Cost Management query (actual cost, month to date)\n")
print("| Day | Cost |\n|---|---|")
for k in sorted(by_day):
    print(f"| {k[:4]}-{k[4:6]}-{k[6:]} | {by_day[k]:.2f} {cur} |")
days = len(by_day) or 1
print(f"\nTotal {sum(by_day.values()):.2f} {cur} over {days} day(s); average {sum(by_day.values()) / days:.2f} {cur} per day "
      "(the latest day is usually incomplete: Azure posts usage with a delay of up to a day)\n")
print("| Resource | Month to date |\n|---|---|")
for k, v in sorted(by_res.items(), key=lambda kv: -kv[1]):
    print(f"| {k} | {v:.2f} {cur} |")
PY
  exit 0
fi
summary "- Cost Management query refused: $(tr '\n' ' ' < "$tmp/q.err" | cut -c1-300)"

start="$(date -u +%Y-%m-01)"; end="$(date -u +%Y-%m-%d)"
if az rest --method get -o json > "$tmp/u.json" 2> "$tmp/u.err" \
     --url "https://management.azure.com${scope}/providers/Microsoft.Consumption/usageDetails?api-version=2023-05-01&\$filter=properties/usageStart ge '${start}' and properties/usageEnd le '${end}'"; then
  python3 - "$tmp/u.json" <<'PY' | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
import collections, json, sys
items = json.load(open(sys.argv[1])).get("value", [])
by_day = collections.defaultdict(float)
for i in items:
    p = i.get("properties", {})
    by_day[(p.get("date") or p.get("usageStart") or "")[:10]] += float(p.get("costInBillingCurrency") or p.get("cost") or 0)
print("Source: Consumption usage details (first page, month to date)\n\n| Day | Cost |\n|---|---|")
for k in sorted(by_day):
    print(f"| {k} | {by_day[k]:.2f} |")
days = len(by_day) or 1
print(f"\nAverage {sum(by_day.values()) / days:.2f} per day over {days} day(s)")
PY
  exit 0
fi
summary "- Consumption usage details refused: $(tr '\n' ' ' < "$tmp/u.err" | cut -c1-300)"
summary ""
summary "**No Azure cost data is readable by the deploy identity.** Turn on the billing account's cost policy for"
summary "subscription users (the same switch the budget needs), or read the cost in the portal as the owner"
summary "(Cost Management > Cost analysis, scope ${rg}). Until then the per-day figure is the estimate in docs/status/infra.md."
exit 0
