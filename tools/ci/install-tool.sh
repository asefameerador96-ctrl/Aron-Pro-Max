#!/usr/bin/env bash
# Installs one pinned CI tool into a bin folder, verifying its SHA-256 before it is made executable (docs/31 s2).
# Pins: version and checksum are written here; a new version is a reviewed change to this file.
#   gitleaks  8.30.1  secret scan        (checksum from the release's gitleaks_8.30.1_checksums.txt)
#   oasdiff   1.33.0  contract breaking-change check (checksum from the release's checksums.txt)
#   syft      1.54.1  SBOM of the container images (checksum from the release's syft_1.54.1_checksums.txt)
#   squawk    2.67.0  PostgreSQL migration lint (the release publishes no checksum file: hash of the binary taken
#                     2026-10-07 and pinned here, so a later swap of the asset fails the install)
#   osv-scanner 2.6.0 dependency vulnerability scan (checksum from the release's osv-scanner_SHA256SUMS)
#   trivy     0.75.0  container image vulnerability scan (checksum from the release's trivy_0.75.0_checksums.txt)
# Usage: tools/ci/install-tool.sh <gitleaks|oasdiff|squawk|syft|osv-scanner|trivy> [bin-dir, default $RUNNER_TEMP/bin]
# Owner: infra lane.
set -euo pipefail
tool="${1:?tool name}"
bin="${2:-${RUNNER_TEMP:-.}/bin}"
mkdir -p "$bin"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

case "$tool" in
  gitleaks)
    url="https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_linux_x64.tar.gz"
    sha="551f6fc83ea457d62a0d98237cbad105af8d557003051f41f3e7ca7b3f2470eb"; kind=tgz ;;
  oasdiff)
    url="https://github.com/oasdiff/oasdiff/releases/download/v1.33.0/oasdiff_1.33.0_linux_amd64.tar.gz"
    sha="43a4e328e2d13ba1552d760aa68d2485c75c5621f309f6ff64ae895188345247"; kind=tgz ;;
  syft)
    url="https://github.com/anchore/syft/releases/download/v1.54.1/syft_1.54.1_linux_amd64.tar.gz"
    sha="c069905b391cc4c20a5ba65ad5c10be2a7ba074f8ea6ad203e24d14e303dad47"; kind=tgz ;;
  squawk)
    url="https://github.com/sbdchd/squawk/releases/download/v2.67.0/squawk-linux-x64"
    sha="03efe0e666b63bf33e2493693ee0aa9dbed07cbdcd20f7a8292245c1da9d790a"; kind=bin ;;
  osv-scanner)
    url="https://github.com/google/osv-scanner/releases/download/v2.6.0/osv-scanner_linux_amd64"
    sha="ca69b3d3cd08f889a49dc0a383122f71cc528b83803671df5fd874d97485b108"; kind=bin ;;
  trivy)
    url="https://github.com/aquasecurity/trivy/releases/download/v0.75.0/trivy_0.75.0_Linux-64bit.tar.gz"
    sha="c6e65abddb348e25f10549df887045629cf28cc72453cd1c63acb717316b3f3f"; kind=tgz ;;
  *) echo "::error::unknown tool $tool" >&2; exit 1 ;;
esac

for attempt in 1 2 3; do
  curl -fsSL --retry 3 -o "$tmp/download" "$url" && break
  [ "$attempt" -eq 3 ] && { echo "::error::cannot download $url" >&2; exit 1; }
  sleep $((attempt * 5))
done
echo "$sha  $tmp/download" | sha256sum -c --quiet - || { echo "::error::$tool checksum mismatch ($url)" >&2; exit 1; }
if [ "$kind" = tgz ]; then
  tar -xzf "$tmp/download" -C "$tmp" "$tool"
  install -m 0755 "$tmp/$tool" "$bin/$tool"
else
  install -m 0755 "$tmp/download" "$bin/$tool"
fi
echo "$bin/$tool"
