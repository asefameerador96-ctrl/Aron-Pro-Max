# brand to integrator: re-record five goldens after the logo lands

From: stream J5 (docs), 2026-10-19. Owner of the recording: the integrator (lead). Goldens are recorded on the Linux CI runner
only; a golden recorded on Windows fails CI. Nothing here blocks the push: the Android pair is expected to fail the first CI run
until it is re-recorded; the web trio will probably still pass (inside the tolerance) but is strictly stale.

## Which goldens and why

| Golden | Why it is stale | Stream |
|---|---|---|
| `android/ui-screenshots/src/test/screenshots/login_bn_light.png` | `ScreenshotTests.login_bn_light` draws `LoginContent`, hence `BrandBlock`; the drawn peak glyph on the plate became the Aron logo on a white 72 dp plate | J2 |
| `android/ui-screenshots/src/test/screenshots/login_bn_sunlight.png` | same screen in Sunlight (the white plate sits on a white card, so only the logo shows) | J2 |
| `web/e2e/config-tiers.spec.ts-snapshots/config-home-tier-A-chromium-linux.png` | `config-tiers.spec.ts` line 54 screenshots the 1366 x 900 page of `/admin/config`, which includes the sidebar brand row; the round red-A monogram became the logo on a white round plate | J3 |
| `.../config-home-tier-B-chromium-linux.png` | same | J3 |
| `.../config-home-tier-C-chromium-linux.png` | same | J3 |

Not stale: no golden renders Enrolment or OTP (J2); the six `permissions-tier-*` and `radius-map-tier-*` goldens are element
shots without the shell (J3). The `close_*` and `parity_*` login and OTP renders in `CloseShot.kt` are look captures written to
`build/kitshots`, not tracked goldens (J2). The changed area of the web trio is about 0.1 percent of the pixels against
`maxDiffPixelRatio` 0.03 (J3). Neither stream ran a golden task.

## Android (Roborazzi)

1. In `android/ui-screenshots/build.gradle.kts` set `val bootstrapRecord = true` (or pass `-Paron.screenshots.recordTo=<dir>`)
   and push to the branch whose goldens are being fixed.
2. The Android job records into `android/ui-screenshots/build/test-results/screenshots-recorded/`, and
   `ScreenshotTests.recordingRunNeverPasses` fails on purpose so the run does not look green. The failed run uploads the artifact
   `android-test-reports` (failure only, 7 days).
3. Download it and copy only the two login PNGs into `android/ui-screenshots/src/test/screenshots/`. Look at them: the Aron logo
   inside the 72 dp white squircle on the gradient card (light), and on a white card (Sunlight). If other PNGs differ,
   something unrelated changed: stop and find out.
4. Set `bootstrapRecord = false`, commit, push. The next run must be green. If android-amo, android-tso or ui-kit re-record the
   same two files meanwhile, merge theirs first.

## Web (Playwright)

1. Edit the trailing comment line of `.github/workflows/web-snapshots.yml` and push (a push that touches that file records the
   goldens on that branch), or run `workflow_dispatch` on the default branch with spec `e2e/config-tiers.spec.ts` (the default).
2. Download the artifact `web-snapshots` and copy only the three `config-home-tier-*-chromium-linux.png` files over
   `web/e2e/config-tiers.spec.ts-snapshots/`. If the other six differ, something unrelated changed.
3. Look at them (the sidebar shows the logo on a white round plate), commit. Never commit `chromium-win32` PNGs.

Both procedures were read from the build files and workflows on 2026-10-19; none was run for this change.
