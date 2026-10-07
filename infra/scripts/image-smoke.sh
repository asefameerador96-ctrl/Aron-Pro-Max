#!/usr/bin/env bash
# Builds the deploy images and proves the backend image's runtime contract WITHOUT Azure (CI job "Container images"):
#   1. backend image from the Gradle distribution (infra/docker/backend.Dockerfile)
#   2. ARON_ROLE=migrate against PostgreSQL 16 exits 0, twice (Flyway is idempotent; V0001 needs btree_gist)
#   3. infra/sql/runtime-logins.sql (the dblogins job's SQL) creates the per-app logins, twice (idempotent)
#   4. ARON_ROLE=api, connected as app_api, answers GET /v1/health and GET /v1/health/ready with 200 and X-Aron-Api: 1
#   5. ARON_ROLE=worker, connected as app_jobs, is still running after 15 s with no "permission denied" in its log
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
agent_ctx="$(mktemp -d)"
infra/scripts/fetch-ai-agent.sh "$agent_ctx"
docker build --pull -q --build-context "agent=$agent_ctx" -f infra/docker/backend.Dockerfile -t "aron-backend:$TAG" \
  backend/app/build/install/aron-backend

note "migrate role (twice)"
for run in 1 2; do
  docker run --rm --network host -e ARON_ROLE=migrate -e ARON_ENV=dev -e "ARON_DB_URL=$PG" "aron-backend:$TAG" \
    || die "ARON_ROLE=migrate failed (run $run)"
done

note "per-app database logins (infra/sql/runtime-logins.sql, twice)"
# Same SQL and the same psql image as the dblogins job in Azure. Throwaway passwords: the CI server trusts local
# connections, so the LOGIN identity (and with it every grant) is what this proves, not the password check.
PSQL_IMAGE="postgres:16-alpine@sha256:721873c34ceb9f8d8fc265984940dc982404c105f19ad51be9fdc5970a6080ea"
pg_uri="${PG#jdbc:}"
pw() { printf 'ci-%s-pw-0000000000000000' "$1"; }
for run in 1 2; do
  docker run --rm --network host -v "$PWD/infra/sql:/sql:ro" -e "ARON_PW_APP_API=$(pw app_api)" \
    -e "ARON_PW_APP_WORKER=$(pw app_worker)" -e "ARON_PW_APP_JOBS=$(pw app_jobs)" \
    --entrypoint psql "$PSQL_IMAGE" "$pg_uri" -X -q -f /sql/runtime-logins.sql || die "runtime-logins.sql failed (run $run)"
done
as_login() { # login -> the test JDBC URL with user replaced by the login
  python3 -c 'import sys; b, _, q = sys.argv[1].partition("?"); p = [x for x in q.split("&") if x and not x.startswith(("user=", "password="))]; print(b + "?" + "&".join(p + ["user=" + sys.argv[2], "password=" + sys.argv[3]]))' "$PG" "$1" "$(pw "$1")"
}
PG_API="$(as_login app_api)"
PG_JOBS="$(as_login app_jobs)"

note "api role (as app_api)"
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$tmp/jwt.pem" 2>/dev/null
api="$(docker run -d --network host -e ARON_ROLE=api -e ARON_ENV=dev -e PORT=8080 -e "ARON_DB_URL=$PG_API" \
  -e "ARON_JWT_SIGNING_KEY=$(cat "$tmp/jwt.pem")" -e ARON_JWT_KID=ci -e ARON_BUILD="$TAG" "aron-backend:$TAG")"
containers+=("$api")
for path in /v1/health /v1/health/ready; do
  code="$(wait_http "http://localhost:8080$path" 90)"
  [ "$code" = 200 ] || die "GET $path -> $code"
  grep -qi '^x-aron-api: *1' "$tmp/h" || die "GET $path has no X-Aron-Api: 1 header"
  note "GET $path -> 200 with X-Aron-Api: 1"
done

note "worker role (as app_jobs)"
worker="$(docker run -d --network host -e ARON_ROLE=worker -e ARON_ENV=dev -e "ARON_DB_URL=$PG_JOBS" \
  -e "ARON_JWT_SIGNING_KEY=$(cat "$tmp/jwt.pem")" -e ARON_JWT_KID=ci "aron-backend:$TAG")"
containers+=("$worker")
sleep 15
[ "$(docker inspect -f '{{.State.Running}}' "$worker")" = true ] || die "the worker exited (Container Apps would restart it in a loop)"
note "worker still running after 15 s"
for c in "$api" "$worker"; do
  if docker logs "$c" 2>&1 | grep -qi 'permission denied'; then die "a least-privilege login lacks a grant (see the logs below)"; fi
done

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
