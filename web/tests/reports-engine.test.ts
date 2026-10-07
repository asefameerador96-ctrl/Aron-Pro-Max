// Report engine: query building, scope binding (F-WEB-041), PII masking, formatting, Excel pass-through (F-WEB-040) against the mock.
import type { AddressInfo } from "node:net";
import { NextRequest } from "next/server";
import { afterAll, beforeAll, beforeEach, describe, expect, it } from "vitest";
import { GET as exportGet } from "@/app/api/bff/reports/[slug]/export/route";
import { SESSION_COOKIE, SESSION_PURPOSE } from "@/lib/auth/cookies";
import { seal } from "@/lib/auth/seal";
import type { SessionData } from "@/lib/auth/session";
import type { ScopeSummary } from "@/contract/types";
import { en } from "@/lib/i18n/messages-en";
import { WEB_REPORTS, reportBySlug } from "@/lib/reports/catalog";
import { formatCell, formatTaka, mtkToTaka } from "@/lib/reports/format";
import { bindGeo, boundGeo, buildReportQuery, withFormat } from "@/lib/reports/query";
import { runReportJson } from "@/lib/reports/server";
import { createMock } from "../mock/server";
import { ROUTES, controlTotals, routesInScope } from "../mock/seed";

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

const terr = (id: number): ScopeSummary => ({ scope_version: 1, nodes: [{ type: "territory", id, code: null, name: null }] });
const national: ScopeSummary = { scope_version: 1, nodes: [{ type: "national", id: 0 }] };

async function login(username: string, password: string): Promise<{ at: string; scope: ScopeSummary; cookie: string }> {
  const r = await fetch(`${base}/v1/auth/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username, password, client: "web" }) });
  const b = (await r.json()) as { access_token: string; scope: ScopeSummary; user: SessionData["user"] };
  const session: SessionData = { at: b.access_token, atExp: Date.now() + 600_000, user: b.user, scope: b.scope };
  return { at: b.access_token, scope: b.scope, cookie: `${SESSION_COOKIE}=${seal(session, SESSION_PURPOSE, 600)}` };
}

describe("catalog", () => {
  it("has unique slugs and rows, every title in both catalogues, and no deferred programme", () => {
    expect(new Set(WEB_REPORTS.map((r) => r.slug)).size).toBe(WEB_REPORTS.length);
    expect(new Set(WEB_REPORTS.map((r) => r.row)).size).toBe(WEB_REPORTS.length);
    for (const r of WEB_REPORTS) expect(en[r.titleKey]).toBeTruthy();
    const banned = /astha|diamond|superstar|campaign|target-allocation|discount/;
    for (const r of WEB_REPORTS) expect(`${r.key} ${r.slug}`).not.toMatch(banned);
  });
});

describe("scope binding (F-WEB-041)", () => {
  it("a national user may narrow at every level; a territory user has wing, division and territory fixed", () => {
    expect(bindGeo(national).locked).toEqual([]);
    expect(bindGeo(terr(334)).locked).toEqual(["wing", "division", "territory"]);
  });
  it("drops locked levels so the client never sends scope ids", () => {
    expect(boundGeo(terr(334), { wing: [1], division: [10], territory: [335], zone: [3341] })).toEqual({ zone: [3341] });
    expect(boundGeo(terr(334), { territory: [335] })).toBeUndefined();
  });
  it("two own territories can be narrowed, but only to their own ids", () => {
    const two: ScopeSummary = { scope_version: 1, nodes: [{ type: "territory", id: 334 }, { type: "territory", id: 335 }] };
    expect(boundGeo(two, { territory: [334, 999] })).toEqual({ territory: [334] });
  });
  it("no scope at all locks everything", () => {
    expect(boundGeo(null, { zone: [1] })).toBeUndefined();
  });
});

describe("query builder", () => {
  const rep = reportBySlug("route-std")!;
  it("defaults to today's Dhaka business date and 50 rows", () => {
    const q = buildReportQuery(rep, {}, terr(334), "json", "2026-10-07");
    expect(q.period).toEqual({ from: "2026-10-07", to: "2026-10-07" });
    expect(q.output).toMatchObject({ format: "json", page: 1, page_size: 50 });
  });
  it("swaps an inverted range and ignores junk", () => {
    const q = buildReportQuery(rep, { from: "2026-10-09", to: "2026-10-01", size: "7", page: "-3", category: "x,5" }, null, "json", "2026-10-07");
    expect(q.period).toEqual({ from: "2026-10-01", to: "2026-10-09" });
    expect(q.output.page_size).toBe(50);
    expect(q.output.page).toBe(1);
    expect(q.category).toEqual([5]);
  });
  it("the Excel query is the screen's query with another format (F-WEB-040)", () => {
    const params = { from: "2026-10-01", to: "2026-10-05", zone: "3341", products: "1,2", page: "3", size: "25" };
    const screen = buildReportQuery(rep, params, national, "json", "2026-10-07");
    const excel = withFormat(screen, "xlsx");
    expect({ ...excel, output: undefined }).toEqual({ ...screen, output: undefined });
    expect(excel.output.format).toBe("xlsx");
  });
  it("single-date reports take one date", () => {
    expect(buildReportQuery(reportBySlug("gigo")!, { date: "2026-10-03" }, null, "json", "2026-10-07").period).toEqual({ date: "2026-10-03" });
  });
  it("accepts std criteria as operator plus value", () => {
    const q = buildReportQuery(reportBySlug("std-memo")!, { std_op: ">=", std_val: "2.5" }, null);
    expect(q.std_criteria).toEqual({ op: ">=", value: 2.5 });
    expect(buildReportQuery(reportBySlug("std-memo")!, { std: "drop table" }, null).std_criteria).toBeUndefined();
  });
});

describe("formatting", () => {
  it("shows milli-taka with three decimals by integer maths", () => {
    expect(mtkToTaka(7935)).toBe("7.935");
    expect(mtkToTaka(5)).toBe("0.005");
    expect(mtkToTaka(-12_500)).toBe("-12.500");
    expect(mtkToTaka(9_007_199_254_740_993n.toString())).toBe("9007199254740.993");
  });
  it("uses Bengali digits in bn and Latin in en", () => {
    const col = { key: "x", label_en: "x", type: "mtk" as const };
    expect(formatCell("bn", col, 7935, { yes: "", no: "" })).toBe("৭.৯৩৫");
    expect(formatCell("en", col, 7935, { yes: "", no: "" })).toBe("7.935");
    expect(formatCell("en", { key: "p", label_en: "p", type: "pct" }, 2.33, { yes: "", no: "" })).toBe("2.3%");
    expect(formatTaka("en", 543_435_000)).toBe("543,435.000");
    expect(formatTaka("bn", 12_345_678_900)).toBe("১,২৩,৪৫,৬৭৮.৯০০");
    expect(formatTaka("en", -5)).toBe("-0.005");
  });
});

describe("reports against the seeded day", () => {
  it("every catalogued report answers, and route-std equals the control totals of the territory (and no more)", async () => {
    const me = await login("tso334", "tso-pass-1");
    const ct = controlTotals(routesInScope(me.scope.nodes));
    const r = await runReportJson(me.at, "route-std", buildReportQuery(reportBySlug("route-std")!, {}, me.scope));
    expect(r.ok).toBe(true);
    if (!r.ok) return;
    expect(r.data.total_rows).toBe(ct.routes);
    expect(r.data.totals).toMatchObject({ target_outlets: ct.target_outlets, visited: ct.visited, successful: ct.successful });
    expect(ct.routes).toBe(ROUTES.filter((x) => x.territory_id === 334).length);
  });
  it("a user outside the scope sees no rows", async () => {
    const me = await login("tso999", "tso-pass-3");
    const r = await runReportJson(me.at, "route-std", buildReportQuery(reportBySlug("route-std")!, {}, me.scope));
    expect(r.ok && r.data.rows).toEqual([]);
  });
  it("PII columns are empty for a role without the permission and present for one with it", async () => {
    const tso = await login("tso334", "tso-pass-1");
    const analyst = await login("analyst1", "analyst-pass-1");
    const q = (s: ScopeSummary) => buildReportQuery(reportBySlug("retailers")!, {}, s);
    const a = await runReportJson(tso.at, "retailer-list", q(tso.scope));
    const b = await runReportJson(analyst.at, "retailer-list", q(analyst.scope));
    expect(a.ok && a.data.rows.every((x) => x.owner_name === null && x.phone === null)).toBe(true);
    expect(b.ok && b.data.rows.some((x) => typeof x.phone === "string")).toBe(true);
  });
});

describe("export route", () => {
  const call = (cookie: string, path: string) => exportGet(new NextRequest(`http://localhost:3000${path}`, { headers: { cookie, host: "localhost:3000" } }), { params: Promise.resolve({ slug: path.split("/")[4]!.split("?")[0]! }) });
  it("streams the workbook for the screen's filters and the server logs the export", async () => {
    const me = await login("tso334", "tso-pass-1");
    const res = await call(me.cookie, "/api/bff/reports/route-std/export?from=2026-10-07&to=2026-10-07&zone=3341&format=xlsx&page=2");
    expect(res.status).toBe(200);
    expect(res.headers.get("content-type")).toContain("spreadsheetml");
    expect(res.headers.get("content-disposition")).toMatch(/aron-route-std-.*\.xlsx/);
    const text = await res.text();
    expect(text.split("\n")[0]).toContain("WATERMARK");
    const log = (await (await fetch(`${base}/__mock/state`)).json()) as { exports: { reportKey: string; format: string; query: { geo?: unknown } }[] };
    expect(log.exports).toHaveLength(1);
    expect(log.exports[0]).toMatchObject({ reportKey: "route-std", format: "xlsx" });
    expect(log.exports[0]!.query.geo).toEqual({ zone: [3341] });
  });
  it("refuses an unknown report and a user without a session", async () => {
    const me = await login("tso334", "tso-pass-1");
    expect((await call(me.cookie, "/api/bff/reports/nope/export?format=xlsx")).status).toBe(404);
    expect((await call("", "/api/bff/reports/route-std/export?format=xlsx")).status).toBe(401);
  });
  it("print view only for reports that offer it", async () => {
    const me = await login("tso334", "tso-pass-1");
    const ok = await call(me.cookie, "/api/bff/reports/ds-rrs/export?format=print&date=2026-10-07");
    expect(ok.headers.get("content-type")).toContain("text/html");
    expect(ok.headers.get("content-security-policy")).toContain("sandbox");
    const other = await call(me.cookie, "/api/bff/reports/route-std/export?format=print");
    expect(other.headers.get("content-type")).toContain("spreadsheetml");
  });
});
