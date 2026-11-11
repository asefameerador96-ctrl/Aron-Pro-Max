// Discount-programme BFF routes against the in-process mock of contract 1.12.0 (portal-discount-web): create, update, status, replay by
// request_uuid, conflicts, grants and roles. The mock follows the contract; the real routes (Lane A) replace it in a later train.
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { POST as loginPost } from "@/app/api/bff/login/route";
import { POST as mfaPost } from "@/app/api/bff/mfa/verify/route";
import { POST as createPost } from "@/app/api/bff/admin/offers/route";
import { PATCH as updatePatch } from "@/app/api/bff/admin/offers/[id]/route";
import { POST as statusPost } from "@/app/api/bff/admin/offers/[id]/status/route";
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
const admin = () => as("madmin1", "admin-pass-1", "123456"); // ADMIN without a module filter in the mock: write on discounts
const reader = () => as("mdisc1", "disc-pass-1", "123456"); // ADMIN with discounts: r
const ungranted = () => as("mgrant1", "grant-pass-1", "123456"); // ADMIN whose grants omit discounts

const U = (n: number) => `0b6d6c0e-8a5b-4d56-9f0a-${String(n).padStart(12, "0")}`;
const body = (over: Record<string, unknown> = {}) => ({
  request_uuid: U(1), code: "NEW-1", group_code: "NEW-1", offer_type: "pct_discount", level: "line", title_en: "New offer", title_bn: null, valid_from: "2026-11-15", valid_to: "2099-12-31",
  threshold_qty_base: null, discount_bp: 500, discount_mtk_per_unit: null, reward_sku_id: null, reward_qty_base: null, qualifying: [{ product_level: "sku", product_id: 2 }], scope: [], stacking: "exclusive", change_reason: "Owner asked for this offer", ...over,
});
const create = async (c: Record<string, string>, b = body()) => createPost(req("/api/bff/admin/offers", "POST", b, c));
const status = (c: Record<string, string>, id: number, b: Record<string, unknown>) => statusPost(req(`/api/bff/admin/offers/${id}/status`, "POST", b, c), { params: Promise.resolve({ id: String(id) }) });
const patch = (c: Record<string, string>, id: number, b: Record<string, unknown>) => updatePatch(req(`/api/bff/admin/offers/${id}`, "PATCH", b, c), { params: Promise.resolve({ id: String(id) }) });
const api = async (c: Record<string, string>, path: string) => (await fetch(`${base}${path}`, { headers: { Authorization: `Bearer ${(await sessionToken(c))}` } }));
async function sessionToken(c: Record<string, string>): Promise<string> {
  const { readSession } = await import("@/lib/auth/session");
  return readSession(c[SESSION_COOKIE])!.at;
}

describe("create (a draft, version 1) and its replay", () => {
  it("creates a draft; the same request_uuid and body replays the first result; another body or another code is a conflict", async () => {
    const c = await superadmin();
    const r1 = await create(c);
    expect(r1.status).toBe(201);
    const first = await r1.json();
    expect(first).toMatchObject({ code: "NEW-1", status: "draft", version_no: 1, discount_bp: 500 });
    const again = await create(c);
    expect(again.status).toBe(201);
    expect((await again.json()).id).toBe(first.id);
    expect((await create(c, body({ discount_bp: 600 }))).status).toBe(409); // same uuid, different content
    expect((await create(c, body({ request_uuid: U(2) }))).status).toBe(409); // code exists
    const list = await (await api(c, "/v1/admin/offers")).json();
    expect(list.items.filter((o: { code: string }) => o.code === "NEW-1")).toHaveLength(1);
  });
  it("an offer with no product rows (all products) is accepted", async () => {
    const res = await create(await superadmin(), body({ qualifying: [] }));
    expect(res.status).toBe(201);
    expect((await res.json()).qualifying).toEqual([]);
  });
  it("an ADMIN with the write grant creates; a reader, an ungranted ADMIN and a TSO are refused 403", async () => {
    expect((await create(await admin())).status).toBe(201);
    expect((await create(await reader(), body({ request_uuid: U(3), code: "R-1" }))).status).toBe(403);
    expect((await create(await ungranted(), body({ request_uuid: U(4), code: "G-1" }))).status).toBe(403);
    expect((await create(await as("tso334", "tso-pass-1"), body({ request_uuid: U(5), code: "T-1" }))).status).toBe(403);
  });
  it("refuses a body the contract refuses: unknown member, bad code, drp_slide, missing money for the type, short reason, bad uuid", async () => {
    const c = await superadmin();
    for (const bad of [{ extra: 1 }, { code: "-x" }, { offer_type: "drp_slide" }, { offer_type: "amount_per_unit", discount_bp: null }, { change_reason: "short" }, { request_uuid: "nope" }, { discount_bp: 10001 }, { valid_to: "bad" }, { scope: [{ node_type: "channel", node_id: 1 }] }]) {
      const res = await create(c, body(bad));
      expect(res.status, JSON.stringify(bad)).toBe(400);
    }
  });
});

describe("update (a new version while draft)", () => {
  it("appends a version with If-Match, replays by request_uuid, and refuses a stale version or an active offer", async () => {
    const c = await superadmin();
    const id = (await (await create(c)).json()).id as number;
    const ok = await patch(c, id, { version: 1, request_uuid: U(10), change_reason: "Lower the percentage a bit", discount_bp: 300 });
    expect(ok.status).toBe(200);
    expect(await ok.json()).toMatchObject({ discount_bp: 300, version_no: 2, version: 2 });
    expect((await patch(c, id, { version: 1, request_uuid: U(10), change_reason: "Lower the percentage a bit", discount_bp: 300 })).status).toBe(200); // replay answered before If-Match
    expect((await patch(c, id, { version: 1, request_uuid: U(11), change_reason: "Stale version retry here", discount_bp: 200 })).status).toBe(412);
    expect((await patch(c, id, { version: 2, request_uuid: U(10), change_reason: "Same uuid another body now", discount_bp: 100 })).status).toBe(409);
    const versions = await (await api(c, `/v1/admin/offers/${id}/versions`)).json();
    expect(versions.items.map((v: { version_no: number; is_current: boolean }) => [v.version_no, v.is_current])).toEqual([[2, true], [1, false]]);
    expect((await status(c, id, { request_uuid: U(12), to_status: "active", reason: "Go live for Ramadan" })).status).toBe(200);
    expect((await patch(c, id, { version: 3, request_uuid: U(13), change_reason: "Edit after activation", discount_bp: 100 })).status).toBe(409);
  });
  it("a reader cannot update; a status member is refused", async () => {
    const id = (await (await create(await superadmin())).json()).id as number;
    expect((await patch(await reader(), id, { version: 1, request_uuid: U(20), change_reason: "Reader tries an edit", discount_bp: 100 })).status).toBe(403);
    expect((await patch(await superadmin(), id, { version: 1, request_uuid: U(21), change_reason: "Try to set the status", status: "active" })).status).toBe(400);
  });
});

describe("status (activate, end) with its reason and request_uuid", () => {
  it("only a Super Admin activates; the moves are draft -> active -> inactive; a replay returns the first result; anything else is 409", async () => {
    const sa = await superadmin();
    const id = (await (await create(sa)).json()).id as number;
    expect((await status(await admin(), id, { request_uuid: U(30), to_status: "active", reason: "Admin tries to activate" })).status).toBe(403);
    expect((await status(sa, id, { request_uuid: U(31), to_status: "inactive", reason: "End before it started" })).status).toBe(409);
    const on = await status(sa, id, { request_uuid: U(32), to_status: "active", reason: "Go live for Ramadan" });
    expect(on.status).toBe(200);
    expect((await on.json()).status).toBe("active");
    expect((await status(sa, id, { request_uuid: U(32), to_status: "active", reason: "Go live for Ramadan" })).status).toBe(200);
    expect((await status(sa, id, { request_uuid: U(32), to_status: "inactive", reason: "Same uuid another move" })).status).toBe(409);
    const off = await status(await admin(), id, { request_uuid: U(33), to_status: "inactive", reason: "Programme finished early" });
    expect((await off.json()).status).toBe("inactive");
    expect((await status(sa, id, { request_uuid: U(34), to_status: "active", reason: "Try to reopen it again" })).status).toBe(409);
  });
  it("needs a reason of 10 to 500 characters and a valid_to that is not in the past", async () => {
    const sa = await superadmin();
    const past = (await (await create(sa, body({ request_uuid: U(40), code: "OLD-1", valid_from: "2026-01-01", valid_to: "2026-01-31" }))).json()).id as number;
    expect((await status(sa, past, { request_uuid: U(41), to_status: "active", reason: "Activate an expired offer" })).status).toBe(400);
    const id = (await (await create(sa, body({ request_uuid: U(42), code: "OK-1" }))).json()).id as number;
    expect((await status(sa, id, { request_uuid: U(43), to_status: "active", reason: "short" })).status).toBe(400);
    expect((await status(sa, id, { request_uuid: U(44), to_status: "active", reason: "x".repeat(501) })).status).toBe(400);
    expect((await status(sa, id, { request_uuid: U(45), to_status: "draft", reason: "Move back to draft" })).status).toBe(400);
    expect((await status(sa, 99999, { request_uuid: U(46), to_status: "active", reason: "Unknown programme here" })).status).toBe(404);
  });
  it("is audited with the reason", async () => {
    const sa = await superadmin();
    const id = (await (await create(sa)).json()).id as number;
    await status(sa, id, { request_uuid: U(50), to_status: "active", reason: "Go live for Ramadan" });
    const audit = await (await api(sa, `/v1/admin/audit?entity=offer&entity_id=${id}`)).json();
    expect(audit.items.map((e: { action: string; reason: string }) => [e.action, e.reason])).toEqual(expect.arrayContaining([["create", "Owner asked for this offer"], ["activate", "Go live for Ramadan"]]));
  });
});

describe("reads through the mock (contract shape)", () => {
  it("lists newest first with status filter and cursor paging, serves one with its ETag, and a reader may read", async () => {
    const r = await reader();
    const all = await (await api(r, "/v1/admin/offers?limit=2")).json();
    expect(all.items).toHaveLength(2);
    expect(all.next_cursor).toBeTruthy();
    const rest = await (await api(r, `/v1/admin/offers?limit=2&cursor=${all.next_cursor}`)).json();
    expect(rest.items).toHaveLength(2);
    expect(rest.next_cursor).toBeNull();
    const drafts = await (await api(r, "/v1/admin/offers?status=draft")).json();
    expect(drafts.items.map((o: { code: string }) => o.code)).toEqual(["ODD-QTY", "MAXR-PKT"]);
    expect((await api(r, "/v1/admin/offers?status=bogus")).status).toBe(400);
    const one = await api(r, `/v1/admin/offers/${drafts.items[1].id}`);
    expect(one.headers.get("etag")).toBe('"1"');
    expect((await one.json()).sku_units).toEqual([{ sku_id: 2, base_unit: "stick", pack_factor: 20, field_unit: "packet" }]);
    expect((await api(r, "/v1/admin/offers/9999")).status).toBe(404);
    expect((await api(await ungranted(), "/v1/admin/offers")).status).toBe(403);
    expect((await api(await as("tso334", "tso-pass-1"), "/v1/admin/offers")).status).toBe(403);
  });
});
