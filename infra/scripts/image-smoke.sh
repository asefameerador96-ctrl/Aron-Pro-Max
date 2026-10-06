#!/usr/bin/env bash
# Builds the deploy images and proves the backend image's runtime contract WITHOUT Azure (CI job "Container images"):
#   1. backend image from the Gradle distribution (infra/docker/backend.Dockerfile)
#   2. ARON_ROLE=migrate against PostgreSQL 16 exits 0, twice (Flyway is idempotent; V0001 needs btree_gist)
#   3. ARON_ROLE=api answers GET /v1/health and GET /v1/health/ready with 200 and X-Aron-Api: 1
#   4. ARON_ROLE=worker is still running after 15 s (Container Apps would restart an exiting worker in a loop)
#   5. web image (infra/docker/web.Dockerfile) builds and serves on 3000, when web/package.json exists
# The Application Insights agent runs without a connection string here (no telemetry, must not crash).
# Usage: infra/scripts/image-smoke.sh   (needs docker, a JDK, and PostgreSQL at ARON_TEST_PG_JDBC, host network)
# shellcheck source=lib.sh
source "$(dirname "$0")/lib.sh"
cd "$(dirname "$0")/../.." || exit 1

PG="${ARON_TEST_PG_JDBC:-jdbc:postgresql://localhost:5432/aron_test?user=aron_test}"
TAG="${IMAGE_TAG:-ci}"
tmp="$(mktemp -d)"
containers=()
cleanup() {
  for c in "${containers[@]}"; do
    echo "---- logs of $c"; docker logs "$c" 2>&1 | tail -40 || true
    docker rm -f "$c" >/dev/null 2>&1 || true
  done
  rm -rf "$tmp"
}
trap cleanup EXIT

wait_http() { # url seconds -> prints status code, writes headers to $tmp/h
  local end=$(( $(date +%s) + $2 )) code=000
  while [ "$(date +%s)" -lt "$end" ]; do
    code="$(curl -s -o "$tmp/body" -D "$tmp/h" -w '%{http_code}' --max-time 5 "$1" || true)"
    [ "$code" != 000 ] && [ "${code:0:1}" != 5 ] && break
    sleep 2
  done
  echo "$code"
}

note "backend image"
./gradlew --console=plain -q :backend:app:installDist
docker build --pull -q -f infra/docker/backend.Dockerfile -t "aron-backend:$TAG" backend/app/build/install/aron-backend

note "migrate role (twice)"
for run in 1 2; do
  docker run --rm --network host -e ARON_ROLE=migrate -e ARON_ENV=dev -e "ARON_DB_URL=$PG" "aron-backend:$TAG" \
    || die "ARON_ROLE=migrate failed (run $run)"
done

note "api role"
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$tmp/jwt.pem" 2>/dev/null
api="$(docker run -d --network host -e ARON_ROLE=api -e ARON_ENV=dev -e PORT=8080 -e "ARON_DB_URL=$PG" \
  -e "ARON_JWT_SIGNING_KEY=$(cat "$tmp/jwt.pem")" -e ARON_JWT_KID=ci -e ARON_BUILD="$TAG" "aron-backend:$TAG")"
containers+=("$api")
for path in /v1/health /v1/health/ready; do
  code="$(wait_http "http://localhost:8080$path" 90)"
  [ "$code" = 200 ] || die "GET $path -> $code"
  grep -qi '^x-aron-api: *1' "$tmp/h" || die "GET $path has no X-Aron-Api: 1 header"
  note "GET $path -> 200 with X-Aron-Api: 1"
done

note "worker role"
worker="$(docker run -d --network host -e ARON_ROLE=worker -e ARON_ENV=dev -e "ARON_DB_URL=$PG" \
  -e "ARON_JWT_SIGNING_KEY=$(cat "$tmp/jwt.pem")" -e ARON_JWT_KID=ci "aron-backend:$TAG")"
containers+=("$worker")
sleep 15
[ "$(docker inspect -f '{{.State.Running}}' "$worker")" = true ] || die "the worker exited (Container Apps would restart it in a loop)"
note "worker still running after 15 s"

if [ -f web/package.json ]; then
  note "web image"
  docker build --pull -q -f infra/docker/web.Dockerfile -t "aron-web:$TAG" web
  web="$(docker run -d --network host -e PORT=3000 -e HOSTNAME=0.0.0.0 -e ARON_ENV=dev \
    -e ARON_API_BASE_URL=http://localhost:8080 -e "ARON_SESSION_SECRET=$(openssl rand -hex 24)" "aron-web:$TAG")"
  containers+=("$web")
  code="$(wait_http "http://localhost:3000/" 60)"
  case "$code" in 2??|3??|401|403) note "web answers GET / -> $code" ;; *) die "web GET / -> $code" ;; esac
fi
note "images pass the runtime smoke test"
