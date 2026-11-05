// Build the Word versions of the owner pack from the markdown files in this folder.
//   node docs/35-final-azure-design-pack/build-docx.js <out-dir>
// Version 1 (full): README + 01 HLD + 02 LLD + 03 cost, with the Azure-icon diagrams.
// Version 2 (precise): 04 only (diagrams, resource table with quantity and justification, cost, Spec+).
const fs = require('fs');
const path = require('path');
const {
  Document, Packer, Paragraph, TextRun, HeadingLevel, Table, TableRow, TableCell, WidthType, ImageRun,
  AlignmentType, PageBreak, PageOrientation, ShadingType, BorderStyle, TableOfContents, LevelFormat, ExternalHyperlink,
} = require('docx');

const HERE = __dirname;
const OUT = path.resolve(process.argv[2] || HERE);
const PAGE_W = 16838, PAGE_H = 11906;            // A4 landscape in DXA (docx-js swaps for landscape)
const MARGIN = 1021;                             // 1.8 cm
const CONTENT_W = 16838 - 2 * MARGIN;            // 14,796 DXA
const CONTENT_PX = Math.round(CONTENT_W / 1440 * 96);   // about 986 px

const FONT = 'Calibri';
const MONO = 'Consolas';

function pngSize(buf) {
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) };
}

// ---- inline markdown -> TextRuns ----------------------------------------------------------------
function inline(text, base = {}) {
  const runs = [];
  const re = /(\*\*[^*]+\*\*|`[^`]+`|\[[^\]]+\]\([^)]+\))/g;
  let last = 0, m;
  while ((m = re.exec(text)) !== null) {
    if (m.index > last) runs.push(new TextRun({ text: text.slice(last, m.index), ...base }));
    const tok = m[0];
    if (tok.startsWith('**')) runs.push(new TextRun({ text: tok.slice(2, -2), bold: true, ...base }));
    else if (tok.startsWith('`')) runs.push(new TextRun({ text: tok.slice(1, -1), font: MONO, size: (base.size || 21) - 2, ...base }));
    else {
      const mm = tok.match(/\[([^\]]+)\]\(([^)]+)\)/);
      if (/^https?:/.test(mm[2])) runs.push(new ExternalHyperlink({ children: [new TextRun({ text: mm[1], style: 'Hyperlink', ...base })], link: mm[2] }));
      else runs.push(new TextRun({ text: mm[1], ...base }));
    }
    last = m.index + tok.length;
  }
  if (last < text.length) runs.push(new TextRun({ text: text.slice(last), ...base }));
  return runs;
}

function para(text, opts = {}) {
  return new Paragraph({ children: inline(text, opts.run || {}), spacing: { after: 120 }, ...opts.para });
}

// ---- tables ---------------------------------------------------------------------------------------
function table(lines) {
  const rows = lines.map((ln) => ln.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim()));
  const head = rows[0], sep = rows[1], body = rows.slice(2);
  const n = head.length;
  const align = sep.map((c) => (c.endsWith(':') && c.startsWith('-') ? AlignmentType.RIGHT : AlignmentType.LEFT));
  // column widths proportional to the longest text in the column, bounded
  const maxLen = head.map((h, i) => Math.max(h.length, ...body.map((r) => (r[i] || '').length)));
  const weights = maxLen.map((l) => Math.min(Math.max(l, 6), 90));
  const sum = weights.reduce((a, b) => a + b, 0);
  const widths = weights.map((w) => Math.round((w / sum) * CONTENT_W));
  const diff = CONTENT_W - widths.reduce((a, b) => a + b, 0);
  widths[widths.length - 1] += diff;
  const size = n >= 6 ? 16 : n >= 4 ? 18 : 20;   // half-points: 8, 9, 10 pt
  const border = { style: BorderStyle.SINGLE, size: 4, color: 'C9C9D2' };
  const mkCell = (text, i, isHead) => new TableCell({
    width: { size: widths[i], type: WidthType.DXA },
    shading: isHead ? { type: ShadingType.CLEAR, fill: 'E8EEF7', color: 'auto' } : undefined,
    margins: { top: 60, bottom: 60, left: 80, right: 80 },
    children: [new Paragraph({ alignment: align[i] || AlignmentType.LEFT, children: inline(text || '', { size, bold: isHead || undefined }) })],
  });
  return new Table({
    width: { size: CONTENT_W, type: WidthType.DXA },
    columnWidths: widths,
    borders: { top: border, bottom: border, left: border, right: border, insideHorizontal: border, insideVertical: border },
    rows: [
      new TableRow({ tableHeader: true, children: head.map((c, i) => mkCell(c, i, true)) }),
      ...body.map((r) => new TableRow({ children: head.map((_, i) => mkCell(r[i], i, false)) })),
    ],
  });
}

// ---- markdown file -> docx children -------------------------------------------------------------
function convert(md, baseDir, ctx) {
  const lines = md.split('\n');
  const out = [];
  let i = 0;
  while (i < lines.length) {
    const ln = lines[i];
    if (ln.startsWith('```')) {   // code or mermaid fence: skip mermaid, keep code
      const lang = ln.slice(3).trim();
      let j = i + 1; const buf = [];
      while (j < lines.length && !lines[j].startsWith('```')) { buf.push(lines[j]); j++; }
      if (lang !== 'mermaid') buf.forEach((b) => out.push(new Paragraph({ children: [new TextRun({ text: b, font: MONO, size: 16 })], spacing: { after: 0 } })));
      i = j + 1; continue;
    }
    if (ln.startsWith('|')) {
      let j = i; const buf = [];
      while (j < lines.length && lines[j].startsWith('|')) { buf.push(lines[j]); j++; }
      out.push(table(buf)); out.push(new Paragraph({ spacing: { after: 120 } })); i = j; continue;
    }
    const img = ln.match(/^!\[([^\]]*)\]\(([^)]+)\)\s*$/);
    if (img) {
      const file = path.resolve(baseDir, img[2]);
      if (fs.existsSync(file)) {
        const buf = fs.readFileSync(file); const { w, h } = pngSize(buf);
        const width = Math.min(CONTENT_PX, Math.round(w / 2));   // the PNGs are rendered at 2x
        const height = Math.round(h * width / w);
        out.push(new Paragraph({ alignment: AlignmentType.CENTER, spacing: { before: 120, after: 60 }, children: [new ImageRun({ type: 'png', data: buf, transformation: { width, height }, altText: { title: img[1], description: img[1], name: img[1] } })] }));
      } else out.push(para(`[missing figure ${img[2]}]`));
      i++; continue;
    }
    const h = ln.match(/^(#{1,3})\s+(.*)$/);
    if (h) {
      const level = h[1].length; const text = h[2].replace(/\*\*/g, '');
      const heading = level === 1 ? HeadingLevel.HEADING_1 : level === 2 ? HeadingLevel.HEADING_2 : HeadingLevel.HEADING_3;
      if (level === 1 && ctx.count > 0) out.push(new Paragraph({ children: [new PageBreak()] }));
      ctx.count++;
      out.push(new Paragraph({ heading, children: inline(text), spacing: { before: level === 1 ? 240 : 200, after: 120 } }));
      i++; continue;
    }
    if (ln.trim() === '---') { i++; continue; }
    if (/^\s*[-*]\s+/.test(ln)) {
      let j = i;
      while (j < lines.length && /^\s*[-*]\s+/.test(lines[j])) {
        out.push(new Paragraph({ numbering: { reference: 'bullets', level: 0 }, children: inline(lines[j].replace(/^\s*[-*]\s+/, '')), spacing: { after: 60 } })); j++;
      }
      i = j; continue;
    }
    if (/^\s*\d+\.\s+/.test(ln)) {
      let j = i;
      const ref = `num${ctx.lists++}`;
      ctx.numbering.push({ reference: ref, levels: [{ level: 0, format: LevelFormat.DECIMAL, text: '%1.', alignment: AlignmentType.START, style: { paragraph: { indent: { left: 540, hanging: 300 } } } }] });
      while (j < lines.length && /^\s*\d+\.\s+/.test(lines[j])) {
        out.push(new Paragraph({ numbering: { reference: ref, level: 0 }, children: inline(lines[j].replace(/^\s*\d+\.\s+/, '')), spacing: { after: 60 } })); j++;
      }
      i = j; continue;
    }
    if (ln.trim() === '') { i++; continue; }
    const cap = ln.match(/^\*(?!\*)(.+)\*\s*$/);
    if (cap) { out.push(new Paragraph({ alignment: AlignmentType.CENTER, children: inline(cap[1], { italics: true, size: 19, color: '555555' }), spacing: { after: 200 } })); i++; continue; }
    let j = i; const buf = [];
    while (j < lines.length && lines[j].trim() !== '' && !/^(\||#|```|!\[|\s*[-*]\s|\s*\d+\.\s)/.test(lines[j])) { buf.push(lines[j].trim()); j++; }
    out.push(para(buf.join(' '))); i = j;
  }
  return out;
}

function cover(title, subtitle, lines) {
  return [
    new Paragraph({ spacing: { before: 2400 } }),
    new Paragraph({ children: [new TextRun({ text: 'AKTCL · Aron field-sales platform', size: 24, color: '9B1C3C', bold: true })], spacing: { after: 240 } }),
    new Paragraph({ children: [new TextRun({ text: title, size: 56, bold: true })], spacing: { after: 200 } }),
    new Paragraph({ children: [new TextRun({ text: subtitle, size: 28, color: '555555' })], spacing: { after: 400 } }),
    ...lines.map((l) => new Paragraph({ children: [new TextRun({ text: l, size: 22 })], spacing: { after: 80 } })),
    new Paragraph({ children: [new PageBreak()] }),
    new Paragraph({ heading: HeadingLevel.HEADING_1, children: [new TextRun('Contents')] }),
    new TableOfContents('Contents', { hyperlink: true, headingStyleRange: '1-2' }),
    new Paragraph({ children: [new PageBreak()] }),
  ];
}

function build(fileName, title, subtitle, coverLines, mdFiles) {
  const ctx = { count: 0, lists: 0, numbering: [] };
  const children = cover(title, subtitle, coverLines);
  for (const f of mdFiles) {
    const md = fs.readFileSync(path.join(HERE, f), 'utf8');
    children.push(...convert(md, HERE, ctx));
  }
  const doc = new Document({
    creator: 'Aron lead',
    title,
    features: { updateFields: true },
    styles: {
      default: { document: { run: { font: FONT, size: 21 } } },
      paragraphStyles: [
        { id: 'Heading1', name: 'Heading 1', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { size: 34, bold: true, color: '1C1C24', font: FONT }, paragraph: { spacing: { before: 240, after: 120 }, outlineLevel: 0 } },
        { id: 'Heading2', name: 'Heading 2', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { size: 27, bold: true, color: '9B1C3C', font: FONT }, paragraph: { spacing: { before: 200, after: 100 }, outlineLevel: 1 } },
        { id: 'Heading3', name: 'Heading 3', basedOn: 'Normal', next: 'Normal', quickFormat: true, run: { size: 23, bold: true, color: '3B4A66', font: FONT }, paragraph: { spacing: { before: 160, after: 80 }, outlineLevel: 2 } },
      ],
    },
    numbering: {
      config: [
        { reference: 'bullets', levels: [{ level: 0, format: LevelFormat.BULLET, text: '•', alignment: AlignmentType.LEFT, style: { paragraph: { indent: { left: 540, hanging: 300 } } } }] },
        ...ctx.numbering,
      ],
    },
    sections: [{
      properties: { page: { size: { width: PAGE_H, height: PAGE_W, orientation: PageOrientation.LANDSCAPE }, margin: { top: MARGIN, bottom: MARGIN, left: MARGIN, right: MARGIN } } },
      children,
    }],
  });
  return Packer.toBuffer(doc).then((buf) => {
    const out = path.join(OUT, fileName);
    fs.writeFileSync(out, buf);
    console.log(out, buf.length, 'bytes');
  });
}

(async () => {
  await build('Aron-Final-Azure-Design-Pack-v1-full.docx', 'Aron on Azure — final account design pack',
    'Version 1 (full): high-level design, low-level design, resource plan and monthly cost, with Azure-icon diagrams',
    ['Prepared by the Aron lead for the owner · 2026-10-29', 'Prices: Azure Retail Prices API, 2026-10-29, Southeast Asia · list prices, pay-as-you-go', 'Source: docs/35-final-azure-design-pack/ in the Aron repository (cost-model.py reproduces every figure)'],
    ['README.md', '01-high-level-design.md', '02-low-level-design.md', '03-resource-plan-and-cost.md']);
  await build('Aron-Final-Azure-Design-Pack-v2-precise.docx', 'Aron on Azure — final account, precise version',
    'Version 2 (precise): HLD and LLD diagrams, resource table with specification, quantity, justification and monthly cost, Spec+ for the existing sites',
    ['Prepared by the Aron lead for the owner · 2026-10-29', 'Prices: Azure Retail Prices API, 2026-10-29, Southeast Asia · list prices, pay-as-you-go', 'Buffer: USD 2,000 a month on Aron, USD 500 on the existing sites (owner band 1,000 to 3,000)'],
    ['04-precise-version.md']);
})();
