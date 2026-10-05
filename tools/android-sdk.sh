#!/usr/bin/env bash
# tools/android-sdk.sh: install the Android SDK pieces the Aron Gradle build needs, headless.
#
# Owner: tools/ lane. Used by: Claude build sessions (Linux containers), CI (.github/workflows/ci.yml
# uses android-actions/setup-android instead, with the same package list), and any Linux laptop.
#
# Usage:
#   tools/android-sdk.sh                      # installs into /opt/android-sdk (or $ANDROID_HOME if set)
#   ANDROID_HOME=$HOME/android-sdk tools/android-sdk.sh
#   source <(tools/android-sdk.sh --print-env)   # just print the export lines
#
# What it installs (keep in sync with gradle/libs.versions.toml and docs/24-build-spec.md section 2):
#   cmdline-tools;latest  (23.0 at 2026-10-05; archive commandlinetools-linux-16111833_latest.zip)
#   platform-tools
#   platforms;android-37.0   (compileSdk 37, the highest API level AGP 9.4 supports)
#   build-tools;36.0.0       (the default build-tools of AGP 9.4.x)
#
# Network: everything comes from dl.google.com over HTTPS. Behind the Claude agent proxy, curl and the JVM
# already trust the proxy CA (see /root/.ccr/README.md); this script never disables TLS verification.
# Time: about 2 to 4 minutes on a container with a fast link; the download is about 330 MB.
set -euo pipefail

SDK_ROOT="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
CMDLINE_ZIP="commandlinetools-linux-16111833_latest.zip"
CMDLINE_URL="https://dl.google.com/android/repository/${CMDLINE_ZIP}"
PACKAGES=("platform-tools" "platforms;android-37.0" "build-tools;36.0.0")

if [[ "${1:-}" == "--print-env" ]]; then
  echo "export ANDROID_HOME=${SDK_ROOT}"
  echo "export ANDROID_SDK_ROOT=${SDK_ROOT}"
  echo "export PATH=\"${SDK_ROOT}/cmdline-tools/latest/bin:${SDK_ROOT}/platform-tools:\$PATH\""
  exit 0
fi

command -v java >/dev/null || { echo "java (JDK 17+) is required" >&2; exit 1; }
command -v unzip >/dev/null || { echo "unzip is required (apt-get install -y unzip)" >&2; exit 1; }

mkdir -p "${SDK_ROOT}/cmdline-tools"
if [[ ! -x "${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager" ]]; then
  tmp="$(mktemp -d)"
  echo "Downloading ${CMDLINE_URL}"
  curl -fsSL --retry 3 -o "${tmp}/${CMDLINE_ZIP}" "${CMDLINE_URL}"
  unzip -q "${tmp}/${CMDLINE_ZIP}" -d "${tmp}"
  rm -rf "${SDK_ROOT}/cmdline-tools/latest"
  mv "${tmp}/cmdline-tools" "${SDK_ROOT}/cmdline-tools/latest"
  rm -rf "${tmp}"
fi

SDKMANAGER="${SDK_ROOT}/cmdline-tools/latest/bin/sdkmanager"
# sdkmanager is a JVM tool: it honours JAVA_TOOL_OPTIONS (proxy host, port and the truststore with the proxy CA).
# cmdline-tools 23.0 prints "sdkmanager is deprecated, Android CLI will be used instead" and delegates to the new
# `android sdk` command; `--licenses` is a no-op there and the install itself writes licenses/android-sdk-license,
# which is the file the Android Gradle plugin checks. Do NOT pipe `yes` into it under `set -o pipefail`: `yes` dies
# of SIGPIPE (exit 141) and aborts the script (this happened on 2026-10-05, see docs/24-build-spec-verification.md).
"${SDKMANAGER}" --sdk_root="${SDK_ROOT}" --install "${PACKAGES[@]}"
if [[ ! -f "${SDK_ROOT}/licenses/android-sdk-license" ]]; then
  # Older cmdline-tools (before 20.0) need the explicit licence step; tolerate SIGPIPE from `yes`.
  set +o pipefail
  yes | "${SDKMANAGER}" --sdk_root="${SDK_ROOT}" --licenses >/dev/null || true
  set -o pipefail
fi
test -f "${SDK_ROOT}/licenses/android-sdk-license" || { echo "licence file missing after install" >&2; exit 1; }

echo
echo "Android SDK ready at ${SDK_ROOT}"
"${SDKMANAGER}" --sdk_root="${SDK_ROOT}" --list_installed 2>/dev/null | sed -n '1,20p'
echo
echo "Add to your shell (or write sdk.dir to local.properties, which is git-ignored):"
echo "  export ANDROID_HOME=${SDK_ROOT}"
echo "  echo sdk.dir=${SDK_ROOT} > local.properties"
