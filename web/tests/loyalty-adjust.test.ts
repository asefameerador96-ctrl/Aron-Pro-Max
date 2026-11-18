// Points adjustment (portal-loyalty, contract 1.23.0): the pure form helpers and the BFF routes against the in-process mock (single post, replay,
// conflict, overdraw, bulk dry run then apply, replay, roles and grants).
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { POST as mfaPost } from "@/app/api/bff/mfa/verify/route";
import { POST as adjPost, PUT as adjPut } from "@/app/api/bff/admin/loyalty/[id]/adjustments/route";
import { MFA_COOKIE, RT_COOKIE, SESSION_COOKIE } from "@/lib/auth/cookies";
import { adjustFailureKey, emptyAdjustForm, parseAdjustFile, parsePoints, signed, validateAdjust } from "@/lib/admin/loyalty-adjust";
import { createMock } from "../mock/server";

const TODAY = "2026-11-18";

describe("form helpers", () => {
  it("parses whole signed points only", () => {
    expect(parsePoints("50")).toBe(50);
    expect(parsePoints(" -20 ")).toBe(-20);
    expect(parsePoints("+5")).toBe(5);
    for (const bad of ["", "1.5", "1e3", "abc", "12345678", "- 5"]) expect(parsePoints(bad)).toBeNull();
  });
  it("validates the single form and places each error", () => {
    const ok = validateAdjust({ ...emptyAdjustForm(), outlet_id: "301", points: "-20", reason: "Correction of a wrong load" }, TODAY);
    expect(ok.body).toEqual({ outlet_id: 301, points: -20, reason: "Correction of a wrong load" });
    const bad = validateAdjust({ outlet_id: "0", points: "0", expires_on: "2026-11-01", reason: "short", allow_overdraw: false }, TODAY);
    expect(bad.body).toBeNull();
    expect(Object.fromEntries(bad.errors.map((e) => [e.field, e.code]))).toEqual({ outlet_id: "not_an_integer", points: "zero", expires_on: "in_the_past", reason: "reason_short" });
    expect(validateAdjust({ outlet_id: "1", points: "1000001", expires_on: "2026-02-30", reason: "x".repeat(501), allow_overdraw: true }, TODAY).errors.map((e) => e.code)).toEqual(["out_of_range", "not_a_date", "reason_long"]);
    expect(validateAdjust({ outlet_id: "1", points: "5", expires_on: "2026-12-31", reason: "Ten chars!!", allow_overdraw: true }, TODAY).body).toEqual({ outlet_id: 1, points: 5, expires_on: "2026-12-31", reason: "Ten chars!!", allow_overdraw: true });
  });
  it("parses a CSV file: header, separators, bad lines with their line numbers, a duplicate outlet", () => {
    const p = parseAdjustFile("outlet_id,points,expires_on\n101,50,2026-12-31\n\n102;-5\n103\t7\n101,3\nabc,5\n104,0\n105,5,2020-01-01\n", TODAY);
    expect(p.rows).toEqual([{ outlet_id: 101, points: 50, expires_on: "2026-12-31" }, { outlet_id: 102, points: -5 }, { outlet_id: 103, points: 7 }]);
    expect(p.errors).toEqual([
      { line: 6, code: "duplicate", column: "outlet_id" }, { line: 7, code: "not_an_integer", column: "outlet_id" },
      { line: 8, code: "zero", column: "points" }, { line: 9, code: "in_the_past", column: "expires_on" },
    ]);
    expect(parseAdjustFile("﻿101,5", TODAY).rows).toHaveLength(1);
  });
  it("maps a refusal to its banner and signs points", () => {
    expect(adjustFailureKey(403, "ERR_FORBIDDEN", [{ pointer: "body.outlet_id", code: "out_of_scope" }])).toBe("loy.adj.refused_scope");
    expect(adjustFailureKey(403, "ERR_FORBIDDEN", [])).toBe("loy.adj.refused_role");
    expect(adjustFailureKey(409, "ERR_CONFLICT", [{ pointer: "body.points", code: "would_overdraw" }])).toBe("loy.adj.overdraw");
    expect(adjustFailureKey(409, "ERR_REQUEST_STATE", [])).toBe("loy.adj.refused_state");
    expect(adjustFailureKey(409, "ERR_CONFLICT", [{ pointer: "body.request_uuid", code: "reused" }])).toBe("loy.adj.reused");
    expect(adjustFailureKey(500, undefined, undefined)).toBeNull();
    expect([signed(5), signed(-5), signed(0)]).toEqual(["+5", "-5", "0"]);
  });
});

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
const req = (path: string, method: string, body: unknown, cookies: Record<string, string>) =>
  new NextRequest(`${ORIGIN}${path}`, { method, body: JSON.stringify(body), headers: { "content-type": "application/json", host: "localhost:3000", origin: ORIGIN, cookie: Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join("; ") } });
const cookieOf = (res: Response, name: string) => res.headers.getSetCookie().find((l) => l.startsWith(`${name}=`))!.split(";")[0]!.split("=").slice(1).join("=");
async function as(username: string, password: string, mfa?: string): Promise<Record<string, string>> {
  let res = await loginPost(new NextRequest(`${ORIGIN}/api/bff/login`, { method: "POST", body: JSON.stringify({ username, password }), headers: { "content-type": "application/json", host: "localhost:3000", origin: ORIGIN } }));
  if (mfa) res = await mfaPost(req("/api/bff/mfa/verify", "POST", { code: mfa }, { [MFA_COOKIE]: cookieOf(res, MFA_COOKIE) }));
  return { [SESSION_COOKIE]: cookieOf(res, SESSION_COOKIE), [RT_COOKIE]: cookieOf(res, RT_COOKIE) };
}
const superadmin = () => as("super1", "super-pass-1", "123456");
const reader = () => as("mloy1", "loy-pass-1", "123456"); // ADMIN with loyalty: r
const ungranted = () => as("mgrant1", "grant-pass-1", "123456");

const U = (n: number) => `0b6d6c0e-8a5b-4d56-9f0a-${String(n).padStart(12, "0")}`;
const WHY = "Opening balance loaded by the office";
const post = (c: Record<string, string>, id: number, b: Record<string, unknown>) => adjPost(req(`/api/bff/admin/loyalty/${id}/adjustments`, "POST", b, c), { params: Promise.resolve({ id: String(id) }) });
const put = (c: Record<string, string>, id: number, b: Record<string, unknown>) => adjPut(req(`/api/bff/admin/loyalty/${id}/adjustments`, "PUT", b, c), { params: Promise.resolve({ id: String(id) }) });
const ledger = async (c: Record<string, string>, id: number) => (await (await fetch(`${base}/v1/admin/programmes/${id}/ledger`, { headers: { authorization: `Bearer ${(await tokenOf(c))}` } })).json()) as { items: { points: number; reason: string | null; source_id: string }[] };
async function tokenOf(c: Record<string, string>): Promise<string> {
  const { readSession } = await import("@/lib/auth/session");
  return readSession(c[SESSION_COOKIE])!.at;
}

describe("single adjustment (POST)", () => {
  it("posts, replays with the same id, refuses another body under it, and guards overdraw", async () => {
    const c = await superadmin();
    const body = { request_uuid: U(1), outlet_id: 301, points: 50, reason: WHY, expires_on: "2099-12-31" };
    const first = await post(c, 1, body);
    expect(first.status).toBe(201);
    expect(((await first.json()) as { points: number; reason: string }).reason).toBe(WHY);
    expect((await post(c, 1, body)).status).toBe(200);          // a replay adds nothing
    expect((await post(c, 1, { ...body, points: 51 })).status).toBe(409);
    const debit = { request_uuid: U(2), outlet_id: 301, points: -80, reason: WHY };
    const refused = await post(c, 1, debit);
    expect(refused.status).toBe(409);
    expect(JSON.stringify(await refused.json())).toContain("would_overdraw");
    expect((await post(c, 1, { ...debit, allow_overdraw: true })).status).toBe(201);
  });
  it("is refused by the BFF for a reader and an ungranted ADMIN, and validates the body", async () => {
    expect((await post(await reader(), 1, { request_uuid: U(3), outlet_id: 301, points: 5, reason: WHY })).status).toBe(403);
    expect((await post(await ungranted(), 1, { request_uuid: U(3), outlet_id: 301, points: 5, reason: WHY })).status).toBe(403);
    const c = await superadmin();
    expect((await post(c, 1, { request_uuid: U(4), outlet_id: 301, points: 0, reason: WHY })).status).toBe(400);
    expect((await post(c, 1, { request_uuid: U(4), outlet_id: 301, points: 5, reason: "short" })).status).toBe(400);
    expect((await post(c, 1, { request_uuid: U(4), outlet_id: 301, points: 5, reason: WHY, bogus: 1 })).status).toBe(400);
    expect((await post(c, 3, { request_uuid: U(5), outlet_id: 301, points: 5, reason: WHY })).status).toBe(409);   // the draft takes no points
    expect((await post(c, 1, { request_uuid: U(6), outlet_id: 5000, points: 5, reason: WHY })).status).toBe(400);  // unknown outlet
  });
});

describe("bulk adjustment (PUT)", () => {
  const rows = [{ outlet_id: 301, points: 100 }, { outlet_id: 302, points: 40, expires_on: "2099-12-31" }];
  it("previews without writing, applies all or nothing, and replays", async () => {
    const c = await superadmin();
    const dry = await put(c, 1, { batch_uuid: U(10), dry_run: true, change_reason: WHY, rows: [...rows, { outlet_id: 303, points: -5 }] });
    expect(dry.status).toBe(200);
    const d = (await dry.json()) as { valid: boolean; error_count: number; errors: { row: number; code: string; balance_points?: number }[] };
    expect(d.valid).toBe(false);
    expect(d.errors).toEqual([{ row: 2, column: "points", code: "would_overdraw", balance_points: 0 }]);
    expect((await ledger(c, 1)).items).toHaveLength(0);                          // nothing written by a dry run
    const ok = (await (await put(c, 1, { batch_uuid: U(10), dry_run: true, change_reason: WHY, rows })).json()) as { valid: boolean; total_points: number; credit_points: number };
    expect(ok).toMatchObject({ valid: true, total_points: 140, credit_points: 140 });
    const bad = await put(c, 1, { batch_uuid: U(10), dry_run: false, change_reason: WHY, rows: [...rows, { outlet_id: 303, points: -5 }] });
    expect(bad.status).toBe(409);                                                // the overdraw refuses the whole file
    expect((await ledger(c, 1)).items).toHaveLength(0);
    const applied = await put(c, 1, { batch_uuid: U(10), dry_run: false, change_reason: WHY, rows });
    expect(await applied.json()).toMatchObject({ posted: 2, replayed: false, total_points: 140 });
    const again = await put(c, 1, { batch_uuid: U(10), dry_run: false, change_reason: WHY, rows });
    expect(await again.json()).toMatchObject({ posted: 0, replayed: true });
    expect((await ledger(c, 1)).items).toHaveLength(2);
    expect((await put(c, 1, { batch_uuid: U(10), dry_run: false, change_reason: WHY, rows: [{ outlet_id: 301, points: 101 }, rows[1]!] })).status).toBe(409); // same id, other file
    expect((await ledger(c, 1)).items.map((i) => i.reason)).toEqual([WHY, WHY]);
  });
  it("reports a duplicate outlet and an outlet outside the programme's scope", async () => {
    const c = await superadmin();
    const dry = (await (await put(c, 1, { batch_uuid: U(11), dry_run: true, change_reason: WHY, rows: [{ outlet_id: 301, points: 1 }, { outlet_id: 301, points: 2 }, { outlet_id: 5000, points: 3 }] })).json()) as { errors: { row: number; code: string }[]; error_count: number };
    expect(dry.errors.map((e) => `${e.row}:${e.code}`)).toEqual(["1:duplicate", "2:not_found"]);
    expect(dry.error_count).toBe(2);
    expect((await put(await reader(), 1, { batch_uuid: U(12), dry_run: true, change_reason: WHY, rows })).status).toBe(403);
    expect((await put(c, 1, { batch_uuid: U(12), change_reason: WHY, rows })).status).toBe(400);                       // dry_run is required
  });
});
