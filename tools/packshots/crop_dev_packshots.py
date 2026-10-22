#!/usr/bin/env python3
"""Crops the DEV-only pack pictures out of the owner's reference screenshot docs/ui-reference/sr/stock.webp.

Output: android/dev-packshots/dev-packshots/<art>.webp (bundled into an APK only when -Paron.devPackshots=true, which
only the dev-origin CI build sets). Three arts are visible in the screenshot (Maxim Regular, Bolt, Wild Berry); the
mapping from dev SKU codes to arts is android/dev-packshots/dev-packshots/index.txt. The real pack art comes from AKTCL
through the admin portal (docs/runbooks/sku-pack-pictures.md); these crops are a stand-in for the test account only.

Repairs: the purple packs badge of the screenshot overlaps each pack's bottom-right corner; that corner is refilled with
the mirrored left edge of the same pack (the pictorial warning and its black caption strip). The Wild Berry pack is cut
off by the totals panel below its top 118 rows; its lower part (the warning picture, the same on every Maxim pack) is
taken from the Regular pack.

Run: python3 -I tools/packshots/crop_dev_packshots.py  (Pillow; deterministic output, re-run gives the same bytes).
"""
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SRC = os.path.join(ROOT, "docs", "ui-reference", "sr", "stock.webp")
OUT = os.path.join(ROOT, "android", "dev-packshots", "dev-packshots")

X0, X1 = 45, 191          # pack left and right edge (px in stock.webp)
H = 237                   # pack height
TOPS = {"maxim-regular": 271, "maxim-bolt": 810, "maxim-wildberry": 1349}
BADGE_X, BADGE_Y = 177 - X0, 442  # the badge covers x >= 177 and y >= 442 on the first pack (the same offset on each)
GREEN_VISIBLE = 118       # rows of the Wild Berry pack above the totals panel
MAX_BYTES = 12 * 1024


def pack(im, top):
    p = im.crop((X0, top, X1, top + H)).copy()
    w = p.width
    by = BADGE_Y - TOPS["maxim-regular"]  # same offset on every pack
    px = p.load()
    for y in range(by, H):
        for x in range(BADGE_X, w):
            px[x, y] = px[w - 1 - x, y]
    return p


def main():
    im = Image.open(SRC).convert("RGB")
    arts = {name: pack(im, top) for name, top in TOPS.items()}
    green = arts["maxim-wildberry"]
    green.paste(arts["maxim-regular"].crop((0, GREEN_VISIBLE, green.width, H)), (0, GREEN_VISIBLE))
    os.makedirs(OUT, exist_ok=True)
    for name, p in arts.items():
        path = os.path.join(OUT, name + ".webp")
        p.save(path, "WEBP", quality=82, method=6)
        size = os.path.getsize(path)
        print(f"{name}.webp {p.width}x{p.height} {size} bytes")
        if size > MAX_BYTES:
            sys.exit(f"{name}.webp is {size} bytes, over the {MAX_BYTES} byte budget")


if __name__ == "__main__":
    main()
