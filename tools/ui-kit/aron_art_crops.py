# Usage: python3 tools/ui-kit/aron_art_crops.py android/core-ui/src/main/res/drawable-nodpi <preview-dir>  (crops the owner art from docs/ui-reference/sr; sales_journey re-encoded at quality 72 by hand)
import sys, os
from PIL import Image
out = sys.argv[1]; prev = sys.argv[2]
def crop(src, box, bg, name, label_frac):
    im = Image.open(src).convert('RGBA')
    x0, y0, x1, y1 = box
    region = im.crop((x0 + 10, y0 + 10, x1 - 10, y0 + int((y1 - y0) * label_frac)))
    px = region.load(); w, h = region.size
    xs, ys = [], []
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2]) > 40:
                xs.append(x); ys.append(y)
    bb = (min(xs), min(ys), max(xs) + 1, max(ys) + 1)
    icon = region.crop(bb)
    # background to transparent (soft edge by distance)
    data = []
    for (r, g, b, a) in icon.getdata():
        d = abs(r - bg[0]) + abs(g - bg[1]) + abs(b - bg[2])
        data.append((r, g, b, 0 if d < 12 else (255 if d > 60 else int(255 * (d - 12) / 48))))
    icon.putdata(data)
    side = max(icon.size)
    sq = Image.new('RGBA', (side, side), (0, 0, 0, 0)); sq.paste(icon, ((side - icon.width) // 2, (side - icon.height) // 2))
    sq = sq.resize((168, 168), Image.LANCZOS) if side > 168 else sq
    p = os.path.join(out, f'aron_art_{name}.webp'); sq.save(p, 'WEBP', quality=85, method=6)
    sq.save(os.path.join(prev, f'{name}.png'))
    print(name, bb, side, os.path.getsize(p))
home = 'docs/ui-reference/sr/home-1-tiles.webp'
cols = [(20, 280), (295, 555), (569, 829), (843, 1103)]
rows = [(313, 614), (637, 938), (961, 1262), (1285, 1586)]
names = [['attendance', 'stock', 'sale', 'memo'], ['summary', 'sales_submit', 'outlet', 'tutorial'], ['task_delegation', None, None, 'photo_capture'], ['sales_journey', 'kpi', None, None]]
for ri, (ya, yb) in enumerate(rows):
    for ci, (xa, xb) in enumerate(cols):
        n = names[ri][ci]
        if n: crop(home, (xa, ya, xb, yb), (255, 255, 255), n, 0.66)
om = 'docs/ui-reference/sr/outlet-menu.png'
for n, box in [('outlet_new', (52, 142, 402, 542)), ('outlet_close', (452, 142, 802, 542)), ('outlet_edit', (852, 142, 1202, 542)), ('cluster', (52, 564, 402, 964))]:
    crop(om, box, (245, 245, 245), n, 0.74)
