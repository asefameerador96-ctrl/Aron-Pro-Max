#!/usr/bin/env bash
# Post-deploy smoke test on the public API address phones use (Front Door, or the api app in the TEST profile):
#   GET  /v1/health  -> 200 and the X-Aron-Api: 1 marker (an edge or WAF page has no marker)
#   HEAD /v1/health  -> 200
# Retries for up to SMOKE_TIMEOUT_S: 600 s on a Container Apps host; 2700 s on Front Door, whose changes take up to
# 15 minutes each to reach the edge and queue behind each other (about 30 minutes for back-to-back changes, Learn:
# Front Door FAQ "estimated time for deploying"); until then the edge answers its own 404 "Page not found".
# Usage: infra/scripts/smoke.sh <front-door-host>
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
host="${1:?front door host}"
default_timeout=600; case "$host" in *.azurefd.net) default_timeout=2700 ;; esac
deadline=$(( $(date +%s) + ${SMOKE_TIMEOUT_S:-$default_timeout} ))
url="https://${host}/v1/health"
hdr="$(mktemp)"; trap 'rm -f "$hdr"' EXIT

while :; do
  code="$(curl -sS -o /tmp/aron-health.json -D "$hdr" -w '%{http_code}' --max-time 20 "$url" || echo 000)"
  if [ "$code" = "200" ] && grep -qi '^x-aron-api: *1' "$hdr"; then
    note "GET $url -> 200 with X-Aron-Api: 1"; cat /tmp/aron-health.json; echo
    head_code="$(curl -sS -I -o /dev/null -w '%{http_code}' --max-time 20 "$url" || echo 000)"
    [ "$head_code" = "200" ] || die "HEAD $url -> $head_code"
    note "HEAD $url -> 200"
    exit 0
  fi
  [ "$(date +%s)" -ge "$deadline" ] && { cat "$hdr" >&2 || true; die "smoke test failed: GET $url -> $code (no X-Aron-Api marker or not 200)"; }
  echo "waiting for the API ($code; a new route or a cold start from zero replicas)..."; sleep 30
done
