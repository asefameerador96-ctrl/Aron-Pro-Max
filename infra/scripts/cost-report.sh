#!/usr/bin/env bash
# Read-only cost reading of ONE resource group (owner's review, docs/28): month-to-date cost per day and per resource
# from Azure's own cost data, without the budget API. Tries, in order:
#   1. Cost Management query  (Microsoft.CostManagement/query, actual cost, daily, grouped by resource)
#   2. Consumption usage details (Microsoft.Consumption/usageDetails, the older API), read at SUBSCRIPTION scope with a
#      resource-group filter: the resource-group scope answers 404 for this API (run 42191608792, D-OPS-COST-SCOPE-01)
# Both are refused while the billing account's cost policy is off for subscription users (the same policy that
# blocks budgets); the script then says so plainly and changes nothing. Writes the result to the run summary.
# When no figure could be read at all the script emits a ::warning:: annotation, so the run list shows it (the run
# itself stays green: a refused cost API is a state of the account, not a failure of the repository).
# Usage: infra/scripts/cost-report.sh <resource-group>
# Sourcing it only defines the functions (tests); running it does the reading.
# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

# Consumption usageDetails URL at subscription scope, filtered to one resource group and the month to date.
usage_details_url() { # subscription resource-group start end
  printf 'https://management.azure.com/subscriptions/%s/providers/Microsoft.Consumption/usageDetails?api-version=2023-05-01&$filter=properties/usageStart ge \x27%s\x27 and properties/usageEnd le \x27%s\x27 and properties/resourceGroup eq \x27%s\x27\n' "$1" "$3" "$4" "$2"
}
# True when an az error text says the call was throttled.
is_throttled() { grep -q "429\|Too Many Requests" "$1"; }
# The annotation that makes a run without any cost figure visible in the run list.
no_figure_warning() { # resource-group reason
  echo "::warning::cost-report of $1: no cost figure was read ($2)"
}

main() {
rg="${1:?resource group}"
sub="$(az account show --query id -o tsv)" || die "not signed in to Azure"
scope="/subscriptions/${sub}/resourceGroups/${rg}"  # Cost Management query accepts the resource-group scope
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
summary() { echo "$*"; [ -z "${GITHUB_STEP_SUMMARY:-}" ] || echo "$*" >> "$GITHUB_STEP_SUMMARY"; }

body='{"type":"ActualCost","timeframe":"MonthToDate","dataset":{"granularity":"Daily",
  "aggregation":{"cost":{"name":"Cost","function":"Sum"}},
  "grouping":[{"type":"Dimension","name":"ResourceId"}]}}'
summary "### Cost reading of ${rg} ($(date -u +%Y-%m-%dT%H:%MZ))"
# Cost Management throttles hard (429): retry with back-off before calling it refused.
query_ok=false
for wait in 0 30 60 120 180; do
  sleep "$wait"
  if az rest --method post --url "https://management.azure.com${scope}/providers/Microsoft.CostManagement/query?api-version=2023-11-01" \
       --body "$body" -o json > "$tmp/q.json" 2> "$tmp/q.err"; then query_ok=true; break; fi
  is_throttled "$tmp/q.err" || break
  echo "Cost Management throttled (429); retrying"
done
if [ "$query_ok" = true ]; then
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
summary "- Cost Management query failed: $(tr '\n' ' ' < "$tmp/q.err" | cut -c1-300)"

start="$(date -u +%Y-%m-01)"; end="$(date -u +%Y-%m-%d)"
if az rest --method get -o json > "$tmp/u.json" 2> "$tmp/u.err" --url "$(usage_details_url "$sub" "$rg" "$start" "$end")"; then
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
summary "- Consumption usage details failed: $(tr '\n' ' ' < "$tmp/u.err" | cut -c1-300)"
summary ""
if is_throttled "$tmp/q.err"; then
  summary "**Cost Management kept throttling (429) for about 6 minutes; run cost-report again later.**"
  no_figure_warning "$rg" "Cost Management throttled (429) for about 6 minutes"
  exit 0
fi
summary "**No Azure cost data is readable by the deploy identity.** Turn on the billing account's cost policy for"
summary "subscription users (the same switch the budget needs), or read the cost in the portal as the owner"
summary "(Cost Management > Cost analysis, scope ${rg}). Until then the per-day figure is the estimate in docs/status/infra.md."
no_figure_warning "$rg" "neither the Cost Management query nor the Consumption usage details is readable by the deploy identity"
exit 0
}
if [ "${BASH_SOURCE[0]}" = "$0" ]; then main "$@"; fi
