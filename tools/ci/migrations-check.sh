#!/usr/bin/env bash
# Migration gate (docs/31 s2, docs/30 s3: forward-only, checksum-checked, expand/contract).
#   1. Checksum: every migration that exists at <base> must be byte-identical at HEAD and still present (a shipped
#      migration is never edited, renamed or deleted; the git blob id is the checksum). Flyway re-validates the
#      checksums against the database on every deploy as well.
#   2. Order: versions are unique, and a new migration's version is higher than every version at <base> (Flyway
#      would otherwise skip it on an existing database).
#   3. Lint: squawk on the migrations added since <base> only (the shipped ones are history and are not re-judged),
#      with tools/ci/squawk.toml (rules that protect a live table; style rules our schema does not follow are off).
# Usage: tools/ci/migrations-check.sh <base-commit> [squawk binary]
# Owner: infra lane. The db lane owns db/migrations; this script only reads it.
set -euo pipefail
base="${1:?base commit}"
squawk="${2:-squawk}"
dir=db/migrations
cd "$(git rev-parse --show-toplevel)"
fail=0
err() { echo "::error::$*"; fail=1; }

git cat-file -e "${base}^{commit}" 2>/dev/null || { echo "::error::base commit $base is not in this checkout"; exit 1; }
version() { basename "$1" | sed -nE 's/^V([0-9]+)__.*\.sql$/\1/p'; }

# 1. Shipped migrations unchanged.
mapfile -t shipped < <(git ls-tree --name-only "$base" -- "$dir/" | grep -E '/V[0-9]+__.*\.sql$' || true)
for f in "${shipped[@]}"; do
  if ! git cat-file -e "HEAD:$f" 2>/dev/null; then
    err "$f was shipped at ${base:0:7} and is gone at HEAD: a shipped migration is never deleted or renamed"
  elif [ "$(git rev-parse "$base:$f")" != "$(git rev-parse "HEAD:$f")" ]; then
    err "$f was shipped at ${base:0:7} and changed since: never edit a shipped migration; add a new one (expand/contract)"
  fi
done
echo "checksum: ${#shipped[@]} shipped migration(s) at ${base:0:7} checked"

# 2. Versions unique and new ones above every shipped one.
mapfile -t now < <(git ls-tree --name-only HEAD -- "$dir/" | grep -E '/V[0-9]+__.*\.sql$' || true)
bad_names="$(git ls-tree --name-only HEAD -- "$dir/" | grep -E '\.sql$' | grep -vE '/V[0-9]+__[A-Za-z0-9_]+\.sql$' || true)"
[ -z "$bad_names" ] || err "migration file names must be V<number>__<words>.sql: $bad_names"
dups="$(for f in "${now[@]}"; do echo "$((10#$(version "$f")))"; done | sort -n | uniq -d)"
[ -z "$dups" ] || err "duplicate migration version(s): $dups"
max_shipped=0
for f in "${shipped[@]}"; do v=$((10#$(version "$f"))); [ "$v" -gt "$max_shipped" ] && max_shipped=$v; done
added=()
for f in "${now[@]}"; do
  git cat-file -e "$base:$f" 2>/dev/null && continue
  added+=("$f")
  v=$((10#$(version "$f")))
  [ "$v" -gt "$max_shipped" ] || err "$f has version $v, not above the highest shipped version $max_shipped (Flyway would not apply it to an existing database)"
done
echo "order: ${#added[@]} new migration(s), highest shipped version $max_shipped"

# 3. squawk on the new ones.
if [ "${#added[@]}" -gt 0 ]; then
  if ! "$squawk" --config tools/ci/squawk.toml --reporter gcc --assume-in-transaction "${added[@]}"; then
    err "squawk found migration risks in: ${added[*]} (rules: tools/ci/squawk.toml; fix the migration, it is not shipped yet. Most common: put SET lock_timeout = '5s'; before the first DDL, CREATE INDEX CONCURRENTLY on an existing table, ADD CONSTRAINT ... NOT VALID then VALIDATE CONSTRAINT)"
  fi
fi

[ "$fail" -eq 0 ] && echo "migrations: ok" || exit 1
