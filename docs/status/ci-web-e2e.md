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

## Run 2 (ci run 44231175158, workflow_dispatch, sha 1e4eb3b): green

A docs-only push starts no ci run (the `on.push.paths` filter), so run 2 was started with `workflow_dispatch` on the branch.

| Job | Wall time |
|---|---|
| Web (lint, types, tests, build) | 2.6 min |
| Web e2e shard 1 / 2 / 3 / 4 / 5 | 6.1 / 10.3 / 5.3 / 5.5 / 3.9 min (job); Playwright time 4.8 / 8.9 / 3.9 / 4.3 / 2.8 min |
| Web (lint, types, tests, build, e2e) (aggregator) | 0.2 min |
| Whole web gate | about 13 min |

Combined: 372 passed, 0 flaky, 0 failed, 0 skipped; 372 of 372 tests.

## Second item: LiteMapScreenTest flake (D-CI-E2E-02)

Cause read from the attempt-1 log of run 44226960956: the Compose test recomposed on an IO worker. Fix: `imageDispatcher` parameter (default IO), `Dispatchers.Unconfined` in the test. 30 of 30 local runs green (`--no-build-cache`, 4 CPU hogs); the push run 44231154596 and the Android job of run 44231175158 are green.

## Not verified

The full suite was not run sharded twice locally (CI runs 1 and 2 are the two proofs); the flake was never reproduced before the fix; whether one shard count (5) stays enough as the suite grows is only known from these two runs (shard 2, with the screenshot specs, is the long one at about 10 minutes).

## Other places that hop to Dispatchers.IO and feed Compose state (list for later, nothing changed)

The LiteMapScreen race (D-CI-E2E-02) needs a composable that does `withContext(Dispatchers.IO)` and then writes state, under a Compose test rule. Same shape elsewhere in main code:

- `android/feature-amo/.../OutletFormKit.kt:120` (decodes a photo in a `produceState`-style block)
- `android/feature-auth/.../EnrolmentScreen.kt:197` (status read in a composable effect)
- `android/app-tso/.../TicketsHost.kt:508` (`Thumbnail`, `produceState` decoding a file)
- `android/app-sr/.../SrApp.kt:313` and `:422` (`LaunchedEffect` reads, device health and the Wi-Fi-only setting)

Only a test that composes these under `createComposeRule` is exposed; `feature-amo`, `feature-auth` and `feature-tso` have such tests today, `app-sr` and `app-tso` have none. Fix shape if one flakes: inject the dispatcher as a defaulted parameter and pass `Dispatchers.Unconfined` in the test, as LiteMapScreen now does.

## Follow-ups of the lead's review (D-CI-E2E-03)

Count step fails by itself, comment corrected, artifact retention 3 days, `national-shots` skip cannot fire today (goldens are committed). Waiting for "116 landed" to merge the integration branch and check ci.
