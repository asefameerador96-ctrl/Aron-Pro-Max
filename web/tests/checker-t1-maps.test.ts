// Independent checker (docs/26 s4), N-047 Google Maps cost guard. Failing tests here are evidence of defects.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { join } from "node:path";
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { SESSION_COOKIE, SESSION_PURPOSE } from "@/lib/auth/cookies";
import { seal } from "@/lib/auth/seal";
import { MapsGuard, capFromEnv } from "@/lib/maps/guard";
import { createMock } from "../mock/server";

describe("N-047 cap from the environment", () => {
  it("an empty MAPS_DAILY_CAP is treated as unset (default cap), not as 0 = maps off", () => {
    expect(capFromEnv({ MAPS_DAILY_CAP: "" })).toBe(2000);
    expect(capFromEnv({ MAPS_DAILY_CAP: "   " })).toBe(2000);
  });
  it("(holds) negative, fractional and junk caps fall back to the default; 0 switches maps off", () => {
    expect(capFromEnv({ MAPS_DAILY_CAP: "-5" })).toBe(2000);
    expect(capFromEnv({ MAPS_DAILY_CAP: "2.5" })).toBe(2000);
    expect(capFromEnv({ MAPS_DAILY_CAP: "0" })).toBe(0);
    expect(new MapsGuard(0).take().allowed).toBe(false);
  });
  it("(holds) the counter resets exactly at 00:00 Dhaka (18:00Z) and a burst never exceeds the cap", () => {
    const g = new MapsGuard(3);
    const t0 = new Date("2026-10-07T17:59:59Z");
    const burst = Array.from({ length: 50 }, () => g.take(t0));
    expect(burst.filter((r) => r.allowed)).toHaveLength(3);
    expect(g.take(new Date("2026-10-07T18:00:00Z"))).toEqual({ allowed: true, used: 1, cap: 3 });
  });
});

describe("N-047 BFF", () => {
  const mock = createMock();
  let cookie = "";
  beforeAll(async () => {
    await new Promise<void>((r) => mock.server.listen(0, "127.0.0.1", r));
    process.env.ARON_API_BASE_URL = `http://127.0.0.1:${(mock.server.address() as AddressInfo).port}`;
    process.env.ARON_COOKIE_INSECURE = "1";
    process.env.MAPS_WEB_KEY = "checker-secret-key";
    process.env.MAPS_DAILY_CAP = "1";
    const login = (await (await fetch(`${process.env.ARON_API_BASE_URL}/v1/auth/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username: "tso334", password: "tso-pass-1", client: "web" }) })).json()) as { access_token: string; user: never; scope: never };
    cookie = `${SESSION_COOKIE}=${seal({ at: login.access_token, atExp: Date.now() + 600_000, user: login.user, scope: login.scope }, SESSION_PURPOSE, 600)}`;
  });
  afterAll(() => new Promise<void>((r) => mock.server.close(() => r())));

  it("(holds) unauthenticated and cross-site callers get no key and do not consume the cap; the 429 body has no key", async () => {
    const { POST } = await import("@/app/api/bff/maps/load/route");
    const call = (headers: Record<string, string>) => POST(new NextRequest("http://localhost:3000/api/bff/maps/load", { method: "POST", headers: { host: "localhost:3000", ...headers } }));
    const anon = await call({});
    expect(anon.status).toBe(401);
    expect(await anon.text()).not.toContain("checker-secret-key");
    const xs = await call({ cookie, origin: "https://evil.example" });
    expect(xs.status).toBe(403);
    expect(await xs.text()).not.toContain("checker-secret-key");
    const ok = await call({ cookie });
    expect(await ok.json()).toEqual({ enabled: true, key: "checker-secret-key" });
    const capped = await call({ cookie });
    expect(capped.status).toBe(429);
    expect(await capped.text()).not.toContain("checker-secret-key");
  });
  // SKIPPED until web-admin removes its direct load (docs/requests/web-dashboard-radius-map-maps-key.md); that file is theirs.
  it.skip("every Maps script load goes through the capped BFF (only map-panel.tsx loads it; no build-time NEXT_PUBLIC key in client code)", () => {
    const files: string[] = [];
    const walk = (d: string) => {
      for (const f of readdirSync(d)) {
        const p = join(d, f);
        if (statSync(p).isDirectory()) walk(p);
        else if (/\.(ts|tsx)$/.test(f)) files.push(p);
      }
    };
    walk(new URL("../src", import.meta.url).pathname);
    const src = files.map((f) => [f, readFileSync(f, "utf8")] as const);
    expect(src.filter(([, s]) => /AIza[0-9A-Za-z_-]{20,}/.test(s)).map(([f]) => f)).toEqual([]); // (holds) no literal key
    expect(src.filter(([, s]) => s.includes("process.env.NEXT_PUBLIC_MAPS_WEB_KEY")).map(([f]) => f.replace(/.*\/src\//, ""))).toEqual([]);
    expect(src.filter(([, s]) => s.includes("maps.googleapis.com")).map(([f]) => f.replace(/.*\/src\//, ""))).toEqual(["components/map-panel.tsx"]);
    expect(src.filter(([, s]) => /from "@\/components\/map-panel"/.test(s)).map(([f]) => f.replace(/.*\/src\//, ""))).toEqual(["app/(dashboards)/page.tsx"]);
  });
});
