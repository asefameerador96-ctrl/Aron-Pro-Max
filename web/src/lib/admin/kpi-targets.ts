// KPI targets (D-TKPI, contract 1.22.0 putRouteKpiTargets): five Excel sections, each its own upload (`?kind=`):
//   brand        route_code, brand_code, month, memo_target, kpi_alloc_taka   -> brand_rows
//   month        route_code, month, kpi_alloc_taka, cpr_target_bp, cpr_alloc_taka -> month_rows
//   programme    route_code, programme_code, month, target_qty               -> programme_rows
//   campaign     outlet_code, programme_code, month, std_target, memo_target -> campaign_rows
//   campaign-sku outlet_code, programme_code, month, sku_code, qty_base      -> campaign_sku_rows
// Money is entered in TAKA (up to 3 decimals) and sent as integer milli-taka; decimals (target_qty, std_target) are sent as exact
// Decimal3 STRINGS, never numbers. No floating point anywhere. The browser checks the sheet's shape; the server checks codes and states.
import type { Schemas } from "@/contract/types";
import { latinDigits } from "./taka";
import { monthOf } from "./route-targets";

export const KPI_KINDS = ["brand", "month", "programme", "campaign", "campaign-sku"] as const;
export type KpiKind = (typeof KPI_KINDS)[number];
export const isKpiKind = (v: unknown): v is KpiKind => typeof v === "string" && (KPI_KINDS as readonly string[]).includes(v);

/** Body member (array) of each section. */
export const SECTION_MEMBER: Record<KpiKind, "brand_rows" | "month_rows" | "programme_rows" | "campaign_rows" | "campaign_sku_rows"> = {
  brand: "brand_rows",
  month: "month_rows",
  programme: "programme_rows",
  campaign: "campaign_rows",
  "campaign-sku": "campaign_sku_rows",
};

export const MAX_KPI_ROWS = 20_000;
export const MAX_MEMO_TARGET = 1_000_000;
export const MAX_MTK = 100_000_000_000;
export const MAX_QTY_BASE = 1_000_000_000;
const CODE = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,39}$/;
const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/;
const norm = (v: unknown) => String(v ?? "").trim().toLowerCase().replace(/[\s-]+/g, "_");

/** Column (sheet header = body member, except money: taka in the sheet, milli-taka on the wire). */
type Col = "route_code" | "brand_code" | "programme_code" | "outlet_code" | "sku_code" | "month" | "memo_target" | "kpi_alloc_mtk" | "cpr_target_bp" | "cpr_alloc_mtk" | "target_qty" | "std_target" | "qty_base";
type Type = "code" | "month" | "int" | "taka" | "dec3" | "bp" | "qty";
interface Spec {
  member: Col;
  header: string;
  aliases: string[];
  type: Type;
  optional?: boolean;
}

const route: Spec = { member: "route_code", header: "route_code", aliases: ["route", "routecode"], type: "code" };
const programme: Spec = { member: "programme_code", header: "programme_code", aliases: ["programme", "program", "program_code"], type: "code" };
const outlet: Spec = { member: "outlet_code", header: "outlet_code", aliases: ["outlet", "outletcode"], type: "code" };
const month: Spec = { member: "month", header: "month", aliases: ["target_month"], type: "month" };

export const SPECS: Record<KpiKind, Spec[]> = {
  brand: [
    route,
    { member: "brand_code", header: "brand_code", aliases: ["brand", "brandcode"], type: "code" },
    month,
    { member: "memo_target", header: "memo_target", aliases: ["memo", "memos"], type: "int" },
    { member: "kpi_alloc_mtk", header: "kpi_alloc_taka", aliases: ["kpi_alloc", "kpi_allocation", "alloc_taka", "kpi_taka"], type: "taka", optional: true },
  ],
  month: [
    route,
    month,
    { member: "kpi_alloc_mtk", header: "kpi_alloc_taka", aliases: ["kpi_alloc", "kpi_allocation", "alloc_taka", "kpi_taka"], type: "taka" },
    { member: "cpr_target_bp", header: "cpr_target_bp", aliases: ["cpr_target", "cpr_bp"], type: "bp", optional: true },
    { member: "cpr_alloc_mtk", header: "cpr_alloc_taka", aliases: ["cpr_alloc", "cpr_taka"], type: "taka", optional: true },
  ],
  programme: [route, programme, month, { member: "target_qty", header: "target_qty", aliases: ["target", "qty", "quantity"], type: "dec3" }],
  campaign: [
    outlet,
    programme,
    month,
    { member: "std_target", header: "std_target", aliases: ["std", "std_qty"], type: "dec3" },
    { member: "memo_target", header: "memo_target", aliases: ["memo", "memos"], type: "int" },
  ],
  "campaign-sku": [outlet, programme, month, { member: "sku_code", header: "sku_code", aliases: ["sku", "skucode"], type: "code" }, { member: "qty_base", header: "qty_base", aliases: ["quantity_base", "quantity", "qty", "target"], type: "qty" }],
};

export const headersOf = (kind: KpiKind): string[] => SPECS[kind].map((s) => s.header);

export interface RowIssue {
  row: number;
  column: Col | "sheet";
  code: "missing_header" | "required" | "invalid" | "past_month" | "duplicate" | "too_many_rows" | "no_rows";
}
export interface ParsedKpi {
  rows: Record<string, unknown>[];
  sheetRows: number[];
  issues: RowIssue[];
}

const blank = (c: unknown) => c === undefined || c === null || String(c).trim() === "";

/** Exact decimal text with at most `places` fraction digits as an integer count of 10^-places units (string arithmetic, no float); null if not exact. */
function scaled(cell: unknown, places: number, maxWhole: number): { whole: number; frac: string } | null {
  let s: string;
  if (typeof cell === "number") {
    if (!Number.isFinite(cell) || cell < 0) return null;
    s = String(cell);
    if (/e/i.test(s)) return null;
  } else if (typeof cell === "string") s = latinDigits(cell.trim());
  else return null;
  const m = new RegExp(`^(\\d{1,13})(?:\\.(\\d{1,${places}}))?$`).exec(s);
  if (!m) return null;
  const whole = Number(m[1]);
  if (!Number.isSafeInteger(whole) || whole > maxWhole) return null;
  return { whole, frac: (m[2] ?? "").padEnd(places, "0") };
}

/** Decimal3 string ("500.000"), as the contract wants it. */
export function dec3Of(cell: unknown): string | null {
  const p = scaled(cell, 3, 9_999_999_999_999);
  return p ? `${p.whole}.${p.frac}` : null;
}
/** Taka cell to integer milli-taka (exact; at most 3 decimals); null when invalid or above the contract maximum. */
export function takaCellToMtk(cell: unknown): number | null {
  const p = scaled(cell, 3, MAX_MTK / 1000);
  if (!p) return null;
  const v = p.whole * 1000 + Number(p.frac);
  return Number.isSafeInteger(v) && v <= MAX_MTK ? v : null;
}
/** A whole number cell (integer or digits) within [0, max]. */
export function wholeOf(cell: unknown, max: number): number | null {
  if (typeof cell === "number") return Number.isSafeInteger(cell) && cell >= 0 && cell <= max ? cell : null;
  if (typeof cell !== "string") return null;
  const s = latinDigits(cell.trim());
  if (!/^\d{1,13}$/.test(s)) return null;
  const n = Number(s);
  return n <= max ? n : null;
}

function readCell(spec: Spec, cell: unknown, currentMonth: string): { value?: unknown; issue?: RowIssue["code"] } {
  if (blank(cell)) return spec.optional ? { value: undefined } : { issue: "required" };
  switch (spec.type) {
    case "code": {
      const v = String(cell).trim();
      return CODE.test(v) ? { value: v } : { issue: "invalid" };
    }
    case "month": {
      const m = monthOf(cell);
      if (!m) return { issue: "invalid" };
      return m < currentMonth ? { issue: "past_month" } : { value: m };
    }
    case "int": {
      const n = wholeOf(cell, MAX_MEMO_TARGET);
      return n === null ? { issue: "invalid" } : { value: n };
    }
    case "bp": {
      const n = wholeOf(cell, 10_000);
      return n === null ? { issue: "invalid" } : { value: n };
    }
    case "qty": {
      const n = wholeOf(cell, MAX_QTY_BASE);
      return n === null ? { issue: "invalid" } : { value: n };
    }
    case "taka": {
      const n = takaCellToMtk(cell);
      return n === null ? { issue: "invalid" } : { value: n };
    }
    case "dec3": {
      const d = dec3Of(cell);
      return d === null ? { issue: "invalid" } : { value: d };
    }
  }
}

/** Reads the first sheet (header row first) of one section. `currentMonth` is the Dhaka month: earlier months are never rewritten. */
export function parseKpiSheet(kind: KpiKind, cells: readonly (readonly unknown[])[], currentMonth: string): ParsedKpi {
  const specs = SPECS[kind];
  const issues: RowIssue[] = [];
  const header = (cells[0] ?? []).map(norm);
  const col = new Map<Col, number>();
  for (const s of specs) {
    const i = header.findIndex((h) => h === s.header || h === s.member || s.aliases.includes(h));
    if (i >= 0) col.set(s.member, i);
    else if (!s.optional) issues.push({ row: 1, column: s.member, code: "missing_header" });
  }
  if (issues.length) return { rows: [], sheetRows: [], issues };
  const rows: Record<string, unknown>[] = [];
  const sheetRows: number[] = [];
  const seen = new Set<string>();
  const keyMembers: Col[] = kind === "brand" ? ["route_code", "brand_code", "month"] : kind === "month" ? ["route_code", "month"] : kind === "programme" ? ["route_code", "programme_code", "month"] : kind === "campaign" ? ["outlet_code", "programme_code", "month"] : ["outlet_code", "programme_code", "month", "sku_code"];
  let used = 0;
  cells.slice(1).forEach((r, i) => {
    const row = i + 2;
    if (!r || r.every(blank)) return;
    if (++used > MAX_KPI_ROWS) {
      if (used === MAX_KPI_ROWS + 1) issues.push({ row, column: "sheet", code: "too_many_rows" });
      return;
    }
    const out: Record<string, unknown> = {};
    let bad = false;
    for (const s of specs) {
      const c = col.get(s.member);
      const res = readCell(s, c === undefined ? undefined : r[c], currentMonth);
      if (res.issue) {
        issues.push({ row, column: s.member, code: res.issue });
        bad = true;
      } else if (res.value !== undefined) out[s.member] = res.value;
    }
    if (bad) return;
    // the brand upload's allocation defaults to 0 on the server when the column is absent or empty; send nothing then
    const key = keyMembers.map((m) => String(out[m])).join("\u0000");
    if (seen.has(key)) return void issues.push({ row, column: keyMembers[0]!, code: "duplicate" });
    seen.add(key);
    rows.push(out);
    sheetRows.push(row);
  });
  if (rows.length === 0 && issues.length === 0) issues.push({ row: 1, column: "sheet", code: "no_rows" });
  return { rows, sheetRows, issues };
}

/** Sheet rows of the downloadable template: the header and one example line. */
export function kpiTemplateCells(kind: KpiKind, monthText: string): string[][] {
  const example: Record<KpiKind, string[]> = {
    brand: ["R-334-01", "MAXR", monthText, "119", "5700"],
    month: ["R-334-01", monthText, "5700", "2700", "1500"],
    programme: ["R-334-01", "MBLS", monthText, "20.000"],
    campaign: ["DHK-334-001", "CAMP-NOV", monthText, "140.000", "8"],
    "campaign-sku": ["DHK-334-001", "CAMP-NOV", monthText, "MaxR-20S", "140"],
  };
  return [headersOf(kind), example[kind]];
}

const SHAPE: Record<Type, (v: unknown) => boolean> = {
  code: (v) => typeof v === "string" && CODE.test(v),
  month: (v) => typeof v === "string" && MONTH.test(v),
  int: (v) => typeof v === "number" && Number.isSafeInteger(v) && v >= 0 && v <= MAX_MEMO_TARGET,
  bp: (v) => typeof v === "number" && Number.isSafeInteger(v) && v >= 0 && v <= 10_000,
  qty: (v) => typeof v === "number" && Number.isSafeInteger(v) && v >= 0 && v <= MAX_QTY_BASE,
  taka: (v) => typeof v === "number" && Number.isSafeInteger(v) && v >= 0 && v <= MAX_MTK,
  dec3: (v) => typeof v === "string" && /^\d{1,13}(\.\d{1,3})?$/.test(v),
};

/** Shape rules the proxy applies before calling the API (the API validates codes and states again). Exactly one section per request. */
export function kpiTargetRules(op: "validate" | "commit", body: Record<string, unknown>): { pointer: string; code: string }[] {
  const out: { pointer: string; code: string }[] = [];
  const members = Object.values(SECTION_MEMBER) as string[];
  for (const k of Object.keys(body)) if (!["batch_uuid", "dry_run", "change_reason", ...members].includes(k)) out.push({ pointer: `/${k}`, code: "unknown_member" });
  if (typeof body.batch_uuid !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(body.batch_uuid)) out.push({ pointer: "/batch_uuid", code: "invalid" });
  if (body.dry_run !== (op === "validate")) out.push({ pointer: "/dry_run", code: "invalid" });
  const present = (Object.keys(SECTION_MEMBER) as KpiKind[]).filter((k) => body[SECTION_MEMBER[k]] !== undefined);
  if (present.length !== 1) {
    out.push({ pointer: "/rows", code: "invalid" });
    return out;
  }
  const kind = present[0]!;
  const member = SECTION_MEMBER[kind];
  const rows = body[member];
  if (!Array.isArray(rows) || rows.length < 1 || rows.length > MAX_KPI_ROWS) return [...out, { pointer: `/${member}`, code: "invalid" }];
  const specs = SPECS[kind];
  const allowed = specs.map((s) => s.member as string);
  const seen = new Set<string>();
  for (let i = 0; i < rows.length; i++) {
    const r = rows[i] as Record<string, unknown> | null;
    const ok = typeof r === "object" && r !== null && !Array.isArray(r) && Object.keys(r).every((k) => allowed.includes(k)) && specs.every((s) => (r[s.member] === undefined ? !!s.optional : SHAPE[s.type](r[s.member])));
    if (!ok) {
      out.push({ pointer: `/${member}/${i}`, code: "invalid" });
      break; // one bad row is enough to refuse; the report comes from the preview
    }
    const key = specs.filter((s) => s.type === "code" || s.type === "month").map((s) => String(r![s.member])).join("\u0000");
    if (seen.has(key)) {
      out.push({ pointer: `/${member}/${i}`, code: "duplicate" });
      break;
    }
    seen.add(key);
  }
  return out;
}

/** Preview answer of a dry run (contract `RouteKpiTargetPreview`); `rows` is added by the page so the shared flow has its count. */
export type KpiPreview = Schemas["RouteKpiTargetPreview"];
/** Answer of GET /v1/admin/route-kpi-targets. */
export type KpiTargetList = Schemas["RouteKpiTargetList"];
