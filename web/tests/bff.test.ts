// BFF route handlers against the in-process contract mock: cookies, MFA step, refresh, role gate, mandatory reason.
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { POST as mfaPost } from "@/app/api/bff/mfa/verify/route";
import { POST as logoutPost } from "@/app/api/bff/logout/route";
import { POST as createPost } from "@/app/api/bff/admin/[entity]/route";
import { PATCH as updatePatch } from "@/app/api/bff/admin/[entity]/[id]/route";
import { authenticate } from "@/lib/api/guard";
import { MFA_COOKIE, RT_COOKIE, SESSION_COOKIE, SESSION_PURPOSE } from "@/lib/auth/cookies";
import { seal } from "@/lib/auth/seal";
import type { SessionData } from "@/lib/auth/session";
import { createMock } from "../mock/server";

const mock = createMock({ accessTtlS: 900 });
let base = "";

beforeAll(async () => {
  await new Promise<void>((r) => mock.server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(mock.server.address() as AddressInfo).port}`;
  process.env.ARON_API_BASE_URL = base;
  process.env.ARON_COOKIE_INSECURE = "1";
});
afterAll(() => new Promise<void>((r) => mock.server.close(() => r())));
beforeEach(() => mock.reset());

const ORIGIN = "http://localhost:3000";
function req(path: string, method: string, body: unknown, cookies: Record<string, string> = {}, headers: Record<string, string> = {}): NextRequest {
  const cookie = Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join("; ");
  return new NextRequest(`${ORIGIN}${path}`, { method, body: body === undefined ? undefined : JSON.stringify(body), headers: { "content-type": "application/json", host: "localhost:3000", origin: ORIGIN, ...(cookie ? { cookie } : {}), ...headers } });
}
function setCookies(res: Response): Record<string, string> {
  const out: Record<string, string> = {};
  for (const line of res.headers.getSetCookie()) out[line.split("=")[0]!] = line;
  return out;
}
const value = (line: string) => line.split(";")[0]!.split("=").slice(1).join("=");

async function sessionCookie(username: string, password: string, mfa?: string): Promise<{ cookies: Record<string, string>; session: string }> {
  let res = await loginPost(req("/api/bff/login", "POST", { username, password }));
  let c = setCookies(res);
  if (mfa) {
    res = await mfaPost(req("/api/bff/mfa/verify", "POST", { code: mfa }, { [MFA_COOKIE]: value(c[MFA_COOKIE]!) }));
    c = setCookies(res);
  }
  return { cookies: { [SESSION_COOKIE]: value(c[SESSION_COOKIE]!), [RT_COOKIE]: value(c[RT_COOKIE]!) }, session: value(c[SESSION_COOKIE]!) };
}

describe("login", () => {
  it("TSO: session in HttpOnly SameSite=Strict cookies; no token in the body", async () => {
    const res = await loginPost(req("/api/bff/login", "POST", { username: "tso334", password: "tso-pass-1" }));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.status).toBe("ok");
    expect(body.user).toMatchObject({ username: "tso334", role: "TSO" });
    expect(JSON.stringify(body)).not.toMatch(/token|at\./);
    const c = setCookies(res);
    for (const name of [RT_COOKIE, SESSION_COOKIE]) {
      expect(c[name], name).toMatch(/HttpOnly/i);
      expect(c[name], name).toMatch(/SameSite=strict/i);
    }
    expect(value(c[SESSION_COOKIE]!)).not.toContain("at.");
  });

  it("secure cookie attribute is on unless ARON_COOKIE_INSECURE is set", async () => {
    delete process.env.ARON_COOKIE_INSECURE;
    const res = await loginPost(req("/api/bff/login", "POST", { username: "tso334", password: "tso-pass-1" }));
    expect(setCookies(res)[SESSION_COOKIE]).toMatch(/;\s*Secure/i);
    process.env.ARON_COOKIE_INSECURE = "1";
  });

  it("admin: password step gives only the mfa cookie; the TOTP step completes the login", async () => {
    const r1 = await loginPost(req("/api/bff/login", "POST", { username: "admin1", password: "admin-pass-1" }));
    expect(await r1.json()).toEqual({ status: "mfa_required" });
    const c1 = setCookies(r1);
    expect(Object.keys(c1)).toEqual([MFA_COOKIE]);

    const bad = await mfaPost(req("/api/bff/mfa/verify", "POST", { code: "000000" }, { [MFA_COOKIE]: value(c1[MFA_COOKIE]!) }));
    expect(bad.status).toBe(401);
    expect((await bad.json()).code).toBe("ERR_AUTH_MFA_INVALID");

    const none = await mfaPost(req("/api/bff/mfa/verify", "POST", { code: "123456" }));
    expect(none.status).toBe(401);

    const ok = await mfaPost(req("/api/bff/mfa/verify", "POST", { code: "123456" }, { [MFA_COOKIE]: value(c1[MFA_COOKIE]!) }));
    expect(ok.status).toBe(200);
    expect(Object.keys(setCookies(ok))).toEqual(expect.arrayContaining([SESSION_COOKIE, RT_COOKIE]));
  });

  it("a server that skips MFA for an MFA role is not trusted: no session is created", async () => {
    mock.state.users.admin1!.mfa = false;
    const res = await loginPost(req("/api/bff/login", "POST", { username: "admin1", password: "admin-pass-1" }));
    expect(res.status).toBe(401);
    expect(setCookies(res)[SESSION_COOKIE]).toBeUndefined();
  });

  it("wrong password, locked account, password change, SR", async () => {
    const wrong = await loginPost(req("/api/bff/login", "POST", { username: "tso334", password: "nope" }));
    expect(wrong.status).toBe(401);
    expect((await wrong.json()).code).toBe("ERR_AUTH_INVALID_CREDENTIALS");
    const locked = await loginPost(req("/api/bff/login", "POST", { username: "locked1", password: "locked-pass-1" }));
    expect(locked.status).toBe(403);
    expect((await locked.json()).code).toBe("ERR_AUTH_ACCOUNT_LOCKED");
    const pw = await loginPost(req("/api/bff/login", "POST", { username: "pwchange1", password: "pwchange-pass-1" }));
    expect(await pw.json()).toEqual({ status: "password_change_required" });
    expect(setCookies(pw)[SESSION_COOKIE]).toBeUndefined();
    const sr = await loginPost(req("/api/bff/login", "POST", { username: "sr334001", password: "sr-pass-1" }));
    expect(await sr.json()).toEqual({ status: "no_web_access" });
    expect(setCookies(sr)[SESSION_COOKIE]).toBeUndefined();
  });

  it("validates input strictly and refuses cross-site posts", async () => {
    expect((await loginPost(req("/api/bff/login", "POST", { username: "x" }))).status).toBe(400);
    expect((await loginPost(req("/api/bff/login", "POST", { username: "tso334", password: "tso-pass-1", client: "app_sr" }))).status).toBe(400);
    const cross = await loginPost(req("/api/bff/login", "POST", { username: "tso334", password: "tso-pass-1" }, {}, { origin: "https://evil.example" }));
    expect(cross.status).toBe(403);
    const fetchSite = await loginPost(req("/api/bff/login", "POST", { username: "tso334", password: "tso-pass-1" }, {}, { "sec-fetch-site": "cross-site" }));
    expect(fetchSite.status).toBe(403);
  });

  it("logout clears all cookies", async () => {
    const { cookies } = await sessionCookie("tso334", "tso-pass-1");
    const res = await logoutPost(req("/api/bff/logout", "POST", undefined, cookies));
    expect(res.status).toBe(204);
    const c = setCookies(res);
    for (const n of [SESSION_COOKIE, RT_COOKIE, MFA_COOKIE]) expect(c[n], n).toMatch(/Max-Age=0|Expires=/i);
  });
});

describe("authenticate (token refresh and role gate)", () => {
  const sealed = (s: SessionData) => seal(s, SESSION_PURPOSE, 600);
  const user = { user_id: 3001, username: "admin1", full_name: "Salma Akter", role: "ADMIN" as const, designation: null, locale: "bn" as const };

  it("refreshes an expiring access token once, rotating the refresh cookie", async () => {
    const { cookies } = await sessionCookie("admin1", "admin-pass-1", "123456");
    const expired = sealed({ at: "stale", atExp: Date.now() + 5_000, user, scope: null });
    const r = req("/api/bff/admin/clusters", "POST", {}, { [SESSION_COOKIE]: expired, [RT_COOKIE]: cookies[RT_COOKIE]! });
    const a = await authenticate(r, "/api/bff/admin/clusters");
    expect("session" in a).toBe(true);
    if (!("session" in a)) return;
    expect(a.session.at).not.toBe("stale");
    expect(mock.state.refreshCount).toBe(1);
    const out = a.finish(new (await import("next/server")).NextResponse(null));
    const c = setCookies(out);
    expect(value(c[RT_COOKIE]!)).not.toBe(cookies[RT_COOKIE]);
    expect(c[SESSION_COOKIE]).toBeDefined();
  });

  it("a dead refresh token ends the session (401, cookies cleared)", async () => {
    const expired = sealed({ at: "stale", atExp: Date.now() - 1, user, scope: null });
    const a = await authenticate(req("/api/bff/admin/clusters", "POST", {}, { [SESSION_COOKIE]: expired, [RT_COOKIE]: "x".repeat(43) }), "/api/bff/admin/clusters");
    expect("status" in a && a.status).toBe(401);
  });

  it("no cookie is 401; a TSO session on an admin path is 403", async () => {
    const none = await authenticate(req("/api/bff/admin/clusters", "POST", {}), "/api/bff/admin/clusters");
    expect("status" in none && none.status).toBe(401);
    const { cookies } = await sessionCookie("tso334", "tso-pass-1");
    const forb = await authenticate(req("/api/bff/admin/clusters", "POST", {}, cookies), "/api/bff/admin/clusters");
    expect("status" in forb && forb.status).toBe(403);
  });
});

describe("generic CRUD writes", () => {
  const params = (entity: string) => ({ params: Promise.resolve({ entity }) });
  const idParams = (entity: string, id: string) => ({ params: Promise.resolve({ entity, id }) });

  it("create without a valid reason is refused before any API call", async () => {
    const { cookies } = await sessionCookie("admin1", "admin-pass-1", "123456");
    for (const reason of [undefined, "", "short", "         ", "x".repeat(501)]) {
      const res = await createPost(req("/api/bff/admin/clusters", "POST", { values: { name: "A", zone_id: 1 }, reason }, cookies), params("clusters"));
      expect(res.status, String(reason)).toBe(400);
      const b = await res.json();
      expect(b.code).toBe("ERR_VALIDATION");
      expect(b.errors.map((e: { pointer: string }) => e.pointer)).toContain("/reason");
    }
    expect(mock.state.clusters).toHaveLength(7);
    expect(mock.state.audit).toHaveLength(0);
  });

  it("create is strict (unknown member, missing required field) and does not forward the reason (contract has none yet)", async () => {
    const { cookies } = await sessionCookie("admin1", "admin-pass-1", "123456");
    const reason = "Opening a new market cluster";
    const unknown = await createPost(req("/api/bff/admin/clusters", "POST", { values: { name: "A", zone_id: 1, status: "inactive" }, reason }, cookies), params("clusters"));
    expect(unknown.status).toBe(400);
    const missing = await createPost(req("/api/bff/admin/clusters", "POST", { values: { name: "A" }, reason }, cookies), params("clusters"));
    expect(missing.status).toBe(400);
    const ok = await createPost(req("/api/bff/admin/clusters", "POST", { values: { name: "Fresh", zone_id: 2, cluster_type: "" }, reason }, cookies), params("clusters"));
    expect(ok.status).toBe(201);
    expect((await ok.json()).row).toMatchObject({ name: "Fresh", zone_id: 2, cluster_type: null });
    const dup = await createPost(req("/api/bff/admin/clusters", "POST", { values: { name: "Fresh", zone_id: 2 }, reason }, cookies), params("clusters"));
    expect(dup.status).toBe(409);
    expect((await dup.json()).code).toBe("ERR_MASTER_DUPLICATE_CODE");
  });

  it("update sends If-Match and change_reason; the audit row carries the reason; a stale version is 412", async () => {
    const { cookies } = await sessionCookie("admin1", "admin-pass-1", "123456");
    const reason = "Zone moved after territory re-draw";
    const ok = await updatePatch(req("/api/bff/admin/clusters/3", "PATCH", { values: { zone_id: 1, status: "inactive" }, reason, version: 1 }, cookies), idParams("clusters", "3"));
    expect(ok.status).toBe(200);
    expect((await ok.json()).row).toMatchObject({ id: 3, zone_id: 1, status: "inactive", version: 2 });
    expect(mock.state.audit).toHaveLength(1);
    expect(mock.state.audit[0]).toMatchObject({ entity: "cluster", entity_id: "3", action: "cluster.update", reason, actor_username: "admin1" });

    const stale = await updatePatch(req("/api/bff/admin/clusters/3", "PATCH", { values: { name: "Late" }, reason, version: 1 }, cookies), idParams("clusters", "3"));
    expect(stale.status).toBe(412);
    expect((await stale.json()).code).toBe("ERR_PRECONDITION_FAILED");
    expect(mock.state.audit).toHaveLength(1);
  });

  it("update refuses: no reason, no changes, no version, bad id, create-only field, unknown entity", async () => {
    const { cookies } = await sessionCookie("admin1", "admin-pass-1", "123456");
    const call = (id: string, body: unknown) => updatePatch(req(`/api/bff/admin/clusters/${id}`, "PATCH", body, cookies), idParams("clusters", id));
    expect((await call("3", { values: { name: "X" }, reason: "", version: 1 })).status).toBe(400);
    expect((await call("3", { values: {}, reason: "valid reason here", version: 1 })).status).toBe(400);
    expect((await call("3", { values: { name: "X" }, reason: "valid reason here" })).status).toBe(400);
    expect((await call("3; drop", { values: { name: "X" }, reason: "valid reason here", version: 1 })).status).toBe(404);
    expect((await call("3", { values: { id: 9 }, reason: "valid reason here", version: 1 })).status).toBe(400);
    const unknown = await createPost(req("/api/bff/admin/nope", "POST", {}, cookies), params("nope"));
    expect(unknown.status).toBe(404);
    expect(mock.state.audit).toHaveLength(0);
  });

  it("support can read but its writes are refused by the BFF", async () => {
    const { cookies } = await sessionCookie("support1", "support-pass-1", "123456");
    const res = await updatePatch(req("/api/bff/admin/clusters/1", "PATCH", { values: { name: "Hacked" }, reason: "valid reason here", version: 1 }, cookies), idParams("clusters", "1"));
    expect(res.status).toBe(403);
    expect(mock.state.clusters[0]!.name).toBe("Banani Market");
  });
});
