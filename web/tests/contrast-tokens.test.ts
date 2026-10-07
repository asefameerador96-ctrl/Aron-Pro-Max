// WCAG contrast of every declared text/surface variable pair (docs/32 s2a): body text 4.5:1, key figures 7:1, light and dark,
// glass surfaces composited over the worst gradient stop, and the solid fallback (prefers-reduced-transparency, no backdrop-filter, prefers-contrast: more).
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const css = readFileSync(new URL("../src/app/globals.css", import.meta.url), "utf8");
type Vars = Record<string, string>;

function block(src: string, open: RegExp): string {
  const m = open.exec(src);
  if (!m) throw new Error(`block not found: ${open}`);
  let depth = 0;
  for (let i = m.index + m[0].length - 1; i < src.length; i++) {
    if (src[i] === "{") depth++;
    if (src[i] === "}" && --depth === 0) return src.slice(m.index + m[0].length, i);
  }
  throw new Error("unbalanced block");
}
const vars = (body: string): Vars => Object.fromEntries([...body.matchAll(/(--[a-z0-9-]+):\s*([^;]+);/g)].map((m) => [m[1]!, m[2]!.trim()]));

const light = vars(block(css, /\n:root \{/));
const dark = { ...light, ...vars(block(block(css, /@media \(prefers-color-scheme: dark\) \{/), /:root \{/)) };

type Rgba = [number, number, number, number];
function parse(v: string): Rgba {
  const hex = /^#([0-9a-f]{6})$/i.exec(v);
  if (hex) return [0, 2, 4].map((i) => parseInt(hex[1]!.slice(i, i + 2), 16)).concat(1) as Rgba;
  const m = /^rgb\(\s*(\d+)\s+(\d+)\s+(\d+)(?:\s*\/\s*([\d.]+))?\s*\)$/.exec(v);
  if (!m) throw new Error(`unsupported colour ${v}`);
  return [Number(m[1]), Number(m[2]), Number(m[3]), m[4] === undefined ? 1 : Number(m[4])];
}
const over = (top: Rgba, bottom: Rgba): Rgba => [0, 1, 2].map((i) => top[i]! * top[3] + bottom[i]! * (1 - top[3])).concat(1) as Rgba;
const lum = ([r, g, b]: Rgba): number => {
  const f = (c: number) => ((c /= 255) <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
};
const ratio = (a: Rgba, b: Rgba): number => {
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi! + 0.05) / (lo! + 0.05);
};

const stops = (t: Vars): Rgba[] => [...t["--bg-gradient"]!.matchAll(/#[0-9a-f]{6}/gi)].map((m) => parse(m[0]));

const TEXT_BODY = ["--text-secondary", "--accent", "--success", "--warning", "--danger"];
const KEY_FIGURE = ["--text-primary"];

for (const [name, t] of [["light", light], ["dark", dark]] as const) {
  describe(`contrast tokens (${name})`, () => {
    const solid = parse(t["--surface-solid"]!);
    const glassOver = stops(t).map((bg) => over(parse(t["--surface-glass"]!), bg));
    const surfaces: [string, Rgba][] = [["solid", solid], ...glassOver.map((g, i): [string, Rgba] => [`glass over gradient stop ${i + 1}`, g])];

    it("reads the gradient and surfaces", () => {
      expect(stops(t).length).toBeGreaterThanOrEqual(3);
    });
    for (const [label, bg] of surfaces) {
      for (const v of TEXT_BODY) {
        it(`${v} on ${label} is at least 4.5:1`, () => {
          const fg = parse(t[v]!);
          expect(ratio(fg, bg)).toBeGreaterThanOrEqual(4.5);
        });
      }
      for (const v of KEY_FIGURE) {
        it(`${v} (key figures) on ${label} is at least 7:1`, () => {
          expect(ratio(parse(t[v]!), bg)).toBeGreaterThanOrEqual(7);
        });
      }
    }
    it("text on the accent fill is at least 4.5:1", () => {
      expect(ratio(parse(t["--on-accent"]!), parse(t["--accent"]!))).toBeGreaterThanOrEqual(4.5);
    });
    it("tier C and the reduced-transparency fallback resolve to the solid surface", () => {
      expect(css).toMatch(/prefers-reduced-transparency: reduce\), \(prefers-contrast: more\) \{ :root \{ --surface: var\(--surface-solid\)/);
      expect(css).toMatch(/:root\[data-glass="off"\] \{ --surface: var\(--surface-solid\)/);
    });
  });
}
