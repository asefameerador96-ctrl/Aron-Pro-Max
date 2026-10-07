// Contrast gate over the web CSS variables (docs/32 s2a): text 4.5:1 on every surface (solid and glass blended over the page gradient),
// key figures (text-primary) 7:1, chart series 3:1 as graphics, in light, dark and prefers-contrast: more. Also the glass fallbacks.
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const css = readFileSync("src/app/globals.css", "utf8");

/** Variables declared in the :root blocks that sit inside the given media wrapper ("" = top level). */
function vars(wrapper: string): Record<string, string> {
  const out: Record<string, string> = {};
  const blocks = wrapper === "" ? [css.replace(/@media[^{]*\{[\s\S]*?\n\}\n/g, "")] : [...css.matchAll(new RegExp(`@media ${wrapper.replace(/[()]/g, "\\$&")} \\{([\\s\\S]*?)\\n\\}\\n`, "g"))].map((m) => m[1]!);
  for (const b of blocks) for (const m of b.matchAll(/--([a-z0-9-]+):\s*([^;]+);/g)) out[m[1]!] = m[2]!.trim();
  return out;
}
const hex = (h: string): [number, number, number] => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16)) as [number, number, number];
const lum = ([r, g, b]: number[]) => {
  const f = (c: number) => ((c /= 255) <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
  return 0.2126 * f(r!) + 0.7152 * f(g!) + 0.0722 * f(b!);
};
const ratio = (a: number[], b: number[]) => {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p);
  return (x! + 0.05) / (y! + 0.05);
};
const blend = (fg: number[], alpha: number, bg: number[]) => fg.map((c, i) => c * alpha + bg[i]! * (1 - alpha));
const gradientStops = (g: string) => [...g.matchAll(/#[0-9a-f]{6}/gi)].map((m) => hex(m[0]));
const glass = (v: string): { rgb: number[]; a: number } => {
  const m = /rgb\((\d+) (\d+) (\d+) \/ ([\d.]+)\)/.exec(v);
  return m ? { rgb: [Number(m[1]), Number(m[2]), Number(m[3])], a: Number(m[4]) } : { rgb: hex(v), a: 1 };
};

const themes: [string, Record<string, string>][] = [
  ["light", vars("")],
  ["dark", { ...vars(""), ...vars("(prefers-color-scheme: dark)") }],
  ["light, more contrast", { ...vars(""), ...vars("(prefers-contrast: more)") }],
  ["dark, more contrast", { ...vars(""), ...vars("(prefers-color-scheme: dark)"), ...vars("(prefers-contrast: more)"), ...vars("(prefers-contrast: more) and (prefers-color-scheme: dark)") }],
];

describe.each(themes)("web tokens, %s", (_name, v) => {
  const surfaces = (): number[][] => {
    const g = glass(v["surface-glass"]!);
    const solid = hex(v["surface-solid"]!);
    const page = gradientStops(v["bg-gradient"]!);
    return [solid, ...page.map((p) => blend(g.rgb, g.a, p))];
  };
  it("key figures (text-primary) reach 7:1 on every surface", () => {
    for (const s of surfaces()) expect(ratio(hex(v["text-primary"]!), s)).toBeGreaterThanOrEqual(7);
  });
  it("secondary text and status colours reach 4.5:1 on every surface", () => {
    for (const k of ["text-secondary", "accent", "success", "warning", "danger"]) for (const s of surfaces()) expect(ratio(hex(v[k]!), s), `${k}`).toBeGreaterThanOrEqual(4.5);
  });
  // Sunlight is a light-theme concern (docs/32 s2a item 2: dark is a user choice, not the default); office dark themes are covered by the ratios above.
  it.skipIf(_name.startsWith("dark"))("glare proxy (35 percent toward white): figures keep 4.5:1, secondary text 3:1", () => {
    const white = [255, 255, 255];
    for (const s of surfaces()) {
      expect(ratio(blend(hex(v["text-primary"]!), 0.65, white), blend(s, 0.65, white))).toBeGreaterThanOrEqual(4.5);
      expect(ratio(blend(hex(v["text-secondary"]!), 0.65, white), blend(s, 0.65, white))).toBeGreaterThanOrEqual(3);
    }
  });
  it("chart series are at least 3:1 on every surface, and distinct from each other", () => {
    for (const k of ["chart-1", "chart-2"]) for (const s of surfaces()) expect(ratio(hex(v[k]!), s), k).toBeGreaterThanOrEqual(3);
    expect(ratio(hex(v["chart-1"]!), hex(v["chart-2"]!))).toBeGreaterThanOrEqual(1.3); // lightness differs as well as hue
  });
});

describe("glass fallbacks", () => {
  it("glass is only switched on under @supports backdrop-filter, with no reduced transparency and no more-contrast request", () => {
    expect(css).toMatch(/@media \(hover: hover\) and \(pointer: fine\) and \(prefers-reduced-transparency: no-preference\) and \(prefers-contrast: no-preference\)/);
    expect(css).toMatch(/@supports \(backdrop-filter: blur\(1px\)\)/);
    expect(css).toMatch(/prefers-reduced-transparency: reduce\)(, \(prefers-contrast: more\))? \{ :root \{ --surface: var\(--surface-solid\)/);
  });
});
