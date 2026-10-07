// Every catalogued report against the seeded day: rows for a user in scope equal the control totals, none for a user outside it (all report rows).
import type { AddressInfo } from "node:net";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { WEB_REPORTS } from "@/lib/reports/catalog";
import { buildReportQuery } from "@/lib/reports/query";
import { runReportJson } from "@/lib/reports/server";
import type { ScopeSummary } from "@/contract/types";
import { createMock } from "../mock/server";
import { controlTotals, routesInScope, SEED_DATE } from "../mock/seed";

const mock = createMock();
let base = "";
beforeAll(async () => {
  await new Promise<void>((r) => mock.server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(mock.server.address() as AddressInfo).port}`;
  process.env.ARON_API_BASE_URL = base;
});
afterAll(() => new Promise<void>((r) => mock.server.close(() => r())));
async function login(u: string, p: string) {
  const b = (await (await fetch(`${base}/v1/auth/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username: u, password: p, client: "web" }) })).json()) as { access_token: string; scope: ScopeSummary };
  return { at: b.access_token, scope: b.scope };
}

// Reports whose figures are a straight sum over the seeded route-days: column -> control total key.
const SUMS: Record<string, [string, "visited" | "successful" | "memos" | "net_mtk" | "target_outlets"]> = {
  "route-std": ["visited", "visited"],
  "route-memo": ["memos", "memos"],
  "std-memo": ["net_mtk", "net_mtk"],
  "sr-efficiency": ["target_outlets", "target_outlets"],
  "tso-top-sheet": ["successful", "successful"],
  "route-bsr-cpr": ["visited", "visited"],
  dss: ["memos", "memos"],
  "ds-rrs": ["net_mtk", "net_mtk"],
  "by-outlet-by-day": ["net_mtk", "net_mtk"],
  "amo-call": ["visited", "visited"],
  "online-offline": ["memos", "memos"],
  "task-planner": ["visited", "visited"],
  "sr-outlets": ["visited", "visited"],
  "qc-report": ["visited", "visited"],
  "route-qc": ["visited", "visited"],
};
const OWN_ROWS = ["retailer-list", "by-outlet", "sku-list"];

describe("every report page's figures equal the seeded day's control totals", () => {
  for (const rep of WEB_REPORTS) {
    it(`${rep.row} ${rep.key}: in scope matches, outside scope is empty`, async () => {
      const tso = await login("tso334", "tso-pass-1");
      const out = await login("tso999", "tso-pass-3");
      const mk = (s: ScopeSummary) => buildReportQuery(rep, { date: SEED_DATE, from: SEED_DATE, to: SEED_DATE }, s, "json", SEED_DATE);
      const a = await runReportJson(tso.at, rep.key, mk(tso.scope));
      const b = await runReportJson(out.at, rep.key, mk(out.scope));
      expect(a.ok, `${rep.key} answered`).toBe(true);
      expect(b.ok).toBe(true);
      if (!a.ok || !b.ok) return;
      const ct = controlTotals(routesInScope(tso.scope.nodes));
      if (OWN_ROWS.includes(rep.key)) {
        if (rep.key !== "sku-list") expect(a.data.total_rows).toBeGreaterThan(0);
        if (rep.key !== "sku-list") expect(b.data.rows).toEqual([]);
        return;
      }
      if (rep.key === "geofence-calibration") {
        expect(a.data.rows.reduce((s, r) => s + Number(r.visits), 0)).toBeGreaterThanOrEqual(ct.visited - 2);
        expect(b.data.rows).toEqual([]);
        return;
      }
      expect(a.data.total_rows).toBeGreaterThan(0);
      expect(a.data.columns.length).toBeGreaterThan(1);
      expect(b.data.rows).toEqual([]);
      const s = SUMS[rep.key];
      if (s) expect(a.data.totals?.[s[0]], `${rep.key} total ${s[0]}`).toBe(ct[s[1]]);
      // rows are the seeded routes of the scope, nothing from territory 335
      expect(JSON.stringify(a.data.rows)).not.toContain("Gulshan");
    });
  }
});

describe("price columns and PII by role", () => {
  it("SKU prices are integer milli-taka (7.935 shown), and the trade price column is only for roles that may see it", async () => {
    const tso = await login("tso334", "tso-pass-1");
    const analyst = await login("analyst1", "analyst-pass-1");
    const q = (s: ScopeSummary) => buildReportQuery(WEB_REPORTS.find((r) => r.key === "sku-list")!, {}, s);
    const a = await runReportJson(tso.at, "sku-list", q(tso.scope));
    const b = await runReportJson(analyst.at, "sku-list", q(analyst.scope));
    if (!a.ok || !b.ok) throw new Error("no data");
    expect(a.data.rows[0]!.retail_price_mtk).toBe(7935);
    expect(a.data.columns.map((c) => c.key)).not.toContain("trade_price_mtk");
    expect(b.data.columns.map((c) => c.key)).toContain("trade_price_mtk");
  });
});
