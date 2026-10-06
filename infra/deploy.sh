#!/usr/bin/env bash
# ONE command that deploys the whole Aron stack into one resource group (N-012 acceptance). The GitHub deploy
# workflow runs exactly this after the OIDC sign-in and the scope check; a person can run it after `az login`.
#
#   AZURE_RESOURCE_GROUP=rg-aron-dev ARON_ALERT_EMAILS=ops@example.com infra/deploy.sh dev
#
# Order (infra/README.md explains why):
#   lock -> ordering guard -> infra (main.bicep; skipped when infra is unchanged since the deployed commit) ->
#   Key Vault seeding -> backend image (+ web image when web/ exists) -> migrate job with the new image -> wait for
#   the migrations -> apps (+ Front Door routes when the profile has Front Door) -> Private Link approval (Premium)
#   -> smoke test on the public API address -> budget check.
#
# Environment: AZURE_RESOURCE_GROUP (required), ARON_ALERT_EMAILS (required, comma-separated), AZURE_LOCATION,
# ARON_BUDGET_AMOUNT, ARON_NAME_SUFFIX, FCM_SERVICE_ACCOUNT_JSON, MAPS_WEB_KEY (all optional),
# RUN_MIGRATIONS (true | false; default true), FORCE_INFRA (true = always run main.bicep).
# shellcheck source=scripts/lib.sh
source "$(dirname "$0")/scripts/lib.sh"
cd "$(dirname "$0")/.." || exit 1

# PROFILE picks the parameter files (infra/params/<profile>*.bicepparam); ENV_NAME is the environment in resource
# names. dev-lite is the cheap TEST profile of the same dev environment (docs/28), so both deploy the dev names.
PROFILE="${1:-dev}"
case "$PROFILE" in dev|dev-lite|prod) ;; *) die "profile must be dev, dev-lite or prod, was $PROFILE" ;; esac
ENV_NAME="${PROFILE%-lite}"
need AZURE_RESOURCE_GROUP "the resource group to deploy into"
need ARON_ALERT_EMAILS "who receives the budget and platform alerts"
RG="$AZURE_RESOURCE_GROUP"
SHA="${GIT_SHA:-$(git rev-parse HEAD)}"
RUN_MIGRATIONS="${RUN_MIGRATIONS:-true}"
export AZURE_LOCATION="${AZURE_LOCATION:-$(az group show -n "$RG" --query location -o tsv)}"
export ARON_ALERT_EMAILS ARON_BUDGET_AMOUNT="${ARON_BUDGET_AMOUNT:-}" ARON_NAME_SUFFIX="${ARON_NAME_SUFFIX:-}"
whatif_file="$(mktemp)"; trap 'rm -f "$whatif_file"' EXIT
summary() { [ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "$*" >> "$GITHUB_STEP_SUMMARY"; echo "$*"; }

# ---------------------------------------------------------------------------------------------------------- lock
# One deploy at a time per group. A GitHub concurrency group would CANCEL pending CI runs; waiting here never does.
note "waiting until no other deployment runs in $RG"
for i in $(seq 1 90); do
  running="$(az deployment group list -g "$RG" --query "[?starts_with(name, 'aron-') && properties.provisioningState=='Running'] | length(@)" -o tsv)"
  [ "${running:-0}" -eq 0 ] && break
  [ "$i" -eq 90 ] && die "another Aron deployment is still running in $RG after 45 minutes"
  sleep 30
done

# ---------------------------------------------------------------------------------------------- ordering guard
# CI runs of several pushes finish out of order. Never replace a deployed commit by one of its ancestors.
api_name="ca-aron-${ENV_NAME}-api"
deployed_image="$(az containerapp show -g "$RG" -n "$api_name" --query 'properties.template.containers[0].image' -o tsv 2>/dev/null || true)"
deployed_sha="${deployed_image##*:}"
if [[ "$deployed_sha" =~ ^[0-9a-f]{40}$ ]] && [ "$deployed_sha" != "$SHA" ]; then
  git cat-file -e "${deployed_sha}^{commit}" 2>/dev/null || git fetch -q origin "$deployed_sha" 2>/dev/null || true
  if git merge-base --is-ancestor "$SHA" "$deployed_sha" 2>/dev/null; then
    summary "Skipped: $SHA is an ancestor of the deployed commit $deployed_sha (a newer commit is already live)."
    exit 0
  fi
fi
note "deploying $SHA (currently deployed: ${deployed_sha:-nothing})"

# Budgets: Azure refuses every budget (even a what-if of one) while the billing account's cost policy is off for
# subscription users. Only that exact refusal skips the budget, with a loud warning; any other failure stops here.
sub_id="$(az account show --query id -o tsv)" || die "not signed in to Azure"
if budget_err="$(az rest --method get -o none \
     --url "https://management.azure.com/subscriptions/${sub_id}/resourceGroups/${RG}/providers/Microsoft.Consumption/budgets?api-version=2024-08-01" 2>&1)"; then
  export ARON_DEPLOY_BUDGET=true
elif grep -qi "cost policy is turned off" <<<"$budget_err"; then
  export ARON_DEPLOY_BUDGET=false
  echo "::warning::No budget: Azure says the cost policy is turned off for this subscription's users, so no budget can exist. The billing account admin must allow subscription users to view charges (Cost Management + Billing > Policies, or the partner's 'Azure usage' customer policy); the next deploy then creates the budget."
else
  echo "$budget_err" >&2; die "cannot read budgets in $RG"
fi

# ------------------------------------------------------------------------------------------------------- infra
infra_paths=(infra/main.bicep infra/modules infra/lib "infra/params/${PROFILE}.bicepparam")
previous="$(az deployment group show -g "$RG" -n aron-infra --query properties.outputs -o json 2>/dev/null || true)"
# The parameters main.bicep would get now (GitHub variables included), compared with the last successful run, so a
# changed ARON_ALERT_EMAILS / ARON_BUDGET_AMOUNT / ARON_NAME_SUFFIX / AZURE_LOCATION also re-runs the infra stage.
params_unchanged() {
  local now last
  now="$(ARON_DB_ADMIN_PASSWORD=compare-only-not-a-secret-0 ARON_BUDGET_START_DATE=2000-01-01 \
    az bicep build-params --file "infra/params/${PROFILE}.bicepparam" --stdout 2>/dev/null)" || return 1
  last="$(az deployment group show -g "$RG" -n aron-infra --query properties.parameters -o json 2>/dev/null)" || return 1
  python3 - "$now" "$last" <<'PY'
import json, sys
now = json.loads(json.loads(sys.argv[1])["parametersJson"])["parameters"]
last = json.loads(sys.argv[2])
ignore = {"postgresAdminPassword", "budgetStartDate", "deployerObjectId"}
diff = [k for k, v in now.items() if k not in ignore and last.get(k, {}).get("value") != v.get("value")]
if diff:
    print("infra parameters changed: " + ", ".join(sorted(diff)), file=sys.stderr)
sys.exit(1 if diff else 0)
PY
}
skip_infra=false
if [ "${FORCE_INFRA:-false}" != "true" ] && [ -n "$previous" ] && [[ "$deployed_sha" =~ ^[0-9a-f]{40}$ ]] \
   && git diff --quiet "$deployed_sha" "$SHA" -- "${infra_paths[@]}" 2>/dev/null \
   && [ "$(az deployment group show -g "$RG" -n aron-infra --query properties.provisioningState -o tsv)" = "Succeeded" ] \
   && params_unchanged; then
  skip_infra=true
fi

out() { python3 -c 'import json,sys; o=json.loads(sys.argv[1]); v=o[sys.argv[2]]["value"]; print(str(v).lower() if isinstance(v,bool) else v)' "$1" "$2"; }

# A resource whose creation-time settings differ from the profile (docs/28) would make main.bicep fail half-way;
# stop before changing anything and point at the reset workflow instead.
if [ "$skip_infra" != true ]; then
  CHECK_ONLY=1 infra/scripts/reset-to-profile.sh "$RG" "$PROFILE"
fi

if [ "$skip_infra" = true ]; then
  note "infrastructure unchanged since $deployed_sha: main.bicep skipped (FORCE_INFRA=true runs it)"
  outputs="$previous"
else
  kv="$(az keyvault list -g "$RG" --query "[?starts_with(name, 'kv-aron-${ENV_NAME}-')].name | [0]" -o tsv)"
  ARON_DB_ADMIN_PASSWORD="$(infra/scripts/db-password.sh "$RG" "$kv")"
  [ -n "${GITHUB_ACTIONS:-}" ] && echo "::add-mask::${ARON_DB_ADMIN_PASSWORD}"
  export ARON_DB_ADMIN_PASSWORD
  budget_name="budget-aron-${ENV_NAME}"
  start=""
  [ "$ARON_DEPLOY_BUDGET" = true ] && start="$(az consumption budget show -g "$RG" --budget-name "$budget_name" --query timePeriod.startDate -o tsv 2>/dev/null || true)"
  export ARON_BUDGET_START_DATE="${start:0:10}"
  [ -n "$ARON_BUDGET_START_DATE" ] || ARON_BUDGET_START_DATE="$(date -u +%Y-%m-01)"
  # What-if first: the infra stage must never recreate or reconfigure a PostgreSQL server (the rehearsal profile
  # adopts the existing one; tier, storage, HA, backup and network are fixed at creation or must not change by accident).
  note "what-if of main.bicep"
  az deployment group what-if -g "$RG" --template-file infra/main.bicep \
    --parameters "infra/params/${PROFILE}.bicepparam" --no-pretty-print -o json > "$whatif_file" \
    || die "what-if of main.bicep failed (nothing was changed)"
  EXISTING_POSTGRES_IDS="$(az postgres flexible-server list -g "$RG" --query "[].id" -o tsv | tr '\n' ' ')" \
    infra/scripts/whatif-guard.py "$whatif_file" || die "what-if shows a change the guard refuses (nothing was changed)"
  note "main.bicep (budget start $ARON_BUDGET_START_DATE)"
  outputs="$(az deployment group create -g "$RG" -n aron-infra --template-file infra/main.bicep \
    --parameters "infra/params/${PROFILE}.bicepparam" --query properties.outputs -o json)"
  unset ARON_DB_ADMIN_PASSWORD
fi
REGISTRY="$(out "$outputs" registryLoginServer)"
REGISTRY_NAME="$(out "$outputs" registryName)"
KV="$(out "$outputs" keyVaultName)"
CAE="$(out "$outputs" containerEnvName)"
JOB="$(out "$outputs" migrateJobName)"
PRIVATE_LINK="$(out "$outputs" privateLinkOrigin)"
BUDGET="$(out "$outputs" budgetName)"

# ------------------------------------------------------------------------------------------------- secrets
infra/scripts/seed-secrets.sh "$KV"

# -------------------------------------------------------------------------------------------------- images
./gradlew --console=plain -q :backend:app:installDist
BACKEND_IMAGE="${REGISTRY}/aron-backend:${SHA}"
docker build --pull -q -f infra/docker/backend.Dockerfile -t "$BACKEND_IMAGE" \
  --label "org.opencontainers.image.revision=${SHA}" backend/app/build/install/aron-backend
az acr login --name "$REGISTRY_NAME"
docker push -q "$BACKEND_IMAGE"
WEB_IMAGE=""
if [ -f web/package.json ]; then
  WEB_IMAGE="${REGISTRY}/aron-web:${SHA}"
  docker build --pull -q -f infra/docker/web.Dockerfile -t "$WEB_IMAGE" \
    --build-arg "NEXT_PUBLIC_MAPS_WEB_KEY=${MAPS_WEB_KEY:-}" --label "org.opencontainers.image.revision=${SHA}" web
  docker push -q "$WEB_IMAGE"
else
  note "web/ has no package.json: no web app"
fi
export ARON_BACKEND_IMAGE="$BACKEND_IMAGE" ARON_WEB_IMAGE="$WEB_IMAGE"

# ------------------------------------------------------------------------------------------------ migrations
note "migrate job with $BACKEND_IMAGE"
ARON_DEPLOY_SERVICES=false az deployment group create -g "$RG" -n aron-apps-migrate --template-file infra/apps.bicep \
  --parameters "infra/params/${PROFILE}.apps.bicepparam" -o none
if [ "$RUN_MIGRATIONS" = true ]; then
  execution="$(az containerapp job start -g "$RG" -n "$JOB" --query name -o tsv)"
  note "migrations started: $execution"
  status=""
  for _ in $(seq 1 120); do
    status="$(az containerapp job execution show -g "$RG" -n "$JOB" --job-execution-name "$execution" --query properties.status -o tsv)"
    case "$status" in
      Succeeded) note "migrations succeeded"; break ;;
      Failed|Stopped|Degraded)
        die "migrations $execution ended $status; the apps were NOT updated. Logs: Log Analytics, ContainerAppConsoleLogs, ContainerJobName_s == '$JOB'" ;;
    esac
    sleep 10
  done
  [ "$status" = Succeeded ] || die "migrations did not finish in 20 minutes ($execution)"
else
  note "migrations not run (RUN_MIGRATIONS=$RUN_MIGRATIONS)"
fi

# ------------------------------------------------------------------------------------------------------ apps
note "apps (and Front Door routes when the profile has Front Door)"
apps="$(ARON_DEPLOY_SERVICES=true az deployment group create -g "$RG" -n aron-apps --template-file infra/apps.bicep \
  --parameters "infra/params/${PROFILE}.apps.bicepparam" --query properties.outputs -o json)"
API_HOST="$(out "$apps" apiHost)"
WEB_DEPLOYED="$(out "$apps" webDeployed)"

if [ "$PRIVATE_LINK" = true ]; then
  expected=1; [ "$WEB_DEPLOYED" = true ] && expected=2
  infra/scripts/approve-private-link.sh "$RG" "$CAE" "$expected"
fi

# ------------------------------------------------------------------------------------------------ checks
infra/scripts/smoke.sh "$API_HOST"
if [ "$ARON_DEPLOY_BUDGET" = true ]; then
  amount="$(az consumption budget show -g "$RG" --budget-name "$BUDGET" --query amount -o tsv)" \
    || die "budget $BUDGET not found in $RG"
  budget_line="${BUDGET}: ${amount} a month"
else
  budget_line="NOT SET: the subscription's cost policy is off (see the warning above)"
fi

summary "### Aron ${ENV_NAME} deployed (profile ${PROFILE})"
summary "| Item | Value |"
summary "|---|---|"
summary "| Resource group | ${RG} |"
summary "| Commit | ${SHA} |"
summary "| API | https://${API_HOST}/v1/health |"
summary "| Backend image | ${BACKEND_IMAGE} |"
summary "| Web image | ${WEB_IMAGE:-none (no web/ yet)} |"
summary "| Infrastructure | $([ "$skip_infra" = true ] && echo "unchanged, skipped" || echo deployed) |"
summary "| Migrations | ${RUN_MIGRATIONS} |"
summary "| Budget | ${budget_line} |"
