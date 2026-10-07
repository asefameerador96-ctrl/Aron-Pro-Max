#!/usr/bin/env bash
# ONE command that deploys the whole Aron stack into one resource group (N-012 acceptance). The GitHub deploy
# workflow runs exactly this after the OIDC sign-in and the scope check; a person can run it after `az login`.
#
#   AZURE_RESOURCE_GROUP=rg-aron-dev ARON_ALERT_EMAILS=ops@example.com infra/deploy.sh dev
#
# Order (infra/README.md explains why):
#   lock -> ordering guard -> infra (main.bicep; skipped when infra is unchanged since the deployed commit) ->
#   Key Vault seeding -> backend image (+ web image when web/ exists), pushed once and deployed BY DIGEST ->
#   ordering guard again -> PITR restore point recorded -> migrate job with the new image -> wait for the migrations
#   -> ordering guard again -> apps (+ Front Door routes when the profile has Front Door) -> Private Link approval
#   (Premium) -> health gate (build == this commit, readiness, web login page; in Multiple revision mode a failed
#   gate puts the api traffic back on the previous revision) -> budget check.
#
# Rollback (docs/runbooks/rollback-bad-deploy.md): ROLLBACK_SHA=<earlier commit of the integration branch> redeploys
# the image already in the registry for that commit, by digest: no build, no migrations, no infra stage, and the
# ordering guard is bypassed on purpose.
#
# Environment: AZURE_RESOURCE_GROUP (required), ARON_ALERT_EMAILS (required, comma-separated), AZURE_LOCATION,
# ARON_BUDGET_AMOUNT, ARON_NAME_SUFFIX, FCM_SERVICE_ACCOUNT_JSON, MAPS_WEB_KEY (all optional),
# RUN_MIGRATIONS (true | false; default true), FORCE_INFRA (true = always run main.bicep), ROLLBACK_SHA (see above),
# ARON_DEPLOY_FREEZE_DHAKA ("HH:MM-HH:MM" Asia/Dhaka; refuses a deploy inside that window, rollbacks excepted; unset
# until real users exist).
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
ROLLBACK_SHA="${ROLLBACK_SHA:-}"
if [ -n "$ROLLBACK_SHA" ]; then
  # Only a commit already on the branch being deployed from (so it passed CI when it was pushed); never a stray one.
  [[ "$ROLLBACK_SHA" =~ ^[0-9a-f]{40}$ ]] || die "ROLLBACK_SHA must be a full 40-character commit id, was '$ROLLBACK_SHA'"
  git merge-base --is-ancestor "$ROLLBACK_SHA" "$SHA" 2>/dev/null \
    || die "ROLLBACK_SHA $ROLLBACK_SHA is not an earlier commit of the deployed branch ($SHA)"
  SHA="$ROLLBACK_SHA"
  # The schema is forward-only (expand/contract), so the older image runs on the newer schema; never migrate back.
  RUN_MIGRATIONS=false
fi
# Dhaka selling window (docs/18): once real users exist, no deploy while reps sell; a rollback is always allowed.
# Checked at the start and again right before the migrations and the apps (the lock wait and the build take time).
check_freeze() { # stage
  if [ -z "${ARON_DEPLOY_FREEZE_DHAKA:-}" ] || [ -n "$ROLLBACK_SHA" ]; then return 0; fi
  if in_freeze_window "$ARON_DEPLOY_FREEZE_DHAKA"; then
    die "deploys are frozen ${ARON_DEPLOY_FREEZE_DHAKA} Asia/Dhaka (ARON_DEPLOY_FREEZE_DHAKA), stopped $1; run it again after the window"
  fi
}
check_freeze "at the start"
export AZURE_LOCATION="${AZURE_LOCATION:-$(az group show -n "$RG" --query location -o tsv)}"
export ARON_ALERT_EMAILS ARON_BUDGET_AMOUNT="${ARON_BUDGET_AMOUNT:-}" ARON_NAME_SUFFIX="${ARON_NAME_SUFFIX:-}"
whatif_file="$(mktemp)"; migrate_log="$(mktemp)"
lock_tag="aron-deploy-lock"; lock_held=false; hb_pid=""
lock_me="${GITHUB_RUN_ID:-local}.${GITHUB_RUN_ATTEMPT:-1}.$$"
lock_ttl=900   # seconds without a heartbeat after which a lock is stale (the holder refreshes it every 60 s)
# Prints the lock value ("" = no lock) and returns 0; returns 1 when the tag could NOT be read (a failed read must
# never look like a free lock).
read_lock() {
  local v
  v="$(az group show -n "$RG" --query "tags.\"$lock_tag\"" -o tsv 2>/dev/null)" || return 1
  [ "$v" = None ] && v=""
  printf '%s' "$v"
}
lock_owner() { printf '%s' "${1%% *}"; }
lock_age() { # seconds since the value's epoch; a malformed value counts as stale
  local ts="${1##* }"
  [[ "$ts" =~ ^[0-9]{9,11}$ ]] || { echo 999999; return; }
  echo $(( $(date +%s) - ts ))
}
release_lock() {
  [ -n "$hb_pid" ] && kill "$hb_pid" 2>/dev/null; hb_pid=""
  [ "$lock_held" = true ] || return 0
  lock_held=false
  local cur; cur="$(read_lock)" || { echo "::warning::could not read the deploy lock to release it; it goes stale ${lock_ttl} s after the last heartbeat"; return 0; }
  if [ "$(lock_owner "$cur")" = "$lock_me" ]; then
    az tag update --resource-id "$rg_id" --operation Delete --tags "$lock_tag=$cur" -o none 2>/dev/null \
      && echo "== deploy lock released" || echo "::warning::could not release the deploy lock; it goes stale ${lock_ttl} s after the last heartbeat"
  fi
}
trap 'rm -f "$whatif_file" "$migrate_log"; release_lock' EXIT
# A cancelled or timed-out job gets SIGINT/SIGTERM before SIGKILL: exit at once so the EXIT trap releases the lock.
trap 'exit 130' INT TERM
summary() { [ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "$*" >> "$GITHUB_STEP_SUMMARY"; echo "$*"; }

# ---------------------------------------------------------------------------------------------------------- lock
# One deploy at a time per group, held for the WHOLE deploy (infra, images, migrations, apps), because a deploy has
# quiet minutes (image builds) in which no ARM deployment runs. A GitHub concurrency group would CANCEL pending CI
# runs; waiting here never does. The lock is a tag on the resource group, "aron-deploy-lock = <owner> <epoch>":
# write it, wait 20 s for any concurrent writer, re-read; only the owner whose value survived proceeds (ARM applies
# tag writes in order, the last writer wins). The holder refreshes the epoch every 60 s; a lock whose epoch is older
# than 15 minutes (a SIGKILLed holder) is stale. A failed read counts as held.
rg_id="$(az group show -n "$RG" --query id -o tsv)" || die "cannot read resource group $RG"
note "taking the deploy lock on $RG (owner $lock_me)"
lock_deadline=$(( $(date +%s) + 80 * 60 ))
waits=0
while :; do
  [ "$(date +%s)" -lt "$lock_deadline" ] || die "the deploy lock on $RG is still held after 80 minutes"
  if ! cur="$(read_lock)"; then
    note "cannot read the deploy lock; treating it as held"; sleep 30; continue
  fi
  if [ -n "$cur" ] && [ "$(lock_owner "$cur")" != "$lock_me" ] && [ "$(lock_age "$cur")" -lt "$lock_ttl" ]; then
    [ $(( waits % 4 )) -eq 0 ] && note "deploy lock held by $(lock_owner "$cur") (heartbeat $(lock_age "$cur") s ago); waiting"
    waits=$(( waits + 1 )); sleep 30; continue
  fi
  if ! tag_err="$(az tag update --resource-id "$rg_id" --operation Merge --tags "$lock_tag=$lock_me $(date +%s)" -o none 2>&1)"; then
    # A missing right never heals by waiting: stop at once instead of holding the queue for 80 minutes.
    grep -q "AuthorizationFailed" <<<"$tag_err" && die "the deploy identity may not write tags on $RG (needed for the deploy lock): $tag_err"
    note "cannot write the deploy lock tag; retrying"; sleep 30; continue
  fi
  lock_held=true  # from the write on, the exit trap may release it (only while the value is still ours)
  sleep 20
  if got="$(read_lock)" && [ "$(lock_owner "$got")" = "$lock_me" ]; then break; fi
  lock_held=false
  note "another deploy took the lock at the same moment; waiting"
done
# Heartbeat: refresh the epoch while this deploy runs; it stops itself when the lock is no longer ours.
(
  set +e
  while sleep 60; do
    v="$(read_lock)" || continue
    [ "$(lock_owner "$v")" = "$lock_me" ] || exit 0
    az tag update --resource-id "$rg_id" --operation Merge --tags "$lock_tag=$lock_me $(date +%s)" -o none 2>/dev/null
  done
) &
hb_pid=$!
note "deploy lock held"
# Also wait for ARM deployments that a deploy without this lock (an older deploy.sh, a person) may still be running.
for i in $(seq 1 90); do
  running="$(az deployment group list -g "$RG" --query "[?starts_with(name, 'aron-') && properties.provisioningState=='Running'] | length(@)" -o tsv)"
  [ "${running:-0}" -eq 0 ] && break
  [ "$i" -eq 90 ] && die "another Aron deployment is still running in $RG after 45 minutes"
  sleep 30
done

# ---------------------------------------------------------------------------------------------- ordering guard
# CI runs of several pushes finish out of order. Never replace a deployed commit by one of its ancestors.
api_name="ca-aron-${ENV_NAME}-api"
# The commit the live api runs: its ARON_BUILD (images are deployed by digest), or the tag of an older tag deploy.
live_sha() {
  local b img
  b="$(az containerapp show -g "$RG" -n "$api_name" --query "properties.template.containers[0].env[?name=='ARON_BUILD'].value | [0]" -o tsv 2>/dev/null || true)"
  if [[ ! "$b" =~ ^[0-9a-f]{40}$ ]]; then
    img="$(az containerapp show -g "$RG" -n "$api_name" --query 'properties.template.containers[0].image' -o tsv 2>/dev/null || true)"
    b="${img##*:}"
  fi
  printf '%s' "$b"
}
# Exits 0 ("Skipped") when a NEWER commit is live. Run again right before the migrate stage and the apps stage
# (AUD-DG-07): the lock covers every deploy.sh, but a person or an older script without the lock may have deployed
# while this one built its images.
guard_newer_live() { # stage
  [ -n "$ROLLBACK_SHA" ] && return 0
  local live; live="$(live_sha)"
  [[ "$live" =~ ^[0-9a-f]{40}$ ]] && [ "$live" != "$SHA" ] || return 0
  git cat-file -e "${live}^{commit}" 2>/dev/null || git fetch -q origin "$live" 2>/dev/null || true
  if git merge-base --is-ancestor "$SHA" "$live" 2>/dev/null; then
    summary "Skipped ($1): $SHA is an ancestor of the deployed commit $live (a newer commit is already live)."
    exit 0
  fi
}
deployed_sha="$(live_sha)"
guard_newer_live "start"
# Nothing that reaches Azure changed since the live commit (docs, Android, tests only): nothing to deploy.
deploy_paths=(shared db backend web infra build.gradle.kts settings.gradle.kts gradle.properties gradle .github/workflows/deploy.yml)
# The same commit again (a re-run, e.g. after a failed health gate) always deploys fully, so the gate runs again.
if [ -z "$ROLLBACK_SHA" ] && [[ "$deployed_sha" =~ ^[0-9a-f]{40}$ ]] && [ "$deployed_sha" != "$SHA" ] \
   && [ "${FORCE_INFRA:-false}" != true ] \
   && git merge-base --is-ancestor "$deployed_sha" "$SHA" 2>/dev/null \
   && git diff --quiet "$deployed_sha" "$SHA" -- "${deploy_paths[@]}" 2>/dev/null; then
  summary "Skipped: nothing deployable changed between the live commit $deployed_sha and $SHA."
  exit 0
fi
note "deploying $SHA (currently deployed: ${deployed_sha:-nothing})$([ -n "$ROLLBACK_SHA" ] && echo ' as a ROLLBACK')"

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
# PostgreSQL zones are chosen at creation; a forced failover (drill.sh, or Azure itself) swaps primary and standby.
# Pass the live zones of the existing primary server so main.bicep matches it and never tries to move it back (the
# what-if guard refuses that, run 37608044223). Set before params_unchanged, so a swap also re-runs the infra stage.
# >>> pg-live-zones (run in isolation by infra/tests/check_infra.py)
# Only the server main.bicep names (psql-aron-<env>-<suffix>, no further '-'): a PITR drill restore
# (<server>-drill-<time>) or the replica (<server>-r1, standalone once promoted) must never be read or block a deploy.
# A rollback skips the infra stage, so it skips this lookup too.
unset ARON_PG_PRIMARY_ZONE ARON_PG_STANDBY_ZONE
pg_live=""
if [ -z "$ROLLBACK_SHA" ]; then
  # "|| '-'" keeps every tsv field non-empty, so a null zone cannot shift the fields read below.
  pg_all="$(az postgres flexible-server list -g "$RG" \
    --query "[].[name, availabilityZone || '-', highAvailability.mode || '-', highAvailability.standbyAvailabilityZone || '-']" \
    -o tsv)" || die "cannot list the PostgreSQL servers of $RG"
  if [ -n "${ARON_NAME_SUFFIX:-}" ]; then
    # A given suffix is used as is (it may contain '-'): match that exact name.
    pg_live="$(printf '%s\n' "$pg_all" | tr -d '\r' | awk -F'\t' -v n="psql-aron-${ENV_NAME}-${ARON_NAME_SUFFIX}" '$1 == n')"
  else
    # The default suffix is take(uniqueString(group id), 6): six lowercase letters or digits.
    pg_live="$(printf '%s\n' "$pg_all" | tr -d '\r' | grep -E "^psql-aron-${ENV_NAME}-[a-z0-9]{6}[[:space:]]" || true)"
  fi
fi
if [ "$(printf '%s\n' "$pg_live" | grep -c .)" -gt 1 ]; then
  die "more than one PostgreSQL server is named psql-aron-${ENV_NAME}-<suffix> in $RG; resolve that first"
fi
if [ -n "$pg_live" ]; then
  read -r _ pg_zone pg_ha pg_standby <<< "$pg_live"
  if [[ "$pg_zone" =~ ^[123]$ ]]; then
    export ARON_PG_PRIMARY_ZONE="$pg_zone"
    # SameZone puts the standby in the primary's zone (the template does that itself); only ZoneRedundant has its own.
    if [ "$pg_ha" = ZoneRedundant ]; then
      if [[ "$pg_standby" =~ ^[123]$ ]] && [ "$pg_standby" != "$pg_zone" ]; then
        export ARON_PG_STANDBY_ZONE="$pg_standby"
      else
        # No standby zone reported (for example while Azure rebuilds the standby): never ask for primary == standby;
        # if this differs from the live standby, the what-if guard refuses and nothing changes.
        for z in 1 2 3; do [ "$z" = "$pg_zone" ] || { export ARON_PG_STANDBY_ZONE="$z"; break; }; done
      fi
    fi
    note "PostgreSQL live zones: primary ${pg_zone}, HA ${pg_ha}, standby ${ARON_PG_STANDBY_ZONE:-n/a}"
  fi
fi
# <<< pg-live-zones
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
# The commit main.bicep was last applied from (resource-group tag aron-infra-sha, written after a successful apply;
# `az deployment group create` has no --tags: deploy run 37639072495). The infra stage is skipped when
# nothing in infra_paths changed since THAT commit; the live api commit is only the fallback, because it does not
# advance while a later stage fails (2026-10-07: every INT push re-applied main.bicep while dblogins failed).
infra_sha="$(az group show -n "$RG" --query 'tags."aron-infra-sha"' -o tsv 2>/dev/null || true)"
[[ "$infra_sha" =~ ^[0-9a-f]{40}$ ]] && git cat-file -e "${infra_sha}^{commit}" 2>/dev/null || infra_sha="$deployed_sha"
skip_infra=false
if [ -n "$ROLLBACK_SHA" ]; then
  # A rollback changes images only; the infrastructure stays as the newest commit left it.
  [ -n "$previous" ] || die "rollback needs a previous successful infra deployment (aron-infra) in $RG"
  skip_infra=true
elif [ "${FORCE_INFRA:-false}" != "true" ] && [ -n "$previous" ] && [[ "$infra_sha" =~ ^[0-9a-f]{40}$ ]] \
   && git diff --quiet "$infra_sha" "$SHA" -- "${infra_paths[@]}" 2>/dev/null \
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
  note "infrastructure unchanged since ${infra_sha:-$deployed_sha}: main.bicep skipped (FORCE_INFRA=true runs it)"
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
  # The live PostgreSQL zones were exported above (pg-live-zones), before params_unchanged.
  note "what-if of main.bicep"
  az deployment group what-if -g "$RG" --template-file infra/main.bicep \
    --parameters "infra/params/${PROFILE}.bicepparam" --no-pretty-print -o json > "$whatif_file" \
    || die "what-if of main.bicep failed (nothing was changed)"
  EXISTING_POSTGRES_IDS="$(az postgres flexible-server list -g "$RG" --query "[].id" -o tsv | tr '\n' ' ')" \
    infra/scripts/whatif-guard.py "$whatif_file" || die "what-if shows a change the guard refuses (nothing was changed)"
  note "main.bicep (budget start $ARON_BUDGET_START_DATE)"
  outputs="$(az deployment group create -g "$RG" -n aron-infra --template-file infra/main.bicep \
    --parameters "infra/params/${PROFILE}.bicepparam" --query properties.outputs -o json)"
  az tag update --resource-id "$rg_id" --operation Merge --tags "aron-infra-sha=${SHA}" -o none \
    || echo "::warning::could not record aron-infra-sha on $RG; the next deploy re-applies main.bicep"
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
# Passwords and URLs of the per-app database logins (created by the dblogins job after the migrations).
infra/scripts/db-login-secrets.sh "$KV"

# -------------------------------------------------------------------------------------------------- images
# Each commit's image is pushed ONCE as <repo>:<sha>, its tag locked (write-enabled false; delete stays allowed for
# the purge, infra/scripts/acr-purge.sh), and the apps run it BY DIGEST (AUD-DG-06): a re-pushed or moved tag can
# never change what runs. A re-run or a rollback of a commit whose image exists reuses it without building.
digest_of() { # repo tag -> digest; empty ONLY when the registry says the repository or tag does not exist
  local out err i
  for i in 1 2 3; do
    if out="$(az acr repository show --name "$REGISTRY_NAME" --image "$1:$2" --query digest -o tsv 2>"$migrate_log")"; then
      printf '%s' "$out"; return 0
    fi
    err="$(cat "$migrate_log")"
    grep -qiE 'not found|MANIFEST_UNKNOWN|NAME_UNKNOWN|does not exist' <<<"$err" && return 0
    sleep $(( i * 10 ))
  done
  die "cannot read $1:$2 from the registry: $err"
}
publish() { # repo build-command...  -> sets IMAGE_REF to <registry>/<repo>@<digest>
  local repo="$1" d; shift
  d="$(digest_of "$repo" "$SHA")"
  if [ -z "$d" ]; then
    [ -z "$ROLLBACK_SHA" ] || die "rollback: $repo:$SHA is no longer in the registry (purged); roll forward with a revert commit instead"
    "$@"
    docker push -q "${REGISTRY}/${repo}:${SHA}"
    d="$(digest_of "$repo" "$SHA")"
    [[ "$d" =~ ^sha256:[0-9a-f]{64}$ ]] || die "cannot read the digest of ${repo}:${SHA} after the push"
    az acr repository update --name "$REGISTRY_NAME" --image "${repo}:${SHA}" --write-enabled false --delete-enabled true -o none \
      || echo "::warning::could not lock the tag ${repo}:${SHA}; the apps still run it by digest"
  else
    note "${repo}:${SHA} already in the registry (${d}); not rebuilt"
  fi
  IMAGE_REF="${REGISTRY}/${repo}@${d}"
}
build_backend() {
  ./gradlew --console=plain -q :backend:app:installDist
  local agent_ctx; agent_ctx="$(mktemp -d)"
  infra/scripts/fetch-ai-agent.sh "$agent_ctx"
  docker build --pull -q --provenance=false --sbom=false --build-context "agent=${agent_ctx}" \
    -f infra/docker/backend.Dockerfile -t "${REGISTRY}/aron-backend:${SHA}" \
    --label "org.opencontainers.image.revision=${SHA}" backend/app/build/install/aron-backend
}
build_web() {
  docker build --pull -q --provenance=false --sbom=false -f infra/docker/web.Dockerfile -t "${REGISTRY}/aron-web:${SHA}" \
    --build-arg "NEXT_PUBLIC_MAPS_WEB_KEY=${MAPS_WEB_KEY:-}" --label "org.opencontainers.image.revision=${SHA}" web
}
az acr login --name "$REGISTRY_NAME"
publish aron-backend build_backend; BACKEND_IMAGE="$IMAGE_REF"
WEB_IMAGE=""
web_digest=""
# Its own statement, so a registry error stops the deploy (inside an `if` a failed $(...) would read as "absent").
if [ -n "$ROLLBACK_SHA" ]; then web_digest="$(digest_of aron-web "$SHA")"; fi
if [ -n "$web_digest" ]; then
  publish aron-web build_web; WEB_IMAGE="$IMAGE_REF"
elif [ -z "$ROLLBACK_SHA" ] && [ -f web/package.json ]; then
  publish aron-web build_web; WEB_IMAGE="$IMAGE_REF"
elif [ -n "$ROLLBACK_SHA" ]; then
  # Without the old web image the web app would be left out of the template; keep the running one instead.
  WEB_IMAGE="$(az containerapp show -g "$RG" -n "ca-aron-${ENV_NAME}-web" --query 'properties.template.containers[0].image' -o tsv 2>/dev/null || true)"
  note "rollback: no aron-web image for $SHA; the web app keeps ${WEB_IMAGE:-nothing}"
else
  note "web/ has no package.json: no web app"
fi
# psql client for the dblogins job, imported once from Docker Hub by digest into this registry (the apps never pull
# from Docker Hub at run time; the purge leaves tools/ alone). `az acr import` takes a tag OR a digest, never both
# (InvalidImportImageParameter, deploy run 37619397240): the digest of postgres:16-alpine alone.
PSQL_SOURCE="docker.io/library/postgres@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea"
PSQL_TAG="16-alpine-721873c34ceb"
psql_digest="$(digest_of tools/postgres "$PSQL_TAG")"
if [ -z "$psql_digest" ]; then
  az acr import --name "$REGISTRY_NAME" --source "$PSQL_SOURCE" --image "tools/postgres:${PSQL_TAG}" -o none \
    || die "cannot import the psql image into $REGISTRY_NAME"
  psql_digest="$(digest_of tools/postgres "$PSQL_TAG")"
fi
[[ "$psql_digest" =~ ^sha256:[0-9a-f]{64}$ ]] || die "no digest for tools/postgres:${PSQL_TAG}"
export ARON_PSQL_IMAGE="${REGISTRY}/tools/postgres@${psql_digest}"
summary "Images (by digest): backend ${BACKEND_IMAGE}; web ${WEB_IMAGE:-none}"
export ARON_BACKEND_IMAGE="$BACKEND_IMAGE" ARON_WEB_IMAGE="$WEB_IMAGE" ARON_BUILD_ID="$SHA"
ARON_MIGRATE_IMAGE=""
if [ -n "$ROLLBACK_SHA" ]; then
  # The migrate job keeps the image that applied the newest migrations: an older Flyway would refuse the newer
  # schema ("applied migration not resolved locally") if a person started the job later.
  ARON_MIGRATE_IMAGE="$(az containerapp job show -g "$RG" -n "$JOB" --query 'properties.template.containers[0].image' -o tsv)" \
    || die "rollback: cannot read the migrate job's current image"
fi
export ARON_MIGRATE_IMAGE

# ------------------------------------------------------------------------------------------------ migrations
guard_newer_live "before the migrations"
check_freeze "before the migrations"
if [ "$RUN_MIGRATIONS" = true ]; then
  # The point-in-time-restore target if these migrations damage data (docs/runbooks/rollback-bad-migration.md).
  summary "PITR restore point (before the migrations): $(date -u +%Y-%m-%dT%H:%M:%SZ) on server $(out "$outputs" postgresServerName 2>/dev/null || echo "(see aron-infra outputs)")"
fi
note "migrate job with $BACKEND_IMAGE"
ARON_DEPLOY_SERVICES=false az deployment group create -g "$RG" -n aron-apps-migrate --template-file infra/apps.bicep \
  --parameters "infra/params/${PROFILE}.apps.bicepparam" -o none
# Prints the console log of one job execution from Log Analytics (ingestion takes a few minutes, so it retries), so a
# failed migration shows its cause in the deploy log. Best effort: never fails the caller.
job_logs() { # execution
  local ws q out
  ws="$(az monitor log-analytics workspace list -g "$RG" --query "[0].customerId" -o tsv 2>/dev/null)" || return 0
  [ -n "$ws" ] || return 0
  q="union isfuzzy=true ContainerAppConsoleLogs_CL, ContainerAppConsoleLogs
     | extend exec = coalesce(column_ifexists('ContainerGroupName_s', ''), column_ifexists('ContainerGroupName', '')),
              line = coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', ''))
     | where exec startswith '$1' | order by TimeGenerated asc | project TimeGenerated, line | take 200"
  for _ in $(seq 1 12); do
    out="$(az monitor log-analytics query -w "$ws" --analytics-query "$q" --query "[].[TimeGenerated, line]" -o tsv 2>/dev/null || true)"
    if [ -n "$out" ]; then echo "---- console log of $1"; echo "$out"; echo "----"; return 0; fi
    sleep 30
  done
  echo "(no console log of $1 in Log Analytics after 6 minutes)"
  # No console output usually means the container never ran (image pull, identity, start command): the platform's
  # system log for the execution says why (deploy run 37624445094: dblogins Failed with an empty console log).
  q="union isfuzzy=true ContainerAppSystemLogs_CL, ContainerAppSystemLogs
     | where * contains '$1'
     | extend line = strcat(coalesce(column_ifexists('Reason_s', ''), column_ifexists('Reason', '')), ': ',
                            coalesce(column_ifexists('Log_s', ''), column_ifexists('Log', '')))
     | order by TimeGenerated asc | project TimeGenerated, line | take 50"
  out="$(az monitor log-analytics query -w "$ws" --analytics-query "$q" --query "[].[TimeGenerated, line]" -o tsv 2>/dev/null || true)"
  if [ -n "$out" ]; then echo "---- system log of $1"; echo "$out"; echo "----"; else echo "(no system log of $1 either)"; fi
}
if [ "$RUN_MIGRATIONS" = true ]; then
  # One more execution ONLY when the first could not open its first database connection (seen twice on 2026-10-06/07:
  # Hikari "Connection is not available ... total=0" within 5 s on a fresh job replica). Migrations are idempotent
  # (Flyway). The root fix, Flyway connectRetries in the migrate role, is requested from backend-core
  # (docs/requests/backend-migrate-connect-retries.md). Any other failure stops at once.
  for attempt in 1 2; do
    execution="$(az containerapp job start -g "$RG" -n "$JOB" --query name -o tsv)"
    note "migrations started: $execution (attempt $attempt)"
    status=""
    for _ in $(seq 1 120); do
      status="$(az containerapp job execution show -g "$RG" -n "$JOB" --job-execution-name "$execution" --query properties.status -o tsv 2>/dev/null || echo unknown)"
      case "$status" in Succeeded|Failed|Stopped|Degraded) break ;; esac
      sleep 10
    done
    [ "$status" = Succeeded ] && { note "migrations succeeded"; break; }
    case "$status" in Failed|Stopped|Degraded) ;; *)
      # Still running after 20 minutes: stop it, so no second migrate execution can start beside it after the lock.
      az containerapp job stop -g "$RG" -n "$JOB" --job-execution-name "$execution" -o none 2>/dev/null \
        || echo "::warning::could not stop $execution"
      status="still running after 20 minutes, stopped"
    esac
    job_logs "$execution" | tee "$migrate_log"
    if [ "$attempt" -eq 1 ] && [ "$status" = Failed ] \
       && grep -qE 'FlywaySqlUnableToConnectToDbException|Connection is not available, request timed out' "$migrate_log"; then
      echo "::warning::migrations $execution could not connect to the database at start; running the job once more"
      continue
    fi
    die "migrations $execution ended ${status}; the apps were NOT updated. Logs: Log Analytics, ContainerAppConsoleLogs, ContainerJobName_s == '$JOB'"
  done
else
  note "migrations not run (RUN_MIGRATIONS=$RUN_MIGRATIONS)"
fi

# ------------------------------------------------------------------------------------------------- db logins
# Per-app least-privilege logins (infra/sql/runtime-logins.sql) must exist with the Key Vault passwords before the
# apps switch to them. Needs the V0014/V0020 roles, so after the migrations. Runs in a rollback too (idempotent, no
# schema change), because the apps template of the current commit may point the apps at these logins.
dblogins_result="not run (no psql image)"
DBLOGINS_JOB="$(az deployment group show -g "$RG" -n aron-apps-migrate --query properties.outputs.dbLoginsJobName.value -o tsv)"
if [ -n "$DBLOGINS_JOB" ]; then
  execution="$(az containerapp job start -g "$RG" -n "$DBLOGINS_JOB" --query name -o tsv)"
  note "database logins started: $execution"
  status=""
  for _ in $(seq 1 60); do
    status="$(az containerapp job execution show -g "$RG" -n "$DBLOGINS_JOB" --job-execution-name "$execution" --query properties.status -o tsv 2>/dev/null || echo unknown)"
    case "$status" in Succeeded|Failed|Stopped|Degraded) break ;; esac
    sleep 10
  done
  if [ "$status" != Succeeded ]; then
    job_logs "$execution"
    # Straight from the platform (Log Analytics had nothing for runs 37624445094, 37630304505, 37632012265): the
    # execution record (status, start and end, replica states; env values are secret references, never values) and
    # the container's own log stream.
    echo "---- execution record of $execution"
    az containerapp job execution show -g "$RG" -n "$DBLOGINS_JOB" --job-execution-name "$execution" \
      --query "{status: properties.status, start: properties.startTime, end: properties.endTime, template: properties.template.containers[0].{image: image, command: command}}" \
      -o jsonc 2>&1 || true
    echo "---- container log of $execution"
    az containerapp job logs show -g "$RG" -n "$DBLOGINS_JOB" --execution "$execution" --container dblogins --tail 100 \
      --format text 2>&1 | tail -n 100 || true
    echo "----"
    per_app="$(az deployment group show -g "$RG" -n aron-apps-migrate --query properties.outputs.dbPerAppLogins.value -o tsv 2>/dev/null || echo unknown)"
    # tsv prints a JSON boolean as True or true depending on the CLI version; anything but false counts as on.
    if [ "${per_app,,}" != false ]; then
      die "database logins $execution ended ${status:-unknown}; the apps use these logins, so they were NOT updated"
    fi
    # dbPerAppLogins is off: the apps still connect as the admin login and never use these logins, so a failure here
    # must not hold back the apps. It stays visible (warning, summary row) until the job passes.
    echo "::warning::database logins $execution ended ${status:-unknown}; per-app logins are OFF, so the apps deploy anyway"
    # Bisect the cause in the same run (two short executions with template overrides; secrets are never printed).
    infra/scripts/dblogins-probe.sh "$RG" "$DBLOGINS_JOB" || echo "::warning::dblogins probe could not run"
    dblogins_result="FAILED (${status:-unknown}; per-app logins off, apps not affected)"
  else
    note "database logins succeeded"
    dblogins_result="succeeded"
  fi
fi

# ------------------------------------------------------------------------------------------------------ apps
guard_newer_live "before the apps"
check_freeze "before the apps"
# Multiple revision mode (stage, prod): the revision serving now is the fallback if the health gate fails.
api_mode="$(az containerapp show -g "$RG" -n "$api_name" --query properties.configuration.activeRevisionsMode -o tsv 2>/dev/null || true)"
# The fallback is a revision that serves traffic now and runs ANOTHER build: on a re-run of the same commit the
# latest ready revision may be this commit's bad one, which must never be the place traffic is "put back" on.
prev_revision=""
if [ "$api_mode" = Multiple ]; then
  while IFS=$'\t' read -r rev build; do
    if [ -n "$rev" ] && [ "$build" != "$SHA" ]; then prev_revision="$rev"; break; fi
  done < <(az containerapp revision list -g "$RG" -n "$api_name" \
             --query "[?properties.trafficWeight > \`0\`].[name, properties.template.containers[0].env[?name=='ARON_BUILD'].value | [0]]" -o tsv 2>/dev/null || true)
  note "fallback revision if the health gate fails: ${prev_revision:-none (no serving revision with another build)}"
fi
note "apps (and Front Door routes when the profile has Front Door)"
apps="$(ARON_DEPLOY_SERVICES=true az deployment group create -g "$RG" -n aron-apps --template-file infra/apps.bicep \
  --parameters "infra/params/${PROFILE}.apps.bicepparam" --query properties.outputs -o json)"
API_HOST="$(out "$apps" apiHost)"
WEB_DEPLOYED="$(out "$apps" webDeployed)"
WEB_HOST="$(out "$apps" webHost)"
api_mode_now="$(az containerapp show -g "$RG" -n "$api_name" --query properties.configuration.activeRevisionsMode -o tsv || true)"

if [ "$PRIVATE_LINK" = true ]; then
  expected=1; [ "$WEB_DEPLOYED" = true ] && expected=2
  infra/scripts/approve-private-link.sh "$RG" "$CAE" "$expected"
fi

# ------------------------------------------------------------------------------------------------ checks
# Health gate (AUD-REL-06, AUD-DG-04): the public address answers with THIS build, readiness (database) is 200, and
# the web login page loads. In Multiple revision mode a failed gate puts all api traffic back on the previous
# revision before the deploy fails; in Single mode (dev) the runbook's rollback dispatch does that.
if ! infra/scripts/smoke.sh "$API_HOST" "$SHA" "$WEB_HOST"; then
  if [ "$api_mode" = Multiple ] && [ "$api_mode_now" = Multiple ] && [ -n "$prev_revision" ]; then
    if az containerapp ingress traffic set -g "$RG" -n "$api_name" --revision-weight "${prev_revision}=100" -o none; then
      summary "Health gate FAILED: api traffic is back on ${prev_revision}."
    else
      summary "Health gate FAILED and the traffic could NOT be put back on ${prev_revision}: follow docs/runbooks/rollback-bad-deploy.md now."
    fi
  fi
  die "health gate failed for $SHA on $API_HOST (docs/runbooks/rollback-bad-deploy.md)"
fi
if [ "$api_mode_now" = Multiple ]; then
  # Keep the new revision and the one before it (instant traffic switch back); deactivate any older active ones.
  latest="$(az containerapp show -g "$RG" -n "$api_name" --query properties.latestReadyRevisionName -o tsv)"
  for r in $(az containerapp revision list -g "$RG" -n "$api_name" --query "[?properties.active].name" -o tsv); do
    [ "$r" = "$latest" ] || [ "$r" = "$prev_revision" ] || az containerapp revision deactivate -g "$RG" -n "$api_name" --revision "$r" -o none \
      || echo "::warning::could not deactivate the old revision $r"
  done
fi
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
summary "| Commit | ${SHA}$([ -n "$ROLLBACK_SHA" ] && echo ' (ROLLBACK)') |"
summary "| API | https://${API_HOST}/v1/health |"
summary "| Backend image | ${BACKEND_IMAGE} |"
summary "| Web image | ${WEB_IMAGE:-none (no web/ yet)} |"
summary "| Infrastructure | $([ "$skip_infra" = true ] && echo "unchanged, skipped" || echo deployed) |"
summary "| Database logins | ${dblogins_result} |"
summary "| Migrations | ${RUN_MIGRATIONS} |"
summary "| Budget | ${budget_line} |"
