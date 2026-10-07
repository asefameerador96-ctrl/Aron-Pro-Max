#!/usr/bin/env bash
# Post-deploy health gate on the public addresses phones and browsers use (Front Door, or the apps' own addresses in
# the TEST profile). Passes only when ALL of these hold:
#   GET  /v1/health        -> 200, the X-Aron-Api: 1 marker (an edge or WAF page has no marker) and, when an
#                             expected build is given, "build" == that commit (the NEW revision answers, not the old)
#   HEAD /v1/health        -> 200
#   GET  /v1/health/ready  -> 200 (the api reaches the database); the response is printed when it is not
#   GET  /login on the web host, when one is given -> 200 after redirects
# Retries for up to SMOKE_TIMEOUT_S: 600 s on a Container Apps host; 2700 s on Front Door, whose changes take up to
# 15 minutes each to reach the edge and queue behind each other (about 30 minutes for back-to-back changes, Learn:
# Front Door FAQ "estimated time for deploying"); until then the edge answers its own 404 "Page not found".
# Usage: infra/scripts/smoke.sh <api-host> [expected-build-sha] [web-host]
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
host="${1:?api host}"
want_build="${2:-}"
web_host="${3:-}"
default_timeout=600; case "$host" in *.azurefd.net) default_timeout=2700 ;; esac
deadline=$(( $(date +%s) + ${SMOKE_TIMEOUT_S:-$default_timeout} ))
url="https://${host}/v1/health"
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT
hdr="$work/hdr"; body="$work/body"

json_field() { python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get(sys.argv[2], ""))' "$1" "$2" 2>/dev/null || true; }
timed_out() { [ "$(date +%s)" -ge "$deadline" ]; }

# 1. liveness through the public address, answered by the expected build
while :; do
  code="$(curl -sS -o "$body" -D "$hdr" -w '%{http_code}' --max-time 20 "$url" || echo 000)"
  build=""
  if [ "$code" = "200" ] && grep -qi '^x-aron-api: *1' "$hdr"; then
    build="$(json_field "$body" build)"
    if [ -z "$want_build" ] || [ "$build" = "$want_build" ]; then break; fi
  fi
  if timed_out; then
    cat "$hdr" "$body" >&2 2>/dev/null || true
    [ -n "$build" ] && die "health gate failed: $url answers build '$build', expected '$want_build' (the new revision is not serving)"
    die "health gate failed: GET $url -> $code (no X-Aron-Api marker or not 200)"
  fi
  echo "waiting for the API ($code${build:+, build $build}; a new route, a revision switch or a cold start)..."; sleep 30
done
note "GET $url -> 200 with X-Aron-Api: 1${want_build:+, build $build}"; cat "$body"; echo
head_code="$(curl -sS -I -o /dev/null -w '%{http_code}' --max-time 20 "$url" || echo 000)"
[ "$head_code" = "200" ] || die "HEAD $url -> $head_code"
note "HEAD $url -> 200"

# 2. readiness: the api reaches its database (a short retry: a fresh revision may still be opening its pool)
ready_url="https://${host}/v1/health/ready"
for i in 1 2 3 4 5 6; do
  code="$(curl -sS -o "$body" -w '%{http_code}' --max-time 20 "$ready_url" || echo 000)"
  [ "$code" = "200" ] && break
  [ "$i" -eq 6 ] && { echo "---- response of $ready_url" >&2; cat "$body" >&2 || true; echo >&2; die "health gate failed: GET $ready_url -> $code"; }
  sleep 10
done
note "GET $ready_url -> 200"

# 3. the web login page (the web app scales to zero in dev, so allow a cold start)
if [ -n "$web_host" ]; then
  login_url="https://${web_host}/login"
  while :; do
    code="$(curl -sS -L -o "$body" -w '%{http_code}' --max-time 30 "$login_url" || echo 000)"
    [ "$code" = "200" ] && break
    timed_out && { head -c 2000 "$body" >&2 2>/dev/null || true; die "health gate failed: GET $login_url -> $code"; }
    echo "waiting for the web login page ($code)..."; sleep 20
  done
  note "GET $login_url -> 200"
fi
