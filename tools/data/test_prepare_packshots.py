#!/usr/bin/env python3
"""Tests of tools/data/prepare_packshots.py on SYNTHETIC pictures (flat colours, a gradient, a drawn letter): never a real
pack picture. Run: python -P -m unittest discover -s tools/data -p "test_*.py"   (needs Pillow; skipped without it)
"""
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import load_packshots as lp  # noqa: E402

try:
    from PIL import Image, ImageDraw
    import prepare_packshots as pp  # noqa: E402
except ImportError:   # pragma: no cover
    Image = None

needs_pillow = unittest.skipIf(Image is None, "Pillow is not installed")

HEADER = "category,segment,brand,variant,sku_code,short_name,pack_size,pack_type,price_outlet,price_cc,price_distributor,price_reporting,price_nto,status"


def synth(seed=1, size=(120, 200), mode="RGB", fmt="JPEG", **save) -> bytes:
    """Flat colour block, gradient strip and a drawn letter; the same arguments give the same bytes."""
    base = ((37 * seed + 30) % 220, (71 * seed + 60) % 200, (113 * seed + 90) % 180)
    im = Image.new("RGB", size, base)
    d = ImageDraw.Draw(im)
    d.rectangle([2, size[1] // 2, size[0] - 3, size[1] - 3], fill=(255 - base[0], 255 - base[1], 255 - base[2]))
    for x in range(size[0]):
        d.line([x, size[1] // 3, x, size[1] // 3 + 5], fill=(x * 2 % 256, 128, 255 - x * 2 % 256))
    d.text((size[0] // 3, 4), chr(65 + seed % 26), fill=(255, 255, 255))
    if mode != "RGB":
        im = im.convert(mode)
    buf = io.BytesIO()
    im.save(buf, fmt, quality=90, **save)
    return buf.getvalue()


def decode(data: bytes):
    return Image.open(io.BytesIO(data))


@needs_pillow
class ProcessImage(unittest.TestCase):
    def test_large_picture_is_scaled_to_a_320_long_edge_keeping_the_aspect(self):
        out, info = pp.process_image(synth(1, (1500, 820)))
        im = decode(out)
        self.assertEqual(im.size, (320, 175))          # a landscape group shot stays whole, only smaller
        self.assertEqual(info["src_size"], (1500, 820))
        self.assertEqual(im.mode, "RGB")
        self.assertEqual(im.format, "JPEG")
        out, _ = pp.process_image(synth(2, (533, 863)))
        self.assertEqual(decode(out).size, (198, 320))
        self.assertAlmostEqual(198 / 320, 533 / 863, delta=0.003)

    def test_a_small_picture_is_never_upscaled(self):
        for size in ((148, 271), (192, 310), (10, 10), (320, 200), (320, 320)):
            out, _ = pp.process_image(synth(3, size))
            self.assertEqual(decode(out).size, size, size)

    def test_output_is_deterministic(self):
        src = synth(4, (600, 900))
        self.assertEqual(pp.process_image(src)[0], pp.process_image(src)[0])

    def test_all_metadata_is_stripped(self):
        exif = Image.Exif()
        exif[0x013B] = "Synthetic Author Name"
        exif[0x9C9D] = "Synthetic XP Author".encode("utf-16-le")
        xmp = b"http://ns.adobe.com/xap/1.0/\x00<x:xmpmeta>Synthetic Author</x:xmpmeta>"
        src = bytearray(synth(5, (400, 600), exif=exif.tobytes(), comment=b"a synthetic comment"))
        # splice an XMP APP1 segment right after SOI, as editors do
        seg = b"\xff\xe1" + (len(xmp) + 2).to_bytes(2, "big") + xmp
        src = bytes(src[:2]) + seg + bytes(src[2:])
        before = lp.metadata_segments(lp.parse_jpeg(src))
        self.assertIn("exif", before)
        self.assertIn("xmp", before)
        self.assertIn("comment", before)
        out, info = pp.process_image(src)
        self.assertEqual(lp.metadata_segments(lp.parse_jpeg(out)), [])
        for needle in (b"Synthetic", b"Exif", b"xmpmeta", b"comment"):
            self.assertNotIn(needle, out)
        self.assertEqual(sorted(set(info["stripped"])), ["comment", "exif", "xmp"])

    def test_exif_orientation_is_baked_in_before_it_is_stripped(self):
        exif = Image.Exif()
        exif[0x0112] = 6   # rotate 90 clockwise to display
        out, info = pp.process_image(synth(6, (100, 200), exif=exif.tobytes()))
        self.assertEqual(decode(out).size, (200, 100))
        self.assertIn("orientation-applied", info["notes"])

    def test_srgb_profile_is_dropped_with_a_note_and_a_broken_one_does_not_fail(self):
        try:
            from PIL import ImageCms
            srgb = ImageCms.ImageCmsProfile(ImageCms.createProfile("sRGB")).tobytes()
        except Exception:
            self.skipTest("ImageCms is not available")
        out, info = pp.process_image(synth(7, (200, 300), icc_profile=srgb))
        self.assertEqual(lp.metadata_segments(lp.parse_jpeg(out)), [])
        self.assertIn("icc-srgb-dropped", info["notes"])
        self.assertIn("icc", info["stripped"])
        out, info = pp.process_image(synth(7, (200, 300), icc_profile=b"not a colour profile at all" * 10))
        self.assertEqual(lp.metadata_segments(lp.parse_jpeg(out)), [])
        self.assertIn("icc-unreadable-dropped", info["notes"])

    def test_grayscale_and_cmyk_become_rgb(self):
        for mode in ("L", "CMYK"):
            out, info = pp.process_image(synth(8, (150, 250), mode=mode))
            self.assertEqual(decode(out).mode, "RGB")
            self.assertIn(f"{mode.lower()}-to-rgb", info["notes"])

    def test_a_cmyk_picture_with_an_unusable_profile_is_still_converted(self):
        # a CMYK profile is applied before the mode conversion (the profile describes the CMYK numbers); an unusable one is
        # dropped with a note and the plain conversion follows
        out, info = pp.process_image(synth(8, (150, 250), mode="CMYK", icc_profile=b"not a colour profile at all" * 10))
        self.assertEqual(decode(out).mode, "RGB")
        self.assertEqual(info["notes"], ["icc-unreadable-dropped", "cmyk-to-rgb"])
        self.assertEqual(lp.metadata_segments(lp.parse_jpeg(out)), [])

    def test_a_png_is_not_a_source(self):
        with self.assertRaises(ValueError):
            pp.process_image(synth(1, fmt="PNG"))

    def test_the_worst_case_picture_stays_under_the_asset_cap(self):
        import random
        rnd = random.Random(1)
        im = Image.new("RGB", (320, 320))   # pure colour noise at the full 320 px edge: nothing to downscale, nothing to compress
        im.putdata([(rnd.randrange(256), rnd.randrange(256), rnd.randrange(256)) for _ in range(320 * 320)])
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=95, subsampling=0)
        out, _ = pp.process_image(buf.getvalue())
        self.assertEqual(lp.asset_rule_problems(out), [])
        self.assertLess(len(out), lp.ASSET_CAP_BYTES)


@needs_pillow
class Mapping(unittest.TestCase):
    CAT = {"MaxDB-10S_10HL": {}, "MaxR-10S": {}, "EAB": {}}

    def test_space_maps_to_underscore(self):
        src = {"MaxDB-10S 10HL": Path("MaxDB-10S 10HL.jpg"), "MaxR-10S": Path("MaxR-10S.jpg"), "EAB": Path("EAB.jpg")}
        mapped, unmapped, missing = pp.map_sources(src, self.CAT)
        self.assertEqual(sorted(mapped), ["EAB", "MaxDB-10S_10HL", "MaxR-10S"])
        self.assertEqual((unmapped, missing), ([], []))

    def test_unmapped_files_and_sku_without_a_file_are_reported(self):
        src = {"MaxR-10S": Path("MaxR-10S.jpg"), "Mystery": Path("Mystery.jpg")}
        mapped, unmapped, missing = pp.map_sources(src, self.CAT)
        self.assertEqual(unmapped, ["Mystery.jpg"])
        self.assertEqual(missing, ["EAB", "MaxDB-10S_10HL"])

    def test_matching_is_case_sensitive(self):
        mapped, unmapped, missing = pp.map_sources({"maxr-10s": Path("maxr-10s.jpg")}, self.CAT)
        self.assertEqual(mapped, {})
        self.assertEqual(unmapped, ["maxr-10s.jpg"])

    def test_two_files_for_one_code_are_a_problem(self):
        src = {"MaxDB-10S 10HL": Path("a.jpg"), "MaxDB-10S_10HL": Path("b.jpg")}
        mapped, unmapped, _ = pp.map_sources(src, self.CAT)
        self.assertEqual(len(mapped), 1)
        self.assertEqual(len(unmapped), 1)

    def test_classify_duplicates_from_the_catalogue(self):
        cat = {"A-10": {"variant": "Alpha", "pack_size": "10"}, "A-20": {"variant": "Alpha", "pack_size": "20"},
               "B-20": {"variant": "Beta Advanced", "pack_size": "20"}, "C-20": {"variant": "Beta Double", "pack_size": "20"},
               "D-20": {"variant": "Delta", "pack_size": "20"}, "D-20b": {"variant": "Delta", "pack_size": "20"}}
        self.assertEqual(pp.classify(["A-10", "A-20"], cat), "same-art-pack-sizes")
        self.assertEqual(pp.classify(["B-20", "C-20"], cat), "suspect-different-variants")
        self.assertEqual(pp.classify(["D-20", "D-20b"], cat), "suspect-same-pack")


@needs_pillow
class Build(unittest.TestCase):
    ROWS = [("Alpha", "AAA-10S", "10", 1), ("Alpha", "AAA-20S", "20", 1), ("Beta Double", "BBB-20S 20HL", "20", 2),
            ("Gamma", "CCC-20s", "20", 3), ("Delta", "DDD-20s", "20", 3)]

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        r = Path(self.tmp.name)
        self.src, self.out, self.manifest, self.catalog = r / "src", r / "out" / "processed", r / "out" / "manifest.csv", r / "catalog.csv"
        self.src.mkdir()
        lines = [HEADER]
        for variant, code, size, seed in self.ROWS:
            lines.append(f"Cigarette,Low,Brand,{variant},{code},{code},{size},HLP,1,1,1,1,0,1")
            big = (900, 1500) if seed == 1 else (150, 260)
            (self.src / f"{code}.jpg").write_bytes(synth(seed, big))
        self.catalog.write_text("\n".join(lines) + "\n", encoding="utf-8")

    def build(self, **kw):
        return pp.build(self.src, self.catalog, self.out, self.manifest, log=lambda *_: None, **kw)

    def test_writes_files_and_a_manifest_that_the_loader_check_accepts(self):
        rows, problems = self.build()
        self.assertEqual(problems, [])
        self.assertEqual(sorted(p.name for p in self.out.glob("*.jpg")),
                         ["AAA-10S.jpg", "AAA-20S.jpg", "BBB-20S_20HL.jpg", "CCC-20s.jpg", "DDD-20s.jpg"])
        rep = lp.check_offline(self.manifest, self.out, self.catalog)
        self.assertEqual(rep.errors, [])
        self.assertEqual(rep.files_checked, 5)
        by = {r["sku_code"]: r for r in rows}
        self.assertEqual(by["AAA-10S"]["source_file"], "AAA-10S.jpg")
        self.assertEqual(by["BBB-20S_20HL"]["source_file"], "BBB-20S 20HL.jpg")
        self.assertEqual(by["BBB-20S_20HL"]["processed_file"], "BBB-20S_20HL.jpg")
        self.assertEqual((by["AAA-10S"]["width"], by["AAA-10S"]["height"]), (192, 320))
        self.assertEqual((by["BBB-20S_20HL"]["width"], by["BBB-20S_20HL"]["height"]), (150, 260))   # not upscaled

    def test_duplicate_groups_follow_the_catalogue(self):
        rows, _ = self.build()
        by = {r["sku_code"]: r for r in rows}
        self.assertTrue(by["AAA-10S"]["duplicate_group"].startswith("same-art-pack-sizes:"))
        self.assertEqual(by["AAA-10S"]["duplicate_group"], by["AAA-20S"]["duplicate_group"])
        self.assertTrue(by["CCC-20s"]["duplicate_group"].startswith("suspect-different-variants:"))
        self.assertEqual(by["CCC-20s"]["duplicate_group"], by["DDD-20s"]["duplicate_group"])
        self.assertEqual(by["BBB-20S_20HL"]["duplicate_group"], "")
        self.assertIn("same source bytes as DDD-20s", by["CCC-20s"]["note"])
        self.assertIn("owner to confirm", by["CCC-20s"]["note"])
        self.assertNotIn("owner to confirm", by["AAA-10S"]["note"])
        self.assertEqual(by["AAA-10S"]["processed_sha256"], by["AAA-20S"]["processed_sha256"])   # the pair shares one asset later

    def test_manifest_columns_and_hash_fields(self):
        rows, _ = self.build()
        text = self.manifest.read_text(encoding="utf-8")
        self.assertEqual(text.splitlines()[0], ",".join(lp.MANIFEST_COLUMNS))
        for r in rows:
            self.assertEqual(r["processed_sha256"], lp.sha256_hex((self.out / r["processed_file"]).read_bytes()))
            self.assertEqual(r["source_sha256"], lp.sha256_hex((self.src / r["source_file"]).read_bytes()))
            self.assertEqual(int(r["processed_bytes"]), (self.out / r["processed_file"]).stat().st_size)
            self.assertLessEqual(int(r["processed_bytes"]), lp.ASSET_CAP_BYTES)

    def test_a_second_run_changes_nothing(self):
        self.build()
        first = {p.name: (p.read_bytes(), p.stat().st_mtime_ns) for p in self.out.glob("*.jpg")}
        manifest = self.manifest.read_bytes()
        self.build()
        self.assertEqual(self.manifest.read_bytes(), manifest)
        for p in self.out.glob("*.jpg"):
            self.assertEqual((p.read_bytes(), p.stat().st_mtime_ns), first[p.name], p.name)

    def test_the_source_folder_is_never_modified(self):
        before = {p.name: (p.read_bytes(), p.stat().st_mtime_ns) for p in self.src.iterdir()}
        self.build()
        self.assertEqual({p.name: (p.read_bytes(), p.stat().st_mtime_ns) for p in self.src.iterdir()}, before)

    def test_an_unmapped_file_stops_the_run_before_anything_is_written(self):
        (self.src / "Mystery.jpg").write_bytes(synth(9))
        rows, problems = self.build()
        self.assertEqual(rows, [])
        self.assertEqual(problems, ["unmapped file: Mystery.jpg"])
        self.assertFalse(self.out.exists())
        self.assertFalse(self.manifest.exists())

    def test_a_sku_without_a_picture_stops_the_run(self):
        (self.src / "DDD-20s.jpg").unlink()
        rows, problems = self.build()
        self.assertEqual(rows, [])
        self.assertEqual(problems, ["SKU without a picture: DDD-20s"])
        self.assertFalse(self.out.exists())

    def test_a_corrupt_source_is_reported_and_nothing_is_written(self):
        (self.src / "CCC-20s.jpg").write_bytes(b"\xff\xd8\xff\xe0 truncated")
        rows, problems = self.build()
        self.assertEqual(rows, [])
        self.assertTrue(any(p.startswith("CCC-20s.jpg:") for p in problems), problems)
        self.assertFalse(self.manifest.exists())

    def test_stale_processed_files_are_reported_and_pruned_on_request(self):
        self.build()
        (self.out / "OLD.jpg").write_bytes(b"\xff\xd8\xff\xff\xd9")
        _, problems = self.build()
        self.assertTrue(any("OLD.jpg" in p for p in problems))
        self.assertTrue((self.out / "OLD.jpg").exists())
        _, problems = self.build(prune=True)
        self.assertEqual(problems, [])
        self.assertFalse((self.out / "OLD.jpg").exists())

    def test_main_reports_and_exits_zero(self):
        buf, err = io.StringIO(), io.StringIO()
        import contextlib
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(err):
            rc = pp.main(["--src", str(self.src), "--catalog", str(self.catalog), "--out-dir", str(self.out), "--manifest", str(self.manifest)])
        self.assertEqual(rc, 0, err.getvalue())
        self.assertIn("5 pictures", buf.getvalue())
        self.assertIn("3 distinct source pictures, 2 duplicate groups", buf.getvalue())

    def test_main_with_a_missing_source_folder_exits_2(self):
        import contextlib
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            rc = pp.main(["--src", str(Path(self.tmp.name) / "nope")])
        self.assertEqual(rc, 2)


def resaved(data: bytes, quality: int = 55, trim: int = 2, size=None) -> bytes:
    """The same art saved again at another quality, `trim` pixel rows shorter (or resized): other bytes, one picture."""
    with Image.open(io.BytesIO(data)) as im:
        im.load()
        im = im.crop((0, 0, im.width, im.height - trim)) if trim else im
        im = im.resize(size) if size else im
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=quality)
        return buf.getvalue()


@needs_pillow
class NearDuplicates(unittest.TestCase):
    """Byte hashes miss a copy that was saved again (BDA-10s and BDDF-10s): the same art of different variants is found by a
    thumbnail diff, and only across different catalogue variants."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        r = Path(self.tmp.name)
        self.src, self.out, self.manifest, self.catalog = r / "src", r / "out" / "processed", r / "out" / "manifest.csv", r / "catalog.csv"
        self.src.mkdir()

    def build(self, rows, pictures):
        lines = [HEADER]
        for variant, code, size in rows:
            lines.append(f"Cigarette,Low,Brand,{variant},{code},{code},{size},HLP,1,1,1,1,0,1")
            (self.src / f"{code}.jpg").write_bytes(pictures[code])
        self.catalog.write_text("\n".join(lines) + "\n", encoding="utf-8")
        logged: list[str] = []
        rows, problems = pp.build(self.src, self.catalog, self.out, self.manifest, log=logged.append)
        self.assertEqual(problems, [])
        return {r["sku_code"]: r for r in rows}, logged

    def test_a_resaved_copy_under_another_variant_is_a_suspect_group_with_a_note(self):
        art = synth(1, (120, 200))
        by, logged = self.build([("Alpha", "A-10", "10"), ("Beta", "B-10", "10"), ("Gamma", "C-10", "10")],
                                {"A-10": art, "B-10": resaved(art), "C-10": synth(5, (120, 200))})
        self.assertNotEqual(by["A-10"]["source_sha256"], by["B-10"]["source_sha256"])
        self.assertEqual(by["A-10"]["duplicate_group"], by["B-10"]["duplicate_group"])
        self.assertTrue(by["A-10"]["duplicate_group"].startswith("suspect-different-variants:"))
        self.assertEqual(by["C-10"]["duplicate_group"], "")
        self.assertIn("near-identical picture to B-10", by["A-10"]["note"])
        self.assertIn("owner to confirm", by["A-10"]["note"])
        self.assertNotIn("same source bytes", by["A-10"]["note"])
        self.assertTrue(any("A-10 and B-10" in line and "bytes differ" in line for line in logged), logged)
        self.assertEqual(lp.check_offline(self.manifest, self.out, self.catalog).errors, [])

    def test_the_group_key_is_stable_and_names_the_smaller_source_hash(self):
        art = synth(2, (120, 200))
        by, _ = self.build([("Alpha", "A-10", "10"), ("Beta", "B-10", "10")], {"A-10": art, "B-10": resaved(art)})
        want = min(by["A-10"]["source_sha256"], by["B-10"]["source_sha256"])[:8]
        self.assertEqual(by["A-10"]["duplicate_group"], f"suspect-different-variants:{want}")

    def test_one_variant_in_two_pack_sizes_is_not_looked_at(self):
        art = synth(3, (120, 200))
        by, logged = self.build([("Alpha", "A-10", "10"), ("Alpha", "A-20", "20")], {"A-10": art, "A-20": resaved(art)})
        self.assertEqual((by["A-10"]["duplicate_group"], by["A-20"]["duplicate_group"]), ("", ""))
        self.assertEqual(logged, [])

    def test_different_art_and_a_different_shape_are_not_near(self):
        art = synth(4, (120, 200))
        by, _ = self.build([("Alpha", "A-10", "10"), ("Beta", "B-10", "10"), ("Gamma", "C-10", "10")],
                           {"A-10": art, "B-10": synth(6, (120, 200)), "C-10": resaved(art, trim=0, size=(200, 120))})
        self.assertEqual({r["duplicate_group"] for r in by.values()}, {""})

    def test_byte_identical_pictures_of_different_variants_keep_their_note_and_group(self):
        art = synth(7, (120, 200))
        by, logged = self.build([("Alpha", "A-10", "10"), ("Beta", "B-10", "10")], {"A-10": art, "B-10": art})
        self.assertIn("same source bytes as B-10", by["A-10"]["note"])
        self.assertNotIn("near-identical", by["A-10"]["note"])
        self.assertTrue(by["A-10"]["duplicate_group"].startswith("suspect-different-variants:"))
        self.assertEqual(logged, [], "identical bytes are already known; only a differing copy is reported as near")

    def test_near_pairs_lists_identical_pictures_too_and_only_across_variants(self):
        art = synth(8, (120, 200))
        outputs = {"A-10": pp.process_image(art)[0], "B-10": pp.process_image(art)[0], "A-20": pp.process_image(resaved(art))[0]}
        cat = {"A-10": {"variant": "Alpha"}, "B-10": {"variant": "Beta"}, "A-20": {"variant": "Alpha"}}
        pairs = pp.near_pairs(outputs, cat)
        self.assertEqual([(a, b) for a, b, _ in pairs], [("A-10", "B-10"), ("A-20", "B-10")])
        self.assertEqual(pairs[0][2], 0.0)
        self.assertLessEqual(pairs[1][2], pp.NEAR_DUP_MAD)


if __name__ == "__main__":
    unittest.main()
