// Shared helpers of the contrast tests: parse src/styles/tokens.css (themes), composite colours and compute WCAG ratios.
import { readFileSync } from "node:fs";

export const css = readFileSync(new URL("../../src/styles/tokens.css", import.meta.url), "utf8");
export type Vars = Record<string, string>;

/** Body of the first block opened by `open` (balanced braces). */
export function block(src: string, open: RegExp): string {
  const m = open.exec(src);
  if (!m) throw new Error(`block not found: ${open}`);
  let depth = 0;
  for (let i = m.index + m[0].length - 1; i < src.length; i++) {
    if (src[i] === "{") depth++;
    if (src[i] === "}" && --depth === 0) return src.slice(m.index + m[0].length, i);
  }
  throw new Error("unbalanced block");
}
export const vars = (body: string): Vars => Object.fromEntries([...body.matchAll(/(--[a-z0-9-]+):\s*([^;]+);/g)].map((m) => [m[1]!, m[2]!.trim()]));

export const light = vars(block(css, /\n:root,\n:root\[data-theme="light"\] \{/));
export const dark = { ...light, ...vars(block(css, /\n:root\[data-theme="dark"\] \{/)) };
export const darkMedia = vars(block(block(css, /@media \(prefers-color-scheme: dark\) \{/), /:root:not\(\[data-theme\]\) \{/));
export const sunlight = { ...light, ...vars(block(css, /\n:root\[data-theme="sunlight"\] \{/)) };

export type Rgba = [number, number, number, number];
export function parse(v: string): Rgba {
  const hex = /^#([0-9a-f]{6})$/i.exec(v);
  if (hex) return [0, 2, 4].map((i) => parseInt(hex[1]!.slice(i, i + 2), 16)).concat(1) as Rgba;
  const m = /^rgb\(\s*(\d+)\s+(\d+)\s+(\d+)(?:\s*\/\s*([\d.]+))?\s*\)$/.exec(v);
  if (!m) throw new Error(`unsupported colour ${v}`);
  return [Number(m[1]), Number(m[2]), Number(m[3]), m[4] === undefined ? 1 : Number(m[4])];
}
export const over = (top: Rgba, bottom: Rgba): Rgba => [0, 1, 2].map((i) => top[i]! * top[3] + bottom[i]! * (1 - top[3])).concat(1) as Rgba;
const lum = ([r, g, b]: Rgba): number => {
  const f = (c: number) => ((c /= 255) <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  return 0.2126 * f(r) + 0.7152 * f(g) + 0.0722 * f(b);
};
export const ratio = (a: Rgba, b: Rgba): number => {
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi! + 0.05) / (lo! + 0.05);
};
/** Glare proxy (docs/32 2a.4): both colours 35 percent toward white, then the ratio. */
export const glare = (fg: Rgba, bg: Rgba): number => ratio(over([255, 255, 255, 0.35], fg), over([255, 255, 255, 0.35], bg));

/** Page backdrops: the three gradient stops, each also under glow A (50 / 18 percent) and glow B (30 / 10 percent) at peak. */
export function pageBackdrops(t: Vars): Rgba[] {
  const g = t["--bg-gradient"]!;
  if (g === "none") return [parse(t["--bg-solid"]!)];
  const stops = [...g.matchAll(/#[0-9a-f]{6}/gi)].map((m) => parse(m[0]));
  const glows = [...g.matchAll(/rgb\((\d+) (\d+) (\d+) \/ (0?\.\d+)\)/g)].map((m): Rgba => [Number(m[1]), Number(m[2]), Number(m[3]), Number(m[4])]).filter((c) => c[3] > 0);
  return [...stops, ...stops.flatMap((s) => glows.map((gl) => over(gl, s)))];
}
/** Every surface a text role can sit on: solid, raised, the data surface and the three glass fills over every page backdrop. */
export function surfaces(t: Vars): [string, Rgba][] {
  const page = pageBackdrops(t);
  const comp = (name: string, v: string) => page.map((p, i): [string, Rgba] => [`${name} over backdrop ${i}`, over(parse(v), p)]);
  return [["solid", parse(t["--surface-solid"]!)], ["raised", parse(t["--surface-solid-raised"]!)], ...comp("data", t["--surface-data"]!), ...comp("glass", t["--surface-glass"]!), ...comp("field", t["--surface-glass-field"]!), ...comp("strong 86", `rgb(${t["--strong-rgb"]} / 0.86)`)];
}
