// Checker N-011: reason/version handling of the admin BFF against the contract (ChangeReason: 10..500 code points; IfMatch: ^"[0-9]{1,10}"$).
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { POST as mfaPost } from "@/app/api/bff/mfa/verify/route";
import { PATCH as updatePatch } from "@/app/api/bff/admin/[entity]/[id]/route";
import { MFA_COOKIE, RT_COOKIE, SESSION_COOKIE } from "@/lib/auth/cookies";
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
  return new NextRequest(`${ORIGIN}${path}`, { method, body: JSON.stringify(body), headers: { "content-type": "application/json", host: "localhost:3000", origin: ORIGIN, ...(cookie ? { cookie } : {}) } });
}
const setCookies = (res: Response) => Object.fromEntries(res.headers.getSetCookie().map((l) => [l.split("=")[0]!, l.split(";")[0]!.split("=").slice(1).join("=")]));
async function admin(): Promise<Record<string, string>> {
  const c1 = setCookies(await loginPost(req("/api/bff/login", "POST", { username: "admin1", password: "admin-pass-1" })));
  const c2 = setCookies(await mfaPost(req("/api/bff/mfa/verify", "POST", { code: "123456" }, { [MFA_COOKIE]: c1[MFA_COOKIE]! })));
  return { [SESSION_COOKIE]: c2[SESSION_COOKIE]!, [RT_COOKIE]: c2[RT_COOKIE]! };
}
const idp = (id: string) => ({ params: Promise.resolve({ entity: "clusters", id }) });

describe("ChangeReason counts code points (JSON Schema), not UTF-16 units", () => {
  it("5 emoji (5 code points, JS length 10) is too short and must be refused by the BFF", async () => {
    const cookies = await admin();
    const reason = "😀".repeat(5);
    expect(reason.length).toBe(10);
    const res = await updatePatch(req("/api/bff/admin/clusters/3", "PATCH", { values: { name: "Emoji" }, reason, version: 1 }, cookies), idp("3"));
    expect(res.status).toBe(400);
    expect(mock.state.audit).toHaveLength(0);
  });

  it("251 emoji (251 code points, JS length 502) is valid per the contract and must be accepted", async () => {
    const cookies = await admin();
    const reason = "😀".repeat(251);
    expect(reason.length).toBe(502);
    const res = await updatePatch(req("/api/bff/admin/clusters/3", "PATCH", { values: { name: "Emoji2" }, reason, version: 1 }, cookies), idp("3"));
    expect(res.status).toBe(200);
  });
});

describe("If-Match must match ^\"[0-9]{1,10}\"$; anything else is refused before the API", () => {
  for (const version of [-1, 1.5, 0, 12345678901, 1e21]) {
    it(`version ${version} -> 400`, async () => {
      const cookies = await admin();
      const res = await updatePatch(req("/api/bff/admin/clusters/3", "PATCH", { values: { name: "V" }, reason: "valid reason here", version }, cookies), idp("3"));
      expect(res.status).toBe(400);
    });
  }
});
