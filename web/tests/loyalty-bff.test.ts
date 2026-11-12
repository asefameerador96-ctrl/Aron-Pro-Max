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
import { GIFT_IMAGE_ASSET, OTHER_ASSET } from "../mock/loyalty";
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
const tier = (tier_code: string, sort_order: number, threshold: string | null, over: Record<string, unknown> = {}) => ({ tier_code, sort_order, label_en: tier_code, label_bn: null, threshold, threshold_basis: "std", ...over });
// The closed ProgrammeWrite of contract 1.13.0 (kind and code only on a create).
const prog = (over: Record<string, unknown> = {}) => ({
  request_uuid: U(1), kind: "astha", code: "NEW-1", name_en: "New programme", name_bn: null, period_label: "2027-Q1", active_from: "2027-01-01", active_to: "2099-12-31",
  tiers: [tier("gold", 1, "3000.500"), tier("platinum", 2, "5000.000")],
  change_reason: REASON, ...over,
});
// The closed ProgrammePatch: no kind, no code.
const progPatch = (over: Record<string, unknown> = {}) => { const { kind: _k, code: _c, ...rest } = prog(over); void _k; void _c; return rest; };
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
    expect(first.tiers[0]).toMatchObject({ threshold: "3000.500", sort_order: 1, threshold_basis: "std" });
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
      { extra: 1 }, { channels: ["GT"] }, { status: "active" }, { code: "-x" }, { change_reason: "short" }, { kind: "gold" }, { request_uuid: "nope" },
      { tiers: [tier("gold", 1, "-5")] }, { tiers: [tier("gold", 1, "1.2345")] }, { tiers: [tier("gold", 1, "1", { threshold_basis: "packet" })] },
      { tiers: [{ tier_code: "gold", label_en: "Gold", label_bn: null, threshold_value: "5", threshold_basis: "std" }] }, // the old member names
      { tiers: [tier("gold", 2, "1")] }, // sort_order must run 1..n
      { tiers: [tier("gold", 1, "50"), tier("silver", 2, "50")] }, // not rising: refused at the BFF
      { tiers: [tier("gold", 1, "1"), tier("gold", 2, "2")] }, // duplicate code
      { tiers: Array.from({ length: 11 }, (_, i) => tier(`t${i}x`, i + 1, String(i + 1))) },
    ]) {
      const res = await create(c, prog(bad) as ReturnType<typeof prog>);
      expect(res.status, JSON.stringify(bad)).toBe(400);
      expect((await problemOf(res)).code).toBe("ERR_VALIDATION");
    }
    expect((await list(c)).map((p) => p.code)).not.toContain("NEW-1");
  });
  it("thresholds that do not rise never reach the API (400 at the BFF); the mock refuses them too", async () => {
    const c = await superadmin();
    const bodyNotRising = prog({ tiers: [tier("gold", 1, "50"), tier("silver", 2, "50")] });
    expect((await create(c, bodyNotRising)).status).toBe(400);
    const direct = await fetch(`${base}/v1/admin/programmes`, { method: "POST", headers: { Authorization: `Bearer ${await sessionToken(c)}`, "Content-Type": "application/json" }, body: JSON.stringify(bodyNotRising) });
    expect(direct.status).toBe(400);
    expect((await problemOf(direct)).errors?.[0]).toEqual({ pointer: "body.tiers", code: "thresholds_not_rising" });
    expect(((await (await api(c, "/v1/admin/programmes")).json()) as { items: { code: string }[] }).items.some((p) => p.code === "NEW-1")).toBe(false);
  });
  it("getProgramme returns the row with its ETag; a missing one is 404", async () => {
    const c = await superadmin();
    const id = ((await (await create(c)).json()) as { programme_id: number }).programme_id;
    const r = await api(c, `/v1/admin/programmes/${id}`);
    expect(r.status).toBe(200);
    expect(r.headers.get("etag")).toBe('"2"'); // the server: the insert plus the link to the first version make it 2
    expect(await r.json()).toMatchObject({ code: "NEW-1", status: "draft" });
    expect((await api(c, "/v1/admin/programmes/9999")).status).toBe(404);
  });
});

describe("update (a new version while draft; If-Match)", () => {
  async function draft(c: Record<string, string>) {
    return (await (await create(c)).json()) as { programme_id: number; version: number };
  }
  it("appends version 2, bumps the row version, and the old one stays", async () => {
    const c = await superadmin();
    const d = await draft(c);
    const res = await patch(c, d.programme_id, { ...progPatch({ request_uuid: U(5), name_en: "Renamed" }), version: d.version });
    expect(res.status).toBe(200);
    const upd = await res.json();
    expect(upd).toMatchObject({ name_en: "Renamed", version_no: 2, status: "draft" });
    const versions = (await (await api(c, `/v1/admin/programmes/${d.programme_id}/versions`)).json()).items as { version_no: number; is_current: boolean; name_en: string }[];
    expect(versions.map((v) => [v.version_no, v.is_current, v.name_en])).toEqual([[2, true, "Renamed"], [1, false, "New programme"]]);
  });
  it("a stale version is 412; a retry of the same request replays; the same uuid with another body is 409; an active programme is 409", async () => {
    const c = await superadmin();
    const d = await draft(c);
    const body = { ...progPatch({ request_uuid: U(6), name_en: "Second" }), version: d.version };
    expect((await patch(c, d.programme_id, body)).status).toBe(200);
    expect((await patch(c, d.programme_id, body)).status).toBe(200); // replay: first result, no third version
    expect((await patch(c, d.programme_id, { ...body, name_en: "Other" })).status).toBe(409);
    expect((await patch(c, d.programme_id, { ...progPatch({ request_uuid: U(7) }), version: d.version })).status).toBe(412); // version moved on
    const cur = (await list(c)).find((p) => p.programme_id === d.programme_id)!;
    expect(cur.version_no).toBe(2);
    expect((await status(c, d.programme_id, { request_uuid: U(8), to_status: "active", reason: REASON })).status).toBe(200);
    const live = (await list(c)).find((p) => p.programme_id === d.programme_id)!;
    expect((await patch(c, d.programme_id, { ...progPatch({ request_uuid: U(9) }), version: live.version })).status).toBe(409);
  });
  it("a patch is closed: kind, code or channels never reach the API", async () => {
    const c = await superadmin();
    const d = await draft(c);
    for (const extra of [{ kind: "astha" }, { code: "NEW-1" }, { channels: ["GT"] }, { status: "active" }]) {
      expect((await patch(c, d.programme_id, { ...progPatch({ request_uuid: U(11) }), ...extra, version: d.version })).status, JSON.stringify(extra)).toBe(400);
    }
    expect((await list(c)).find((p) => p.programme_id === d.programme_id)!.version_no).toBe(1);
  });
  it("an unknown id is 404 and a non-numeric id never reaches the API", async () => {
    const c = await superadmin();
    expect((await patch(c, 9999, { ...progPatch({ request_uuid: U(10) }), version: 1 })).status).toBe(404);
    const res = await updatePatch(J("/api/bff/admin/loyalty/abc", "PATCH", { ...progPatch(), version: 1 }, c), { params: Promise.resolve({ id: "abc" }) });
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

describe("enrolments (idempotent by batch_uuid, no overlap)", () => {
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
  it("there is no progress operation in 1.13.0: the old sketch path is unknown (404), and nothing fakes the figures", async () => {
    expect((await api(await superadmin(), "/v1/admin/loyalty/programmes/1/progress")).status).toBe(404);
    expect((await api(await superadmin(), "/v1/admin/programmes/1/progress")).status).toBe(404);
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
    const gp = (over: Record<string, unknown>) => { const { programme_id: _p, code: _c, ...rest } = g(over); void _p; void _c; return { ...rest, version: 1 }; }; // GiftPatch is closed: no programme_id, no code
    expect((await giftUpdate(c, first.gift_id, { ...g({ request_uuid: U(47), points_cost: 350 }), version: 1 })).status).toBe(400);
    const upd = await giftUpdate(c, first.gift_id, gp({ request_uuid: U(42), points_cost: 350 }));
    const updBody = await upd.clone().json();
    expect(upd.status, JSON.stringify(updBody)).toBe(200);
    expect(updBody).toMatchObject({ points_cost: 350, version: 2 });
    expect((await giftUpdate(c, first.gift_id, gp({ request_uuid: U(43), points_cost: 360 }))).status).toBe(412);
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
  it("SUPERADMIN, a write ADMIN, a read ADMIN and a TSO list; an ungranted ADMIN gets 403", async () => {
    for (const who of [await superadmin(), await admin(), await reader()]) expect((await api(who, "/v1/admin/programmes")).status).toBe(200);
    expect((await api(await ungranted(), "/v1/admin/programmes")).status).toBe(403);
    expect((await api(await ungranted(), "/v1/admin/gifts")).status).toBe(403);
    expect((await api(await as("tso334", "tso-pass-1"), "/v1/admin/programmes")).status).toBe(200); // a TSO reads within its reach (docs/34), as the server does
  });
  it("the seed has the three states: active, draft, ended", async () => {
    expect((await list(await superadmin())).map((p) => `${p.code}:${p.status}`).sort()).toEqual(["ASTHA-Q3:inactive", "ASTHA-Q4:active", "DL-OCT:active", "SS-NOV:draft"]);
  });
});

// ---- the mock equals the real server (D-LOY-WEB-04): pointers, codes, order of checks, reach ----
const direct = async (c: Record<string, string>, method: string, path: string, body?: unknown, headers: Record<string, string> = {}) =>
  fetch(`${base}${path}`, { method, headers: { Authorization: `Bearer ${await sessionToken(c)}`, "Content-Type": "application/json", ...headers }, body: body === undefined ? undefined : JSON.stringify(body) });
const codeOf = async (r: Response) => ((await r.json()) as { code?: string }).code;

describe("field errors carry the server's pointers and codes", () => {
  it("the server answers ONE error, the first, with body.* pointers: unknown_field, not_a_decimal, thresholds_not_rising", async () => {
    const c = await superadmin();
    const first = async (body: unknown) => { const res = await direct(c, "POST", "/v1/admin/programmes", body); expect(res.status).toBe(400); return (await problemOf(res)).errors; };
    expect(await first({ ...prog({ extra: 1 }), tiers: [tier("gold", 1, "x1")] })).toEqual([{ pointer: "body.extra", code: "unknown_field" }]); // only() runs before anything else
    expect(await first(prog({ tiers: [tier("gold", 1, "x1"), tier("silver", 2, "5")] }))).toEqual([{ pointer: "body.tiers[0].threshold", code: "not_a_decimal" }]);
    expect(await first(prog({ tiers: [tier("gold", 1, "9"), tier("silver", 2, "5")] }))).toEqual([{ pointer: "body.tiers", code: "thresholds_not_rising" }]);
    expect(await first(prog({ name_en: "", active_to: "2020-01-01" }))).toEqual([{ pointer: "body.name_en", code: "length" }]); // the name before the dates, as readSpec reads them
    expect(await first(prog({ active_from: "2026-02-31" }))).toEqual([{ pointer: "body.active_from", code: "not_a_date" }]); // an impossible date
    expect(await first({ ...prog(), request_uuid: undefined })).toEqual([{ pointer: "body.request_uuid", code: "required" }]); // a create without request_uuid
  });
  it("status is refused on a create (use the status endpoint); header.If-Match is required on a patch", async () => {
    const c = await superadmin();
    expect((await problemOf(await direct(c, "POST", "/v1/admin/programmes", prog({ status: "active" })))).errors).toContainEqual({ pointer: "body.status", code: "use_status_endpoint" });
    const d = (await (await create(c)).json()) as { programme_id: number };
    const noHeader = await direct(c, "PATCH", `/v1/admin/programmes/${d.programme_id}`, progPatch({ request_uuid: U(60) }));
    expect(noHeader.status).toBe(400);
    expect((await problemOf(noHeader)).errors).toEqual([{ pointer: "header.If-Match", code: "invalid_value" }]); // missing or malformed: one code
    const malformed = await direct(c, "PATCH", `/v1/admin/programmes/${d.programme_id}`, progPatch({ request_uuid: U(60) }), { "If-Match": "1" });
    expect((await problemOf(malformed)).errors).toEqual([{ pointer: "header.If-Match", code: "invalid_value" }]);
  });
  it("an unknown member of the status body and of an enrolment row is refused", async () => {
    const c = await superadmin();
    const st = await direct(c, "POST", "/v1/admin/programmes/1/status", { request_uuid: U(61), to_status: "inactive", reason: REASON, extra: 1 });
    expect((await problemOf(st)).errors).toEqual([{ pointer: "body.extra", code: "unknown_field" }]);
    const en = await direct(c, "PUT", "/v1/admin/programmes/1/enrolments", { batch_uuid: U(62), change_reason: REASON, rows: [{ programme_id: 1, outlet_id: 5, valid_from: "2027-01-01", extra: 1 }] });
    expect((await problemOf(en)).errors).toEqual([{ pointer: "body.extra", code: "unknown_field" }]); // the server's only() names body.<member> at every depth
  });
  it("query values outside the enum are 400 and the filters are honoured", async () => {
    const c = await superadmin();
    expect((await problemOf(await api(c, "/v1/admin/programmes?status=bogus"))).errors).toEqual([{ pointer: "query.status", code: "not_in_enum" }]);
    expect((await api(c, "/v1/admin/programmes?kind=bogus")).status).toBe(400);
    expect((await api(c, "/v1/admin/gifts?status=draft")).status).toBe(400); // gifts are active or inactive
    const act = ((await (await api(c, "/v1/admin/programmes?status=active&kind=astha")).json()) as { items: { code: string }[] }).items;
    expect(act.map((p) => p.code)).toEqual(["ASTHA-Q4"]);
    await giftUpdate(c, 1, { ...{ request_uuid: U(63), name_en: "Kitchen rack", name_bn: null, points_cost: 200, tier_codes: [], status: "inactive", change_reason: REASON }, version: 1 });
    expect(((await (await api(c, "/v1/admin/gifts?status=inactive")).json()) as { items: { code: string }[] }).items.map((g) => g.code)).toEqual(["kitchen_rack"]);
  });
});

describe("409 codes and the order of checks", () => {
  it("non-draft patch = ERR_REQUEST_STATE; taken code = ERR_MASTER_DUPLICATE_CODE; reused uuid = ERR_CONFLICT; ended programme enrolment / gift = ERR_REQUEST_STATE; overlap = ERR_MASTER_OVERLAP", async () => {
    const c = await superadmin();
    const live = (await list(c)).find((p) => p.code === "ASTHA-Q4")!;
    expect(await codeOf(await patch(c, live.programme_id, { ...progPatch({ request_uuid: U(64) }), version: live.version }))).toBe("ERR_REQUEST_STATE");
    expect(await codeOf(await create(c, prog({ request_uuid: U(65), code: "ASTHA-Q4" })))).toBe("ERR_MASTER_DUPLICATE_CODE");
    expect((await create(c, prog({ request_uuid: U(66) }))).status).toBe(201);
    expect(await codeOf(await create(c, prog({ request_uuid: U(66), name_en: "Other" })))).toBe("ERR_CONFLICT");
    expect(await codeOf(await enrol(c, 4, { batch_uuid: U(67), rows: [{ programme_id: 4, outlet_id: 5, tier_code: "gold", valid_from: "2027-01-01" }], change_reason: REASON }))).toBe("ERR_REQUEST_STATE");
    expect(await codeOf(await gift(c, { request_uuid: U(68), programme_id: 4, code: "late_gift", name_en: "Late", name_bn: null, points_cost: 5, tier_codes: [], status: "active", change_reason: REASON }))).toBe("ERR_REQUEST_STATE");
    const row = (outlet: number, from: string, to: string | null) => ({ programme_id: 1, outlet_id: outlet, tier_code: "gold", valid_from: from, valid_to: to });
    expect((await enrol(c, 1, { batch_uuid: U(69), rows: [row(7, "2027-01-01", "2027-02-01")], change_reason: REASON })).status).toBe(200);
    expect(await codeOf(await enrol(c, 1, { batch_uuid: U(70), rows: [row(7, "2027-01-15", null)], change_reason: REASON }))).toBe("ERR_MASTER_OVERLAP");
    expect((await enrol(c, 1, { batch_uuid: U(71), rows: [row(7, "2027-02-01", null)], change_reason: REASON })).status).toBe(200); // back to back: the end is exclusive
    expect(await codeOf(await enrol(c, 1, { batch_uuid: U(69), rows: [row(8, "2027-01-01", null)], change_reason: REASON }))).toBe("ERR_CONFLICT"); // batch_uuid reused with other content
  });
  it("a stale ETag on a non-draft programme is 409 before 412; on a draft it is 412", async () => {
    const c = await superadmin();
    const live = (await list(c)).find((p) => p.code === "ASTHA-Q4")!;
    expect((await direct(c, "PATCH", `/v1/admin/programmes/${live.programme_id}`, progPatch({ request_uuid: U(72) }), { "If-Match": '"99"' })).status).toBe(409);
    const d = (await (await create(c)).json()) as { programme_id: number };
    expect((await direct(c, "PATCH", `/v1/admin/programmes/${d.programme_id}`, progPatch({ request_uuid: U(73) }), { "If-Match": '"99"' })).status).toBe(412);
  });
  it("an ADMIN who activates gets 403 before any state check (even on an already active programme)", async () => {
    const a = await admin();
    const r = await direct(a, "POST", "/v1/admin/programmes/1/status", { request_uuid: U(74), to_status: "active", reason: REASON });
    expect(r.status).toBe(403);
  });
});

describe("no_change: a save that changes nothing is 400 and makes no version", () => {
  it("programme patch and gift patch", async () => {
    const c = await superadmin();
    const d = (await (await create(c)).json()) as { programme_id: number; version: number };
    const same = { ...progPatch({ request_uuid: U(75) }), version: d.version };
    const res = await patch(c, d.programme_id, same);
    expect(res.status).toBe(400);
    expect((await problemOf(res)).errors).toContainEqual({ pointer: "body", code: "no_change" });
    expect((await list(c)).find((p) => p.programme_id === d.programme_id)!.version_no).toBe(1);
    const g = await gift(c, { request_uuid: U(76), programme_id: 2, code: "tea_set", name_en: "Tea set", name_bn: null, points_cost: 90, tier_codes: [], status: "active", change_reason: REASON });
    const gid = ((await g.json()) as { gift_id: number }).gift_id;
    const gp = { request_uuid: U(77), name_en: "Tea set", name_bn: null, points_cost: 90, tier_codes: [], status: "active", change_reason: REASON, version: 1 };
    const gr = await giftUpdate(c, gid, gp);
    expect(gr.status).toBe(400);
    expect((await problemOf(gr)).errors).toContainEqual({ pointer: "body", code: "no_change" });
  });
  it("editing a draft that HAS scope and qualifying keeps both unchanged (the patch leaves them out)", async () => {
    const c = await superadmin();
    const before = (await list(c)).find((p) => p.code === "SS-NOV")! as unknown as { programme_id: number; version: number; scope: unknown; qualifying: unknown };
    expect(before.scope).toEqual([{ node_type: "sub_channel", node_id: 11 }, { node_type: "wing", node_id: 1 }]);
    const res = await patch(c, before.programme_id, { ...progPatch({ request_uuid: U(78), name_en: "Superstar renamed", active_from: "2026-11-15", active_to: "2099-12-31", period_label: "2026-11", tiers: [] }), version: before.version });
    expect(res.status).toBe(200);
    const got = (await (await api(c, `/v1/admin/programmes/${before.programme_id}`)).json()) as { name_en: string; scope: unknown; qualifying: unknown; version_no: number };
    expect(got).toMatchObject({ name_en: "Superstar renamed", version_no: 2 });
    expect(got.scope).toEqual(before.scope);
    expect(got.qualifying).toEqual(before.qualifying);
  });
});

describe("other refusals the real server makes", () => {
  it("an enrolment tier outside the tiers = unknown_tier (read before the outlets); an unknown outlet = not_found; a programme without tiers takes any tier code", async () => {
    const c = await superadmin();
    const bad = await enrol(c, 1, { batch_uuid: U(80), rows: [{ programme_id: 1, outlet_id: 5000, tier_code: "nope", valid_from: "2027-01-01" }], change_reason: REASON });
    expect(bad.status).toBe(400);
    expect((await problemOf(bad)).errors).toEqual([{ pointer: "body.rows[0].tier_code", code: "unknown_tier" }]);
    const outlet = await enrol(c, 1, { batch_uuid: U(83), rows: [{ programme_id: 1, outlet_id: 5000, tier_code: "gold", valid_from: "2027-01-01" }], change_reason: REASON });
    expect((await problemOf(outlet)).errors).toEqual([{ pointer: "body.rows[0].outlet_id", code: "not_found" }]);
    expect((await enrol(c, 2, { batch_uuid: U(81), rows: [{ programme_id: 2, outlet_id: 9, tier_code: "free_code", valid_from: "2027-01-01" }], change_reason: REASON })).status).toBe(200); // DL-OCT has no tiers
  });
  it("enrolment valid_to must be after valid_from (equal is refused)", async () => {
    const res = await enrol(await superadmin(), 1, { batch_uuid: U(82), rows: [{ programme_id: 1, outlet_id: 9, tier_code: "gold", valid_from: "2027-01-01", valid_to: "2027-01-01" }], change_reason: REASON });
    expect(res.status).toBe(400);
    expect((await problemOf(res)).errors).toEqual([{ pointer: "body.rows[0].valid_to", code: "before_valid_from" }]);
  });
  it("gift tier_codes outside the current tiers = unknown_tier at body.tier_codes[i]; the gift code is unique per programme, not global", async () => {
    const c = await superadmin();
    const g = (over: Record<string, unknown>) => ({ request_uuid: U(83), programme_id: 1, code: "mug", name_en: "Mug", name_bn: null, points_cost: null, tier_codes: ["gold"], status: "active", change_reason: REASON, ...over });
    const bad = await direct(c, "POST", "/v1/admin/gifts", g({ tier_codes: ["gold", "diamond"] }));
    expect(bad.status).toBe(400);
    expect((await problemOf(bad)).errors).toEqual([{ pointer: "body.tier_codes[1]", code: "unknown_tier" }]);
    expect((await gift(c, g({ request_uuid: U(84), tier_codes: ["gold"] }))).status).toBe(201);
    expect(await codeOf(await gift(c, g({ request_uuid: U(85) })))).toBe("ERR_MASTER_DUPLICATE_CODE"); // same programme
    expect((await gift(c, g({ request_uuid: U(86), programme_id: 2, tier_codes: [] }))).status).toBe(201); // another programme: the same code is fine
  });
  it("image_asset_id: not_a_uuid, then unknown_asset, then wrong_purpose; an uploaded gift_image asset is accepted and resolves image_url", async () => {
    const c = await superadmin();
    const g = (over: Record<string, unknown>) => ({ request_uuid: U(87), programme_id: 2, code: "pic_gift", name_en: "Pic", name_bn: null, points_cost: 5, tier_codes: [], status: "active", change_reason: REASON, ...over });
    const err = async (asset: string, n: number) => (await problemOf(await direct(c, "POST", "/v1/admin/gifts", g({ request_uuid: U(n), image_asset_id: asset })))).errors;
    expect(await err("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz", 87)).toEqual([{ pointer: "body.image_asset_id", code: "not_a_uuid" }]);
    expect(await err("0b6d6c0e-8a5b-4d56-9f0a-0000000a00ff", 88)).toEqual([{ pointer: "body.image_asset_id", code: "unknown_asset" }]);
    expect(await err(OTHER_ASSET, 89)).toEqual([{ pointer: "body.image_asset_id", code: "wrong_purpose" }]);
    const ok = await direct(c, "POST", "/v1/admin/gifts", g({ request_uuid: U(90), image_asset_id: GIFT_IMAGE_ASSET }));
    expect(ok.status).toBe(201);
    expect(((await ok.json()) as { image_url: string | null }).image_url).toContain(GIFT_IMAGE_ASSET);
  });
  it("ids: the BFF keeps its guard (at least 1, no leading zeros); the server accepts leading zeros and answers 400 path.id for 0 or a non-number", async () => {
    const c = await superadmin();
    for (const id of ["0", "007", "01"]) {
      const res = await updatePatch(J(`/api/bff/admin/loyalty/${id}`, "PATCH", { ...progPatch(), version: 1 }, c), { params: Promise.resolve({ id }) });
      expect(res.status, id).toBe(404); // never reaches the API
    }
    expect((await direct(c, "GET", "/v1/admin/programmes/001")).status).toBe(200); // the server reads 001 as 1
    for (const bad of ["0", "abc"]) expect((await problemOf(await direct(c, "GET", `/v1/admin/programmes/${bad}`))).errors).toEqual([{ pointer: "path.id", code: "invalid_value" }]);
  });
  it("versions and enrolments page by cursor", async () => {
    const c = await superadmin();
    const first = (await (await api(c, "/v1/admin/programmes/1/enrolments?limit=2")).json()) as { items: unknown[]; next_cursor: string | null };
    expect(first.items).toHaveLength(2);
    expect(first.next_cursor).not.toBeNull();
    const second = (await (await api(c, `/v1/admin/programmes/1/enrolments?limit=2&cursor=${first.next_cursor}`)).json()) as { items: unknown[]; next_cursor: string | null };
    expect(second.items).toHaveLength(1);
    expect(second.next_cursor).toBeNull();
    const d = (await (await create(c)).json()) as { programme_id: number; version: number };
    await patch(c, d.programme_id, { ...progPatch({ request_uuid: U(88), name_en: "Second" }), version: d.version });
    const v1 = (await (await api(c, `/v1/admin/programmes/${d.programme_id}/versions?limit=1`)).json()) as { items: { version_no: number }[]; next_cursor: string | null };
    expect(v1.items.map((v) => v.version_no)).toEqual([2]);
    expect(v1.next_cursor).not.toBeNull();
  });
});

describe("roles below ADMIN read within their reach (same as the server)", () => {
  it("NATIONAL reads everything, including a zone outside any reach; a write is 403", async () => {
    const n = await as("nat1", "nat-pass-1");
    expect(((await (await api(n, "/v1/admin/programmes")).json()) as { items: unknown[] }).items).toHaveLength(4);
    expect((await api(n, "/v1/admin/programmes/1/enrolments?zone_id=99")).status).toBe(200);
    expect((await create(n)).status).toBe(403);
  });
  it("BRAND: a programme of other brands is 404 and absent from the list; enrolments are 403; gifts of a hidden programme are hidden", async () => {
    const s = await superadmin();
    const hidden = (await (await direct(s, "POST", "/v1/admin/programmes", prog({ request_uuid: U(90), code: "OTHER-BRAND", qualifying: [{ product_level: "brand", product_id: 2 }] }))).json()) as { programme_id: number };
    const b = await as("brand1", "brand-pass-1");
    const codes = ((await (await api(b, "/v1/admin/programmes")).json()) as { items: { code: string }[] }).items.map((p) => p.code);
    expect(codes).not.toContain("OTHER-BRAND");
    expect(codes).toContain("DL-OCT");
    expect((await api(b, `/v1/admin/programmes/${hidden.programme_id}`)).status).toBe(404);
    expect((await api(b, "/v1/admin/programmes/1/enrolments")).status).toBe(403);
    expect((await api(b, "/v1/admin/gifts")).status).toBe(200);
  });
  it("TSO / WM / DMO: the rule is containment: a programme is shown when it names no geography or a node that contains a zone of the reach", async () => {
    const s0 = await superadmin();
    const z15 = (await (await direct(s0, "POST", "/v1/admin/programmes", prog({ request_uuid: U(91), code: "ZONE-15", scope: [{ node_type: "zone", node_id: 15 }] }))).json()) as { programme_id: number };
    const codesOf = async (c: Record<string, string>) => ((await (await api(c, "/v1/admin/programmes")).json()) as { items: { code: string }[] }).items.map((x) => x.code);
    for (const [u, p, own] of [["wm1", "wm-pass-1", true], ["dmo1", "dmo-pass-1", true], ["tso334", "tso-pass-1", false], ["tso335", "tso-pass-2", true]] as const) {
      const c = await as(u, p);
      const codes = await codesOf(c);
      expect(codes, u).toContain("ASTHA-Q4"); // names a sub-channel only: channels do not narrow
      expect(codes, u).toContain("SS-NOV"); // names wing 1, which contains zones 14 and 15: every reach has one of them
      expect(codes.includes("ZONE-15"), u).toBe(own);
      expect((await api(c, `/v1/admin/programmes/${z15.programme_id}`)).status, u).toBe(own ? 200 : 404);
    }
    const c = await as("tso334", "tso-pass-1");
    expect((await api(c, "/v1/admin/programmes/1/enrolments?zone_id=99")).status).toBe(403);
    expect((await api(c, "/v1/admin/programmes/1/enrolments?zone_id=15")).status).toBe(403);
    expect((await api(c, "/v1/admin/programmes/1/enrolments?zone_id=14")).status).toBe(200);
    expect((await api(await as("wm1", "wm-pass-1"), "/v1/admin/programmes/1/enrolments?zone_id=15")).status).toBe(200);
  });
});

// ---- order of checks, newest-first keysets and replays, as AdminLoyalty.kt does them (D-LOY-WEB-05)
describe("lists are newest first with numeric keyset cursors", () => {
  it("programmes, gifts and enrolments newest first", async () => {
    const c = await superadmin();
    const codes = ((await (await api(c, "/v1/admin/programmes")).json()) as { items: { code: string }[] }).items.map((p) => p.code);
    expect(codes).toEqual(["ASTHA-Q3", "SS-NOV", "DL-OCT", "ASTHA-Q4"]);
    const gifts = ((await (await api(c, "/v1/admin/gifts")).json()) as { items: { code: string }[] }).items.map((g) => g.code);
    expect(gifts).toEqual(["rice_cooker", "tornado_fan", "chair", "kitchen_rack"]);
    const en = ((await (await api(c, "/v1/admin/programmes/1/enrolments")).json()) as { items: { outlet_id: number }[] }).items.map((e) => e.outlet_id);
    expect(en).toEqual([103, 102, 101]);
  });
  it("the cursor is the last id of the page (enrolments) or the last version_no (versions); limit 1..500 and a numeric cursor are validated", async () => {
    const c = await superadmin();
    const p1 = (await (await api(c, "/v1/admin/programmes/1/enrolments?limit=2")).json()) as { items: { outlet_id: number }[]; next_cursor: string | null };
    expect(p1.items.map((e) => e.outlet_id)).toEqual([103, 102]);
    expect(p1.next_cursor).toBe("2"); // the id of outlet 102's row
    const p2 = (await (await api(c, `/v1/admin/programmes/1/enrolments?limit=2&cursor=${p1.next_cursor}`)).json()) as { items: { outlet_id: number }[]; next_cursor: string | null };
    expect(p2.items.map((e) => e.outlet_id)).toEqual([101]);
    expect(p2.next_cursor).toBeNull();
    const bad = async (q: string) => (await problemOf(await api(c, `/v1/admin/programmes/1/enrolments?${q}`))).errors;
    expect(await bad("limit=0")).toEqual([{ pointer: "query.limit", code: "out_of_range" }]);
    expect(await bad("limit=501")).toEqual([{ pointer: "query.limit", code: "out_of_range" }]);
    expect(await bad("cursor=abc")).toEqual([{ pointer: "query.cursor", code: "invalid_value" }]);
    expect(await bad("cursor=0")).toEqual([{ pointer: "query.cursor", code: "invalid_value" }]);
    expect((await api(c, "/v1/admin/programmes/1/enrolments?limit=500")).status).toBe(200);
    const d = (await (await create(c)).json()) as { programme_id: number; version: number };
    await patch(c, d.programme_id, { ...progPatch({ request_uuid: U(100), name_en: "Two" }), version: d.version });
    const v1 = (await (await api(c, `/v1/admin/programmes/${d.programme_id}/versions?limit=1`)).json()) as { items: { version_no: number }[]; next_cursor: string | null };
    expect([v1.items[0]!.version_no, v1.next_cursor]).toEqual([2, "2"]);
    const v2 = (await (await api(c, `/v1/admin/programmes/${d.programme_id}/versions?limit=1&cursor=2`)).json()) as { items: { version_no: number }[]; next_cursor: string | null };
    expect([v2.items[0]!.version_no, v2.next_cursor]).toEqual([1, null]);
  });
  it("a version outside the caller's reach is not listed (each version is judged on its own scope), and the cursor never points at a hidden one", async () => {
    const s0 = await superadmin();
    const d = (await (await direct(s0, "POST", "/v1/admin/programmes", prog({ request_uuid: U(101), code: "CUT-1" }))).json()) as { programme_id: number; version: number };
    await direct(s0, "PATCH", `/v1/admin/programmes/${d.programme_id}`, { ...progPatch({ request_uuid: U(102), name_en: "v2" }), scope: [{ node_type: "zone", node_id: 15 }] }, { "If-Match": `"${d.version}"` });
    await direct(s0, "PATCH", `/v1/admin/programmes/${d.programme_id}`, { ...progPatch({ request_uuid: U(103), name_en: "v3" }), scope: [] }, { "If-Match": '"3"' });
    const t = await as("tso334", "tso-pass-1"); // reach zone 14: v1 (no geography) and v3 (none) are visible, v2 (zone 15) is not
    const page = (await (await api(t, `/v1/admin/programmes/${d.programme_id}/versions?limit=1`)).json()) as { items: { version_no: number }[]; next_cursor: string | null };
    expect(page.items.map((v) => v.version_no)).toEqual([3]);
    expect(page.next_cursor).toBe("3");
    const next = (await (await api(t, `/v1/admin/programmes/${d.programme_id}/versions?limit=1&cursor=3`)).json()) as { items: { version_no: number }[]; next_cursor: string | null };
    expect(next.items.map((v) => v.version_no)).toEqual([1]); // 2 is skipped
    expect(next.next_cursor).toBeNull();
  });
});

describe("order of checks and replays", () => {
  it("a PATCH judges the full body only after the 409 and 412 checks (a bad date on a live programme is 409, on a stale draft 412)", async () => {
    const c = await superadmin();
    const live = (await list(c)).find((p) => p.code === "ASTHA-Q4")!;
    expect(await codeOf(await direct(c, "PATCH", `/v1/admin/programmes/${live.programme_id}`, progPatch({ request_uuid: U(104), active_from: "2026-02-31" }), { "If-Match": `"${live.version}"` }))).toBe("ERR_REQUEST_STATE");
    const d = (await (await create(c)).json()) as { programme_id: number };
    expect((await direct(c, "PATCH", `/v1/admin/programmes/${d.programme_id}`, progPatch({ request_uuid: U(105), active_from: "2026-02-31" }), { "If-Match": '"9"' })).status).toBe(412);
    expect((await direct(c, "PATCH", `/v1/admin/programmes/${d.programme_id}`, progPatch({ request_uuid: U(106), active_from: "2026-02-31" }), { "If-Match": '"2"' })).status).toBe(400);
  });
  it("status: the ADMIN activation is 403 before the body's later problems are reached in the tx; a replay answers before If-Match is judged; a stale If-Match is 412 before the state check", async () => {
    const s0 = await superadmin();
    const d = (await (await create(s0)).json()) as { programme_id: number };
    expect((await direct(await admin(), "POST", `/v1/admin/programmes/${d.programme_id}/status`, { request_uuid: U(107), to_status: "active", reason: REASON })).status).toBe(403);
    const mv = { request_uuid: U(108), to_status: "active", reason: REASON };
    expect((await direct(s0, "POST", `/v1/admin/programmes/${d.programme_id}/status`, mv, { "If-Match": '"2"' })).status).toBe(200);
    const again = await direct(s0, "POST", `/v1/admin/programmes/${d.programme_id}/status`, mv, { "If-Match": "garbage" }); // replay: even a malformed ETag gets the first result
    expect(again.status).toBe(200);
    expect(((await again.json()) as { status: string; version: number }).version).toBe(3);
    expect((await direct(s0, "POST", `/v1/admin/programmes/${d.programme_id}/status`, { ...mv, request_uuid: U(109), to_status: "inactive" }, { "If-Match": '"1"' })).status).toBe(412); // stale before the state is judged
    expect((await problemOf(await direct(s0, "POST", `/v1/admin/programmes/${d.programme_id}/status`, { ...mv, request_uuid: U(110), to_status: "inactive" }, { "If-Match": "garbage" }))).errors).toEqual([{ pointer: "header.If-Match", code: "invalid_value" }]);
  });
  it("one request_uuid reused across create / update / status is 409 ERR_CONFLICT; a batch_uuid on a second programme too; a gift replay returns the stored first result", async () => {
    const s0 = await superadmin();
    const d = (await (await create(s0, prog({ request_uuid: U(111), code: "RPL-1" }))).json()) as { programme_id: number; version: number };
    const st = await direct(s0, "POST", `/v1/admin/programmes/${d.programme_id}/status`, { request_uuid: U(111), to_status: "active", reason: REASON });
    expect(await codeOf(st)).toBe("ERR_CONFLICT"); // used by the create
    expect((await direct(s0, "POST", `/v1/admin/programmes/${d.programme_id}/status`, { request_uuid: U(112), to_status: "active", reason: REASON })).status).toBe(200);
    expect(await codeOf(await direct(s0, "POST", "/v1/admin/programmes", prog({ request_uuid: U(112), code: "RPL-2" })))).toBe("ERR_CONFLICT"); // used by a status move
    const row = (programme_id: number) => ({ programme_id, outlet_id: 6, tier_code: "gold", valid_from: "2027-01-01" });
    expect((await enrol(s0, 1, { batch_uuid: U(113), rows: [row(1)], change_reason: REASON })).status).toBe(200);
    expect(await codeOf(await enrol(s0, 2, { batch_uuid: U(113), rows: [row(2)], change_reason: REASON }))).toBe("ERR_CONFLICT"); // another programme
    const g = { request_uuid: U(114), programme_id: 2, code: "replay_gift", name_en: "First", name_bn: null, points_cost: 10, tier_codes: [], status: "active", change_reason: REASON };
    const g1 = (await (await gift(s0, g)).json()) as { gift_id: number; name_en: string; version: number };
    await giftUpdate(s0, g1.gift_id, { request_uuid: U(115), name_en: "Second", name_bn: null, points_cost: 10, tier_codes: [], status: "active", change_reason: REASON, version: 1 });
    const replay = (await (await gift(s0, g)).json()) as { name_en: string; version: number };
    expect([replay.name_en, replay.version]).toEqual(["First", 1]); // the stored first result, not the live gift
  });
  it("a gift can be edited on an ended programme (only createGift refuses); the live server has no such check", async () => {
    const c = await superadmin();
    const g = (await (await api(c, "/v1/admin/gifts?programme_id=1")).json()) as { items: { gift_id: number; version: number }[] };
    expect((await giftUpdate(c, g.items[0]!.gift_id, { request_uuid: U(116), name_en: "Rice cooker 2", name_bn: null, points_cost: null, tier_codes: ["gold"], status: "active", change_reason: REASON, version: g.items[0]!.version })).status).toBe(200);
    // programme 4 is ended: create is refused, but its gifts (none in the seed) would be editable; end programme 1 and edit again
    expect((await status(c, 1, { request_uuid: U(117), to_status: "inactive", reason: REASON })).status).toBe(200);
    const after = (await (await api(c, "/v1/admin/gifts?programme_id=1")).json()) as { items: { gift_id: number; version: number }[] };
    expect((await giftUpdate(c, after.items[0]!.gift_id, { request_uuid: U(118), name_en: "Rice cooker 3", name_bn: null, points_cost: null, tier_codes: ["gold"], status: "active", change_reason: REASON, version: after.items[0]!.version })).status).toBe(200);
    expect(await codeOf(await gift(c, { request_uuid: U(119), programme_id: 1, code: "late_one", name_en: "Late", name_bn: null, points_cost: null, tier_codes: [], status: "active", change_reason: REASON }))).toBe("ERR_REQUEST_STATE");
  });
  it("the gift no_change pointer is `body` exactly", async () => {
    const c = await superadmin();
    const res = await giftUpdate(c, 3, { request_uuid: U(120), name_en: "Tornado fan", name_bn: "টর্নেডো ফ্যান", points_cost: 400, tier_codes: [], status: "active", change_reason: REASON, version: 1 });
    expect(res.status).toBe(400);
    expect((await problemOf(res)).errors).toEqual([{ pointer: "body", code: "no_change" }]);
  });
});

describe("row versions are the server's (ETags)", () => {
  it("a created programme is version 2 (ETag \"2\"), one patch makes 3, a status move +1; a gift starts at 1 and each update +1", async () => {
    const c = await superadmin();
    const made = (await (await create(c)).json()) as { programme_id: number; version: number };
    expect(made.version).toBe(2);
    expect((await direct(c, "PATCH", `/v1/admin/programmes/${made.programme_id}`, progPatch({ request_uuid: U(130), name_en: "Changed" }), { "If-Match": '"1"' })).status).toBe(412); // "1" would be stale on the real API
    const upd = await patch(c, made.programme_id, { ...progPatch({ request_uuid: U(131), name_en: "Changed" }), version: 2 });
    expect(upd.status).toBe(200);
    expect(((await upd.json()) as { version: number; version_no: number })).toMatchObject({ version: 3, version_no: 2 });
    const mv = await status(c, made.programme_id, { request_uuid: U(132), to_status: "active", reason: REASON });
    expect(((await mv.json()) as { version: number }).version).toBe(4);
    const g = await gift(c, { request_uuid: U(133), programme_id: 2, code: "v_gift", name_en: "V", name_bn: null, points_cost: 5, tier_codes: [], status: "active", change_reason: REASON });
    const gj = (await g.json()) as { gift_id: number; version: number };
    expect(gj.version).toBe(1);
    const gu = await giftUpdate(c, gj.gift_id, { request_uuid: U(134), name_en: "V2", name_bn: null, points_cost: 5, tier_codes: [], status: "active", change_reason: REASON, version: 1 });
    expect(((await gu.json()) as { version: number }).version).toBe(2);
  });
});

describe("pointers inside tiers[] and rows[]: exactly what the server answers today", () => {
  it("the flat-member helpers name the FLAT member, only the explicitly indexed ones are indexed", async () => {
    const c = await superadmin();
    const first = async (tiers: unknown[]) => (await problemOf(await direct(c, "POST", "/v1/admin/programmes", prog({ tiers })))).errors;
    expect(await first([{ tier_code: "gold", sort_order: 1, label_en: "", threshold: null }])).toEqual([{ pointer: "body.label_en", code: "length" }]); // flat
    expect(await first([{ sort_order: 1, label_en: "G" }])).toEqual([{ pointer: "body.tier_code", code: "required" }]); // flat
    expect(await first([{ tier_code: "G!", sort_order: 1, label_en: "Gold" }])).toEqual([{ pointer: "body.tiers[0].tier_code", code: "pattern" }]); // indexed
    expect(await first([{ tier_code: "gold", label_en: "Gold" }])).toEqual([{ pointer: "body.tiers[0].sort_order", code: "required" }]); // indexed
    expect(await first([{ tier_code: "gold", sort_order: 1, label_en: "Gold", threshold: "1.2345" }])).toEqual([{ pointer: "body.tiers[0].threshold", code: "not_a_decimal" }]); // indexed
    expect(await first([{ tier_code: "gold", sort_order: 1, label_en: "Gold", extra: 1 }])).toEqual([{ pointer: "body.extra", code: "unknown_field" }]); // only(): flat
    const rows = async (row: Record<string, unknown>) => (await problemOf(await direct(c, "PUT", "/v1/admin/programmes/1/enrolments", { batch_uuid: U(140), change_reason: REASON, rows: [{ programme_id: 1, outlet_id: 5, valid_from: "2027-01-01", ...row }] }))).errors;
    expect(await rows({ outlet_id: 0 })).toEqual([{ pointer: "body.outlet_id", code: "out_of_range" }]); // flat
    expect(await rows({ valid_from: "2027-02-31" })).toEqual([{ pointer: "body.valid_from", code: "not_a_date" }]); // flat
    expect(await rows({ valid_from: undefined })).toEqual([{ pointer: "body.rows[0].valid_from", code: "required" }]); // indexed
    expect(await rows({ valid_to: "2026-12-31" })).toEqual([{ pointer: "body.rows[0].valid_to", code: "before_valid_from" }]); // indexed
    expect(await rows({ base_target: "-3" })).toEqual([{ pointer: "body.rows[0].base_target", code: "negative" }]); // indexed
    expect(await rows({ tier_code: "Bad!" })).toEqual([{ pointer: "body.rows[0].tier_code", code: "pattern" }]); // indexed
  });
  it("small server details: league_label \"\" becomes null; qualifying and scope come back sorted by kind then id; the reason is trimmed before 10..500; a uuid is checked up to 64 characters", async () => {
    const c = await superadmin();
    const en = await enrol(c, 1, { batch_uuid: U(141), rows: [{ programme_id: 1, outlet_id: 11, league_label: "", tier_code: "gold", valid_from: "2027-01-01" }], change_reason: REASON });
    expect(en.status).toBe(200);
    expect(((await (await api(c, "/v1/admin/programmes/1/enrolments?limit=1")).json()) as { items: { league_label: string | null }[] }).items[0]!.league_label).toBeNull();
    const made = (await (await direct(c, "POST", "/v1/admin/programmes", prog({ request_uuid: U(142), code: "SRT-1", scope: [{ node_type: "zone", node_id: 14 }, { node_type: "wing", node_id: 1 }, { node_type: "zone", node_id: 2 }], qualifying: [{ product_level: "sku", product_id: 9 }, { product_level: "brand", product_id: 3 }, { product_level: "brand", product_id: 1 }] }))).json()) as { scope: unknown; qualifying: unknown };
    expect(made.scope).toEqual([{ node_type: "wing", node_id: 1 }, { node_type: "zone", node_id: 2 }, { node_type: "zone", node_id: 14 }]);
    expect(made.qualifying).toEqual([{ product_level: "brand", product_id: 1 }, { product_level: "brand", product_id: 3 }, { product_level: "sku", product_id: 9 }]);
    expect((await direct(c, "POST", "/v1/admin/programmes", prog({ request_uuid: U(143), code: "TRM-1", change_reason: "   too short " }))).status).toBe(400); // trimmed to 9
    expect((await direct(c, "POST", "/v1/admin/programmes", prog({ request_uuid: U(144), code: "TRM-2", change_reason: "  ten chars!  " }))).status).toBe(201);
    expect((await problemOf(await direct(c, "POST", "/v1/admin/programmes", prog({ request_uuid: "x".repeat(65), code: "UU-1" })))).errors).toEqual([{ pointer: "body.request_uuid", code: "length" }]);
    expect((await problemOf(await direct(c, "POST", "/v1/admin/programmes", prog({ request_uuid: "not-a-uuid", code: "UU-2" })))).errors).toEqual([{ pointer: "body.request_uuid", code: "not_a_uuid" }]);
  });
});
