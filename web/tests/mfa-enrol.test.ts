import { NextRequest } from "next/server";
import { describe, expect, it } from "vitest";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { POST as enrolPost } from "@/app/api/bff/mfa/enrol/route";
import { POST as verifyPost } from "@/app/api/bff/mfa/verify/route";
import { MFA_COOKIE } from "@/lib/auth/cookies";
import { jar, ORIGIN, req, setupMock } from "./helpers/bff";

setupMock();
const login = async (username: string, password: string) => jar(await loginPost(req("/api/bff/login", "POST", { username, password })));

describe("authenticator setup at the TOTP step (first admin sign-in)", () => {
  it("a new administrator enrols with the login's mfa cookie: URI and ten recovery codes, no-store, then the first code signs in", async () => {
    const c = await login("newadmin1", "newadmin-pass-1");
    expect(c[MFA_COOKIE]).toBeTruthy();
    const res = await enrolPost(req("/api/bff/mfa/enrol", "POST", undefined, { [MFA_COOKIE]: c[MFA_COOKIE]! }));
    expect(res.status).toBe(200);
    expect(res.headers.get("cache-control")).toBe("no-store");
    const body = (await res.json()) as { otpauth_uri: string; recovery_codes: string[] };
    expect(body.otpauth_uri).toMatch(/^otpauth:\/\/totp\//);
    expect(body.recovery_codes).toHaveLength(10);
    const ok = await verifyPost(req("/api/bff/mfa/verify", "POST", { code: "123456" }, { [MFA_COOKIE]: c[MFA_COOKIE]! }));
    expect(ok.status).toBe(200);
  });
  it("a second enrolment is a 409 (an administrator resets it); no mfa cookie is 401; a cross-site call is 403", async () => {
    const c = await login("newadmin1", "newadmin-pass-1");
    expect((await enrolPost(req("/api/bff/mfa/enrol", "POST", undefined, { [MFA_COOKIE]: c[MFA_COOKIE]! }))).status).toBe(200);
    const c2 = await login("newadmin1", "newadmin-pass-1");
    expect((await enrolPost(req("/api/bff/mfa/enrol", "POST", undefined, { [MFA_COOKIE]: c2[MFA_COOKIE]! }))).status).toBe(409);
    expect((await enrolPost(req("/api/bff/mfa/enrol", "POST", undefined))).status).toBe(401);
    const cross = new NextRequest(`${ORIGIN}/api/bff/mfa/enrol`, { method: "POST", headers: { host: "localhost:3000", origin: "https://evil.example", "sec-fetch-site": "cross-site", cookie: `${MFA_COOKIE}=${c2[MFA_COOKIE]!}` } });
    expect((await enrolPost(cross)).status).toBe(403);
  });
  it("an already enrolled administrator gets the conflict, never a new secret", async () => {
    const c = await login("madmin1", "admin-pass-1");
    expect((await enrolPost(req("/api/bff/mfa/enrol", "POST", undefined, { [MFA_COOKIE]: c[MFA_COOKIE]! }))).status).toBe(409);
  });
});
