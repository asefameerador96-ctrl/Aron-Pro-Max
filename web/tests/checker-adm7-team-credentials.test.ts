// Checker (T1) for F-TSO-023: team password reset and unlock (docs/24 s8.5, D24-81). Failing tests are confirmed defects;
// the "holds" block documents attacks that were tried and refused.
import { describe, expect, it } from "vitest";
import { POST as opPost } from "@/app/api/bff/master-op/route";
import { ORIGIN, req, setupMock } from "./helpers/bff";
import { NextRequest } from "next/server";

const { mock, signIn } = setupMock();
const callOp = (op: string, args: Record<string, unknown>, c: Record<string, string>) => opPost(req("/api/bff/master-op", "POST", { op, ...args }, c));
const REASON = "Phone was replaced, password forgotten";

describe("F-TSO-023 checker: defects", () => {
  // docs/24 s8.5: only SUPERADMIN writes ADMIN-role users (the user-scope.put rule in the same file enforces it; the API does
  // too, AdminUsers.kt "only a SUPERADMIN acts on ADMIN and SUPERADMIN users"). The BFF lets SUPPORT and ADMIN through.
  it("SUPPORT cannot obtain a temporary password for an ADMIN user through the BFF", async () => {
    const c = await signIn("msupport1");
    const n = mock.state.audit.length;
    const r = await callOp("credential.manage", { params: { id: 3001 }, body: { action: "reset_password" }, reason: REASON }, c);
    const text = await r.text();
    expect(r.status).not.toBe(200);
    expect(text).not.toContain("temporary_password\":\"");
    expect(mock.state.audit.length).toBe(n);
  });
  it("an ADMIN cannot obtain a temporary password for another ADMIN through the BFF", async () => {
    const c = await signIn("madmin1");
    const r = await callOp("credential.manage", { params: { id: 3001 }, body: { action: "reset_password" }, reason: REASON }, c);
    expect(r.status).not.toBe(200);
  });

  // Contract IdPath is an integer >= 1; the BFF forwards a non-canonical "01001" which then acts on (and audits) user 1001.
  it("a non-canonical id (leading zeros) is refused with 400 before the API is called", async () => {
    const c = await signIn("mtso1");
    const n = mock.state.audit.length;
    const r = await callOp("credential.manage", { params: { id: "01001" }, body: { action: "unlock" }, reason: REASON }, c);
    expect(r.status).toBe(400);
    expect(mock.state.audit.length).toBe(n);
  });

  // The mandatory reason can be satisfied by ten invisible characters (U+200B survives trim() in JS and Kotlin).
  it("a reason made only of zero-width characters is refused", async () => {
    const c = await signIn("mtso1");
    const r = await callOp("credential.manage", { params: { id: 1001 }, body: { action: "unlock" }, reason: "​".repeat(10) }, c);
    expect(r.status).toBe(400);
  });

  // The TSO target lookup turns any non-404 API failure (outage, 401, 403) into 400 ERR_VALIDATION /params/id "invalid":
  // the operator is told the input is wrong instead of "try again"; a SUPPORT caller gets the honest 503 on the same outage.
  it("an API outage during the TSO target check answers 503 retryable, not a validation error", async () => {
    const c = await signIn("mtso1");
    const keep = process.env.ARON_API_BASE_URL;
    process.env.ARON_API_BASE_URL = "http://127.0.0.1:1";
    try {
      const r = await callOp("credential.manage", { params: { id: 1001 }, body: { action: "unlock" }, reason: REASON }, c);
      expect(r.status).toBe(503);
    } finally {
      process.env.ARON_API_BASE_URL = keep;
    }
  });

  // The row and the team page promise "valid for 24 hours" (en and bn team.reset_note; cfg.auth.temp_password_ttl_h = 24 in
  // docs/19 and docs/21 s144; T-0-79). The real API issues 72-hour temporary passwords; only the mock says 24 hours.
  // The backend TTL (72 h against the 24 h of the spec) is the backend lane's: docs/requests/web-admin-temp-password-ttl.md.
});

describe("F-TSO-023 checker: attacks that hold", () => {
  it("unauthenticated is 401, DMO 403, cross-site 403", async () => {
    expect((await callOp("credential.manage", { params: { id: 1001 }, body: { action: "unlock" }, reason: REASON }, {})).status).toBe(401);
    const dmo = await signIn("dmo1");
    expect((await callOp("credential.manage", { params: { id: 1001 }, body: { action: "unlock" }, reason: REASON }, dmo)).status).toBe(403);
    const c = await signIn("mtso1");
    const x = new NextRequest(`${ORIGIN}/api/bff/master-op`, {
      method: "POST",
      body: JSON.stringify({ op: "credential.manage", params: { id: 1001 }, body: { action: "unlock" }, reason: REASON }),
      headers: { "content-type": "application/json", host: "localhost:3000", origin: "https://evil.example", "sec-fetch-site": "cross-site", cookie: Object.entries(c).map(([k, v]) => `${k}=${v}`).join("; ") },
    });
    expect((await opPost(x)).status).toBe(403);
  });
  it("path tricks, odd id types and prototype keys never reach a write", async () => {
    const c = await signIn("mtso1");
    const n = mock.state.audit.length;
    for (const id of ["1001/../2001", "1001%2F..%2F2001", "١٠٠١", "1e3", 1001.5, [1001], "-1", ".", "..", "", null, { a: 1 }]) {
      const r = await callOp("credential.manage", { params: { id }, body: { action: "reset_password" }, reason: REASON }, c);
      expect([400, 404]).toContain(r.status);
    }
    for (const body of [{ action: "reset_password", __proto__x: 1 }, JSON.parse('{"action":"unlock","__proto__":{"admin":true}}'), { action: ["unlock"] }, { action: "UNLOCK" }])
      expect((await callOp("credential.manage", { params: { id: 1001 }, body, reason: REASON }, c)).status).toBe(400);
    for (const id of [2001, 3001, 1003]) expect((await callOp("credential.manage", { params: { id }, body: { action: "reset_password" }, reason: REASON }, c)).status).toBe(404);
    expect(mock.state.audit.length).toBe(n);
  });
  it("the reason is trimmed and stored; the temporary password is not in the audit row", async () => {
    const c = await signIn("mtso1");
    const r = await callOp("credential.manage", { params: { id: 1001 }, body: { action: "reset_password" }, reason: `   ${REASON}   ` }, c);
    expect(r.status).toBe(200);
    const pw = ((await r.json()) as { data: { temporary_password: string } }).data.temporary_password;
    const row = mock.state.audit.at(-1)!;
    expect(row).toMatchObject({ reason: REASON });
    expect(JSON.stringify(row)).not.toContain(pw);
  });
});
