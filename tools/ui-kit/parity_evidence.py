#!/usr/bin/env python3
"""Side-by-side parity evidence: the owner's screenshot (left) and the kit mock-up (right), both scaled to 1000 px high, 24 px white gap.

Run after `./gradlew :android:ui-screenshots:testDebugUnitTest --tests '*KitParityShot*'`; writes docs/evidence/ui-parity/kit-<name>.png.
"""
import pathlib
import sys

from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[2]
PAIRS = {
    "home": "home-1-tiles.webp",
    "stock": "stock.webp",
    "sale-select": "sale-select.png",
    "summary": "summary.png",
    "sales-submit": "sales-submit.png",
    "outlet-menu": "outlet-menu.png",
    "attendance": "attendance.png",
    "tasks": "tasks.png",
}
H, GAP = 1000, 24


def scaled(path: pathlib.Path) -> Image.Image:
    im = Image.open(path).convert("RGB")
    return im.resize((round(im.width * H / im.height), H), Image.LANCZOS)


def main(names: list[str]) -> None:
    for name in names or PAIRS:
        ref = scaled(ROOT / "docs/ui-reference/sr" / PAIRS[name])
        kit = scaled(ROOT / "android/ui-screenshots/build/kitshots" / f"parity_{name.replace('-', '_')}.png")
        out = Image.new("RGB", (ref.width + GAP + kit.width, H), "white")
        out.paste(ref, (0, 0))
        out.paste(kit, (ref.width + GAP, 0))
        out.save(ROOT / "docs/evidence/ui-parity" / f"kit-{name}.png", optimize=True)
        print(f"kit-{name}.png", out.size)


if __name__ == "__main__":
    main(sys.argv[1:])
