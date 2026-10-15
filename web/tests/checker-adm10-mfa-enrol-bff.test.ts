// CHECKER ADM-10 (T1, security) for F-WEB-043b: POST /api/bff/mfa/enrol at the login's TOTP step.
// Tries to break the purpose binding of the sealed cookies, replay, CSRF, method confusion, leaks and the proxy headers,
// and checks the mock against the backend's enrolment semantics (LoginService.enrolMfa + JdbiMfaStore.enrol).
import { createServer, type Server } from "node:http";
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import { POST as loginPost } from "@/app/api/bff/login/route";
import * as enrolRoute from "@/app/api/bff/mfa/enrol/route";
import { POST as verifyPost } from "@/app/api/bff/mfa/verify/route";
import { MFA_COOKIE, MFA_PURPOSE, PWC_COOKIE, PWC_PURPOSE, RT_COOKIE, SESSION_COOKIE } from "@/lib/auth/cookies";
import { seal } from "@/lib/auth/seal";
import { proxy } from "@/proxy";
import { REQUIRED_HEADERS } from "@/lib/security-headers";
import { jar, ORIGIN, req, setupMock } from "./helpers/bff";

const enrolPost = enrolRoute.POST;
const { mock, signIn } = setupMock();
const login = async (username: string, password: string) => jar(await loginPost(req("/api/bff/login", "POST", { username, password })));
const enrol = (c: Record<string, string> = {}) => enrolPost(req("/api/bff/mfa/enrol", "POST", undefined, c));
const SECRET = "JBSWY3DPEHPK3PXP";

async function noSecretIn(res: Response) {
  const text = await res.clone().text();
  expect(text).not.toContain("otpauth");
  expect(text).not.toContain(SECRET);
  expect(text).not.toMatch(/recovery_codes/);
}

describe("ADM-10 held: purpose binding, replay, CSRF, method, leaks", () => {
  it("the session, refresh and password-change cookies cannot stand in for the mfa cookie", async () => {
    const s = await signIn("madmin1");
    for (const v of [s[SESSION_COOKIE]!, s[RT_COOKIE]!, seal({ token: "x", remember: false }, PWC_PURPOSE, 600), seal({ mfaToken: "x" }, PWC_PURPOSE, 600)]) {
      const res = await enrol({ [MFA_COOKIE]: v });
      expect(res.status).toBe(401);
      await noSecretIn(res);
    }
    // and a valid mfa cookie under the session/pwc cookie names is ignored
    const c = await login("newadmin1", "newadmin-pass-1");
    expect((await enrol({ [SESSION_COOKIE]: c[MFA_COOKIE]!, [PWC_COOKIE]: c[MFA_COOKIE]! })).status).toBe(401);
    expect(mock.state.mfaEnrolled.has("newadmin1")).toBe(false);
  });

  it("an expired sealed mfa cookie, a forged one and a spent mfa_token are refused without a secret", async () => {
    const c = await login("newadmin1", "newadmin-pass-1");
    const tok = [...mock.state.mfaTokens.keys()].pop()!;
    expect((await enrol({ [MFA_COOKIE]: seal({ mfaToken: tok }, MFA_PURPOSE, -1) })).status).toBe(401);
    expect((await enrol({ [MFA_COOKIE]: seal({ mfaToken: tok }, MFA_PURPOSE, 60, "another-secret-0123456789-0123456789") })).status).toBe(401);
    const flipped = c[MFA_COOKIE]!.slice(0, -2) + (c[MFA_COOKIE]!.endsWith("A") ? "BB" : "AA");
    expect((await enrol({ [MFA_COOKIE]: flipped })).status).toBe(401);
    // spend it at verify (mock: newadmin1 accepts 123456 even before enrolment), then replay the cookie at enrol
    expect((await verifyPost(req("/api/bff/mfa/verify", "POST", { code: "123456" }, { [MFA_COOKIE]: c[MFA_COOKIE]! }))).status).toBe(200);
    const replay = await enrol({ [MFA_COOKIE]: c[MFA_COOKIE]! });
    expect(replay.status).toBe(401);
    await noSecretIn(replay);
  });

  it("an enrolled administrator gets a 409 with no secret, no rotation, no cookies", async () => {
    const c = await login("madmin1", "admin-pass-1");
    const res = await enrol({ [MFA_COOKIE]: c[MFA_COOKIE]! });
    expect(res.status).toBe(409);
    await noSecretIn(res);
    expect(res.headers.getSetCookie()).toEqual([]);
  });

  it("CSRF: same-site, cross-site, a foreign or opaque Origin and a forwarded-host mismatch are 403", async () => {
    const c = await login("newadmin1", "newadmin-pass-1");
    const mk = (h: Record<string, string>) => new NextRequest(`${ORIGIN}/api/bff/mfa/enrol`, { method: "POST", headers: { host: "localhost:3000", cookie: `${MFA_COOKIE}=${c[MFA_COOKIE]!}`, ...h } });
    for (const h of <Record<string, string>[]>[
      { "sec-fetch-site": "same-site", origin: "http://sub.localhost:3000" },
      { "sec-fetch-site": "cross-site" },
      { origin: "https://evil.example" },
      { origin: "null" },
      { origin: "http://localhost:3000", "x-forwarded-host": "evil.example" },
    ]) {
      const res = await enrolPost(mk(h));
      expect(res.status, JSON.stringify(h)).toBe(403);
      await noSecretIn(res);
    }
    expect(mock.state.mfaEnrolled.has("newadmin1")).toBe(false);
  });

  it("only POST is exported (GET/PUT get Next's 405); the success body carries exactly the two fields, no-store, no cookies, no secret in headers", async () => {
    expect(Object.keys(enrolRoute).filter((k) => /^[A-Z]+$/.test(k))).toEqual(["POST"]);
    const c = await login("newadmin1", "newadmin-pass-1");
    const res = await enrol({ [MFA_COOKIE]: c[MFA_COOKIE]! });
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(res.headers.getSetCookie()).toEqual([]);
    for (const [, v] of res.headers) expect(v).not.toContain(SECRET);
    expect(Object.keys((await res.json()) as object).sort()).toEqual(["otpauth_uri", "recovery_codes"]);
  });

  it("the proxy lets the route through without a session and stamps the security headers (no-store) on it", () => {
    const r = proxy(new NextRequest(`${ORIGIN}/api/bff/mfa/enrol`, { method: "POST", headers: { host: "localhost:3000" } }));
    expect(r.headers.get("x-middleware-next")).toBe("1");
    // CSP is left to the handler on API pass-through by design (proxy.ts); the rest must be stamped.
    for (const h of REQUIRED_HEADERS.filter((x) => x !== "Content-Security-Policy")) expect(r.headers.get(h), h).toBeTruthy();
    expect(r.headers.get("cache-control")).toContain("no-store");
  });
});

describe("ADM-10 defects", () => {
  it("DEFECT: the mock refuses a second enrolment before confirmation, but the backend replaces an unconfirmed one (200, new secret)", async () => {
    // JdbiMfaStore.enrol: "Stores a new, unconfirmed enrolment (replacing an unconfirmed one); false when a confirmed one exists."
    // A first admin whose 5-minute mfa_token ran out while copying the recovery codes signs in again and sets up again:
    // production answers 200; the mock (and tests/mfa-enrol.test.ts, and the route's doc comment) say 409.
    const c1 = await login("newadmin1", "newadmin-pass-1");
    expect((await enrol({ [MFA_COOKIE]: c1[MFA_COOKIE]! })).status).toBe(200);
    const c2 = await login("newadmin1", "newadmin-pass-1");
    expect((await enrol({ [MFA_COOKIE]: c2[MFA_COOKIE]! })).status).toBe(200);
  });

  it("DEFECT (low): a 204 from the API makes the route throw (NextResponse.json with status 204 -> bare 500) instead of a problem response", async () => {
    const srv: Server = createServer((_q, s) => {
      s.writeHead(204);
      s.end();
    });
    await new Promise<void>((r) => srv.listen(0, "127.0.0.1", r));
    const prev = process.env.ARON_API_BASE_URL;
    process.env.ARON_API_BASE_URL = `http://127.0.0.1:${(srv.address() as AddressInfo).port}`;
    try {
      const res = await enrol({ [MFA_COOKIE]: seal({ mfaToken: "mfa.x" }, MFA_PURPOSE, 60) }).catch((e: unknown) => e);
      expect(res).toBeInstanceOf(Response);
      expect((res as Response).headers.get("content-type")).toContain("application/problem+json");
    } finally {
      process.env.ARON_API_BASE_URL = prev;
      await new Promise<void>((r) => srv.close(() => r()));
    }
  });
});
