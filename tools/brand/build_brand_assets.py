#!/usr/bin/env python3
"""Generate every Aron brand raster from the one owner-supplied master (Pillow only, deterministic, idempotent).

    python tools/brand/build_brand_assets.py                 # regenerate every output and assets/brand/manifest.json
    python tools/brand/build_brand_assets.py --check         # verify the files on disk against the manifest, write nothing
    python tools/brand/build_brand_assets.py --check-regen   # regenerate in memory and compare byte for byte (same Pillow build only)

Master: assets/brand/aron-logo-original.png (500x499 RGBA, transparent, supplied by the owner on 2026-10-18; ownership per the owner).
Nothing here recolours, redraws or adds an effect to the artwork: every output is a crop, a LANCZOS resize and a placement.
The one derived picture, the Android 13 themed-icon layer, is a tone map of the same pixels (see mono_alpha).

Outputs (decision D-BRAND-01; consumers are the app shells, feature-auth and web):
  android/core-ui/src/main/res/mipmap-{xhdpi,xxhdpi,xxxhdpi}/aron_launcher_foreground.webp   adaptive-icon foreground, 108 dp layer
  android/core-ui/src/main/res/mipmap-{xhdpi,xxhdpi,xxxhdpi}/aron_launcher_monochrome.webp   Android 13 themed-icon layer, same canvas
  android/core-ui/src/main/res/drawable-nodpi/aron_brand_mark.png                             in-app mark, 256 px, lossless
  web/public/brand/aron-mark-{64,128,256}.png                                                 shared BrandMark
  web/src/app/{icon.png,apple-icon.png,favicon.ico}                                           Next file conventions
  backend/analytics/src/main/resources/brand/aron-mark-64.png                                 printed/PDF report header (embedded as a data: URI)
  assets/brand/manifest.json                                                                  path, bytes, sha256, pixels, parameters

What "inside the safe zone" means here. The 108 dp launcher layer keeps a 66 dp circle (radius 33 dp) that no system mask crops.
The glyph is 60 dp tall, and the generator measures the distance from the layer centre to the outer corner of the farthest pixel
whose alpha is at least ALPHA_FLOOR (16 of 255, about 6 percent opacity) and refuses to write above 33 dp. That distance is
32.93 / 32.70 / 32.81 dp at xhdpi / xxhdpi / xxxhdpi (0.07 dp of margin at the tightest, xhdpi). It is NOT true for every nonzero
pixel: counting alpha >= 1 the farthest reaches 33.44 / 33.04 / 33.06 dp, so an antialiasing halo fainter than 6 percent opacity
can sit up to 0.44 dp outside the circle. Both figures are recorded in the manifest (parameters.launcher).

--check proves that the files on disk equal the manifest (size, sha256, decoded pixel size, every ICO frame) and that the manifest
lists exactly the outputs this script makes. It does NOT prove that the manifest equals what the current script would produce
(change a parameter without regenerating and --check still passes); only --check-regen proves that. The encoded bytes depend on
the Pillow build (zlib, libwebp): the manifest records the build that wrote them, --check-regen is only meaningful on that
build, and a regenerate on another build rewrites every file (the script warns). Pillow is needed for every mode, --check included.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import math
import random
import struct
import sys
from pathlib import Path

from PIL import Image, UnidentifiedImageError
from PIL import __version__ as PILLOW_VERSION

REPO = Path(__file__).resolve().parents[2]
MASTER_REL = "assets/brand/aron-logo-original.png"
MANIFEST_REL = "assets/brand/manifest.json"
RES = "android/core-ui/src/main/res"

# --- parameters (all of them are copied into the manifest) --------------------------------------------------------------
LAUNCHER_LAYER_DP = 108  # adaptive-icon layer
LAUNCHER_GLYPH_DP = 60  # glyph height; the brief caps it at 60 because 62 touches the circle
SAFE_RADIUS_DP = 33.0  # the 66 dp safe zone of the 108 dp layer, as a radius
SAFE_MARGIN_DP = 0.25  # kept clear inside the safe circle
LAUNCHER_DENSITIES = (("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4))
LAUNCHER_WEBP = {"quality": 85, "method": 6}  # lossy colour; the alpha plane is lossless (Pillow default alpha_quality=100)
MONO_WEBP = {"quality": 75, "method": 6, "alpha_quality": 70}  # the colour is constant black; alpha_quality 70 halves the size, max alpha error 12/255
BRAND_MARK_PX = 256
BRAND_MARK_GLYPH_PX = 236
WEB_MARK_SIZES = (64, 128, 256)
WEB_MARK_PAD = 0.06  # per side
REPORT_MARK_PX = 64  # report header mark: drawn about 28 css px wide on white paper, so 64 px is 2x or more at print resolution
REPORT_MARK_PAD = 0.06  # per side; same placement as the web 64 px mark, which it matches byte for byte
WEB_ICON_PX = 192  # icon.png is only the tab and bookmark icon (no PWA manifest wants 512): about 28 KB, against 154 KB at 512 px
WEB_ICON_PAD = 0.06
APPLE_ICON_PX = 180
APPLE_GLYPH_PX = 130
PAGE_PLATE = (0xE8, 0xEF, 0xFA)  # bg.solid, light (docs/design/tokens.md s2.1)
FAVICON_FRAMES = ((16, 16), (32, 30), (48, 44))  # (canvas px, glyph height px): a 16 px tall glyph is mushy, so the full height
ALPHA_FLOOR = 16  # pixels with alpha below this (of 255) do not count when measuring the glyph extent and the safe zone

# monochrome tone map (alpha 0..1 per pixel): navy outline and orange core are solid, ice is a lighter tone, the counter is cut
MONO_ICE_MIN = 0.35  # alpha of the lightest ice
MONO_ICE_SPAN = 0.65  # ice alpha rises by this much as the pixel gets darker
COUNTER_SEED = (149, 171)  # a pixel inside the violet counter, in glyph-crop coordinates (master 256,238 minus the crop origin 107,67)
COUNTER_AREA = (300, 3000)  # sanity bounds on the flood-filled counter, in pixels


# --- output paths (static, so --check does not have to render anything) ---------------------------------------------------
def launcher_path(kind: str, density: str) -> str:
    return f"{RES}/mipmap-{density}/aron_launcher_{kind}.webp"


BRAND_MARK_REL = f"{RES}/drawable-nodpi/aron_brand_mark.png"
WEB_ICON_REL = "web/src/app/icon.png"
APPLE_ICON_REL = "web/src/app/apple-icon.png"
FAVICON_REL = "web/src/app/favicon.ico"
REPORT_MARK_REL = "backend/analytics/src/main/resources/brand/aron-mark-64.png"


def web_mark_path(size: int) -> str:
    return f"web/public/brand/aron-mark-{size}.png"


def output_paths() -> list[str]:
    """Every file the generator writes, in manifest order (the manifest itself excluded)."""
    return ([launcher_path("foreground", n) for n, _ in LAUNCHER_DENSITIES]
            + [launcher_path("monochrome", n) for n, _ in LAUNCHER_DENSITIES]
            + [BRAND_MARK_REL] + [web_mark_path(s) for s in WEB_MARK_SIZES] + [WEB_ICON_REL, APPLE_ICON_REL, FAVICON_REL]
            + [REPORT_MARK_REL])


# --- small helpers ------------------------------------------------------------------------------------------------------
def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def clamp01(v: float) -> float:
    return 0.0 if v < 0.0 else 1.0 if v > 1.0 else v


def png_bytes(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.save(buf, "PNG", optimize=True)  # no metadata, no timestamp chunk: deterministic
    return buf.getvalue()


def webp_bytes(img: Image.Image, **params) -> bytes:
    buf = io.BytesIO()
    img.save(buf, "WEBP", **params)
    return buf.getvalue()


def ico_bytes(frames: list[Image.Image]) -> bytes:
    """An ICO of PNG frames written by hand so each frame is an optimised PNG (Pillow's writer does not optimise them)."""
    pngs = [png_bytes(f) for f in frames]
    out = bytearray(struct.pack("<HHH", 0, 1, len(frames)))
    offset = 6 + 16 * len(frames)
    for f, data in zip(frames, pngs):
        w, h = f.size
        out += struct.pack("<BBBBHHII", w % 256, h % 256, 0, 0, 1, 32, len(data), offset)
        offset += len(data)
    for data in pngs:
        out += data
    return bytes(out)


# --- the glyph -----------------------------------------------------------------------------------------------------------
def load_glyph(master: Image.Image) -> Image.Image:
    """The master cropped to its visible bounding box (237x361: a tall, slanted letter A of ice shards)."""
    bbox = master.getchannel("A").getbbox()
    if bbox is None:
        raise SystemExit("master has no visible pixels")
    return master.crop(bbox)


def place(glyph: Image.Image, canvas: int, height: int, anchor: tuple[float, float] | None = None) -> Image.Image:
    """Resize the glyph to `height` px tall (LANCZOS; Pillow resizes RGBA premultiplied) and put it on a transparent square.

    `anchor` is a point in glyph-crop coordinates that lands on the canvas centre; None means the bounding-box centre.
    """
    gw, gh = glyph.size
    scale = height / gh
    width = max(1, round(gw * scale))
    ax, ay = anchor if anchor is not None else (gw / 2, gh / 2)
    x0 = math.floor(canvas / 2 - ax * scale + 0.5)
    y0 = math.floor(canvas / 2 - ay * scale + 0.5)
    if x0 < 0 or y0 < 0 or x0 + width > canvas or y0 + height > canvas:
        raise SystemExit(f"glyph {width}x{height} at ({x0},{y0}) does not fit a {canvas} px canvas")
    resized = glyph.resize((width, height), Image.LANCZOS)
    out = Image.new("RGBA", (canvas, canvas), (0, 0, 0, 0))
    out.paste(resized, (x0, y0))
    return out


# --- launcher geometry ---------------------------------------------------------------------------------------------------
def _hull(points: list[tuple[float, float]]) -> list[tuple[float, float]]:
    pts = sorted(set(points))
    if len(pts) < 3:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower: list = []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    upper: list = []
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def _circle2(a, b):
    cx, cy = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
    return cx, cy, max(math.hypot(cx - a[0], cy - a[1]), math.hypot(cx - b[0], cy - b[1]))


def _circle3(a, b, c):
    """Circle through three points; a collinear triple falls back to the circle on its two farthest points."""
    ox = (min(a[0], b[0], c[0]) + max(a[0], b[0], c[0])) / 2
    oy = (min(a[1], b[1], c[1]) + max(a[1], b[1], c[1])) / 2
    ax, ay, bx, by, cx, cy = a[0] - ox, a[1] - oy, b[0] - ox, b[1] - oy, c[0] - ox, c[1] - oy
    d = (ax * (by - cy) + bx * (cy - ay) + cx * (ay - by)) * 2
    if d == 0:
        pairs = [(a, b), (a, c), (b, c)]
        return _circle2(*max(pairs, key=lambda pq: math.hypot(pq[0][0] - pq[1][0], pq[0][1] - pq[1][1])))
    x = ox + ((ax * ax + ay * ay) * (by - cy) + (bx * bx + by * by) * (cy - ay) + (cx * cx + cy * cy) * (ay - by)) / d
    y = oy + ((ax * ax + ay * ay) * (cx - bx) + (bx * bx + by * by) * (ax - cx) + (cx * cx + cy * cy) * (bx - ax)) / d
    return x, y, max(math.hypot(x - p[0], y - p[1]) for p in (a, b, c))


def min_enclosing_circle(points: list[tuple[float, float]]) -> tuple[float, float, float]:
    """Smallest circle holding every point (Welzl, three nested loops; a fixed shuffle keeps the result deterministic)."""
    pts = list(points)
    random.Random(7).shuffle(pts)

    def outside(c, p):
        return math.hypot(p[0] - c[0], p[1] - c[1]) > c[2] * (1 + 1e-12) + 1e-9

    c = None
    for i, p in enumerate(pts):
        if c is not None and not outside(c, p):
            continue
        c = (p[0], p[1], 0.0)
        for j, q in enumerate(pts[:i]):
            if not outside(c, q):
                continue
            c = _circle2(p, q)
            for r in pts[:j]:
                if outside(c, r):
                    c = _circle3(p, q, r)
    return c


def glyph_geometry(glyph: Image.Image) -> dict:
    """Extent facts about the glyph in glyph-crop coordinates: hull of the visible pixels, centroid, minimum enclosing circle."""
    w, h = glyph.size
    alpha = glyph.getchannel("A")
    data = alpha.tobytes()
    pts: list[tuple[float, float]] = []
    for y in range(h):
        row = data[y * w : (y + 1) * w]
        xs = [x for x, v in enumerate(row) if v >= ALPHA_FLOOR]
        if xs:  # the corners of the leftmost and rightmost visible pixel of the row hold every hull vertex
            pts += [(xs[0], y), (xs[0], y + 1), (xs[-1] + 1, y), (xs[-1] + 1, y + 1)]
    hull = _hull([(float(x), float(y)) for x, y in pts])
    tot = sx = sy = 0.0
    for y in range(h):
        for x in range(w):
            v = data[y * w + x]
            if v:
                tot += v
                sx += v * (x + 0.5)
                sy += v * (y + 0.5)
    return {"hull": hull, "centroid": (sx / tot, sy / tot), "mec": min_enclosing_circle(hull), "size": (w, h)}


def launcher_anchor(geo: dict) -> tuple[tuple[float, float], dict]:
    """The glyph point that sits on the centre of the 108 dp layer.

    The glyph is slanted: its centre of mass is right of its bounding-box centre. Centring on the mass would push the lower-left
    tip outside the 66 dp safe zone at 60 dp, so the anchor starts at the minimum-enclosing-circle centre (the best fit for a
    circular mask) and moves toward the centroid by as much as the safe zone still allows.
    """
    gw, gh = geo["size"]
    px_per_dp = gh / LAUNCHER_GLYPH_DP
    limit = (SAFE_RADIUS_DP - SAFE_MARGIN_DP) * px_per_dp
    mx, my, mr = geo["mec"]
    tx, ty = geo["centroid"]
    if mr > limit:
        raise SystemExit(f"glyph needs a {mr / px_per_dp:.2f} dp radius, the safe zone allows {limit / px_per_dp:.2f} dp")

    def reach(t: float) -> float:
        cx, cy = mx + (tx - mx) * t, my + (ty - my) * t
        return max(math.hypot(px - cx, py - cy) for px, py in geo["hull"])

    lo, hi = 0.0, 1.0
    for _ in range(60):
        mid = (lo + hi) / 2
        if reach(mid) <= limit:
            lo = mid
        else:
            hi = mid
    cx, cy = mx + (tx - mx) * lo, my + (ty - my) * lo
    info = {
        "bbox_centre_px": [gw / 2, gh / 2],
        "centroid_px": [round(tx, 2), round(ty, 2)],
        "min_enclosing_circle_px": [round(mx, 2), round(my, 2), round(mr, 2)],
        "anchor_px": [round(cx, 2), round(cy, 2)],
        "toward_centroid": round(lo, 4),
        "max_radius_dp": round(reach(lo) / px_per_dp, 3),
        "safe_radius_dp": SAFE_RADIUS_DP,
    }
    return (cx, cy), info


def farthest_dp(layer: Image.Image, density: int, alpha_min: int) -> float:
    """Distance in dp from the layer centre to the outer corner of the farthest pixel whose alpha is at least `alpha_min`."""
    w, h = layer.size
    data = layer.getchannel("A").tobytes()
    cx = cy = w / 2
    far = 0.0
    for y in range(h):
        row = data[y * w : (y + 1) * w]
        xs = [x for x, v in enumerate(row) if v >= alpha_min]
        if xs:
            for x in (xs[0], xs[-1] + 1):
                far = max(far, math.hypot(x - cx, y - cy), math.hypot(x - cx, y + 1 - cy))
    return far / density


def assert_in_safe_zone(layer: Image.Image, density: int, label: str) -> tuple[float, float]:
    """Fail if a pixel at least ALPHA_FLOOR opaque leaves the 66 dp circle. Returns dp for alpha >= ALPHA_FLOOR and for alpha >= 1."""
    dp = farthest_dp(layer, density, ALPHA_FLOOR)
    if dp > SAFE_RADIUS_DP:
        raise SystemExit(f"{label}: farthest pixel with alpha >= {ALPHA_FLOOR} is {dp:.2f} dp out, the safe zone is {SAFE_RADIUS_DP} dp")
    return dp, farthest_dp(layer, density, 1)


# --- the monochrome (themed icon) layer ----------------------------------------------------------------------------------
def _luma(r: int, g: int, b: int) -> float:
    return (299 * r + 587 * g + 114 * b) / 1000


def _counter_region(px, w: int, h: int) -> set[tuple[int, int]]:
    """Flood-fill the violet counter (the small triangle inside the A) from its seed; it is boxed in by the navy outline."""
    sx, sy = COUNTER_SEED
    stack = [(sx, sy)]
    seen = {(sx, sy)}
    region = set()
    while stack:
        x, y = stack.pop()
        r, g, b, a = px[x, y]
        if a < 250 or _luma(r, g, b) <= 55 or r - b >= 40:  # not opaque, navy, or warm: the wall of the counter
            continue
        region.add((x, y))
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < w and 0 <= ny < h and (nx, ny) not in seen:
                seen.add((nx, ny))
                stack.append((nx, ny))
    if not COUNTER_AREA[0] <= len(region) <= COUNTER_AREA[1]:
        raise SystemExit(f"counter flood fill found {len(region)} px, expected {COUNTER_AREA}: update COUNTER_SEED for the new master")
    return region


def mono_alpha(glyph: Image.Image) -> Image.Image:
    """An 'L' alpha plane for the Android 13 themed icon, from the glyph's own pixels.

    The system tints the alpha with one colour, so the tone becomes opacity: the navy outline and the orange core are solid,
    the ice shards are a lighter tone (darker ice, a deeper tone), and the violet counter is cut out. The silhouette keeps the
    master's own antialiasing: it is multiplied in at the end.
    """
    w, h = glyph.size
    px = glyph.load()
    counter = _counter_region(px, w, h)
    ring = set(counter)
    for _ in range(2):  # the soft edge of the cut reaches two pixels into the navy frame
        ring |= {(x + dx, y + dy) for x, y in ring for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1))}
    out = Image.new("L", (w, h), 0)
    op = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            y_l = _luma(r, g, b)
            warm = clamp01((r - b - 40) / 60)
            ice = 1.0 - MONO_ICE_SPAN * clamp01((y_l - 40) / 200)  # lightest ice 0.35, mid-blue about 0.7, dark shadow near 1
            tone = warm + (1 - warm) * max(MONO_ICE_MIN, ice)
            if (x, y) in ring:
                cut = clamp01((y_l - 35) / 40)
                tone = min(tone, 1.0 - cut * cut * (3 - 2 * cut))
            op[x, y] = round(a * tone)
    return out


# --- output builders ------------------------------------------------------------------------------------------------------
class Output:
    def __init__(self, rel: str, data: bytes, pixels: tuple[int, int], role: str, params: dict | None = None):
        self.rel, self.data, self.pixels, self.role, self.params = rel, data, pixels, role, params or {}


def build_outputs(master: Image.Image) -> tuple[list[Output], dict]:
    glyph = load_glyph(master)
    gw, gh = glyph.size
    geo = glyph_geometry(glyph)
    anchor, anchor_info = launcher_anchor(geo)
    mono = mono_alpha(glyph)
    mono_glyph = Image.merge("RGBA", (Image.new("L", mono.size, 0),) * 3 + (mono,))
    outs: list[Output] = []
    reach: dict[str, float] = {}
    reach_any: dict[str, float] = {}

    for name, d in LAUNCHER_DENSITIES:
        canvas, height = LAUNCHER_LAYER_DP * d, LAUNCHER_GLYPH_DP * d
        fg = place(glyph, canvas, height, anchor)
        dp_visible, dp_any = assert_in_safe_zone(fg, d, f"foreground {name}")
        reach[name], reach_any[name] = round(dp_visible, 3), round(dp_any, 3)
        outs.append(Output(launcher_path("foreground", name), webp_bytes(fg, **LAUNCHER_WEBP), fg.size,
                           "adaptive-icon foreground layer (108 dp)", {"density": name, "glyph_px": height, **LAUNCHER_WEBP}))
    for name, d in LAUNCHER_DENSITIES:
        canvas, height = LAUNCHER_LAYER_DP * d, LAUNCHER_GLYPH_DP * d
        layer = place(mono_glyph, canvas, height, anchor)
        outs.append(Output(launcher_path("monochrome", name), webp_bytes(layer, **MONO_WEBP), layer.size,
                           "Android 13 themed-icon layer (single colour, alpha only)", {"density": name, "glyph_px": height, **MONO_WEBP}))

    mark = place(glyph, BRAND_MARK_PX, BRAND_MARK_GLYPH_PX)
    outs.append(Output(BRAND_MARK_REL, png_bytes(mark), mark.size,
                       "in-app mark, drawn on a solid plate", {"glyph_px": BRAND_MARK_GLYPH_PX, "lossless": True}))

    for size in WEB_MARK_SIZES:
        height = round(size * (1 - 2 * WEB_MARK_PAD))
        img = place(glyph, size, height)
        outs.append(Output(web_mark_path(size), png_bytes(img), img.size, "web BrandMark",
                           {"glyph_px": height, "padding": WEB_MARK_PAD}))

    icon_h = round(WEB_ICON_PX * (1 - 2 * WEB_ICON_PAD))
    icon = place(glyph, WEB_ICON_PX, icon_h)
    outs.append(Output(WEB_ICON_REL, png_bytes(icon), icon.size, "Next app icon", {"glyph_px": icon_h, "padding": WEB_ICON_PAD}))

    apple = Image.new("RGBA", (APPLE_ICON_PX, APPLE_ICON_PX), PAGE_PLATE + (255,))
    apple.alpha_composite(place(glyph, APPLE_ICON_PX, APPLE_GLYPH_PX))
    apple = apple.convert("RGB")  # opaque: iOS fills transparency with black
    outs.append(Output(APPLE_ICON_REL, png_bytes(apple), apple.size, "Next apple icon (opaque, on the calm page plate)",
                       {"glyph_px": APPLE_GLYPH_PX, "plate": "#%02X%02X%02X" % PAGE_PLATE}))

    frames = [place(glyph, c, g) for c, g in FAVICON_FRAMES]
    outs.append(Output(FAVICON_REL, ico_bytes(frames), (FAVICON_FRAMES[-1][0],) * 2, "Next favicon",
                       {"frames": [{"canvas_px": c, "glyph_px": g} for c, g in FAVICON_FRAMES]}))

    # Transparent, no plate: reports print on white paper and the navy outline carries the glyph (never put it on a dark band).
    report_h = round(REPORT_MARK_PX * (1 - 2 * REPORT_MARK_PAD))
    report = place(glyph, REPORT_MARK_PX, report_h)
    outs.append(Output(REPORT_MARK_REL, png_bytes(report), report.size, "printed/PDF report header mark (data: URI, white paper)",
                       {"glyph_px": report_h, "padding": REPORT_MARK_PAD}))

    if [o.rel for o in outs] != output_paths():
        raise SystemExit("build_outputs and output_paths() disagree: keep them in step")
    info = {
        "launcher": {"layer_dp": LAUNCHER_LAYER_DP, "glyph_dp": LAUNCHER_GLYPH_DP, "safe_radius_dp": SAFE_RADIUS_DP,
                     "safe_margin_dp": SAFE_MARGIN_DP, **anchor_info, "alpha_floor_of_255": ALPHA_FLOOR,
                     "farthest_visible_pixel_dp": reach, "farthest_any_alpha_pixel_dp": reach_any},
        "monochrome": {"ice_alpha_min": MONO_ICE_MIN, "ice_alpha_span": MONO_ICE_SPAN, "counter_seed_px": list(COUNTER_SEED)},
        "glyph_px": [gw, gh],
    }
    return outs, info


def describe(rel: str, data: bytes, pixels: tuple[int, int]) -> dict:
    return {"path": rel, "bytes": len(data), "sha256": sha256(data), "pixels": list(pixels)}


def build_manifest(master_bytes: bytes, master: Image.Image, outs: list[Output], info: dict) -> bytes:
    glyph_bbox = master.getchannel("A").getbbox()
    doc = {
        "schema": 1,
        "generator": "tools/brand/build_brand_assets.py",
        "pillow": PILLOW_VERSION,
        "note": "Generated. Do not edit. Regenerate with the generator; verify with --check.",
        "master": {**describe(MASTER_REL, master_bytes, master.size), "glyph_bbox": list(glyph_bbox),
                   "provenance": "owner-supplied 2026-10-18; ownership per the owner"},
        "parameters": info,
        "files": [{**describe(o.rel, o.data, o.pixels), "role": o.role, "params": o.params} for o in outs],
    }
    return (json.dumps(doc, indent=2, ensure_ascii=True) + "\n").encode("utf-8")


# --- modes ------------------------------------------------------------------------------------------------------------------
def write_if_changed(path: Path, data: bytes) -> bool:
    if path.exists() and path.read_bytes() == data:
        return False
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(data)
    return True


def one_line(exc: BaseException) -> str:
    """An exception as one short line (the check modes report a problem, they do not dump a traceback)."""
    if isinstance(exc, UnidentifiedImageError):  # Pillow's own text names a BytesIO address, which says nothing
        return "UnidentifiedImageError: not a recognised image format"
    return " ".join(f"{type(exc).__name__}: {exc}".split())[:200]


# What a corrupt or truncated file can raise when it is opened: PIL.UnidentifiedImageError is an OSError, a bad ICO directory is a
# struct.error or ValueError, an absurd size is a DecompressionBombError.
IMAGE_ERRORS = (OSError, ValueError, struct.error, Image.DecompressionBombError)


def load_master() -> tuple[bytes, Image.Image]:
    path = REPO / MASTER_REL
    if not path.is_file():
        raise SystemExit(f"FAIL missing master {MASTER_REL}")
    data = path.read_bytes()
    try:
        return data, Image.open(io.BytesIO(data)).convert("RGBA")
    except IMAGE_ERRORS as exc:
        raise SystemExit(f"FAIL master {MASTER_REL} is not a readable image: {one_line(exc)}")


def recorded_pillow() -> str | None:
    """The Pillow build named in the committed manifest, or None when there is none or it cannot be read."""
    try:
        value = json.loads((REPO / MANIFEST_REL).read_text(encoding="utf-8")).get("pillow")
    except (OSError, ValueError, AttributeError):
        return None
    return value if isinstance(value, str) else None


def cmd_generate() -> int:
    recorded = recorded_pillow()
    if recorded and recorded != PILLOW_VERSION:
        print(f"WARNING: Pillow {PILLOW_VERSION} is not the build that wrote the committed files ({recorded}); the encoded bytes "
              f"can differ, so outputs and their sha256 may all be rewritten. Commit that only on purpose.", file=sys.stderr)
    master_bytes, master = load_master()
    outs, info = build_outputs(master)
    changed = 0
    for o in outs:
        changed += write_if_changed(REPO / o.rel, o.data)
    manifest = build_manifest(master_bytes, master, outs, info)
    changed += write_if_changed(REPO / MANIFEST_REL, manifest)
    total = sum(len(o.data) for o in outs)
    print(f"{len(outs)} files, {total:,} bytes generated ({changed} written, {len(outs) + 1 - changed} already identical)")
    for o in outs:
        print(f"  {len(o.data):>8,}  {o.rel}")
    return 0


def manifest_entries(doc) -> list[dict]:
    """The master entry and every file entry of a parsed manifest, shape-checked; a ValueError says what is wrong."""
    if not isinstance(doc, dict) or "master" not in doc or not isinstance(doc.get("files"), list):
        raise ValueError("expected an object with 'master' and a 'files' list")
    entries = [doc["master"], *doc["files"]]
    for i, e in enumerate(entries):
        if not (isinstance(e, dict) and isinstance(e.get("path"), str) and isinstance(e.get("bytes"), int)
                and isinstance(e.get("sha256"), str) and isinstance(e.get("pixels"), list)):
            raise ValueError(f"entry {i} needs a string path and sha256, an integer bytes and a pixels list")
    return entries


def ico_frame_sizes(data: bytes) -> list[int]:
    """Edge length of every frame of an ICO: read from the directory, then confirmed by decoding the PNG the entry points at."""
    if len(data) < 6 or struct.unpack_from("<HH", data, 0) != (0, 1):
        raise ValueError("not an ICO file")
    sizes = []
    for i in range(struct.unpack_from("<H", data, 4)[0]):
        w, h, _colors, _reserved, _planes, _bpp, length, offset = struct.unpack_from("<BBBBHHII", data, 6 + 16 * i)
        w, h = w or 256, h or 256  # a stored 0 means 256
        frame = data[offset : offset + length]
        if len(frame) != length:
            raise ValueError(f"frame {i} runs past the end of the file")
        with Image.open(io.BytesIO(frame)) as im:
            if im.format != "PNG" or im.size != (w, h):
                raise ValueError(f"frame {i} is {im.format} {im.size[0]}x{im.size[1]}, the directory says {w}x{h}")
        sizes.append(w)
    return sorted(sizes)


def expected_ico_sizes(entry: dict) -> list[int]:
    """The frame edges the manifest promises for an ICO (its own params, so --check stays files-against-manifest)."""
    frames = (entry.get("params") or {}).get("frames")
    if isinstance(frames, list) and frames and all(isinstance(f, dict) and isinstance(f.get("canvas_px"), int) for f in frames):
        return sorted(f["canvas_px"] for f in frames)
    return sorted(c for c, _ in FAVICON_FRAMES)


def cmd_check() -> int:
    manifest_path = REPO / MANIFEST_REL
    if not manifest_path.is_file():
        print(f"FAIL missing {MANIFEST_REL}; run the generator", file=sys.stderr)
        return 1
    try:
        doc = json.loads(manifest_path.read_text(encoding="utf-8"))
        entries = manifest_entries(doc)
    except (OSError, ValueError) as exc:  # JSONDecodeError and UnicodeDecodeError are ValueErrors
        print(f"FAIL {MANIFEST_REL} is unreadable or malformed: {one_line(exc)}", file=sys.stderr)
        return 1
    bad = []
    for e in entries:
        p = REPO / e["path"]
        if not p.is_file():
            bad.append(f"missing {e['path']}")
            continue
        data = p.read_bytes()
        if len(data) != e["bytes"] or sha256(data) != e["sha256"]:
            bad.append(f"changed {e['path']} (sha256 {sha256(data)[:12]}, manifest {e['sha256'][:12]})")
            continue
        try:
            if e["path"].endswith(".ico"):
                sizes, want = ico_frame_sizes(data), expected_ico_sizes(e)
                if sizes != want:
                    bad.append(f"ico {e['path']} has frames {sizes}, manifest {want}")
                elif e["pixels"] != [max(sizes)] * 2:
                    bad.append(f"size {e['path']} is {[max(sizes)] * 2}, manifest {e['pixels']}")
            else:
                with Image.open(io.BytesIO(data)) as im:
                    if list(im.size) != e["pixels"]:
                        bad.append(f"size {e['path']} is {list(im.size)}, manifest {e['pixels']}")
        except IMAGE_ERRORS as exc:
            bad.append(f"unreadable image {e['path']}: {one_line(exc)}")
    # the manifest must list exactly the outputs this generator makes (a new output without a regenerate fails here)
    expected = set(output_paths())
    listed = {e["path"] for e in doc["files"]}
    for rel in sorted(expected - listed):
        bad.append(f"not in manifest {rel}")
    for rel in sorted(listed - expected):
        bad.append(f"manifest lists {rel} but the generator no longer makes it")
    if bad:
        print("FAIL brand assets do not match the manifest:", file=sys.stderr)
        for line in bad:
            print("  " + line, file=sys.stderr)
        return 1
    print(f"OK {len(entries)} files match {MANIFEST_REL}")
    return 0


def cmd_check_regen() -> int:
    master_bytes, master = load_master()
    outs, info = build_outputs(master)
    bad = []
    for o in outs:
        p = REPO / o.rel
        if not p.exists() or p.read_bytes() != o.data:
            bad.append(o.rel)
    p = REPO / MANIFEST_REL
    if not p.exists() or p.read_bytes() != build_manifest(master_bytes, master, outs, info):
        bad.append(MANIFEST_REL)
    if bad:
        print("FAIL regenerated bytes differ (different Pillow build?):", file=sys.stderr)
        for rel in bad:
            print("  " + rel, file=sys.stderr)
        return 1
    print(f"OK {len(outs) + 1} files are byte-identical to a fresh run (Pillow {PILLOW_VERSION})")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    mode = ap.add_mutually_exclusive_group()
    mode.add_argument("--check", action="store_true", help="verify files on disk against the manifest; write nothing")
    mode.add_argument("--check-regen", action="store_true", help="regenerate in memory and compare byte for byte; write nothing")
    args = ap.parse_args()
    if args.check:
        return cmd_check()
    if args.check_regen:
        return cmd_check_regen()
    return cmd_generate()


if __name__ == "__main__":
    sys.exit(main())
