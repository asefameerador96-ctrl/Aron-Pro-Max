#!/usr/bin/env python3
"""Noto Sans Bengali SemiBold for the parity kit (Vision QA 2026-10-19: bar titles and tile labels are SemiBold, not Bold).

Instantiates wght 600 / wdth 100 from the OFL variable Noto Sans Bengali (google/fonts ofl/notosansbengali) and subsets it to exactly
the characters of the bundled regular file, Latin included. The other bundled weights are NOT touched: core-printing rasterises the
memo and the slips with them, and the printed format must not change (removing their Latin glyphs moved the print goldens).

Usage: python3 tools/ui-kit/aron_fonts.py <path to NotoSansBengali[wdth,wght].ttf>   (needs: pip install fonttools)
"""
import pathlib
import sys

from fontTools import subset
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

FONTS = pathlib.Path(__file__).resolve().parents[2] / "android/core-ui/src/main/res/font"


def main(variable: str) -> None:
    keep = set(TTFont(FONTS / "noto_sans_bengali_regular.ttf").getBestCmap())
    semi = instancer.instantiateVariableFont(TTFont(variable), {"wght": 600, "wdth": 100})
    opts = subset.Options()
    opts.layout_features = ["*"]
    opts.name_IDs = ["*"]
    opts.name_languages = ["*"]
    opts.notdef_outline = True
    opts.hinting = True
    sub = subset.Subsetter(opts)
    sub.populate(unicodes=sorted(keep))
    sub.subset(semi)
    out = FONTS / "noto_sans_bengali_semibold.ttf"
    semi.save(out)
    print(out.name, out.stat().st_size, "bytes", len(TTFont(out).getBestCmap()), "chars")


if __name__ == "__main__":
    main(sys.argv[1])
