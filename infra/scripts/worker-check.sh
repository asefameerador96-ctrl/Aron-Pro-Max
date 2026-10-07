#!/usr/bin/env bash
# Worker health after a deploy (lead 2026-10-07: "the worker without the signing key starts and stays up", proven, not
# assumed). The worker has no HTTP endpoint, so the health gate cannot reach it; this reads the platform instead:
#   1. the worker's latest revision runs the image this deploy built (by digest)
#   2. within WORKER_TIMEOUT_S, a replica of it is Running with every container Running and restartCount 0
#   3. after WORKER_HOLD_S more, the same replica still runs with restartCount 0 (not a crash loop caught between starts)
# Exit 0 when all hold; 1 otherwise, printing the replica states (never env values). Used non-blocking at first.
# Usage: infra/scripts/worker-check.sh <resource-group> <worker-app> <expected-image>
set -euo pipefail
RG="${1:?resource group}"; APP="${2:?worker app}"; IMAGE="${3:?expected image}"
timeout_s="${WORKER_TIMEOUT_S:-300}"; hold_s="${WORKER_HOLD_S:-90}"; poll_s="${WORKER_POLL_S:-10}"

rev="$(az containerapp show -g "$RG" -n "$APP" --query properties.latestRevisionName -o tsv)"
image="$(az containerapp revision show -g "$RG" -n "$APP" --revision "$rev" --query 'properties.template.containers[0].image' -o tsv)"
if [ "$image" != "$IMAGE" ]; then
  echo "worker: latest revision $rev runs $image, not this deploy's $IMAGE"; exit 1
fi

# One line: "<replica> <replica state> <all containers running: yes|no> <total restarts>" per replica.
states() {
  az containerapp replica list -g "$RG" -n "$APP" --revision "$rev" -o json | python3 -c '
import json, sys
for r in json.load(sys.stdin):
    p = r.get("properties", {})
    cs = p.get("containers", []) or []
    up = bool(cs) and all(c.get("runningState") == "Running" for c in cs)
    print(r.get("name"), p.get("runningState"), "yes" if up else "no", sum(int(c.get("restartCount") or 0) for c in cs))'
}
healthy() { awk '$2 == "Running" && $3 == "yes" && $4 == 0 { print $1; exit }'; }

deadline=$(( $(date +%s) + timeout_s )); replica=""; last=""
while :; do
  last="$(states || true)"
  replica="$(printf '%s\n' "$last" | healthy)"
  [ -n "$replica" ] && break
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "worker: no replica of $rev running with 0 restarts after ${timeout_s} s"; printf '  %s\n' "$last"; exit 1
  fi
  sleep "$poll_s"
done
echo "worker: $replica of $rev running, 0 restarts; holding ${hold_s} s"
sleep "$hold_s"
last="$(states || true)"
if printf '%s\n' "$last" | awk -v r="$replica" '$1 == r && $2 == "Running" && $3 == "yes" && $4 == 0 { ok = 1 } END { exit !ok }'; then
  echo "worker: $replica still running after ${hold_s} s, 0 restarts (image $IMAGE)"
else
  echo "worker: $replica did not stay up for ${hold_s} s"; printf '  %s\n' "$last"; exit 1
fi
