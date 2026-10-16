// The pre-paint stamp (web-glass s2.6): runs THEME_SCRIPT against a fake document and checks the stamps for the stored choice,
// the system fallbacks (dark, more contrast), the tier rules (no blur or reduced transparency gives lite; sunlight gives off)
// and that a throwing localStorage never breaks the page. The TypeScript twins (resolveTheme, resolveGlass) are checked too.
import { describe, expect, it } from "vitest";
import { THEME_SCRIPT, resolveGlass, resolveTheme } from "@/lib/ui/theme";

function run(opts: { store?: Record<string, string>; media?: string[]; blur?: boolean; throwing?: boolean }): Record<string, string> {
  const attrs: Record<string, string> = {};
  const media = new Set(opts.media ?? []);
  const ctx = {
    document: { documentElement: { setAttribute: (k: string, v: string) => void (attrs[k] = v) } },
    localStorage: opts.throwing
      ? {
          getItem: () => {
            throw new Error("blocked");
          },
        }
      : { getItem: (k: string) => opts.store?.[k] ?? null },
    matchMedia: (q: string) => ({ matches: media.has(q) }),
    window: { CSS: { supports: () => opts.blur ?? true } },
  };
  new Function("document", "localStorage", "matchMedia", "window", "CSS", THEME_SCRIPT)(ctx.document, ctx.localStorage, ctx.matchMedia, ctx.window, ctx.window.CSS);
  return attrs;
}

describe("pre-paint theme script", () => {
  it("defaults to light, tier A and regular density", () => {
    expect(run({})).toEqual({ "data-theme": "light", "data-glass": "full", "data-density": "regular" });
  });
  it("applies the stored theme, density and sidebar state", () => {
    expect(run({ store: { "aron.theme": "dark", "aron.density": "compact", "aron.nav": "rail" } })).toEqual({ "data-theme": "dark", "data-glass": "full", "data-density": "compact", "data-nav": "rail" });
  });
  it("system follows the OS: dark, and more-contrast wins as sunlight which forces tier C", () => {
    expect(run({ store: { "aron.theme": "system" }, media: ["(prefers-color-scheme: dark)"] })["data-theme"]).toBe("dark");
    const s = run({ store: { "aron.theme": "system" }, media: ["(prefers-contrast: more)"] });
    expect(s["data-theme"]).toBe("sunlight");
    expect(s["data-glass"]).toBe("off");
  });
  it("no backdrop-filter or reduced transparency gives tier B; the user may only go more solid", () => {
    expect(run({ blur: false })["data-glass"]).toBe("lite");
    expect(run({ media: ["(prefers-reduced-transparency: reduce)"] })["data-glass"]).toBe("lite");
    expect(run({ store: { "aron.glass": "off" } })["data-glass"]).toBe("off");
    expect(run({ store: { "aron.glass": "lite" } })["data-glass"]).toBe("lite");
  });
  it("an unknown stored value falls back to the defaults and a blocked store never throws", () => {
    expect(run({ store: { "aron.theme": "neon", "aron.density": "huge" } })).toEqual({ "data-theme": "light", "data-glass": "full", "data-density": "regular" });
    expect(run({ throwing: true })["data-theme"]).toBe("light");
  });
  it("the TypeScript twins agree with the script", () => {
    const no = () => false;
    expect(resolveTheme("system", (q) => q === "(prefers-color-scheme: dark)")).toBe("dark");
    expect(resolveTheme("light", () => true)).toBe("light");
    expect(resolveGlass("light", no, true, null)).toBe("full");
    expect(resolveGlass("light", no, false, null)).toBe("lite");
    expect(resolveGlass("sunlight", no, true, null)).toBe("off");
    expect(resolveGlass("dark", (q) => q === "(prefers-reduced-transparency: reduce)", true, null)).toBe("lite");
  });
});
