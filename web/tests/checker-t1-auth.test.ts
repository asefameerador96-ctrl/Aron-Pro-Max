// Independent checker (docs/26 s4), F-WEB-043 web login and session. Failing tests here are evidence of defects.
import { readFileSync } from "node:fs";
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { POST as mfaPost } from "@/app/api/bff/mfa/verify/route";
import { GET as refreshGet } from "@/app/api/bff/session/refresh/route";
import { safeNext } from "@/lib/api/origin";
import { MFA_COOKIE, RT_COOKIE, SESSION_COOKIE } from "@/lib/auth/cookies";
import { readSession } from "@/lib/auth/session";
import { createMock } from "../mock/server";

const mock = createMock({ accessTtlS: 900 });
beforeAll(async () => {
  await new Promise<void>((r) => mock.server.listen(0, "127.0.0.1", r));
  process.env.ARON_API_BASE_URL = `http://127.0.0.1:${(mock.server.address() as AddressInfo).port}`;
  process.env.ARON_COOKIE_INSECURE = "1";
});
afterAll(() => new Promise<void>((r) => mock.server.close(() => r())));
beforeEach(() => mock.reset());

const ORIGIN = "http://localhost:3000";
function req(path: string, method: string, body: unknown, cookies: Record<string, string> = {}): NextRequest {
  const cookie = Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join("; ");
  return new NextRequest(`${ORIGIN}${path}`, { method, body: body === undefined ? undefined : JSON.stringify(body), headers: { "content-type": "application/json", host: "localhost:3000", origin: ORIGIN, ...(cookie ? { cookie } : {}) } });
}
const lines = (res: Response) => Object.fromEntries(res.headers.getSetCookie().map((l) => [l.split("=")[0]!, l]));
const val = (line: string | undefined) => (line ? line.split(";")[0]!.split("=").slice(1).join("=") : "");
const maxAge = (line: string | undefined) => Number(/Max-Age=(\d+)/i.exec(line ?? "")?.[1] ?? NaN);

describe("F-WEB-043 open redirect on next", () => {
  it("safeNext never returns a protocol-relative path (dot segments collapse to //host)", () => {
    for (const raw of ["/.//evil.example", "/x/..//evil.example", "/%2e%2e//evil.example"]) {
      expect(safeNext(raw), raw).not.toMatch(/^\/\//);
    }
  });
  it("the refresh route does not redirect off-site after a successful refresh", async () => {
    const c = lines(await loginPost(req("/api/bff/login", "POST", { username: "tso334", password: "tso-pass-1" })));
    const res = await refreshGet(req("/api/bff/session/refresh?next=" + encodeURIComponent("/.//evil.example/x"), "GET", undefined, { [SESSION_COOKIE]: val(c[SESSION_COOKIE]), [RT_COOKIE]: val(c[RT_COOKIE]) }));
    expect(res.status).toBe(307);
    expect(new URL(res.headers.get("location")!).host).toBe("localhost:3000");
  });
  it("(holds) plain //host, backslash and control characters are refused", () => {
    for (const raw of ["//evil.example", "/\\evil.example", "/\t/evil.example", "https://evil.example"]) expect(safeNext(raw), raw).toBe("/");
  });
});

describe("F-WEB-043 MFA and Remember me", () => {
  it("(holds) an MFA role gets no session from the password step even with remember: only the mfa cookie", async () => {
    const res = await loginPost(req("/api/bff/login", "POST", { username: "ADMIN1", password: "admin-pass-1", remember: true }));
    const c = lines(res);
    expect(await res.json()).toEqual({ status: "mfa_required" });
    expect(c[SESSION_COOKIE]).toBeUndefined();
    expect(c[RT_COOKIE]).toBeUndefined();
    expect(c[MFA_COOKIE]).toMatch(/HttpOnly/i);
  });
  it("(holds) the mfa cookie cannot be replayed after a successful verify, and the verified admin session is never persistent", async () => {
    const mfa = val(lines(await loginPost(req("/api/bff/login", "POST", { username: "admin1", password: "admin-pass-1", remember: true })))[MFA_COOKIE]);
    const ok = await mfaPost(req("/api/bff/mfa/verify", "POST", { code: "123456" }, { [MFA_COOKIE]: mfa }));
    expect(ok.status).toBe(200);
    const c = lines(ok);
    expect(c[SESSION_COOKIE]).not.toMatch(/Max-Age/i);
    expect(readSession(val(c[SESSION_COOKIE]))?.rem).toBeUndefined();
    expect(JSON.stringify(await ok.json())).not.toMatch(/access_token|refresh_token|mfa_token/);
    const replay = await mfaPost(req("/api/bff/mfa/verify", "POST", { code: "123456" }, { [MFA_COOKIE]: mfa }));
    expect(replay.status).toBe(401);
    expect(lines(replay)[SESSION_COOKIE]).toBeUndefined();
  });
  it("(holds) a forged session cookie of another purpose (the mfa cookie) is not accepted as a session", async () => {
    const mfa = val(lines(await loginPost(req("/api/bff/login", "POST", { username: "admin1", password: "admin-pass-1" })))[MFA_COOKIE]);
    expect(readSession(mfa)).toBeNull();
  });
  it("a refreshed session whose role is now an MFA role must not stay persistent (Remember me is never for admin roles)", async () => {
    const c = lines(await loginPost(req("/api/bff/login", "POST", { username: "tso334", password: "tso-pass-1", remember: true })));
    expect(maxAge(c[SESSION_COOKIE])).toBeGreaterThan(0);
    mock.state.users.tso334!.summary.role = "ADMIN"; // promoted mid-session
    const res = await refreshGet(req("/api/bff/session/refresh?next=/", "GET", undefined, { [SESSION_COOKIE]: val(c[SESSION_COOKIE]), [RT_COOKIE]: val(c[RT_COOKIE]) }));
    // Promotion to an MFA role mid-session: no admin session without TOTP; back to /login with the cookies cleared.
    expect(res.headers.get("location")).toContain("/login");
    const r = lines(res);
    expect(r[SESSION_COOKIE] ?? "").not.toMatch(/aron_sess=[^;]+;.*Max-Age=[1-9]/i);
    expect(r[SESSION_COOKIE] ? readSession(val(r[SESSION_COOKIE])) : null).toBeNull();
  });
  it("Remember me lasts at most 30 days (docs/21: cfg.auth.web_remember_me_days 0..30)", async () => {
    const c = lines(await loginPost(req("/api/bff/login", "POST", { username: "tso334", password: "tso-pass-1", remember: true })));
    expect(maxAge(c[SESSION_COOKIE])).toBeLessThanOrEqual(30 * 86_400);
    expect(maxAge(c[RT_COOKIE])).toBeLessThanOrEqual(30 * 86_400);
  });
  it("(holds) Remember me off: browser-session cookies, HttpOnly; a refresh keeps them non-persistent", async () => {
    const c = lines(await loginPost(req("/api/bff/login", "POST", { username: "tso334", password: "tso-pass-1" })));
    for (const n of [SESSION_COOKIE, RT_COOKIE]) {
      expect(c[n]).toMatch(/HttpOnly/i);
      expect(c[n]).toMatch(/SameSite=Strict/i);
      expect(c[n]).not.toMatch(/Max-Age/i);
    }
    const r = lines(await refreshGet(req("/api/bff/session/refresh?next=/", "GET", undefined, { [SESSION_COOKIE]: val(c[SESSION_COOKIE]), [RT_COOKIE]: val(c[RT_COOKIE]) })));
    expect(r[SESSION_COOKIE]).not.toMatch(/Max-Age/i);
    expect(r[RT_COOKIE]).toMatch(/HttpOnly/i);
  });
});

describe("F-WEB-043 User ID and form", () => {
  it("(holds) User ID is case-insensitive and surrounding whitespace is ignored", async () => {
    for (const u of ["TSO334", "  Tso334\t", " tso334 "]) {
      const res = await loginPost(req("/api/bff/login", "POST", { username: u, password: "tso-pass-1" }));
      expect(res.status, JSON.stringify(u)).toBe(200);
    }
  });
  it("(holds) no forgot-password link; Remember me unchecked by default; show/hide toggle present", () => {
    const src = readFileSync(new URL("../src/components/login-form.tsx", import.meta.url), "utf8");
    expect(src).not.toMatch(/forgot/i);
    expect(src).toMatch(/name="remember" defaultChecked=\{false\}/);
    expect(src).toMatch(/type=\{show \? "text" : "password"\}/);
  });
});
