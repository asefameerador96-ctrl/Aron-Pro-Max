// KPI targets (contract 1.22.0): the five sheet parsers, exact money and decimal handling, the proxy rules and the preview/commit flow.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { readSheet } from "read-excel-file/node";
import writeExcelFile from "write-excel-file/node";
import { dec3Of, kpiTargetRules, kpiTemplateCells, KPI_KINDS, MAX_KPI_ROWS, parseKpiSheet, SECTION_MEMBER, takaCellToMtk, wholeOf, type KpiKind } from "@/lib/admin/kpi-targets";
import { rowIssuesOf } from "@/lib/admin/row-pointer";
import { OPS } from "@/lib/admin/ops";
import { moduleOfApiPath, moduleOfPage } from "@/lib/admin/modules";
import { madmin, op, setupMock, support } from "./helpers/harness";

beforeEach(() => {
  vi.setSystemTime(new Date("2026-10-24T09:00:00Z"));
});
afterEach(() => {
  vi.useRealTimers();
});

const h = setupMock();
const CUR = "2026-10";
const NEXT = "2026-12";
const REASON = "KPI targets of the sales plan for December";
const B1 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const B2 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";

const HEAD: Record<KpiKind, string[]> = {
  brand: ["route_code", "brand_code", "month", "memo_target", "kpi_alloc_taka"],
  month: ["route_code", "month", "kpi_alloc_taka", "cpr_target_bp", "cpr_alloc_taka"],
  programme: ["route_code", "programme_code", "month", "target_qty"],
  campaign: ["outlet_code", "programme_code", "month", "std_target", "memo_target"],
  "campaign-sku": ["outlet_code", "programme_code", "month", "sku_code", "qty_base"],
};

describe("exact cell readers", () => {
  it("taka to integer milli-taka without floating point", () => {
    expect(takaCellToMtk("5700")).toBe(5_700_000);
    expect(takaCellToMtk(1234.567)).toBe(1_234_567);
    expect(takaCellToMtk("০.১২৫")).toBe(125); // Bengali digits
    expect(takaCellToMtk(0.1)).toBe(100);
    expect(takaCellToMtk("1.2345")).toBeNull(); // finer than a milli-taka
    expect(takaCellToMtk(-1)).toBeNull();
    expect(takaCellToMtk("1e3")).toBeNull();
    expect(takaCellToMtk(100_000_001)).toBeNull(); // above 100,000,000,000 mtk
    expect(takaCellToMtk(100_000_000)).toBe(100_000_000_000);
  });
  it("decimals are exact Decimal3 strings, never numbers", () => {
    expect(dec3Of(20)).toBe("20.000");
    expect(dec3Of("2.833")).toBe("2.833");
    expect(dec3Of(12.25)).toBe("12.250");
    expect(dec3Of("1.5555")).toBeNull();
    expect(dec3Of("-1")).toBeNull();
    expect(dec3Of("")).toBeNull();
    expect(dec3Of(true)).toBeNull();
  });
  it("whole numbers: integers or digits only", () => {
    expect(wholeOf("১১৯", 1000)).toBe(119);
    expect(wholeOf(1.5, 1000)).toBeNull();
    expect(wholeOf("1,000", 10_000)).toBeNull();
    expect(wholeOf(1001, 1000)).toBeNull();
  });
});

describe("parseKpiSheet", () => {
  it("brand: reads rows, converts taka to mtk, optional allocation may be empty", () => {
    const r = parseKpiSheet("brand", [HEAD.brand, ["R-334-01", "MAXR", "2026-11", 119, "5700.5"], [], ["R-334-01", "VAL", "2026-11", "146", ""]], CUR);
    expect(r.issues).toEqual([]);
    expect(r.rows).toEqual([
      { route_code: "R-334-01", brand_code: "MAXR", month: "2026-11", memo_target: 119, kpi_alloc_mtk: 5_700_500 },
      { route_code: "R-334-01", brand_code: "VAL", month: "2026-11", memo_target: 146 },
    ]);
    expect(r.sheetRows).toEqual([2, 4]);
  });
  it("month: CPR columns are optional, month allocation is required", () => {
    expect(parseKpiSheet("month", [HEAD.month, ["R1", "2026-11", 5700, 2700, 1500]], CUR).rows).toEqual([{ route_code: "R1", month: "2026-11", kpi_alloc_mtk: 5_700_000, cpr_target_bp: 2700, cpr_alloc_mtk: 1_500_000 }]);
    expect(parseKpiSheet("month", [HEAD.month, ["R1", "2026-11", 5700, "", ""]], CUR).rows).toEqual([{ route_code: "R1", month: "2026-11", kpi_alloc_mtk: 5_700_000 }]);
    expect(parseKpiSheet("month", [HEAD.month, ["R1", "2026-11", "", "", ""]], CUR).issues).toEqual([{ row: 2, column: "kpi_alloc_mtk", code: "required" }]);
    expect(parseKpiSheet("month", [HEAD.month, ["R1", "2026-11", 1, 10_001, ""]], CUR).issues).toEqual([{ row: 2, column: "cpr_target_bp", code: "invalid" }]);
  });
  it("programme and campaign: decimals become Decimal3 strings", () => {
    expect(parseKpiSheet("programme", [HEAD.programme, ["R1", "MBLS", "2026-11", 20]], CUR).rows).toEqual([{ route_code: "R1", programme_code: "MBLS", month: "2026-11", target_qty: "20.000" }]);
    expect(parseKpiSheet("campaign", [HEAD.campaign, ["O1", "CAMP", "2026-11", "140.5", 8]], CUR).rows).toEqual([{ outlet_code: "O1", programme_code: "CAMP", month: "2026-11", std_target: "140.500", memo_target: 8 }]);
  });
  it("campaign-sku: quantity is a whole base-unit number", () => {
    expect(parseKpiSheet("campaign-sku", [HEAD["campaign-sku"], ["O1", "CAMP", "2026-11", "MaxR-20S", 140]], CUR).rows).toEqual([{ outlet_code: "O1", programme_code: "CAMP", month: "2026-11", sku_code: "MaxR-20S", qty_base: 140 }]);
    expect(parseKpiSheet("campaign-sku", [HEAD["campaign-sku"], ["O1", "CAMP", "2026-11", "S", 1.5]], CUR).issues).toEqual([{ row: 2, column: "qty_base", code: "invalid" }]);
  });
  it("accepts header aliases and member names in any case", () => {
    const r = parseKpiSheet("brand", [["Route", "Brand", "Month", "Memo", "KPI_alloc_mtk"], ["R1", "B1", new Date(Date.UTC(2026, 10, 1)), 5, 10]], CUR);
    expect(r.issues).toEqual([]);
    expect(r.rows[0]).toMatchObject({ month: "2026-11", memo_target: 5, kpi_alloc_mtk: 10_000 });
  });
  it("reports a missing header column and stops; an optional column may be absent", () => {
    expect(parseKpiSheet("programme", [["route_code", "programme_code", "month"]], CUR).issues).toEqual([{ row: 1, column: "target_qty", code: "missing_header" }]);
    expect(parseKpiSheet("brand", [HEAD.brand.slice(0, 4), ["R1", "B1", "2026-11", 3]], CUR).rows).toEqual([{ route_code: "R1", brand_code: "B1", month: "2026-11", memo_target: 3 }]);
  });
  it("reports every bad cell with row and column, including past months", () => {
    const r = parseKpiSheet(
      "brand",
      [HEAD.brand, ["", "B", "2026-11", 1, 1], ["R", "bad code!", "2026-11", 1, 1], ["R", "B", "2026-13", 1, 1], ["R", "B", "2026-09", 1, 1], ["R", "B", "2026-11", -1, 1], ["R", "B", "2026-11", 1, "1.2345"]],
      CUR,
    );
    expect(r.rows).toEqual([]);
    expect(r.issues).toEqual([
      { row: 2, column: "route_code", code: "required" },
      { row: 3, column: "brand_code", code: "invalid" },
      { row: 4, column: "month", code: "invalid" },
      { row: 5, column: "month", code: "past_month" },
      { row: 6, column: "memo_target", code: "invalid" },
      { row: 7, column: "kpi_alloc_mtk", code: "invalid" },
    ]);
  });
  it("flags a repeated key per section, an empty sheet, and caps at 20,000 rows once", () => {
    expect(parseKpiSheet("brand", [HEAD.brand, ["R", "B", "2026-11", 1, 1], ["R", "B", "2026-11", 2, 2]], CUR).issues).toEqual([{ row: 3, column: "route_code", code: "duplicate" }]);
    expect(parseKpiSheet("campaign-sku", [HEAD["campaign-sku"], ["O", "C", "2026-11", "S1", 1], ["O", "C", "2026-11", "S2", 1]], CUR).issues).toEqual([]); // a different SKU is another key
    expect(parseKpiSheet("month", [HEAD.month], CUR).issues).toEqual([{ row: 1, column: "sheet", code: "no_rows" }]);
    const rows = Array.from({ length: MAX_KPI_ROWS + 5 }, (_, i) => ["R", `B${i}`, "2026-11", 1, 1]);
    const r = parseKpiSheet("brand", [HEAD.brand, ...rows], CUR);
    expect(r.rows).toHaveLength(MAX_KPI_ROWS);
    expect(r.issues).toEqual([{ row: MAX_KPI_ROWS + 2, column: "sheet", code: "too_many_rows" }]);
  });
  it.each(KPI_KINDS)("round-trips a real .xlsx workbook of the %s template", async (kind) => {
    const buf = await writeExcelFile(kpiTemplateCells(kind, "2026-11").map((r) => r.map((value) => ({ value })))).toBuffer();
    const r = parseKpiSheet(kind, (await readSheet(buf)) as unknown[][], CUR);
    expect(r.issues).toEqual([]);
    expect(r.rows).toHaveLength(1);
  });
});

describe("pointers of a refused batch", () => {
  it("one array per section: body.brand_rows/3/memo_target is sheet row 4", () => {
    expect(rowIssuesOf([{ pointer: "body.brand_rows/3/memo_target", code: "invalid_quantity" }, { pointer: "body.campaign_sku_rows/0/sku_code", code: "unknown_sku" }, { pointer: "/change_reason", code: "too_short" }])).toEqual([
      { row: 4, column: "memo_target", code: "invalid_quantity" },
      { row: 1, column: "sku_code", code: "unknown_sku" },
    ]);
  });
});

describe("routing and operations", () => {
  it("API and page belong to the targets module; Administrators write", () => {
    expect(moduleOfApiPath("/v1/admin/route-kpi-targets")).toBe("targets");
    expect(moduleOfPage("/admin/kpi-targets")).toBe("targets");
    expect(OPS["kpi-targets.validate"]).toMatchObject({ method: "PUT", path: "/v1/admin/route-kpi-targets", reason: null });
    expect(OPS["kpi-targets.commit"]).toMatchObject({ method: "PUT", reason: "change_reason" });
    expect([...OPS["kpi-targets.commit"].roles]).toEqual(["ADMIN", "SUPERADMIN"]);
  });
  const brand = [{ route_code: "R-334-01", brand_code: "MAXR", month: NEXT, memo_target: 119, kpi_alloc_mtk: 5_700_000 }];
  it("rule: one section only, dry_run matches the operation, shapes are exact", () => {
    expect(kpiTargetRules("validate", { batch_uuid: B1, brand_rows: brand, dry_run: true })).toEqual([]);
    expect(kpiTargetRules("commit", { batch_uuid: B1, brand_rows: brand, dry_run: true })).toContainEqual({ pointer: "/dry_run", code: "invalid" });
    expect(kpiTargetRules("validate", { batch_uuid: B1, brand_rows: brand, month_rows: [], dry_run: true })).toContainEqual({ pointer: "/rows", code: "invalid" });
    expect(kpiTargetRules("validate", { batch_uuid: B1, dry_run: true })).toContainEqual({ pointer: "/rows", code: "invalid" });
    expect(kpiTargetRules("validate", { batch_uuid: B1, brand_rows: [], dry_run: true })).toContainEqual({ pointer: "/brand_rows", code: "invalid" });
    expect(kpiTargetRules("validate", { batch_uuid: B1, brand_rows: [{ ...brand[0], extra: 1 }], dry_run: true })).toContainEqual({ pointer: "/brand_rows/0", code: "invalid" });
    expect(kpiTargetRules("validate", { batch_uuid: B1, brand_rows: [...brand, ...brand], dry_run: true })).toContainEqual({ pointer: "/brand_rows/1", code: "duplicate" });
    expect(kpiTargetRules("validate", { batch_uuid: "x", brand_rows: brand, dry_run: true })).toContainEqual({ pointer: "/batch_uuid", code: "invalid" });
    expect(kpiTargetRules("validate", { batch_uuid: B1, brand_rows: brand, dry_run: true, scope: "all" })).toContainEqual({ pointer: "/scope", code: "unknown_member" });
    // a decimal is a string on the wire, never a number
    expect(kpiTargetRules("validate", { batch_uuid: B1, programme_rows: [{ route_code: "R", programme_code: "P", month: NEXT, target_qty: 20 }], dry_run: true })).toContainEqual({ pointer: "/programme_rows/0", code: "invalid" });
    expect(kpiTargetRules("validate", { batch_uuid: B1, programme_rows: [{ route_code: "R", programme_code: "P", month: NEXT, target_qty: "20.000" }], dry_run: true })).toEqual([]);
    // money is integer milli-taka
    expect(kpiTargetRules("validate", { batch_uuid: B1, brand_rows: [{ ...brand[0], kpi_alloc_mtk: 1.5 }], dry_run: true })).toContainEqual({ pointer: "/brand_rows/0", code: "invalid" });
  });
  it("every section name maps to its body member", () => {
    expect(SECTION_MEMBER).toEqual({ brand: "brand_rows", month: "month_rows", programme: "programme_rows", campaign: "campaign_rows", "campaign-sku": "campaign_sku_rows" });
  });
});

describe("preview and commit through the proxy", () => {
  it("preview names section, row and column; commit needs a reason, is idempotent, audited and never doubles", async () => {
    const c = await madmin();
    const bad = [{ route_code: "NOPE", brand_code: "MAXR", month: NEXT, memo_target: 1 }, { route_code: "R-334-01", brand_code: "ZZZ", month: NEXT, memo_target: 1 }];
    const p1 = await (await op(c, { op: "kpi-targets.validate", body: { batch_uuid: B1, brand_rows: bad, dry_run: true } })).json();
    expect(p1.data.errors).toEqual([
      { section: "brand_rows", row: 1, column: "route_code", code: "unknown_route" },
      { section: "brand_rows", row: 2, column: "brand_code", code: "unknown_brand" },
    ]);
    const good = [{ route_code: "R-334-01", brand_code: "MAXR", month: NEXT, memo_target: 119, kpi_alloc_mtk: 5_700_000 }];
    const p2 = await (await op(c, { op: "kpi-targets.validate", body: { batch_uuid: B1, brand_rows: good, dry_run: true } })).json();
    expect(p2.data).toMatchObject({ brand_rows: 1, month_rows: 0, programme_rows: 0, campaign_rows: 0, campaign_sku_rows: 0, errors: [], errors_truncated: false });
    expect(h.mock.state.custom.kpi.brand).toHaveLength(0); // a preview writes nothing
    expect((await op(c, { op: "kpi-targets.commit", body: { batch_uuid: B1, brand_rows: good, dry_run: false }, reason: "short" })).status).toBe(400);
    const ok = await op(c, { op: "kpi-targets.commit", body: { batch_uuid: B1, brand_rows: good, dry_run: false }, reason: REASON });
    expect((await ok.json()).data).toMatchObject({ rows: 1, replayed: false });
    const again = await op(c, { op: "kpi-targets.commit", body: { batch_uuid: B1, brand_rows: good, dry_run: false }, reason: REASON });
    expect((await again.json()).data).toMatchObject({ replayed: true });
    expect(h.mock.state.custom.kpi.brand).toHaveLength(1);
    const other = await op(c, { op: "kpi-targets.commit", body: { batch_uuid: B1, brand_rows: [{ ...good[0], memo_target: 5 }], dry_run: false }, reason: REASON });
    expect(other.status).toBe(409); // same batch id, other content
    expect(h.mock.state.audit.at(-1)).toMatchObject({ entity: "route_kpi_target", reason: REASON });
  });
  it("a refused commit points at the section row; the other four sections commit too", async () => {
    const c = await madmin();
    const refused = await op(c, { op: "kpi-targets.commit", body: { batch_uuid: B2, month_rows: [{ route_code: "NOPE", month: NEXT, kpi_alloc_mtk: 1 }], dry_run: false }, reason: REASON });
    expect(refused.status).toBe(400);
    expect(rowIssuesOf((await refused.json()).errors)).toEqual([{ row: 1, column: "route_code", code: "unknown_route" }]);
    const sections: [string, unknown[]][] = [
      ["month_rows", [{ route_code: "R-334-01", month: NEXT, kpi_alloc_mtk: 5_700_000, cpr_target_bp: 2700, cpr_alloc_mtk: 1_500_000 }]],
      ["programme_rows", [{ route_code: "R-334-01", programme_code: "MBLS", month: NEXT, target_qty: "20.000" }]],
      ["campaign_rows", [{ outlet_code: "DHK-334-001", programme_code: "CAMP-NOV", month: NEXT, std_target: "140.000", memo_target: 8 }]],
      ["campaign_sku_rows", [{ outlet_code: "DHK-334-001", programme_code: "CAMP-NOV", month: NEXT, sku_code: "MaxR-20S", qty_base: 140 }]],
    ];
    for (const [member, rows] of sections) {
      const r = await op(c, { op: "kpi-targets.commit", body: { batch_uuid: crypto.randomUUID(), [member]: rows, dry_run: false }, reason: REASON });
      expect((await r.json()).data).toMatchObject({ rows: 1, replayed: false });
    }
    const k = h.mock.state.custom.kpi;
    expect([k.month.length, k.programme.length, k.campaign.length, k.campaignSku.length]).toEqual([1, 1, 1, 1]);
    expect(k.programme[0]).toMatchObject({ target_qty: "20.000" }); // a decimal stays a string
  });
  it("SUPPORT cannot validate or commit", async () => {
    const c = await support();
    for (const o of ["kpi-targets.validate", "kpi-targets.commit"]) expect((await op(c, { op: o, body: { batch_uuid: B1, brand_rows: brand0(), dry_run: o.endsWith("validate") }, reason: REASON })).status).toBe(403);
    expect(h.calls()).toHaveLength(0);
  });
});

const brand0 = () => [{ route_code: "R-334-01", brand_code: "MAXR", month: NEXT, memo_target: 1 }];
