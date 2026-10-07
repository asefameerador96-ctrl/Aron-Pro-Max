#!/usr/bin/env bash
# Downloads the Application Insights Java agent pinned in infra/docker/backend.Dockerfile (AI_AGENT_VERSION and
# AI_AGENT_SHA256 there are the single source) into <dir>/agent/applicationinsights-agent.jar, with retries, and checks
# the SHA-256. The image builds pass <dir> as the named build context "agent" (docker build --build-context agent=<dir>),
# which replaces the Dockerfile's own ADD stage. Why: BuildKit's ADD does one request without retry, and Maven Central
# sometimes answers a runner with other bytes (an error or rate-limit page): "digest mismatch" on 2026-10-07, run 361.
# Usage: infra/scripts/fetch-ai-agent.sh <dir>
set -euo pipefail
dir="${1:?target directory}"
df="$(dirname "$0")/../docker/backend.Dockerfile"
version="$(sed -n 's/^ARG AI_AGENT_VERSION=//p' "$df")"
sha="$(sed -n 's/^ARG AI_AGENT_SHA256=//p' "$df")"
if [ -z "$version" ] || [ -z "$sha" ]; then
  echo "::error::AI_AGENT_VERSION or AI_AGENT_SHA256 not found in $df" >&2; exit 1
fi
# Two sources, tried in order on every attempt: Maven Central, then Microsoft's GitHub release (the download Learn
# documents). Maven Central rate-limited a deploy runner with 429 on every attempt (2026-10-07, deploy run 144); the
# pinned SHA-256 decides either way, so a second source cannot change what goes into the image.
urls=(
  "https://repo1.maven.org/maven2/com/microsoft/azure/applicationinsights-agent/${version}/applicationinsights-agent-${version}.jar"
  "https://github.com/microsoft/ApplicationInsights-Java/releases/download/${version}/applicationinsights-agent-${version}.jar"
)
mkdir -p "$dir/agent"
out="$dir/agent/applicationinsights-agent.jar"
for attempt in 1 2 3 4 5; do
  for url in "${urls[@]}"; do
    if curl -fsSL --retry 2 --retry-all-errors --retry-delay 5 --max-time 300 -o "$out.part" "$url" \
       && echo "${sha}  $out.part" | sha256sum -c --quiet - 2>/dev/null; then
      mv "$out.part" "$out"
      host="${url#https://}"
      echo "applicationinsights-agent ${version}: sha256 ok from ${host%%/*} (attempt ${attempt})"
      exit 0
    fi
    echo "::warning::applicationinsights-agent ${version}: download or checksum failed from ${url} (attempt ${attempt})"
    rm -f "$out.part"
  done
  sleep $(( attempt * 10 ))
done
echo "::error::applicationinsights-agent ${version}: no download matched sha256 ${sha} after 5 attempts" >&2
exit 1
