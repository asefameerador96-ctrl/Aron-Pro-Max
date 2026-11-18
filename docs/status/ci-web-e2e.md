# Lane CI-E2E status

Web e2e split into 5 Playwright shards (D-CI-E2E-01). Before: one e2e step of 23 to 27 minutes in a 30-minute job.

## Run 1 (ci run 44227267913, sha 49005d3): green

| Job | Wall time |
|---|---|
| Web (lint, types, tests, build) | 2.4 min |
| Web e2e shard 1 / 2 / 3 / 4 / 5 | 6.3 / 10.5 / 5.2 / 5.8 / 4.5 min (job); Playwright time 5.1 / 8.9 / 4.1 / 4.6 / 3.1 min |
| Web (lint, types, tests, build, e2e) (aggregator) | 0.1 min |
| Whole web gate, first start to aggregator end | about 13 min |

Combined: 372 passed, 0 flaky, 0 failed, 0 skipped; 372 of 372 tests (the build job counts the list with `playwright test --list`; the aggregator fails if the shards do not add up to it). Per shard: 78, 72, 76, 72, 74.

## Run 2

Triggered by this status line; timings to follow.
