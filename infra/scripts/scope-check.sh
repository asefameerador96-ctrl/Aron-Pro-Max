#!/usr/bin/env bash
# Acceptance test of N-012: the deploy identity has rights ONLY on its resource group.
#   1. it can read its own group;
#   2. a test deployment into ANOTHER, EXISTING group (rg-aron-scope-probe, empty, created by the bootstrap with no
#      rights for this identity) is denied with AuthorizationFailed;
#   3. it cannot create a resource group;
#   4. it cannot deploy into any other group it happens to see (normally it sees none).
# Fails the run if the identity is over-privileged; removes anything it managed to create.
# Usage: infra/scripts/scope-check.sh <its-resource-group>
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
rg="${1:?resource group}"
empty="$(dirname "$0")/../tests/empty.json"

az group show --name "$rg" --query name -o tsv >/dev/null || die "the deploy identity cannot read its own group $rg"
note "can read $rg"

denied() { case "$1" in *AuthorizationFailed*|*"does not have authorization"*) return 0 ;; *) return 1 ;; esac; }

other="${ARON_SCOPE_PROBE_GROUP:-rg-aron-scope-probe}"
if out="$(az deployment group validate --resource-group "$other" --template-file "$empty" -o none 2>&1)"; then
  die "OVER-PRIVILEGED: a test deployment into $other was accepted"
fi
if denied "$out"; then
  note "test deployment into another group ($other) is denied"
else
  case "$out" in
    # ARM may answer "not found" to a caller without rights; the probe group then does not exist yet (bootstrap
    # older than this check). Checks 3 and 4 still hold; say so instead of failing every deploy.
    *ResourceGroupNotFound*) echo "::warning title=Scope probe group missing::$other does not exist; re-run infra/bootstrap-azure.ps1 to create it" ;;
    *) die "a test deployment into $other did not fail with AuthorizationFailed (rights beyond $rg?): $out" ;;
  esac
fi

probe="rg-aron-scope-probe-${GITHUB_RUN_ID:-local}"
if out="$(az group create --name "$probe" --location "${AZURE_LOCATION:-southeastasia}" --tags purpose=scope-probe -o none 2>&1)"; then
  az group delete --name "$probe" --yes --no-wait || true
  die "OVER-PRIVILEGED: the deploy identity created resource group $probe"
fi
denied "$out" || die "unexpected answer when probing group creation: $out"
note "creating a resource group is denied"

for g in $(az group list --query "[?name!='$rg'].name" -o tsv); do
  if az deployment group validate --resource-group "$g" --template-file "$empty" -o none 2>/dev/null; then
    die "OVER-PRIVILEGED: the deploy identity may deploy into $g"
  fi
  note "deploying into $g is denied"
done
note "scope check passed: rights are limited to $rg"
