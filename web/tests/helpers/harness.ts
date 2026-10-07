// Test harness of the web-config lane: the contract mock plus per-test stubbed endpoints, real BFF handlers, real cookies.
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach } from "vitest";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { POST as mfaPost } from "@/app/api/bff/mfa/verify/route";
import { POST as opPost } from "@/app/api/bff/admin-op/route";
import { readSession } from "@/lib/auth/session";
import { MFA_COOKIE, RT_COOKIE, SESSION_COOKIE } from "@/lib/auth/cookies";
import { createMock, type Stub } from "../../mock/server";

export const ORIGIN = "http://localhost:3000";

export function setupMock() {
  const mock = createMock({ accessTtlS: 900 });
  beforeAll(async () => {
    await new Promise<void>((r) => mock.server.listen(0, "127.0.0.1", r));
    process.env.ARON_API_BASE_URL = `http://127.0.0.1:${(mock.server.address() as AddressInfo).port}`;
    process.env.ARON_COOKIE_INSECURE = "1";
    process.env.ARON_SESSION_SECRET ??= "test-secret-test-secret-test-secret-123";
  });
  afterAll(() => new Promise<void>((r) => mock.server.close(() => r())));
  beforeEach(() => mock.reset());
  return {
    mock,
    stub: (s: Stub) => void mock.state.stubs.push(s),
    calls: () => mock.state.calls,
  };
}

export function req(path: string, method: string, body: unknown, cookies: Record<string, string> = {}): NextRequest {
  const cookie = Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join("; ");
  return new NextRequest(`${ORIGIN}${path}`, { method, body: body === undefined ? undefined : JSON.stringify(body), headers: { "content-type": "application/json", host: "localhost:3000", origin: ORIGIN, ...(cookie ? { cookie } : {}) } });
}

const setCookies = (res: Response) => Object.fromEntries(res.headers.getSetCookie().map((l) => [l.split("=")[0]!, l.split(";")[0]!.split("=").slice(1).join("=")]));

export async function signIn(username: string, password: string, mfa = false): Promise<Record<string, string>> {
  const c1 = setCookies(await loginPost(req("/api/bff/login", "POST", { username, password })));
  const c = mfa ? setCookies(await mfaPost(req("/api/bff/mfa/verify", "POST", { code: "123456" }, { [MFA_COOKIE]: c1[MFA_COOKIE]! }))) : c1;
  return { [SESSION_COOKIE]: c[SESSION_COOKIE]!, [RT_COOKIE]: c[RT_COOKIE]! };
}
export const admin = () => signIn("admin1", "admin-pass-1", true);
export const support = () => signIn("support1", "support-pass-1", true);
export const tso = () => signIn("tso334", "tso-pass-1");

export function op(cookies: Record<string, string>, body: Record<string, unknown>) {
  return opPost(req("/api/bff/admin-op", "POST", body, cookies));
}

/** The API access token inside a signed-in cookie jar (what a server page passes to the loaders). */
export function token(cookies: Record<string, string>): string {
  return readSession(cookies[SESSION_COOKIE])!.at;
}
