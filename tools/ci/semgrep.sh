#!/usr/bin/env bash
# Static analysis gate (docs/31 s2): Semgrep OSS, image pinned by digest, registry rule packs for Kotlin, TypeScript,
# React, Next.js, GitHub Actions and Dockerfiles (ERROR and WARNING severities). Paths skipped: .semgrepignore.
#   tools/ci/semgrep.sh <base-commit>   fails only on findings NEW since <base-commit> (baseline mode), so existing
#                                       findings never turn a push red; they are listed as warnings by the run below
#   tools/ci/semgrep.sh                 full scan, report only (exit 0), every finding as a warning annotation
# Needs Docker and network access to semgrep.dev for the rule packs. SEMGREP_DOCKER_ARGS adds docker run options
# (for example a proxy and its CA bundle on a developer machine). Owner: infra lane.
set -euo pipefail
cd "$(dirname "$0")/../.."
IMAGE="semgrep/semgrep:1.179.0@sha256:93963d9295a366f59e4850127b1550400ee7b388f04fe144e4a1f6325d96e01b"
base="${1:-}"
rules=(--config p/kotlin --config p/typescript --config p/react --config p/nextjs --config p/github-actions --config p/dockerfile)
out="$(mktemp)"; trap 'rm -f "$out"' EXIT
args=(scan "${rules[@]}" --metrics=off --disable-version-check --severity ERROR --severity WARNING --json --output /out/semgrep.json)
[ -n "$base" ] && args+=(--baseline-commit "$base" --error)
set +e
# The runner owns the checkout, the container runs as root: mark it safe for git (baseline mode reads history).
# shellcheck disable=SC2086 # SEMGREP_DOCKER_ARGS is a list of options on purpose
docker run --rm ${SEMGREP_DOCKER_ARGS:-} -v "$PWD:/src" -v "$(dirname "$out"):/out" -w /src \
  -e GIT_CONFIG_COUNT=1 -e GIT_CONFIG_KEY_0=safe.directory -e GIT_CONFIG_VALUE_0=/src \
  "$IMAGE" semgrep "${args[@]}"
rc=$?
set -e
mv "$(dirname "$out")/semgrep.json" "$out" 2>/dev/null || true
python3 - "$out" "${base:+new}" <<'PY'
import json, sys
try:
    d = json.load(open(sys.argv[1]))
except Exception:
    sys.exit(0)
kind = "error" if len(sys.argv) > 2 and sys.argv[2] == "new" else "warning"
for r in d.get("results", []):
    msg = " ".join(r["extra"].get("message", "").split())[:300]
    print(f"::{kind} file={r['path']},line={r['start']['line']}::semgrep {r['check_id'].split('.')[-1]}: {msg}")
for e in d.get("errors", []):
    print(f"::warning::semgrep could not fully analyse: {str(e.get('message', e))[:200]}")
print(f"semgrep: {len(d.get('results', []))} finding(s){' new since the base' if kind == 'error' else ''}")
PY
if [ -n "$base" ]; then
  # 1 = findings (with --error); anything else non-zero is a tool failure, which must not pass silently either.
  exit "$rc"
fi
exit 0
