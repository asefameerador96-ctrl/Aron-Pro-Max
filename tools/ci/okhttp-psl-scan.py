#!/usr/bin/env python3
"""Keeps the APK-size exclusion of OkHttp's PublicSuffixDatabase.list safe (docs/status/apk-size-2026-11-18.md).

The sr, amo and tso apps drop that asset. OkHttp reads it only in HttpUrl.topPrivateDomain() and in Cookie.parse
(which runs only when a CookieJar other than NO_COOKIES is installed). If main sources ever use one of those, the
first call would throw at runtime. This gate fails on `topPrivateDomain`, `.cookieJar(` and `JavaNetCookieJar` under
android/**/src/main (Kotlin and Java). Usage: okhttp-psl-scan.py [repo-root]
"""
import re
import sys
from pathlib import Path

BANNED = re.compile(r'topPrivateDomain|\.cookieJar\(|JavaNetCookieJar')


def scan(root):
    hits = []
    for p in sorted(Path(root, 'android').rglob('*')):
        if p.suffix not in ('.kt', '.java') or not p.is_file():
            continue
        parts = p.relative_to(root).parts
        if 'src' not in parts or parts[parts.index('src') + 1:parts.index('src') + 2] != ('main',):
            continue
        for n, line in enumerate(p.read_text(encoding='utf-8', errors='replace').splitlines(), 1):
            m = BANNED.search(line)
            if m:
                hits.append('%s:%d: %s' % (p.relative_to(root), n, m.group()))
    return hits


if __name__ == '__main__':
    hits = scan(sys.argv[1] if len(sys.argv) > 1 else '.')
    if hits:
        print('FAIL: OkHttp public-suffix users in main sources (the asset is not packaged in sr/amo/tso):')
        print('\n'.join(hits))
        sys.exit(1)
    print('okhttp public-suffix scan: clean')
