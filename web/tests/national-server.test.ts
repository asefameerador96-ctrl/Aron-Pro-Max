// The national dashboard adapters: the contract 1.4.0 wire shapes map to the view-model the cards use; scope is never sent, only the filter.
import { beforeEach, describe, expect, it, vi } from "vitest";

const calls: { method: string; path: string; body?: unknown; query?: unknown }[] = [];
let answer: unknown = {};
vi.mock("@/lib/api/raw", () => ({
  rawRequest: vi.fn(async (r: { method: string; path: string; body?: unknown; query?: unknown }) => {
    calls.push(r);
    return { ok: true, status: 200, data: answer };
  }),
}));
const server = await import("@/lib/national/server");
const { parseQuery, pinColor, toSearch, parseBreakdown, achievementPct } = await import("@/lib/national/query");

const stamp = { date: "2026-10-22", till_time: true, as_of: "2026-10-22T06:00:00Z", last_updated_at: "2026-10-22T06:01:00Z" };
beforeEach(() => void (calls.length = 0));

describe("national filter", () => {
  it("parses only known members and clamps the date", () => {
    const q = parseQuery({ date: "2999-01-01", wing: "1,2,x", brand: "maxim,BAD CODE", zone: "7" }, "2026-10-22");
    expect(q).toEqual({ date: "2026-10-22", wing: [1, 2], brand: ["maxim"], zone: [7] });
    expect(toSearch(q).toString()).toBe("date=2026-10-22&wing=1%2C2&zone=7&brand=maxim");
  });
  it("builds the request body with the contract member names and no scope", () => {
    expect(server.toFilter({ date: "2026-10-22", territory: [3], variant: ["v1"] })).toEqual({ date: "2026-10-22", territory_ids: [3], variant_codes: ["v1"] });
  });
  it("pin colours: red below 70, amber 70 to 90 inclusive, green above 90", () => {
    expect([69.99, 70, 90, 90.01].map(pinColor)).toEqual(["red", "amber", "amber", "green"]);
  });
  it("breakdown setting defaults to STD by brand; ring is null without a target", () => {
    expect(parseBreakdown({})).toEqual({ measure: "std", by: "brand" });
    expect(parseBreakdown({ bm: "memo", bb: "variant" })).toEqual({ measure: "memo", by: "variant" });
    expect(achievementPct(50, null)).toBeNull();
    expect(achievementPct(50, 0)).toBeNull();
    expect(achievementPct(50, 200)).toBe(25);
  });
});

describe("national adapters", () => {
  it("sales: POSTs the filter, maps decimals and drops unknown categories", async () => {
    answer = { ...stamp, working_days: 26, cards: [{ category_code: "cigarette", base_unit: "stick", total_sales: 100, daily_target: "200.000", sales_so_far: 100, remaining: "100.000", achievement_pct: 50, target_routes: 3 }, { category_code: "tea", base_unit: "piece", total_sales: 1, daily_target: null, sales_so_far: 1, remaining: null, achievement_pct: null, target_routes: 0 }] };
    const r = await server.getSales("tok", { date: "2026-10-22", zone: [4] });
    expect(calls[0]).toMatchObject({ method: "POST", path: "/v1/dashboards/national/sales", body: { date: "2026-10-22", zone_ids: [4] } });
    expect(r.ok && r.data.cards).toEqual([{ category: "cigarette", unit: "stick", total: 100, daily_target: 200, pct: 50, remaining: 100 }]);
    expect(r.ok && r.data.as_of).toBe("2026-10-22T06:01:00Z"); // "last updated" wins over the generation time
  });
  it("channels come in the owner's order; breakdown asks for one category when one is picked", async () => {
    answer = { ...stamp, channels: [{ channel: "MT", value: 1, share_pct: 1 }, { channel: "GT", value: 9, share_pct: 9 }], total: 10, unclassified: 0 };
    const r = await server.getChannelCalls("tok", { date: "2026-10-22" });
    expect(r.ok && r.data.channels.map((c) => c.code)).toEqual(["GT", "MT"]);
    answer = { ...stamp, measure: "std", dimension: "brand", category_code: "bidi", unit: "stick", items: [{ code: "a", name: null, value: 5 }] };
    const b = await server.getBreakdown("tok", { date: "2026-10-22", category: ["bidi"] }, "std", "brand");
    expect(calls[1]!.body).toMatchObject({ measure: "std", dimension: "brand", category_code: "bidi" });
    expect(b.ok && b.data.rows).toEqual([{ key: "a", label: "a", value: 5, unit: "stick" }]);
  });
  it("route map: outlet states and the time card map to the view-model", async () => {
    answer = { ...stamp, route: { id: 1, code: "R1", name: "Rampasha" }, sr_name: "S", territory: { id: 2, code: "1306", name: "Shahjalal" }, zone: { id: 3, code: "Z", name: "Tajpur" }, sale_start: null, sr_last_position: null, path: [], target_vs_achievement: [{ category_code: "bidi", base_unit: "stick", daily_target: null, sales: 0, achievement_pct: 0 }], cpr: { target_outlets: 50, successful_calls: 15, geo_validated: 15, cpr_pct: 30 }, time_card: { total_sale_seconds: 9180, average_sale_seconds: 612, distance_covered_m: 1196.97 },
      outlets: [{ outlet_id: 9, code: "c", name: null, lat: 1, lng: 2, state: "photo_validation", call_time: "2026-10-22T05:00:00Z", sales: [{ category_code: "bidi", base_unit: "stick", qty: 7 }] }, { outlet_id: 10, code: "d", name: "D", lat: 1, lng: 2, state: "not_called", call_time: null, sales: [] }] };
    const r = await server.getRouteMap("tok", 1, "2026-10-22");
    expect(calls[0]).toMatchObject({ method: "GET", path: "/v1/dashboards/route-map/1" });
    expect(r.ok && r.data.outlets.map((o) => [o.state, o.sales?.bidi ?? null])).toEqual([["photo", 7], ["not_called", null]]);
    expect(r.ok && r.data.time).toMatchObject({ total_hours: 2.55, avg_hours: 0.17, distance_m: 1196.97 });
    expect(r.ok && r.data.territory_code).toBe("1306");
  });
});
