#!/usr/bin/env bash
# Contract breaking-change gate (docs/30 s3, docs/31 s2): oasdiff compares contract/openapi.yaml at <base> with HEAD.
# Phones stay on old app versions for days in the field, so a breaking change to the API (a removed path or field, a
# narrowed request, a new value in a response enum an old app does not know, ...) fails CI unless the SAME push
#   - bumps info.version in contract/openapi.yaml, AND
#   - adds or changes a lead-approved request file docs/requests/contract-*.md that names the change.
# Then the breaking changes are reported as warnings and the gate passes. Non-breaking changes always pass.
# Usage: tools/ci/contract-breaking.sh <base-commit> [oasdiff binary]
# Owner: infra lane. The contract lane owns contract/; this script only reads it.
set -euo pipefail
base="${1:?base commit}"
oasdiff="${2:-oasdiff}"
spec=contract/openapi.yaml
cd "$(git rev-parse --show-toplevel)"
git cat-file -e "${base}^{commit}" 2>/dev/null || { echo "::error::base commit $base is not in this checkout"; exit 1; }

if ! git cat-file -e "$base:$spec" 2>/dev/null; then echo "contract: no $spec at ${base:0:7}; nothing to compare"; exit 0; fi
if git diff --quiet "$base" HEAD -- "$spec"; then echo "contract: $spec unchanged since ${base:0:7}"; exit 0; fi

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
git show "$base:$spec" > "$tmp/base.yaml"
"$oasdiff" breaking "$tmp/base.yaml" "$spec" --format json > "$tmp/breaking.json"

ver() { python3 - "$1" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"^info:\s*\n(?:[ \t]+.*\n)*?[ \t]+version:\s*['\"]?([^'\"\s]+)", text, re.M)
print(m.group(1) if m else "")
PY
}
old_v="$(ver "$tmp/base.yaml")"; new_v="$(ver "$spec")"
# Added or modified only: deleting an old request file is not an approval.
request="$(git diff --name-only --diff-filter=AM "$base" HEAD -- 'docs/requests/contract-*.md' | head -1)"

python3 - "$tmp/breaking.json" "$old_v" "$new_v" "$request" <<'PY'
import collections, json, sys
items, old_v, new_v, request = json.load(open(sys.argv[1])), sys.argv[2], sys.argv[3], sys.argv[4]
errors = [i for i in items if i.get("level") == 3]
warns = [i for i in items if i.get("level") == 2]
approved = bool(old_v and new_v and old_v != new_v and request)
print(f"contract: {len(errors)} breaking change(s), {len(warns)} warning(s); info.version {old_v} -> {new_v}; "
      f"request file: {request or 'none'}")
by_id = collections.Counter(i["id"] for i in errors)
for rule, n in by_id.most_common():
    print(f"  {n:5}  {rule}")
level = "warning" if approved else "error"
for i in errors[:40]:
    print(f"::{level}::{i.get('operation', '')} {i.get('path', '')}: {i['text']}")
if len(errors) > 40:
    print(f"... and {len(errors) - 40} more (oasdiff breaking <base> contract/openapi.yaml)")
if errors and not approved:
    print("::error::breaking contract change: old app versions in the field may fail. Either make it additive, or bump "
          "info.version AND add a lead-approved docs/requests/contract-<name>.md in the same push (docs/30 s3).")
    sys.exit(1)
print("contract: ok" + (" (breaking changes approved by version bump and request file)" if errors else ""))
PY
