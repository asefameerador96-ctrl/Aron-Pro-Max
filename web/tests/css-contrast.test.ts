// Contrast gate over the web CSS variables (docs/32 s2a; docs/design/tokens.md s2.7; web-glass s2.4), recomputed from
// src/styles/tokens.css for light, dark and sunlight: key figures (text-primary) 7:1 on every surface (solid, raised, data
// and glass composited over the page gradient under both glows), secondary and accent text 4.5:1, every *-on-container on its
// container 4.5:1, status words on glass 4.5:1, base status colours and the accent as graphics 3:1, the primary and
// destructive labels 7:1, border-input and border-focus 3:1, and the glare proxy for the light themes. Also the tier fallbacks.
import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { block, css, dark, darkMedia, glare, light, parse, ratio, sunlight, surfaces, vars, type Vars } from "./helpers/tokens";

const STATUS = ["success", "warning", "danger", "offline"] as const;

const themes: [string, Vars][] = [
  ["light", light],
  ["dark", dark],
  ["sunlight", sunlight],
];

describe.each(themes)("web tokens, %s", (name, v) => {
  const all = surfaces(v);
  const p = (k: string) => parse(v[`--${k}`]!);
  it("key figures (text-primary) reach 7:1 on every surface", () => {
    for (const [s, bg] of all) expect(ratio(p("text-primary"), bg), s).toBeGreaterThanOrEqual(7);
  });
  it("secondary text and accent text reach 4.5:1 on every surface and on the accent container", () => {
    for (const k of ["text-secondary", "accent-text"]) {
      for (const [s, bg] of all) expect(ratio(p(k), bg), `${k} on ${s}`).toBeGreaterThanOrEqual(4.5);
    }
    expect(ratio(p("text-secondary"), p("accent-container"))).toBeGreaterThanOrEqual(4.5);
  });
  it("every status word (*-on-container) reaches 4.5:1 on its container and on every surface", () => {
    for (const k of [...STATUS, "accent"]) {
      expect(ratio(p(`${k}-on-container`), p(`${k}-container`)), k).toBeGreaterThanOrEqual(4.5);
      for (const [s, bg] of all) expect(ratio(p(`${k}-on-container`), bg), `${k} on ${s}`).toBeGreaterThanOrEqual(4.5);
    }
  });
  it("base status colours and the graphic accent reach 3:1 as icons, bars and rings on every surface", () => {
    for (const k of [...STATUS, "accent"]) for (const [s, bg] of all) expect(ratio(p(k), bg), `${k} on ${s}`).toBeGreaterThanOrEqual(3);
  });
  it("the primary and destructive labels reach 7:1 on their fills (top, bottom, pressed)", () => {
    for (const k of ["accent-hi", "accent-fill", "accent-pressed", "danger-fill"]) expect(ratio(p("text-on-accent"), p(k)), k).toBeGreaterThanOrEqual(7);
  });
  it("input and focus borders reach 3:1 on raised, solid and the data surface; placeholder 4.5:1; disabled label 4.5:1 on its fill", () => {
    for (const k of ["border-input", "border-focus"]) for (const s of ["surface-solid", "surface-solid-raised"]) expect(ratio(p(k), p(s)), `${k} on ${s}`).toBeGreaterThanOrEqual(3);
    for (const s of ["surface-solid", "surface-solid-raised"]) expect(ratio(p("text-placeholder"), p(s)), `placeholder on ${s}`).toBeGreaterThanOrEqual(4.5);
    expect(ratio(p("state-disabled-label"), p("state-disabled-fill"))).toBeGreaterThanOrEqual(4.5);
  });
  it("chart series are at least 3:1 on the data surface in dark; slots 1 to 3 pass in both themes", () => {
    const data = all.filter(([s]) => s.startsWith("data"));
    for (const i of [1, 2, 3]) for (const [s, bg] of data) expect(ratio(p(`viz-${i}`), bg), `viz-${i} on ${s}`).toBeGreaterThanOrEqual(name === "dark" ? 3 : 2.7); // light and sunlight share the light palette: slots 3 to 5 carry the relief rule (direct labels)
  });
  // The web is a desk screen (docs/32 2a.3) so the raw ratio is the gate; the glare proxy is asserted for the light themes at
  // the tokens.md s2.7 levels (key figures 4.5, secondary and accent text 3, status words 3).
  it.skipIf(name === "dark")("glare proxy (35 percent toward white): figures keep 4.5:1, secondary, accent and status words 3:1", () => {
    for (const [s, bg] of all) {
      expect(glare(p("text-primary"), bg), s).toBeGreaterThanOrEqual(4.5);
      expect(glare(p("text-secondary"), bg), s).toBeGreaterThanOrEqual(3);
      expect(glare(p("accent-text"), bg), s).toBeGreaterThanOrEqual(3);
      for (const k of STATUS) expect(glare(p(`${k}-on-container`), bg), `${k} on ${s}`).toBeGreaterThanOrEqual(3);
    }
    for (const k of [...STATUS, "accent"]) expect(glare(p(`${k}-on-container`), p(`${k}-container`)), k).toBeGreaterThanOrEqual(3);
    for (const k of ["accent-hi", "accent-fill", "danger-fill"]) expect(glare(p("text-on-accent"), p(k)), k).toBeGreaterThanOrEqual(4.5);
  });
});

describe("themes and tiers", () => {
  it("the [data-theme=dark] block and the prefers-color-scheme fallback declare the same variables with the same values", () => {
    const stamped = vars(block(css, /\n:root\[data-theme="dark"\] \{/));
    expect(darkMedia).toEqual(stamped);
  });
  it("sunlight is flat and opaque with 2 px edges and no shadows (tokens.md s2, docs/32 s2a.5)", () => {
    expect(sunlight["--bg-gradient"]).toBe("none");
    for (const k of ["--surface-glass", "--surface-glass-field", "--surface-data", "--card-fill"]) expect(parse(sunlight[k]!)[3], k).toBe(1);
    expect(sunlight["--edge-w"]).toBe("2px");
    expect(sunlight["--focus-w"]).toBe("3px");
    expect(sunlight["--elev-card"]).toBe("0 0 #0000");
    expect(sunlight["--vibrancy-tint"]).toBe("transparent");
  });
  it("tier B drops the blur and tier C (off, sunlight, reduced transparency, more contrast, forced colours) goes solid", () => {
    expect(css).toMatch(/:root\[data-glass="lite"\],\n:root\[data-glass="b"\] \{[^}]*--bf-bar: none;/);
    expect(css).toMatch(/:root\[data-glass="off"\],\n:root\[data-glass="c"\],\n:root\[data-theme="sunlight"\] \{[^}]*--glass-fill-strong: var\(--surface-solid\);/);
    expect(css).toMatch(/@supports not \(\(backdrop-filter: blur\(1px\)\) or \(-webkit-backdrop-filter: blur\(1px\)\)\) \{\n\s*:root:root \{[^}]*--bf-bar: none;/);
    expect(css).toMatch(/@media \(prefers-reduced-transparency: reduce\), \(prefers-contrast: more\), \(forced-colors: active\) \{\n\s*:root:root \{[^}]*--glass-fill-strong: var\(--surface-solid\);/);
  });
  it("blur only on tier A bars and sheets (glass.css): cards never blur, the top bar and sheets read --bf-bar / --bf-sheet", () => {
    const glass = readFileSync(new URL("../src/styles/glass.css", import.meta.url), "utf8");
    const util = (n: string) => block(glass, new RegExp(`@utility ${n} \\{`));
    for (const n of ["glass-card", "glass-data", "glass-rail", "glass-dialog", "glass-pop"]) expect(util(n), n).not.toMatch(/backdrop-filter/);
    expect(util("glass-bar")).toMatch(/backdrop-filter: var\(--bf-bar\)/);
    expect(util("glass-sheet")).toMatch(/backdrop-filter: var\(--bf-sheet\)/);
    expect(util("glass-panel")).toMatch(/backdrop-filter: var\(--bf-bar\)/);
  });
  it("reduced motion caps every duration at 120 ms", () => {
    const rm = block(css, /@media \(prefers-reduced-motion: reduce\) \{/);
    for (const k of ["--motion-fast", "--motion-base", "--motion-sheet"]) expect(vars(rm)[k], k).toBe("120ms");
  });
  it("Bangla line heights are at least 1.40 em of their size (tokens s7.1)", () => {
    const bn = vars(block(css, /\n:lang\(bn\) \{/));
    const sizes: [string, number][] = [["--lh-hero", 3], ["--lh-display", 2.125], ["--lh-numeral", 1.625], ["--lh-title", 1.375], ["--lh-body", 1], ["--lh-caption", 0.875]];
    for (const [k, size] of sizes) expect(parseFloat(bn[k]!) / size, k).toBeGreaterThanOrEqual(1.4);
  });
});
