#!/usr/bin/env python3
"""Keeps the APK-size exclusion of OkHttp's PublicSuffixDatabase.list safe (docs/status/apk-size-2026-11-18.md).

The sr, amo and tso apps drop that asset. OkHttp reads it only in HttpUrl.topPrivateDomain() and in Cookie.parse /
Cookie.parseAll (which run only when a CookieJar other than NO_COOKIES is installed, or when called directly). If
shipping sources ever use one of those, the first call would throw at runtime. This gate fails on topPrivateDomain,
any cookieJar( call (with or without receiver, with a space, split across lines), JavaNetCookieJar, Cookie.parse,
Cookie.parseAll and PublicSuffixDatabase in every Kotlin/Java source set that can ship: src/main, src/debug and
src/benchmark*. src/test and src/androidTest are not scanned. It also fails when fewer than MIN_FILES files were
scanned, so an empty or moved tree is never reported clean. Usage: okhttp-psl-scan.py [repo-root]
"""
import re
import sys
from pathlib import Path

MIN_FILES = 100
BANNED = re.compile(r'topPrivateDomain|\bcookieJar\s*\(|JavaNetCookieJar|\bCookie\s*\.\s*parse(?:All)?\b|PublicSuffixDatabase')


def shipping(parts):
    """True for android/<module>/src/<main|debug|benchmark*>/..."""
    if 'src' not in parts:
        return False
    i = parts.index('src')
    return i + 1 < len(parts) and (parts[i + 1] in ('main', 'debug') or parts[i + 1].startswith('benchmark'))


def scan(root):
    """-> (number of files scanned, list of hit strings)"""
    hits, files = [], 0
    for p in sorted(Path(root, 'android').rglob('*')):
        if p.suffix not in ('.kt', '.java') or not p.is_file():
            continue
        if not shipping(p.relative_to(root).parts):
            continue
        files += 1
        text = p.read_text(encoding='utf-8', errors='replace')
        for m in BANNED.finditer(text):
            hits.append('%s:%d: %s' % (p.relative_to(root), text.count('\n', 0, m.start()) + 1, ' '.join(m.group().split())))
    return files, hits


if __name__ == '__main__':
    min_files = int(sys.argv[2]) if len(sys.argv) > 2 else MIN_FILES
    n, hits = scan(sys.argv[1] if len(sys.argv) > 1 else '.')
    if n < min_files:
        print('FAIL: only %d Kotlin/Java files scanned under android/**/src/(main|debug|benchmark*), expected at least %d' % (n, min_files))
        sys.exit(1)
    if hits:
        print('FAIL: OkHttp public-suffix users in shipping sources (the asset is not packaged in sr/amo/tso):')
        print('\n'.join(hits))
        sys.exit(1)
    print('okhttp public-suffix scan: clean (%d files)' % n)
