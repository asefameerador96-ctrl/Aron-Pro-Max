#!/usr/bin/env bash
# Chromium for the web e2e without hanging the Web job. Run from web/ (uses its @playwright/test).
# Why: `npx playwright install --with-deps chromium` hung in `apt-get update` (archive.ubuntu.com) until the job
# timeout in CI run 37691144739 and candidate zzk, so INT got no green run and the dev deploy was skipped.
#   1 browser download from the Playwright CDN, at most twice, 5 minutes each (a no-op when the browser cache is warm);
#   2 system libraries through apt ONLY when the downloaded browser misses one (the runner image normally has them
#     all), with apt network timeouts and a 5-minute cap, at most twice;
#   3 if the download or the libraries still fail: the runner's preinstalled Google Chrome (PW_CHROMIUM_PATH, read by
#     web/playwright.config.ts), with a warning. No Chrome either: fail with an error, never hang.
set -uo pipefail
STEP_TIMEOUT_S="${PW_STEP_TIMEOUT_S:-300}"
CACHE="${PLAYWRIGHT_BROWSERS_PATH:-$HOME/.cache/ms-playwright}"

use_chrome() {
  local chrome
  chrome="$(command -v google-chrome || command -v google-chrome-stable || true)"
  [ -n "$chrome" ] || { echo "::error::no Playwright Chromium ($1) and no preinstalled Google Chrome"; exit 1; }
  echo "::warning::e2e uses the runner's preinstalled Chrome (${chrome}): $1"
  echo "PW_CHROMIUM_PATH=${chrome}" >> "${GITHUB_ENV:-/dev/null}"
  exit 0
}

retry() {  # retry <what> <command...>: two attempts, each capped
  local attempt
  for attempt in 1 2; do
    timeout "$STEP_TIMEOUT_S" "${@:2}" && return 0
    echo "::warning::$1: attempt ${attempt} failed or timed out after ${STEP_TIMEOUT_S} s"
  done
  return 1
}

retry "playwright browser download" npx playwright install chromium || use_chrome "browser download failed twice"

missing="$(find "$CACHE" -maxdepth 3 -type f \( -name chrome -o -name headless_shell -o -name chrome-headless-shell \) \
  -perm -u+x -exec ldd {} \; 2>/dev/null | awk '/not found/ {print $1}' | sort -u | tr '\n' ' ')"
if [ -z "$missing" ]; then
  echo "playwright chromium ready (no system library missing)"
  exit 0
fi
echo "missing system libraries: ${missing}"
printf 'Acquire::Retries "3";\nAcquire::http::Timeout "30";\nAcquire::https::Timeout "30";\n' \
  | sudo tee /etc/apt/apt.conf.d/99aron-ci-timeouts >/dev/null
retry "playwright system libraries" npx playwright install-deps chromium || use_chrome "system libraries could not be installed"
echo "playwright chromium ready (system libraries installed)"
