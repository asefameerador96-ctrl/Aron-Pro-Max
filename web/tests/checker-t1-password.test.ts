// Independent checker (docs/26 s4), F-WEB-033 change password. Failing tests here are evidence of defects.
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { POST as pwPost } from "@/app/api/bff/password/route";
import { RT_COOKIE, SESSION_COOKIE } from "@/lib/auth/cookies";
import { checkPasswords, isValid } from "@/lib/auth/password-policy";
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
function req(path: string, body: unknown, cookies: Record<string, string> = {}, extra: Record<string, string> = {}): NextRequest {
  const cookie = Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join("; ");
  return new NextRequest(`${ORIGIN}${path}`, { method: "POST", body: JSON.stringify(body), headers: { "content-type": "application/json", host: "localhost:3000", origin: ORIGIN, ...(cookie ? { cookie } : {}), ...extra } });
}
const val = (line: string) => line.split(";")[0]!.split("=").slice(1).join("=");
async function tso(): Promise<Record<string, string>> {
  const c = Object.fromEntries((await loginPost(req("/api/bff/login", { username: "tso334", password: "tso-pass-1" }))).headers.getSetCookie().map((l) => [l.split("=")[0]!, val(l)]));
  return { [SESSION_COOKIE]: c[SESSION_COOKIE]!, [RT_COOKIE]: c[RT_COOKIE]! };
}

describe("F-WEB-033 policy", () => {
  it("12 characters means 12 characters, not 12 UTF-16 code units (emoji/astral count once)", () => {
    const pw = "Aa1" + "\u{1F600}".repeat(5); // 8 characters, 13 code units
    expect([...pw].length).toBe(8);
    expect(checkPasswords("old-pass", pw, pw).next).toBe("too_short");
  });
  it("(holds) ASCII-only classes: non-ASCII digits/letters do not satisfy upper, lower or digit", () => {
    expect(checkPasswords("x", "aaaaaaaaaaa١A", "aaaaaaaaaaa١A").next).toBe("needs_digit"); // Arabic-Indic one
    expect(checkPasswords("x", "aaaaaaaaaa1Ä", "aaaaaaaaaa1Ä").next).toBe("needs_upper");
    expect(checkPasswords("x", "AAAAAAAAAA1ä", "AAAAAAAAAA1ä").next).toBe("needs_lower");
    expect(checkPasswords("x", "১২৩AAAAAAAAAa", "১২৩AAAAAAAAAa").next).toBe("needs_digit"); // Bengali digits
  });
  it("(holds) boundaries 11/12 and 128/129; whitespace-only refused", () => {
    expect(checkPasswords("x", "Aa1aaaaaaaa", "Aa1aaaaaaaa").next).toBe("too_short");
    expect(isValid(checkPasswords("x", "Aa1aaaaaaaaa", "Aa1aaaaaaaaa"))).toBe(true);
    const p128 = "Aa1" + "a".repeat(125);
    expect(isValid(checkPasswords("x", p128, p128))).toBe(true);
    expect(checkPasswords("x", p128 + "a", p128 + "a").next).toBe("too_long");
    expect(isValid(checkPasswords("x", " ".repeat(20), " ".repeat(20)))).toBe(false);
  });
});

describe("F-WEB-033 BFF", () => {
  it("(holds) refuses a weak password before any API call, never echoes it, and refuses cross-site posts", async () => {
    const log = vi.spyOn(console, "log");
    const err = vi.spyOn(console, "error");
    const c = await tso();
    const before = mock.state.dash.passwords[2001];
    const weak = await pwPost(req("/api/bff/password", { current_password: "tso-pass-1", new_password: "Secretpass" }, c));
    expect(weak.status).toBe(400);
    const body = await weak.text();
    expect(body).not.toContain("Secretpass");
    expect(body).not.toContain("tso-pass-1");
    expect(mock.state.dash.passwords[2001]).toBe(before);
    const xsite = await pwPost(req("/api/bff/password", { current_password: "tso-pass-1", new_password: "GoodPassword123" }, c, { origin: "https://evil.example" }));
    expect(xsite.status).toBe(403);
    expect(mock.state.dash.passwords[2001]).toBe(before);
    const ok = await pwPost(req("/api/bff/password", { current_password: "tso-pass-1", new_password: "GoodPassword123" }, c));
    expect(ok.status).toBe(200);
    expect(await ok.text()).not.toContain("GoodPassword123");
    const all = [...log.mock.calls, ...err.mock.calls].flat().map(String).join(" ");
    expect(all).not.toMatch(/GoodPassword123|tso-pass-1|Secretpass/);
  });
  it("(holds) wrong current password comes back as a pointer the form maps to its authored error", async () => {
    const c = await tso();
    const r = await pwPost(req("/api/bff/password", { current_password: "nope-nope", new_password: "GoodPassword123" }, c));
    expect(r.status).toBe(400);
    expect(((await r.json()) as { errors?: { pointer: string }[] }).errors?.[0]?.pointer).toBe("/current_password");
  });
  it("(holds) no session is 401", async () => {
    expect((await pwPost(req("/api/bff/password", { current_password: "a", new_password: "GoodPassword123" }))).status).toBe(401);
  });
});

describe("hold-notes: change-password keeps this session's refresh family, and the signed client IP", () => {
  it("forwards the BFF's own aron_rt cookie and, with a key file, the signed X-Azure-ClientIP", async () => {
    const { writeFileSync, mkdtempSync } = await import("node:fs");
    const { tmpdir } = await import("node:os");
    const { createHmac } = await import("node:crypto");
    const { resetClientIpKey } = await import("@/lib/api/client-ip");
    const dir = mkdtempSync(`${tmpdir()}/ipkey-`);
    writeFileSync(`${dir}/k`, "0123456789abcdef0123456789abcdef");
    process.env.ARON_BFF_IP_HMAC_KEY_FILE = `${dir}/k`;
    resetClientIpKey();
    try {
      const c = await tso();
      let seen: { cookie?: string; ip?: string; sig?: string; rid?: string } = {};
      mock.state.stubs.push({ method: "POST", path: "/v1/auth/change-password", fn: (call) => { seen = { cookie: call.headers.cookie, ip: call.headers["x-aron-client-ip"] as string, sig: call.headers["x-aron-client-ip-sig"] as string, rid: call.headers["x-request-id"] as string }; return { status: 204 }; } });
      const res = await pwPost(req("/api/bff/password", { current_password: "tso-pass-1", new_password: "Brand-new-Pass-42" }, c, { "x-azure-clientip": "203.0.113.7", "x-forwarded-for": "6.6.6.6" }));
      expect(res.status).toBe(200);
      expect(seen.cookie).toBe(`${RT_COOKIE}=${c[RT_COOKIE]}`);
      expect(seen.ip).toBe("203.0.113.7"); // never the browser-supplied x-forwarded-for
      expect(seen.sig).toBe(createHmac("sha256", "0123456789abcdef0123456789abcdef").update(`203.0.113.7\n${seen.rid}`).digest("base64url"));
    } finally {
      delete process.env.ARON_BFF_IP_HMAC_KEY_FILE;
      resetClientIpKey();
    }
  });
  it("sends no client-ip headers without a key file", async () => {
    const c = await tso();
    let ip: unknown = "unset";
    mock.state.stubs.push({ method: "POST", path: "/v1/auth/change-password", fn: (call) => { ip = call.headers["x-aron-client-ip"]; return { status: 204 }; } });
    await pwPost(req("/api/bff/password", { current_password: "tso-pass-1", new_password: "Brand-new-Pass-42" }, c, { "x-azure-clientip": "203.0.113.7" }));
    expect(ip).toBeUndefined();
  });
});
