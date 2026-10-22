// Route monthly targets (D-TARGET-MIN-01, docs/34 s2): one Excel sheet with route, SKU, month and quantity in BASE units (sticks,
// pieces, dozens). The browser reads the sheet and checks its shape; the server checks codes and states (preview, then commit).
// The API is PROVISIONAL: docs/requests/backend-admin-route-targets.md, served by mock/custom.ts.
import { latinDigits } from "./taka";

export interface TargetRow {
  route_code: string;
  sku_code: string;
  month: string;
  qty_base: number;
}

/** Issue code per row and column; the page maps `code` to a message. `row` is the sheet row (header = 1). */
export interface RowIssue {
  row: number;
  column: "route" | "sku" | "month" | "quantity" | "sheet";
  code: "missing_header" | "required" | "invalid" | "past_month" | "duplicate" | "too_many_rows" | "no_rows";
}

export const MAX_TARGET_ROWS = 20_000;
export const MAX_QTY_BASE = 1_000_000_000;
export const HEADERS = { route: "route_code", sku: "sku_code", month: "month", quantity: "quantity_base" } as const;

const ALIASES: Record<keyof typeof HEADERS, readonly string[]> = {
  route: ["route_code", "route", "routecode"],
  sku: ["sku_code", "sku", "skucode"],
  month: ["month", "target_month"],
  quantity: ["quantity_base", "quantity", "qty", "qty_base", "target"],
};
const norm = (v: unknown) => String(v ?? "").trim().toLowerCase().replace(/[\s-]+/g, "_");
const CODE = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,39}$/;
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;

/** A month cell: text "2026-11", a date cell (its year and month), or Excel text like "Nov-2026" is NOT guessed. */
export function monthOf(cell: unknown): string | null {
  if (cell instanceof Date && !Number.isNaN(cell.getTime())) return `${cell.getUTCFullYear()}-${String(cell.getUTCMonth() + 1).padStart(2, "0")}`;
  if (typeof cell !== "string") return null;
  const s = latinDigits(cell.trim());
  const m = /^(\d{4})-(\d{2})(?:-\d{2})?$/.exec(s);
  return m && MONTH.test(`${m[1]}-${m[2]}`) ? `${m[1]}-${m[2]}` : null;
}

/** A whole, non-negative quantity: an integer cell or digits (Bengali digits accepted); no decimals, no thousands separators. */
export function qtyOf(cell: unknown): number | null {
  if (typeof cell === "number") return Number.isSafeInteger(cell) && cell >= 0 && cell <= MAX_QTY_BASE ? cell : null;
  if (typeof cell !== "string") return null;
  const s = latinDigits(cell.trim());
  if (!/^\d{1,10}$/.test(s)) return null;
  const n = Number(s);
  return n <= MAX_QTY_BASE ? n : null;
}

export interface ParsedSheet {
  rows: TargetRow[];
  /** Sheet row number (header = 1) of each entry of `rows`, so a server error "row n of the batch" points at the sheet. */
  sheetRows: number[];
  issues: RowIssue[];
}

/** Reads the cells of the first sheet (header row first). `currentMonth` is the Dhaka month: earlier months are never rewritten. */
export function parseTargetSheet(cells: readonly (readonly unknown[])[], currentMonth: string): ParsedSheet {
  const issues: RowIssue[] = [];
  const header = (cells[0] ?? []).map(norm);
  const col = {} as Record<keyof typeof HEADERS, number>;
  for (const k of Object.keys(HEADERS) as (keyof typeof HEADERS)[]) {
    col[k] = header.findIndex((h) => ALIASES[k].includes(h));
    if (col[k] < 0) issues.push({ row: 1, column: k, code: "missing_header" });
  }
  if (issues.length) return { rows: [], sheetRows: [], issues };
  const rows: TargetRow[] = [];
  const sheetRows: number[] = [];
  const seen = new Set<string>();
  const body = cells.slice(1);
  let used = 0;
  body.forEach((r, i) => {
    const row = i + 2;
    const blank = !r || r.every((c) => c === null || c === undefined || String(c).trim() === "");
    if (blank) return;
    if (++used > MAX_TARGET_ROWS) {
      if (used === MAX_TARGET_ROWS + 1) issues.push({ row, column: "sheet", code: "too_many_rows" });
      return;
    }
    const route = String(r[col.route] ?? "").trim();
    const sku = String(r[col.sku] ?? "").trim();
    const month = monthOf(r[col.month]);
    const qty = qtyOf(r[col.quantity]);
    let bad = false;
    const add = (column: RowIssue["column"], code: RowIssue["code"]) => {
      issues.push({ row, column, code });
      bad = true;
    };
    if (route === "") add("route", "required");
    else if (!CODE.test(route)) add("route", "invalid");
    if (sku === "") add("sku", "required");
    else if (!CODE.test(sku)) add("sku", "invalid");
    if (r[col.month] === undefined || r[col.month] === null || String(r[col.month]).trim() === "") add("month", "required");
    else if (!month) add("month", "invalid");
    else if (month < currentMonth) add("month", "past_month");
    if (r[col.quantity] === undefined || r[col.quantity] === null || String(r[col.quantity]).trim() === "") add("quantity", "required");
    else if (qty === null) add("quantity", "invalid");
    if (bad) return;
    const key = `${route}\u0000${sku}\u0000${month}`;
    if (seen.has(key)) return void issues.push({ row, column: "route", code: "duplicate" });
    seen.add(key);
    rows.push({ route_code: route, sku_code: sku, month: month!, qty_base: qty! });
    sheetRows.push(row);
  });
  if (rows.length === 0 && issues.length === 0) issues.push({ row: 1, column: "sheet", code: "no_rows" });
  return { rows, sheetRows, issues };
}

/** Sheet rows of the downloadable template: the header and one example line. */
export function templateCells(month: string): string[][] {
  return [Object.values(HEADERS), ["R-334-01", "MaxR", month, "1200"]];
}

/** Shape rules the proxy applies before calling the API (the API validates codes and states again). */
export function routeTargetRules(op: "validate" | "commit", body: Record<string, unknown>): { pointer: string; code: string }[] {
  const out: { pointer: string; code: string }[] = [];
  for (const k of Object.keys(body)) if (!["batch_uuid", "rows", "dry_run", "change_reason"].includes(k)) out.push({ pointer: `/${k}`, code: "unknown_member" });
  if (typeof body.batch_uuid !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(body.batch_uuid)) out.push({ pointer: "/batch_uuid", code: "invalid" });
  if (body.dry_run !== (op === "validate")) out.push({ pointer: "/dry_run", code: "invalid" });
  const rows = body.rows;
  if (!Array.isArray(rows) || rows.length < 1 || rows.length > MAX_TARGET_ROWS) out.push({ pointer: "/rows", code: "invalid" });
  else {
    const seen = new Set<string>();
    for (let i = 0; i < rows.length; i++) {
      const r = rows[i] as Record<string, unknown> | null;
      const ok = typeof r === "object" && r !== null && Object.keys(r).every((k) => ["route_code", "sku_code", "month", "qty_base"].includes(k)) && typeof r.route_code === "string" && CODE.test(r.route_code) && typeof r.sku_code === "string" && CODE.test(r.sku_code) && typeof r.month === "string" && MONTH.test(r.month) && typeof r.qty_base === "number" && Number.isSafeInteger(r.qty_base) && r.qty_base >= 0 && r.qty_base <= MAX_QTY_BASE;
      if (!ok) {
        out.push({ pointer: `/rows/${i}`, code: "invalid" });
        break; // one bad row is enough to refuse; the report comes from the preview
      }
      const k = `${r!.route_code}\u0000${r!.sku_code}\u0000${r!.month}`;
      if (seen.has(k)) {
        out.push({ pointer: `/rows/${i}`, code: "duplicate" });
        break;
      }
      seen.add(k);
    }
  }
  return out;
}

/** Wire shape of the preview answer (REQUEST: backend-admin-route-targets). */
export interface TargetPreview {
  batch_uuid: string;
  rows: number;
  routes: number;
  skus: number;
  months: string[];
  total_qty_base: number;
  replaces: number;
  /** `row` is the 1-based position in the submitted `rows`. */
  errors: { row: number; column: string; code: string }[];
}
