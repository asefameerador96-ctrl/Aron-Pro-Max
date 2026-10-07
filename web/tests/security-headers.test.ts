// AUD-SEC-04: every response carries CSP (nonce), HSTS, no-store and friends; the web session has an idle and an absolute limit.
import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import nextConfig from "../next.config";
import { proxy } from "@/proxy";
import { SESSION_COOKIE, SESSION_PURPOSE } from "@/lib/auth/cookies";
import { sessionState, slid, idleMs } from "@/lib/auth/limits";
import { seal } from "@/lib/auth/seal";
import type { SessionData } from "@/lib/auth/session";
import { REQUIRED_HEADERS } from "@/lib/security-headers";

const user = (role: string) => ({ user_id: 1, username: "u", full_name: "U", role, designation: role, locale: "en" }) as SessionData["user"];
const sess = (role: string, extra: Partial<SessionData> = {}): SessionData => ({ at: "tok", atExp: Date.now() + 600_000, user: user(role), scope: { scope_version: 1, nodes: [{ type: "national", id: 0 }] }, ...extra });
const req = (path: string, s?: SessionData) => new NextRequest(`http://localhost:3000${path}`, { headers: { host: "localhost:3000", ...(s ? { cookie: `${SESSION_COOKIE}=${seal(s, SESSION_PURPOSE, 600)}` } : {}) } });
const MIN = 60_000;

describe("security headers on every proxy response", () => {
  const cases: [string, string, SessionData | undefined][] = [
    ["page for a signed-in user", "/", sess("TSO")],
    ["redirect to login", "/reports/route-std", undefined],
    ["public login page", "/login", undefined],
    ["BFF without a session (401)", "/api/bff/maps/load", undefined],
    ["403 for the wrong role", "/admin", sess("TSO")],
  ];
  for (const [name, path, s] of cases) {
    it(name, () => {
      const res = proxy(req(path, s));
      for (const h of REQUIRED_HEADERS) expect(res.headers.get(h), `${h} on ${name}`).toBeTruthy();
      expect(res.headers.get("cache-control")).toContain("no-store");
      expect(res.headers.get("strict-transport-security")).toMatch(/max-age=31536000/);
      const csp = res.headers.get("content-security-policy")!;
      expect(csp).toContain("frame-ancestors 'none'");
      expect(csp).toContain("default-src 'self'");
      expect(csp.match(/script-src[^;]*/)![0]).not.toContain("'unsafe-inline'");
    });
  }
  it("the nonce is fresh per request and reaches the app in the forwarded CSP header", () => {
    const a = proxy(req("/", sess("TSO")));
    const b = proxy(req("/", sess("TSO")));
    const nonce = (r: Response) => /'nonce-([^']+)'/.exec(r.headers.get("content-security-policy")!)![1];
    expect(nonce(a)).not.toBe(nonce(b));
    expect(a.headers.get("x-middleware-request-content-security-policy")).toContain(`nonce-${nonce(a)}`);
  });
  it("next.config sets the same baseline on static assets", async () => {
    const rules = await nextConfig.headers!();
    const keys = rules.flatMap((r) => r.headers.map((h) => h.key));
    for (const k of ["Strict-Transport-Security", "X-Content-Type-Options", "X-Frame-Options", "Referrer-Policy", "Cache-Control"]) expect(keys).toContain(k);
  });
});

describe("session limits", () => {
  const now = Date.now();
  it("idle: 30 minutes by default", () => {
    expect(idleMs({})).toBe(30 * MIN);
    expect(idleMs({ ARON_WEB_IDLE_MIN: "10" })).toBe(10 * MIN);
    expect(idleMs({ ARON_WEB_IDLE_MIN: "0" })).toBe(30 * MIN);
    expect(sessionState(sess("TSO", { sat: now, act: now - 29 * MIN }), now)).toBe("ok");
    expect(sessionState(sess("TSO", { sat: now, act: now - 31 * MIN }), now)).toBe("idle");
  });
  it("absolute: 7 days for admin roles, 30 for the others", () => {
    const old = now - 8 * 86_400_000;
    expect(sessionState(sess("ADMIN", { sat: old, act: now }), now)).toBe("absolute");
    expect(sessionState(sess("SUPPORT", { sat: old, act: now }), now)).toBe("absolute");
    expect(sessionState(sess("TSO", { sat: old, act: now }), now)).toBe("ok");
    expect(sessionState(sess("TSO", { sat: now - 31 * 86_400_000, act: now }), now)).toBe("absolute");
  });
  it("sliding moves the idle clock at most once a minute", () => {
    expect(slid(sess("TSO", { act: now - 10_000 }), now)).toBeNull();
    expect(slid(sess("TSO", { act: now - 2 * MIN }), now)?.act).toBe(now);
  });
  it("the proxy ends an idle session: login redirect and cleared cookies; a live one slides", () => {
    const idle = proxy(req("/reports/route-std", sess("TSO", { sat: Date.now(), act: Date.now() - 31 * MIN })));
    expect(idle.headers.get("location")).toContain("/login");
    expect(idle.headers.getSetCookie().some((c) => c.startsWith(`${SESSION_COOKIE}=;`) || /aron_sess=;?.*(Max-Age=0|Expires=Thu, 01 Jan 1970)/i.test(c))).toBe(true);
    const live = proxy(req("/", sess("TSO", { sat: Date.now(), act: Date.now() - 5 * MIN })));
    expect(live.headers.get("location")).toBeNull();
    expect(live.headers.getSetCookie().some((c) => c.startsWith(`${SESSION_COOKIE}=`) && !/Max-Age=0/.test(c))).toBe(true);
  });
  it("an API call with an idle session gets 401, not data", () => {
    const res = proxy(req("/api/bff/maps/load", sess("ADMIN", { sat: Date.now(), act: Date.now() - 31 * MIN })));
    expect(res.status).toBe(401);
  });
  it("an admin session older than 7 days is ended even if it was just used", () => {
    const res = proxy(req("/admin", sess("ADMIN", { sat: Date.now() - 8 * 86_400_000, act: Date.now() })));
    expect(res.headers.get("location")).toContain("/login");
  });
});
