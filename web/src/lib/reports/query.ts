// URL search params <-> ReportQuery. One function builds the query for the screen, the Excel export and the print view,
// so "Get Excel runs the same query as the screen" holds by construction (F-WEB-040). Scope is never built here: the geo
// selectors only narrow, and levels at or above the caller's own reach are dropped (F-WEB-041, docs/24 s8.5).
import type { Schemas, ScopeSummary } from "@/contract/types";
import { businessDate } from "@/lib/i18n";
import type { WebReport } from "./catalog";

export type ReportQuery = Schemas["ReportQuery"];
export type OutputFormat = ReportQuery["output"]["format"];

export const GEO_LEVELS = ["wing", "division", "territory", "zone", "route"] as const;
export type GeoLevel = (typeof GEO_LEVELS)[number];
export const PAGE_SIZES = [10, 25, 50, 100, 500] as const;

type Params = Record<string, string | string[] | undefined>;

/** A real calendar date (2026-02-30 is not), by round trip. */
const validDate = (s: string | undefined): s is string => {
  if (!s || !DATE.test(s)) return false;
  const d = new Date(`${s}T00:00:00Z`);
  return !Number.isNaN(d.getTime()) && d.toISOString().slice(0, 10) === s;
};
const validMonth = (s: string | undefined): s is string => {
  const m = s ? MONTH.exec(s) : null;
  return Boolean(m) && Number(s!.slice(5)) >= 1 && Number(s!.slice(5)) <= 12;
};
const first = (v: string | string[] | undefined): string | undefined => (Array.isArray(v) ? v[0] : v);
const DATE = /^\d{4}-\d{2}-\d{2}$/;
const MONTH = /^\d{4}-\d{2}$/;
const CODE = /^[A-Za-z0-9_.-]{1,32}$/;

function ids(v: string | string[] | undefined, max: number): number[] | undefined {
  // The page passes one string, the export route getAll(): both may carry comma lists, so split either form.
  const raw = (Array.isArray(v) ? v : [v ?? ""]).flatMap((s) => s.split(",")).map((s) => s.trim()).filter(Boolean);
  const out = [...new Set(raw.filter((s) => /^\d{1,10}$/.test(s)).map(Number).filter((n) => n <= 2_147_483_647))].slice(0, max);
  return out.length ? out : undefined;
}

const DEPTH: Record<string, number> = { national: 0, wing: 1, division: 2, territory: 3, zone: 4, route: 5 };
/** An unknown node type fails closed: it is treated as deeper than every level, so every level is locked. */
const depthOf = (type: string): number => DEPTH[type] ?? 99;

export interface GeoBinding {
  /** Levels the filter shows but cannot change: the server has already fixed them from the token. */
  locked: GeoLevel[];
  /** At the shallowest own level: the only ids the user may narrow to (their own nodes). */
  ownIds: Partial<Record<GeoLevel, number[]>>;
}

/** What the caller's scope fixes. National reach locks nothing; one territory locks wing, division and territory. */
export function bindGeo(scope: ScopeSummary | null): GeoBinding {
  const nodes = scope?.nodes ?? [];
  if (nodes.length === 0) return { locked: [...GEO_LEVELS], ownIds: {} };
  const minDepth = Math.min(...nodes.map((n) => depthOf(n.type)));
  const locked: GeoLevel[] = [];
  const ownIds: GeoBinding["ownIds"] = {};
  for (const lvl of GEO_LEVELS) {
    const d = DEPTH[lvl]!;
    if (d < minDepth) locked.push(lvl);
    else {
      const own = nodes.filter((n) => n.type === lvl).map((n) => n.id);
      if (d === minDepth && own.length === 1) locked.push(lvl);
      else if (own.length > 0) ownIds[lvl] = own; // every level that has own nodes only narrows to them
    }
  }
  return { locked, ownIds };
}

/** Apply the binding to requested geo ids: drop locked levels and anything outside the user's own nodes. */
export function boundGeo(scope: ScopeSummary | null, requested: Partial<Record<GeoLevel, number[]>>): NonNullable<ReportQuery["geo"]> | undefined {
  const b = bindGeo(scope);
  const out: Partial<Record<GeoLevel, number[]>> = {};
  for (const lvl of GEO_LEVELS) {
    if (b.locked.includes(lvl)) continue;
    let v = requested[lvl];
    const own = b.ownIds[lvl];
    if (v && own) v = v.filter((id) => own.includes(id));
    if (v?.length) out[lvl] = v;
  }
  return Object.keys(out).length ? out : undefined;
}

export function geoFromParams(params: Params): Partial<Record<GeoLevel, number[]>> {
  const out: Partial<Record<GeoLevel, number[]>> = {};
  const max: Record<GeoLevel, number> = { wing: 50, division: 100, territory: 300, zone: 1100, route: 2000 };
  for (const lvl of GEO_LEVELS) {
    const v = ids(params[lvl], max[lvl]);
    if (v) out[lvl] = v;
  }
  return out;
}

export function buildReportQuery(report: WebReport, params: Params, scope: ScopeSummary | null, format: OutputFormat = "json", today: string = businessDate()): ReportQuery {
  const has = (f: string) => report.filters.includes(f as never);
  const q: ReportQuery = { period: {}, date_grouping: "total", active_status: "all", field_force_type: "all", output: { format, page: 1, page_size: 50 } };

  if (has("period")) {
    const pick = (k: string) => (validDate(first(params[k])) ? first(params[k]) : undefined);
    const from = pick("from");
    const to = pick("to");
    const date = pick("date");
    const m = first(params.month);
    const month = validMonth(m) ? m : undefined;
    if (report.singleDate) q.period = { date: date ?? today };
    else if (month) q.period = { month };
    else q.period = { from: from ?? today, to: to ?? from ?? today };
    if (q.period.from && q.period.to && q.period.from > q.period.to) q.period = { from: q.period.to, to: q.period.from };
  } else {
    q.period = { date: today };
  }
  if (has("geo")) {
    const g = boundGeo(scope, geoFromParams(params));
    if (g) q.geo = g;
  }
  if (has("date_grouping")) {
    const g = first(params.group);
    q.date_grouping = g === "day" || g === "week" || g === "month" || g === "total" ? g : (report.grouping ?? "total");
  }
  if (has("location")) {
    const l = first(params.location);
    if (l && ["wing", "division", "territory", "zone", "route", "outlet", "user"].includes(l)) q.location = l as NonNullable<ReportQuery["location"]>;
  }
  if (has("category")) {
    const c = ids(params.category, 10);
    if (c) q.category = c;
  }
  if (has("product_type")) {
    const p = first(params.product_type);
    if (p && ["category", "segment", "brand", "variant", "sku"].includes(p)) q.product_type = p as NonNullable<ReportQuery["product_type"]>;
  }
  if (has("products")) {
    const p = ids(params.products, 500);
    if (p) q.products = p;
  }
  if (has("active_status")) {
    const s = first(params.status);
    q.active_status = s === "active" || s === "inactive" ? s : "all";
  }
  if (has("sub_channels")) {
    const s = ids(params.sub_channels, 20);
    if (s) q.sub_channels = s;
  }
  if (has("field_force_type")) {
    const f = first(params.ff);
    q.field_force_type = f === "sr" || f === "amo" ? f : "all";
  }
  for (const [key, param] of [["std_criteria", "std"], ["memo_criteria", "memo"]] as const) {
    if (!has(key)) continue;
    const combined = first(params[param]) || `${first(params[`${param}_op`]) ?? ""}${first(params[`${param}_val`]) ?? ""}`;
    const m = /^(>=|<=|>|<|=)(-?\d+(?:\.\d+)?)$/.exec(combined);
    if (m && Number.isFinite(Number(m[2]))) q[key] = { op: m[1] as "<", value: Number(m[2]) };
  }
  if (has("outlet_code")) {
    const c = first(params.outlet_code);
    if (c && CODE.test(c)) q.outlet_code = c;
  }
  const ps = Number(first(params.size));
  q.output.page_size = (PAGE_SIZES as readonly number[]).includes(ps) ? (ps as (typeof PAGE_SIZES)[number]) : 50;
  const page = Number(first(params.page));
  q.output.page = Number.isInteger(page) && page >= 1 && page <= 100_000 ? page : 1;
  return q;
}

/** The same query for another output format, keeping every filter (the Excel and Print links). */
export function withFormat(q: ReportQuery, format: OutputFormat): ReportQuery {
  // Page and page size are required by the contract's type but ignored for xlsx, pdf and print (the whole result is exported).
  return { ...q, output: { ...q.output, format, page: 1, page_size: 500 } };
}
