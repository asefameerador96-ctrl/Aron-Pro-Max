# brand to infra: CI gates for the brand assets and the pack-picture tooling

From: stream J5 (docs), 2026-10-19. Owner of `.github/workflows/ci.yml` and `tools/ci/`: infra. Nothing below blocks the push;
the checks are offline and ran clean on the laptop on 2026-10-19 (J1, and J5 for the two `--check` commands). Today CI runs none
of them. This file is the "CI guard" that `.gitignore` and D-PACK-04 point to; it does not exist as code yet.

## 1. Brand assets are current (a step in the `gates` job)

```
python3 -m pip install pillow
python3 tools/brand/build_brand_assets.py --check
```

It compares the 14 generated rasters on disk (launcher WebPs, in-app mark, web marks and icons, report mark) with the bytes,
sha256 and pixel size in `assets/brand/manifest.json`, checks every ICO frame and that the manifest lists exactly the outputs the
generator makes. It writes nothing, ends a bad manifest or an unreadable image with one `FAIL ...` line and exit 1, and holds on
any machine. On 2026-10-19 it printed "OK 15 files match assets/brand/manifest.json" (J5).

* It proves the files equal the manifest, not that the manifest equals what the generator would write now. Only `--check-regen`
  proves that, and only on the Pillow build that wrote the files (12.3.0): use `--check`, not `--check-regen`, on a runner.
* Path filter: `assets/**`, `tools/brand/**`, `android/core-ui/src/main/res/**`, `web/public/brand/**`, `web/src/app/icon.png`,
  `web/src/app/apple-icon.png`, `web/src/app/favicon.ico`, `backend/analytics/src/main/resources/brand/**`.
* Add a case to `tools/ci/test_gates.py` if the gate should prove it fails on a deliberate violation, like the others.

## 2. The pack-picture tooling tests

```
python3 -m pip install pillow
python3 -m unittest discover -s tools/data -p "test_*.py"
python3 tools/data/load_packshots.py --check
```

156 tests ran on the laptop (J1); the ones that build synthetic JPEGs skip without Pillow, so install it. `--check` is offline
and prints one WARNING line per suspect picture (the four Black Diamond SKUs) and still exits 0. On a runner there is no
`assets/packshots/processed/` (git-ignored), so ask that `--check` be tried there first: in the first design it fell back to
validating `manifest.csv` against `db/seed/sku_catalog.csv` alone, and J5 did not re-run it in that state.

## 3. A guard against a forced add of a pack picture (D-PACK-04)

The root `.gitignore` ignores everything directly under `assets/packshots/` except `manifest.csv` and `README.md`. That stops an
accidental `git add -A`, not `git add -f` and not a picture copied to another folder. Make the guard match the allowlist: fail
the `gates` job if `git ls-files assets/packshots` prints anything other than `assets/packshots/manifest.csv` and
`assets/packshots/README.md`. It covers any file format (an extension filter would miss `.jfif`, `.svg`, `.zip`). On 2026-10-19
that command printed nothing (J5). It does not cover a picture copied into `web/public`, an Android `res` folder or `docs`; a
second guard could fail on any new binary under those folders that `assets/brand/manifest.json` does not list, with an
exception list for the tracked reference screenshots and `android/dev-packshots`. Add a case to `tools/ci/test_gates.py`.

## 4. APK size baseline

The logo files ship in each APK: 24,282 bytes of launcher foreground, 10,464 of monochrome layer and a 49,544-byte in-app mark,
84,290 bytes in all (summed from the files, J5); the three placeholder vector icons are deleted. The smallest entry of
`tools/ci/apk-size-baseline.json` is `armeabi-v7a` at 4,582,277 bytes, so the logo is at most +1.8 percent there if all of it
lands in that APK, under the +5 percent warn line. No release (R8) build or `apk-size-gate.py` run was made for this change. If
the gate warns or fails on the first run, refresh the entries with `apk-size-gate.py --write-baseline` after that run and cite
this file and D-BRAND-02 as the reason (the gate warns above +5 percent and fails above +15 percent of the baseline).

## 5. Optional

* `infra/scripts/image-smoke.sh`: one more check that the web image serves `/brand/aron-mark-64.png` with 200 and `image/png` to
  a signed-out client (the proxy matcher exempts exactly the six icon paths). J3 checked cache headers only on a local standalone
  server, not in the Docker image and not behind Azure.
* The root `.gitignore` ignores `/apks/` (D-BP-A-apks) and no longer ignores `*.apk` anywhere else. If a CI step writes an APK to
  another folder it is not ignored: say so.
