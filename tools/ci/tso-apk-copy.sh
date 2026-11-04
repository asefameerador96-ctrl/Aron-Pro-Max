#!/usr/bin/env bash
# tso-apk-copy.sh <signed dir> <version name> <version code> [<yyyy-mm-dd>]
# D-TSO-MAN-42 (screenshot 1): next to aron-tso-<version>-dev.apk the signed folder carries the owner's file name
# aron_tso_app_<dd_mm_yyyy>_v<versionCode>.apk, and SHA256SUMS is rewritten to list every apk. Run AFTER release-manifest.py
# (it accepts only the aron-<app>-<version>-dev.apk names). release-app.yml makes the same copy for the final-account release.
set -euo pipefail
dir="${1:?signed dir}"; vname="${2:?version name}"; vcode="${3:?version code}"; day="${4:-}"
src="${dir}/aron-tso-${vname}-dev.apk"
[[ -f "${src}" ]] || { echo "::error::${src} is missing"; exit 1; }
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
name="$(python3 "${here}/release-apks.py" --tso-copy-name "${vcode}" ${day:+"${day}"})"
cp "${src}" "${dir}/${name}"
(cd "${dir}" && sha256sum ./*.apk > SHA256SUMS)
echo "${name}"
