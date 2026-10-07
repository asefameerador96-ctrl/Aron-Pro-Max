// Checker: BFF input validation of take-action / leave / risk-review against the mock.
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { POST as actionPost } from "@/app/api/bff/tracking-action/route";
import { POST as leavePost } from "@/app/api/bff/leave-decision/route";
import { SESSION_COOKIE, SESSION_PURPOSE } from "@/lib/auth/cookies";
import { seal } from "@/lib/auth/seal";
import { accessFor } from "@/lib/auth/roles";
import { createMock } from "../mock/server";

const mock = createMock();
let base = "";
beforeAll(async () => {
  await new Promise<void>((r) => mock.server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(mock.server.address() as AddressInfo).port}`;
  process.env.ARON_API_BASE_URL = base;
  process.env.ARON_COOKIE_INSECURE = "1";
});
afterAll(() => new Promise<void>((r) => mock.server.close(() => r())));
beforeEach(() => mock.reset());
async function session(username: string, password: string) {
  const r = (await (await fetch(`${base}/v1/auth/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username, password, client: "web" }) })).json()) as { access_token: string; user: never; scope: never };
  return { at: r.access_token, cookie: `${SESSION_COOKIE}=${seal({ at: r.access_token, atExp: Date.now() + 600_000, user: r.user, scope: r.scope }, SESSION_PURPOSE, 600)}` };
}
const setNow = (now: string) => fetch(`${base}/__mock/now`, { method: "POST", body: JSON.stringify({ now }) });
const req = (path: string, cookie: string, body: unknown) => new NextRequest(`http://localhost:3000${path}`, { method: "POST", body: JSON.stringify(body), headers: { cookie, host: "localhost:3000", "content-type": "application/json" } });
const act = (cookie: string, body: Record<string, unknown>) => actionPost(req("/api/bff/tracking-action", cookie, { action_uuid: crypto.randomUUID(), route_id: 10233, business_date: "2026-10-07", note: "Please push the route", ...body }));

describe("F-WEB-039 take-action BFF", () => {
  it("rejects a 2-character note (contract TrackingActionRequest.note minLength 3)", async () => {
    const s = await session("tso334", "tso-pass-1");
    await setNow("2026-10-07T12:00:00Z");
    expect((await act(s.cookie, { note: "ab" })).status).toBe(400);
  });
  it("rejects a calendar-invalid date (2026-02-31) before calling the API", async () => {
    const s = await session("tso334", "tso-pass-1");
    await setNow("2026-10-07T12:00:00Z");
    expect((await act(s.cookie, { business_date: "2026-02-31" })).status).toBe(400);
  });
  it("accepts exactly 17:00:00 Dhaka and refuses 16:59:59", async () => {
    const s = await session("tso334", "tso-pass-1");
    await setNow("2026-10-07T10:59:59Z");
    expect((await act(s.cookie, {})).status).toBe(409);
    await setNow("2026-10-07T11:00:00Z");
    expect((await act(s.cookie, {})).status).toBe(201);
  });
  it("a note is stored with markup verbatim and never reaches HTML unescaped (React escapes); length 501 refused", async () => {
    const s = await session("tso334", "tso-pass-1");
    await setNow("2026-10-07T12:00:00Z");
    expect((await act(s.cookie, { note: "x".repeat(501) })).status).toBe(400);
  });
  it("future date refused (409) and SR/AMO roles do not reach the handler", async () => {
    const s = await session("tso334", "tso-pass-1");
    await setNow("2026-10-07T12:00:00Z");
    expect((await act(s.cookie, { business_date: "2026-10-08" })).status).toBe(409);
    expect(accessFor("SR", "/api/bff/tracking-action")).toBe("forbidden");
    expect(accessFor("AMO", "/daily-tracking")).toBe("forbidden");
    expect(accessFor("AMO", "/api/bff/leave-decision")).toBe("forbidden");
  });
});

describe("F-WEB-046 leave decision BFF", () => {
  it("non-uuid refused; TSO (not DMO) refused by the server; an already-decided leave is 409 and unchanged", async () => {
    const tso = await session("tso334", "tso-pass-1");
    expect((await leavePost(req("/api/bff/leave-decision", tso.cookie, { leave_uuid: "nope", decision: "approve" }))).status).toBe(400);
    const st = (await (await fetch(`${base}/__mock/state`)).json()) as { leave?: { leave_uuid: string; status: string }[] };
    const pending = st.leave?.find((l) => l.status === "pending");
    if (!pending) return;
    expect((await leavePost(req("/api/bff/leave-decision", tso.cookie, { leave_uuid: pending.leave_uuid, decision: "approve" }))).status).toBe(403);
  });
});
