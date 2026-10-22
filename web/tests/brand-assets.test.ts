// The Aron logo and the browser icons (D-BRAND-01). The trap this file guards: src/proxy.ts matches every path except a short
// allow-list, and accessFor() sends any path without a session to /login, so without an exact exclusion a signed-out visitor
// gets the login HTML instead of the logo, the favicon or the app icons. The matcher is held to a table, to the real compiled
// Next matcher, to the files that sit in public/brand and to the Cache-Control rules of next.config.ts.
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { createRequire } from "node:module";
import { join } from "node:path";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import nextConfig from "../next.config";
import { BrandMark } from "@/components/brand-mark";
import { config, proxy } from "@/proxy";

const ROOT = join(__dirname, "..");
const BRAND_DIR = join(ROOT, "public", "brand");

// The matcher as Next itself compiles it (adds the _next/data prefix, the .rsc suffixes and the optional trailing slash). The
// function is in Next's JS but not in its typings, so it is loaded with require and typed here.
const { getMiddlewareMatchers } = createRequire(import.meta.url)("next/dist/build/analysis/get-page-static-info") as {
  getMiddlewareMatchers: (matcher: string[], nextConfig: object) => { regexp: string }[];
};
const compiled = new RegExp(getMiddlewareMatchers(config.matcher, {})[0]!.regexp);
/** true: the proxy (and with it the sign-in gate) runs for this path. false: the path is served without it. */
const gated = (path: string): boolean => compiled.test(path);

const PUBLIC_STATIC = ["/brand/aron-mark-64.png", "/brand/aron-mark-128.png", "/brand/aron-mark-256.png", "/icon.png", "/apple-icon.png", "/favicon.ico"];
const BUILD_AND_ROBOTS = ["/_next/static/chunks/main.js", "/_next/image", "/robots.txt"];
const PROTECTED = [
  "/", "/login", "/admin", "/admin/skus", "/admin/skus/1", "/products/tree", "/reports/route-std",
  "/api/bff/login", "/api/bff/session/refresh", "/api/bff/admin/skus", "/api/bff/maps/load",
  // lookalikes: only the exact names are public
  "/brand", "/brand/", "/brand/other.png", "/brand/aron-mark-512.png", "/brand/aron-mark-64.png.bak", "/brand/aron-mark-64.png/", "/brand/sub/aron-mark-64.png", "/brand/../admin",
  "/icon", "/apple-icon", "/icon.png.bak", "/icon/x", "/icons", "/xicon", "/icon.svg", "/apple-icon.svg", "/apple-icon-x", "/favicon.ico/", "/favicon.icox", "/favicon-ico", "/robots.txt.bak", "/manifest.webmanifest",
];

describe("proxy matcher: public static paths bypass the gate, nothing else does", () => {
  it.each(PUBLIC_STATIC)("public: %s", (p) => expect(gated(p), p).toBe(false));
  it.each(BUILD_AND_ROBOTS)("as before: %s", (p) => expect(gated(p), p).toBe(false));
  it.each(PROTECTED)("gated: %s", (p) => expect(gated(p), p).toBe(true));

  it("every file in public/brand is public and cacheable, and every public /brand entry is a real file", () => {
    const files = readdirSync(BRAND_DIR);
    expect(files.length).toBeGreaterThan(0);
    for (const f of files) {
      expect(gated(`/brand/${f}`), `${f} must be in the matcher`).toBe(false);
      expect(PUBLIC_STATIC, `${f} must be in the test table`).toContain(`/brand/${f}`);
    }
    for (const p of PUBLIC_STATIC.filter((x) => x.startsWith("/brand/"))) expect(files).toContain(p.slice("/brand/".length));
  });

  it("the icon routes are backed by files in src/app", () => {
    for (const f of ["favicon.ico", "icon.png", "apple-icon.png"]) expect(existsSync(join(ROOT, "src", "app", f)), f).toBe(true);
  });

  it("behaviour is unchanged where the proxy still runs: login stays public, admin and the BFF want a session", () => {
    const req = (path: string) => new NextRequest(`http://localhost:3000${path}`, { headers: { host: "localhost:3000" } });
    const login = proxy(req("/login"));
    expect(login.headers.get("location")).toBeNull();
    expect(login.headers.get("x-middleware-next")).toBe("1");
    expect(proxy(req("/admin")).headers.get("location")).toContain("/login?next=%2Fadmin");
    expect(proxy(req("/products/tree")).headers.get("location")).toContain("/login?next=");
    expect(proxy(req("/api/bff/admin/skus")).status).toBe(401);
    // A lookalike is NOT waved through: it is still redirected like any other page.
    expect(proxy(req("/brand/aron-mark-64.png.bak")).headers.get("location")).toContain("/login?next=");
  });
});

describe("next.config.ts Cache-Control", () => {
  type Rule = { source: string; headers: { key: string; value: string }[] };
  const rules = async (): Promise<Rule[]> => (await nextConfig.headers!()) as Rule[];
  /** The header value a path ends up with: the LAST matching rule wins (Next docs). Sources here are exact or the catch-all. */
  const effective = async (path: string, key: string): Promise<string | undefined> => {
    let v: string | undefined;
    for (const r of await rules()) if (r.source === "/:path*" || r.source === path) v = r.headers.find((h) => h.key === key)?.value ?? v;
    return v;
  };

  it("the catch-all keeps no-store and the baseline, and comes first", async () => {
    const [first] = await rules();
    expect(first!.source).toBe("/:path*");
    expect(first!.headers).toContainEqual({ key: "Cache-Control", value: "no-store" });
    expect(first!.headers.map((h) => h.key)).toContain("Strict-Transport-Security");
  });

  it.each(PUBLIC_STATIC)("%s gets a moderate public cache rule later in the list", async (p) => {
    const cc = (await effective(p, "Cache-Control"))!;
    expect(cc).toMatch(/^public, max-age=\d+/);
    const maxAge = Number(/max-age=(\d+)/.exec(cc)![1]);
    expect(maxAge).toBeGreaterThanOrEqual(3600); // not a no-op
    expect(maxAge).toBeLessThanOrEqual(7 * 86_400); // not "forever": the names are not content-hashed
    expect(cc).not.toContain("immutable");
    expect(cc).not.toContain("no-store");
    const all = await rules();
    expect(all.findIndex((r) => r.source === p)).toBeGreaterThan(0);
  });

  it("everything else stays no-store", async () => {
    for (const p of ["/login", "/", "/admin", "/api/bff/session", "/brand/other.png"]) expect(await effective(p, "Cache-Control"), p).toBe("no-store");
  });

  it("the cacheable sources are exactly the paths the proxy lets through (no cacheable 404 for a path that is gated)", async () => {
    const sources = (await rules()).filter((r) => r.source !== "/:path*").map((r) => r.source);
    expect([...sources].sort()).toEqual([...PUBLIC_STATIC].sort());
    for (const s of sources) expect(gated(s), s).toBe(false);
  });
});

/** PNG header: signature, then IHDR width, height, bit depth, colour type. */
function png(file: string): { w: number; h: number; colorType: number; bytes: number; hasTrns: boolean } {
  const b = readFileSync(file);
  expect([...b.subarray(0, 8)], `${file} PNG signature`).toEqual([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  expect(b.subarray(12, 16).toString("latin1")).toBe("IHDR");
  return { w: b.readUInt32BE(16), h: b.readUInt32BE(20), colorType: b[25]!, bytes: b.length, hasTrns: b.includes(Buffer.from("tRNS")) };
}

describe("the brand and icon files are real, square and small", () => {
  it("public/brand/aron-mark-N.png: PNG, N by N, with an alpha channel, within a size budget", () => {
    for (const f of readdirSync(BRAND_DIR)) {
      const m = /^aron-mark-(\d+)\.png$/.exec(f);
      expect(m, `${f} follows the naming`).not.toBeNull();
      const n = Number(m![1]);
      const info = png(join(BRAND_DIR, f));
      expect([info.w, info.h], f).toEqual([n, n]);
      expect([4, 6], `${f} keeps transparency (grey+alpha or RGBA)`).toContain(info.colorType);
      expect(info.bytes, `${f} bytes`).toBeLessThanOrEqual(64 * 1024);
    }
  });

  it("the logo ships at the sizes BrandMark asks for", () => {
    expect(readdirSync(BRAND_DIR).sort()).toEqual(["aron-mark-128.png", "aron-mark-256.png", "aron-mark-64.png"]);
  });

  it("src/app/icon.png is a 192 px PNG within a 40 KB budget, apple-icon.png a 180 px OPAQUE PNG (iOS paints transparency black)", () => {
    // icon.png is only the tab and bookmark icon (there is no PWA manifest asking for 512 px), so it stays small: 512 px was 154 KB.
    const icon = png(join(ROOT, "src", "app", "icon.png"));
    expect([icon.w, icon.h]).toEqual([192, 192]);
    expect([4, 6], "icon.png keeps transparency (grey+alpha or RGBA)").toContain(icon.colorType);
    expect(icon.bytes, "icon.png bytes").toBeLessThanOrEqual(40 * 1024);
    const apple = png(join(ROOT, "src", "app", "apple-icon.png"));
    expect([apple.w, apple.h]).toEqual([180, 180]);
    expect([0, 2, 3], "no alpha channel").toContain(apple.colorType);
    expect(apple.hasTrns, "no tRNS transparency").toBe(false);
    expect(apple.bytes).toBeLessThanOrEqual(64 * 1024);
  });

  it("src/app/favicon.ico is an ICO with the 16, 32 and 48 px frames", () => {
    const f = join(ROOT, "src", "app", "favicon.ico");
    const b = readFileSync(f);
    expect([b.readUInt16LE(0), b.readUInt16LE(2)], "reserved 0, type 1 (icon)").toEqual([0, 1]);
    const count = b.readUInt16LE(4);
    const sizes: number[] = [];
    for (let i = 0; i < count; i++) sizes.push(b[6 + i * 16] || 256);
    expect(sizes.sort((a, c) => a - c)).toEqual(expect.arrayContaining([16, 32, 48]));
    expect(statSync(f).size).toBeLessThanOrEqual(32 * 1024);
  });
});

describe("BrandMark", () => {
  const html = (props: Parameters<typeof BrandMark>[0] = {}) => renderToStaticMarkup(createElement(BrandMark, props));

  it("is a round white plate (the existing sidebar badge) holding a decorative <img> with explicit size, async decoding and density candidates", () => {
    const h = html({ size: 32 });
    // The plate keeps the slot the old monogram had: its box is the requested size, the logo box is 88% of it. (React 19 may put a
    // preload link for the picture in front of the markup; the checks look inside, not at the start.)
    expect(h).toMatch(/<span class="[^"]*rounded-full[^"]*bg-on-rail[^"]*" style="width:32px;height:32px" aria-hidden="true"><img /);
    expect(h).toContain("<img ");
    expect(h).toContain('alt=""');
    expect(h).toContain('width="28"');
    expect(h).toContain('height="28"');
    expect(h).toContain('decoding="async"');
    expect(h).toContain('src="/brand/aron-mark-64.png"');
    expect(h).toContain("/brand/aron-mark-64.png 1x");
    expect(h).toContain("/brand/aron-mark-64.png 2x");
    expect(h).toContain("/brand/aron-mark-128.png 3x");
    expect(h.toLowerCase()).not.toContain("fetchpriority");
    expect(h).not.toContain("<svg");
  });

  it("the default is the sidebar's 36 px plate", () => {
    const h = html();
    expect(h).toContain('style="width:36px;height:36px"');
    expect(h).toContain('width="32"');
  });

  it("the login mark asks for high fetch priority and the next file up for 2x and 3x", () => {
    const h = html({ size: 44, priority: true });
    expect(h.toLowerCase()).toContain('fetchpriority="high"');
    expect(h).toContain('style="width:44px;height:44px"');
    expect(h).toContain('width="39"');
    expect(h).toContain("/brand/aron-mark-64.png 1x");
    expect(h).toContain("/brand/aron-mark-128.png 2x");
    expect(h).toContain("/brand/aron-mark-128.png 3x");
  });

  it("an extra class lands on the plate, never on the picture", () => {
    const h = html({ size: 32, className: "extra-x" });
    expect(h).toMatch(/<span class="[^"]*extra-x[^"]*" style=/);
    expect(h.match(/extra-x/g)).toHaveLength(1);
  });

  it("every file it can point at exists", () => {
    for (const size of [24, 28, 32, 36, 40, 44, 48, 56, 64, 72, 96, 400]) {
      for (const u of html({ size }).match(/\/brand\/aron-mark-\d+\.png/g)!) expect(existsSync(join(ROOT, "public", u)), `${u} (size ${size})`).toBe(true);
    }
  });

  it("is used by the sidebar, the drawer, the mobile bar and the login card, and the old monogram is gone", () => {
    const read = (p: string) => readFileSync(join(ROOT, p), "utf8");
    expect(read("src/components/sidebar.tsx")).toMatch(/import \{ BrandMark \} from "\.\/brand-mark"/);
    expect(read("src/components/sidebar.tsx").match(/<BrandMark/g)).toHaveLength(2); // rail and drawer
    expect(read("src/components/shell.tsx")).toMatch(/<BrandMark size=\{32\} \/>/);
    expect(read("src/app/login/page.tsx")).toMatch(/<BrandMark size=\{44\} priority \/>/);
    expect(read("src/components/brand-mark.tsx")).not.toMatch(/from "next\/image"/);
    expect(read("src/components/sidebar.tsx")).not.toMatch(/<svg|vendor artwork|monogram/i);
    // The login page no longer takes the mark from the sidebar module, and the mobile bar no longer draws the dashboard glyph.
    expect(read("src/app/login/page.tsx")).toContain('from "@/components/brand-mark"');
    expect(read("src/components/shell.tsx")).not.toContain('name="dashboard"');
  });
});
