// Route monthly targets (D-TARGET-MIN-01): sheet parsing, the validation report, the proxy rules and the preview/commit flow.
import { describe, expect, it } from "vitest";
import { readSheet } from "read-excel-file/node";
import writeExcelFile from "write-excel-file/node";
import { monthOf, MAX_TARGET_ROWS, parseTargetSheet, qtyOf, routeTargetRules, templateCells } from "@/lib/admin/route-targets";
import { businessDate } from "@/lib/i18n";
import { OPS } from "@/lib/admin/ops";
import { madmin, op, setupMock, support } from "./helpers/harness";

const h = setupMock();
const CUR = "2026-10";
const REASON = "Monthly targets of the sales plan for November";
const BATCH = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const monthPlus = (n: number) => {
  const d = new Date(Date.parse(`${businessDate().slice(0, 7)}-01T00:00:00Z`));
  d.setUTCMonth(d.getUTCMonth() + n);
  return d.toISOString().slice(0, 7);
};
const NEXT = monthPlus(2); // tests must use relative dates: past months are refused
const HEAD = ["route_code", "sku_code", "month", "quantity_base"];

describe("parseTargetSheet", () => {
  it("reads valid rows and keeps the sheet row numbers", () => {
    const r = parseTargetSheet([HEAD, ["R-334-01", "MaxR-20S", "2026-11", 1200], [], ["R-334-02", "MaxR-10S", "2026-11", "২৪০০"]], CUR);
    expect(r.issues).toEqual([]);
    expect(r.rows).toEqual([
      { route_code: "R-334-01", sku_code: "MaxR-20S", month: "2026-11", qty_base: 1200 },
      { route_code: "R-334-02", sku_code: "MaxR-10S", month: "2026-11", qty_base: 2400 },
    ]);
    expect(r.sheetRows).toEqual([2, 4]);
  });
  it("accepts header aliases in any case and a date cell as the month", () => {
    const r = parseTargetSheet([["Route", "SKU", "Month", "Qty"], ["R1", "S1", new Date(Date.UTC(2026, 10, 1)), 5]], CUR);
    expect(r.issues).toEqual([]);
    expect(r.rows[0]).toMatchObject({ month: "2026-11", qty_base: 5 });
  });
  it("reports a missing header column and stops", () => {
    expect(parseTargetSheet([["route_code", "sku_code", "month"]], CUR).issues).toEqual([{ row: 1, column: "quantity", code: "missing_header" }]);
  });
  it("reports every bad cell with its row and column", () => {
    const r = parseTargetSheet(
      [HEAD, ["", "S1", "2026-11", 1], ["R1", "bad code!", "2026-11", 1], ["R1", "S1", "2026-13", 1], ["R1", "S1", "2026-09", 1], ["R1", "S1", "2026-11", -1], ["R1", "S1", "2026-11", 1.5], ["R1", "S1", "2026-11", ""], ["R1", "S2", "2026-11", "1,200"]],
      CUR,
    );
    expect(r.rows).toEqual([]);
    expect(r.issues).toEqual([
      { row: 2, column: "route", code: "required" },
      { row: 3, column: "sku", code: "invalid" },
      { row: 4, column: "month", code: "invalid" },
      { row: 5, column: "month", code: "past_month" },
      { row: 6, column: "quantity", code: "invalid" },
      { row: 7, column: "quantity", code: "invalid" },
      { row: 8, column: "quantity", code: "required" },
      { row: 9, column: "quantity", code: "invalid" },
    ]);
  });
  it("flags a repeated route, SKU and month and an empty sheet", () => {
    expect(parseTargetSheet([HEAD, ["R1", "S1", "2026-11", 1], ["R1", "S1", "2026-11", 2]], CUR).issues).toEqual([{ row: 3, column: "route", code: "duplicate" }]);
    expect(parseTargetSheet([HEAD], CUR).issues).toEqual([{ row: 1, column: "sheet", code: "no_rows" }]);
  });
  it("refuses more than 20,000 rows once", () => {
    const rows = Array.from({ length: MAX_TARGET_ROWS + 5 }, (_, i) => ["R1", `S${i}`, "2026-11", 1]);
    const r = parseTargetSheet([HEAD, ...rows], CUR);
    expect(r.rows).toHaveLength(MAX_TARGET_ROWS);
    expect(r.issues).toEqual([{ row: MAX_TARGET_ROWS + 2, column: "sheet", code: "too_many_rows" }]);
  });
  it("zero is a valid target; month and quantity helpers", () => {
    expect(qtyOf(0)).toBe(0);
    expect(qtyOf("1000000001")).toBeNull();
    expect(monthOf("2026-11-15")).toBe("2026-11");
    expect(monthOf("Nov 2026")).toBeNull();
  });
  it("round-trips a real .xlsx workbook (template written, then read back and parsed)", async () => {
    const buf = await writeExcelFile(templateCells("2026-11").map((r) => r.map((value) => ({ value })))).toBuffer();
    const cells = (await readSheet(buf)) as unknown[][];
    const r = parseTargetSheet(cells, CUR);
    expect(r.issues).toEqual([]);
    expect(r.rows).toEqual([{ route_code: "R-334-01", sku_code: "MaxR", month: "2026-11", qty_base: 1200 }]);
  });
});

describe("proxy rules and ops", () => {
  const rows = [{ route_code: "R-334-01", sku_code: "MaxR-20S", month: "2026-11", qty_base: 100 }];
  it("validate is a dry run without a reason, commit carries change_reason; Administrators only", () => {
    expect(OPS["route-targets.validate"]).toMatchObject({ method: "PUT", reason: null });
    expect(OPS["route-targets.commit"]).toMatchObject({ method: "PUT", reason: "change_reason" });
    expect([...OPS["route-targets.commit"].roles]).toEqual(["ADMIN", "SUPERADMIN"]);
  });
  it("rule: dry_run must match the operation, rows and members are checked", () => {
    expect(routeTargetRules("validate", { batch_uuid: BATCH, rows, dry_run: true })).toEqual([]);
    expect(routeTargetRules("commit", { batch_uuid: BATCH, rows, dry_run: true })).toContainEqual({ pointer: "/dry_run", code: "invalid" });
    expect(routeTargetRules("validate", { batch_uuid: "x", rows, dry_run: true })).toContainEqual({ pointer: "/batch_uuid", code: "invalid" });
    expect(routeTargetRules("validate", { batch_uuid: BATCH, rows: [], dry_run: true })).toContainEqual({ pointer: "/rows", code: "invalid" });
    expect(routeTargetRules("validate", { batch_uuid: BATCH, rows: [{ ...rows[0], extra: 1 }], dry_run: true })).toContainEqual({ pointer: "/rows/0", code: "invalid" });
    expect(routeTargetRules("validate", { batch_uuid: BATCH, rows: [...rows, ...rows], dry_run: true })).toContainEqual({ pointer: "/rows/1", code: "duplicate" });
    expect(routeTargetRules("validate", { batch_uuid: BATCH, rows, dry_run: true, scope: "all" })).toContainEqual({ pointer: "/scope", code: "unknown_member" });
  });

  it("preview reports server-side problems with the batch row; commit needs a reason, is idempotent and audited", async () => {
    const c = await madmin();
    const bad = [{ route_code: "NOPE", sku_code: "MaxR-20S", month: NEXT, qty_base: 1 }, { route_code: "A-334-01", sku_code: "X", month: NEXT, qty_base: 1 }];
    const p1 = await (await op(c, { op: "route-targets.validate", body: { batch_uuid: BATCH, rows: bad, dry_run: true } })).json();
    expect(p1.data.errors).toEqual([
      { row: 1, column: "route", code: "unknown_route" },
      { row: 2, column: "route", code: "not_selling_route" },
      { row: 2, column: "sku", code: "unknown_sku" },
    ]);
    const good = [{ route_code: "R-334-01", sku_code: "MaxR-20S", month: NEXT, qty_base: 500 }, { route_code: "R-334-02", sku_code: "MaxR-10S", month: NEXT, qty_base: 800 }];
    const p2 = await (await op(c, { op: "route-targets.validate", body: { batch_uuid: BATCH, rows: good, dry_run: true } })).json();
    expect(p2.data).toMatchObject({ rows: 2, routes: 2, skus: 2, months: [NEXT], total_qty_base: 1300, replaces: 0, errors: [] });
    expect(h.mock.state.custom.routeTargets.filter((t) => t.month === NEXT)).toHaveLength(0); // a preview writes nothing
    expect((await op(c, { op: "route-targets.commit", body: { batch_uuid: BATCH, rows: good, dry_run: false }, reason: "short" })).status).toBe(400);
    const ok = await op(c, { op: "route-targets.commit", body: { batch_uuid: BATCH, rows: good, dry_run: false }, reason: REASON });
    expect((await ok.json()).data).toMatchObject({ rows: 2, replayed: false });
    const again = await op(c, { op: "route-targets.commit", body: { batch_uuid: BATCH, rows: good, dry_run: false }, reason: REASON });
    expect((await again.json()).data).toMatchObject({ replayed: true });
    expect(h.mock.state.custom.routeTargets.filter((t) => t.month === NEXT)).toHaveLength(2); // no double numbers
    const other = await op(c, { op: "route-targets.commit", body: { batch_uuid: BATCH, rows: [good[0]], dry_run: false }, reason: REASON });
    expect(other.status).toBe(409); // same batch id, other content
    expect(h.mock.state.audit.at(-1)).toMatchObject({ entity: "route_target", reason: REASON });
  });

  it("SUPPORT cannot validate or commit", async () => {
    const c = await support();
    for (const o of ["route-targets.validate", "route-targets.commit"]) expect((await op(c, { op: o, body: { batch_uuid: BATCH, rows, dry_run: o.endsWith("validate") }, reason: REASON })).status).toBe(403);
    expect(h.calls()).toHaveLength(0);
  });
});
