#!/usr/bin/env bash
# Steps the infra lane's CI workflow calls (docs/24 s13.2, web gate). Run from anywhere: bash web/scripts/ci.sh <step>.
#   install    npm ci
#   generate   regenerate src/contract/openapi.d.ts and fail if it differs from the committed file
#   lint       eslint (includes the hard-coded-text rule)
#   typecheck  tsc --noEmit
#   test       vitest (unit, drift and rule tests)
#   build      next build (runs lint first via prebuild)
#   e2e        Playwright smoke tests against the contract mock (needs build)
#   all        install, generate, lint, typecheck, test, build, e2e
set -euo pipefail
cd "$(dirname "$0")/.."

step_install()   { npm ci; }
step_generate()  { npm run gen:contract; git diff --exit-code -- src/contract/openapi.d.ts || { echo "openapi.d.ts is stale: run 'npm run gen:contract' and commit" >&2; exit 1; }; }
step_lint()      { npm run lint; }
step_typecheck() { npm run typecheck; }
step_test()      { npm test; }
step_build()     { npm run build; }
step_e2e()       { CI="${CI:-}" npx playwright test; }

case "${1:-all}" in
  install) step_install ;;
  generate) step_generate ;;
  lint) step_lint ;;
  typecheck) step_typecheck ;;
  test) step_test ;;
  build) step_build ;;
  e2e) step_e2e ;;
  all) step_install; step_generate; step_lint; step_typecheck; step_test; step_build; step_e2e ;;
  *) echo "unknown step: $1" >&2; exit 2 ;;
esac
