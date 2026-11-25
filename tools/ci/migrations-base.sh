#!/usr/bin/env bash
# Which commit does the migration gate compare with? (D-CI-MIGRATIONS-BASE-01)
#   "What is shipped is what the integration branch has": on a LANE branch (a push or a manual run of any branch that is not main and
#   not the integration branch) the base is the merge-base of the commit with origin/<integration branch>. A lane that has merged
#   the integration branch since the last green run therefore compares with what is shipped NOW, not with a stale last-green commit
#   (a base that never moved kept lane/sr-targets-server red forever: "V0230..V0233 was shipped at 6af9be0 and is gone at HEAD").
#   Everything else (the integration branch itself, main, pull requests) keeps the gate base given as the argument.
# Never falls back silently: a lane run without origin/<integration branch> or without the history for the merge-base FAILS loudly.
# Usage: GITHUB_EVENT_NAME=push GITHUB_REF=refs/heads/<ref> GITHUB_SHA=<sha> INTEGRATION_BRANCH=<branch> tools/ci/migrations-base.sh <gate-base>
# Prints one line per base on stdout: "<base-sha> <reason>" (a lane run can have several merge-bases; the gate runs against every one). Owner: infra lane (the lead's T1).
set -euo pipefail
gate_base="${1:?gate base (the previous comparison base for the integration branch, main and pull requests)}"
event="${GITHUB_EVENT_NAME:-}"
ref="${GITHUB_REF:-}"
sha="${GITHUB_SHA:-$(git rev-parse HEAD)}"
integ="${INTEGRATION_BRANCH:?INTEGRATION_BRANCH}"

lane=false
case "${event}" in push|workflow_dispatch)
  case "${ref}" in
    refs/heads/main|"refs/heads/${integ}") ;;
    refs/heads/*) lane=true ;;
  esac ;;
esac

if [ "${lane}" != true ]; then
  echo "${gate_base} gate base (${event:-unknown event} on ${ref:-unknown ref}: the integration branch, main and pull requests keep the previous-head rule)"
  exit 0
fi

if [ "$(git rev-parse --is-shallow-repository)" = true ]; then
  git fetch -q --unshallow origin 2>/dev/null || true
  [ "$(git rev-parse --is-shallow-repository)" = false ] || { echo "::error::shallow clone: the history for the merge-base with origin/${integ} is missing (the checkout needs fetch-depth 0)" >&2; exit 1; }
fi
git cat-file -e "${sha}^{commit}" 2>/dev/null \
  || { echo "::error::commit ${sha} is not in this checkout (GITHUB_SHA missing from the clone); refusing to guess" >&2; exit 1; }
integ_ref="refs/remotes/origin/${integ}"
git rev-parse -q --verify "${integ_ref}" >/dev/null \
  || { echo "::error::${integ_ref} is not in this checkout: a lane run needs it to know what is shipped; refusing to guess" >&2; exit 1; }
# --all: with criss-cross history (a train merged a lane commit into the integration branch, the lane merged an older integration
# commit) there are SEVERAL best common ancestors, and one of them may lack a migration the other shipped. Picking one could judge an
# edited shipped migration ok, so EVERY base is printed (one per line) and the gate must pass against each of them.
mapfile -t mbs < <(git merge-base --all "${integ_ref}" "${sha}")
[ "${#mbs[@]}" -gt 0 ] || { echo "::error::no merge-base between ${sha} and ${integ_ref} (unrelated history?); refusing to guess" >&2; exit 1; }
for mb in "${mbs[@]}"; do
  echo "${mb} lane branch ${ref#refs/heads/}: merge-base with origin/${integ} (what the integration branch has shipped; ${#mbs[@]} merge-base(s), the gate runs against each)"
done
