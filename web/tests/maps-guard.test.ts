// N-047: the daily cost guard stops Maps loads above the cap; the key is only handed out under it.
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { SESSION_COOKIE, SESSION_PURPOSE } from "@/lib/auth/cookies";
import { seal } from "@/lib/auth/seal";
import { MapsGuard, capFromEnv, mapsKey } from "@/lib/maps/guard";
import { createMock } from "../mock/server";

describe("MapsGuard", () => {
  it("allows up to the cap, then refuses, and resets at the Dhaka day change", () => {
    const g = new MapsGuard(2);
    const d1 = new Date("2026-10-07T10:00:00Z");
    expect(g.take(d1)).toEqual({ allowed: true, used: 1, cap: 2 });
    expect(g.take(d1).allowed).toBe(true);
    expect(g.take(d1)).toEqual({ allowed: false, used: 2, cap: 2 });
    expect(g.take(new Date("2026-10-07T17:59:00Z")).allowed).toBe(false); // 23:59 Dhaka, same business day
    expect(g.take(new Date("2026-10-07T18:00:00Z")).allowed).toBe(true); // 00:00 Dhaka next day
  });
  it("a cap of 0 switches the map off", () => {
    expect(new MapsGuard(0).take().allowed).toBe(false);
  });
  it("reads the cap and key from the environment, never a default key", () => {
    expect(capFromEnv({ MAPS_DAILY_CAP: "5" })).toBe(5);
    expect(capFromEnv({ MAPS_DAILY_CAP: "junk" })).toBe(2000);
    expect(mapsKey({})).toBeNull();
    expect(mapsKey({ NEXT_PUBLIC_MAPS_WEB_KEY: "k" })).toBeNull();
    expect(mapsKey({ MAPS_WEB_KEY: "k" })).toBe("k");
  });
});

describe("POST /api/bff/maps/load", () => {
  const mock = createMock();
  let cookie = "";
  beforeAll(async () => {
    await new Promise<void>((r) => mock.server.listen(0, "127.0.0.1", r));
    process.env.ARON_API_BASE_URL = `http://127.0.0.1:${(mock.server.address() as AddressInfo).port}`;
    process.env.ARON_COOKIE_INSECURE = "1";
    process.env.MAPS_WEB_KEY = "test-key";
    process.env.MAPS_DAILY_CAP = "2";
    const login = (await (await fetch(`${process.env.ARON_API_BASE_URL}/v1/auth/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username: "tso334", password: "tso-pass-1", client: "web" }) })).json()) as { access_token: string; user: never; scope: never };
    cookie = `${SESSION_COOKIE}=${seal({ at: login.access_token, atExp: Date.now() + 600_000, user: login.user, scope: login.scope }, SESSION_PURPOSE, 600)}`;
  });
  afterAll(() => new Promise<void>((r) => mock.server.close(() => r())));

  it("hands out the key twice, then answers 429 and no key", async () => {
    const { POST } = await import("@/app/api/bff/maps/load/route");
    const call = () => POST(new NextRequest("http://localhost:3000/api/bff/maps/load", { method: "POST", headers: { cookie, host: "localhost:3000" } }));
    const a = await call();
    expect(await a.json()).toEqual({ enabled: true, key: "test-key" });
    expect((await call()).status).toBe(200);
    const c = await call();
    expect(c.status).toBe(429);
    expect(JSON.stringify(await c.json())).not.toContain("test-key");
  });
  it("needs a session", async () => {
    const { POST } = await import("@/app/api/bff/maps/load/route");
    expect((await POST(new NextRequest("http://localhost:3000/api/bff/maps/load", { method: "POST", headers: { host: "localhost:3000" } }))).status).toBe(401);
  });
});
