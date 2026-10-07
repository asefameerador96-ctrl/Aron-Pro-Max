#!/usr/bin/env bash
# Fails when web/src/contract/openapi.d.ts is not what `npm run gen:contract` produces from contract/openapi.yaml.
# Does not modify the working tree (generates into a temp file). Needs `npm ci` in web/ first (CI does it).
# Usage: shared/contract/tools/check-web-contract-drift.sh        Exit 0 = in sync, 1 = drift, 2 = tool missing.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
bin="$root/web/node_modules/.bin/openapi-typescript"
[ -x "$bin" ] || { echo "openapi-typescript not installed: run 'npm ci' in web/ first" >&2; exit 2; }
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
(cd "$root/web" && "$bin" ../contract/openapi.yaml -o "$tmp/openapi.d.ts" >/dev/null)
if diff -u "$root/web/src/contract/openapi.d.ts" "$tmp/openapi.d.ts" > "$tmp/diff"; then
  echo "web/src/contract/openapi.d.ts matches contract/openapi.yaml"
else
  head -60 "$tmp/diff" >&2
  echo "DRIFT: contract/openapi.yaml changed but web/src/contract/openapi.d.ts was not regenerated. Run: cd web && npm run gen:contract" >&2
  exit 1
fi
