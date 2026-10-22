# assets

Brand artwork and the packshot inventory. Nothing here is a build input of the apps by itself: the generator in
`tools/brand/` turns the one master logo into every raster the apps and the web need, and writes them straight into the
module that uses them. Which screen draws which file is each lane's code; the table below is what the generator writes.

## Provenance

The Aron logo (a tall, slanted letter A of ice shards with an orange core, a dark navy outline and a violet counter) was
supplied by the owner on 2026-10-18 for use across the product. It is AKTCL's brand artwork and, unlike the pack pictures, it
is committed on purpose. The master is `assets/brand/aron-logo-original.png` (500x499 RGBA, transparent background), kept
byte for byte as it arrived; `assets/brand/manifest.json` records its sha256. The artwork is never recoloured, redrawn or
given an effect: every derived file is a crop, a LANCZOS resize and a placement. The one derived picture that is not a plain
resize is the Android 13 themed-icon layer (a tone map of the same pixels, see below).

The logo is artwork, not a theme source: the generator recolours nothing and no app colour is derived from it (the palette
is the parity spec's, `docs/ui-reference/PARITY-SPEC.md`).

## What lives where

`assets/brand/aron-logo-original.png` is the only hand-supplied file. Everything else is generated, listed with its bytes,
sha256, pixel size and parameters in `assets/brand/manifest.json`. Do not edit a generated file by hand.

| Output | Used by |
| --- | --- |
| `android/core-ui/src/main/res/mipmap-{xhdpi,xxhdpi,xxxhdpi}/aron_launcher_foreground.webp` | adaptive-icon foreground of the three apps (108 dp layer; the glyph is 60 dp tall; see "Launcher safe zone" below) |
| `android/core-ui/src/main/res/mipmap-{xhdpi,xxhdpi,xxxhdpi}/aron_launcher_monochrome.webp` | Android 13 themed icon (same canvas and scale; alpha only, the system tints it; the violet counter is cut out and the ice shards are a lighter tone) |
| `android/core-ui/src/main/res/drawable-nodpi/aron_brand_mark.png` | in-app mark (256 px, lossless), for the few screens that carry a brand mark (sign-in, enrolment and OTP of the three apps, per D-BRAND-01), drawn on a solid plate; never on a dense working screen, the printed memo, the push icon or the device-owner (dpc) app |
| `web/public/brand/aron-mark-{64,128,256}.png` | the web brand mark (about 6 percent padding) |
| `web/src/app/icon.png` (192, the tab and bookmark icon only), `apple-icon.png` (180, opaque on the calm page plate `#E8EFFA`), `favicon.ico` (16, 32, 48) | Next.js file conventions |
| `backend/analytics/src/main/resources/brand/aron-mark-64.png` | the printed report header mark (the analytics module embeds it as a `data:` URI in the print HTML); the same pixels as `web/public/brand/aron-mark-64.png` |

The per-app launcher background colours (SR `#0B5D3B`, AMO `#1F4E8C`, TSO `#8C1D18`) are not generated here; they live in the
app modules (decision D-BRAND-01, requirement F-SYS-033: the three apps must be told apart side by side).

## Launcher safe zone

The launcher layer is 108 dp and the system keeps a 66 dp circle (radius 33 dp) of it uncropped under every mask. The glyph
(60 dp tall) is measured from the layer centre to the outer corner of the farthest pixel that is **at least 16 of 255 opaque**
(about 6 percent; `ALPHA_FLOOR` in the generator), and the generator refuses to write if that exceeds 33 dp. The result is
32.93 dp at xhdpi, 32.70 at xxhdpi and 32.81 at xxxhdpi, so the tightest margin is 0.07 dp (xhdpi). It is not true for every
nonzero pixel: counting alpha >= 1 the farthest reaches 33.44 / 33.04 / 33.06 dp, an antialiasing halo under 6 percent opacity
that can sit up to 0.44 dp outside the circle and is not visible in practice. Both sets of figures are in the manifest
(`parameters.launcher`). "Inside the safe zone" in this repository means this, nothing stronger; the visual check is
the on-device look at the three launcher icons side by side.

## Regenerate and verify

Pillow only (Python 3, `pip install pillow`); no other image tool is needed, and every mode below needs it, `--check` too.

```
python tools/brand/build_brand_assets.py                # regenerate every output and the manifest (idempotent)
python tools/brand/build_brand_assets.py --check        # verify the files on disk against the manifest, write nothing
python tools/brand/build_brand_assets.py --check-regen  # regenerate in memory and compare byte for byte
```

`--check` proves that the files on disk equal the manifest (size, sha256, decoded pixel size and every ICO frame) and that
the manifest lists exactly the outputs the generator makes, so it holds on any machine. It does **not** prove that the
manifest equals what the current generator would write: change a generator parameter without regenerating and `--check`
still passes. Only `--check-regen` proves that, and only on the Pillow build that wrote the files. The encoded bytes
themselves depend on the Pillow build (zlib and libwebp); the manifest records the build that wrote them, and a regenerate
on another build can rewrite every file (the generator warns before it does). A bad manifest or an unreadable image ends a
check with one `FAIL ...` line (exit 1), not a traceback. If the master is ever replaced, rerun the generator and commit the
regenerated files together with the manifest; the generator stops with a message if its counter detection no longer fits
the new artwork.

## Pack pictures are never committed while the repository is public

Packshots (one picture per SKU, 42 JPEGs supplied by the owner) are real product pictures. The repository is public until the
product is complete (CLAUDE.md rule 13) and git history cannot be retracted, so **no pixel of those 42 files, or of anything
made from them, may appear in any tracked or to-be-tracked file**: not the originals, not thumbnails or crops, not web mock
fixtures, not screenshot goldens, not test resources, not docs. Tests and fixtures use synthetic pictures generated in code (flat
colour blocks, a gradient, a drawn letter). (Pictures of packs that are in git for another reason are a separate case, not these
files: the owner's reference screenshots of the current app under `docs/ui-reference` and the three dev-only stand-in crops cut
from one of them under `android/dev-packshots`.)

- Pack pictures reach users only through the admin asset store (purpose `sku_image`), then `app.sku.image_asset_id`, then the
  sync bundle, which carries the asset id and never a link; each phone downloads a picture once from
  `GET /v1/sku-images/{asset_id}` after a sync, keeps it in its disk cache, and the Stock and Sale rows draw it from there
  (decisions D-PACK-03 and D-PACKSHOT-01; `docs/runbooks/sku-pack-pictures.md` for one SKU at a time in the web admin,
  `docs/runbooks/load-packshots.md` for the bulk load and for when a phone shows the picture). They are not bundled in any APK.
- `assets/packshots/` holds only `manifest.csv` (a hash and size inventory, no pixels) and `README.md`, and both are
  trackable. The root `.gitignore` is an allowlist there: it ignores everything directly under `assets/packshots/` (any file
  type, and the whole `processed/` folder) except those two files, so a pack picture cannot be staged by accident under
  `assets/packshots/`. That is all it guarantees: a forced add (`git add -f`) or a picture copied to another folder is not
  stopped by it, and no CI step guards against that yet (an infra-lane addition, not made here).
- The processed copies (long edge at most 320 px, no metadata, content-addressed) are produced by
  `tools/data/prepare_packshots.py` and uploaded by `tools/data/load_packshots.py` (`assets/packshots/README.md` and
  `docs/runbooks/load-packshots.md`).

## Line endings

`.gitattributes` marks the picture formats binary and pins `assets/**/manifest.*` to LF. There is deliberately no global
`text=auto`: the laptop runs `core.autocrlf=true`, and a global rule would rewrite line endings of files that are already
committed and show phantom diffs.
