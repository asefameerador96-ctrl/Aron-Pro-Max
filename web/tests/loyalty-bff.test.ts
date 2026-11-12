// Loyalty-programme BFF routes against the in-process mock (portal-loyalty-web): create, update, status, enrolments, gifts, replay by
// request_uuid / batch_uuid, conflicts, grants and roles. The mock follows the contract 1.12.2 plus the brief's 1.13.0 sketch; the
// real routes (portal-loyalty-api) replace it in a later train.
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { POST as mfaPost } from "@/app/api/bff/mfa/verify/route";
import { POST as createPost } from "@/app/api/bff/admin/loyalty/route";
import { PATCH as updatePatch } from "@/app/api/bff/admin/loyalty/[id]/route";
import { POST as statusPost } from "@/app/api/bff/admin/loyalty/[id]/status/route";
import { PUT as enrolPut } from "@/app/api/bff/admin/loyalty/[id]/enrolments/route";
import { POST as giftPost } from "@/app/api/bff/admin/loyalty/gifts/route";
import { PATCH as giftPatch } from "@/app/api/bff/admin/loyalty/gifts/[id]/route";
import { MFA_COOKIE, RT_COOKIE, SESSION_COOKIE } from "@/lib/auth/cookies";
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
const req = (path: string, method: string, body: unknown, cookies: Record<string, string>) =>
  new NextRequest(`${ORIGIN}${path}`, { method, body: JSON.stringify(body), headers: { "content-type": "application/json", host: "localhost:3000", origin: ORIGIN, cookie: Object.entries(cookies).map(([k, v]) => `${k}=${v}`).join("; ") } });
const cookieOf = (res: Response, name: string) => res.headers.getSetCookie().find((l) => l.startsWith(`${name}=`))!.split(";")[0]!.split("=").slice(1).join("=");

async function as(username: string, password: string, mfa?: string): Promise<Record<string, string>> {
  let res = await loginPost(new NextRequest(`${ORIGIN}/api/bff/login`, { method: "POST", body: JSON.stringify({ username, password }), headers: { "content-type": "application/json", host: "localhost:3000", origin: ORIGIN } }));
  if (mfa) res = await mfaPost(req("/api/bff/mfa/verify", "POST", { code: mfa }, { [MFA_COOKIE]: cookieOf(res, MFA_COOKIE) }));
  return { [SESSION_COOKIE]: cookieOf(res, SESSION_COOKIE), [RT_COOKIE]: cookieOf(res, RT_COOKIE) };
}
const superadmin = () => as("super1", "super-pass-1", "123456");
const admin = () => as("madmin1", "admin-pass-1", "123456"); // ADMIN without a module filter in the mock: write on loyalty
const reader = () => as("mloy1", "loy-pass-1", "123456"); // ADMIN with loyalty: r
const ungranted = () => as("mgrant1", "grant-pass-1", "123456"); // ADMIN whose grants omit loyalty

const U = (n: number) => `0b6d6c0e-8a5b-4d56-9f0a-${String(n).padStart(12, "0")}`;
const REASON = "Owner approved this change";
const prog = (over: Record<string, unknown> = {}) => ({
  request_uuid: U(1), kind: "astha", code: "NEW-1", name_en: "New programme", name_bn: null, period_label: "2027-Q1", active_from: "2027-01-01", active_to: "2099-12-31", channels: ["Astha"],
  tiers: [{ tier_code: "gold", label_en: "Gold", label_bn: null, threshold_value: "3000.500", threshold_basis: "std" }, { tier_code: "platinum", label_en: "Platinum", label_bn: null, threshold_value: "5000.000", threshold_basis: "std" }],
  change_reason: REASON, ...over,
});
const J = (path: string, method: string, b: unknown, c: Record<string, string>) => req(path, method, b, c);
const create = (c: Record<string, string>, b = prog()) => createPost(J("/api/bff/admin/loyalty", "POST", b, c));
const status = (c: Record<string, string>, id: number, b: Record<string, unknown>) => statusPost(J(`/api/bff/admin/loyalty/${id}/status`, "POST", b, c), { params: Promise.resolve({ id: String(id) }) });
const patch = (c: Record<string, string>, id: number, b: Record<string, unknown>) => updatePatch(J(`/api/bff/admin/loyalty/${id}`, "PATCH", b, c), { params: Promise.resolve({ id: String(id) }) });
const enrol = (c: Record<string, string>, id: number, b: Record<string, unknown>) => enrolPut(J(`/api/bff/admin/loyalty/${id}/enrolments`, "PUT", b, c), { params: Promise.resolve({ id: String(id) }) });
const gift = (c: Record<string, string>, b: Record<string, unknown>) => giftPost(J("/api/bff/admin/loyalty/gifts", "POST", b, c));
const giftUpdate = (c: Record<string, string>, id: number, b: Record<string, unknown>) => giftPatch(J(`/api/bff/admin/loyalty/gifts/${id}`, "PATCH", b, c), { params: Promise.resolve({ id: String(id) }) });
const api = async (c: Record<string, string>, path: string) => (await fetch(`${base}${path}`, { headers: { Authorization: `Bearer ${(await sessionToken(c))}` } }));
async function sessionToken(c: Record<string, string>): Promise<string> {
  const { readSession } = await import("@/lib/auth/session");
  return readSession(c[SESSION_COOKIE])!.at;
}
const list = async (c: Record<string, string>) => ((await (await api(c, "/v1/admin/programmes")).json()) as { items: { programme_id: number; code: string; status: string; version: number; version_no: number }[] }).items;
const problemOf = async (res: Response) => (await res.json()) as { code?: string; errors?: { pointer: string; code: string }[] };

describe("create (a draft, version 1) and its replay", () => {
  it("creates a draft; the same request_uuid and body replays the first result; another body or another code is a conflict", async () => {
    const c = await superadmin();
    const r1 = await create(c);
    expect(r1.status).toBe(201);
    const first = await r1.json();
    expect(first).toMatchObject({ code: "NEW-1", status: "draft", version_no: 1, kind: "astha" });
    expect(first.tiers[0].threshold_value).toBe("3000.500");
    const again = await create(c);
    expect(again.status).toBe(201);
    expect((await again.json()).programme_id).toBe(first.programme_id);
    expect((await create(c, prog({ name_en: "Changed" }))).status).toBe(409); // same uuid, different content
    expect((await create(c, prog({ request_uuid: U(2) }))).status).toBe(409); // code exists
    expect((await list(c)).filter((p) => p.code === "NEW-1")).toHaveLength(1);
  });
  it("an ADMIN with the write grant creates; a reader, an ungranted ADMIN and other roles are refused 403 before the API", async () => {
    expect((await create(await admin())).status).toBe(201);
    expect((await create(await reader(), prog({ request_uuid: U(3), code: "R-1" }))).status).toBe(403);
    expect((await create(await ungranted(), prog({ request_uuid: U(4), code: "R-2" }))).status).toBe(403);
    expect((await list(await superadmin())).map((p) => p.code)).not.toContain("R-1");
  });
  it("the BFF re-validates: unknown member, bad code, negative threshold, a short reason, a bad basis, an unknown channel", async () => {
    const c = await superadmin();
    for (const bad of [
      { extra: 1 }, { code: "-x" }, { change_reason: "short" }, { kind: "gold" }, { channels: ["XX"] }, { request_uuid: "nope" },
      { tiers: [{ tier_code: "gold", label_en: "Gold", label_bn: null, threshold_value: "-5", threshold_basis: "std" }] },
      { tiers: [{ tier_code: "gold", label_en: "Gold", label_bn: null, threshold_value: "1.2345", threshold_basis: "std" }] },
      { tiers: [{ tier_code: "gold", label_en: "Gold", label_bn: null, threshold_value: "1", threshold_basis: "packet" }] },
    ]) {
      const res = await create(c, prog(bad) as ReturnType<typeof prog>);
      expect(res.status, JSON.stringify(bad)).toBe(400);
      expect((await problemOf(res)).code).toBe("ERR_VALIDATION");
    }
    expect((await list(c)).map((p) => p.code)).not.toContain("NEW-1");
  });
  it("the API refuses thresholds that do not rise (400 with the pointer)", async () => {
    const res = await create(await superadmin(), prog({ tiers: [{ tier_code: "gold", label_en: "Gold", label_bn: null, threshold_value: "50", threshold_basis: "std" }, { tier_code: "silver", label_en: "Silver", label_bn: null, threshold_value: "50", threshold_basis: "std" }] }));
    expect(res.status).toBe(400);
    expect((await problemOf(res)).errors?.[0]).toEqual({ pointer: "/tiers/1/threshold_value", code: "not_rising" });
  });
});

describe("update (a new version while draft; If-Match)", () => {
  async function draft(c: Record<string, string>) {
    return (await (await create(c)).json()) as { programme_id: number; version: number };
  }
  it("appends version 2, bumps the row version, and the old one stays", async () => {
    const c = await superadmin();
    const d = await draft(c);
    const res = await patch(c, d.programme_id, { ...prog({ request_uuid: U(5), name_en: "Renamed" }), version: d.version });
    expect(res.status).toBe(200);
    const upd = await res.json();
    expect(upd).toMatchObject({ name_en: "Renamed", version_no: 2, status: "draft" });
    const versions = (await (await api(c, `/v1/admin/loyalty/programmes/${d.programme_id}/versions`)).json()).items as { version_no: number; is_current: boolean; name_en: string }[];
    expect(versions.map((v) => [v.version_no, v.is_current, v.name_en])).toEqual([[2, true, "Renamed"], [1, false, "New programme"]]);
  });
  it("a stale version is 412; a retry of the same request replays; the same uuid with another body is 409; an active programme is 409", async () => {
    const c = await superadmin();
    const d = await draft(c);
    const body = { ...prog({ request_uuid: U(6), name_en: "Second" }), version: d.version };
    expect((await patch(c, d.programme_id, body)).status).toBe(200);
    expect((await patch(c, d.programme_id, body)).status).toBe(200); // replay: first result, no third version
    expect((await patch(c, d.programme_id, { ...body, name_en: "Other" })).status).toBe(409);
    expect((await patch(c, d.programme_id, { ...prog({ request_uuid: U(7) }), version: d.version })).status).toBe(412); // version moved on
    const cur = (await list(c)).find((p) => p.programme_id === d.programme_id)!;
    expect(cur.version_no).toBe(2);
    expect((await status(c, d.programme_id, { request_uuid: U(8), to_status: "active", reason: REASON })).status).toBe(200);
    const live = (await list(c)).find((p) => p.programme_id === d.programme_id)!;
    expect((await patch(c, d.programme_id, { ...prog({ request_uuid: U(9) }), version: live.version })).status).toBe(409);
  });
  it("an unknown id is 404 and a non-numeric id never reaches the API", async () => {
    const c = await superadmin();
    expect((await patch(c, 9999, { ...prog({ request_uuid: U(10) }), version: 1 })).status).toBe(404);
    const res = await updatePatch(J("/api/bff/admin/loyalty/abc", "PATCH", { ...prog(), version: 1 }, c), { params: Promise.resolve({ id: "abc" }) });
    expect(res.status).toBe(404);
  });
});

describe("status: draft -> active (Super Admin) -> inactive", () => {
  it("the Super Admin activates and ends; each needs a reason; a repeat replays and writes one audit row", async () => {
    const c = await superadmin();
    const id = ((await (await create(c)).json()) as { programme_id: number }).programme_id;
    expect((await status(c, id, { request_uuid: U(11), to_status: "active", reason: "short" })).status).toBe(400);
    const on = await status(c, id, { request_uuid: U(12), to_status: "active", reason: REASON });
    expect(on.status).toBe(200);
    expect((await on.json()).status).toBe("active");
    expect((await status(c, id, { request_uuid: U(12), to_status: "active", reason: REASON })).status).toBe(200);
    expect(mock.state.audit.filter((a) => a.entity === "programme" && a.entity_id === String(id) && a.action === "activate")).toHaveLength(1);
    expect((await status(c, id, { request_uuid: U(13), to_status: "active", reason: REASON })).status).toBe(409); // already active
    const off = await status(c, id, { request_uuid: U(14), to_status: "inactive", reason: REASON });
    expect((await off.json()).status).toBe("inactive");
    expect((await status(c, id, { request_uuid: U(15), to_status: "inactive", reason: REASON })).status).toBe(409);
  });
  it("an ADMIN with the write grant cannot activate (403 at the BFF) but ends an active programme", async () => {
    const s = await superadmin();
    const a = await admin();
    const id = ((await (await create(a)).json()) as { programme_id: number }).programme_id;
    expect((await status(a, id, { request_uuid: U(16), to_status: "active", reason: REASON })).status).toBe(403);
    expect((await list(s)).find((p) => p.programme_id === id)!.status).toBe("draft");
    expect((await status(s, id, { request_uuid: U(17), to_status: "active", reason: REASON })).status).toBe(200);
    expect((await status(a, id, { request_uuid: U(18), to_status: "inactive", reason: REASON })).status).toBe(200);
  });
  it("a window that has already ended cannot be activated (400)", async () => {
    const c = await superadmin();
    const id = ((await (await create(c, prog({ active_from: "2020-01-01", active_to: "2020-12-31" }))).json()) as { programme_id: number }).programme_id;
    expect((await status(c, id, { request_uuid: U(19), to_status: "active", reason: REASON })).status).toBe(400);
  });
  it("a reader and an ungranted ADMIN are refused", async () => {
    expect((await status(await reader(), 1, { request_uuid: U(20), to_status: "inactive", reason: REASON })).status).toBe(403);
    expect((await status(await ungranted(), 1, { request_uuid: U(21), to_status: "inactive", reason: REASON })).status).toBe(403);
  });
});

describe("enrolments (idempotent by batch_uuid, no overlap) and progress", () => {
  const row = (over: Record<string, unknown> = {}) => ({ programme_id: 1, outlet_id: 201, tier_code: "gold", league_label: null, base_target: "1000.500", valid_from: "2027-01-01", valid_to: null, ...over });
  it("enrols, replays the same batch, refuses an overlap, and a negative target never leaves the BFF", async () => {
    const c = await superadmin();
    const b = { batch_uuid: U(30), rows: [row()], change_reason: REASON };
    const r1 = await enrol(c, 1, b);
    expect(r1.status).toBe(200);
    expect(await r1.json()).toMatchObject({ inserted: 1, updated: 0, unchanged: 0 });
    expect(await (await enrol(c, 1, b)).json()).toMatchObject({ inserted: 1 }); // replay of the first result
    expect(((await (await api(c, "/v1/admin/programmes/1/enrolments")).json()) as { items: { outlet_id: number }[] }).items.filter((e) => e.outlet_id === 201)).toHaveLength(1);
    expect((await enrol(c, 1, { batch_uuid: U(31), rows: [row({ valid_from: "2027-02-01" })], change_reason: REASON })).status).toBe(409); // overlaps the open-ended one
    const neg = await enrol(c, 1, { batch_uuid: U(32), rows: [row({ outlet_id: 202, base_target: "-37500" })], change_reason: REASON });
    expect(neg.status).toBe(400);
    expect(((await (await api(c, "/v1/admin/programmes/1/enrolments")).json()) as { items: { outlet_id: number }[] }).items.some((e) => e.outlet_id === 202)).toBe(false);
  });
  it("a row for another programme, an unknown tier and an ended programme are refused; a reader cannot write", async () => {
    const c = await superadmin();
    expect((await enrol(c, 1, { batch_uuid: U(33), rows: [row({ programme_id: 2 })], change_reason: REASON })).status).toBe(400);
    expect((await enrol(c, 1, { batch_uuid: U(34), rows: [row({ outlet_id: 203, tier_code: "nope" })], change_reason: REASON })).status).toBe(400);
    expect((await enrol(c, 4, { batch_uuid: U(35), rows: [row({ programme_id: 4, outlet_id: 204, tier_code: "gold" })], change_reason: REASON })).status).toBe(409); // ASTHA-Q3 ended
    expect((await enrol(await reader(), 1, { batch_uuid: U(36), rows: [row({ outlet_id: 205 })], change_reason: REASON })).status).toBe(403);
  });
  it("progress states targets and achievement as Decimal3 text and the zero-target tier is there to be guarded", async () => {
    const c = await superadmin();
    const p = (await (await api(c, "/v1/admin/loyalty/programmes/1/progress")).json()) as { tiers: { tier_code: string; enrolled_outlets: number; target_std: string; achieved_std: string }[]; points: Record<string, number> };
    const silver = p.tiers.find((x) => x.tier_code === "silver")!;
    expect(silver).toMatchObject({ enrolled_outlets: 1, target_std: "0.000", achieved_std: "400.000" });
    expect(p.points).toEqual({ earned: 0, redeemed: 0, expiring: 0 });
  });
});

describe("gifts (Super Admin only)", () => {
  const g = (over: Record<string, unknown> = {}) => ({ request_uuid: U(40), programme_id: 2, code: "water_filter", name_en: "Water filter", name_bn: null, points_cost: 300, tier_codes: [], status: "active", change_reason: REASON, ...over });
  it("creates, replays, refuses a duplicate code, and updates with If-Match", async () => {
    const c = await superadmin();
    const r1 = await gift(c, g());
    expect(r1.status).toBe(201);
    const first = await r1.json();
    expect(first).toMatchObject({ code: "water_filter", points_cost: 300, version: 1 });
    expect((await (await gift(c, g())).json()).gift_id).toBe(first.gift_id);
    expect((await gift(c, g({ request_uuid: U(41) }))).status).toBe(409);
    const upd = await giftUpdate(c, first.gift_id, { ...g({ request_uuid: U(42), points_cost: 350 }), version: 1 });
    expect(upd.status).toBe(200);
    expect(await upd.json()).toMatchObject({ points_cost: 350, version: 2 });
    expect((await giftUpdate(c, first.gift_id, { ...g({ request_uuid: U(43), points_cost: 360 }), version: 1 })).status).toBe(412);
  });
  it("an ADMIN with the write grant, a reader and an ungranted ADMIN cannot change the catalogue", async () => {
    for (const who of [await admin(), await reader(), await ungranted()]) expect((await gift(who, g({ request_uuid: U(44), code: "other_gift" }))).status).toBe(403);
  });
  it("points outside 1..100000 and a non-integer never reach the API", async () => {
    const c = await superadmin();
    for (const points_cost of [0, 100_001, 2.5, "200"]) expect((await gift(c, g({ request_uuid: U(45), code: "x_gift", points_cost }))).status, String(points_cost)).toBe(400);
  });
  it("Astha gifts carry no points (null)", async () => {
    const res = await gift(await superadmin(), g({ request_uuid: U(46), programme_id: 1, code: "tea_set", points_cost: null, tier_codes: ["gold"] }));
    expect(res.status).toBe(201);
    expect(await res.json()).toMatchObject({ points_cost: null, tier_codes: ["gold"] });
  });
});

describe("reads follow the grant", () => {
  it("SUPERADMIN, a write ADMIN and a read ADMIN list; an ungranted ADMIN and a TSO get 403", async () => {
    for (const who of [await superadmin(), await admin(), await reader()]) expect((await api(who, "/v1/admin/programmes")).status).toBe(200);
    expect((await api(await ungranted(), "/v1/admin/programmes")).status).toBe(403);
    expect((await api(await ungranted(), "/v1/admin/gifts")).status).toBe(403);
    expect((await api(await as("tso334", "tso-pass-1"), "/v1/admin/programmes")).status).toBe(403);
  });
  it("the seed has the three states: active, draft, ended", async () => {
    expect((await list(await superadmin())).map((p) => `${p.code}:${p.status}`).sort()).toEqual(["ASTHA-Q3:inactive", "ASTHA-Q4:active", "DL-OCT:active", "SS-NOV:draft"]);
  });
});
