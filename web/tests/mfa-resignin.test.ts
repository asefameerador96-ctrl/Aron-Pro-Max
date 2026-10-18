// AZ-1: a 401 ERR_AUTH_MFA_INVALID from a non-/auth call is shown as "sign in again with your authenticator code", not as a wrong code.
import { afterEach, describe, expect, it, vi } from "vitest";
import { rawRequest } from "@/lib/api/raw";
import { relabelMfa, WEB_MFA_RESIGNIN } from "@/lib/api/client";
import { problemMessage } from "@/lib/i18n";

const problem = { type: "t", title: "x", status: 401, code: "ERR_AUTH_MFA_INVALID" } as never;

afterEach(() => vi.unstubAllGlobals());

describe("MFA re-sign-in", () => {
  it("relabels only a 401 on a non-/auth path", () => {
    expect(relabelMfa(401, "/v1/admin/config/keys", problem).code).toBe(WEB_MFA_RESIGNIN);
    expect(relabelMfa(401, "/v1/auth/mfa/verify", problem).code).toBe("ERR_AUTH_MFA_INVALID");
    expect(relabelMfa(403, "/v1/admin/x", problem).code).toBe("ERR_AUTH_MFA_INVALID");
  });
  it("raw requests carry the web code on a write", async () => {
    vi.stubGlobal("fetch", async () => new Response(JSON.stringify(problem), { status: 401, headers: { "x-aron-api": "1", "content-type": "application/json" } }));
    const r = await rawRequest({ method: "PATCH", path: "/v1/admin/clusters/1", token: "t", body: {} });
    expect(r.ok).toBe(false);
    if (!r.ok) expect(r.problem.code).toBe(WEB_MFA_RESIGNIN);
  });
  it("has its own text in both languages; the real step keeps 'not right'", () => {
    expect(problemMessage("en", WEB_MFA_RESIGNIN)).toBe("Sign in again with your authenticator code.");
    expect(problemMessage("bn", WEB_MFA_RESIGNIN)).toMatch(/সাইন ইন/);
    expect(problemMessage("en", "ERR_AUTH_MFA_INVALID")).toMatch(/not right/);
  });
});
