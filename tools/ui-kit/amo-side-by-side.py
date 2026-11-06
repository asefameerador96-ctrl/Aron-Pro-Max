#!/usr/bin/env python3
"""Side-by-side parity PNGs for the AMO app (amo-shell A1; PARITY-SPEC "side-by-side PNG rule").

Left: the owner's reference screenshot (docs/ui-reference/amo/NN-*.jpg); right: our golden
(android/ui-screenshots/src/test/screenshots/<name>.png); both scaled to the same height; written to
docs/evidence/ui-parity/amo-NN.png. Run from the repository root after recording the goldens:
    python3 tools/ui-kit/amo-side-by-side.py
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
REF = ROOT / "docs" / "ui-reference" / "amo"
OURS = ROOT / "android" / "ui-screenshots" / "src" / "test" / "screenshots"
OUT = ROOT / "docs" / "evidence" / "ui-parity"

# screenshot number -> (reference file prefix, our golden)
PAIRS = {
    "03": ("03-login_and_location_permission", "amo_login_bn"),
    "08": ("08-dashboard_menu", "amo_home_bn_light"),
    "09": ("09-target_cards_bottom_of_the_dashboard", "amo_home_targets_bn"),
    "14": ("14-attendance_screen", "amo_attendance_before_bn"),
    "15": ("15-check_in", "amo_attendance_checkin_sheet_bn"),
    "16": ("16-check_out", "amo_attendance_done_bn"),
    "41": ("41-stock_issue", "amo_stock_notconnected_bn"),
    "75": ("75-settings", "amo_settings_bn"),
    "77": ("77-log_out", "amo_settings_logout_confirm_bn"),
}


def fit_height(img: Image.Image, h: int) -> Image.Image:
    return img.resize((max(1, round(img.width * h / img.height)), h), Image.LANCZOS)


def main() -> int:
    OUT.mkdir(parents=True, exist_ok=True)
    for num, (ref, ours) in sorted(PAIRS.items()):
        left = Image.open(REF / f"{ref}.jpg").convert("RGB")
        right = Image.open(OURS / f"{ours}.png").convert("RGB")
        h = min(left.height, 1600)
        l, r = fit_height(left, h), fit_height(right, h)
        sheet = Image.new("RGB", (l.width + r.width + 24, h), (255, 255, 255))
        sheet.paste(l, (0, 0))
        sheet.paste(r, (l.width + 24, 0))
        sheet.save(OUT / f"amo-{num}.png", optimize=True)
        print(f"amo-{num}.png  ref={ref}.jpg  ours={ours}.png  {sheet.width}x{sheet.height}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
