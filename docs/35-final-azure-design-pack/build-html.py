#!/usr/bin/env python3
"""Build the single-page HTML version of the owner pack from the four markdown files in this folder.

    python3 -I docs/35-final-azure-design-pack/build-html.py <output.html>

The markdown subset used by the pack is converted directly (headings, paragraphs, pipe tables with
alignment, bullet and numbered lists, fenced code and ```mermaid blocks, bold, inline code, links).
Mermaid blocks become <pre class="mermaid"> which the artifact viewer renders natively.
"""
import html
import pathlib
import re
import sys

HERE = pathlib.Path(__file__).resolve().parent
DOCS = [("summary", "Summary", "README.md"), ("hld", "High-level design", "01-high-level-design.md"),
        ("lld", "Low-level design", "02-low-level-design.md"), ("cost", "Resources and cost", "03-resource-plan-and-cost.md")]
ANCHORS = {"README.md": "#summary", "01-high-level-design.md": "#hld", "02-low-level-design.md": "#lld",
           "03-resource-plan-and-cost.md": "#cost", "cost-model.py": "#cost"}


def inline(s):
    s = html.escape(s, quote=False)
    s = re.sub(r"`([^`]+)`", r"<code>\1</code>", s)
    s = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", s)

    def link(m):
        text, href = m.group(1), m.group(2)
        href = ANCHORS.get(href, href)
        return f'<a href="{href}">{text}</a>'
    s = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", link, s)
    return s


def table(lines):
    rows = [[c.strip() for c in ln.strip().strip("|").split("|")] for ln in lines]
    head, sep, body = rows[0], rows[1], rows[2:]
    align = ["right" if c.endswith(":") and c.startswith("-") else ("center" if c.startswith(":") and c.endswith(":") else "left") for c in sep]
    out = ['<div class="tbl"><table>', "<thead><tr>"]
    for i, c in enumerate(head):
        out.append(f'<th style="text-align:{align[i] if i < len(align) else "left"}">{inline(c)}</th>')
    out.append("</tr></thead><tbody>")
    for r in body:
        out.append("<tr>")
        for i, c in enumerate(r):
            a = align[i] if i < len(align) else "left"
            out.append(f'<td style="text-align:{a}">{inline(c)}</td>')
        out.append("</tr>")
    out.append("</tbody></table></div>")
    return "\n".join(out)


def convert(md, section_id):
    lines = md.split("\n")
    out, i, n = [], 0, len(lines)
    hcount = 0
    while i < n:
        ln = lines[i]
        if ln.startswith("```"):
            lang = ln[3:].strip()
            j = i + 1
            buf = []
            while j < n and not lines[j].startswith("```"):
                buf.append(lines[j]); j += 1
            body = "\n".join(buf)
            if lang == "mermaid":
                out.append(f'<div class="dia"><pre class="mermaid">\n{html.escape(body, quote=False)}\n</pre></div>')
            else:
                out.append(f"<pre><code>{html.escape(body, quote=False)}</code></pre>")
            i = j + 1
            continue
        if ln.startswith("|"):
            j = i
            buf = []
            while j < n and lines[j].startswith("|"):
                buf.append(lines[j]); j += 1
            out.append(table(buf)); i = j
            continue
        m = re.match(r"^(#{1,3})\s+(.*)$", ln)
        if m:
            level = len(m.group(1)); text = m.group(2)
            hcount += 1
            hid = f"{section_id}-{hcount}"
            if level == 1:
                out.append(f'<h1 id="{hid}">{inline(text)}</h1>')
            else:
                out.append(f'<h{level} id="{hid}">{inline(text)}</h{level}>')
            i += 1
            continue
        if ln.strip() == "---":
            out.append("<hr>"); i += 1; continue
        if re.match(r"^\s*[-*]\s+", ln):
            j = i; items = []
            while j < n and re.match(r"^\s*[-*]\s+", lines[j]):
                items.append(re.sub(r"^\s*[-*]\s+", "", lines[j])); j += 1
            out.append("<ul>" + "".join(f"<li>{inline(x)}</li>" for x in items) + "</ul>"); i = j
            continue
        if re.match(r"^\s*\d+\.\s+", ln):
            j = i; items = []
            while j < n and re.match(r"^\s*\d+\.\s+", lines[j]):
                items.append(re.sub(r"^\s*\d+\.\s+", "", lines[j])); j += 1
            out.append("<ol>" + "".join(f"<li>{inline(x)}</li>" for x in items) + "</ol>"); i = j
            continue
        if ln.strip() == "":
            i += 1; continue
        j = i; buf = []
        while j < n and lines[j].strip() != "" and not lines[j].startswith(("|", "#", "```")) and not re.match(r"^\s*([-*]|\d+\.)\s+", lines[j]):
            buf.append(lines[j].strip()); j += 1
        out.append(f"<p>{inline(' '.join(buf))}</p>"); i = j
    return "\n".join(out)


STYLE = """
<style>
/* Layout: one long document with a sticky section nav; wide tables scroll in their own box. */
:root{--bg:#f7f7f9;--surface:#ffffff;--fg:#1c1c24;--muted:#5b5b6b;--line:#dcdce4;--accent:#9b1c3c;--accent-soft:#f3e3e8;--code-bg:#ededf2;--font-body:"IBM Plex Sans",system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;--font-display:"IBM Plex Sans Condensed","IBM Plex Sans",system-ui,sans-serif;--font-mono:"IBM Plex Mono",ui-monospace,SFMono-Regular,Menlo,monospace}
@media (prefers-color-scheme: dark){:root:not([data-theme="light"]){--bg:#15151b;--surface:#1d1d25;--fg:#ececf1;--muted:#a3a3b2;--line:#34343f;--accent:#e86a8a;--accent-soft:#3a1d27;--code-bg:#262630;color-scheme:dark}}
:root[data-theme="dark"]{--bg:#15151b;--surface:#1d1d25;--fg:#ececf1;--muted:#a3a3b2;--line:#34343f;--accent:#e86a8a;--accent-soft:#3a1d27;--code-bg:#262630;color-scheme:dark}
body{background:var(--bg);color:var(--fg);font-family:var(--font-body);font-size:15px;line-height:1.55;margin:0}
body::before{content:"";position:fixed;inset:0 0 auto;height:env(safe-area-inset-top,0px);z-index:10;background:var(--surface)}
.wrap{max-width:1180px;margin:0 auto;padding-block:0 64px;padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px))}
nav.top{position:sticky;top:env(safe-area-inset-top,0px);z-index:9;background:var(--surface);border-bottom:1px solid var(--line)}
nav.top .in{max-width:1180px;margin:0 auto;display:flex;flex-wrap:wrap;gap:4px 18px;align-items:baseline;padding-block:10px;padding-left:max(16px,env(safe-area-inset-left,0px));padding-right:max(16px,env(safe-area-inset-right,0px))}
nav.top .brand{font-family:var(--font-display);font-weight:600;font-size:17px;letter-spacing:.01em;margin-right:auto}
nav.top a{color:var(--muted);text-decoration:none;font-size:14px;padding:4px 0;border-bottom:2px solid transparent}
nav.top a:hover,nav.top a:focus-visible{color:var(--accent);border-bottom-color:var(--accent);outline:none}
header.hero{padding-block:40px 24px;border-bottom:1px solid var(--line)}
header.hero .eyebrow{font-family:var(--font-mono);font-size:12px;letter-spacing:.08em;text-transform:uppercase;color:var(--accent)}
header.hero h1{font-family:var(--font-display);font-weight:600;font-size:clamp(28px,4vw,44px);line-height:1.1;margin:8px 0 12px;text-wrap:balance}
header.hero p{max-width:72ch;color:var(--muted);margin:0}
.kpis{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:12px;margin:24px 0 0}
.kpi{background:var(--surface);border:1px solid var(--line);border-radius:6px;padding:14px 16px;min-width:0}
.kpi .l{font-size:12px;color:var(--muted);text-transform:uppercase;letter-spacing:.06em}
.kpi .v{font-family:var(--font-display);font-size:26px;font-weight:600;font-variant-numeric:tabular-nums;margin-top:4px}
.kpi .s{font-size:13px;color:var(--muted)}
section.doc{padding-block:36px 8px;border-bottom:1px solid var(--line)}
section.doc:last-child{border-bottom:0}
h1,h2,h3{font-family:var(--font-display);font-weight:600;line-height:1.2;text-wrap:balance}
section.doc h1{font-size:clamp(24px,3vw,32px);margin:0 0 12px;padding-left:12px;border-left:4px solid var(--accent)}
h2{font-size:22px;margin:36px 0 10px}
h3{font-size:17px;margin:26px 0 8px;color:var(--muted)}
p{max-width:78ch;margin:10px 0}
ul,ol{max-width:78ch;padding-left:22px;margin:8px 0}
li{margin:4px 0}
a{color:var(--accent)}
code{font-family:var(--font-mono);font-size:.9em;background:var(--code-bg);padding:1px 5px;border-radius:3px}
pre{background:var(--code-bg);padding:12px 14px;border-radius:6px;overflow-x:auto;font-family:var(--font-mono);font-size:13px}
pre code{background:none;padding:0}
hr{border:0;border-top:1px solid var(--line);margin:28px 0}
.tbl{overflow-x:auto;margin:14px 0;border:1px solid var(--line);border-radius:6px;background:var(--surface)}
table{border-collapse:collapse;width:100%;font-size:13.5px;min-width:640px}
th,td{padding:8px 10px;border-bottom:1px solid var(--line);vertical-align:top;text-align:left}
th{background:var(--accent-soft);font-weight:600;white-space:nowrap}
tbody tr:last-child td{border-bottom:0}
td{font-variant-numeric:tabular-nums}
.dia{overflow-x:auto;background:var(--surface);border:1px solid var(--line);border-radius:6px;padding:12px;margin:14px 0}
.dia pre.mermaid{background:none;padding:0;overflow:visible;font-size:13px}
footer{color:var(--muted);font-size:13px;padding-block:24px 0}
@media (prefers-reduced-motion: no-preference){a{transition:color .15s}}
</style>
"""

HEAD = """<title>Aron Final Azure Pack</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Sans:wght@400;600&family=IBM+Plex+Sans+Condensed:wght@600&family=IBM+Plex+Mono:wght@400&display=swap">
"""


def main():
    out_path = pathlib.Path(sys.argv[1])
    parts = [HEAD, STYLE]
    parts.append('<nav class="top"><div class="in"><span class="brand">Aron on Azure, final account</span>'
                 + "".join(f'<a href="#{sid}">{title}</a>' for sid, title, _ in DOCS) + "</div></nav>")
    parts.append('<div class="wrap">')
    parts.append("""<header class="hero">
<div class="eyebrow">AKTCL field-sales platform · design pack · 2026-10-29</div>
<h1>What Aron needs in the final Azure account for 8,500 field users, and what it costs each month</h1>
<p>High-level and low-level design, every resource with its specification and the reason for it, and the monthly Azure cost per resource at list prices read from Microsoft's price API on the date above. Designed, written as infrastructure code and tested at pilot size; the fleet-size proof runs in the final account before the first rollout wave.</p>
<div class="kpis">
<div class="kpi"><div class="l">Core production</div><div class="v">USD 5,980</div><div class="s">a month, zone-redundant, one region</div></div>
<div class="kpi"><div class="l">Core + DR tier</div><div class="v">USD 7,420</div><div class="s">a month; 6,560 with a 1-year database reservation</div></div>
<div class="kpi"><div class="l">Design peaks</div><div class="v">400 req/s</div><div class="s">morning storm; 4,500 rows/s at check-out</div></div>
<div class="kpi"><div class="l">Recovery</div><div class="v">RPO 0</div><div class="s">zone loss; region loss RPO 15 min, RTO 4 h</div></div>
</div>
</header>""")
    for sid, title, fname in DOCS:
        md = (HERE / fname).read_text(encoding="utf-8")
        parts.append(f'<section class="doc" id="{sid}">{convert(md, sid)}</section>')
    parts.append('<footer>Source: <code>docs/35-final-azure-design-pack/</code> in the Aron repository. Figures are list prices from the Azure Retail Prices API (2026-10-29) and the design load model of docs/18; the first invoices in the final account replace them.</footer>')
    parts.append("</div>")
    out_path.write_text("\n".join(parts), encoding="utf-8")
    print(out_path, out_path.stat().st_size, "bytes")


if __name__ == "__main__":
    main()
