#!/usr/bin/env python3
"""AMO side-by-sides: the owner's screenshot (left) and the Roborazzi golden(s) of our screen (right), scaled to 1000 px high, 24 px gap.

Goldens are android/ui-screenshots/src/test/screenshots/<name>.png (recorded by AmoCallsScreenshotTests); the reference is
docs/ui-reference/amo/<row>-*.jpg. Writes docs/evidence/ui-parity/amo-<row>.png. Run: python3 tools/ui-kit/amo_parity_evidence.py [row ...]
"""
import glob
import pathlib
import sys

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]
PAIRS = {
    "24": ["amo_sale_entry_bn"],
    "25": ["amo_review_bn"],
    "42": ["amo_memo_menu_bn", "amo_memo_detail_due_bn"],
    "43": ["amo_memo_detail_due_bn"],
    "65": ["amo_sales_submit_bn"],
}
H, GAP = 1000, 24


def scaled(path: pathlib.Path) -> Image.Image:
    im = Image.open(path).convert("RGB")
    return im.resize((round(im.width * H / im.height), H), Image.LANCZOS)


def main(rows: list[str]) -> None:
    for row in rows or PAIRS:
        ref = scaled(next(pathlib.Path(p) for p in glob.glob(str(ROOT / f"docs/ui-reference/amo/{row}-*.jpg"))))
        ours = [scaled(ROOT / "android/ui-screenshots/src/test/screenshots" / f"{n}.png") for n in PAIRS[row]]
        out = Image.new("RGB", (ref.width + sum(GAP + o.width for o in ours), H), "white")
        out.paste(ref, (0, 0))
        x = ref.width
        for o in ours:
            x += GAP
            out.paste(o, (x, 0))
            x += o.width
        out.save(ROOT / "docs/evidence/ui-parity" / f"amo-{row}.png", optimize=True)
        print(f"amo-{row}.png", out.size)


if __name__ == "__main__":
    main(sys.argv[1:])
