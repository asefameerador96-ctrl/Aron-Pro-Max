#!/usr/bin/env bash
# Acceptance test of N-012: the deploy identity has rights ONLY on its resource group.
#   1. it can see its own group;
#   2. it cannot create a resource group (that would need subscription rights);
#   3. it cannot deploy into any other group it can see (normally it sees none).
# Fails the run if the identity is over-privileged; cleans up anything it managed to create.
# Usage: infra/scripts/scope-check.sh <its-resource-group>
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
rg="${1:?resource group}"

az group show --name "$rg" --query name -o tsv >/dev/null || die "the deploy identity cannot read its own group $rg"
note "can read $rg"

probe="rg-aron-scope-probe-${GITHUB_RUN_ID:-local}"
if out="$(az group create --name "$probe" --location "${AZURE_LOCATION:-southeastasia}" --tags purpose=scope-probe -o none 2>&1)"; then
  az group delete --name "$probe" --yes --no-wait || true
  die "OVER-PRIVILEGED: the deploy identity created resource group $probe (it must only have rights on $rg)"
fi
case "$out" in
  *AuthorizationFailed*|*"does not have authorization"*|*Forbidden*) note "creating another group is denied (as required)" ;;
  *) die "unexpected answer when probing group creation: $out" ;;
esac

others="$(az group list --query "[?name!='$rg'].name" -o tsv)"
for g in $others; do
  if az deployment group validate --resource-group "$g" --template-file "$(dirname "$0")/../tests/empty.json" -o none 2>/dev/null; then
    die "OVER-PRIVILEGED: the deploy identity may deploy into $g"
  fi
  note "deploying into $g is denied"
done
note "scope check passed: rights are limited to $rg"
