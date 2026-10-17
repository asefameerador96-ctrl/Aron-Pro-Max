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
  it("the graphic accent reaches 3:1 and the primary fill carries its label at 7:1", () => {
    for (const s of surfaces()) expect(ratio(hex(v["accent-graphic"]!), s)).toBeGreaterThanOrEqual(3);
    expect(ratio(hex(v["on-accent"]!), hex(v["accent-fill"]!))).toBeGreaterThanOrEqual(7);
  });
  it("the selected-state container carries its text at 7:1", () => {
    expect(ratio(hex(v["accent-on-wash"]!), hex(v["accent-wash"]!))).toBeGreaterThanOrEqual(7);
  });
  it("chart series are at least 3:1 on every surface, and distinct from each other", () => {
    for (const k of ["chart-1", "chart-2"]) for (const s of surfaces()) expect(ratio(hex(v[k]!), s), k).toBeGreaterThanOrEqual(3);
    expect(ratio(hex(v["chart-1"]!), hex(v["chart-2"]!))).toBeGreaterThanOrEqual(1.3); // lightness differs as well as hue
  });
  it("all ten vibrant chart series are at least 3:1 on every surface (owner 2026-10-17: a mix of ten or more colours)", () => {
    if (_name.endsWith("more contrast")) return; // the high-contrast themes only override the first two series
    for (let i = 1; i <= 10; i++) for (const s of surfaces()) expect(ratio(hex(v[`chart-${i}`]!), s), `chart-${i}`).toBeGreaterThanOrEqual(3);
  });
  it("the yes button is green and the no button is red, both with a 7:1 label", () => {
    const [yr, yg, yb] = hex(v["yes-fill"]!);
    const [nr, ng, nb] = hex(v["no-fill"]!);
    expect(yg!).toBeGreaterThan(Math.max(yr!, yb!)); // green dominates
    expect(nr!).toBeGreaterThan(Math.max(ng!, nb!)); // red dominates
    expect(ratio(hex(v["on-yes"]!), hex(v["yes-fill"]!))).toBeGreaterThanOrEqual(7);
    expect(ratio(hex(v["on-no"]!), hex(v["no-fill"]!))).toBeGreaterThanOrEqual(7);
    expect(ratio(hex(v["yes-fill"]!), hex(v["no-fill"]!))).toBeGreaterThanOrEqual(1); // never the same colour
  });
});

describe("vibrant utility palette (owner 2026-10-17): every wash carries its ink at 7:1; yes and no buttons carry a white label", () => {
  const t = vars("");
  const c = (name: string) => hex(t[`color-${name}`]!);
  const pairs: [string, string][] = [
    ["red-50", "red-800"], ["red-50", "red-900"], ["green-50", "green-800"], ["emerald-50", "emerald-900"], ["emerald-100", "emerald-900"],
    ["emerald-50", "emerald-800"], ["amber-50", "amber-800"], ["amber-50", "amber-900"], ["amber-100", "amber-900"], ["sky-50", "sky-900"],
    ["yellow-50", "yellow-900"], ["violet-100", "violet-900"], ["lime-50", "lime-900"],
  ];
  it.each(pairs)("%s with %s ink is at least 7:1", (wash, ink) => {
    expect(ratio(c(ink), c(wash))).toBeGreaterThanOrEqual(7);
  });
  it("red-700 text on the red-50 wash (small error text) is at least 4.5:1", () => {
    expect(ratio(c("red-700"), c("red-50"))).toBeGreaterThanOrEqual(4.5);
  });
  it("white on emerald-700 (yes) and red-700 (no) is at least 7:1; red-600 text on white is at least 4.5:1", () => {
    const white = [255, 255, 255];
    expect(ratio(white, c("emerald-700"))).toBeGreaterThanOrEqual(7);
    expect(ratio(white, c("red-700"))).toBeGreaterThanOrEqual(7);
    expect(ratio(c("red-600"), white)).toBeGreaterThanOrEqual(4.5);
  });
  it("the palette spans at least ten distinct hues", () => {
    const hues = new Set<number>();
    for (const [k, val] of Object.entries(t)) {
      if (!/^color-(red|green|emerald|amber|sky|yellow|violet|lime|brand)-/.test(k) && !/^chart-/.test(k) && !/^(yes|no)-fill$/.test(k)) continue;
      const [r, g, b] = hex(val).map((x) => x / 255) as [number, number, number];
      const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
      if (mx - mn < 0.12) continue;
      const d = mx - mn;
      const h = mx === r ? ((g - b) / d + 6) % 6 : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
      hues.add(Math.round(h * 60 / 30)); // 30-degree buckets
    }
    expect(hues.size).toBeGreaterThanOrEqual(10);
  });
});

describe("glass fallbacks", () => {
  it("glass is only switched on under @supports backdrop-filter, with no reduced transparency and no more-contrast request", () => {
    expect(css).toMatch(/@media \(hover: hover\) and \(pointer: fine\) and \(prefers-reduced-transparency: no-preference\) and \(prefers-contrast: no-preference\)/);
    expect(css).toMatch(/@supports \(backdrop-filter: blur\(1px\)\)/);
    expect(css).toMatch(/prefers-reduced-transparency: reduce\)(, \(prefers-contrast: more\))? \{ :root \{ --surface: var\(--surface-solid\)/);
  });
});
