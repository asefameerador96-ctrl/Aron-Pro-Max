// Independent checker (docs/26 s4): F-WEB-045 sync health, F-WEB-047 final-submit panel, F-WEB-067 calibration histogram.
// Failing tests here are evidence of defects against docs/24 s12.4 (KPI definitions) and the contract paging rules.
import type { AddressInfo } from "node:net";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { FinalSubmitPanel } from "@/components/dash/final-submit-panel";
import { chartPoints } from "@/components/reports/bar-chart";
import type { Schemas } from "@/contract/types";
import { latency, rollupByZone, totalsOf } from "@/lib/dash/ops";
import { getDailyTracking, getPendingPhotos, type Kpis } from "@/lib/dash/server";
import { formatCell } from "@/lib/reports/format";
import { createMock } from "../mock/server";

type Row = Schemas["DailyTrackingRow"];
const row = (route_id: number, zone_id: number, state: Row["state"], bucket: Row["bucket"] = "below_80"): Row =>
  ({ route_id, route_name: `R${route_id}`, zone_id, state, target_outlets: 10, visited_outlets: 0, successful_calls: 0, active_memo_count: 0, net_mtk: 0, bucket }) as Row;

describe("F-WEB-045 KPI maths (docs/24 s12.4)", () => {
  it("percentages carry 2 decimals: 2 of 3 logged-in routes submitted is 66.67, not 66.7", () => {
    const zs = rollupByZone([row(1, 1, "sales_submitted"), row(2, 1, "final_submitted"), row(3, 1, "in_field")]);
    expect(zs[0]!.submit_pct).toBe(66.67);
    expect(totalsOf(zs).submit_pct).toBe(66.67);
  });
  it("an approved day exception is not a target route: it is out of the login % denominator", () => {
    const zs = rollupByZone([row(1, 1, "logged_in"), row(2, 1, "not_started", "exception")]);
    expect(zs[0]!.login_pct).toBe(100);
  });
  it("a zone whose only open route is an approved exception counts as final-submitted", () => {
    const zs = rollupByZone([row(1, 1, "final_submitted"), row(2, 1, "not_started", "exception")]);
    expect(zs[0]!.done).toBe(true);
  });
  it("(holds) submit % is of logged-in routes; empty and zero cases are null, never NaN", () => {
    const zs = rollupByZone([row(1, 1, "not_started"), row(2, 1, "not_started")]);
    expect(zs[0]).toMatchObject({ login_pct: 0, submit_pct: null, done: false });
    const z2 = rollupByZone([row(1, 1, "logged_in"), row(2, 1, "sales_submitted"), row(3, 1, "not_started"), row(4, 1, "not_started")]);
    expect(z2[0]).toMatchObject({ login_pct: 50, submit_pct: 50 });
    expect(totalsOf([])).toEqual({ login_pct: null, submit_pct: null, zones_done: 0, zones: 0 });
    expect(latency([])).toEqual({ worst: null, median: null });
  });
  it("report pct cells show 2 decimals (formatCell)", () => {
    expect(formatCell("en", { key: "p", label_en: "P", type: "pct" } as never, 66.67, { yes: "y", no: "n" })).toBe("66.67%");
  });
});

describe("F-WEB-045 paging: figures come from every page, not the first 500 rows", () => {
  const mock = createMock({ accessTtlS: 900 });
  let token = "";
  beforeAll(async () => {
    await new Promise<void>((r) => mock.server.listen(0, "127.0.0.1", r));
    process.env.ARON_API_BASE_URL = `http://127.0.0.1:${(mock.server.address() as AddressInfo).port}`;
  });
  afterAll(() => new Promise<void>((r) => mock.server.close(() => r())));
  beforeEach(async () => {
    mock.reset();
    token = ((await (await fetch(`${process.env.ARON_API_BASE_URL}/v1/auth/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username: "analyst1", password: "analyst-pass-1", client: "web" }) })).json()) as { access_token: string }).access_token;
  });
  it("daily tracking follows next_cursor (a national reach has thousands of routes; limit max is 500)", async () => {
    mock.state.stubs.push({ method: "GET", path: "/v1/dashboards/daily-tracking", fn: (c) => (c.query.cursor === "p2" ? { status: 200, body: { items: [row(2, 1, "not_started")], next_cursor: null } } : { status: 200, body: { items: [row(1, 1, "final_submitted")], next_cursor: "p2" } }) });
    const r = await getDailyTracking(token, "2026-10-07");
    if (!r.ok) throw new Error("no data");
    expect(r.data.items).toHaveLength(2);
  });
  it("pending photos sum every device page", async () => {
    const dev = (n: number) => ({ device_id: n, last_status: { pending_media: 3 } });
    mock.state.stubs.push({ method: "GET", path: "/v1/admin/devices", fn: (c) => (c.query.cursor === "p2" ? { status: 200, body: { items: [dev(2)], next_cursor: null } } : { status: 200, body: { items: [dev(1)], next_cursor: "p2" } }) });
    expect(await getPendingPhotos(token)).toBe(6);
  });
});

describe("F-WEB-047 final-submit badges", () => {
  const k = (o: Partial<Kpis>): Kpis => ({ zones_with_target_routes: 1, zones_final_submitted: 0, submit_pct_of_logged_in: 100, day_completion_pct: 100, ...o }) as Kpis;
  it("a zone with every route sales-submitted but none final-submitted is Pending (day_completion_pct is sales_submitted / target)", () => {
    const zones = [{ node: { type: "zone", id: 7, name: "Z7" }, kpis: k({ day_completion_pct: 100, zones_final_submitted: 0, zones_with_target_routes: 1 }) }] as never;
    const html = renderToStaticMarkup(createElement(FinalSubmitPanel, { locale: "en", kpis: k({}), zones }));
    expect(html).toMatch(/data-zone="7" data-state="pending"/);
  });
  it("(holds) remaining never goes negative", () => {
    const html = renderToStaticMarkup(createElement(FinalSubmitPanel, { locale: "en", kpis: k({ zones_with_target_routes: 1, zones_final_submitted: 3 }), zones: [] }));
    const counts = /data-testid="final-submit-counts">([^<]*)</.exec(html)?.[1] ?? "";
    expect(counts).toMatch(/0/);
    expect(counts).not.toMatch(/-/);
  });
});

describe("F-WEB-067 geofence calibration histogram", () => {
  it("one bar per distance band even when the result has several geo classes / territories", () => {
    const rows = ["Urban", "Rural"].flatMap((g) => ["0-25", "25-50"].map((b) => ({ distance_band_m: b, geo_class: g, territory: "T", visits: 10 })));
    const pts = chartPoints(rows, "distance_band_m", "visits")!;
    expect(new Set(pts.map((p) => p.label)).size).toBe(pts.length); // React keys are the label: duplicates collide
    expect(pts.find((p) => p.label === "0-25")?.value).toBe(20);
  });
  it("(holds) a null or non-numeric value disables the chart rather than drawing garbage", () => {
    expect(chartPoints([{ distance_band_m: "0-25", visits: "x" }], "distance_band_m", "visits")).toBeNull();
    expect(chartPoints([], "distance_band_m", "visits")).toBeNull();
  });
});
