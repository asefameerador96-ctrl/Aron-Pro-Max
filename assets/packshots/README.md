# assets/packshots

The SKU pack pictures: **only their hash inventory is in git.** The pictures themselves are not.

## Provenance

The owner supplied 42 JPEGs, one per SKU, on 2026-10-17/18 (files dated 2026-10-17, request of 2026-10-18), from
`E:\Github Project\Aron-Pro-Max\packshot`. The file stem is the catalogue `sku_code` in `db/seed/sku_catalog.csv`; the DB
code replaces a space with `_` (five codes: `MaxDB-10S 10HL`, `MaxDB-20S 20HL`, `MaxPrime-20S 20HL`, `EssP-20S 20HL`,
`EssCM-20S 20HL`; `docs/requests/db-sku-code-spaces.md`). The mapping is 1:1 with the catalogue (42 files, 42 SKUs).
The folder is only ever read; nothing under it is modified, moved or deleted.

## Why the pixels are not in git

The repository is public until the product is complete (CLAUDE.md rule 13), and git history cannot be retracted by making it
private later. So no pixel of these 42 files, or of anything made from them, may appear in any tracked or to-be-tracked file:
not the originals, not the processed copies, not thumbnails or crops, not web mock fixtures, not screenshot goldens, not test
resources, not docs (decision D-PACK-03).
Tests use synthetic pictures made in code. The root `.gitignore` is an allowlist for this folder: everything under
`assets/packshots/` is ignored (any picture or archive format in any case, and the whole `processed/` folder) except
`manifest.csv` and this `README.md`, so a pack picture cannot be staged by accident under `assets/packshots/` (`git add -A`,
`git add assets`). It does not stop `git add -f`, and it does not cover a picture copied to another folder; no CI step guards against that yet
(an infra-lane addition, not made here). Five originals carried metadata (EXIF, XMP or a JPEG comment; two of them
an author name); the processed copies carry none, and the manifest note says which segments were stripped.

The pictures reach people only through the admin asset store (purpose `sku_image`) -> `app.sku.image_asset_id` -> the sync
bundle's `image_asset_id` -> a one-time download by each phone -> the phone's disk cache (see "How a picture reaches a phone").
They are not bundled in any APK.

## What is here

| File | In git | What |
|---|---|---|
| `manifest.csv` | yes | one row per SKU: `sku_code`, `source_file`, `source_bytes`, `source_sha256`, `width`, `height` (of the processed file), `processed_file`, `processed_bytes`, `processed_sha256`, `duplicate_group` (`kind:key`, empty for a unique picture), `note` (source pixel size, stripped metadata, duplicates). UTF-8, LF, sorted by `sku_code` |
| `README.md` | yes | this file |
| `processed/<sku_code>.jpg` | **no** (ignored) | the upload copies: long edge at most 320 px, never upscaled, RGB, JPEG quality 82 with optimised tables, 4:4:4, baseline, all metadata stripped, deterministic bytes, at most 300 KB (the asset cap); 42 files, 854 KB in total (37 distinct pictures, 762,985 bytes), largest 33,591 bytes |

Nothing is cropped or edited: pictures are only made smaller (Aster, a 1500x820 group shot, becomes 320x175 and stays whole).
How a phone fits a picture into its slot is the app kit's business (`PackshotImage` crops to fill the portrait Stock and Sale
slot; the landscape Aster is the one picture that loses its sides there: `docs/runbooks/load-packshots.md`, "When the phones
show it").

## How a picture reaches a phone

1. `tools/data/load_packshots.py --apply` (or the web admin's SKU form, one SKU at a time) uploads the file to the admin asset
   store (`POST /v1/admin/assets`, purpose `sku_image`; the asset id is derived from the file's sha256, so equal pictures share
   one asset) and binds it with `PATCH /v1/admin/skus/{id}` (`image_asset_id`). The loader then reads each picture back twice,
   from the blob store and from `GET /v1/sku-images/{asset_id}`, the route the phones use.
2. The SKU's entry in the sync bundle carries that `image_asset_id`, never a link. A sync run does not tell a phone that its bundle
   changed; the SR app takes the changed bundle when it is started, when it comes to the front (at most every 30 to 240
   minutes) or when the next business day begins.
3. After its next completed sync run the phone downloads each picture it does not hold yet from `GET /v1/sku-images/{asset_id}`
   (the API streams the bytes): 37 distinct pictures, about 0.75 MB, once per phone, mobile data allowed. It stores them under
   the asset id in its bounded image cache and the Stock and Sale rows draw them from disk; a SKU without a picture shows the
   grey pack silhouette. A changed picture is a new upload with a new asset id, so phones fetch it once; an unchanged one never again.

The reasons and the checks are in `docs/runbooks/load-packshots.md` ("When the phones show it"); the design is D-PACKSHOT-01.

## Duplicates

Five pairs of source files are byte-identical (`duplicate_group` in the manifest). Four are the same variant in two pack sizes
(`same-art-pack-sizes`: Supreme, MaxDB, MaxB, MaxR) and fine. `BDA-20s` and `BDDF-20s` are different Black Diamond variants with
the same file (`suspect-different-variants`), probably a source mistake.

A byte hash misses a copy that was saved again, so `prepare_packshots.py` also compares the processed pictures of SKUs of
**different** catalogue variants on a small grey thumbnail (similar shape, mean difference of at most 6 of 255; the real
pairs sit at 0 and 3.8, every other pair of different variants with a similar shape at 12.6 or more). That finds a second
pair: `BDA-10s` and `BDDF-10s` are two different files (185x387 and 185x385) with the same art, also
`suspect-different-variants`. So all four Black Diamond SKUs (`BDA-10s`, `BDDF-10s`, `BDA-20s`, `BDDF-20s`) are suspect. All four
are kept and loaded like any other; the loader prints a `WARNING` line per suspect SKU in `--check`, `--dry-run` and `--apply`,
and `--skip-suspect` leaves them out so the other 38 can go first. The owner decides (`docs/runbooks/load-packshots.md`,
"Owner approval" and "Duplicate pictures"). The same variant in two pack sizes is not compared: `MaxWB-10S` and `MaxWB-20S`
look alike on purpose.

## Regenerate and verify

Python 3.11 or newer (`-P`). Pillow only for the first command (`python -m pip install pillow`); the others are standard library.

```
python -P tools/data/prepare_packshots.py [--src DIR]    # read the owner's folder, write processed/ and manifest.csv (idempotent)
python -P tools/data/load_packshots.py --check           # offline: manifest vs catalogue, files, sizes, hashes, no metadata
python -P -m unittest discover -s tools/data -p "test_*.py"
```

`ARON_PACKSHOT_SRC` replaces `--src`. The run stops before writing anything if a file maps to no SKU, a SKU has no file or a
file cannot be processed (a stale file in `processed/` is reported after the write; `--prune` deletes it).
The encoded bytes depend on the Pillow and libjpeg build (the manifest holds the hashes of the build that wrote it: Pillow
12.3.0, JPEG library 8.0). Another build gives other bytes and therefore other asset ids on the next load; that is harmless
(assets are append-only), but commit the regenerated manifest together with any regeneration. The upload is
`tools/data/load_packshots.py --apply`, an owner-approved laptop operation: `docs/runbooks/load-packshots.md`.
