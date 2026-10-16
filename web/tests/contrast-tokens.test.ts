// The shared contrast ledger of docs/design/tokens.md s2.7 (raw / glare), re-asserted row by row from src/styles/tokens.css for
// light, dark and sunlight, plus the web-only pairs of web-glass s2.4 (text on the data surface). The kit lane runs the same
// rows in a JVM test against the Kotlin tokens; this file is the web half of that proof. Helpers come from css-contrast.test.ts.
import { describe, expect, it } from "vitest";
import { dark, glare, light, over, pageBackdrops, parse, ratio, sunlight, type Rgba, type Vars } from "./helpers/tokens";

type Row = { pair: string; fg: string; bgs: (t: Vars) => Rgba[]; raw: number; glareGate?: number; sunlightRaw?: number };

const solid = (t: Vars) => [parse(t["--surface-solid"]!)];
const raised = (t: Vars) => [parse(t["--surface-solid-raised"]!)];
const field = (t: Vars) => pageBackdrops(t).map((p) => over(parse(t["--surface-glass-field"]!), p));
const glass = (t: Vars) => pageBackdrops(t).map((p) => over(parse(t["--surface-glass"]!), p));
const data = (t: Vars) => pageBackdrops(t).map((p) => over(parse(t["--surface-data"]!), p));
const page = (t: Vars) => pageBackdrops(t);
const topBar = (t: Vars) => pageBackdrops(t).flatMap((p) => [over(parse(`rgb(${t["--strong-rgb"]} / 0.96)`), p), over(parse(`rgb(${t["--strong-rgb"]} / 0.86)`), p)]);
const container = (k: string) => (t: Vars) => [parse(t[`--${k}-container`]!)];
const fills = (t: Vars) => ["--accent-hi", "--accent-fill", "--accent-pressed"].map((k) => parse(t[k]!));
const all = (...f: ((t: Vars) => Rgba[])[]) => (t: Vars) => f.flatMap((g) => g(t));

const ROWS: Row[] = [
  { pair: "text.primary on field, raised, solid (key figures)", fg: "--text-primary", bgs: all(field, raised, solid), raw: 7, glareGate: 4.5 },
  { pair: "text.primary on the bare page and on the top bar (body)", fg: "--text-primary", bgs: all(page, topBar), raw: 7, glareGate: 3 },
  { pair: "text.secondary on field, raised, solid, accent.container", fg: "--text-secondary", bgs: all(field, raised, solid, container("accent")), raw: 4.5, glareGate: 3, sunlightRaw: 7 },
  { pair: "text.secondary on the bare page and on the top bar", fg: "--text-secondary", bgs: all(page, topBar), raw: 4.5, glareGate: 3, sunlightRaw: 7 },
  { pair: "accent.text on field, raised, solid", fg: "--accent-text", bgs: all(field, raised, solid), raw: 4.5, glareGate: 3 },
  { pair: "accent.text on the bare page and on the top bar", fg: "--accent-text", bgs: all(page, topBar), raw: 4.5, glareGate: 3 },
  { pair: "text.onAccent on the primary fill (top, bottom, pressed)", fg: "--text-on-accent", bgs: fills, raw: 7, glareGate: 4.5 },
  { pair: "text.onAccent on danger.fill", fg: "--text-on-accent", bgs: (t) => [parse(t["--danger-fill"]!)], raw: 7, glareGate: 4.5 },
  { pair: "accent as icon, ring or meter on field, raised, solid", fg: "--accent", bgs: all(field, raised, solid), raw: 3 },
  ...(["success", "warning", "danger", "offline", "accent"] as const).map((k): Row => ({ pair: `${k}.onContainer on ${k}.container`, fg: `--${k}-on-container`, bgs: container(k), raw: 4.5, glareGate: 3 })),
  ...(["success", "warning", "danger", "offline", "accent"] as const).map((k): Row => ({ pair: `${k}.onContainer as status words on field and solid`, fg: `--${k}-on-container`, bgs: all(field, solid), raw: 4.5, glareGate: 3 })),
  ...(["success", "warning", "danger", "offline"] as const).map((k): Row => ({ pair: `${k} as icon on field and solid`, fg: `--${k}`, bgs: all(field, solid), raw: 3 })),
  { pair: "border.input on raised, solid, field", fg: "--border-input", bgs: all(raised, solid, field), raw: 3 },
  { pair: "border.focus on solid and field", fg: "--border-focus", bgs: all(solid, field), raw: 3 },
  { pair: "text.disabled on raised and field", fg: "--text-disabled", bgs: all(raised, field), raw: 3 },
  { pair: "text.placeholder on raised and solid", fg: "--text-placeholder", bgs: all(raised, solid), raw: 4.5 },
  { pair: "state.disabled.label on state.disabled.fill", fg: "--state-disabled-label", bgs: (t) => [parse(t["--state-disabled-fill"]!)], raw: 4.5 },
  // +web (web-glass s2.4): the data surface and status text on the 62 percent glass
  { pair: "text.primary on surface.data (key numbers)", fg: "--text-primary", bgs: data, raw: 7 },
  { pair: "text.secondary on surface.data", fg: "--text-secondary", bgs: data, raw: 4.5 },
  { pair: "accent.text on surface.data", fg: "--accent-text", bgs: data, raw: 4.5 },
  { pair: "border.input on surface.data", fg: "--border-input", bgs: data, raw: 3 },
  ...(["success", "warning", "danger", "offline"] as const).map((k): Row => ({ pair: `${k}.onContainer as text on surface.glass`, fg: `--${k}-on-container`, bgs: glass, raw: 4.5 })),
  ...(["success", "warning", "danger", "offline"] as const).map((k): Row => ({ pair: `${k} as icon on surface.glass`, fg: `--${k}`, bgs: glass, raw: 3 })),
];

for (const [name, t] of [
  ["light", light],
  ["dark", dark],
  ["sunlight", sunlight],
] as const) {
  describe(`contrast ledger (${name})`, () => {
    for (const r of ROWS) {
      const gate = name === "sunlight" && r.sunlightRaw ? r.sunlightRaw : r.raw;
      it(`${r.pair} is at least ${gate}:1 raw`, () => {
        for (const bg of r.bgs(t)) expect(ratio(parse(t[r.fg]!), bg)).toBeGreaterThanOrEqual(gate);
      });
      // Glare (docs/32 2a.4) is a field gate; the web honours it for the light themes, dark is a night choice (docs/32 s2a item 2).
      if (r.glareGate && name !== "dark")
        it(`${r.pair} keeps ${r.glareGate}:1 under glare`, () => {
          for (const bg of r.bgs(t)) expect(glare(parse(t[r.fg]!), bg)).toBeGreaterThanOrEqual(r.glareGate!);
        });
    }
  });
}
