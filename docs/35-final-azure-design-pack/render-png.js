// Render every SVG in a folder to PNG at 2x with the pre-installed Chromium (Playwright for Node).
//   node docs/35-final-azure-design-pack/render-png.js <dir>
const { chromium } = require('playwright');
const fs = require('fs');
const path = require('path');

(async () => {
  const dir = path.resolve(process.argv[2]);
  const files = fs.readdirSync(dir).filter((f) => f.endsWith('.svg'));
  const browser = await chromium.launch();
  for (const f of files) {
    const svg = fs.readFileSync(path.join(dir, f), 'utf8');
    const m = svg.match(/width="(\d+)" height="(\d+)"/);
    const w = parseInt(m[1], 10), h = parseInt(m[2], 10);
    const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 2 });
    await page.setContent(`<html><body style="margin:0;background:#fff">${svg}</body></html>`);
    await page.waitForTimeout(150);
    const out = path.join(dir, f.replace(/\.svg$/, '.png'));
    await page.screenshot({ path: out, clip: { x: 0, y: 0, width: w, height: h } });
    await page.close();
    console.log(path.basename(out), fs.statSync(out).size, 'bytes');
  }
  await browser.close();
})();
