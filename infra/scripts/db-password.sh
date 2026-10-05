#!/usr/bin/env bash
# Prints the PostgreSQL admin password for the deployment: the one already in Key Vault (aron-db-admin-password)
# or, on the very first deployment (no vault yet, or vault without the secret), a new random one.
# A vault that exists but refuses the read is an ERROR, never a reason to mint a new password.
# Usage: ARON_DB_ADMIN_PASSWORD="$(infra/scripts/db-password.sh <resource-group> <key-vault-name>)"
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
rg="${1:?resource group}"; kv="${2:?key vault name}"

if ! az keyvault show --resource-group "$rg" --name "$kv" --only-show-errors >/dev/null 2>&1; then
  echo "== Key Vault $kv not found: first deployment, generating the database admin password" >&2
else
  if out="$(az keyvault secret show --vault-name "$kv" --name aron-db-admin-password --query value -o tsv 2>&1)"; then
    printf '%s' "$out"; exit 0
  fi
  case "$out" in
    *SecretNotFound*|*"was not found"*) echo "== secret absent in $kv: generating the database admin password" >&2 ;;
    *) die "cannot read aron-db-admin-password from $kv (not generating a new one): $out" ;;
  esac
fi
# 32 alphanumerics plus one of each class Azure requires; URL-safe, so it can sit inside a JDBC URL.
pw="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)"
printf 'Ar9%s' "$pw"
