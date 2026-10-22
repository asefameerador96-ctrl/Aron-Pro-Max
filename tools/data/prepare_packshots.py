#!/usr/bin/env python3
"""Pack pictures (D-PACK-03): the owner's JPEGs to small, clean, content-addressed copies plus a hash manifest.

    python -P tools/data/prepare_packshots.py [--src DIR]        # needs Pillow (pip install pillow)

Reads one JPEG per SKU (default E:/Github Project/Aron-Pro-Max/packshot, or --src, or ARON_PACKSHOT_SRC; the folder is
only ever read), maps the file stem to the DB sku_code (a space becomes '_': docs/requests/db-sku-code-spaces.md),
checks the mapping is 1:1 against db/seed/sku_catalog.csv (an unmapped file or a SKU without a file stops the run before
anything is written), and writes

  assets/packshots/processed/<sku_code>.jpg   long edge at most 320 px, never upscaled, RGB, JPEG quality 82 with
                                              optimised Huffman tables, 4:4:4 chroma (the white Bangla warning text on
                                              black stays crisp), baseline, ALL metadata stripped (EXIF, XMP, ICC,
                                              comments: two originals carry an author name), deterministic bytes
  assets/packshots/manifest.csv               sku_code, source and processed sizes and sha256, pixel size, duplicate
                                              group, note: hashes only, safe for the public repository

Duplicates: SKUs whose source files are byte-identical share a duplicate_group, and so do SKUs of DIFFERENT catalogue
variants whose processed pictures are the same art although the bytes differ (a thumbnail diff, see near_pairs: BDA-10s
and BDDF-10s). One variant in several pack sizes is expected (same-art-pack-sizes); different variants sharing a picture
are most likely a wrong file and are marked suspect-different-variants, which tools/data/load_packshots.py warns about.
The processed folder is git-ignored: the repository is public until the product is complete (CLAUDE.md rule 13), so no
pack pixel may be tracked; tools/data/load_packshots.py puts the files into an environment through the admin API.
Pictures are never cropped or edited (Aster, a 1500x820 group shot, stays whole): the apps letterbox them in a square slot.
Every picture is encoded afresh, also one that needs no resize: one code path and one set of encoder settings for all 42 files.
Copying the eight small sources through would be a second path, and would only avoid a growth of at most 2.7 KB each and one more
JPEG generation at quality 82; a source a pixel or two over the long edge is resampled to it like any other (no tolerance).
The encoded bytes depend on the Pillow and libjpeg build (the manifest records the hashes of this build's output); a
different build gives different bytes, hence new asset ids on the next load, which is harmless (assets are append-only).
"""
from __future__ import annotations

import argparse
import io
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import load_packshots as lp  # noqa: E402

DEFAULT_SRC = "E:/Github Project/Aron-Pro-Max/packshot"
QUALITY = 82
SUBSAMPLING = 0   # 4:4:4
# Near-identical art: the mean absolute grey difference (of 255) of two 32x64 (or 64x32) thumbnails at or below this, and
# shapes that agree within 5 percent. Calibrated on the 42 real pictures: the BDA-10s/BDDF-10s pair is at 3.8, every other
# pair of different variants with a similar shape is at 12.6 or more (the next ones are beyond the shape limit).
NEAR_DUP_MAD = 6.0
NEAR_DUP_ASPECT = 0.05


def _pil():
    try:
        from PIL import Image, ImageOps
    except ImportError:
        raise SystemExit("Pillow is required: python -m pip install pillow") from None
    return Image, ImageOps


def process_image(data: bytes, max_edge: int = lp.MAX_EDGE_PX, quality: int = QUALITY) -> tuple[bytes, dict]:
    """One source JPEG to the processed JPEG. Returns (bytes, info): info has src_size, size, stripped (metadata segment
    names the source carried) and notes (orientation applied, colour profile handled, conversions)."""
    Image, ImageOps = _pil()
    stripped = [s for s in lp.metadata_segments(lp.parse_jpeg(data))]
    notes: list[str] = []
    with Image.open(io.BytesIO(data)) as im:
        if im.format != "JPEG":
            raise ValueError("the source is not a JPEG")
        im.load()
        src_size = im.size
        icc = im.info.get("icc_profile")
        oriented = ImageOps.exif_transpose(im)   # the orientation tag is stripped below, so bake it in first
        if oriented.size != im.size or (im.getexif().get(0x0112, 1) not in (0, 1)):
            notes.append("orientation-applied")
        work = oriented
        if icc and work.mode == "CMYK":   # a CMYK profile describes the CMYK numbers: go through it, not through the naive formula
            work, note = _to_srgb(work, icc)
            notes.append(note)
            icc = None
        if work.mode != "RGB":
            notes.append(f"{work.mode.lower()}-to-rgb")
            work = work.convert("RGB")
        if icc:
            work, note = _to_srgb(work, icc)
            notes.append(note)
        w, h = work.size
        long_edge = max(w, h)
        if long_edge > max_edge:   # never upscale: a smaller source keeps its pixels
            nw, nh = max(1, (w * max_edge + long_edge // 2) // long_edge), max(1, (h * max_edge + long_edge // 2) // long_edge)
            work = work.resize((nw, nh), Image.Resampling.LANCZOS)
        clean = Image.frombytes("RGB", work.size, work.tobytes())   # a fresh image carries no info dict, so nothing to write
    from PIL import ImageFile
    # optimised Huffman tables need the whole scan in one buffer, and Pillow sizes it at one byte per pixel: a very noisy
    # picture would fail with "broken data stream" (a real pack picture is far below that, 5 to 34 KB)
    ImageFile.MAXBLOCK = max(ImageFile.MAXBLOCK, clean.width * clean.height * 3 + 65536)
    buf = io.BytesIO()
    clean.save(buf, "JPEG", quality=quality, optimize=True, progressive=False, subsampling=SUBSAMPLING)
    out = buf.getvalue()
    info = lp.parse_jpeg(out)
    if lp.metadata_segments(info):
        raise ValueError("metadata left in the output: " + ",".join(lp.metadata_segments(info)))
    return out, {"src_size": src_size, "size": clean.size, "stripped": stripped, "notes": notes}


def _to_srgb(im, icc: bytes):
    """An embedded colour profile is dropped with the other metadata, so a non-sRGB picture is converted first or it would
    shift colour (a profile-less JPEG is read as sRGB). Returns (image, note)."""
    try:
        from PIL import ImageCms
        src = ImageCms.ImageCmsProfile(io.BytesIO(icc))
        name = (ImageCms.getProfileDescription(src) or "").lower()
        if "srgb" in name:
            return im, "icc-srgb-dropped"
        out = ImageCms.profileToProfile(im, src, ImageCms.createProfile("sRGB"), outputMode="RGB")
        return out, "icc-converted-to-srgb"
    except Exception:
        return im, "icc-unreadable-dropped"


def list_sources(src: Path) -> dict[str, Path]:
    """file stem -> path of every .jpg / .jpeg in the folder (any case). Two files with one stem are an error."""
    out: dict[str, Path] = {}
    for p in sorted(src.iterdir()):
        if p.is_file() and p.suffix.lower() in (".jpg", ".jpeg"):
            if p.stem in out:
                raise ValueError(f"two source files share the stem {p.stem!r}")
            out[p.stem] = p
    return out


def map_sources(sources: dict[str, Path], catalog: dict[str, dict]) -> tuple[dict[str, Path], list[str], list[str]]:
    """db code -> source path, plus the files that map to no SKU and the SKUs that have no file. A stem maps to the SKU
    whose db code equals the stem with each space replaced by '_' (case-sensitive)."""
    mapped: dict[str, Path] = {}
    unmapped: list[str] = []
    for stem, path in sources.items():
        code = lp.db_code(stem)
        if code in catalog and code not in mapped:
            mapped[code] = path
        elif code in mapped:
            unmapped.append(f"{path.name} (maps to {code}, which {mapped[code].name} already took)")
        else:
            unmapped.append(path.name)
    return mapped, sorted(unmapped), sorted(set(catalog) - set(mapped))


classify = lp.classify   # the same rule validates the manifest (load_packshots.check_offline)


def thumb(data: bytes) -> tuple[float, tuple[int, int], bytes]:
    """(width/height, thumbnail size, grey thumbnail pixels) of a JPEG: 32x64 for a portrait, 64x32 for a landscape."""
    Image, _ = _pil()
    with Image.open(io.BytesIO(data)) as im:
        w, h = im.size
        size = (64, 32) if w > h else (32, 64)
        return w / h, size, im.convert("L").resize(size, Image.Resampling.BOX).tobytes()


def near_pairs(outputs: dict[str, bytes], catalog: dict[str, dict]) -> list[tuple[str, str, float]]:
    """Pairs of SKUs of DIFFERENT catalogue variants whose processed pictures are the same art: a similar shape and a mean
    grey difference of at most NEAR_DUP_MAD on a thumbnail. Byte hashing alone misses a copy that was saved again or lost a
    pixel row on the way (BDA-10s and BDDF-10s). Identical pictures are in the list too (difference 0). The same variant in
    two pack sizes is expected and not looked at."""
    th = {code: thumb(data) for code, data in outputs.items()}
    out: list[tuple[str, str, float]] = []
    codes = sorted(outputs)
    for i, a in enumerate(codes):
        for b in codes[i + 1:]:
            if catalog[a]["variant"] == catalog[b]["variant"]:
                continue
            (ra, sa, pa), (rb, sb, pb) = th[a], th[b]
            if sa != sb or abs(ra / rb - 1) > NEAR_DUP_ASPECT:
                continue
            d = sum(abs(x - y) for x, y in zip(pa, pb)) / len(pa)
            if d <= NEAR_DUP_MAD:
                out.append((a, b, d))
    return out


def build(src: Path, catalog_path: Path, out_dir: Path, manifest_path: Path, max_edge: int = lp.MAX_EDGE_PX,
          quality: int = QUALITY, prune: bool = False, log=print) -> tuple[list[dict], list[str]]:
    """The whole run. Returns (manifest rows, problems). Nothing is written when a source is unmapped, a SKU has no source
    or a source cannot be processed (rows is then empty); a stale file in the output folder is reported after the write
    (everything else is written) unless prune deletes it."""
    catalog = lp.read_catalog(catalog_path)
    sources = list_sources(src)
    mapped, unmapped, missing = map_sources(sources, catalog)
    problems = [f"unmapped file: {u}" for u in unmapped] + [f"SKU without a picture: {m}" for m in missing]
    if problems:
        return [], problems
    rows: list[dict] = []
    outputs: dict[str, bytes] = {}
    for code in sorted(mapped):
        path = mapped[code]
        raw = path.read_bytes()
        try:
            data, info = process_image(raw, max_edge, quality)
        except Exception as e:   # one bad file must not hide the others
            problems.append(f"{path.name}: {type(e).__name__}: {e}")
            continue
        bad = lp.asset_rule_problems(data) + ([f"{info['size'][0]}x{info['size'][1]} is above the {max_edge} px long edge"]
                                              if max(info["size"]) > max_edge else [])
        if bad:
            problems.append(f"{path.name}: " + "; ".join(bad))
            continue
        notes = [f"src {info['src_size'][0]}x{info['src_size'][1]}"] + info["notes"]
        if info["stripped"]:
            notes.append("stripped " + "+".join(dict.fromkeys(info["stripped"])))
        outputs[code] = data
        rows.append({"sku_code": code, "source_file": path.name, "source_bytes": len(raw), "source_sha256": lp.sha256_hex(raw),
                     "width": info["size"][0], "height": info["size"][1], "processed_file": f"{code}.jpg",
                     "processed_bytes": len(data), "processed_sha256": lp.sha256_hex(data), "duplicate_group": "", "note": notes})
    if problems:
        return [], problems
    by_code = {r["sku_code"]: r for r in rows}
    parent = {c: c for c in by_code}

    def find(x: str) -> str:
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    def union(x: str, y: str) -> None:
        parent[find(x)] = find(y)

    first_of: dict[str, str] = {}
    for r in rows:   # byte-identical sources
        union(r["sku_code"], first_of.setdefault(r["source_sha256"], r["sku_code"]))
    near = near_pairs(outputs, catalog)
    near_of: dict[str, set[str]] = {}
    for a, b, d in near:
        union(a, b)
        near_of.setdefault(a, set()).add(b)
        near_of.setdefault(b, set()).add(a)
        if by_code[a]["source_sha256"] != by_code[b]["source_sha256"]:
            log(f"near-identical pictures of different variants: {a} and {b} (mean grey difference {d:.1f} of 255, the bytes differ)")
    members: dict[str, list[str]] = {}
    for c in by_code:
        members.setdefault(find(c), []).append(c)
    for codes in members.values():
        if len(codes) < 2:
            continue
        codes.sort()
        kind = classify(codes, catalog)
        key = min(by_code[c]["source_sha256"] for c in codes)[:8]
        for c in codes:
            r = by_code[c]
            r["duplicate_group"] = f"{kind}:{key}"
            same = [o for o in codes if o != c and by_code[o]["source_sha256"] == r["source_sha256"]]
            nearby = [o for o in codes if o != c and o not in same and o in near_of.get(c, ())]
            if same:
                r["note"].append("same source bytes as " + " and ".join(same))
            if nearby:
                r["note"].append("near-identical picture to " + " and ".join(nearby))
            if kind != "same-art-pack-sizes":
                r["note"].append("owner to confirm: different variants share one picture")
    for r in rows:
        r["note"] = "; ".join(r["note"])
    out_dir.mkdir(parents=True, exist_ok=True)
    for code, data in outputs.items():
        p = out_dir / f"{code}.jpg"
        if not p.exists() or p.read_bytes() != data:
            p.write_bytes(data)
    stale = sorted(p.name for p in out_dir.glob("*.jpg") if p.stem not in outputs)
    for name in stale:
        if prune:
            (out_dir / name).unlink()
            log(f"pruned {name}")
        else:
            problems.append(f"{name}: not a SKU picture of this run (use --prune to delete it)")
    lp.write_manifest(manifest_path, rows)
    return rows, problems


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--src", default=os.environ.get("ARON_PACKSHOT_SRC") or DEFAULT_SRC, help="folder of the owner's JPEGs (read only)")
    ap.add_argument("--catalog", type=Path, default=lp.DEFAULT_CATALOG)
    ap.add_argument("--out-dir", type=Path, default=lp.DEFAULT_PROCESSED)
    ap.add_argument("--manifest", type=Path, default=lp.DEFAULT_MANIFEST)
    ap.add_argument("--max-edge", type=int, default=lp.MAX_EDGE_PX)
    ap.add_argument("--quality", type=int, default=QUALITY)
    ap.add_argument("--prune", action="store_true", help="delete processed files that are not in this run")
    a = ap.parse_args(argv)
    src = Path(a.src)
    if not src.is_dir():
        print(f"error: the source folder {src} does not exist (--src or ARON_PACKSHOT_SRC)", file=sys.stderr)
        return 2
    rows, problems = build(src, a.catalog, a.out_dir, a.manifest, a.max_edge, a.quality, a.prune)
    for p in problems:
        print("problem: " + p, file=sys.stderr)
    if not rows:
        print(f"error: {len(problems)} problems, nothing was written", file=sys.stderr)
        return 1
    total = sum(int(r["processed_bytes"]) for r in rows)
    print(f"{'sku_code':<20} {'source':>11} -> {'processed':>9}  {'bytes':>7}  group")
    for r in rows:
        sw = r["note"].split(";")[0].replace("src ", "")
        print(f"{r['sku_code']:<20} {sw:>11} -> {r['width']:>4}x{r['height']:<4}  {r['processed_bytes']:>7}  {r['duplicate_group']}")
    dups = len({r["duplicate_group"] for r in rows if r["duplicate_group"]})
    print(f"{len(rows)} pictures, {total / 1024:.0f} KB processed in total (largest {max(int(r['processed_bytes']) for r in rows)} bytes), "
          f"{len({r['source_sha256'] for r in rows})} distinct source pictures, {dups} duplicate groups")
    try:
        import PIL
        from PIL import features
        print(f"built with Pillow {PIL.__version__}, JPEG library {features.version('jpg')}; manifest: {a.manifest}")
    except ImportError:
        pass
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
