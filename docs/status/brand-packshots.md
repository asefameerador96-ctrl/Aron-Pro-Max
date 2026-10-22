# Aron logo and pack-picture tooling

Status of 2026-10-19 (lead, stream J5). Decisions: D-BRAND-02, D-PACK-03, D-PACK-04, D-BP-A-apks and D-BP-A6 in `DECISIONS.md`
(section "2026-10-19, Aron logo and pack-picture tooling"). Owner ask of 2026-10-18: "here are the packshots ... and find the
'aron' logo attached to use everywhere it required." Every claim about what ran comes from a stream report or is marked as J5's own.

## State in one paragraph

The logo is in the code of the three Android apps, the web portal and the printed report header, on top of origin `0675990b`.
The streams committed and pushed nothing; the lead stages, commits and pushes (owner's go of 2026-10-19: re-check origin first,
push to the integration branch, no force, watch CI and say what fails, do not run the packshot loader until told). No logo has
run on a phone: it reaches a phone only through the next CI-built APK. Pack pictures are tooling only (bulk loader, processed
set, hash manifest, runbook). Our first pack-picture design (signed bundle links, a Room column, thumbnails) was built, reviewed
and dropped on 2026-10-19 for the ui-packshots lane's design that is already on origin; it lives only in the local branch
`backup/brand-first-design` (commit `0b15ed92`, unpushed). The loader has never touched a real environment and is NOT run.

## What landed (working tree, uncommitted)

| Stream | Area | Files |
|---|---|---|
| J1 | assets, generator, loader | `assets/README.md`, `assets/brand/{aron-logo-original.png,manifest.json}`, `assets/packshots/{README.md,manifest.csv}`, `tools/brand/build_brand_assets.py` and the 14 generated rasters (6 launcher WebPs and the in-app mark in `android/core-ui/src/main/res`, 3 marks in `web/public/brand`, `web/src/app/{icon.png,apple-icon.png,favicon.ico}`, 1 report mark), `tools/data/{prepare,load}_packshots.py` and their two test files, `docs/runbooks/load-packshots.md`, one row in `docs/runbooks/README.md` |
| J2 | Android logo | `AronBrandMark.kt` (+ test) in core-ui; `BrandBlock` in feature-auth `AuthKit.kt`; adaptive icon, plate colour and `AppIconTest` in each of app-sr, app-amo, app-tso; 3 manifests; the 3 placeholder `ic_launcher.xml` vectors deleted |
| J3 | web logo | `web/src/components/brand-mark.tsx`, `sidebar.tsx`, `shell.tsx`, `login/page.tsx`, `proxy.ts`, `next.config.ts`, `e2e/smoke.spec.ts`, `eslint.config.mjs`, `tests/brand-assets.test.ts` |
| J4 | report header | `ReportOutputs.kt`, `ReportApiTest.kt`, new `ReportPrintMarkTest.kt` in `backend/analytics` |
| J5 | docs | this page, `DECISIONS.md`, the three `docs/requests/brand-*.md`, the logo in `README.md` |

The foreign changes in the tree are not ours and stay out of the commit: `docs/status/laptop.md` and `tools/checks/honor-upgrade-*`.

## What was run, and what was not

| Stream | Ran and passed | Not run |
|---|---|---|
| J1 | 156 tests in `tools/data` (143 older, 13 new) against a local fake server; `build_brand_assets.py --check` and `--check-regen` on Pillow 12.3.0; the 15 generated art and manifest files are byte-identical to the first design's; `prepare_packshots.py` leaves `manifest.csv` unchanged | the loader against any real host (Azure, Key Vault, the real admin and `sku-images` API); anything on a phone; any Gradle build; CI; other Python or Pillow builds. Phone-side claims in the docs are read from code |
| J2 | the three debug APKs assemble; the merged manifests carry the icon attributes; `AppIconTest` (4 cases in each app) and `AronBrandMarkTest` (5); unit tests of core-ui, feature-auth, feature-home and the three apps, except the known `ScannerSourceSetsTest`; `HardcodedStringScanTest`; `lintDebug` for all three apps; a throwaway render of the login block in Bangla light and Sunlight (deleted afterwards) | a real launcher (round, squircle, Android 13 themed icon); Enrolment and OTP renders; ktlint and detekt; core-system tests; the low-density look (D-UI-04); any golden task |
| J3 | Playwright smoke, calm-glass and dash-print specs, 22 of 22; the proxy matcher table in vitest; measured plate and logo sizes in the browser | `config-tiers.spec.ts` and the rest of `web/e2e`; any CI job; Linux or Azure deployment; the Docker image; cache headers behind Azure |
| J4 | `ReportPrintMarkTest` (6 tests); the other DB-free `backend:analytics` tests; `:backend:app:compileKotlin`; the DB-backed failure set is the same before and after (138 tests, all "ARON_TEST_PG_URL is not set") | the new assertion in `ReportApiTest.printIsServerRenderedEscapedAndLogged` (needs PostgreSQL); the printed header as an image; any lint |
| J5 (own, offline, writes nothing) | `vitest run tests/brand-assets.test.ts`: 64 passed. `build_brand_assets.py --check`: "OK 15 files match assets/brand/manifest.json". `load_packshots.py --check`: OK, 42 SKUs 1:1 with the catalogue, 42 processed files verified (854 KB), 4 suspect pictures as WARNING lines. `git ls-files -o --exclude-standard` filtered to picture and archive extensions lists 15 files, all of them the logo family (master, 6 launcher WebPs, the in-app mark, the report mark, 3 web marks, 3 web icons); no tracked picture file is modified or deleted; `git ls-files assets/packshots` prints nothing | everything else. J3's summary mentions three vitest failures that it calls the known CRLF-checkout ones and does not name; they are not in `brand-assets.test.ts`, which passed in full in J5's run |

Known environment-only failures that are not this work's: `SchemaMigrationTest`/`Migration*Test` MigrationTestHelper cases,
`ScannerSourceSetsTest`, `DeviceTelemetryTest`, DB-backed backend tests, `RouteInventoryTest`, `JcsTest`, web `contrast-tokens`,
`css-contrast` and `contract-drift`.

Offline checks anyone can re-run (none contacts a server):

```
python tools/brand/build_brand_assets.py --check
python -P tools/data/load_packshots.py --check
python -P -m unittest discover -s tools/data -p "test_*.py"
```

## Stale goldens (never record them on Windows; `docs/requests/brand-integrator-golden-rerecord.md`)

| Golden | Why |
|---|---|
| `android/ui-screenshots/src/test/screenshots/login_bn_light.png` | `ScreenshotTests.login_bn_light` draws `BrandBlock` (J2) |
| `android/ui-screenshots/src/test/screenshots/login_bn_sunlight.png` | same, in Sunlight (J2) |
| `web/e2e/config-tiers.spec.ts-snapshots/config-home-tier-{A,B,C}-chromium-linux.png` | the sidebar still shows the old round red-A monogram; about 0.1 percent of the pixels against `maxDiffPixelRatio` 0.03, so CI will probably pass but they are strictly stale (J3) |

No golden renders Enrolment or OTP (J2). The other six web goldens are element shots without the shell and are not stale (J3).

## Next steps, in order

1. **Lead:** `git fetch` and check origin has not moved (no Room number is ours any more, D-PACK-03, so there is nothing to
   renumber); stage by an explicit path list (below); commit; push with no force.
2. **Watch CI.** The first Linux run is the real test of the Android goldens and of the DB-backed tests, among them the new
   assertion in `ReportApiTest` (J4). The two login goldens are expected to fail it (J2). A green push to the integration branch starts the dev deploy (`deploy.yml` runs on `workflow_run`
   of a successful `ci`; read from the files on 2026-10-19). Report to the owner what fails.
3. **Lead:** re-record the goldens above after the push.
4. **Owner (hands):** install the CI-built SR, AMO and TSO APKs; look at the three launcher icons side by side (and the Android 13
   themed icon), and at the sign-in mark in light and Sunlight (device check D-UI-04).
5. **Owner (a yes in chat for the named environment, never standing):** the packshot loader on dev, in the runbook's order:
   offline `--check`, dry run, canary `--apply --only MAX`, `--apply` for all, closing dry run. It needs `az login`, the Key Vault
   read and the authenticator, as the runbook says. **Not done.** Decide the next item in the same message.
6. **Owner:** four Black Diamond SKUs are suspect, `BDA-10s`, `BDDF-10s`, `BDA-20s` and `BDDF-20s` (the two 20s files are
   byte-identical, the two 10s files show the same art): load all 42 now and fix the four later, or `--skip-suspect` first, and
   supply the right files when they exist.
7. **Owner (hands), after the loader:** on an SR phone, force-stop and reopen the app (a sync run does not bring the changed
   bundle), tap Sync now, open Stock and Sale (`docs/runbooks/load-packshots.md`, "Check on a test phone"). The AMO and TSO apps
   have no start-up or foreground bundle download in this tree, so check SR first.
8. **Owner, no hurry:** Fit or Crop for Aster (the kit crops, so its sides are lost); whether the logo is printed on the memo
   (recommended no for the cutover, the default); a one-line confirmation that the logo is AKTCL's.

## Staging note

Never `git add -A`, `-u`, `-a` or `.`. Build the list from `git ls-files -o --exclude-standard` plus `git diff --name-only HEAD`,
minus `docs/status/laptop.md` and `tools/checks/honor-upgrade-*`, and add it with `git add -- <list>`: it must include the three
deleted placeholder vectors, the new `mipmap-anydpi` folders, `.gitattributes`, `web/public/brand/`, `web/src/app/{icon.png,
apple-icon.png,favicon.ico}` and `backend/analytics/src/main/resources/brand/aron-mark-64.png` (J3 and J4: without the web files
the web build and its new tests fail on CI, and without the report mark `ReportPrintMarkTest` fails). `.gitignore` goes in
whole, so the `/apks/` rule rides with it (D-BP-A-apks). Before committing, `git ls-files -o --exclude-standard` must list no pack
picture. New files are LF in a CRLF checkout; git may warn about line endings (J2).

## Risks and follow-ups

| Item | Who | Where |
|---|---|---|
| Brand-asset check, `tools/data` tests and an allowlist guard against a forced add of a pack picture are not in CI | infra | `docs/requests/brand-infra-ci-gates.md` |
| The media lifecycle rule tiers the pack pictures to Cool and Cold | infra | `docs/requests/brand-infra-media-lifecycle.md` |
| The lane's runbook says "the next sync" brings the bundle and a failed picture is retried after every sync; neither is so (D-PACK-03 a, b). Optional server fix: fill `bundle_version_current` in batch answers (`IngestService.kt:170`); failed downloads stay in `SkuPackshots.failed` until restart | ui-packshots, backend-core | `docs/runbooks/sku-pack-pictures.md` |
| `PackshotImage` crops (`ContentScale.Crop`); Aster loses its sides | ui-kit, lead | D-PACK-03 (c) |
| The admin SKU answer lacks `image_asset_id` although the contract's `Sku` declares it | backend-admin | `AdminProducts.kt` `SkuOut` |
| `docs/status/ui-parity-ui-sr-b.md` still says the login block is a "gradient brand card instead of the vendor logo"; `web-glass.md` and `ui-parity-web-dashboard.md` still call the sidebar logo a monogram | ui-sr-b, web-dashboard, lead | one line each |
| `docs/24` s1.1 has no row for `assets/` or `.gitattributes`, and its `tools/` row names only dev-box scripts (not `tools/brand`, `tools/data`) | lead | `docs/24-build-spec.md` |
| The generator still bakes the old Calm Glass plate colour `#E8EFFA` into `apple-icon.png`; regenerating rewrites bytes and the manifest | lead | `tools/brand/build_brand_assets.py` |
| `AuthKit.kt` carries unused imports from other lanes (`Size`, `SolidColor`, `LocalAronColors`); ktlint and detekt were not run | ui-sr-b, lead | `android/feature-auth` |
| The plate sizes are asserted to the pixel in `smoke.spec.ts`; a change to the shell geometry must update them on purpose | web-dashboard | `web/e2e/smoke.spec.ts` |

The preview page `assets/packshots/preview/index.html` is local, git-ignored and holds real pack pictures: never commit or share
it. It was built at the first design's commit `0b15ed92`: its file and line references, its Stock and Sale renders with the first
design's thumbnails and its renders of the first-bundle and Settings About marks do not describe this tree. Do not use it as
evidence for the pack-picture screens or those two marks.
