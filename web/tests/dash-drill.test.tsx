// Completion pass items 1 to 3: the pure row builders, CSV rules (formula guard, Bengali digits, money from integer milli-taka),
// the auto-refresh rule, the table states, and the drill-down page with a stubbed API.
import type * as ServerMod from "@/lib/dash/server";
import type { ReactElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {} }), notFound() { throw new Error("notFound"); } }));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => ({ at: "tok", scope: { nodes: [] }, user: { role: "TSO" } }) }));
const locale = vi.hoisted(() => ({ v: "en" as "en" | "bn" }));
vi.mock("@/lib/auth/service", () => ({ getLocale: async () => locale.v }));
const stub = vi.hoisted(() => ({ tracking: vi.fn(), summary: vi.fn(), login: vi.fn() }));
vi.mock("@/lib/dash/server", async (orig) => {
  const real = await orig<typeof ServerMod>();
  return { ...real, getDailyTracking: stub.tracking, getSummary: stub.summary, getLoginSubmit: stub.login };
});

import Drill from "@/app/(dashboards)/drill/[kind]/page";
import { DataTable } from "@/components/dash/data-table";
import { formatTakaDisplay } from "@/lib/reports/format";
import { shouldRefresh } from "@/components/dash/table-actions";
import { csvField, csvTaka, toCsv } from "@/lib/dash/csv";
import { callsRows, categoryRoutes, rankRows, srRows, strikeOf, type TrackingRow } from "@/lib/dash/rows";
import { drillHref, isDrillKind } from "@/lib/dash/drill";

const ok = <T,>(data: T) => ({ ok: true as const, status: 200, data });
const tr = (o: Partial<TrackingRow> = {}): TrackingRow => ({ route_id: 1, route_name: "R1", zone_id: 1, user_id: 7, user_name: "Rahim", state: "in_field", target_outlets: 10, visited_outlets: 8, successful_calls: 4, active_memo_count: 4, net_mtk: 120_000, bucket: "below_80", ...o });

describe("row builders", () => {
  it("groups routes of one SR and ranks by net sales; strike is calls over visited", () => {
    const rows = srRows([tr({ route_id: 1, route_name: "A" }), tr({ route_id: 2, route_name: "B", net_mtk: 30_000, successful_calls: 2, visited_outlets: 2 }), tr({ route_id: 3, route_name: "C", user_id: 9, user_name: "Karim", net_mtk: 500_000 })]);
    expect(rows.map((r) => r.sr)).toEqual(["Karim", "Rahim"]);
    expect(rows[1]).toMatchObject({ routes: ["A", "B"], calls: 6, visited: 10, planned: 20, net_mtk: 150_000, strike: 60 });
  });
  it("keeps an unassigned route as its own row and never invents a strike rate", () => {
    const rows = srRows([tr({ user_id: null, user_name: null, visited_outlets: 0, successful_calls: 0 })]);
    expect(rows[0]).toMatchObject({ sr: null, strike: null });
    expect(strikeOf(1, 0)).toBeNull();
  });
  it("ranks children by net and names them", () => {
    const kp = { successful_calls: 3, active_memo_count: 2, strike_rate_pct: 50 };
    const r = rankRows([{ node: { type: "zone", id: 1, name: "Z1" }, kpis: { ...kp, net_mtk: 10 } }, { node: { type: "zone", id: 2, code: "Z2" }, kpis: { ...kp, net_mtk: 99 } }] as never);
    expect(r.map((x) => x.name)).toEqual(["Z2", "Z1"]);
  });
  it("category routes keep only routes that sold the category, in its unit", () => {
    const cat = (net: number) => [{ category_code: "match", base_unit: "dozen" as const, qty_base: 12, net_mtk: net }];
    const r = categoryRoutes([tr({ route_id: 1, by_category: cat(5) }), tr({ route_id: 2, by_category: [] }), tr({ route_id: 3 })], "match");
    expect(r).toHaveLength(1);
    expect(r[0]).toMatchObject({ unit: "dozen", qty: 12 });
  });
  it("calls by route: CPR over planned, strike over visited", () => {
    expect(callsRows([tr()])[0]).toMatchObject({ cpr: 40, strike: 50 });
    expect(callsRows([tr({ target_outlets: 0 })])[0]!.cpr).toBeNull();
  });
});

describe("csv", () => {
  it("quotes, guards formulas, leaves numbers alone, adds a BOM and CRLF", () => {
    expect(csvField('a,"b"')).toBe('"a,""b"""');
    expect(csvField("=SUM(A1)")).toBe("'=SUM(A1)");
    expect(csvField("-12.5")).toBe("-12.5");
    expect(csvField("-৫.৫")).toBe("-৫.৫");
    expect(toCsv(["x"], [["1"]])).toBe("﻿x\r\n1\r\n");
  });
  it("money is taka with three decimals from integer milli-taka; Bengali digits in bn", () => {
    expect(csvTaka("en", 7_935)).toBe("7.935");
    expect(csvTaka("bn", 1_234_567)).toBe("১২৩৪.৫৬৭");
    expect(csvTaka("en", -5)).toBe("-0.005");
  });
});

describe("auto refresh rule", () => {
  it("never faster than 60 s and never while hidden", () => {
    expect(shouldRefresh(59_999, 0, true)).toBe(false);
    expect(shouldRefresh(60_000, 0, true)).toBe(true);
    expect(shouldRefresh(10 * 60_000, 0, false)).toBe(false);
    expect(shouldRefresh(30_000, 0, true, 1000)).toBe(false); // a shorter request is raised to 60 s
  });
});

describe("DataTable states", () => {
  const base = { id: "x", locale: "en" as const, heading: "H", columns: [{ header: "A" }, { header: "B", numeric: true }], csvName: "x", asOf: "2026-10-17T05:00:00Z", businessDate: "2026-10-17", today: "2026-10-17" };
  it("empty message with the as-of stamp and no export buttons", () => {
    const h = renderToStaticMarkup(<DataTable {...base} rows={[]} />);
    expect(h).toContain('data-testid="table-empty-x"');
    expect(h).toContain('data-testid="tile-asof"');
    expect(h).not.toContain("csv-x");
  });
  it("error shows the problem text and Retry", () => {
    const h = renderToStaticMarkup(<DataTable {...base} rows={[]} error="internal" />);
    expect(h).toContain('role="alert"');
    expect(h).toContain('data-testid="retry"');
  });
  it("rows render with CSV and print and a focusable scroll region", () => {
    const h = renderToStaticMarkup(<DataTable {...base} rows={[{ key: "1", cells: ["a", "1"] }]} />);
    expect(h).toContain("csv-x");
    expect(h).toContain('tabindex="0"');
  });
});

describe("drill route", () => {
  beforeEach(() => {
    locale.v = "en";
    stub.tracking.mockReset();
    stub.summary.mockReset();
    stub.login.mockReset();
    stub.tracking.mockResolvedValue(ok({ as_of: "2026-10-17T05:00:00Z", business_date: "2026-10-17", items: [tr({ by_category: [{ category_code: "cigarette", base_unit: "stick", qty_base: 400, net_mtk: 90_000 }] })], next_cursor: null }));
    stub.summary.mockResolvedValue(ok({ as_of: "2026-10-17T05:00:00Z", by_brand: [{ code: "b", name: "Brand B", net_mtk: 5, memo_count: 1, successful_calls: 1 }], by_channel: [{ code: "gt", name: "GT", net_mtk: 5, memo_count: 1, successful_calls: 2 }], children: [] }));
    stub.login.mockResolvedValue(ok({ as_of: "2026-10-17T05:00:00Z", not_logged_in: [{ route_id: 1, route_name: "R1", state: "not_started", business_date: "2026-10-17", submit_cycle: 0, submit_voided: false }], logged_in_not_submitted: [], submitted: [] }));
  });
  const render = async (kind: string, q: Record<string, string> = {}) => renderToStaticMarkup((await Drill({ params: Promise.resolve({ kind }), searchParams: Promise.resolve(q) })) as ReactElement);

  it("category: route table with the unit and the brand table", async () => {
    const h = await render("category", { code: "cigarette" });
    expect(h).toContain("table-cat-routes");
    expect(h).toContain("Stick");
    expect(h).toContain("Brand B");
  });
  it("channel, calls and login pages render their tables; login names the SR from the tracking rows", async () => {
    expect(await render("channel", { code: "gt" })).toContain("table-channels");
    expect(await render("calls")).toContain("table-calls");
    const l = await render("login");
    expect(l).toContain("table-not-logged-in");
    expect(l).toContain("Rahim");
  });
  it("an unknown kind is a 404", async () => {
    await expect(render("nope")).rejects.toThrow("notFound");
    expect(isDrillKind("calls")).toBe(true);
  });
  it("a failed read shows the error state with Retry, not a number", async () => {
    stub.tracking.mockResolvedValue({ ok: false, status: 500, problem: { code: "internal" } });
    const h = await render("calls");
    expect(h).toContain("table-error-calls");
  });
  it("bn: Bengali digits, no Latin digits in the table text", async () => {
    locale.v = "bn";
    const h = await render("calls");
    const text = h.replace(/<[^>]*>/g, " ").replace(/&[a-z#0-9]+;/g, " ");
    expect(text).toMatch(/[০-৯]/);
    expect(text.replace(/R1/g, "")).not.toMatch(/\d/);
  });
  it("keeps scope and date in the links", () => {
    expect(drillHref("category", { code: "bidi", zone: "3", date: "2026-10-16" })).toBe("/drill/category?code=bidi&zone=3&date=2026-10-16");
  });
});

describe("large tables and paisa agreement", () => {
  const base = { id: "big", locale: "en" as const, heading: "H", columns: [{ header: "A" }], csvName: "big", asOf: null, businessDate: "2026-10-17", today: "2026-10-17" };
  it("puts at most 200 rows in the DOM and says how many are held back", () => {
    const rows = Array.from({ length: 450 }, (_, i) => ({ key: String(i), cells: [`r${i}`] }));
    const h = renderToStaticMarkup(<DataTable {...base} rows={rows} />);
    expect((h.match(/<tr/g) ?? []).length).toBe(201); // header + 200
    expect(h).toContain('data-rows="450"');
    expect(h).toContain("more-big");
  });
  it("the CSV amount rounds to the on-screen amount for any milli-taka value", () => {
    let seed = 12345;
    const rnd = () => (seed = (seed * 1103515245 + 12345) % 2147483648);
    for (let i = 0; i < 2000; i++) {
      const mtk = (rnd() % 2_000_000_000) - 500_000_000;
      const screen = formatTakaDisplay("en", mtk).replace(/,/g, "");
      const csv = csvTaka("en", mtk);
      // round the three-decimal CSV text to paisa by integer maths, half away from zero
      const neg = csv.startsWith("-");
      const digits = BigInt(csv.replace("-", "").replace(".", ""));
      const paisa = (digits + 5n) / 10n;
      const back = `${neg && paisa > 0n ? "-" : ""}${paisa / 100n}.${String(paisa % 100n).padStart(2, "0")}`;
      expect(back).toBe(screen);
    }
  });
});
