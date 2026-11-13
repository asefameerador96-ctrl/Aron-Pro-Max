#!/usr/bin/env python3
"""Trace AMO artwork crops (owner screenshots, docs/ui-reference/amo) into small vector drawables.
Usage: trace-amo-icons.py   (edit JOBS). Per job it tries 12, 10, 8, 7, 6 colours and keeps the richest result within MAX_KB and MAX_PATHS."""
import vtracer, re, io, os, sys
from PIL import Image, ImageFilter
MAX_KB, MAX_PATHS = 14.0, 80
TMP = os.environ.get('TRACE_TMP', '/tmp/trace')
os.makedirs(TMP, exist_ok=True)

def shift(d, tx, ty):
    nums = re.findall(r'-?\d+(?:\.\d+)?', d); cmds = re.findall(r'[MCLZ]', d)
    out = []; i = 0
    for tok in re.findall(r'[MCLZ]|-?\d+(?:\.\d+)?', d):
        if tok in 'MCLZ': out.append(tok); continue
        out.append(tok)
    # absolute coords alternate x,y after each command letter
    res = []; k = 0
    for tok in re.findall(r'[MCLZ]|-?\d+(?:\.\d+)?', d):
        if tok in ('M', 'C', 'L', 'Z'): res.append(tok); k = 0; continue
        v = float(tok) + (tx if k % 2 == 0 else ty); k += 1
        res.append(str(int(round(v))))
    return ' '.join(res)

def trace(im, name, dp, S, K, speckle, out_dir):
    w, h = im.size; W, H = w * S, h * S
    c = im.resize((W, H), Image.LANCZOS).filter(ImageFilter.MedianFilter(3))
    q = c.quantize(K, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).convert('RGB'); p = f'{TMP}/{name}.png'; q.save(p)
    vtracer.convert_image_to_svg_py(p, f'{TMP}/{name}.svg', colormode='color', hierarchical='stacked', mode='spline', filter_speckle=speckle,
                                    color_precision=8, layer_difference=1, corner_threshold=60, length_threshold=10.0, splice_threshold=45, path_precision=0)
    s = open(f'{TMP}/{name}.svg').read()
    paths = re.findall(r'<path d="([^"]+)" fill="(#[0-9A-Fa-f]{6})" transform="translate\(([-\d.]+),([-\d.]+)\)"/>', s)
    out = [f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{dp}dp" android:height="{round(dp*H/W)}dp" android:viewportWidth="{W}" android:viewportHeight="{H}">']
    n = 0
    for d, col, tx, ty in paths:
        r, g, b = int(col[1:3], 16), int(col[3:5], 16), int(col[5:7], 16)
        if r > 238 and g > 236 and b > 238: continue
        out.append(f'<path android:fillColor="{col}" android:pathData="{shift(d, float(tx), float(ty))}"/>'); n += 1
    out.append('</vector>'); x = '\n'.join(out)
    return x, n

def best(im, name, dp, S, out_dir):
    for K, sp in ((12, 16), (10, 20), (8, 24), (7, 28), (6, 32)):
        x, n = trace(im, name, dp, S, K, sp, out_dir)
        if len(x) / 1024 <= MAX_KB and n <= MAX_PATHS:
            break
    open(f'{out_dir}/{name}.xml', 'w').write(x)
    print(f'{name}: {len(x)/1024:.1f} KB, {n} paths, {K} colours')
    return len(x), n, K

if __name__ == '__main__':
    A = 'docs/ui-reference/amo/'
    exec(open(sys.argv[1]).read()) if len(sys.argv) > 1 else None
