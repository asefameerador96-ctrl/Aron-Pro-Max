#!/usr/bin/env python3
"""Pack pictures (D-PACK-03): offline check, and the repeatable upload through the audited admin API (stdlib only).

Two jobs: an offline check of the manifest and the processed files, and the upload of those files to an environment.

    python -P tools/data/load_packshots.py --check                      # offline: manifest, catalogue, files, hashes
    python -P tools/data/load_packshots.py --dry-run                    # online, READ ONLY: the plan per SKU
    python -P tools/data/load_packshots.py --apply [--only CODE,CODE]   # online: upload, bind, verify (owner go-ahead)
    python -P tools/data/load_packshots.py --unbind [--only CODE,CODE]  # online: take the loaded pictures off again

`--skip-suspect` (with --dry-run or --apply) leaves out the SKUs whose picture the manifest marks `suspect-*` (one picture
for different variants, BDA/BDDF), so the owner can load the other 38 first. Every run prints a WARNING line per suspect SKU.

The pictures themselves are never in git (the repository is public until the product is complete, CLAUDE.md rule 13):
`tools/data/prepare_packshots.py` makes the processed copies under assets/packshots/processed/ (git-ignored) and the
hash manifest assets/packshots/manifest.csv (committed). This script is the BULK way they reach an environment (the web
admin's SKU form does one SKU at a time through the same API: docs/runbooks/sku-pack-pictures.md); the phones then take them
from the bundle's image_asset_id (docs/runbooks/load-packshots.md, "When the phones show it"). What the script does:

  1. admin login with MFA (POST /v1/auth/login, client "web", then POST /v1/auth/mfa/verify; TOTP is RFC 6238 SHA-1,
     6 digits, 30 s, and a code works once, so a second login waits for the next step),
  2. GET /v1/admin/skus (all SKUs of the environment),
  3. per processed file: skip when the SKU is already bound to this very picture and both reads of it answer 200
     image/jpeg with the same sha256; otherwise POST /v1/admin/assets (purpose sku_image, an asset_id derived from the
     file's sha256, so a rerun and two SKUs with the same art share ONE asset), PUT the bytes to the returned write URL
     (x-ms-blob-type BlockBlob, Content-Type image/jpeg), PATCH /v1/admin/skus/{id} with image_asset_id, If-Match
     and a change_reason, then read the picture back twice: the admin's image_url (a read link to the stored blob) and
     GET /v1/sku-images/{asset_id} with the admin token (the path the phones take: the API streams the bytes and
     answers 404 unless a SKU binds the asset), each checked for 200, image/jpeg and the sha256.
  What a SKU is bound to is read from its answer: `image_asset_id` when the answer carries it (the contract's Sku does;
  the admin list of today answers `image_url` only), else the asset id inside `image_url`.
  The server never checks that the PUT happened, so a failed PUT is never followed by a PATCH, and every binding is
  proven by the two reads. Assets are append-only: a changed picture is a new asset, an undone binding is
  PATCH image_asset_id null (--unbind does it, with If-Match, for the SKUs bound to a picture of this manifest; a SKU
  whose picture changed during the run, or that holds a picture of another origin, is left alone). --unbind only removes:
  it never restores the picture a SKU showed before --apply rebound it.
  A SKU that fails is reported and the run goes on, EXCEPT that the run stops (exit 1, with the list of SKUs it bound
  and the --unbind command) at the first SKU whose check fails right after a fresh bind (a wrong stored content type, an
  unreadable read link or a phone path that answers an error would repeat for every SKU, and the server accepts a bind
  without looking at the blob) and after 3 SKUs in a row that fail the same way. --unbind goes by what the SKU's answer
  shows (image_asset_id or image_url), so a binding the API answered with neither cannot be undone by it: the stop then
  says so and names another next step. Exit code: 0 all good, 1 any failure, 2 usage or configuration.

Environment (nothing secret is accepted on the command line, nothing secret is printed):
  ARON_API_BASE              https://HOST (no /v1); or --api-base. http is accepted for a loopback host only.
  ARON_ADMIN_USER            admin login, default admin1001 (or --username)
  ARON_ADMIN_PASSWORD        the password
  ARON_ADMIN_TOTP_SECRET     base32 TOTP secret, or
  ARON_ADMIN_TOTP_FILE       a JSON file (or --totp-file) holding the answer of POST /v1/auth/mfa/enrol, whose
                             `otpauth_uri` carries the secret (docs/status/laptop.md 2026-10-15, step 4c)
  ARON_PACKSHOT_STATE_DIR    optional; where the last used TOTP step number is kept (default: the temp folder)
Output is counts, SKU codes and HTTP statuses only; every URL query (a SAS signature) is masked.
"""
from __future__ import annotations

import argparse
import base64
import binascii
import csv
import datetime as dt
import hashlib
import hmac
import http.client
import io
import json
import math
import os
import re
import struct
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
DEFAULT_MANIFEST = REPO / "assets" / "packshots" / "manifest.csv"
DEFAULT_PROCESSED = REPO / "assets" / "packshots" / "processed"
DEFAULT_CATALOG = REPO / "db" / "seed" / "sku_catalog.csv"

PURPOSE = "sku_image"
MIME = "image/jpeg"
ASSET_CAP_BYTES = 300 * 1024      # IMAGE_CAP_BYTES of AdminContentApi.kt: 307,200 passes, 307,201 is a 413
MAX_EDGE_PX = 320                 # D-PACK-03
MANIFEST_COLUMNS = ["sku_code", "source_file", "source_bytes", "source_sha256", "width", "height", "processed_file",
                    "processed_bytes", "processed_sha256", "duplicate_group", "note"]
DUP_KINDS = ("same-art-pack-sizes", "suspect-different-variants", "suspect-same-pack")
UUID_V4 = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
SHA256_HEX = re.compile(r"^[0-9a-f]{64}$")
IMAGE_PATH = re.compile(r"/assets/sku_image/([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})\.(?:jpg|png)$")
UUID_ANY = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
SKU_IMAGE_PATH = "/v1/sku-images/"   # getSkuImage: the picture bytes by asset id, streamed by the API (what the phones fetch)
LOOPBACK = {"127.0.0.1", "::1", "localhost"}
USER_AGENT = "aron-load-packshots/1"
REFRESH_MARGIN_S = 90             # web access tokens live 15 min; sign in again when less than this is left
TOTP_STEP_S = 30
STREAK_LIMIT = 3                  # SKUs in a row that fail the same way before the run stops
SUSPECT_PREFIX = "suspect-"       # duplicate_group kinds that need the owner's eye
SUSPECT_TEXT = {
    "suspect-different-variants": "same art as {others}, a different catalogue variant (owner to confirm the right pack)",
    "suspect-same-pack": "the same picture as {others}, same variant and pack size (owner to confirm)",
}


class ConfigError(Exception):
    """Wrong or missing configuration: exit 2, before any request."""


class Failure(Exception):
    """One SKU (or the whole run) failed; the message holds a step and a status, never a URL or a secret."""


class LoginFailure(Failure):
    """The sign-in itself failed: stop at once, every further SKU would only repeat the failed attempt."""


class SkuMissing(Failure):
    """The environment has no SKU with this code: a per-SKU fact, never a sign of a systematic fault."""


class Abort(Failure):
    """A fault that every further SKU would repeat: the run stops here and reports which SKUs are bound."""


class ChangedMeanwhile(Failure):
    """--unbind only: after a stale If-Match the refetched SKU no longer holds the picture the run decided to take off (the
    owner chose another one in the web admin meanwhile). Not a fault: the SKU is left alone, `bound` is what it holds now."""

    def __init__(self, bound: str | None):
        super().__init__("the SKU's picture changed while the run was going")
        self.bound = bound


# ---------------------------------------------------------------------------------------------------- catalogue
def db_code(catalogue_code: str) -> str:
    """The DB sku code: the catalogue code with a space replaced by '_' (db/seed/gen_products.py, docs/requests/db-sku-code-spaces.md)."""
    return catalogue_code.strip().replace(" ", "_")


def read_catalog(path: Path = DEFAULT_CATALOG) -> dict[str, dict]:
    """db code -> catalogue row (catalogue_code, variant, pack_size, brand). Two rows with one db code are an error."""
    out: dict[str, dict] = {}
    with open(path, encoding="utf-8", newline="") as f:
        for r in csv.DictReader(f):
            code = db_code(r["sku_code"])
            if code in out:
                raise ValueError(f"catalogue lists the db code {code} twice")
            out[code] = {"catalogue_code": r["sku_code"].strip(), "variant": r["variant"], "pack_size": r["pack_size"],
                         "brand": r["brand"]}
    return out


def classify(codes: list[str], catalog: dict[str, dict]) -> str:
    """Why do these SKUs share one picture? One variant in several pack sizes is the same art on purpose; different
    variants are most likely a wrong file (BDA-20s and BDDF-20s); one variant and one pack size twice is a mistake too."""
    variants = {catalog[c]["variant"] for c in codes}
    sizes = {catalog[c]["pack_size"] for c in codes}
    if len(variants) > 1:
        return "suspect-different-variants"
    return "same-art-pack-sizes" if len(sizes) == len(codes) else "suspect-same-pack"


def suspect_rows(rows: list[dict]) -> dict[str, str]:
    """sku_code -> why, for every manifest row whose duplicate_group is a suspect kind (SUSPECT_PREFIX). The partners are
    the other members of the same group, so the text names what the picture is shared with."""
    groups: dict[str, list[str]] = {}
    for r in rows:
        g = r.get("duplicate_group", "")
        if g.startswith(SUSPECT_PREFIX):
            groups.setdefault(g, []).append(r["sku_code"])
    out: dict[str, str] = {}
    for g, codes in groups.items():
        text = SUSPECT_TEXT.get(g.split(":")[0], "{others} shares this picture")
        for c in codes:
            out[c] = text.format(others=", ".join(sorted(x for x in codes if x != c)) or "another SKU")
    return out


# ---------------------------------------------------------------------------------------------------- hashing, ids
def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def asset_id_for(sha256: str) -> str:
    """A UUID-v4-shaped asset id derived from the file's sha256: the first 128 bits with the version nibble 4 and the
    variant bits 10, so the server's UUID_V4 pattern accepts it. Same bytes, same id: a rerun and two SKUs with the same
    art share one asset (the server answers a repeat POST with the same blob path and a fresh write URL)."""
    if not SHA256_HEX.match(sha256):
        raise ValueError("sha256 must be 64 lowercase hex digits")
    b = bytearray(bytes.fromhex(sha256)[:16])
    b[6] = (b[6] & 0x0F) | 0x40
    b[8] = (b[8] & 0x3F) | 0x80
    h = bytes(b).hex()
    return f"{h[:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:]}"


def asset_in_url(url: str | None) -> str | None:
    """The asset id inside a blob read URL (`.../assets/sku_image/<id>.jpg?sas`), or None. Azure's SDK writes the blob name as one
    path segment, so the live URL reads `.../media/assets%2Fsku_image%2F<id>.jpg`: the path is decoded before it is matched."""
    if not url:
        return None
    m = IMAGE_PATH.search(urllib.parse.unquote(urllib.parse.urlsplit(url).path))
    return m.group(1) if m else None


def picture_of(sku: dict | None) -> tuple[bool, str | None]:
    """(has a picture, the asset id it is bound to or None when the answer does not say). A SKU answer shows the binding
    in one of two ways: `image_asset_id` (the contract's Sku carries it; today's admin answer, SkuOut in AdminProducts.kt,
    does not) or `image_url`, a read link of the asset's blob whose path ends in the asset id. A string in
    `image_asset_id` that is not a UUID is a picture of an unknown origin (it is replaced, never trusted)."""
    if not isinstance(sku, dict):
        return False, None
    a = sku.get("image_asset_id")
    if isinstance(a, str) and a:
        return True, (a.lower() if UUID_ANY.match(a.lower()) else None)
    url = sku.get("image_url")
    return bool(url), asset_in_url(url if isinstance(url, str) else None)


# ---------------------------------------------------------------------------------------------------- JPEG inspection
_SOF = {0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF}


def parse_jpeg(data: bytes) -> dict:
    """Walks the marker segments (no pixel decoding): {width, height, segments, complete}. `segments` names the
    non-image segments in order: jfif (the plain 16-byte header), jfif-ext (a JFIF header with a thumbnail or other extra
    bytes), exif, xmp, icc, iptc, adobe, comment, appN. A processed picture must hold `jfif` at most. Raises ValueError
    when the bytes are not a JPEG or a segment is cut off."""
    n = len(data)
    if n < 4 or data[:3] != b"\xff\xd8\xff":
        raise ValueError("no JPEG signature")
    i, width, height, segs, eoi = 2, None, None, [], False
    while i < n:
        if data[i] != 0xFF:
            raise ValueError("bad marker")
        while i < n and data[i] == 0xFF:
            i += 1
        if i >= n:
            break
        m = data[i]
        i += 1
        if m == 0xD9:
            eoi = True
            break
        if m in (0x00, 0x01, 0xD8) or 0xD0 <= m <= 0xD7:
            continue
        if i + 2 > n:
            raise ValueError("truncated segment")
        length = (data[i] << 8) | data[i + 1]
        if length < 2 or i + length > n:
            raise ValueError("truncated segment")
        payload = data[i + 2:i + length]
        if m in _SOF and len(payload) >= 5:
            height, width = (payload[1] << 8) | payload[2], (payload[3] << 8) | payload[4]
        elif m == 0xE0:
            segs.append(("jfif" if length == 16 else "jfif-ext") if payload.startswith(b"JFIF\x00") else "app0")
        elif m == 0xE1:
            segs.append("exif" if payload.startswith(b"Exif\x00\x00") else "xmp" if payload.startswith(b"http://ns.adobe.com/xap/") else "app1")
        elif m == 0xE2:
            segs.append("icc" if payload.startswith(b"ICC_PROFILE\x00") else "app2")
        elif m == 0xED:
            segs.append("iptc")
        elif m == 0xEE:
            segs.append("adobe" if payload.startswith(b"Adobe") else "app14")
        elif 0xE0 <= m <= 0xEF:
            segs.append(f"app{m - 0xE0}")
        elif m == 0xFE:
            segs.append("comment")
        i += length
        if m == 0xDA:   # entropy-coded data runs to the next marker that is neither a stuffed 0xFF00 nor a restart
            while True:
                j = data.find(b"\xff", i)
                if j < 0 or j + 1 >= n:
                    i = n
                    break
                nxt = data[j + 1]
                if nxt == 0 or 0xD0 <= nxt <= 0xD7:
                    i = j + 2
                    continue
                i = j
                break
    return {"width": width, "height": height, "segments": segs, "complete": eoi}


def metadata_segments(info: dict) -> list[str]:
    """Everything in a parsed JPEG that is metadata rather than picture (the JFIF header is structure, not metadata)."""
    return [s for s in info["segments"] if s != "jfif"]


def asset_rule_problems(data: bytes) -> list[str]:
    """The upload rules of the asset store for purpose sku_image (AdminContentApi.kt requestAsset): a JPEG of 1 byte to
    300 KB. 307,200 bytes passes, 307,201 does not (the server answers 413 ERR_PAYLOAD_TOO_LARGE)."""
    out = []
    if not data:
        out.append("empty file (the asset store needs at least 1 byte)")
    elif len(data) > ASSET_CAP_BYTES:
        out.append(f"{len(data)} bytes is above the {ASSET_CAP_BYTES}-byte image cap")
    if data[:3] != b"\xff\xd8\xff":
        out.append("not a JPEG (no FFD8FF signature)")
    elif data[-2:] != b"\xff\xd9":
        out.append("JPEG does not end with the EOI marker")
    return out


# ---------------------------------------------------------------------------------------------------- manifest
class ManifestError(Exception):
    pass


def read_manifest(path: Path = DEFAULT_MANIFEST) -> list[dict]:
    try:
        f = open(path, encoding="utf-8", newline="")
    except OSError:
        raise ManifestError(f"cannot read the manifest {path.name}") from None
    with f:
        rd = csv.DictReader(f)
        if rd.fieldnames != MANIFEST_COLUMNS:
            raise ManifestError("manifest header is not: " + ",".join(MANIFEST_COLUMNS))
        return list(rd)


def write_manifest(path: Path, rows: list[dict]) -> bool:
    """Writes the manifest (UTF-8, LF, no BOM, rows by sku_code) when it differs; returns whether it was written."""
    text_rows = sorted(rows, key=lambda r: r["sku_code"])
    buf = io.StringIO(newline="")
    w = csv.DictWriter(buf, fieldnames=MANIFEST_COLUMNS, lineterminator="\n")
    w.writeheader()
    for r in text_rows:
        w.writerow({c: r.get(c, "") for c in MANIFEST_COLUMNS})
    data = buf.getvalue().encode("utf-8")
    if path.exists() and path.read_bytes() == data:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return True


def _int(s: str) -> int | None:
    return int(s) if isinstance(s, str) and s.isascii() and s.isdigit() else None


class Report:
    def __init__(self):
        self.errors: list[str] = []
        self.notes: list[str] = []
        self.rows: list[dict] = []
        self.files_checked = 0
        self.processed_total = 0


def check_offline(manifest: Path = DEFAULT_MANIFEST, processed: Path = DEFAULT_PROCESSED, catalog: Path = DEFAULT_CATALOG,
                  check_files: bool = True) -> Report:
    """Manifest against the catalogue (1:1), every field's format, the duplicate groups (kind against the catalogue), and,
    when processed files exist, the files themselves (presence, size, sha256, JPEG signature, dimensions, no metadata, at
    most 300 KB). Without any processed file the manifest is validated alone and the report says so; check_files=False
    validates the manifest alone on purpose (--unbind needs only the hashes)."""
    rep = Report()
    try:
        cat = read_catalog(catalog)
    except (OSError, ValueError, KeyError) as e:
        rep.errors.append(f"catalogue {catalog.name}: {type(e).__name__}")
        return rep
    try:
        rows = read_manifest(manifest)
    except ManifestError as e:
        rep.errors.append(str(e))
        return rep
    rep.rows = rows
    by_code: dict[str, dict] = {}
    for r in rows:
        code = r["sku_code"]
        if code in by_code:
            rep.errors.append(f"{code}: listed twice in the manifest")
        by_code[code] = r
    for code in sorted(by_code):
        if code not in cat:
            rep.errors.append(f"{code}: not a SKU of {catalog.name} (unmapped manifest row)")
    for code in sorted(cat):
        if code not in by_code:
            rep.errors.append(f"{code}: SKU has no manifest row (no picture)")
    src_groups: dict[str, list[str]] = {}
    dup_groups: dict[str, list[str]] = {}
    for code in sorted(by_code):
        r = by_code[code]
        bad = rep.errors.append
        if r["processed_file"] != f"{code}.jpg":
            bad(f"{code}: processed_file must be {code}.jpg")
        stem = re.sub(r"\.jpe?g$", "", r["source_file"], flags=re.I)
        if stem.replace(" ", "_") != code:
            bad(f"{code}: source_file {r['source_file']!r} does not map to this code (a space becomes '_')")
        for col in ("source_sha256", "processed_sha256"):
            if not SHA256_HEX.match(r[col]):
                bad(f"{code}: {col} is not 64 lowercase hex digits")
        sb, pb, w, h = _int(r["source_bytes"]), _int(r["processed_bytes"]), _int(r["width"]), _int(r["height"])
        if not sb:
            bad(f"{code}: source_bytes is not a positive integer")
        if not pb or pb > ASSET_CAP_BYTES:
            bad(f"{code}: processed_bytes {r['processed_bytes']!r} is outside 1..{ASSET_CAP_BYTES}")
        if not w or not h:
            bad(f"{code}: width and height must be positive integers")
        elif max(w, h) > MAX_EDGE_PX:
            bad(f"{code}: {w}x{h} is above the {MAX_EDGE_PX} px long edge")
        if r["duplicate_group"] and r["duplicate_group"].split(":")[0] not in DUP_KINDS:
            bad(f"{code}: duplicate_group {r['duplicate_group']!r} has an unknown kind")
        elif r["duplicate_group"]:
            dup_groups.setdefault(r["duplicate_group"], []).append(code)
        if SHA256_HEX.match(r["source_sha256"]):
            src_groups.setdefault(r["source_sha256"], []).append(code)
    for sha, codes in src_groups.items():
        groups = {by_code[c]["duplicate_group"] for c in codes}
        if len(codes) > 1 and ("" in groups or len(groups) != 1):
            rep.errors.append(f"{', '.join(codes)}: share one source picture but not one duplicate_group")
    for group, codes in dup_groups.items():
        if len(codes) < 2:
            rep.errors.append(f"{codes[0]}: duplicate_group {group!r} has no other member")
            continue
        kind = group.split(":")[0]
        if all(c in cat for c in codes) and classify(codes, cat) != kind:
            rep.errors.append(f"{', '.join(codes)}: duplicate_group kind {kind} does not match the catalogue (expected {classify(codes, cat)})")
        if len({by_code[c]["source_sha256"] for c in codes}) > 1 and kind != "suspect-different-variants":
            rep.errors.append(f"{', '.join(codes)}: different source pictures in one {kind} group (only a near-identical pair of different variants may be)")
    if not check_files:
        return rep
    present = processed.is_dir() and any(p.suffix.lower() == ".jpg" for p in processed.iterdir())
    if not present:
        rep.notes.append("processed files absent: validated the manifest alone (run tools/data/prepare_packshots.py for the files)")
        return rep
    listed = {r["processed_file"] for r in rows}
    for code in sorted(by_code):
        r = by_code[code]
        p = processed / r["processed_file"]
        if not p.is_file():
            rep.errors.append(f"{code}: processed file {r['processed_file']} is missing")
            continue
        data = p.read_bytes()
        rep.files_checked += 1
        rep.processed_total += len(data)
        if _int(r["processed_bytes"]) != len(data):
            rep.errors.append(f"{code}: size {len(data)} differs from the manifest {r['processed_bytes']}")
        if sha256_hex(data) != r["processed_sha256"]:
            rep.errors.append(f"{code}: sha256 differs from the manifest (file changed or regenerated: rerun prepare_packshots.py)")
        for prob in asset_rule_problems(data):
            rep.errors.append(f"{code}: {prob}")
        try:
            info = parse_jpeg(data)
        except ValueError as e:
            rep.errors.append(f"{code}: {e}")
            continue
        if (str(info["width"]), str(info["height"])) != (r["width"], r["height"]):
            rep.errors.append(f"{code}: pixel size {info['width']}x{info['height']} differs from the manifest {r['width']}x{r['height']}")
        if metadata_segments(info):
            rep.errors.append(f"{code}: carries metadata ({', '.join(metadata_segments(info))}); it must be stripped")
        if not info["complete"]:
            rep.errors.append(f"{code}: JPEG is cut off")
    for p in sorted(processed.iterdir()):
        if p.is_file() and not p.name.startswith(".") and p.name not in listed:
            rep.errors.append(f"{p.name}: file in the processed folder is not in the manifest")
    return rep


# ---------------------------------------------------------------------------------------------------- TOTP (RFC 6238)
def totp_code(secret: bytes, step: int, digits: int = 6) -> str:
    """HOTP of the counter `step` (RFC 4226 over HMAC-SHA1); a TOTP step is floor(unix_time / 30) (RFC 6238)."""
    h = hmac.new(secret, struct.pack(">Q", step), hashlib.sha1).digest()
    o = h[-1] & 0x0F
    n = ((h[o] & 0x7F) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3]
    return str(n % (10 ** digits)).zfill(digits)


def decode_b32(text: str) -> bytes:
    s = re.sub(r"[\s-]", "", text).upper()
    try:
        return base64.b32decode(s + "=" * (-len(s) % 8))
    except (binascii.Error, ValueError):
        raise ConfigError("the TOTP secret is not valid base32") from None


def secret_from_otpauth(uri: str) -> str | None:
    q = urllib.parse.parse_qs(urllib.parse.urlsplit(uri).query)
    v = q.get("secret")
    return v[0] if v else None


def load_totp_secret(env: dict, totp_file: str | None) -> bytes | None:
    """The TOTP secret from ARON_ADMIN_TOTP_SECRET, else from the JSON file (the answer of POST /v1/auth/mfa/enrol: its
    `otpauth_uri` holds `secret=`; `totp_secret` or `secret` keys are accepted too). Messages never echo file content."""
    raw = env.get("ARON_ADMIN_TOTP_SECRET")
    if raw and raw.strip():
        return decode_b32(raw)
    path = totp_file or env.get("ARON_ADMIN_TOTP_FILE")
    if not path:
        return None
    try:
        blob = Path(path).read_bytes()
        text = blob.decode("utf-16") if blob[:2] in (b"\xff\xfe", b"\xfe\xff") else blob.decode("utf-8-sig")
        obj = json.loads(text)
    except (OSError, ValueError):
        raise ConfigError("cannot read the TOTP file (missing, unreadable or not JSON)") from None
    secret = None
    if isinstance(obj, dict):
        uri = obj.get("otpauth_uri")
        secret = secret_from_otpauth(uri) if isinstance(uri, str) else None
        for key in ("totp_secret", "secret"):
            if not secret and isinstance(obj.get(key), str):
                secret = obj[key]
    if not secret:
        raise ConfigError("the TOTP file has no otpauth_uri (expected the answer of POST /v1/auth/mfa/enrol)")
    return decode_b32(secret)


# ---------------------------------------------------------------------------------------------------- output, clock
_URL_QUERY = re.compile(r"(https?://[^\s\"'<>?#]*)[?#][^\s\"'<>]*")


class Out:
    """Every line goes through scrub(): a URL query (a SAS signature) is masked and every secret learnt during the run
    (password, TOTP secret, access and MFA tokens, write-URL query) is replaced, whatever string it ends up in."""

    def __init__(self):
        self.secrets: list[str] = []
        self.lines: list[str] = []

    def add_secret(self, s: str | None) -> None:
        if s and len(s) >= 4 and s not in self.secrets:
            self.secrets.append(s)

    def scrub(self, text: str) -> str:
        text = _URL_QUERY.sub(r"\1?<masked>", text)
        for s in sorted(self.secrets, key=len, reverse=True):
            text = text.replace(s, "***")
        return text

    def say(self, text: object = "", err: bool = False) -> None:
        line = self.scrub(str(text))
        self.lines.append(line)
        print(line, file=sys.stderr if err else sys.stdout, flush=True)


class Clock:
    def time(self) -> float:
        return time.time()

    def sleep(self, seconds: float) -> None:
        time.sleep(max(0.0, seconds))


def parse_ts(s: object) -> float | None:
    if not isinstance(s, str):
        return None
    try:
        d = dt.datetime.fromisoformat(s.strip().replace("Z", "+00:00"))
    except ValueError:
        return None
    return (d if d.tzinfo else d.replace(tzinfo=dt.timezone.utc)).timestamp()


# ---------------------------------------------------------------------------------------------------- HTTP
class NetError(Exception):
    """A connection-level failure; the message is the exception class only (a URL never travels in it)."""


class Resp:
    def __init__(self, status: int, headers: dict, body: bytes):
        self.status, self.headers, self.body = status, headers, body

    def json(self) -> dict:
        try:
            v = json.loads(self.body.decode("utf-8"))
        except ValueError:
            return {}
        return v if isinstance(v, dict) else {}

    @property
    def problem(self) -> str:
        """The stable ERR_* code of a problem answer, '' otherwise."""
        c = self.json().get("code")
        return c if isinstance(c, str) and re.fullmatch(r"ERR_[A-Z0-9_]{1,60}", c) else ""


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """A redirect is an answer, never followed: the bearer token must not travel to another host."""

    def redirect_request(self, *args, **kwargs):
        return None


def make_opener(loopback: bool):
    handlers: list = [_NoRedirect()]
    if loopback:
        handlers.append(urllib.request.ProxyHandler({}))   # a local fake server must not go through a system proxy
    return urllib.request.build_opener(*handlers)


def _send(opener, method: str, url: str, headers: dict, body: bytes | None, timeout: float) -> Resp:
    req = urllib.request.Request(url, data=body, method=method, headers=headers)
    try:
        with opener.open(req, timeout=timeout) as r:
            return Resp(r.status, {k.lower(): v for k, v in r.getheaders()}, r.read())
    except urllib.error.HTTPError as e:
        try:
            data = e.read()
        except Exception:
            data = b""
        finally:
            e.close()
        return Resp(e.code, {k.lower(): v for k, v in e.headers.items()}, data)
    except (urllib.error.URLError, OSError, http.client.HTTPException) as e:
        raise NetError(type(e).__name__) from None


class Api:
    """The admin session: sign-in with MFA, bearer calls that sign in again when the token is near expiry or refused
    (401), and the two token-free blob calls (write URL, read URL)."""

    def __init__(self, base: str, username: str, password: str, totp: bytes | None, clock: Clock, out: Out,
                 state_file: Path | None = None):
        self.base, self.username, self.password, self.totp = base, username, password, totp
        self.clock, self.out, self.state_file = clock, out, state_file
        self.loopback = (urllib.parse.urlsplit(base).hostname or "") in LOOPBACK
        self.opener = make_opener(self.loopback)
        self.token: str | None = None
        self.expires = 0.0
        self.last_step: int | None = self._read_state()
        self.logins = 0
        out.add_secret(password)
        if totp:
            out.add_secret(base64.b32encode(totp).decode().rstrip("="))

    # -- plumbing
    def _retrying(self, method: str, url: str, headers: dict, body: bytes | None, timeout: float, tries: int = 3) -> Resp:
        for attempt in range(tries):
            try:
                r = _send(self.opener, method, url, headers, body, timeout)
            except NetError:
                if attempt == tries - 1:
                    raise
                self.clock.sleep(2 ** attempt)
                continue
            if r.status in (502, 503, 504) and attempt < tries - 1:
                self.clock.sleep(2 ** attempt)
                continue
            if r.status == 429 and attempt < tries - 1:   # the per-user rate limit (300 a minute) is far above a 160-request load
                ra = r.headers.get("retry-after", "")
                self.clock.sleep(min(int(ra), 60) if ra.isascii() and ra.isdigit() else 5)
                continue
            return r
        raise AssertionError("unreachable")

    def _api(self, method: str, path: str, obj: object = None, headers: dict | None = None, bearer: bool = False,
             retry: bool = True, shown: str | None = None) -> Resp:
        h = {"Accept": "application/json", "User-Agent": USER_AGENT}
        body = None
        if obj is not None:
            body = json.dumps(obj).encode("utf-8")
            h["Content-Type"] = "application/json"
        if bearer:
            h["Authorization"] = "Bearer " + str(self.token)
        h.update(headers or {})
        try:
            return self._retrying(method, self.base + path, h, body, 30, 3 if retry else 1)
        except NetError as e:
            raise Failure(f"{method} {shown or path.split('?')[0]}: connection failed ({e})") from None

    # -- sign-in
    def _read_state(self) -> int | None:
        """The last TOTP step an earlier run of this loader presented (a bare number kept in the temp folder: not a
        secret). The runbook's dry run, canary and apply follow each other within seconds, and a spent code counts as a
        wrong one towards the account lockout, so a run starts by waiting out the step the previous run used."""
        try:
            step = int(self.state_file.read_text(encoding="ascii").strip())   # type: ignore[union-attr]
        except (OSError, ValueError, AttributeError):
            return None
        return step if 0 <= step <= int(self.clock.time() // TOTP_STEP_S) + 2 else None   # a bogus future value would stall the run

    def _write_state(self, step: int) -> None:
        try:
            self.state_file.write_text(str(step), encoding="ascii")   # type: ignore[union-attr]
        except (OSError, AttributeError):
            pass

    def _usable_step(self) -> int:
        """The TOTP step to present: the current one, or, when that step's code was already presented, the next one
        (the server accepts a step once; presenting a spent code counts as a wrong code towards the lockout)."""
        now = self.clock.time()
        step = int(now // TOTP_STEP_S)
        if self.last_step is not None and step <= self.last_step:
            wait = (self.last_step + 1) * TOTP_STEP_S - now + 1.0
            self.out.say(f"waiting {math.ceil(wait)} s for the next authenticator step (a code works once)")
            self.clock.sleep(wait)
            step = int(self.clock.time() // TOTP_STEP_S)
        return step

    def login(self) -> None:
        r = self._api("POST", "/v1/auth/login", {"username": self.username, "password": self.password, "client": "web"})
        if r.status != 200:
            raise LoginFailure(f"login: HTTP {r.status} {r.problem}".rstrip())
        body = r.json()
        status = body.get("status")
        if status == "mfa_required":
            if self.totp is None:
                raise LoginFailure("login: the account needs MFA; give ARON_ADMIN_TOTP_SECRET or --totp-file")
            mfa_token = body.get("mfa_token")
            if not isinstance(mfa_token, str):
                raise LoginFailure("login: no mfa_token in the answer")
            self.out.add_secret(mfa_token)
            for attempt in (1, 2):
                step = self._usable_step()
                code = totp_code(self.totp, step)
                self.last_step = step   # presented, so spent whatever the answer
                self._write_state(step)
                # never resent on a gateway error or a lost answer: the code may have been accepted, and a spent one counts as wrong
                r = self._api("POST", "/v1/auth/mfa/verify", {"mfa_token": mfa_token, "code": code}, retry=False)
                if r.status == 200:
                    break
                if attempt == 1 and r.status == 401 and r.problem == "ERR_AUTH_MFA_INVALID":
                    self.out.say("login: the code was refused; trying once more with the next step")
                    continue
                raise LoginFailure(f"login: MFA step refused, HTTP {r.status} {r.problem}".rstrip())
            body = r.json()
            status = body.get("status")
        if status == "password_change_required":
            raise LoginFailure("login: the account must change its password first (sign in on the web admin once)")
        token = body.get("access_token")
        if status != "ok" or not isinstance(token, str):
            raise LoginFailure("login: no access token in the answer")
        self.token = token
        self.out.add_secret(token)
        self.expires = parse_ts(body.get("access_expires_at")) or (self.clock.time() + 14 * 60)
        self.logins += 1

    def call(self, method: str, path: str, obj: object = None, headers: dict | None = None, shown: str | None = None) -> Resp:
        """A bearer call. `shown` names the path in a failure message when the real one holds an id: the stop rule
        compares failure texts, so three SKUs failing the same way must read the same."""
        if self.token is None or self.clock.time() >= self.expires - REFRESH_MARGIN_S:
            if self.token is not None:
                self.out.say("access token near expiry: signing in again")
            self.login()
        r = self._api(method, path, obj, headers, bearer=True, shown=shown)
        if r.status == 401:
            self.out.say("session refused (401): signing in again")
            self.login()
            r = self._api(method, path, obj, headers, bearer=True, shown=shown)
        return r

    def blob(self, method: str, url: str, headers: dict | None = None, body: bytes | None = None) -> Resp:
        """A call to a write or read URL: no bearer token, https only (http for a loopback test server)."""
        u = urllib.parse.urlsplit(url)
        if not (u.scheme == "https" or (u.scheme == "http" and (u.hostname or "") in LOOPBACK)):
            raise Failure(f"{method} blob: the URL is not https")
        h = {"User-Agent": USER_AGENT}
        h.update(headers or {})
        try:
            return self._retrying(method, url, h, body, 60)
        except NetError as e:
            raise Failure(f"{method} blob: connection failed ({e})") from None


# ---------------------------------------------------------------------------------------------------- the load
PAST = {"bind": "bound", "rebind": "rebound", "repair": "repaired"}


class Summary:
    def __init__(self):
        self.skipped = self.bound = self.repaired = self.failed = self.assets = 0
        self.bound_unverified = 0           # of `failed`: bound on the server in this run, but its check failed
        self.would_upload = self.would_bind = self.would_replace = 0
        self.unbound = self.unbound_already = self.left_alone = 0


class Loader:
    def __init__(self, api: Api, rows: list[dict], processed: Path, out: Out, apply: bool,
                 suspect: dict[str, str] | None = None):
        self.api, self.rows, self.processed, self.out, self.apply = api, rows, processed, out, apply
        self.suspect = suspect or {}        # sku_code -> why, for the rows the manifest marks suspect (suspect_rows)
        self.skus: dict[str, dict] = {}
        self.ready: set[str] = set()        # assets known to be in the store (verified or uploaded in this run)
        self.uploaded: set[str] = set()     # assets this run PUT itself
        self.owner_of: dict[str, str] = {}  # asset id -> the first SKU of this run that uses it
        self.bound_now: list[str] = []      # SKUs this run bound (a PATCH went through): what --unbind would undo
        # of bound_now: the PATCH answer showed no picture (no image_asset_id, no image_url), so --unbind cannot see the binding
        self.no_link: list[str] = []
        self.streak_why, self.streak = "", 0
        self.steps: list[str] = []
        self.s = Summary()

    def fetch_skus(self) -> dict[str, dict]:
        items: dict[str, dict] = {}
        cursor = None
        while True:
            r = self.api.call("GET", "/v1/admin/skus?limit=500" + (f"&cursor={urllib.parse.quote(str(cursor), safe='')}" if cursor else ""))
            if r.status != 200:
                raise Failure(f"list SKUs: HTTP {r.status} {r.problem}".rstrip())
            body = r.json()
            for it in body.get("items") or []:
                if isinstance(it, dict) and isinstance(it.get("code"), str):
                    items[it["code"]] = it
            cursor = body.get("next_cursor")
            if not cursor:
                return items

    @staticmethod
    def _image_problem(r: Resp, sha: str, what: str) -> str:
        """'' when the answer is 200, image/jpeg and the file's bytes; else why not. A wrong content type would be refused
        by the phone's image cache for the whole process; a missing blob would show the silhouette for ever."""
        if r.status != 200:
            return f"{what} answers HTTP {r.status}" + (f" {r.problem}" if r.problem else "")
        ctype = r.headers.get("content-type", "").split(";")[0].strip().lower()
        if ctype != MIME:
            return f"{what}: content-type is {ctype or 'missing'}, not {MIME}"
        if sha256_hex(r.body) != sha:
            return f"{what}: the stored bytes differ from the file (sha256)"
        return ""

    def verify(self, url: str | None, sha: str) -> tuple[bool, str, str]:
        """GET the admin's read link of the blob (`image_url`) and check 200, image/jpeg and the file's sha256.
        Returns (ok, step text, reason)."""
        if not url:
            return False, "GET blob -", "no image_url (is the blob store configured?)"
        r = self.api.blob("GET", url)
        step = f"GET blob {r.status}"
        why = self._image_problem(r, sha, "the picture")
        return not why, step, why

    def verify_phone_path(self, asset_id: str, sha: str) -> tuple[bool, str, str]:
        """GET /v1/sku-images/{asset_id} with the admin token: the path the phones take (SkuImageRead.kt). The API reads
        the blob itself and answers 404 unless the asset is a stored sku_image of at most 300 KB that a SKU binds, so a
        200 with the file's bytes proves the bind and the storage read together. The asset id stays out of the texts:
        the stop rule compares failure texts, and they must read the same for every SKU."""
        r = self.api.call("GET", SKU_IMAGE_PATH + asset_id, headers={"Accept": "image/jpeg, image/png"},
                          shown=SKU_IMAGE_PATH + "{asset_id}")
        step = f"GET api {r.status}"
        why = self._image_problem(r, sha, "the phone path")
        return not why, step, why

    def check(self, asset_id: str, url: str | None, sha: str) -> tuple[bool, str, str]:
        """Both reads of a bound picture, the blob first (a failure of it says more than the phone path would)."""
        ok, step, why = self.verify(url, sha)
        if not ok:
            return ok, step, why
        ok2, step2, why2 = self.verify_phone_path(asset_id, sha)
        return ok2, f"{step} {step2}", why2

    def set_image(self, code: str, asset_id: str | None, reason: str, expect: str | None = None) -> dict:
        """PATCH image_asset_id (an asset id binds, None unbinds) with If-Match; a stale If-Match (412) is refetched and
        retried once. `expect` is the asset id an unbind decided to take off, on the list fetched at the start of the run:
        a 412 means somebody changed the SKU since, so the retry first repeats that decision on the refetched SKU, and a SKU
        that holds anything else by now (the owner chose a picture in the web admin meanwhile) is left alone
        (ChangedMeanwhile) instead of being unbound. Returns the SKU the server answered with."""
        body = {"image_asset_id": asset_id, "change_reason": reason}
        sku = self.skus[code]
        for attempt in (1, 2):
            r = self.api.call("PATCH", f"/v1/admin/skus/{sku['id']}", body, {"If-Match": f'"{sku.get("version")}"'})
            self.steps.append(f"PATCH {r.status}")
            if r.status == 200:
                return r.json()
            if r.status == 412 and attempt == 1:
                self.skus = self.fetch_skus()
                sku = self.skus.get(code)
                if sku is None:
                    raise Failure("PATCH: the SKU is gone after a 412")
                has_now, now = picture_of(sku)
                if (now == asset_id) if asset_id else not has_now:
                    return sku   # somebody made it so meanwhile
                if expect is not None and now != expect:
                    raise ChangedMeanwhile(now)
                continue
            raise Failure(f"PATCH: HTTP {r.status} {r.problem}".rstrip())
        raise AssertionError("unreachable")

    def patch(self, code: str, asset_id: str, sha: str) -> dict:
        """Binds the asset."""
        return self.set_image(code, asset_id, f"Pack picture loaded by tools/data/load_packshots.py (D-PACK-03), sha256 {sha[:16]}")

    def upload(self, asset_id: str, data: bytes, sha: str) -> None:
        """Registers the asset and PUTs the bytes. Any failure raises before the caller can PATCH: the server never
        checks that the blob exists, so binding after a failed PUT would leave the SKU pointing at nothing."""
        r = self.api.call("POST", "/v1/admin/assets", {"asset_id": asset_id, "purpose": PURPOSE, "mime": MIME,
                                                        "bytes": len(data), "sha256": sha})
        self.steps.append(f"POST {r.status}")
        if r.status != 200:
            raise Failure(f"POST asset: HTTP {r.status} {r.problem}".rstrip())
        info = r.json()
        url = info.get("upload_url")
        if info.get("asset_id") != asset_id or not isinstance(url, str):
            raise Failure("POST asset: the answer does not match the request")
        self.out.add_secret(urllib.parse.urlsplit(url).query)
        p = self.api.blob("PUT", url, {"x-ms-blob-type": "BlockBlob", "Content-Type": MIME, "x-ms-blob-content-type": MIME}, data)
        self.steps.append(f"PUT {p.status}")
        if p.status not in (200, 201):
            raise Failure(f"PUT blob: HTTP {p.status}")
        self.uploaded.add(asset_id)
        self.ready.add(asset_id)
        self.s.assets += 1

    # -- fail fast
    def worked(self) -> None:
        """A SKU went through: the streak of identical failures is over."""
        self.streak_why, self.streak = "", 0

    def failed(self, why: str, fresh_bind: bool = False) -> None:
        """Called after a SKU failed; stops the run (Abort) when the failure looks systematic: the check of a picture failed
        right after this run bound it (the server accepts a bind without looking at the blob, so the same cause would
        leave every further SKU bound to a bad blob), or STREAK_LIMIT SKUs in a row failed the same way."""
        if fresh_bind:
            raise Abort("a SKU failed its check right after it was bound; the cause (the stored content type, the read URL, "
                        "the token) would repeat for every other SKU")
        self.streak = self.streak + 1 if why == self.streak_why else 1
        self.streak_why = why
        if self.streak >= STREAK_LIMIT:
            raise Abort(f"{STREAK_LIMIT} SKUs in a row failed the same way ({why})")

    def tag(self, code: str) -> str:
        return " [SUSPECT picture: owner to confirm]" if code in self.suspect else ""

    def report_bound(self) -> None:
        """What this run bound and how to take it off. --unbind goes by what the SKU's answer shows (image_asset_id, or the
        image_url), so a binding whose PATCH answer showed neither (the API could not sign a read URL, and does not say the
        asset id) is invisible to it: `--unbind --only` would print 'no picture: nothing to unbind' and change nothing.
        Those SKUs get their own next step instead of a command that does nothing."""
        if not self.bound_now:
            self.out.say("no SKU was bound in this run", err=True)
            return
        self.out.say(f"bound in this run ({len(self.bound_now)}): {', '.join(self.bound_now)}", err=True)
        undo = [c for c in self.bound_now if c not in self.no_link]
        if undo:
            self.out.say("undo with: python -P tools/data/load_packshots.py --unbind --only " + ",".join(undo), err=True)
        if self.no_link:
            codes = ",".join(self.no_link)
            self.out.say(f"cannot be undone with --unbind ({codes}): the API answered with no picture link (no image_url, no "
                         "image_asset_id), and --unbind goes by those, so it would print 'no picture: nothing to unbind' and "
                         "change nothing", err=True)
            self.out.say(f"next step for {codes}: fix the read-URL signing on the server and run --apply --only {codes} again "
                         "(it checks the binding), or clear the pack picture in the web admin SKU form", err=True)

    # -- the load
    def one(self, row: dict) -> None:
        code = row["sku_code"]
        data = (self.processed / row["processed_file"]).read_bytes()
        sha = sha256_hex(data)
        asset_id = asset_id_for(sha)
        sku = self.skus.get(code)
        if sku is None:
            raise SkuMissing("no such SKU on the server")
        tag = self.tag(code)
        first = self.owner_of.setdefault(asset_id, code)
        shared = f" (asset shared with {first})" if first != code else ""
        has_picture, bound = picture_of(sku)
        if bound == asset_id:
            try:
                ok, step, _why = self.check(asset_id, sku.get("image_url"), sha)
            except Failure as e:   # the read host is down or hangs: through failed(), so three in a row stop the run
                self.s.failed += 1
                self.out.say(f"{code:<20} FAILED {e}{tag}", err=True)
                self.failed(str(e))
                return
            if ok:
                self.ready.add(asset_id)
                self.s.skipped += 1
                self.worked()
                self.out.say(f"{code:<20} skipped: already bound, {step} image/jpeg, sha256 ok{tag}")
                return
            action = "repair"
        else:
            action = "rebind" if has_picture else "bind"
        need_upload = (asset_id not in self.uploaded) if action == "repair" else (asset_id not in self.ready)
        if not self.apply:
            todo = (["upload"] if need_upload else []) + ([] if action == "repair" else ["bind"])
            self.s.would_upload += need_upload
            self.s.would_bind += action != "repair"
            self.s.would_replace += action == "rebind"
            self.ready.add(asset_id)   # planned: the next SKU with the same art needs no second upload
            self.out.say(f"{code:<20} would {' + '.join(todo)}{shared}" + (" [replaces another picture]" if action == "rebind" else "")
                         + (" [bound picture does not verify]" if action == "repair" else "") + tag)
            return
        self.steps = []
        fresh = False
        try:
            if need_upload:
                self.upload(asset_id, data, sha)
            if action == "repair":
                url = sku.get("image_url")
            else:
                answer = self.patch(code, asset_id, sha)
                url = answer.get("image_url")
                fresh = True
                self.bound_now.append(code)
                if not picture_of(answer)[0]:
                    self.no_link.append(code)
            ok, step, why = self.check(asset_id, url, sha)
            self.steps.append(step)
            if not ok:
                raise Failure(why)
        except LoginFailure:
            raise
        except Failure as e:
            self.s.failed += 1
            self.s.bound_unverified += fresh
            self.out.say(f"{code:<20} FAILED {e}  [{' '.join(self.steps)}]{tag}", err=True)
            self.failed(str(e), fresh_bind=fresh)
            return
        if action == "repair":
            self.s.repaired += 1
        else:
            self.s.bound += 1
        self.worked()
        self.out.say(f"{code:<20} {PAST[action]}{shared}  [{' '.join(self.steps)}] verified image/jpeg, sha256 ok{tag}")

    def run(self) -> int:
        self.skus = self.fetch_skus()
        mine = {r["sku_code"] for r in self.rows}
        self.out.say(f"server: {len(self.skus)} SKUs; manifest: {len(self.rows)} pictures; "
                     f"{len(mine - set(self.skus))} manifest codes unknown to the server")
        stopped = ""
        attempted = 0
        for row in sorted(self.rows, key=lambda r: r["sku_code"]):
            attempted += 1
            try:
                self.one(row)
            except Abort as e:
                stopped = f"{row['sku_code']}: {e}"
                break
            except (LoginFailure, KeyboardInterrupt):
                if self.bound_now:
                    self.report_bound()
                raise
            except Failure as e:
                self.s.failed += 1
                self.out.say(f"{row['sku_code']:<20} FAILED {e}{self.tag(row['sku_code'])}", err=True)
        s = self.s
        if stopped:
            self.out.say(f"STOPPED: {stopped}", err=True)
            self.report_bound()
            self.out.say(f"{len(self.rows) - attempted} of {len(self.rows)} SKUs were not attempted", err=True)
        if self.apply:
            # a SKU bound in this run whose check then failed counts as failed, and says it is bound on the server, so the
            # line agrees with "bound in this run" above it
            also = f" ({s.bound_unverified} of them bound on the server, but failed their check)" if s.bound_unverified else ""
            self.out.say(f"summary: {len(self.rows)} pictures: {s.bound} bound, {s.repaired} repaired, {s.skipped} skipped, "
                         f"{s.failed} failed{also}; {s.assets} assets uploaded")
        else:
            self.out.say(f"plan: {len(self.rows)} pictures: {s.skipped} skipped, {s.would_bind} to bind, "
                         f"{s.would_upload} assets to upload, {s.failed} not loadable (dry run: nothing was written)")
            if s.would_replace:
                self.out.say(f"WARNING: {s.would_replace} of the pictures to bind would REPLACE a picture that is already set "
                             "(the lines marked [replaces another picture]): --apply overwrites it, and --unbind afterwards "
                             "leaves that SKU with no picture (it never restores an earlier one)")
        if self.suspect:
            self.out.say(f"WARNING: {len(self.suspect)} suspect pictures in this run ({', '.join(sorted(self.suspect))}); "
                         "the owner has not confirmed that they show the right pack")
        return 1 if s.failed or stopped else 0

    # -- the undo
    def unbind(self) -> int:
        """PATCH image_asset_id null for every selected SKU that is bound to a picture of this manifest. A SKU bound to
        any other picture (the web admin's choice, an older load) is left alone and said so, also when it changed only
        while the run was going (set_image repeats the test after a 412); a SKU whose answer shows no picture needs nothing
        (and one bound with no readable link is invisible here: report_bound says what to do instead). The asset and the
        blob stay (assets are append-only); an earlier picture is never restored."""
        self.skus = self.fetch_skus()
        mine = {r["sku_code"] for r in self.rows}
        self.out.say(f"server: {len(self.skus)} SKUs; manifest: {len(self.rows)} pictures; "
                     f"{len(mine - set(self.skus))} manifest codes unknown to the server")
        s = self.s
        stopped = ""
        for row in sorted(self.rows, key=lambda r: r["sku_code"]):
            code = row["sku_code"]
            self.steps = []
            try:
                sku = self.skus.get(code)
                if sku is None:
                    raise SkuMissing("no such SKU on the server")
                has_picture, bound = picture_of(sku)
                if not has_picture:
                    s.unbound_already += 1
                    self.out.say(f"{code:<20} no picture: nothing to unbind")
                    continue
                if bound != asset_id_for(row["processed_sha256"]):
                    s.left_alone += 1
                    self.out.say(f"{code:<20} left alone: bound to a picture that is not in this manifest"
                                 + (f" (asset {bound[:8]})" if bound else ""))
                    continue
                out = self.set_image(code, None, "Pack picture taken off by tools/data/load_packshots.py --unbind (D-PACK-03)",
                                     expect=bound)
                if picture_of(out)[0]:
                    raise Failure("PATCH answered 200 but the SKU still has a picture")
            except LoginFailure:
                raise
            except ChangedMeanwhile as e:   # the "left alone" test, repeated on the refetched SKU after a stale If-Match
                s.left_alone += 1
                self.out.say(f"{code:<20} left alone: changed meanwhile, now bound to a picture that is not in this manifest"
                             + (f" (asset {e.bound[:8]})" if e.bound else "") + f"  [{' '.join(self.steps)}]")
                continue
            except Failure as e:
                s.failed += 1
                self.out.say(f"{code:<20} FAILED {e}  [{' '.join(self.steps)}]", err=True)
                try:
                    if not isinstance(e, SkuMissing):
                        self.failed(str(e))
                except Abort as a:
                    stopped = f"{code}: {a}"
                    break
                continue
            s.unbound += 1
            self.worked()
            self.out.say(f"{code:<20} unbound  [{' '.join(self.steps)}]")
        if stopped:
            self.out.say(f"STOPPED: {stopped}", err=True)
        self.out.say(f"summary: {len(self.rows)} SKUs: {s.unbound} unbound, {s.unbound_already} had no picture, "
                     f"{s.left_alone} left alone (bound to another picture), {s.failed} failed")
        return 1 if s.failed or stopped else 0


# ---------------------------------------------------------------------------------------------------- command line
def state_path(env: dict, base: str, username: str) -> Path:
    """Where the last presented TOTP step is kept: ARON_PACKSHOT_STATE_DIR or the temp folder, one file per API and user."""
    key = hashlib.sha256(f"{base}|{username}".encode("utf-8")).hexdigest()[:12]
    return Path(env.get("ARON_PACKSHOT_STATE_DIR") or tempfile.gettempdir()) / f"aron-load-packshots-{key}.step"


def normalise_base(raw: str) -> str:
    u = urllib.parse.urlsplit(raw.strip())
    host = u.hostname or ""
    if u.scheme != "https" and not (u.scheme == "http" and host in LOOPBACK):
        raise ConfigError("the API base must be https://HOST (http only for localhost)")
    if u.username or u.password or u.query or u.fragment:
        raise ConfigError("the API base must be a plain origin: no credentials, query or fragment")
    base = f"{u.scheme}://{u.netloc}{u.path}".rstrip("/")
    return base[:-3] if base.endswith("/v1") else base


def main(argv: list[str] | None = None, env: dict | None = None, clock: Clock | None = None, out: Out | None = None) -> int:
    env = os.environ if env is None else env
    out = out or Out()
    clock = clock or Clock()
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0], formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true", help="offline: manifest vs catalogue, files, hashes, sizes")
    mode.add_argument("--dry-run", action="store_true", help="online, read only: the plan per SKU")
    mode.add_argument("--apply", action="store_true", help="online: upload, bind, verify (needs the owner's go-ahead)")
    mode.add_argument("--unbind", action="store_true",
                      help="online: PATCH image_asset_id null (If-Match) for the SKUs bound to a picture of this manifest; "
                           "the rollback of --apply")
    ap.add_argument("--api-base", help="https://HOST, or ARON_API_BASE")
    ap.add_argument("--username", help="admin login, or ARON_ADMIN_USER (default admin1001)")
    ap.add_argument("--totp-file", help="JSON file with the otpauth_uri of the admin's TOTP enrolment, or ARON_ADMIN_TOTP_FILE")
    ap.add_argument("--only", help="comma-separated SKU codes: load (or unbind) only these")
    ap.add_argument("--skip-suspect", action="store_true",
                    help="with --dry-run or --apply: leave out the SKUs whose picture the manifest marks suspect-*")
    ap.add_argument("--manifest", type=Path, default=DEFAULT_MANIFEST)
    ap.add_argument("--processed-dir", type=Path, default=DEFAULT_PROCESSED)
    ap.add_argument("--catalog", type=Path, default=DEFAULT_CATALOG)
    args = ap.parse_args(argv)
    try:
        return _run(args, env, clock, out)
    except ConfigError as e:
        out.say(f"error: {e}", err=True)
        return 2
    except LoginFailure as e:
        out.say(f"error: {e}", err=True)
        return 1
    except Failure as e:
        out.say(f"error: {e}", err=True)
        return 1
    except KeyboardInterrupt:
        return 130
    except Exception as e:   # no traceback: one never knows which value a frame would show next to a token or a URL
        out.say(f"error: unexpected {type(e).__name__}", err=True)
        return 1


def warn_suspects(out: Out, suspects: dict[str, str], left_out: bool = False) -> None:
    """One visible WARNING line per suspect SKU (the manifest marks a picture shared by different variants, or twice for one
    pack, as suspect-*): the loader loads them like any other unless --skip-suspect says otherwise."""
    for code in sorted(suspects):
        out.say(f"WARNING {code:<20} suspect picture: {suspects[code]}" + (" [left out by --skip-suspect]" if left_out else ""))


def _run(args, env, clock: Clock, out: Out) -> int:
    if args.skip_suspect and (args.check or args.unbind):
        raise ConfigError("--skip-suspect belongs to --dry-run and --apply")
    rep = check_offline(args.manifest, args.processed_dir, args.catalog, check_files=not args.unbind)
    for n in rep.notes:
        out.say("note: " + n)
    for e in rep.errors[:60]:
        out.say("problem: " + e, err=True)
    if len(rep.errors) > 60:
        out.say(f"problem: ... and {len(rep.errors) - 60} more", err=True)
    suspects = suspect_rows(rep.rows)
    if args.check:
        warn_suspects(out, suspects)
        if rep.errors:
            out.say(f"check: FAILED, {len(rep.errors)} problems", err=True)
            return 1
        kb = rep.processed_total / 1024
        out.say(f"check: OK, {len(rep.rows)} SKUs 1:1 with the catalogue"
                + (f", {rep.files_checked} processed files verified ({kb:.0f} KB)" if rep.files_checked else ", manifest only")
                + (f"; {len(suspects)} suspect pictures (see the WARNING lines)" if suspects else ""))
        return 0
    if rep.errors:
        out.say("error: the offline check failed; nothing was sent", err=True)
        return 1
    if not args.unbind and not rep.files_checked:
        raise ConfigError("no processed files: run tools/data/prepare_packshots.py first")
    base = args.api_base or env.get("ARON_API_BASE")
    if not base:
        raise ConfigError("give the API base with --api-base or ARON_API_BASE")
    base = normalise_base(base)
    password = (env.get("ARON_ADMIN_PASSWORD") or "").rstrip("\r\n")
    if not password:
        raise ConfigError("ARON_ADMIN_PASSWORD is not set")
    rows = rep.rows
    if args.only:
        want = [c.strip() for c in args.only.split(",") if c.strip()]
        unknown = sorted(set(want) - {r["sku_code"] for r in rows})
        if unknown:
            raise ConfigError("--only names no manifest SKU: " + ", ".join(unknown))
        rows = [r for r in rows if r["sku_code"] in want]
    totp = load_totp_secret(env, args.totp_file)
    username = args.username or env.get("ARON_ADMIN_USER") or "admin1001"
    api = Api(base, username, password, totp, clock, out, state_path(env, base, username))
    host = urllib.parse.urlsplit(base).netloc
    if args.unbind:
        out.say(f"unbind: {len(rows)} SKUs on {host}")
        api.login()
        out.say("login: ok")
        return Loader(api, rows, args.processed_dir, out, False).unbind()
    selected = {r["sku_code"] for r in rows}
    in_scope = {c: why for c, why in suspects.items() if c in selected}
    warn_suspects(out, in_scope, left_out=args.skip_suspect)
    if args.skip_suspect:
        rows = [r for r in rows if r["sku_code"] not in in_scope]
        in_scope = {}
    out.say(f"{'apply' if args.apply else 'dry run'}: {len(rows)} pictures to {host}"
            + (f" ({len(suspects)} suspect pictures left out by --skip-suspect)" if args.skip_suspect and suspects else ""))
    api.login()
    out.say("login: ok")
    return Loader(api, rows, args.processed_dir, out, args.apply, in_scope).run()


if __name__ == "__main__":
    sys.exit(main())
