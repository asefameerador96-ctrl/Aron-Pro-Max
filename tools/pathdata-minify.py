#!/usr/bin/env python3
"""Lossless minifier and equality checker for Android vector android:pathData.

  pathdata-minify.py apply FILE...        rewrite pathData in place (text only, no number changes)
  pathdata-minify.py check OLD NEW        parse both numerically; exit 1 on any difference
  pathdata-minify.py check-git REV [PATH] compare every changed xml between REV and the working tree
  pathdata-minify.py self-test

Only the text form changes: leading zeros dropped (0.5 -> .5), trailing zeros dropped, repeated command
letters dropped (except M/m, where a repeat means lineto), minimal separators. No value is rounded.
"""
import re, subprocess, sys
from decimal import Decimal

NUM = re.compile(r'[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?')
ARGS = {'m': 2, 'l': 2, 'h': 1, 'v': 1, 'c': 6, 's': 4, 'q': 4, 't': 2, 'a': 7, 'z': 0}
PD = re.compile(r'(android:pathData=")([^"]*)(")')


def parse(d):
    """-> list of (cmd, [Decimal,...]) with implicit repeats expanded (M repeat -> L, m -> l)."""
    out, i, n = [], 0, len(d)
    cmd = None
    while i < n:
        c = d[i]
        if c in ' ,\t\r\n':
            i += 1
        elif c.isalpha():
            cmd = c
            i += 1
            if c in 'zZ':
                out.append((c, []))
        else:
            if cmd is None:
                raise ValueError('number before command: ' + d[:40])
            k = ARGS[cmd.lower()]
            args = []
            for j in range(k):
                while i < n and d[i] in ' ,\t\r\n':
                    i += 1
                if cmd.lower() == 'a' and j in (3, 4):  # flags are single chars, may be unseparated
                    m = re.match(r'[01]', d[i:])
                    args.append(Decimal(m.group()))
                    i += 1
                else:
                    m = NUM.match(d, i)
                    if not m:
                        raise ValueError('bad number at %d in %s' % (i, d[:60]))
                    args.append(Decimal(m.group()))
                    i = m.end()
            out.append((cmd, args))
            if cmd == 'M':
                cmd = 'L'
            elif cmd == 'm':
                cmd = 'l'
    return out


def fmt(x, raw):
    s = raw
    m = re.match(r'([+-]?)(\d*)(?:\.(\d*))?(.*)$', s)
    sign, ip, fp, rest = m.group(1), m.group(2), m.group(3) or '', m.group(4)
    if sign == '+':
        sign = ''
    fp = fp.rstrip('0')
    ip = ip.lstrip('0') if fp else (ip.lstrip('0') or '0')
    return sign + ip + ('.' + fp if fp else '') + rest


def minify(d):
    toks = []  # (cmd or None, [raw strings])
    i, n, cmd = 0, len(d), None
    cur = None
    while i < n:
        c = d[i]
        if c in ' ,\t\r\n':
            i += 1
        elif c.isalpha():
            cmd = c
            i += 1
            toks.append((c, []))
            cur = toks[-1]
            if c in 'zZ':
                cur = None
        else:
            k = ARGS[cmd.lower()]
            raws = []
            for j in range(k):
                while d[i] in ' ,\t\r\n':
                    i += 1
                if cmd.lower() == 'a' and j in (3, 4):
                    raws.append(d[i]); i += 1
                else:
                    m = NUM.match(d, i)
                    raws.append(fmt(None, m.group())); i = m.end()
            toks.append((None, raws, cmd))
            if cmd == 'M':
                cmd = 'L'
            elif cmd == 'm':
                cmd = 'l'
    out, prev_cmd, last = [], None, ''
    for t in toks:
        if t[0] is not None and len(t) == 2:
            out.append(t[0]); last = t[0]; prev_cmd = t[0]
            continue
        raws, c = t[1], t[2]
        # first group after an explicit letter keeps no letter; later groups of the same
        # command are separated by a sep only (implicit repeat), which parse() expands
        for r in raws:
            if last and last[-1].isalpha():
                out.append(r)
            elif r.startswith('-') or (r.startswith('.') and '.' in last):
                out.append(r)
            else:
                out.append(' ' + r)
            last = r
    return ''.join(out)


def same(a, b):
    return parse(a) == parse(b)


def paths(text):
    return [m.group(2) for m in PD.finditer(text)]


def check_texts(old, new, name=''):
    po, pn = paths(old), paths(new)
    if len(po) != len(pn):
        return ['%s: pathData count %d != %d' % (name, len(po), len(pn))]
    return ['%s: path %d differs' % (name, i) for i, (a, b) in enumerate(zip(po, pn)) if not same(a, b)]


def mask(t):
    return PD.sub(r'\1\3', t)


def apply(f):
    t = open(f, encoding='utf-8').read()
    nt = PD.sub(lambda m: m.group(1) + minify(m.group(2)) + m.group(3), t)
    errs = check_texts(t, nt, f)
    assert not errs and mask(t) == mask(nt), errs
    if nt != t:
        open(f, 'w', encoding='utf-8', newline='').write(nt)
        return len(t) - len(nt)
    return None


def self_test():
    cases = ['M0.50,1.0L2,3 4,5z', 'M24,2a22,22 0 1,0 0.01,0z', 'm1,2 3,4 5,6', 'M1,2 M3,4', 'M10-5.5.5-.5H1.5V.25',
             'c0.1,0.2 0.3,0.4 0.5,0.6 0.1,0.2 0.3,0.4 0.5,0.6', 'a1,1 0 10.5,.5', 'M1e2,2E-1L+3,-0']
    for c in cases:
        m = minify(c)
        assert same(c, m), (c, m)
        assert len(m) <= len(c) or 'a' in c, (c, m)
    assert minify('M0.50,1.0L2,3 4,5z') == 'M.5 1L2 3 4 5z', minify('M0.50,1.0L2,3 4,5z')
    assert not same('M1,2L3,4', 'M1,2L3,5')
    assert not same('M1,2 3,4', 'M1,2M3,4')       # implicit lineto vs second moveto must differ
    assert check_texts('android:pathData="M1,2"', 'android:pathData="M1,3"')
    assert not check_texts('android:pathData="M1.0,2"', 'android:pathData="M1 2"')
    print('self-test ok')


def main(a):
    if not a or a[0] == 'self-test':
        return self_test()
    if a[0] == 'apply':
        saved = changed = 0
        for f in a[1:]:
            s = apply(f)
            if s is not None:
                changed += 1; saved += s
        print('files changed: %d, bytes saved: %d' % (changed, saved))
    elif a[0] == 'check':
        errs = check_texts(open(a[1]).read(), open(a[2]).read(), a[2])
        print('\n'.join(errs) or '0 differences'); sys.exit(1 if errs else 0)
    elif a[0] == 'check-git':
        rev, sub = a[1], (a[2] if len(a) > 2 else 'android')
        files = subprocess.check_output(['git', 'diff', '--name-only', '--diff-filter=M', rev, '--', sub]).decode().split()
        files = [f for f in files if f.endswith('.xml')]
        errs, n, cnt = [], 0, 0
        for f in files:
            old = subprocess.check_output(['git', 'show', '%s:%s' % (rev, f)]).decode()
            new = open(f, encoding='utf-8').read()
            if not paths(old):
                continue
            n += 1; cnt += len(paths(new))
            errs += check_texts(old, new, f)
            if mask(old) != mask(new):
                errs.append(f + ': non-pathData text differs')
        print('files %d, pathData strings %d, differences %d' % (n, cnt, len(errs)))
        print('\n'.join(errs)); sys.exit(1 if errs else 0)


if __name__ == '__main__':
    main(sys.argv[1:])
