// Rings carry a printed value, but their arc is still a graphic: each ring hue keeps 3:1 against the data surface (WCAG 1.4.11)
// in every theme block of tokens.css. VIZ[3] (amber, about 2.2:1 in light) and VIZ[2] (green, about 2.8:1) are left out of rings.
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { RING_HUES } from "@/components/dash/charts";

const css = readFileSync(fileURLToPath(new URL("../src/styles/tokens.css", import.meta.url)), "utf8");

function block(selector: string): string {
  const i = css.indexOf(selector);
  expect(i, selector).toBeGreaterThanOrEqual(0);
  return css.slice(i, css.indexOf("}", i));
}
const vars = (b: string) => Object.fromEntries([...b.matchAll(/--([a-z0-9-]+):\s*([^;]+);/g)].map((m) => [m[1]!, m[2]!.trim()]));
function rgb(v: string): [number, number, number] {
  const hex = /^#([0-9a-f]{6})$/i.exec(v);
  if (hex) return [0, 2, 4].map((i) => parseInt(hex[1]!.slice(i, i + 2), 16)) as [number, number, number];
  const m = /^rgb\((\d+)\s+(\d+)\s+(\d+)/.exec(v);
  if (!m) throw new Error(`colour ${v}`);
  return [Number(m[1]), Number(m[2]), Number(m[3])]; // translucent surfaces are checked at their base colour
}
const lum = (c: [number, number, number]) => c.map((x) => x / 255).map((x) => (x <= 0.03928 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4)).reduce((s, x, i) => s + x * [0.2126, 0.7152, 0.0722][i]!, 0);
const ratio = (a: string, b: string) => { const [x, y] = [lum(rgb(a)), lum(rgb(b))].sort((p, q) => q - p); return (x! + 0.05) / (y! + 0.05); };

describe("ring hues keep 3:1 against the data surface", () => {
  const light = vars(block(':root[data-theme="light"]'));
  for (const [name, sel] of [["light", ':root[data-theme="light"]'], ["dark", ':root[data-theme="dark"]'], ["sunlight", ':root[data-theme="sunlight"]']] as const) {
    it(name, () => {
      const v = { ...light, ...vars(block(sel)) };
      for (const hue of RING_HUES) {
        const key = /var\(--([a-z0-9-]+)\)/.exec(hue)![1]!;
        expect(ratio(v[key]!, v["surface-data"]!), `${name} ${key}`).toBeGreaterThanOrEqual(3);
      }
    });
  }
});
