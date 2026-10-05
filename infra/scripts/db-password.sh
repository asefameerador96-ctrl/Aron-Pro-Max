#!/usr/bin/env bash
# Prints the PostgreSQL admin password for the deployment: the one already in Key Vault (aron-db-admin-password)
# or, on the very first deployment (no vault yet, or a vault without the secret), a new random one.
# Any other failure (throttling, network, permission that cannot be repaired) is an ERROR, never a reason to mint a
# new password: a new one would rotate the server password under the running apps.
# Usage: ARON_DB_ADMIN_PASSWORD="$(infra/scripts/db-password.sh <resource-group> <key-vault-name or empty>)"
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
rg="${1:?resource group}"; kv="${2:-}"

generate() {
  echo "== $1: generating the database admin password" >&2
  # 32 alphanumerics plus one of each class Azure requires; URL-safe, so it can sit inside a JDBC URL.
  printf 'Ar9%s' "$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32)"
}

[ -n "$kv" ] || { generate "no Key Vault in $rg yet (first deployment)"; exit 0; }

if ! show="$(az keyvault show --resource-group "$rg" --name "$kv" --query id -o tsv 2>&1)"; then
  case "$show" in
    *ResourceNotFound*|*"was not found"*|*NotFound*) generate "Key Vault $kv not found (first deployment)"; exit 0 ;;
    *) die "cannot read Key Vault $kv (not generating a new password): $show" ;;
  esac
fi
vault_id="$show"

read_secret() { az keyvault secret show --vault-name "$kv" --name aron-db-admin-password --query value -o tsv 2>&1; }
if out="$(read_secret)"; then printf '%s' "$out"; exit 0; fi
case "$out" in
  *SecretNotFound*|*"was not found"*) generate "secret absent in $kv"; exit 0 ;;
  *Forbidden*|*"not authorized"*|*AuthorizationFailed*)
    echo "== no read access to $kv yet: granting Key Vault Secrets Officer to the deploy identity" >&2
    ensure_secrets_officer "$vault_id" "$kv" || die "still no data-plane access to $kv after the role assignment"
    if out="$(read_secret)"; then printf '%s' "$out"; exit 0; fi
    case "$out" in
      *SecretNotFound*|*"was not found"*) generate "secret absent in $kv"; exit 0 ;;
    esac
    die "cannot read aron-db-admin-password from $kv: $out" ;;
  *) die "cannot read aron-db-admin-password from $kv (not generating a new one): $out" ;;
esac
