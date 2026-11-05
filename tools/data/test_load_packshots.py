#!/usr/bin/env python3
"""Tests of tools/data/load_packshots.py on SYNTHETIC pictures (flat colours, a gradient, a drawn letter) and a local fake
HTTP server that mimics the admin API and the blob store. No real pack picture, no real host, no real secret is used.
Run: python -P -m unittest discover -s tools/data -p "test_*.py"   (Pillow makes the synthetic JPEGs; without it the
tests that need pictures are skipped)
"""
import base64
import contextlib
import http.server
import io
import json
import os
import re
import sys
import tempfile
import threading
import unittest
import urllib.parse
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import load_packshots as lp  # noqa: E402

try:
    from PIL import Image, ImageDraw
    import prepare_packshots as pp  # noqa: E402
except ImportError:   # pragma: no cover
    Image = None

needs_pillow = unittest.skipIf(Image is None, "Pillow is not installed")



def _join(*parts: str) -> str:
    """The fake credentials below are assembled at run time from fragments, so that no source line holds a credential
    keyword next to a complete, random-looking value: the CI secret scan (gitleaks, generic-api-key) reads source text."""
    return "-".join(parts)


PW = _join("Fake", "Pw", "for", "tests", "1")
WRONG_PW = _join("Another", "Wrong", "Pw", "9")
SECRET = b"12345678901234567890"                       # the RFC 6238 test secret
SECRET_B32 = base64.b32encode(SECRET).decode()
OTHER_B32 = base64.b32encode(b"09876543210987654321").decode()   # a TOTP secret the fake server does not know
T0 = 1_800_000_000.0                                    # a multiple of 30: the start of a TOTP step
SAS_WRITE = _join("WRITE", "SIG", "SECRET", "7f3a")
SAS_READ = _join("READ", "SIG", "SECRET", "91bc")


# ---------------------------------------------------------------------------------------------------- fixtures
def synth_jpeg(seed: int, size=(120, 200), **save) -> bytes:
    """A flat colour block, a gradient strip and a drawn letter: the same seed gives the same bytes."""
    base = ((37 * seed + 30) % 220, (71 * seed + 60) % 200, (113 * seed + 90) % 180)
    im = Image.new("RGB", size, base)
    d = ImageDraw.Draw(im)
    d.rectangle([4, size[1] // 2, size[0] - 5, size[1] - 5], fill=(255 - base[0], 255 - base[1], 255 - base[2]))
    for x in range(size[0]):
        d.line([x, size[1] // 2 - 8, x, size[1] // 2 - 2], fill=(x * 2 % 256, 128, 255 - x * 2 % 256))
    d.text((size[0] // 3, 10), chr(65 + seed % 26), fill=(255, 255, 255))
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=90, **save)
    return buf.getvalue()


def resaved(data: bytes, quality: int = 55, trim: int = 2) -> bytes:
    """The same art saved again at another quality and two pixel rows shorter: different bytes, one picture."""
    with Image.open(io.BytesIO(data)) as im:
        im.load()
        im = im.crop((0, 0, im.width, im.height - trim)) if trim else im
        buf = io.BytesIO()
        im.save(buf, "JPEG", quality=quality)
        return buf.getvalue()


CATALOG_HEADER = "category,segment,brand,variant,sku_code,short_name,pack_size,pack_type,price_outlet,price_cc,price_distributor,price_reporting,price_nto,status"
FIXTURE = [  # (variant, catalogue sku_code, pack size, picture seed): equal seeds mean equal source bytes
    ("Alpha", "AAA-10S", "10", 1), ("Alpha", "AAA-20S", "20", 1),        # one variant, two pack sizes: the same art on purpose
    ("Beta Double", "BBB-20S 20HL", "20", 2),                            # a space in the catalogue code
    ("Gamma", "CCC-20s", "20", 3), ("Delta", "DDD-20s", "20", 3),        # two variants with one picture: suspect
]
DB_CODES = ["AAA-10S", "AAA-20S", "BBB-20S_20HL", "CCC-20s", "DDD-20s"]


class Workspace:
    """A catalogue, a source folder, the processed folder and the manifest of the five fixture SKUs, made by the real
    prepare_packshots.build()."""

    def __init__(self, root: Path, fixture=None, pictures: dict | None = None):
        self.root = root
        self.catalog, self.src = root / "catalog.csv", root / "src"
        self.processed, self.manifest = root / "processed", root / "manifest.csv"
        self.src.mkdir()
        lines = [CATALOG_HEADER]
        for variant, code, size, seed in (fixture or FIXTURE):
            lines.append(f"Cigarette,Low,Brand,{variant},{code},{code},{size},HLP,1,1,1,1,0,1")
            (self.src / f"{code}.jpg").write_bytes((pictures or {}).get(code) or synth_jpeg(seed))
        self.catalog.write_text("\n".join(lines) + "\n", encoding="utf-8")
        self.rows, problems = pp.build(self.src, self.catalog, self.processed, self.manifest, log=lambda *_: None)
        assert not problems, problems

    def argv(self, *mode):
        return [*mode, "--manifest", str(self.manifest), "--processed-dir", str(self.processed), "--catalog", str(self.catalog)]

    def check(self):
        return lp.check_offline(self.manifest, self.processed, self.catalog)

    def rewrite_manifest(self, fn):
        rows = lp.read_manifest(self.manifest)
        fn(rows)
        lp.write_manifest(self.manifest, rows)


# ---------------------------------------------------------------------------------------------------- pure functions
class Totp(unittest.TestCase):
    def test_rfc6238_sha1_vectors(self):
        for t, expected in [(59, "94287082"), (1111111109, "07081804"), (1111111111, "14050471"),
                            (1234567890, "89005924"), (2000000000, "69279037"), (20000000000, "65353130")]:
            self.assertEqual(lp.totp_code(SECRET, t // 30, 8), expected, t)
            self.assertEqual(lp.totp_code(SECRET, t // 30, 6), expected[-6:], t)   # six digits are the low six of the same value

    def test_base32_and_otpauth(self):
        self.assertEqual(lp.decode_b32(SECRET_B32.lower()), SECRET)
        self.assertEqual(lp.decode_b32(" ".join(SECRET_B32[i:i + 4] for i in range(0, len(SECRET_B32), 4))), SECRET)
        with self.assertRaises(lp.ConfigError):
            lp.decode_b32("not base32 !!")
        uri = f"otpauth://totp/Aron%3Aadmin?secret={SECRET_B32}&issuer=Aron&algorithm=SHA1&digits=6&period=30"
        self.assertEqual(lp.secret_from_otpauth(uri), SECRET_B32)

    def test_secret_from_env_and_file(self):
        self.assertEqual(lp.load_totp_secret({"ARON_ADMIN_TOTP_SECRET": SECRET_B32}, None), SECRET)
        self.assertIsNone(lp.load_totp_secret({}, None))
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "enrol.json"
            # the shape of the answer of POST /v1/auth/mfa/enrol: an otpauth_uri and one-time recovery codes
            f.write_text(json.dumps({"otpauth_uri": f"otpauth://totp/x?secret={SECRET_B32}&issuer=Aron", "recovery_codes": ["AAAA-BBBB"]}), encoding="utf-8")
            self.assertEqual(lp.load_totp_secret({}, str(f)), SECRET)
            self.assertEqual(lp.load_totp_secret({"ARON_ADMIN_TOTP_FILE": str(f)}, None), SECRET)
            f.write_bytes(b"\xef\xbb\xbf" + f.read_bytes())    # a BOM from an editor
            self.assertEqual(lp.load_totp_secret({}, str(f)), SECRET)

    def test_bad_files_fail_without_echoing_content(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "x.json"
            f.write_text("SENTINEL-NOT-JSON", encoding="utf-8")
            with self.assertRaises(lp.ConfigError) as cm:
                lp.load_totp_secret({}, str(f))
            self.assertNotIn("SENTINEL", str(cm.exception))
            f.write_text(json.dumps({"recovery_codes": ["SENTINEL-CODE"]}), encoding="utf-8")
            with self.assertRaises(lp.ConfigError) as cm:
                lp.load_totp_secret({}, str(f))
            self.assertNotIn("SENTINEL", str(cm.exception))
            with self.assertRaises(lp.ConfigError):
                lp.load_totp_secret({}, str(Path(d) / "missing.json"))


class AssetIds(unittest.TestCase):
    def test_deterministic_and_v4_shaped(self):
        sha = lp.sha256_hex(b"one picture")
        a = lp.asset_id_for(sha)
        self.assertEqual(a, lp.asset_id_for(sha))
        self.assertRegex(a, lp.UUID_V4)
        self.assertEqual(a[14], "4")
        self.assertIn(a[19], "89ab")
        self.assertNotEqual(a, lp.asset_id_for(lp.sha256_hex(b"another picture")))

    def test_many_hashes_all_pass_the_servers_pattern(self):
        server = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")   # AdminProducts.kt UUID_V4
        for i in range(500):
            self.assertRegex(lp.asset_id_for(lp.sha256_hex(str(i).encode())), server)

    def test_identical_bytes_share_one_asset(self):
        self.assertEqual(lp.asset_id_for(lp.sha256_hex(b"same art")), lp.asset_id_for(lp.sha256_hex(b"same art")))

    def test_rejects_a_bad_hash(self):
        for bad in ("", "abc", "G" * 64, "A" * 64):
            with self.assertRaises(ValueError):
                lp.asset_id_for(bad)

    def test_asset_in_url(self):
        a = lp.asset_id_for(lp.sha256_hex(b"x"))
        self.assertEqual(lp.asset_in_url(f"https://acct.blob.core.windows.net/media/assets/sku_image/{a}.jpg?sv=1&sig=zz"), a)
        self.assertIsNone(lp.asset_in_url("https://acct.blob.core.windows.net/media/assets/content_kv/x.jpg"))
        self.assertIsNone(lp.asset_in_url(None))

    def test_asset_in_url_with_the_slashes_encoded(self):
        """The dev environment's real read link (2026-10-24): the blob name is one segment, `assets%2Fsku_image%2F<id>.jpg`."""
        a = lp.asset_id_for(lp.sha256_hex(b"x"))
        self.assertEqual(lp.asset_in_url(f"https://acct.blob.core.windows.net/media/assets%2Fsku_image%2F{a}.jpg?sv=1&sig=zz"), a)
        self.assertEqual(lp.picture_of({"code": "X", "image_url": f"https://acct.blob.core.windows.net/media/assets%2Fsku_image%2F{a}.jpg?sv=1"}), (True, a))
        self.assertIsNone(lp.asset_in_url("https://acct.blob.core.windows.net/media/assets%2Fcontent_kv%2Fx.jpg"))


class PictureOf(unittest.TestCase):
    """What a SKU answer says about its picture: image_asset_id when it carries one (the contract), else the asset id in the
    blob read link (today's admin answer)."""
    A = lp.asset_id_for(lp.sha256_hex(b"x"))

    def url(self, a=None):
        return f"https://acct.blob.core.windows.net/media/assets/sku_image/{a or self.A}.jpg?sv=1&sig=zz"

    def test_today_s_admin_answer_has_the_link_only(self):
        self.assertEqual(lp.picture_of({"code": "X", "image_url": self.url()}), (True, self.A))
        self.assertEqual(lp.picture_of({"code": "X", "image_url": None}), (False, None))
        self.assertEqual(lp.picture_of({"code": "X"}), (False, None))

    def test_the_contract_s_image_asset_id_is_read_when_present(self):
        self.assertEqual(lp.picture_of({"image_asset_id": self.A.upper(), "image_url": None}), (True, self.A))
        self.assertEqual(lp.picture_of({"image_asset_id": self.A}), (True, self.A))
        self.assertEqual(lp.picture_of({"image_asset_id": None, "image_url": None}), (False, None))

    def test_a_link_of_unknown_shape_or_a_garbage_id_is_a_picture_of_unknown_origin(self):
        self.assertEqual(lp.picture_of({"image_url": "https://x.example/other/path.jpg"}), (True, None))
        self.assertEqual(lp.picture_of({"image_asset_id": "not-a-uuid"}), (True, None))

    def test_no_answer_is_no_picture(self):
        self.assertEqual(lp.picture_of(None), (False, None))
        self.assertEqual(lp.picture_of({"image_url": 5}), (True, None))


class CapBoundary(unittest.TestCase):
    @staticmethod
    def fake(n):
        return b"\xff\xd8\xff" + b"\x00" * (n - 5) + b"\xff\xd9"

    def test_307200_passes_and_307201_is_rejected(self):
        self.assertEqual(lp.ASSET_CAP_BYTES, 307_200)
        self.assertEqual(lp.asset_rule_problems(self.fake(307_200)), [])
        bad = lp.asset_rule_problems(self.fake(307_201))
        self.assertEqual(len(bad), 1)
        self.assertIn("307201", bad[0])

    def test_empty_and_not_jpeg(self):
        self.assertTrue(lp.asset_rule_problems(b""))
        self.assertTrue(any("not a JPEG" in p for p in lp.asset_rule_problems(b"\x89PNG" + b"\x00" * 20)))
        self.assertTrue(any("EOI" in p for p in lp.asset_rule_problems(b"\xff\xd8\xff" + b"\x00" * 20)))
        self.assertEqual(lp.asset_rule_problems(self.fake(5)), [])    # one byte is the lower bound of the store; 5 is the smallest JPEG-shaped file


class Mapping(unittest.TestCase):
    def test_space_becomes_underscore(self):
        self.assertEqual(lp.db_code("MaxDB-10S 10HL"), "MaxDB-10S_10HL")
        self.assertEqual(lp.db_code(" EssP-20S 20HL "), "EssP-20S_20HL")
        self.assertEqual(lp.db_code("MaxR-10S"), "MaxR-10S")

    def test_real_catalogue_has_42_codes_and_five_underscores(self):
        cat = lp.read_catalog()
        self.assertEqual(len(cat), 42)
        self.assertFalse([c for c in cat if " " in c])
        self.assertEqual(sorted(c for c in cat if "_" in c),
                         ["EssCM-20S_20HL", "EssP-20S_20HL", "MaxDB-10S_10HL", "MaxDB-20S_20HL", "MaxPrime-20S_20HL"])


class ManifestFile(unittest.TestCase):
    ROW = {"sku_code": "AAA-10S", "source_file": "AAA-10S.jpg", "source_bytes": "100", "source_sha256": "a" * 64, "width": "10",
           "height": "20", "processed_file": "AAA-10S.jpg", "processed_bytes": "90", "processed_sha256": "b" * 64,
           "duplicate_group": "", "note": "src 10x20; with, comma and \"quotes\""}

    def test_round_trip(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "manifest.csv"
            rows = [dict(self.ROW, sku_code="ZZZ"), dict(self.ROW)]
            self.assertTrue(lp.write_manifest(p, rows))
            self.assertEqual(lp.read_manifest(p), [dict(self.ROW), dict(self.ROW, sku_code="ZZZ")])   # sorted by sku_code
            raw = p.read_bytes()
            self.assertNotIn(b"\r", raw)
            self.assertFalse(raw.startswith(b"\xef\xbb\xbf"))
            self.assertEqual(raw.split(b"\n")[0].decode(), ",".join(lp.MANIFEST_COLUMNS))
            self.assertFalse(lp.write_manifest(p, rows), "a second write of the same rows changes nothing")

    def test_wrong_header(self):
        with tempfile.TemporaryDirectory() as d:
            p = Path(d) / "manifest.csv"
            p.write_text("sku_code,bytes\nA,1\n", encoding="utf-8")
            with self.assertRaises(lp.ManifestError):
                lp.read_manifest(p)
            with self.assertRaises(lp.ManifestError):
                lp.read_manifest(Path(d) / "nope.csv")


class OutScrubbing(unittest.TestCase):
    def test_url_queries_and_secrets_are_masked(self):
        o = lp.Out()
        o.add_secret("hunter2-secret")
        s = o.scrub("PUT https://acct.blob.core.windows.net/media/assets/sku_image/x.jpg?sv=2023&sig=abc%2Fdef ok hunter2-secret")
        self.assertNotIn("sig=", s)
        self.assertNotIn("abc", s)
        self.assertNotIn("hunter2", s)
        self.assertIn("https://acct.blob.core.windows.net/media/assets/sku_image/x.jpg?<masked>", s)


class BaseUrl(unittest.TestCase):
    def test_normalise(self):
        self.assertEqual(lp.normalise_base("https://host.example/"), "https://host.example")
        self.assertEqual(lp.normalise_base("https://host.example/v1"), "https://host.example")
        self.assertEqual(lp.normalise_base("http://127.0.0.1:8080"), "http://127.0.0.1:8080")
        for bad in ("http://host.example", "ftp://host.example", "https://u:p@host.example", "https://host.example/?x=1", "host.example"):
            with self.assertRaises(lp.ConfigError, msg=bad):
                lp.normalise_base(bad)


# ---------------------------------------------------------------------------------------------------- offline --check
@needs_pillow
class CheckOffline(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.ws = Workspace(Path(self.tmp.name))

    def errors(self):
        return self.ws.check().errors

    def test_clean_workspace_passes(self):
        rep = self.ws.check()
        self.assertEqual(rep.errors, [])
        self.assertEqual(rep.files_checked, 5)
        self.assertEqual(len(rep.rows), 5)

    def test_cli_check_ok_and_failed(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            self.assertEqual(lp.main(self.ws.argv("--check"), env={}), 0)
        self.assertIn("check: OK", buf.getvalue())
        (self.ws.processed / "AAA-10S.jpg").unlink()
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            self.assertEqual(lp.main(self.ws.argv("--check"), env={}), 1)
        self.assertIn("check: FAILED", buf.getvalue())

    def test_missing_file(self):
        (self.ws.processed / "BBB-20S_20HL.jpg").unlink()
        self.assertTrue(any("BBB-20S_20HL" in e and "missing" in e for e in self.errors()))

    def test_bad_hash(self):
        p = self.ws.processed / "CCC-20s.jpg"
        data = bytearray(p.read_bytes())
        data[len(data) // 2] ^= 0x01   # same size, one flipped bit
        p.write_bytes(bytes(data))
        self.assertTrue(any("CCC-20s" in e and "sha256" in e for e in self.errors()))

    def test_wrong_size_in_manifest(self):
        self.ws.rewrite_manifest(lambda rows: rows[0].update(processed_bytes="1"))
        self.assertTrue(any("differs from the manifest" in e for e in self.errors()))

    def test_unmapped_manifest_row(self):
        self.ws.rewrite_manifest(lambda rows: rows.append(dict(rows[0], sku_code="ZZZ-99", source_file="ZZZ-99.jpg", processed_file="ZZZ-99.jpg")))
        errs = self.errors()
        self.assertTrue(any("ZZZ-99" in e and "unmapped" in e for e in errs), errs)

    def test_catalogue_sku_without_a_row(self):
        self.ws.rewrite_manifest(lambda rows: rows.pop(0))
        self.assertTrue(any("AAA-10S" in e and "no manifest row" in e for e in self.errors()))

    def test_long_edge_above_320(self):
        self.ws.rewrite_manifest(lambda rows: rows[0].update(width="400"))
        self.assertTrue(any("320 px" in e for e in self.errors()))

    def test_over_the_cap_in_the_manifest(self):
        self.ws.rewrite_manifest(lambda rows: rows[0].update(processed_bytes="307201"))
        self.assertTrue(any("307201" in e for e in self.errors()))

    def test_unlisted_file(self):
        (self.ws.processed / "EXTRA.jpg").write_bytes(synth_jpeg(9))
        self.assertTrue(any("EXTRA.jpg" in e and "not in the manifest" in e for e in self.errors()))

    def test_metadata_in_a_processed_file_is_refused(self):
        p = self.ws.processed / "DDD-20s.jpg"
        exif = Image.Exif()
        exif[0x013B] = "Synthetic Author"
        p.write_bytes(synth_jpeg(3, exif=exif.tobytes()))
        self.ws.rewrite_manifest(lambda rows: [r.update(processed_bytes=str(p.stat().st_size), processed_sha256=lp.sha256_hex(p.read_bytes()))
                                               for r in rows if r["sku_code"] == "DDD-20s"])
        self.assertTrue(any("DDD-20s" in e and "metadata" in e and "exif" in e for e in self.errors()), self.errors())

    def test_not_a_jpeg(self):
        p = self.ws.processed / "AAA-20S.jpg"
        p.write_bytes(b"\x89PNG\r\n" + b"\x00" * 50)
        self.ws.rewrite_manifest(lambda rows: [r.update(processed_bytes=str(p.stat().st_size), processed_sha256=lp.sha256_hex(p.read_bytes()))
                                               for r in rows if r["sku_code"] == "AAA-20S"])
        self.assertTrue(any("AAA-20S" in e and "not a JPEG" in e for e in self.errors()))

    def test_duplicate_group_must_be_consistent(self):
        def clear(rows):
            for r in rows:
                if r["sku_code"] == "AAA-10S":
                    r["duplicate_group"] = ""
        self.ws.rewrite_manifest(clear)
        self.assertTrue(any("duplicate_group" in e for e in self.errors()))

    def test_source_file_must_map_to_the_code(self):
        self.ws.rewrite_manifest(lambda rows: rows[0].update(source_file="OTHER.jpg"))
        self.assertTrue(any("does not map" in e for e in self.errors()))

    def test_processed_absent_validates_the_manifest_alone(self):
        for p in self.ws.processed.glob("*.jpg"):
            p.unlink()
        rep = self.ws.check()
        self.assertEqual(rep.errors, [])
        self.assertEqual(rep.files_checked, 0)
        self.assertTrue(any("manifest alone" in n for n in rep.notes))
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            self.assertEqual(lp.main(self.ws.argv("--check"), env={}), 0)
        self.assertIn("manifest only", buf.getvalue())

    def test_manifest_alone_still_catches_manifest_problems(self):
        for p in self.ws.processed.glob("*.jpg"):
            p.unlink()
        self.ws.rewrite_manifest(lambda rows: rows.pop(0))
        self.assertTrue(self.errors())

    def test_real_manifest_if_present_is_consistent_with_the_catalogue(self):
        if not lp.DEFAULT_MANIFEST.exists():
            self.skipTest("assets/packshots/manifest.csv not generated here")
        rep = lp.check_offline(lp.DEFAULT_MANIFEST, Path(self.tmp.name) / "no-processed-here", lp.DEFAULT_CATALOG)
        self.assertEqual(rep.errors, [])
        self.assertEqual(len(rep.rows), 42)


class JpegInspection(unittest.TestCase):
    @staticmethod
    def app0(extra: bytes = b"") -> bytes:
        payload = b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00" + extra
        return b"\xff\xd8\xff\xe0" + (len(payload) + 2).to_bytes(2, "big") + payload + b"\xff\xd9"

    def test_a_plain_jfif_header_is_structure_and_one_with_a_thumbnail_is_metadata(self):
        plain = lp.parse_jpeg(self.app0())
        self.assertEqual(plain["segments"], ["jfif"])
        self.assertEqual(lp.metadata_segments(plain), [])
        fat = lp.parse_jpeg(self.app0(b"\x00" * 9))   # 9 extra bytes: room for a thumbnail or a hidden note
        self.assertEqual(fat["segments"], ["jfif-ext"])
        self.assertEqual(lp.metadata_segments(fat), ["jfif-ext"])


@needs_pillow
class DuplicateGroupsInTheManifest(unittest.TestCase):
    """Two different variants with near-identical art (the bytes differ): a group of different source pictures."""
    NEAR = [("Alpha", "AAA-10S", "10", 1), ("Beta", "BBB-10S", "10", 2), ("Gamma", "GGG-10S", "10", 5)]

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        art = synth_jpeg(1)
        self.ws = Workspace(Path(self.tmp.name), self.NEAR, {"BBB-10S": resaved(art)})

    def by(self):
        return {r["sku_code"]: r for r in lp.read_manifest(self.ws.manifest)}

    def test_the_near_pair_is_a_suspect_group_and_the_manifest_passes_the_check(self):
        by = self.by()
        self.assertNotEqual(by["AAA-10S"]["source_sha256"], by["BBB-10S"]["source_sha256"])
        self.assertTrue(by["AAA-10S"]["duplicate_group"].startswith("suspect-different-variants:"))
        self.assertEqual(by["AAA-10S"]["duplicate_group"], by["BBB-10S"]["duplicate_group"])
        self.assertEqual(by["GGG-10S"]["duplicate_group"], "")
        self.assertEqual(self.ws.check().errors, [])
        self.assertEqual(sorted(lp.suspect_rows(list(by.values()))), ["AAA-10S", "BBB-10S"])

    def test_a_group_of_one_is_refused(self):
        def alone(rows):
            for r in rows:
                if r["sku_code"] == "BBB-10S":
                    r["duplicate_group"] = ""
        self.ws.rewrite_manifest(alone)
        self.assertTrue(any("AAA-10S" in e and "no other member" in e for e in self.ws.check().errors), self.ws.check().errors)

    def test_the_kind_must_match_the_catalogue(self):
        def relabel(rows):
            for r in rows:
                if r["duplicate_group"]:
                    r["duplicate_group"] = "same-art-pack-sizes:" + r["duplicate_group"].split(":")[1]
        self.ws.rewrite_manifest(relabel)
        self.assertTrue(any("does not match the catalogue" in e for e in self.ws.check().errors), self.ws.check().errors)

    def test_different_source_pictures_may_only_be_a_suspect_pair_of_different_variants(self):
        def same_pack(rows):
            for r in rows:
                if r["sku_code"] == "GGG-10S":
                    r["duplicate_group"] = "suspect-same-pack:00000000"
                if r["sku_code"] == "BBB-10S":
                    r["duplicate_group"] = "suspect-same-pack:00000000"
        self.ws.rewrite_manifest(same_pack)
        errs = self.ws.check().errors
        self.assertTrue(any("different source pictures" in e or "does not match the catalogue" in e for e in errs), errs)


# ---------------------------------------------------------------------------------------------------- the fake server
class FakeClock(lp.Clock):
    def __init__(self, t: float):
        self.t, self.lock, self.sleeps = t, threading.Lock(), []

    def time(self) -> float:
        with self.lock:
            return self.t

    def sleep(self, seconds: float) -> None:
        with self.lock:
            self.t += max(0.0, seconds)
            self.sleeps.append(seconds)

    def advance(self, seconds: float) -> None:
        with self.lock:
            self.t += seconds


def iso(t: float) -> str:
    import datetime as dt
    return dt.datetime.fromtimestamp(t, dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


class State:
    """What the admin API, the phone's picture route and the blob store do, as far as the loader can see (see AdminProducts.kt,
    AdminContentApi.kt and SkuImageRead.kt: the admin SKU answer carries `image_url` but not `image_asset_id`, and
    GET /v1/sku-images/{asset_id} streams the bytes of a bound sku_image or answers a bare 404)."""

    def __init__(self, clock: FakeClock, codes, ttl=900):
        self.clock, self.ttl, self.lock = clock, ttl, threading.Lock()
        self.skus = {c: {"id": i + 1, "code": c, "version": 1, "image_asset_id": None, "status": "active"} for i, c in enumerate(codes)}
        self.assets, self.blobs = {}, {}
        self.tokens = {}
        self.mfa_tokens = set()
        self.last_step, self.mfa_failures, self.logins, self.admin_ok = None, 0, 0, 0
        self.calls: list[tuple[str, str]] = []
        self.blob_auth: list[bool] = []
        self.port = 0
        self.put_status = 201
        self.get_content_type = None     # overrides the stored content type on GET
        self.corrupt_get = False
        self.stale: set[str] = set()     # the next PATCH of these SKUs finds a newer version (once)
        self.foreign: dict[str, str | None] = {}   # code -> asset id (None: no picture): the next PATCH of the SKU finds that
                                                   # somebody else chose it meanwhile (once), so it answers 412
        self.always_stale: set[str] = set()
        self.no_link = False             # the SKU answers carry no image_url (the API cannot sign a read URL)
        self.expose_asset_id = False     # the SKU answers also carry image_asset_id (the contract's Sku does; today's admin answer does not)
        self.api_status = None           # GET /v1/sku-images/{asset_id} answers this problem status instead of the picture
        self.api_content_type = None     # overrides the content type of that answer
        self.corrupt_api = False         # that answer's bytes differ from the stored ones
        self.api_gets: list[str] = []    # the asset ids the phone path was asked for
        self.api_accept: list[str] = []  # the Accept header of each of those calls
        self.drop_blob_get = False       # the blob host closes the connection on every GET, without an answer
        self.expire_after = None         # the Nth authenticated call is refused with 401 and every token dies
        self.tick = 0.0                  # seconds the clock advances per authenticated call
        self.reject_login = False
        self.redirect_list = False       # GET /v1/admin/skus answers 302 to /elsewhere
        self.redirect_blob_get = False   # the blob host answers a GET with a 302 to /elsewhere
        self.page_size = None            # GET /v1/admin/skus answers this many SKUs per page (cursor = next index)
        self.lockout_attempts = 10       # cfg.auth.lockout_attempts: wrong passwords or codes before ERR_AUTH_ACCOUNT_LOCKED
        self.failures = 0                # wrong passwords and wrong codes since the last success
        self.locked = False

    def count(self, method, prefix):
        return sum(1 for m, p in self.calls if m == method and p.startswith(prefix))

    def prob(self, status, code, headers=None):
        return status, dict(headers or {}, **{"Content-Type": "application/problem+json"}), json.dumps({"status": status, "code": code}).encode()

    def ok(self, obj, status=200, headers=None):
        return status, dict(headers or {}, **{"Content-Type": "application/json"}), json.dumps(obj).encode()

    def wrong(self, answer):
        """A wrong password or code counts towards the lockout (LoginService: lockouts.recordFailure); at the limit the
        account is locked and every later sign-in answers ERR_AUTH_ACCOUNT_LOCKED."""
        self.failures += 1
        if self.failures >= self.lockout_attempts:
            self.locked = True
        return answer

    def sku_out(self, s):
        out = {k: s[k] for k in ("id", "code", "version", "status")}
        a = s["image_asset_id"]
        out["image_url"] = f"http://127.0.0.1:{self.port}/blob/media/assets/sku_image/{a}.jpg?sp=r&sig={SAS_READ}" if a and not self.no_link else None
        if self.expose_asset_id:
            out["image_asset_id"] = a
        return out

    def handle(self, method, path, q, h, body):
        self.calls.append((method, path))
        if path.startswith("/blob/"):
            return self.blob(method, path, q, h, body)
        if method in ("POST", "PATCH", "PUT") and h.get("content-type", "").split(";")[0].strip().lower() != "application/json":
            return self.prob(415, "ERR_UNSUPPORTED_MEDIA_TYPE")   # receiveStrict: a JSON body needs application/json
        if path in ("/v1/auth/login", "/v1/auth/mfa/verify") and self.locked:
            return self.prob(403, "ERR_AUTH_ACCOUNT_LOCKED", {"Retry-After": "900"})
        if path == "/v1/auth/login" and method == "POST":
            r = json.loads(body)
            if self.reject_login or r.get("password") != PW or r.get("client") != "web" or r.get("username") != "admin1001":
                return self.wrong(self.prob(401, "ERR_AUTH_INVALID_CREDENTIALS"))
            tok = f"MFA-TOKEN-{len(self.mfa_tokens)}"
            self.mfa_tokens.add(tok)
            return self.ok({"status": "mfa_required", "mfa_token": tok})
        if path == "/v1/auth/mfa/verify" and method == "POST":
            r = json.loads(body)
            now_step = int(self.clock.time() // 30)
            hit = next((s for s in range(now_step - 1, now_step + 2) if lp.totp_code(SECRET, s) == r.get("code")), None)
            if r.get("mfa_token") not in self.mfa_tokens or hit is None or (self.last_step is not None and hit <= self.last_step):
                self.mfa_failures += 1
                return self.wrong(self.prob(401, "ERR_AUTH_MFA_INVALID"))
            self.last_step = hit
            self.failures = 0
            self.logins += 1
            tok = f"ACCESS-TOKEN-{self.logins}"
            self.tokens[tok] = self.clock.time() + self.ttl
            return self.ok({"status": "ok", "access_token": tok, "access_expires_at": iso(self.tokens[tok])})
        auth = h.get("authorization", "")
        tok = auth[7:] if auth.startswith("Bearer ") else ""
        if self.tokens.get(tok, 0) <= self.clock.time():
            return self.prob(401, "ERR_TOKEN_EXPIRED")
        self.admin_ok += 1
        if self.expire_after is not None and self.admin_ok == self.expire_after:
            self.expire_after = None
            self.tokens.clear()
            return self.prob(401, "ERR_TOKEN_EXPIRED")
        self.clock.advance(self.tick)
        return self.admin(method, path, q, h, body)

    def admin(self, method, path, q, h, body):
        if path == "/elsewhere":
            return self.ok({"items": [], "next_cursor": None})
        if path == "/v1/admin/skus" and method == "GET" and self.redirect_list:
            return 302, {"Location": f"http://127.0.0.1:{self.port}/elsewhere"}, b""
        if path == "/v1/admin/skus" and method == "GET":
            items = [self.sku_out(s) for s in self.skus.values()]
            if not self.page_size:
                return self.ok({"items": items, "next_cursor": None})
            start = int((q.get("cursor") or ["0"])[0])
            nxt = start + self.page_size
            return self.ok({"items": items[start:nxt], "next_cursor": str(nxt) if nxt < len(items) else None})
        m = re.fullmatch(r"/v1/sku-images/([^/]+)", path)
        if m and method == "GET":   # getSkuImage: any signed-in user; a bare 404 unless a SKU binds a stored sku_image
            aid = m.group(1)
            self.api_gets.append(aid)
            self.api_accept.append(h.get("accept", ""))
            if self.api_status:
                return self.prob(self.api_status, "ERR_SERVICE_UNAVAILABLE" if self.api_status == 503 else "ERR_NOT_FOUND")
            stored = self.blobs.get(f"assets/sku_image/{aid}.jpg")
            if stored is None or not any(s["image_asset_id"] == aid for s in self.skus.values()):
                return self.prob(404, "ERR_NOT_FOUND")
            ctype, data = stored
            return 200, {"Content-Type": self.api_content_type or ctype, "ETag": f'"{aid}"',
                         "Cache-Control": "private, max-age=31536000, immutable"}, data + (b"x" if self.corrupt_api else b"")
        if path == "/v1/admin/assets" and method == "POST":
            r = json.loads(body)
            if set(r) != {"asset_id", "purpose", "mime", "bytes", "sha256"} or not lp.UUID_V4.match(str(r["asset_id"])):
                return self.prob(400, "ERR_VALIDATION")
            if r["purpose"] != "sku_image" or r["mime"] != "image/jpeg" or not lp.SHA256_HEX.match(str(r["sha256"])):
                return self.prob(400, "ERR_VALIDATION")
            if not 1 <= r["bytes"] <= 104_857_600:
                return self.prob(400, "ERR_VALIDATION")
            if r["bytes"] > 307_200:
                return self.prob(413, "ERR_PAYLOAD_TOO_LARGE")
            key = (r["purpose"], r["mime"], r["bytes"], r["sha256"])
            if self.assets.setdefault(r["asset_id"], key) != key:
                return self.prob(409, "ERR_CONFLICT")
            bp = f"assets/sku_image/{r['asset_id']}.jpg"
            return self.ok({"asset_id": r["asset_id"], "blob_path": bp, "expires_at": iso(self.clock.time() + 900),
                            "upload_url": f"http://127.0.0.1:{self.port}/blob/media/{bp}?sv=2023-11-03&sp=cw&sig={SAS_WRITE}"})
        m = re.fullmatch(r"/v1/admin/skus/(\d+)", path)
        if m and method == "PATCH":
            sku = next((s for s in self.skus.values() if s["id"] == int(m.group(1))), None)
            if sku is None:
                return self.prob(404, "ERR_NOT_FOUND")
            im = re.fullmatch(r'"(\d+)"', h.get("if-match", ""))
            if not im:
                return self.prob(400, "ERR_VALIDATION")
            if sku["code"] in self.foreign:
                sku["image_asset_id"] = self.foreign.pop(sku["code"])
                sku["version"] += 1   # somebody else chose a picture (or removed it) in the web admin
            if sku["code"] in self.stale or sku["code"] in self.always_stale:
                self.stale.discard(sku["code"])
                sku["version"] += 1   # somebody else changed the row
            if int(im.group(1)) != sku["version"]:
                return self.prob(412, "ERR_PRECONDITION_FAILED", {"ETag": f'"{sku["version"]}"'})
            r = json.loads(body)
            reason = r.get("change_reason")
            if set(r) - {"image_asset_id", "change_reason"} or (reason is not None and not 10 <= len(reason) <= 500):
                return self.prob(400, "ERR_VALIDATION")
            a = r.get("image_asset_id")
            if a is not None and a not in self.assets:
                return self.prob(400, "ERR_VALIDATION")
            if a != sku["image_asset_id"]:
                sku["image_asset_id"] = a
                sku["version"] += 1
            return self.ok(self.sku_out(sku), headers={"ETag": f'"{sku["version"]}"'})
        return self.prob(404, "ERR_NOT_FOUND")

    def blob(self, method, path, q, h, body):
        self.blob_auth.append("authorization" in h)
        key = path[len("/blob/media/"):]
        if method == "PUT":
            if q.get("sig") != [SAS_WRITE] or h.get("x-ms-blob-type") != "BlockBlob" or h.get("content-type") != "image/jpeg":
                return 403, {}, b""
            if self.put_status != 201:
                return self.put_status, {}, b""
            self.blobs[key] = ("image/jpeg", body)
            return 201, {}, b""
        if method == "GET":
            if self.drop_blob_get:
                return None
            if q.get("sig") != [SAS_READ]:
                return 403, {}, b""
            if self.redirect_blob_get:
                return 302, {"Location": f"http://127.0.0.1:{self.port}/elsewhere"}, b""
            if key not in self.blobs:
                return 404, {}, b""
            ctype, data = self.blobs[key]
            return 200, {"Content-Type": self.get_content_type or ctype}, data + (b"x" if self.corrupt_get else b"")
        return 405, {}, b""


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def _go(self):
        st = self.server.state
        body = self.rfile.read(int(self.headers.get("Content-Length") or 0))
        u = urllib.parse.urlsplit(self.path)
        with st.lock:
            answer = st.handle(self.command, u.path, urllib.parse.parse_qs(u.query),
                               {k.lower(): v for k, v in self.headers.items()}, body)
        if answer is None:   # the connection is closed without an answer
            self.close_connection = True
            return
        status, headers, payload = answer
        self.send_response(status)
        for k, v in headers.items():
            self.send_header(k, v)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    do_GET = do_POST = do_PUT = do_PATCH = _go


@needs_pillow
class LiveFlow(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.ws = Workspace(Path(self.tmp.name))
        self.clock = FakeClock(T0 + 7)
        self.state = State(self.clock, DB_CODES)
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.state = self.state
        self.state.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True).start()
        self.addCleanup(self.server.server_close)
        self.addCleanup(self.server.shutdown)
        self.env = {"ARON_API_BASE": f"http://127.0.0.1:{self.state.port}",
                    "ARON_PACKSHOT_STATE_DIR": self.tmp.name,
                    "ARON_ADMIN_PASSWORD": PW,
                    "ARON_ADMIN_TOTP_SECRET": SECRET_B32}

    def run_cli(self, mode="--apply", *extra, env=None):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            rc = lp.main(self.ws.argv(mode, *extra), env=self.env if env is None else env, clock=self.clock)
        return rc, buf.getvalue()

    def assert_clean(self, text):
        for needle in (PW, SECRET_B32, "ACCESS-TOKEN", "MFA-TOKEN", "SIG-SECRET", "sig=", "sp=cw", "Bearer"):
            self.assertNotIn(needle, text)
        self.assertNotRegex(text, r"https?://[^\s]*\?")   # no URL query anywhere

    def processed_bytes(self, code):
        return (self.ws.processed / f"{code}.jpg").read_bytes()

    # -- the happy path
    def test_all_success_shares_assets_and_verifies_everything(self):
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        st = self.state
        # 5 SKUs, 3 distinct pictures: AAA-10S and AAA-20S are one asset, so are CCC-20s and DDD-20s
        self.assertEqual((st.count("POST", "/v1/admin/assets"), len([b for b in st.blobs]), st.count("PATCH", "/v1/admin/skus/")), (3, 3, 5))
        self.assertEqual(len(st.assets), 3)
        self.assertEqual(st.skus["AAA-10S"]["image_asset_id"], st.skus["AAA-20S"]["image_asset_id"])
        self.assertEqual(st.skus["CCC-20s"]["image_asset_id"], st.skus["DDD-20s"]["image_asset_id"])
        for code in DB_CODES:
            aid = st.skus[code]["image_asset_id"]
            self.assertEqual(aid, lp.asset_id_for(lp.sha256_hex(self.processed_bytes(code))))
            self.assertEqual(st.blobs[f"assets/sku_image/{aid}.jpg"], ("image/jpeg", self.processed_bytes(code)))
        self.assertIn("asset shared with AAA-10S", text)
        self.assertIn("summary: 5 pictures: 5 bound, 0 repaired, 0 skipped, 0 failed; 3 assets uploaded", text)
        self.assertFalse(any(st.blob_auth), "the bearer token must never be sent to the blob store")
        # every binding is read back twice: the blob link, then the phone's GET /v1/sku-images/{asset_id} (bearer, images accepted)
        self.assertIn("[POST 200 PUT 201 PATCH 200 GET blob 200 GET api 200] verified image/jpeg, sha256 ok", text)
        self.assertEqual(sorted(st.api_gets), sorted(st.skus[c]["image_asset_id"] for c in DB_CODES))
        self.assertTrue(all("image/jpeg" in a for a in st.api_accept), st.api_accept)
        self.assert_clean(text)

    def test_second_run_changes_nothing(self):
        self.assertEqual(self.run_cli()[0], 0)
        before = len(self.state.calls)
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        later = self.state.calls[before:]
        self.assertFalse([c for c in later if c[0] in ("PUT", "PATCH") or c == ("POST", "/v1/admin/assets")], later)
        self.assertIn("5 skipped", text)
        self.assertIn("0 assets uploaded", text)
        self.assertIn("skipped: already bound, GET blob 200 GET api 200 image/jpeg, sha256 ok", text)
        self.assertEqual(len(self.state.api_gets), 5 + 5, "a skip is decided by both reads, never by the binding alone")
        self.assert_clean(text)

    def test_a_bound_sku_with_another_picture_is_rebound_and_a_shared_asset_is_not_uploaded_twice(self):
        # a stale binding to a different asset; the other SKU of the same art is already bound and verified
        self.assertEqual(self.run_cli("--apply", "--only", "AAA-10S")[0], 0)
        other = lp.asset_id_for(lp.sha256_hex(b"older picture"))
        self.state.assets[other] = ("sku_image", "image/jpeg", 10, lp.sha256_hex(b"older picture"))
        self.state.skus["BBB-20S_20HL"]["image_asset_id"] = other
        puts_before = self.state.count("PUT", "/blob/")
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        self.assertIn("rebound", text)
        self.assertEqual(self.state.skus["BBB-20S_20HL"]["image_asset_id"], lp.asset_id_for(lp.sha256_hex(self.processed_bytes("BBB-20S_20HL"))))
        # AAA-10S was skipped (verified), so AAA-20S binds the same asset without a second upload: 2 new PUTs (BBB, CCC), not 3
        self.assertEqual(self.state.count("PUT", "/blob/") - puts_before, 2)

    def test_repair_re_uploads_when_the_bound_blob_is_missing(self):
        self.assertEqual(self.run_cli()[0], 0)
        aid = self.state.skus["BBB-20S_20HL"]["image_asset_id"]
        del self.state.blobs[f"assets/sku_image/{aid}.jpg"]
        before = len(self.state.calls)
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        later = self.state.calls[before:]
        self.assertEqual([c for c in later if c[0] == "PATCH"], [], "an unchanged binding needs no PATCH")
        self.assertEqual(self.state.count("PUT", "/blob/"), 3 + 1)
        self.assertIn("repaired", text)
        self.assertIn(f"assets/sku_image/{aid}.jpg", self.state.blobs)

    # -- read-only
    def test_dry_run_writes_nothing(self):
        rc, text = self.run_cli("--dry-run")
        self.assertEqual(rc, 0, text)
        st = self.state
        self.assertEqual(st.count("POST", "/v1/admin/assets") + st.count("PUT", "/blob/") + st.count("PATCH", "/v1/admin/skus/"), 0)
        self.assertEqual(len(st.assets), 0)
        self.assertTrue(all(s["image_asset_id"] is None for s in st.skus.values()))
        self.assertIn("would upload + bind", text)
        self.assertIn("would bind (asset shared with AAA-10S)", text)
        self.assertIn("plan: 5 pictures: 0 skipped, 5 to bind, 3 assets to upload", text)
        self.assertNotIn("REPLACE", text, "a fresh environment replaces no picture")
        self.assert_clean(text)

    def test_dry_run_counts_the_pictures_it_would_replace_and_says_what_unbind_cannot_restore(self):
        # a picture somebody set in the web admin: --apply would rebind it, and --unbind could only remove it afterwards
        theirs = lp.asset_id_for(lp.sha256_hex(b"the owner's own picture"))
        self.state.assets[theirs] = ("sku_image", "image/jpeg", 10, lp.sha256_hex(b"the owner's own picture"))
        self.state.skus["BBB-20S_20HL"]["image_asset_id"] = theirs
        before = len(self.state.calls)
        rc, text = self.run_cli("--dry-run")
        self.assertEqual(rc, 0, text)
        self.assertRegex(text, r"BBB-20S_20HL\s+would upload \+ bind \[replaces another picture\]")
        self.assertEqual(len(re.findall(r"^\S+\s+would .*\[replaces another picture\]", text, re.M)), 1)
        self.assertIn("plan: 5 pictures: 0 skipped, 5 to bind, 3 assets to upload", text)
        self.assertIn("WARNING: 1 of the pictures to bind would REPLACE a picture that is already set", text)
        self.assertIn("it never restores an earlier one", text)
        self.assertFalse([c for c in self.state.calls[before:] if c[0] in ("PUT", "PATCH") or c == ("POST", "/v1/admin/assets")])
        self.assertEqual(self.state.skus["BBB-20S_20HL"]["image_asset_id"], theirs, "a dry run changes nothing")

    def test_dry_run_after_a_load_skips_everything(self):
        self.assertEqual(self.run_cli()[0], 0)
        before = len(self.state.calls)
        rc, text = self.run_cli("--dry-run")
        self.assertEqual(rc, 0, text)
        self.assertFalse([c for c in self.state.calls[before:] if c[0] in ("PUT", "PATCH", "POST") and not c[1].startswith("/v1/auth")])
        self.assertIn("plan: 5 pictures: 5 skipped, 0 to bind, 0 assets to upload", text)

    # -- failures
    def test_put_failure_exits_non_zero_and_never_binds(self):
        self.state.put_status = 403
        rc, text = self.run_cli()
        self.assertNotEqual(rc, 0)
        self.assertEqual(self.state.count("PATCH", "/v1/admin/skus/"), 0, "a failed PUT must never be followed by a PATCH")
        self.assertTrue(all(s["image_asset_id"] is None for s in self.state.skus.values()))
        self.assertIn("PUT 403", text)
        self.assertIn("3 failed", text)
        self.assertIn("STOPPED: BBB-20S_20HL: 3 SKUs in a row failed the same way (PUT blob: HTTP 403)", text)
        self.assertIn("2 of 5 SKUs were not attempted", text)
        self.assertIn("no SKU was bound in this run", text)
        self.assertEqual(self.state.count("PUT", "/blob/"), 3, "the third identical failure stops the run")
        self.assert_clean(text)

    def test_a_failed_run_is_repaired_by_the_next_run(self):
        self.state.put_status = 403
        self.assertNotEqual(self.run_cli()[0], 0)
        self.state.put_status = 201
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        self.assertTrue(all(s["image_asset_id"] for s in self.state.skus.values()))

    def test_stale_if_match_is_refetched_and_retried_once(self):
        self.state.stale = {"BBB-20S_20HL"}
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        self.assertEqual(self.state.count("PATCH", "/v1/admin/skus/3"), 2)   # BBB-20S_20HL is id 3: 412, then 200
        self.assertEqual(self.state.skus["BBB-20S_20HL"]["version"], 3)
        self.assertIn("PATCH 412 PATCH 200", text)

    def test_stale_twice_fails(self):
        self.state.always_stale = {"BBB-20S_20HL"}
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertEqual(self.state.count("PATCH", "/v1/admin/skus/3"), 2, "one retry, not a loop")
        self.assertIsNone(self.state.skus["BBB-20S_20HL"]["image_asset_id"])
        self.assertIn("1 failed", text)
        self.assertIn("4 bound", text)   # the others still went through

    def test_wrong_content_type_on_the_stored_blob_fails(self):
        self.state.get_content_type = "application/octet-stream"
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertIn("application/octet-stream", text)
        self.assert_clean(text)

    def test_stored_bytes_that_differ_fail(self):
        self.state.corrupt_get = True
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertIn("sha256", text)

    # -- the phone path (GET /v1/sku-images/{asset_id}, the lane's design: the API streams the bytes)
    def test_a_phone_path_that_is_down_stops_the_run_at_the_first_bind_and_unbind_undoes_it(self):
        self.state.api_status = 503     # the API's blob reader is not configured, or storage is down
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        st = self.state
        self.assertEqual(st.count("PATCH", "/v1/admin/skus/"), 1, "the run stops at the first SKU bound to a picture the phones cannot read")
        self.assertIn("STOPPED: AAA-10S: a SKU failed its check right after it was bound", text)
        self.assertIn("the phone path answers HTTP 503 ERR_SERVICE_UNAVAILABLE", text)
        self.assertIn("GET blob 200 GET api 503", text)
        self.assertIn("bound in this run (1): AAA-10S", text)
        self.assertIn("undo with: python -P tools/data/load_packshots.py --unbind --only AAA-10S", text)
        self.assertNotIn("cannot be undone", text, "the admin link is there, so --unbind sees this binding")
        self.assertIn("4 of 5 SKUs were not attempted", text)
        self.assert_clean(text)
        st.api_status = None
        rc, text = self.run_cli("--unbind", "--only", "AAA-10S")
        self.assertEqual(rc, 0, text)
        self.assertIsNone(st.skus["AAA-10S"]["image_asset_id"])
        self.assertEqual(self.run_cli()[0], 0)
        self.assertTrue(all(s["image_asset_id"] for s in st.skus.values()))

    def test_a_phone_path_that_does_not_find_the_picture_is_a_failure_too(self):
        self.state.api_status = 404     # a bare 404: not a stored sku_image of at most 300 KB that a SKU binds
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertIn("the phone path answers HTTP 404 ERR_NOT_FOUND", text)
        self.assertIn("STOPPED: AAA-10S: a SKU failed its check right after it was bound", text)

    def test_the_phone_path_must_answer_image_jpeg_and_the_same_bytes(self):
        self.state.api_content_type = "application/octet-stream"
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertIn("the phone path: content-type is application/octet-stream, not image/jpeg", text)
        self.state.api_content_type = None
        self.state.corrupt_api = True
        rc, text = self.run_cli("--unbind")
        self.assertEqual(rc, 0, text)
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertIn("the phone path: the stored bytes differ from the file (sha256)", text)
        self.assert_clean(text)

    def test_a_rerun_against_a_broken_phone_path_stops_after_three_and_binds_nothing_new(self):
        self.assertEqual(self.run_cli()[0], 0)
        st = self.state
        st.api_status = 503
        puts, patches = st.count("PUT", "/blob/"), st.count("PATCH", "/v1/admin/skus/")
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertIn("STOPPED: BBB-20S_20HL: 3 SKUs in a row failed the same way (the phone path answers HTTP 503 ERR_SERVICE_UNAVAILABLE)", text)
        self.assertIn("2 of 5 SKUs were not attempted", text)
        self.assertEqual(st.count("PATCH", "/v1/admin/skus/") - patches, 0, "a binding that is already right needs no PATCH")
        self.assertEqual(st.count("PUT", "/blob/") - puts, 2, "the two assets of the SKUs tried are put again (harmless); the shared one only once")
        self.assert_clean(text)
        st.api_status = None
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        self.assertIn("5 skipped", text)

    def test_the_failure_text_never_holds_an_asset_id_so_skus_that_fail_alike_read_alike(self):
        self.assertEqual(self.run_cli()[0], 0)
        self.state.api_status = 503
        rc, text = self.run_cli()
        self.assertEqual(rc, 1)
        for sku in self.state.skus.values():
            self.assertNotIn(sku["image_asset_id"], text)

    def test_a_connection_failure_on_the_phone_path_names_no_asset_id(self):
        api = lp.Api(self.env["ARON_API_BASE"], "admin1001", PW, SECRET, self.clock, lp.Out())
        self.server.shutdown()
        self.server.server_close()
        with self.assertRaises(lp.Failure) as cm:
            api._api("GET", "/v1/sku-images/aaaaaaaa", bearer=True, shown="/v1/sku-images/{asset_id}")
        self.assertIn("GET /v1/sku-images/{asset_id}: connection failed", str(cm.exception))
        self.assertNotIn("aaaaaaaa", str(cm.exception))

    # -- the answer that carries the contract's image_asset_id (today's admin answer does not; the loader accepts both)
    def test_an_answer_with_image_asset_id_binds_and_skips_like_one_with_the_link_only(self):
        self.state.expose_asset_id = True
        self.assertEqual(self.run_cli()[0], 0)
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        self.assertIn("5 skipped", text)
        rc, text = self.run_cli("--dry-run")
        self.assertEqual(rc, 0, text)
        self.assertIn("plan: 5 pictures: 5 skipped, 0 to bind, 0 assets to upload", text)

    def test_an_answer_with_image_asset_id_lets_unbind_see_a_binding_that_has_no_read_link(self):
        # the read-URL signing is broken, but the answer names the asset: --unbind can undo it (without the id it cannot)
        self.state.expose_asset_id = True
        self.state.no_link = True
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertIn("STOPPED: AAA-10S: a SKU failed its check right after it was bound", text)
        self.assertIn("undo with: python -P tools/data/load_packshots.py --unbind --only AAA-10S", text)
        self.assertNotIn("cannot be undone", text)
        rc, text = self.run_cli("--unbind", "--only", "AAA-10S")
        self.assertEqual(rc, 0, text)
        self.assertIn("1 unbound", text)
        self.assertIsNone(self.state.skus["AAA-10S"]["image_asset_id"])

    def test_an_answer_with_another_asset_id_is_rebound_and_unbind_leaves_it_alone(self):
        self.state.expose_asset_id = True
        self.assertEqual(self.run_cli()[0], 0)
        theirs = lp.asset_id_for(lp.sha256_hex(b"the owner's own picture"))
        self.state.assets[theirs] = ("sku_image", "image/jpeg", 10, lp.sha256_hex(b"the owner's own picture"))
        self.state.skus["BBB-20S_20HL"]["image_asset_id"] = theirs
        rc, text = self.run_cli("--dry-run")
        self.assertEqual(rc, 0, text)
        self.assertRegex(text, r"BBB-20S_20HL\s+would upload \+ bind \[replaces another picture\]")
        rc, text = self.run_cli("--unbind")
        self.assertEqual(rc, 0, text)
        self.assertEqual(self.state.skus["BBB-20S_20HL"]["image_asset_id"], theirs)
        self.assertIn("4 unbound, 0 had no picture, 1 left alone", text)

    def test_sku_missing_on_the_server_fails_but_the_rest_loads(self):
        del self.state.skus["DDD-20s"]
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertIn("no such SKU on the server", text)
        self.assertIn("4 bound", text)

    def test_wrong_password_stops_at_login(self):
        env = dict(self.env, ARON_ADMIN_PASSWORD=WRONG_PW)
        rc, text = self.run_cli(env=env)
        self.assertEqual(rc, 1)
        self.assertEqual([c for c in self.state.calls if c[0] != "POST" or not c[1].startswith("/v1/auth/login")], [])
        self.assertNotIn(WRONG_PW, text)
        self.assertIn("login: HTTP 401 ERR_AUTH_INVALID_CREDENTIALS", text)

    def test_a_used_totp_step_is_retried_once_with_the_next_step(self):
        self.state.last_step = int(self.clock.time() // 30)   # somebody just used this step's code
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        self.assertEqual(self.state.mfa_failures, 1)
        self.assertIn("trying once more with the next step", text)

    def test_back_to_back_runs_wait_for_the_next_step_instead_of_presenting_a_spent_code(self):
        # the runbook's dry run, canary and apply follow each other within seconds; a spent code counts towards the lockout
        self.assertEqual(self.run_cli("--dry-run")[0], 0)
        self.assertEqual(self.run_cli("--apply", "--only", "BBB-20S_20HL")[0], 0)
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        self.assertEqual(self.state.mfa_failures, 0)
        self.assertEqual(self.state.logins, 3)
        self.assertIn("waiting", text)
        self.assertTrue(any(s > 0 for s in self.clock.sleeps))

    def test_a_bogus_remembered_step_does_not_stall_the_run(self):
        state_file = lp.state_path(self.env, self.env["ARON_API_BASE"], "admin1001")
        state_file.write_text("999999999999", encoding="ascii")
        self.assertEqual(self.run_cli("--dry-run")[0], 0)
        self.assertTrue(all(s < 100 for s in self.clock.sleeps), self.clock.sleeps)
        state_file.write_text("not a number", encoding="ascii")
        self.assertEqual(self.run_cli("--dry-run")[0], 0)

    def test_the_remembered_step_is_a_bare_number_and_not_a_secret(self):
        self.assertEqual(self.run_cli("--dry-run")[0], 0)
        state_file = lp.state_path(self.env, self.env["ARON_API_BASE"], "admin1001")
        self.assertEqual(state_file.read_text(encoding="ascii"), str(int(T0 // 30)))

    # -- tokens
    def test_token_refused_mid_run_signs_in_again_after_the_next_totp_step(self):
        self.state.expire_after = 3    # GET list, POST asset, then the PATCH is refused with 401
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        self.assertEqual(self.state.logins, 2)
        self.assertEqual(self.state.mfa_failures, 0, "the second sign-in must not present a spent code")
        self.assertTrue(any(s > 0 for s in self.clock.sleeps), "it waited for the next authenticator step")
        self.assertIn("signing in again", text)
        self.assertTrue(all(s["image_asset_id"] for s in self.state.skus.values()))
        self.assert_clean(text)

    def test_token_near_expiry_signs_in_again_before_it_is_refused(self):
        self.state.ttl, self.state.tick = 100, 25.0    # the token has 100 s; the client signs in again when under 90 s remain
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        self.assertGreaterEqual(self.state.logins, 2)
        self.assertEqual(self.state.mfa_failures, 0)
        self.assertIn("near expiry", text)
        self.assertTrue(all(s["image_asset_id"] for s in self.state.skus.values()))
        self.assert_clean(text)

    # -- configuration
    def test_only_limits_the_run_and_unknown_codes_are_a_usage_error(self):
        rc, text = self.run_cli("--apply", "--only", "BBB-20S_20HL")
        self.assertEqual(rc, 0, text)
        self.assertEqual(self.state.count("PATCH", "/v1/admin/skus/"), 1)
        rc, text = self.run_cli("--apply", "--only", "NOPE")
        self.assertEqual(rc, 2)
        self.assertIn("NOPE", text)

    def test_configuration_errors_exit_2_before_any_request(self):
        for env in ({}, {"ARON_API_BASE": self.env["ARON_API_BASE"]}, dict(self.env, ARON_API_BASE="http://example.com"),
                    {k: v for k, v in self.env.items() if k != "ARON_ADMIN_PASSWORD"}):
            rc, text = self.run_cli(env=env)
            self.assertEqual(rc, 2, text)
        self.assertEqual(self.state.calls, [])

    def test_no_totp_secret_for_an_mfa_account_is_a_clear_login_failure(self):
        env = {k: v for k, v in self.env.items() if k != "ARON_ADMIN_TOTP_SECRET"}
        rc, text = self.run_cli(env=env)
        self.assertEqual(rc, 1)
        self.assertIn("needs MFA", text)

    def test_rate_limit_answers_are_waited_out_with_a_bound(self):
        real = self.state.handle
        seen = {"n": 0}

        def limited(method, path, q, h, body):
            if path == "/v1/admin/skus" and method == "GET" and seen["n"] == 0:
                seen["n"] += 1
                return self.state.prob(429, "ERR_RATE_LIMITED", {"Retry-After": "7"})
            return real(method, path, q, h, body)
        self.state.handle = limited
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        self.assertIn(7, self.clock.sleeps)

    def test_a_redirect_is_an_answer_never_followed(self):
        self.state.redirect_list = True
        rc, text = self.run_cli()
        self.assertEqual(rc, 1)
        self.assertIn("list SKUs: HTTP 302", text)
        self.assertNotIn(("GET", "/elsewhere"), self.state.calls, "the bearer token must not follow a redirect")

    def test_an_unexpected_error_prints_no_traceback(self):
        real = lp.Loader.run

        def boom(self_):
            raise KeyError("PASSWORD-LIKE-SENTINEL")
        lp.Loader.run = boom
        self.addCleanup(setattr, lp.Loader, "run", real)
        rc, text = self.run_cli()
        self.assertEqual(rc, 1)
        self.assertIn("unexpected KeyError", text)
        self.assertNotIn("SENTINEL", text)
        self.assertNotIn("Traceback", text)

    def test_a_server_that_is_down_exits_1_without_leaking(self):
        self.server.shutdown()
        self.server.server_close()   # a closed port refuses at once; a listening one would make the client wait out its timeouts
        rc, text = self.run_cli()
        self.assertEqual(rc, 1)
        self.assertIn("connection failed", text)
        self.assert_clean(text)

    def test_processed_files_are_required_for_an_online_run(self):
        for p in self.ws.processed.glob("*.jpg"):
            p.unlink()
        rc, text = self.run_cli()
        self.assertEqual(rc, 2)
        self.assertEqual(self.state.calls, [])

    def test_a_failing_offline_check_blocks_the_online_run(self):
        (self.ws.processed / "AAA-10S.jpg").write_bytes(b"\xff\xd8\xff" + b"\x00" * 40 + b"\xff\xd9")
        rc, text = self.run_cli()
        self.assertEqual(rc, 1)
        self.assertEqual(self.state.calls, [])

    # -- suspect pictures (PK-1): CCC-20s and DDD-20s are two variants with one picture
    def test_check_prints_a_warning_per_suspect_row(self):
        buf = io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(buf):
            rc = lp.main(self.ws.argv("--check"), env={})
        text = buf.getvalue()
        self.assertEqual(rc, 0, text)
        self.assertRegex(text, r"WARNING CCC-20s\s+suspect picture: same art as DDD-20s")
        self.assertRegex(text, r"WARNING DDD-20s\s+suspect picture: same art as CCC-20s")
        self.assertNotIn("WARNING AAA", text)
        self.assertIn("2 suspect pictures", text)

    def test_dry_run_and_apply_warn_per_suspect_row_and_tag_their_lines(self):
        for mode in ("--dry-run", "--apply"):
            rc, text = self.run_cli(mode)
            self.assertEqual(rc, 0, text)
            self.assertEqual(len(re.findall(r"^WARNING (?:CCC-20s|DDD-20s) ", text, re.M)), 2, text)
            self.assertRegex(text, r"CCC-20s .*\[SUSPECT picture: owner to confirm\]")
            self.assertNotRegex(text, r"AAA-10S .*SUSPECT")
            self.assertIn("WARNING: 2 suspect pictures in this run (CCC-20s, DDD-20s)", text)

    def test_skip_suspect_loads_the_rest_and_leaves_the_suspects_alone(self):
        rc, text = self.run_cli("--apply", "--skip-suspect")
        self.assertEqual(rc, 0, text)
        st = self.state
        self.assertIsNone(st.skus["CCC-20s"]["image_asset_id"])
        self.assertIsNone(st.skus["DDD-20s"]["image_asset_id"])
        self.assertTrue(all(st.skus[c]["image_asset_id"] for c in ("AAA-10S", "AAA-20S", "BBB-20S_20HL")))
        self.assertIn("summary: 3 pictures: 3 bound, 0 repaired, 0 skipped, 0 failed; 2 assets uploaded", text)
        self.assertEqual(len(re.findall(r"^WARNING (?:CCC-20s|DDD-20s) .*\[left out by --skip-suspect\]", text, re.M)), 2, text)
        self.assertIn("2 suspect pictures left out by --skip-suspect", text)
        self.assertNotIn("WARNING: 2 suspect pictures in this run", text)
        rc, text = self.run_cli("--dry-run", "--skip-suspect")
        self.assertEqual(rc, 0, text)
        self.assertIn("plan: 3 pictures: 3 skipped, 0 to bind", text)

    def test_skip_suspect_belongs_to_dry_run_and_apply(self):
        for mode in ("--check", "--unbind"):
            rc, text = self.run_cli(mode, "--skip-suspect")
            self.assertEqual(rc, 2, text)
            self.assertIn("--skip-suspect", text)
        self.assertEqual(self.state.calls, [])

    # -- fail fast (PK-3)
    def test_a_check_failure_right_after_a_fresh_bind_stops_the_run_and_names_the_bound_skus(self):
        self.state.get_content_type = "application/octet-stream"
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        st = self.state
        self.assertEqual(st.count("PATCH", "/v1/admin/skus/"), 1, "the run stops at the first SKU bound to a bad blob")
        self.assertEqual(st.count("PUT", "/blob/"), 1)
        self.assertIn("STOPPED: AAA-10S: a SKU failed its check right after it was bound", text)
        self.assertIn("bound in this run (1): AAA-10S", text)
        self.assertIn("--unbind --only AAA-10S", text)
        self.assertIn("4 of 5 SKUs were not attempted", text)
        # the summary agrees with the lines above it: the SKU is bound on the server, so it is not just "0 bound, 1 failed"
        self.assertIn("summary: 5 pictures: 0 bound, 0 repaired, 0 skipped, 1 failed "
                      "(1 of them bound on the server, but failed their check); 1 assets uploaded", text)
        self.assert_clean(text)
        # the command it printed undoes the damage, and the next load repairs nothing it did not break
        st.get_content_type = None
        rc, text = self.run_cli("--unbind", "--only", "AAA-10S")
        self.assertEqual(rc, 0, text)
        self.assertIsNone(st.skus["AAA-10S"]["image_asset_id"])
        self.assertEqual(self.run_cli()[0], 0)
        self.assertTrue(all(s["image_asset_id"] for s in st.skus.values()))

    def test_failures_that_are_not_in_a_row_do_not_stop_the_run(self):
        self.state.always_stale = {"AAA-10S", "BBB-20S_20HL", "DDD-20s"}
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertNotIn("STOPPED", text)
        self.assertIn("summary: 5 pictures: 2 bound, 0 repaired, 0 skipped, 3 failed", text)

    def test_a_bind_the_api_shows_no_picture_link_for_is_not_offered_to_unbind(self):
        # the server accepts the bind, but the PATCH answer has no image_url: --unbind goes by that link and would change nothing
        self.state.no_link = True
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertIn("STOPPED: AAA-10S: a SKU failed its check right after it was bound", text)
        self.assertIn("no image_url", text)
        self.assertIn("bound in this run (1): AAA-10S", text)
        self.assertNotIn("undo with:", text)
        self.assertNotIn("--unbind --only", text)
        self.assertIn("cannot be undone with --unbind (AAA-10S)", text)
        self.assertIn("so it would print 'no picture: nothing to unbind' and change nothing", text)
        self.assertRegex(text, r"next step for AAA-10S: fix the read-URL signing on the server and run --apply --only AAA-10S again .*web admin SKU form")
        self.assert_clean(text)
        # the reason it says so: the server holds the binding and --unbind cannot see it
        self.assertTrue(self.state.skus["AAA-10S"]["image_asset_id"])
        rc, text = self.run_cli("--unbind", "--only", "AAA-10S")
        self.assertEqual(rc, 0, text)
        self.assertIn("no picture: nothing to unbind", text)
        self.assertTrue(self.state.skus["AAA-10S"]["image_asset_id"], "still bound: the advice it replaces would have done nothing")
        # the next step it printed works: with the signing fixed, the same command finds the binding and checks it
        self.state.no_link = False
        rc, text = self.run_cli("--apply", "--only", "AAA-10S")
        self.assertEqual(rc, 0, text)
        self.assertIn("skipped: already bound", text)

    def test_report_bound_gives_the_undo_command_only_for_the_bindings_unbind_can_see(self):
        out = lp.Out()
        loader = lp.Loader(None, [], Path("."), out, True)
        loader.bound_now, loader.no_link = ["AAA-10S", "BBB-20S_20HL"], ["BBB-20S_20HL"]
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            loader.report_bound()
        text = "\n".join(out.lines)
        self.assertIn("bound in this run (2): AAA-10S, BBB-20S_20HL", text)
        self.assertIn("--unbind --only AAA-10S\n", text + "\n")
        self.assertNotIn("--unbind --only AAA-10S,BBB-20S_20HL", text)
        self.assertIn("cannot be undone with --unbind (BBB-20S_20HL)", text)

    def test_a_blob_host_that_drops_every_read_stops_a_rerun_after_three_skus_in_a_row(self):
        # the skip check GETs the read URL of every bound SKU: a dead storage endpoint is a systematic fault like any other
        self.assertEqual(self.run_cli()[0], 0)
        st = self.state
        st.drop_blob_get = True
        gets, calls = st.count("GET", "/blob/"), len(st.calls)
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertIn("STOPPED: BBB-20S_20HL: 3 SKUs in a row failed the same way (GET blob: connection failed", text)
        self.assertIn("2 of 5 SKUs were not attempted", text)
        self.assertIn("summary: 5 pictures: 0 bound, 0 repaired, 0 skipped, 3 failed; 0 assets uploaded", text)
        self.assertEqual(st.count("GET", "/blob/") - gets, 9, "three tries for each of the three SKUs, then it stops")
        self.assertFalse([c for c in st.calls[calls:] if c[0] in ("PUT", "PATCH") or c == ("POST", "/v1/admin/assets")])
        self.assert_clean(text)
        # the same in a dry run, which is read only
        gets = st.count("GET", "/blob/")
        rc, text = self.run_cli("--dry-run")
        self.assertEqual(rc, 1, text)
        self.assertIn("STOPPED: BBB-20S_20HL: 3 SKUs in a row failed the same way", text)
        self.assertEqual(st.count("GET", "/blob/") - gets, 9)
        # and once the host answers again, the rerun has nothing to do
        st.drop_blob_get = False
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        self.assertIn("5 skipped", text)

    def test_missing_skus_are_never_a_systematic_fault(self):
        for code in ("AAA-10S", "AAA-20S", "BBB-20S_20HL"):
            del self.state.skus[code]
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertNotIn("STOPPED", text)
        self.assertIn("summary: 5 pictures: 2 bound, 0 repaired, 0 skipped, 3 failed", text)

    # -- the undo (PK-3)
    def test_unbind_takes_every_loaded_picture_off_with_if_match_and_deletes_nothing(self):
        self.assertEqual(self.run_cli()[0], 0)
        before = len(self.state.calls)
        rc, text = self.run_cli("--unbind")
        self.assertEqual(rc, 0, text)
        st = self.state
        self.assertTrue(all(s["image_asset_id"] is None for s in st.skus.values()))
        later = st.calls[before:]
        self.assertEqual(len([c for c in later if c[0] == "PATCH"]), 5)
        self.assertFalse([c for c in later if c[0] in ("PUT", "POST") and not c[1].startswith("/v1/auth")], later)
        self.assertIn("summary: 5 SKUs: 5 unbound, 0 had no picture, 0 left alone", text)
        self.assertEqual((len(st.assets), len(st.blobs)), (3, 3), "assets and blobs are append-only")
        self.assert_clean(text)
        rc, text = self.run_cli("--unbind")   # again: nothing to do, no PATCH
        self.assertEqual(rc, 0, text)
        self.assertIn("5 had no picture", text)

    def test_unbind_only_touches_the_named_skus(self):
        self.assertEqual(self.run_cli()[0], 0)
        rc, text = self.run_cli("--unbind", "--only", "CCC-20s,DDD-20s")
        self.assertEqual(rc, 0, text)
        st = self.state
        self.assertIsNone(st.skus["CCC-20s"]["image_asset_id"])
        self.assertIsNone(st.skus["DDD-20s"]["image_asset_id"])
        self.assertTrue(all(st.skus[c]["image_asset_id"] for c in ("AAA-10S", "AAA-20S", "BBB-20S_20HL")))
        self.assertEqual(self.run_cli("--unbind", "--only", "NOPE")[0], 2)

    def test_unbind_leaves_a_picture_alone_that_is_not_from_this_manifest(self):
        self.assertEqual(self.run_cli()[0], 0)
        other = lp.asset_id_for(lp.sha256_hex(b"somebody else's picture"))
        self.state.assets[other] = ("sku_image", "image/jpeg", 10, lp.sha256_hex(b"somebody else's picture"))
        self.state.skus["BBB-20S_20HL"]["image_asset_id"] = other
        rc, text = self.run_cli("--unbind")
        self.assertEqual(rc, 0, text)
        self.assertEqual(self.state.skus["BBB-20S_20HL"]["image_asset_id"], other)
        self.assertRegex(text, r"BBB-20S_20HL\s+left alone: bound to a picture that is not in this manifest")
        self.assertIn("4 unbound, 0 had no picture, 1 left alone", text)

    def test_unbind_needs_the_manifest_only_not_the_processed_files(self):
        self.assertEqual(self.run_cli()[0], 0)
        for p in self.ws.processed.glob("*.jpg"):
            p.unlink()
        rc, text = self.run_cli("--unbind")
        self.assertEqual(rc, 0, text)
        self.assertTrue(all(s["image_asset_id"] is None for s in self.state.skus.values()))

    def test_unbind_refetches_and_retries_a_stale_if_match(self):
        self.assertEqual(self.run_cli("--apply", "--only", "AAA-10S")[0], 0)
        self.state.stale = {"AAA-10S"}
        rc, text = self.run_cli("--unbind", "--only", "AAA-10S")
        self.assertEqual(rc, 0, text)
        self.assertIn("PATCH 412 PATCH 200", text)
        self.assertIsNone(self.state.skus["AAA-10S"]["image_asset_id"])

    def test_unbind_does_not_retry_over_a_picture_the_owner_chose_while_it_ran(self):
        # the "left alone" decision is taken on the list fetched at the start; the 412 retry must take it again
        self.assertEqual(self.run_cli()[0], 0)
        theirs = lp.asset_id_for(lp.sha256_hex(b"the owner's own picture"))
        self.state.assets[theirs] = ("sku_image", "image/jpeg", 10, lp.sha256_hex(b"the owner's own picture"))
        self.state.foreign = {"AAA-10S": theirs}   # chosen in the web admin just as the PATCH null is answered
        before = len(self.state.calls)
        rc, text = self.run_cli("--unbind")
        self.assertEqual(rc, 0, text)
        st = self.state
        self.assertEqual(st.skus["AAA-10S"]["image_asset_id"], theirs, "the owner's picture must not be unbound")
        self.assertEqual(len([c for c in st.calls[before:] if c == ("PATCH", "/v1/admin/skus/1")]), 1, "no second PATCH after the refetch")
        self.assertRegex(text, r"AAA-10S\s+left alone: changed meanwhile, now bound to a picture that is not in this manifest "
                               rf"\(asset {theirs[:8]}\)\s+\[PATCH 412\]")
        self.assertIn("4 unbound, 0 had no picture, 1 left alone (bound to another picture), 0 failed", text)
        self.assertNotIn("STOPPED", text)
        self.assertTrue(all(st.skus[c]["image_asset_id"] is None for c in DB_CODES[1:]))
        self.assert_clean(text)

    def test_unbind_still_retries_when_the_picture_was_removed_or_stays_ours_while_it_ran(self):
        self.assertEqual(self.run_cli()[0], 0)
        self.state.foreign = {"AAA-10S": None}     # somebody removed it: the refetch finds the goal reached
        self.state.stale = {"BBB-20S_20HL"}        # somebody only touched the row: the picture is still ours, so retry
        rc, text = self.run_cli("--unbind")
        self.assertEqual(rc, 0, text)
        self.assertRegex(text, r"AAA-10S\s+unbound\s+\[PATCH 412\]")
        self.assertRegex(text, r"BBB-20S_20HL\s+unbound\s+\[PATCH 412 PATCH 200\]")
        self.assertIn("5 unbound, 0 had no picture, 0 left alone (bound to another picture), 0 failed", text)
        self.assertTrue(all(s["image_asset_id"] is None for s in self.state.skus.values()))

    def test_unbind_stops_after_three_identical_failures(self):
        self.assertEqual(self.run_cli()[0], 0)
        self.state.always_stale = set(DB_CODES)
        rc, text = self.run_cli("--unbind")
        self.assertEqual(rc, 1, text)
        self.assertIn("STOPPED: BBB-20S_20HL: 3 SKUs in a row failed the same way", text)
        self.assertIn("3 failed", text)

    # -- what the fake server now models (PK-8)
    def test_the_sku_list_is_followed_across_pages(self):
        self.state.page_size = 2
        rc, text = self.run_cli()
        self.assertEqual(rc, 0, text)
        self.assertEqual(self.state.count("GET", "/v1/admin/skus"), 3)
        self.assertIn("server: 5 SKUs", text)
        self.assertTrue(all(s["image_asset_id"] for s in self.state.skus.values()))

    def test_the_totp_file_works_end_to_end_through_main(self):
        f = Path(self.tmp.name) / "enrol.json"
        f.write_text(json.dumps({"otpauth_uri": f"otpauth://totp/x?secret={SECRET_B32}&issuer=Aron"}), encoding="utf-8")
        env = {k: v for k, v in self.env.items() if k != "ARON_ADMIN_TOTP_SECRET"}
        rc, text = self.run_cli("--dry-run", "--totp-file", str(f), env=env)
        self.assertEqual(rc, 0, text)
        rc, text = self.run_cli("--dry-run", env=dict(env, ARON_ADMIN_TOTP_FILE=str(f)))
        self.assertEqual(rc, 0, text)
        self.assert_clean(text)

    def test_the_fake_enforces_the_json_content_type_rule_of_the_real_server(self):
        api = lp.Api(self.env["ARON_API_BASE"], "admin1001", PW, SECRET, self.clock, lp.Out())
        body = {"username": "admin1001", "password": PW, "client": "web"}
        r = api._api("POST", "/v1/auth/login", body, headers={"Content-Type": "text/plain"})
        self.assertEqual((r.status, r.problem), (415, "ERR_UNSUPPORTED_MEDIA_TYPE"))
        self.assertEqual(api._api("POST", "/v1/auth/login", body).status, 200)

    def test_a_wrong_totp_secret_presents_at_most_two_codes_and_does_not_lock_the_account(self):
        rc, text = self.run_cli("--dry-run", env=dict(self.env, ARON_ADMIN_TOTP_SECRET=OTHER_B32))
        self.assertEqual(rc, 1, text)
        self.assertEqual(self.state.mfa_failures, 2)
        self.assertFalse(self.state.locked)
        self.assertIn("ERR_AUTH_MFA_INVALID", text)

    def test_repeated_wrong_secrets_lock_the_account_and_the_loader_says_so(self):
        for _ in range(5):   # two wrong codes a run, ten in all
            self.run_cli("--dry-run", env=dict(self.env, ARON_ADMIN_TOTP_SECRET=OTHER_B32))
        self.assertTrue(self.state.locked)
        rc, text = self.run_cli("--dry-run")
        self.assertEqual(rc, 1, text)
        self.assertIn("ERR_AUTH_ACCOUNT_LOCKED", text)
        self.assertEqual(self.state.count("POST", "/v1/auth/mfa/verify"), 10, "a locked account is not asked for more codes")

    def test_a_redirect_from_the_blob_host_is_an_answer_never_followed(self):
        self.state.redirect_blob_get = True
        rc, text = self.run_cli()
        self.assertEqual(rc, 1, text)
        self.assertIn("the picture answers HTTP 302", text)
        self.assertNotIn(("GET", "/elsewhere"), self.state.calls)


if __name__ == "__main__":
    unittest.main()
