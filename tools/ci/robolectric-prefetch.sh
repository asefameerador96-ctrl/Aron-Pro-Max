#!/usr/bin/env bash
# Pre-fetches the Robolectric android-all-instrumented runtime jars the Android unit tests use, into one folder, with
# retries and a SHA-256 check, so the test phase itself never goes to Maven (D-QA-CI-01: a Maven hiccup failed 11 test
# classes at class-level setup in runs 41754199003 and 41814202817). The folder is cached by CI (key = this file's hash)
# and handed to Gradle through ROBOLECTRIC_OFFLINE_DIR (build.gradle.kts sets robolectric.offline=true and
# robolectric.dependency.dir from it).
# The jars are those Robolectric 4.17 resolves for the API levels in use: 29, 30, 34 and 36 (the default for a test with no
# @Config is the targetSdk 36). A new Robolectric version or API level is a reviewed change to the table below;
# tools/ci/test_gates.py fails when the catalog version and this table drift apart.
# Usage: tools/ci/robolectric-prefetch.sh [dir, default $RUNNER_TEMP/robolectric-deps]
# Owner: qa-ci lane.
set -euo pipefail
dir="${1:-${RUNNER_TEMP:-.}/robolectric-deps}"
mkdir -p "$dir"
mirrors=(
  "https://repo1.maven.org/maven2"
  "https://maven-central.storage-download.googleapis.com/maven2"
)
# version  sha256
jars=(
  "10-robolectric-5803371-i7 700fb3bb88ff346969b955241b86d2f55d395488bf5df1e8eddfbd003980c78a"
  "11-robolectric-6757853-i7 7f4accae465a47e75e7591877b98e71fb982a908cffc4ba4b3ca0319cb6a0a10"
  "14-robolectric-10818077-i7 3fdac2d463f431c6b8d83b9d0ca739e795de75fffedee648c6761bd4bd864701"
  "16-robolectric-13921718-i7 16f1f751643d1d3d5592008846bbdfc1e57cff15e6ec303d26584de3b6ac25ec"
)
for entry in "${jars[@]}"; do
  read -r ver sha <<<"${entry}"
  file="${dir}/android-all-instrumented-${ver}.jar"
  if [ -f "${file}" ] && echo "${sha}  ${file}" | sha256sum -c --status; then
    echo "robolectric-prefetch: ${ver} already present and verified"; continue
  fi
  ok=0
  for attempt in 1 2 3 4; do
    for m in "${mirrors[@]}"; do
      url="${m}/org/robolectric/android-all-instrumented/${ver}/android-all-instrumented-${ver}.jar"
      if curl -fsSL --retry 2 --retry-delay 3 --connect-timeout 20 --max-time 300 -o "${file}.part" "${url}" \
         && echo "${sha}  ${file}.part" | sha256sum -c --status; then
        mv "${file}.part" "${file}"; ok=1; echo "robolectric-prefetch: ${ver} fetched (${m##*//}, attempt ${attempt})"; break 2
      fi
      rm -f "${file}.part"
    done
    sleep $((attempt * 5))
  done
  [ "${ok}" = 1 ] || { echo "::error::robolectric-prefetch: ${ver} could not be fetched and verified"; exit 1; }
done
ls -l "${dir}"
