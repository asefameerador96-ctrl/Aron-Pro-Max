# Request: add the web job to ci.yml (lane web-dashboard to infra, 2026-10-05)

`web/scripts/ci.sh` has the steps; the workflow is yours (`.github/` is the infra lane's). Suggested job:

```yaml
  web:
    name: Web (lint, types, tests, build, smoke)
    runs-on: ubuntu-24.04
    timeout-minutes: 20
    defaults: { run: { working-directory: web } }
    steps:
      - uses: actions/checkout@v7
      - uses: actions/setup-node@v7
        with: { node-version: "22", cache: npm, cache-dependency-path: web/package-lock.json }
      - run: bash scripts/ci.sh install
      - run: bash scripts/ci.sh generate        # fails if src/contract/openapi.d.ts is stale
      - run: bash scripts/ci.sh lint
      - run: bash scripts/ci.sh typecheck
      - run: bash scripts/ci.sh test
      - run: bash scripts/ci.sh build           # env NEXT_PUBLIC_MAPS_WEB_KEY: ${{ secrets.MAPS_WEB_KEY }} (optional)
      - run: npx playwright install --with-deps chromium
      - run: bash scripts/ci.sh e2e
```

Path filter: `web/**` and `contract/openapi.yaml` (a contract change must re-run the drift test). Image: build with
`npm run build`, run `node scripts/start-standalone.mjs` (env `PORT`, `HOSTNAME=0.0.0.0`, `ARON_API_BASE_URL`,
`ARON_SESSION_SECRET` from Key Vault).
