#!/usr/bin/env bash
# Acceptance test of N-012: the deploy identity has rights ONLY on its resource group.
#   1. it can read its own group;
#   2. a test deployment into ANOTHER group is denied with AuthorizationFailed (a "not found" answer would mean it can
#      read the subscription, which is already too much);
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

other="rg-aron-scope-probe"
if out="$(az deployment group validate --resource-group "$other" --template-file "$empty" -o none 2>&1)"; then
  die "OVER-PRIVILEGED: a test deployment into $other was accepted"
fi
denied "$out" || die "a test deployment into $other did not fail with AuthorizationFailed (rights beyond $rg?): $out"
note "test deployment into another group ($other) is denied"

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
