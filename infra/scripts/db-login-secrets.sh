#!/usr/bin/env bash
# Key Vault secrets of the per-app database logins (docs/requests/db-runtime-roles.md, infra/sql/runtime-logins.sql):
#   aron-db-pw-app-api, aron-db-pw-app-worker, aron-db-pw-app-jobs   passwords, generated ONCE and then kept (a lost
#                                    read is an error, never a reason to mint a new one: that would rotate a live login)
#   aron-db-api-url, aron-db-api-read-url           api: pooled primary and read URLs as app_api
#   aron-db-jobs-direct-url, aron-db-jobs-read-url  worker: direct primary (advisory locks) and read URLs as app_jobs
# The URLs are the admin URLs Bicep writes (aron-db-url, aron-db-read-url, aron-db-direct-url) with only the user and
# password replaced, so host, port, PgBouncer and TLS settings can never drift apart. A URL is written only when its
# value changed (each write is a new secret version). Nothing secret is printed or passed on a command line.
# Usage: infra/scripts/db-login-secrets.sh <key-vault-name>
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
kv="${1:?key vault name}"
tmp="$(mktemp -d)"; chmod 700 "$tmp"; trap 'rm -rf "$tmp"' EXIT

# get <name> <file>: 0 = written to file; 3 = the secret does not exist; anything else dies.
get() {
  if az keyvault secret show --vault-name "$kv" --name "$1" --query value -o tsv >"$2" 2>"$tmp/err"; then return 0; fi
  grep -qiE 'SecretNotFound|was not found' "$tmp/err" && return 3
  die "cannot read $1 from $kv (not generating a new one): $(cat "$tmp/err")"
}
put() { az keyvault secret set --vault-name "$kv" --name "$1" --file "$2" --encoding utf-8 --content-type "$3" --only-show-errors -o none \
  || die "cannot write $1 to $kv"; }

for login in app-api app-worker app-jobs; do
  name="aron-db-pw-${login}"
  rc=0; get "$name" "$tmp/pw" || rc=$?
  if [ "$rc" -eq 3 ]; then
    python3 -c 'import secrets, string; a = string.ascii_letters + string.digits; print("Ar9" + "".join(secrets.choice(a) for _ in range(32)), end="")' > "$tmp/pw"
    put "$name" "$tmp/pw" text/plain
    note "$name generated"
  else
    note "$name present (kept)"
  fi
  cp "$tmp/pw" "$tmp/pw-${login}"
done

# url <target-secret> <source-admin-secret> <login>
url() {
  get "$2" "$tmp/src" || die "$2 is missing in $kv (written by main.bicep)"
  python3 - "$tmp/src" "$tmp/pw-${3//_/-}" "$3" "$tmp/new" <<'PY'
import sys, urllib.parse
src, pw_file, login, out = sys.argv[1:5]
url = open(src).read().strip()
pw = open(pw_file).read().strip()
base, _, query = url.partition("?")
pairs = [p for p in query.split("&") if p and not p.startswith(("user=", "password="))]
pairs += ["user=" + login, "password=" + urllib.parse.quote(pw, safe="")]
open(out, "w").write(base + "?" + "&".join(pairs))
PY
  rc=0; get "$1" "$tmp/old" || rc=$?
  if [ "$rc" -eq 0 ] && cmp -s <(tr -d '\n' < "$tmp/old") "$tmp/new"; then
    note "$1 unchanged"
  else
    put "$1" "$tmp/new" jdbc-url
    note "$1 written"
  fi
}
url aron-db-api-url aron-db-url app_api
url aron-db-api-read-url aron-db-read-url app_api
url aron-db-jobs-direct-url aron-db-direct-url app_jobs
url aron-db-jobs-read-url aron-db-read-url app_jobs
