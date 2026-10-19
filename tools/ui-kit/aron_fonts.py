#!/usr/bin/env python3
"""Bengali fonts of the parity kit (Vision QA 2026-10-19: Roboto for Latin glyphs, Noto Sans Bengali SemiBold for bar titles).

1. Instantiates SemiBold (wght 600, wdth 100) from the OFL variable Noto Sans Bengali (google/fonts ofl/notosansbengali) and subsets it
   to exactly the characters of the bundled regular file.
2. Removes the printable ASCII glyphs (U+0021..U+007E) from every bundled Bengali weight, so Android draws Latin letters, ASCII digits
   and ASCII punctuation inside Bangla text with the system Roboto through its normal per-glyph font fallback (as the reference app).
   The space and the Bengali block stay.

Usage: python3 tools/ui-kit/aron_fonts.py <path to NotoSansBengali[wdth,wght].ttf>   (needs: pip install fonttools)
Idempotent: running it again on already-stripped files changes nothing.
"""
import pathlib
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

FONTS = pathlib.Path(__file__).resolve().parents[2] / "android/core-ui/src/main/res/font"
ASCII = set(range(0x21, 0x7F))


def strip(font: TTFont, keep: set[int]) -> None:
    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.notdef_outline = True
    opts.glyph_names = False
    opts.hinting = True
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=sorted(keep))
    sub.subset(font)


def main(variable: str) -> None:
    regular = TTFont(FONTS / "noto_sans_bengali_regular.ttf")
    keep = set(regular.getBestCmap()) - ASCII
    semi = instancer.instantiateVariableFont(TTFont(variable), {"wght": 600, "wdth": 100})
    strip(semi, keep)
    semi.save(FONTS / "noto_sans_bengali_semibold.ttf")
    for weight in ("regular", "medium", "bold"):
        path = FONTS / f"noto_sans_bengali_{weight}.ttf"
        font = TTFont(path)
        strip(font, set(font.getBestCmap()) - ASCII)
        font.save(path)
    for path in sorted(FONTS.glob("noto_sans_bengali_*.ttf")):
        cmap = TTFont(path).getBestCmap()
        print(path.name, path.stat().st_size, "bytes", len(cmap), "chars", "latin A" if ord("A") in cmap else "no latin")


if __name__ == "__main__":
    main(sys.argv[1])
