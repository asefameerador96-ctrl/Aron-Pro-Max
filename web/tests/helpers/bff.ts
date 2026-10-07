// Shared setup of the BFF integration tests: an in-process contract mock, and signed-in cookie jars per mock user.
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach } from "vitest";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { POST as mfaPost } from "@/app/api/bff/mfa/verify/route";
import { POST as createPost } from "@/app/api/bff/admin/[entity]/route";
import { PATCH as updatePatch } from "@/app/api/bff/admin/[entity]/[id]/route";
import { POST as actionPost } from "@/app/api/bff/admin/[entity]/[id]/[action]/route";
import { MFA_COOKIE, RT_COOKIE, SESSION_COOKIE } from "@/lib/auth/cookies";
import { createMock } from "../../mock/server";

export const ORIGIN = "http://localhost:3000";

export function req(path: string, method: string, body: unknown, c: Record<string, string> = {}): NextRequest {
  return new NextRequest(`${ORIGIN}${path}`, {
    method,
    body: body === undefined ? undefined : JSON.stringify(body),
    headers: { "content-type": "application/json", host: "localhost:3000", origin: ORIGIN, cookie: Object.entries(c).map(([k, v]) => `${k}=${v}`).join("; ") },
  });
}
export const jar = (res: Response): Record<string, string> => Object.fromEntries(res.headers.getSetCookie().map((l) => [l.split("=")[0]!, l.split(";")[0]!.split("=").slice(1).join("=")]));

const PASSWORDS: Record<string, string> = { madmin1: "admin-pass-1", msupport1: "support-pass-1", tso334: "tso-pass-1", mtso1: "tso-pass-1", dmo1: "dmo-pass-1" };

/** Starts the mock for the file; returns helpers. Call once at file level. */
export function setupMock() {
  const mock = createMock();
  beforeAll(async () => {
    await new Promise<void>((r) => mock.server.listen(0, "127.0.0.1", r));
    process.env.ARON_API_BASE_URL = `http://127.0.0.1:${(mock.server.address() as AddressInfo).port}`;
    process.env.ARON_COOKIE_INSECURE = "1";
  });
  afterAll(() => new Promise<void>((r) => mock.server.close(() => r())));
  beforeEach(() => mock.reset());

  async function signIn(username: keyof typeof PASSWORDS & string): Promise<Record<string, string>> {
    const c1 = jar(await loginPost(req("/api/bff/login", "POST", { username, password: PASSWORDS[username] })));
    if (c1[SESSION_COOKIE]) return { [SESSION_COOKIE]: c1[SESSION_COOKIE]!, [RT_COOKIE]: c1[RT_COOKIE]! };
    const c2 = jar(await mfaPost(req("/api/bff/mfa/verify", "POST", { code: "123456" }, { [MFA_COOKIE]: c1[MFA_COOKIE]! })));
    return { [SESSION_COOKIE]: c2[SESSION_COOKIE]!, [RT_COOKIE]: c2[RT_COOKIE]! };
  }
  const create = (entity: string, body: unknown, c: Record<string, string>) => createPost(req(`/api/bff/admin/${entity}`, "POST", body, c), { params: Promise.resolve({ entity }) });
  const update = (entity: string, id: string, body: unknown, c: Record<string, string>) => updatePatch(req(`/api/bff/admin/${entity}/${id}`, "PATCH", body, c), { params: Promise.resolve({ entity, id }) });
  const act = (entity: string, id: string, action: string, body: unknown, c: Record<string, string>) => actionPost(req(`/api/bff/admin/${entity}/${id}/${action}`, "POST", body, c), { params: Promise.resolve({ entity, id, action }) });
  const token = () => [...mock.state.access.keys()].pop()!;
  return { mock, signIn, create, update, act, token };
}
