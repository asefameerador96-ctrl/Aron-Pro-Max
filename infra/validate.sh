#!/usr/bin/env bash
# Offline validation of the Azure infrastructure and the workflows (no Azure credentials needed):
#   1. bicep build of every template and module, bicep lint (warnings are errors), bicep build-params
#   2. infra/tests/check_infra.py on the compiled JSON (group scope only, components, security, sizing, workflows)
#   3. actionlint (with shellcheck) on .github/workflows, shellcheck on infra/scripts
#   4. ONLY when `az` is signed in and AZURE_RESOURCE_GROUP is set: what-if of main.bicep against that group
# Tools: uses bicep/actionlint/shellcheck from PATH (or BICEP/ACTIONLINT/SHELLCHECK), otherwise downloads the
# pinned release into $TOOLS_DIR and checks its SHA-256.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
TOOLS_DIR="${TOOLS_DIR:-${RUNNER_TEMP:-/tmp}/aron-infra-tools}"
OUT="${ARON_COMPILED:-$ROOT/infra/.compiled}"
mkdir -p "$TOOLS_DIR" "$OUT"

BICEP_VERSION=0.47.16
BICEP_SHA256=64c345a58e0c3e48b1bc98a4e62d6b3adb1d238281297de3400aeafb2697aa5a
ACTIONLINT_VERSION=1.7.12
ACTIONLINT_SHA256=8aca8db96f1b94770f1b0d72b6dddcb1ebb8123cb3712530b08cc387b349a3d8
SHELLCHECK_VERSION=0.10.0
SHELLCHECK_SHA256=6c881ab0698e4e6ea235245f22832860544f17ba386442fe7e9d629f8cbedf87

fetch() { # url sha256 dest
  curl -sSfL --retry 3 -o "$3.part" "$1"
  echo "$2  $3.part" | sha256sum -c --quiet - || { rm -f "$3.part"; echo "checksum mismatch for $1" >&2; exit 1; }
  mv "$3.part" "$3"
}

BICEP="${BICEP:-$(command -v bicep || true)}"
if [ -z "$BICEP" ]; then
  BICEP="$TOOLS_DIR/bicep-$BICEP_VERSION"
  [ -x "$BICEP" ] || { fetch "https://github.com/Azure/bicep/releases/download/v$BICEP_VERSION/bicep-linux-x64" "$BICEP_SHA256" "$BICEP"; chmod +x "$BICEP"; }
fi
SHELLCHECK="${SHELLCHECK:-$(command -v shellcheck || true)}"
if [ -z "$SHELLCHECK" ]; then
  SHELLCHECK="$TOOLS_DIR/shellcheck-$SHELLCHECK_VERSION"
  if [ ! -x "$SHELLCHECK" ]; then
    fetch "https://github.com/koalaman/shellcheck/releases/download/v$SHELLCHECK_VERSION/shellcheck-v$SHELLCHECK_VERSION.linux.x86_64.tar.xz" "$SHELLCHECK_SHA256" "$TOOLS_DIR/sc.tar.xz"
    tar -xJf "$TOOLS_DIR/sc.tar.xz" -C "$TOOLS_DIR" && mv "$TOOLS_DIR/shellcheck-v$SHELLCHECK_VERSION/shellcheck" "$SHELLCHECK"
  fi
fi
ACTIONLINT="${ACTIONLINT:-$(command -v actionlint || true)}"
if [ -z "$ACTIONLINT" ]; then
  ACTIONLINT="$TOOLS_DIR/actionlint-$ACTIONLINT_VERSION"
  if [ ! -x "$ACTIONLINT" ]; then
    fetch "https://github.com/rhysd/actionlint/releases/download/v$ACTIONLINT_VERSION/actionlint_${ACTIONLINT_VERSION}_linux_amd64.tar.gz" "$ACTIONLINT_SHA256" "$TOOLS_DIR/al.tgz"
    tar -xzf "$TOOLS_DIR/al.tgz" -C "$TOOLS_DIR" actionlint && mv "$TOOLS_DIR/actionlint" "$ACTIONLINT"
  fi
fi
echo "bicep: $("$BICEP" --version)"

fail=0
step() { echo; echo "== $*"; }
check() { # runs a command, records failure, prints its output; warnings are failures too
  local log; log="$(mktemp)"
  if ! "$@" >"$log" 2>&1 || grep -qE ': (Warning|Error) ' "$log"; then cat "$log"; echo "FAILED: $*"; fail=1; else cat "$log"; fi
  rm -f "$log"
}

step "bicep build + lint"
for f in infra/main.bicep infra/apps.bicep infra/lib/*.bicep infra/modules/*.bicep; do
  check "$BICEP" build "$f" --stdout >/dev/null
  check "$BICEP" lint "$f"
done
"$BICEP" build infra/main.bicep --outfile "$OUT/main.json"
"$BICEP" build infra/apps.bicep --outfile "$OUT/apps.json"

step "bicep build-params"
# A throwaway value satisfies the password's minLength at compile time; the deploy supplies the real one.
export ARON_DB_ADMIN_PASSWORD="validate-only-not-a-secret-000"
for p in infra/params/*.bicepparam; do
  name="$(basename "$p" .bicepparam)"
  check "$BICEP" build-params "$p" --outfile "$OUT/$name.parameters.json"
done
# GitHub passes an unset repository variable as an EMPTY string: every optional variable empty must still compile.
for p in infra/params/*.bicepparam; do
  check env AZURE_LOCATION= ARON_NAME_SUFFIX= ARON_ALERT_EMAILS= ARON_BUDGET_AMOUNT= ARON_BUDGET_START_DATE= \
    ARON_PG_READ_REPLICA= ARON_BACKEND_IMAGE= ARON_BUILD_ID= ARON_MIGRATE_IMAGE= ARON_WEB_IMAGE= ARON_DEPLOY_SERVICES= ARON_API_READINESS_PATH= \
    ARON_WORKER_MIN_REPLICAS= "$BICEP" build-params "$p" --stdout >/dev/null
done
unset ARON_DB_ADMIN_PASSWORD

step "template checks (infra/tests/check_infra.py)"
ARON_COMPILED="$OUT" python3 infra/tests/check_infra.py || fail=1

step "actionlint + shellcheck"
check env SHELLCHECK_OPTS="-e SC1091" "$ACTIONLINT" -shellcheck "$SHELLCHECK" .github/workflows/*.yml
check "$SHELLCHECK" -x -P SCRIPTDIR infra/scripts/*.sh infra/validate.sh infra/deploy.sh tools/ci/*.sh
# Every script the workflow or a person runs directly must be executable in git (checkout keeps the mode).
for f in infra/deploy.sh infra/validate.sh infra/scripts/*.sh tools/ci/*.sh tools/ci/*.py; do
  [ "$f" = infra/scripts/lib.sh ] && continue
  mode="$(git ls-files -s "$f" 2>/dev/null | cut -d' ' -f1)"
  if [ -n "$mode" ] && [ "$mode" != 100755 ]; then echo "FAILED: $f is committed without the executable bit ($mode)"; fail=1; fi
  [ -x "$f" ] || { echo "FAILED: $f is not executable"; fail=1; }
done

step "CI gate self-tests (tools/ci/test_gates.py; squawk and oasdiff tests skip without the binaries, CI has them)"
check python3 tools/ci/test_gates.py

step "what-if against Azure (needs a signed-in az and AZURE_RESOURCE_GROUP)"
if command -v az >/dev/null && [ -n "${AZURE_RESOURCE_GROUP:-}" ] && az account show >/dev/null 2>&1; then
  ARON_DB_ADMIN_PASSWORD="whatif-only-not-a-secret-0000" az deployment group what-if -g "$AZURE_RESOURCE_GROUP" \
    --template-file infra/main.bicep --parameters "infra/params/${ARON_ENV:-dev}.bicepparam" || fail=1
else
  echo "SKIPPED: no Azure sign-in in this shell (what-if, quota and RBAC checks run in the deploy workflow)"
fi

echo
if [ "$fail" -ne 0 ]; then echo "infra validation FAILED"; exit 1; fi
echo "infra validation passed"
