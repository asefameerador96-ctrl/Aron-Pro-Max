// tso-manual W1 (build contract s5 W1 "web/mock fixtures for every new op"): the contract mock answers the operations of contract
// 1.10.0 beyond the support desk the way the contract describes them, so the web (and anyone developing against the mock) sees
// real shapes: the notifications inbox (own rows, newest first, read up to an id, idempotent), Brand CPR (integer basis points,
// both denominators, sorted by outlets bought) and Segment Share (value share of all value, volume share inside the unit family).
import { describe, expect, it } from "vitest";
import type { Schemas } from "@/contract/types";
import { setupMock, signIn, token } from "./helpers/harness";

setupMock();
const base = () => process.env.ARON_API_BASE_URL!;
async function call<T>(who: [string, string, boolean?], method: string, path: string, body?: unknown): Promise<{ status: number; body: T }> {
  const at = token(await signIn(who[0], who[1], who[2] ?? false));
  const res = await fetch(`${base()}${path}`, { method, headers: { Authorization: `Bearer ${at}`, "Content-Type": "application/json" }, body: body === undefined ? undefined : JSON.stringify(body) });
  return { status: res.status, body: (await res.json()) as T };
}
const TSO: [string, string] = ["tso334", "tso-pass-1"];
const NAT: [string, string] = ["nat1", "nat-pass-1"];
const half = (n: number, d: number) => Math.floor((20_000 * n + d) / (2 * d));

describe("GET /v1/me/notifications and POST /v1/me/notifications/read (D-TSO-MAN-26)", () => {
  it("own rows newest first with the unread count; another user's inbox is empty", async () => {
    const r = await call<Schemas["NotificationInbox"]>(TSO, "GET", "/v1/me/notifications");
    expect(r.status).toBe(200);
    expect(r.body.items.map((i) => i.id)).toEqual([13, 12, 11]);
    expect(r.body.unread_count).toBe(2);
    expect(r.body.items.map((i) => i.kind).sort()).toEqual(["final_submit_auto", "leave_decided", "ticket_updated"]);
    expect((await call<Schemas["NotificationInbox"]>(["tso335", "tso-pass-2"], "GET", "/v1/me/notifications")).body).toMatchObject({ items: [], unread_count: 0 });
  });
  it("pages with before_id and limit, and refuses bad paging values", async () => {
    const p1 = await call<Schemas["NotificationInbox"]>(TSO, "GET", "/v1/me/notifications?limit=2");
    expect(p1.body.items.map((i) => i.id)).toEqual([13, 12]);
    expect(p1.body.next_before_id).toBe(12);
    const p2 = await call<Schemas["NotificationInbox"]>(TSO, "GET", `/v1/me/notifications?limit=2&before_id=${p1.body.next_before_id}`);
    expect(p2.body.items.map((i) => i.id)).toEqual([11]);
    expect(p2.body.next_before_id).toBeNull();
    expect((await call(TSO, "GET", "/v1/me/notifications?limit=0")).status).toBe(400);
    expect((await call(TSO, "GET", "/v1/me/notifications?limit=101")).status).toBe(400);
    expect((await call(TSO, "GET", "/v1/me/notifications?before_id=x")).status).toBe(400);
  });
  it("read up to an id marks those rows only; a repeat changes nothing", async () => {
    expect((await call<Schemas["NotificationReadResult"]>(TSO, "POST", "/v1/me/notifications/read", { up_to_id: 12 })).body).toEqual({ unread_count: 1 });
    expect((await call<Schemas["NotificationReadResult"]>(TSO, "POST", "/v1/me/notifications/read", { up_to_id: 12 })).body).toEqual({ unread_count: 1 });
    expect((await call<Schemas["NotificationReadResult"]>(TSO, "POST", "/v1/me/notifications/read", { up_to_id: 13 })).body).toEqual({ unread_count: 0 });
    expect((await call(TSO, "POST", "/v1/me/notifications/read", { up_to_id: 0 })).status).toBe(400);
    expect((await call(TSO, "POST", "/v1/me/notifications/read", { up_to_id: 1, extra: true })).status).toBe(400);
  });
});

describe("POST /v1/dashboards/national/brand-cpr (D-TSO-MAN-06)", () => {
  it("integer bp from the counts, both denominators, sorted by outlets bought then code", async () => {
    const r = await call<Schemas["NationalBrandCpr"]>(NAT, "POST", "/v1/dashboards/national/brand-cpr", {});
    expect(r.status).toBe(200);
    expect(r.body.denominator).toBe("successful_calls");
    const b = r.body.brands;
    expect(b.length).toBeGreaterThan(1);
    for (let i = 1; i < b.length; i++) expect(b[i - 1]!.outlets_bought > b[i]!.outlets_bought || (b[i - 1]!.outlets_bought === b[i]!.outlets_bought && b[i - 1]!.brand_code < b[i]!.brand_code)).toBe(true);
    for (const x of b) {
      expect(x.cpr_of_successful_bp).toBe(half(x.outlets_bought, r.body.successful_calls));
      expect(x.cpr_of_target_bp).toBe(half(x.outlets_bought, r.body.target_outlets));
      expect(x.cpr_bp).toBe(x.cpr_of_successful_bp);
      expect(Number.isInteger(x.cpr_bp)).toBe(true);
    }
  });
  it("a BRAND user gets 403 (as the CPR card)", async () => {
    expect((await call(["brand1", "brand-pass-1"], "POST", "/v1/dashboards/national/brand-cpr", {})).status).toBe(403);
  });
});

describe("POST /v1/dashboards/national/segment-share (D-TSO-MAN-11)", () => {
  it("value share of all value; volume share only inside the segment's unit family; sorted by value", async () => {
    const r = await call<Schemas["NationalSegmentShare"]>(NAT, "POST", "/v1/dashboards/national/segment-share", {});
    expect(r.status).toBe(200);
    const s = r.body.segments;
    expect(s.reduce((a, x) => a + x.value_mtk, 0)).toBe(r.body.total_value_mtk);
    for (let i = 1; i < s.length; i++) expect(s[i - 1]!.value_mtk).toBeGreaterThanOrEqual(s[i]!.value_mtk);
    for (const x of s) {
      const fam = r.body.families.find((f) => f.base_unit === x.base_unit)!;
      expect(x.volume_share_bp).toBe(half(x.qty_base, fam.total_qty_base));
      expect(x.value_share_bp).toBe(half(x.value_mtk, r.body.total_value_mtk));
    }
    for (const f of r.body.families) expect(f.total_qty_base).toBe(s.filter((x) => x.base_unit === f.base_unit).reduce((a, x) => a + x.qty_base, 0)); // sticks, pieces and dozens never added together
  });
  it("a BRAND user may read it (a product card, as queryNationalSales)", async () => {
    expect((await call(["brand1", "brand-pass-1"], "POST", "/v1/dashboards/national/segment-share", {})).status).toBe(200);
  });
});
