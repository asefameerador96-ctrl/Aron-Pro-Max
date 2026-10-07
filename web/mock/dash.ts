// Dashboard and report endpoints of the mock API. Typed with the generated contract types; every figure comes from mock/seed.ts.
import { randomUUID } from "node:crypto";
import type { Problem, ProblemCode, Role, ScopeSummary, Schemas } from "../src/contract/types";
import { GEO, OUTLETS, PRODUCT_NODES, SEED_DATE, SKUS, controlTotals, pct, routesInScope, sum, type SeedRoute } from "./seed";

type Cell = string | number | boolean | null;
type Row = Record<string, Cell>;
type Col = Schemas["ReportColumn"];

export interface DashCtx {
  user: { role: Role; scope: ScopeSummary; id: number; name: string; password: string };
  method: string;
  path: string;
  url: URL;
  body: () => Promise<unknown>;
  send: (status: number, body: unknown, headers?: Record<string, string | string[]>) => void;
  raw: (status: number, contentType: string, body: string | Buffer, headers?: Record<string, string>) => void;
  problem: (status: number, code: ProblemCode, extra?: Partial<Problem>) => Problem;
  store: DashStore;
}

export interface DashStore {
  /** Simulated "now" (ISO), so the 17:00 take-action rule is testable. */
  now: string | null;
  actions: Schemas["TrackingAction"][];
  leave: Schemas["LeaveApplication"][];
  exceptionDecisions: Record<string, "reviewed" | "dismissed" | "confirmed">;
  exports: { reportKey: string; format: string; query: unknown; user: number; at: string }[];
  passwords: Record<number, string>;
  /** Videos added in the admin portal (F-WEB-035 shows them without a deploy). */
  tutorialExtra: Schemas["TutorialItem"][];
}

export function freshDashStore(): DashStore {
  return {
    now: null,
    actions: [],
    leave: [
      { leave_uuid: "11111111-1111-4111-8111-111111111111", user_id: 2001, leave_type_code: "casual", from_date: "2026-10-12", to_date: "2026-10-13", days: 2, reason: "Family event", status: "pending" },
      { leave_uuid: "22222222-2222-4222-8222-222222222222", user_id: 2005, leave_type_code: "sick", from_date: "2026-10-08", to_date: "2026-10-08", days: 1, reason: "Fever", status: "pending" },
    ],
    exceptionDecisions: {},
    exports: [],
    passwords: {},
    tutorialExtra: [],
  };
}

const c = (key: string, label_en: string, type: Col["type"], extra: Partial<Col> = {}): Col => ({ key, label_en, label_bn: label_en, type, ...extra });
const FIELD = {
  route: c("route_name", "Route", "string"),
  zone: c("zone", "Zone", "string"),
  user: c("user_name", "SR", "string"),
  target: c("target_outlets", "Target outlets", "integer"),
  visited: c("visited", "Visited", "integer"),
  success: c("successful", "Successful (STD)", "integer"),
  memos: c("memos", "Memos", "integer"),
  net: c("net_mtk", "Net sales", "mtk"),
  gross: c("gross_mtk", "Gross sales", "mtk"),
  strike: c("strike_pct", "Strike rate", "pct"),
  geo: c("geo_pct", "Geo valid", "pct"),
  force: c("force_sale", "Force sale", "integer"),
  mock: c("mock_visits", "Mock visits", "integer"),
  susp: c("suspicious", "Suspicious visits", "integer"),
  free: c("free_qty", "Free sample qty", "integer"),
  offline: c("offline_memos", "Offline memos", "integer"),
  online: c("online_memos", "Online memos", "integer"),
  state: c("state", "Final submit", "string"),
  first: c("first_submit_at", "First submit", "timestamp"),
  last: c("last_submit_at", "Last submit", "timestamp"),
  login: c("logged_in_at", "Check-in", "timestamp"),
  dl_min: c("download_min", "Download MIN", "timestamp"),
  ul_max: c("upload_max", "Upload MAX", "timestamp"),
  count: c("event_count", "Count", "integer"),
  date: c("business_date", "Date", "date"),
  source: c("source", "Source", "string"),
};
type RouteField = keyof typeof FIELD;

function routeRow(rt: SeedRoute): Row {
  return {
    route_name: rt.route_name, zone: `Z-${rt.zone_id}`, user_name: rt.user_name, target_outlets: rt.target_outlets, visited: rt.visited, successful: rt.successful, memos: rt.memos,
    net_mtk: rt.net_mtk, gross_mtk: rt.gross_mtk, strike_pct: pct(rt.successful, rt.visited), geo_pct: pct(rt.geo_valid, rt.visited), force_sale: rt.force_sale, mock_visits: rt.mock_visits,
    suspicious: rt.suspicious, free_qty: rt.free_qty, offline_memos: rt.offline_memos, online_memos: rt.memos - rt.offline_memos,
    state: rt.state === "final_submitted" ? "Done" : "Not Done", first_submit_at: rt.submitted_at, last_submit_at: rt.submitted_at, logged_in_at: rt.logged_in_at,
    download_min: rt.logged_in_at, upload_max: rt.submitted_at, event_count: rt.memos,
    route_id: rt.route_id, business_date: SEED_DATE, source: rt.route_id % 2 === 0 ? "Market" : "Warehouse",
  };
}

/** Route-day reports: a column list over the route-day projection. `sum` columns get a totals row. */
const ROUTE_REPORTS: Record<string, { fields: RouteField[]; sum: RouteField[] }> = {
  "route-std": { fields: ["route", "target", "visited", "success", "strike"], sum: ["target", "visited", "success"] },
  "route-memo": { fields: ["route", "memos", "net"], sum: ["memos", "net"] },
  "std-memo": { fields: ["route", "success", "memos", "net"], sum: ["success", "memos", "net"] },
  "sr-efficiency": { fields: ["user", "target", "visited", "success", "memos", "strike", "geo"], sum: ["target", "visited", "success", "memos"] },
  "route-bsr-cpr": { fields: ["route", "visited", "success", "strike"], sum: ["visited", "success"] },
  "tso-top-sheet": { fields: ["zone", "target", "visited", "success", "strike", "geo", "memos"], sum: ["target", "visited", "success", "memos"] },
  "data-entry-log": { fields: ["route", "dl_min", "ul_max", "count"], sum: ["count"] },
  "final-submit-log": { fields: ["zone", "route", "state", "first", "last", "count"], sum: ["count"] },
  "online-offline": { fields: ["route", "online", "offline", "memos"], sum: ["online", "offline", "memos"] },
  "free-sample": { fields: ["route", "free"], sum: ["free"] },
  "task-planner": { fields: ["user", "target", "visited"], sum: ["target", "visited"] },
  "gigo": { fields: ["user", "login", "geo"], sum: [] },
  "suspicious-location": { fields: ["date", "route", "user", "mock", "susp"], sum: ["mock", "susp"] },
  "memo-number-gaps": { fields: ["user", "memos", "offline"], sum: ["memos"] },
    "amo-call": { fields: ["user", "visited", "success", "memos"], sum: ["visited", "success", "memos"] },
  "dss": { fields: ["route", "memos", "net", "gross"], sum: ["memos", "net", "gross"] },
  "ds-rrs": { fields: ["route", "memos", "gross", "net"], sum: ["memos", "gross", "net"] },
  "qc-report": { fields: ["source", "route", "visited", "success"], sum: ["visited", "success"] },
  "route-qc": { fields: ["route", "visited", "success"], sum: ["visited", "success"] },
  "leaderboard": { fields: ["zone", "target", "net", "strike"], sum: ["target", "net"] },
  "by-route-geo-capture": { fields: ["route", "target", "geo"], sum: ["target"] },
  "sr-outlets": { fields: ["user", "target", "visited"], sum: ["target", "visited"] },
  "by-outlet-by-day": { fields: ["route", "visited", "success", "net"], sum: ["visited", "success", "net"] },
};

function inScope(user: DashCtx["user"], q: Schemas["ReportQuery"] | null): SeedRoute[] {
  let rows = routesInScope(user.scope.nodes);
  const g = q?.geo;
  if (g?.territory?.length) rows = rows.filter((x) => g.territory!.includes(x.territory_id));
  if (g?.zone?.length) rows = rows.filter((x) => g.zone!.includes(x.zone_id));
  if (g?.route?.length) rows = rows.filter((x) => g.route!.includes(x.route_id));
  if (g?.division?.length) rows = rows.filter((x) => g.division!.includes(x.division_id));
  if (g?.wing?.length) rows = rows.filter((x) => g.wing!.includes(x.wing_id));
  return rows;
}

const ADMIN_READ: Role[] = ["ADMIN", "SUPERADMIN", "SUPPORT", "ANALYST"];
const PII_ROLES: Role[] = ["ANALYST", "ADMIN", "SUPERADMIN", "SUPPORT"];
export const hasPii = (role: Role): boolean => PII_ROLES.includes(role);

function build(key: Schemas["ReportKey"], user: DashCtx["user"], q: Schemas["ReportQuery"]): { columns: Col[]; rows: Row[]; totals: Row | null } | null {
  const pii = hasPii(user.role);
  if (key === "retailer-list" || key === "by-outlet") {
    const reach = new Set(inScope(user, q).map((x) => x.route_id));
    let rows = OUTLETS.filter((o) => reach.has(o.route_id));
    if (q.active_status && q.active_status !== "all") rows = rows.filter((o) => o.status === q.active_status);
    if (q.outlet_code) rows = rows.filter((o) => o.code === q.outlet_code);
    const columns = [c("code", "Outlet code", "string"), c("name", "Outlet", "string"), c("owner_name", "Owner", "string", { pii: true }), c("phone", "Phone", "string", { pii: true }), c("sub_channel", "Sub channel", "string"), c("status", "Status", "string"), ...(key === "by-outlet" ? [c("visits", "Visits", "integer"), c("memos", "Memos", "integer"), c("dues_mtk", "Dues", "mtk")] : [])];
    return { columns, rows: rows.map((o) => ({ code: o.code, name: o.name, owner_name: pii ? o.owner_name : null, phone: pii ? o.phone : null, sub_channel: o.sub_channel, status: o.status, visits: 1, memos: 1, dues_mtk: 12_500 })), totals: null };
  }
  if (key === "sku-list") {
    const columns = [c("code", "SKU", "string"), c("name", "Name", "string"), c("retail_price_mtk", "Retail price", "mtk"), ...(pii ? [c("trade_price_mtk", "Trade price", "mtk")] : []), c("status", "Status", "string")];
    const rows = SKUS.filter((s) => !q.active_status || q.active_status === "all" || s.status === q.active_status);
    return { columns, rows: rows.map((s) => ({ code: s.code, name: s.name, retail_price_mtk: s.retail_mtk, ...(pii ? { trade_price_mtk: s.trade_mtk } : {}), status: s.status })), totals: null };
  }
  if (key === "geofence-calibration") {
    const reach = inScope(user, q);
    const bands: [string, number, number][] = [["0-25", 55, 0], ["25-50", 25, 0], ["50-100", 15, 40], ["100+", 5, 100]];
    const total = sum(reach, (r) => r.visited);
    const rows = bands.map(([band, share, force]): Row => ({ distance_band_m: band, geo_class: "Urban", territory: "T", visits: Math.round((total * share) / 100), force_sale_share: force }));
    return { columns: [c("distance_band_m", "Distance band (m)", "string"), c("geo_class", "Geo class", "string"), c("territory", "Territory", "string"), c("visits", "Visits", "integer"), c("force_sale_share", "Force-sale share", "pct")], rows: total === 0 ? [] : rows, totals: null };
  }
  const spec = ROUTE_REPORTS[key];
  if (!spec) return null;
  const rows = inScope(user, q);
  const columns = spec.fields.map((f) => FIELD[f]);
  const out = rows.map(routeRow);
  const totals: Row | null = spec.sum.length ? Object.fromEntries(spec.sum.map((f) => [FIELD[f].key, sum(out, (x) => Number(x[FIELD[f].key] ?? 0))])) : null;
  return { columns, rows: out, totals };
}

const REPORT_KEYS = [...Object.keys(ROUTE_REPORTS), "retailer-list", "by-outlet", "sku-list"];

function listDefs(): Schemas["ReportDefinition"][] {
  return REPORT_KEYS.map((k) => ({ report_key: k as Schemas["ReportKey"], title_en: k, area: "sales", grain: "route-day", formats: ["json", "xlsx", "print"], columns: [], filters: ["period", "geo"], columns_known: true }));
}

const XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
const sanitise = (v: Cell): Cell => (typeof v === "string" && /^[=+\-@\t\r]/.test(v) ? `'${v}` : v);

export async function handleDash(x: DashCtx): Promise<boolean> {
  const { path, method, user, send, problem, store } = x;
  const nowIso = store.now ?? new Date().toISOString();
  const kp = (rows: SeedRoute[]) => {
    const d = controlTotals(rows);
    const logged = rows.filter((r) => r.state !== "not_started").length;
    const submitted = rows.filter((r) => r.state === "sales_submitted" || r.state === "final_submitted").length;
    const zones = new Set(rows.map((r) => r.zone_id));
    const zoneDone = [...zones].filter((z) => rows.filter((r) => r.zone_id === z).every((r) => r.state === "final_submitted")).length;
    return {
      target_routes: rows.length, logged_in_routes: logged, login_pct: pct(logged, rows.length), sales_submitted_routes: submitted, submit_pct_of_logged_in: pct(submitted, logged), day_completion_pct: pct(rows.filter((r) => r.state === "final_submitted").length, rows.length),
      target_outlets: d.target_outlets, visited_outlets: d.visited, successful_calls: d.successful, strike_rate_pct: pct(d.successful, d.visited), active_memo_count: d.memos, gross_mtk: sum(rows, (r) => r.gross_mtk), net_mtk: d.net_mtk,
      geo_valid_pct: pct(sum(rows, (r) => r.geo_valid), d.visited), force_sale_pct: pct(sum(rows, (r) => r.force_sale), d.visited), mock_visits: sum(rows, (r) => r.mock_visits), suspicious_visits: sum(rows, (r) => r.suspicious),
      zones_with_target_routes: zones.size, zones_final_submitted: zoneDone, final_submit_pct: pct(zoneDone, zones.size),
    } satisfies Schemas["DashboardKpis"];
  };
  const asOf = SEED_DATE + "T11:00:00.000Z";

  if (path === "/v1/reports" && method === "GET") return send(200, { items: listDefs() }), true;

  const rq = /^\/v1\/reports\/([a-z-]+)\/query$/.exec(path);
  if (rq && method === "POST") {
    const key = rq[1]! as Schemas["ReportKey"];
    const q = (await x.body()) as Schemas["ReportQuery"] | undefined;
    if (!q || typeof q !== "object" || !q.output) return send(400, problem(400, "ERR_VALIDATION")), true;
    const built = build(key, user, q);
    if (!built) return send(404, problem(404, "ERR_NOT_FOUND")), true;
    const total_rows = built.rows.length;
    const page = q.output.page ?? 1;
    const size = q.output.page_size ?? 50;
    if (q.output.format === "json") {
      return send(200, { report_key: key, as_of: asOf, columns: built.columns, rows: built.rows.slice((page - 1) * size, page * size), totals: built.totals, page, page_size: size, total_rows } satisfies Schemas["ReportResult"]), true;
    }
    store.exports.push({ reportKey: key, format: q.output.format, query: q, user: user.id, at: nowIso });
    if (q.output.format === "xlsx") {
      if (total_rows > 10_000) return send(202, { export_id: randomUUID(), report_key: key, status: "queued", created_at: nowIso } satisfies Schemas["ExportJob"]), true;
      // A tab-separated stand-in for the workbook: sanitised cells and a watermark line, enough for the web's pass-through tests.
      const lines = [`WATERMARK\t${user.name}\t${nowIso}`, built.columns.map((k) => k.key).join("\t"), ...built.rows.map((r) => built.columns.map((k) => String(sanitise(r[k.key] ?? null) ?? "")).join("\t"))];
      return x.raw(200, XLSX, lines.join("\n"), { "X-Export-Rows": String(total_rows) }), true;
    }
    if (q.output.format === "print") {
      const body = `<!doctype html><title>${key}</title><table>${built.rows.map((r) => `<tr>${built.columns.map((k) => `<td>${r[k.key] ?? ""}</td>`).join("")}</tr>`).join("")}</table><p data-totals>${JSON.stringify(built.totals)}</p>`;
      return x.raw(200, "text/html; charset=utf-8", body), true;
    }
    return send(202, { export_id: randomUUID(), report_key: key, status: "queued", created_at: nowIso } satisfies Schemas["ExportJob"]), true;
  }

  const ej = /^\/v1\/report-exports\/([0-9a-f-]{36})$/.exec(path);
  if (ej && method === "GET") return send(200, { export_id: ej[1]!, report_key: "dss", status: "done", row_count: 6, created_at: nowIso, download_url: "https://files.example/export.xlsx", expires_at: nowIso } satisfies Schemas["ExportJob"]), true;

  if (path === "/v1/dashboards/summary") {
    const rows = routesInScope(user.scope.nodes);
    const kpis = kp(rows);
    const top = user.scope.nodes[0] ?? { type: "national" as const, id: 0 };
    const childLevel = ({ national: "wing", wing: "division", division: "territory", territory: "zone", zone: "route", route: "route" } as const)[top.type];
    const key = (r: SeedRoute): number => (childLevel === "wing" ? r.wing_id : childLevel === "division" ? r.division_id : childLevel === "territory" ? r.territory_id : childLevel === "zone" ? r.zone_id : r.route_id);
    const nodeName = (id: number): string | null => GEO.find((g) => g.id === id)?.name ?? rows.find((r) => r.route_id === id)?.route_name ?? null;
    const byChild = [...new Set(rows.map(key))].map((id) => ({ node: { type: childLevel, id, name: nodeName(id) }, kpis: kp(rows.filter((r) => key(r) === id)) }));
    const body: Schemas["DashboardSummary"] = {
      as_of: asOf, from: x.url.searchParams.get("from") ?? SEED_DATE, to: x.url.searchParams.get("to") ?? SEED_DATE, node: { type: top.type, id: top.id, name: "name" in top ? (top.name ?? null) : null }, kpis,
      by_category: [{ category_code: "cigarette", base_unit: "stick", qty_base: 20_000, net_mtk: kpis.net_mtk }],
      by_channel: [{ code: "grocery", name: "Grocery", net_mtk: Math.round(kpis.net_mtk * 0.7), memo_count: kpis.active_memo_count, successful_calls: kpis.successful_calls, memo_ratio_pct: 100 }, { code: "pan", name: "Pan", net_mtk: Math.round(kpis.net_mtk * 0.3), memo_count: 0, successful_calls: 0, memo_ratio_pct: null }],
      by_segment: [{ code: "premium", name: "Premium", net_mtk: kpis.net_mtk, memo_count: kpis.active_memo_count, successful_calls: kpis.successful_calls }],
      by_brand: [{ code: "sample", name: "Sample", net_mtk: kpis.net_mtk, memo_count: kpis.active_memo_count, successful_calls: kpis.successful_calls, memo_ratio_pct: 100 }],
      children: byChild,
    };
    return send(200, body), true;
  }

  if (path === "/v1/dashboards/daily-tracking") {
    const rows = routesInScope(user.scope.nodes);
    const bucket = (r: SeedRoute): Schemas["DailyTrackingRow"]["bucket"] => {
      if (r.exception) return "exception";
      if (r.state === "not_started") return "not_logged_in";
      const p = pct(r.visited, r.target_outlets) ?? 0;
      return p >= 100 ? "ge_100" : p >= 90 ? "from_90" : p >= 80 ? "from_80" : "below_80";
    };
    const items: Schemas["DailyTrackingRow"][] = rows.map((r) => ({ route_id: r.route_id, route_name: r.route_name, zone_id: r.zone_id, user_id: r.user_id, user_name: r.user_name, state: r.state === "not_started" ? "not_started" : r.state === "final_submitted" ? "final_submitted" : r.state === "sales_submitted" ? "sales_submitted" : "in_field", target_outlets: r.target_outlets, visited_outlets: r.visited, successful_calls: r.successful, active_memo_count: r.memos, net_mtk: r.net_mtk, tilldate_target_achievement_pct: pct(r.visited, r.target_outlets), bucket: bucket(r) }));
    const cnt = (b: Schemas["DailyTrackingRow"]["bucket"]) => items.filter((i) => i.bucket === b).length;
    const body: Schemas["DailyTrackingPage"] = { as_of: asOf, business_date: x.url.searchParams.get("business_date") ?? SEED_DATE, items, next_cursor: null, comparator: { business_date: "2026-10-06", as_of_time: "17:00", buckets: { ge_100: cnt("ge_100") + 1, from_90: cnt("from_90"), from_80: cnt("from_80"), below_80: cnt("below_80"), exception: cnt("exception"), not_logged_in: cnt("not_logged_in") } } };
    return send(200, body), true;
  }

  if (path === "/v1/dashboards/login-submit") {
    const rows = routesInScope(user.scope.nodes);
    const rd = (r: SeedRoute): Schemas["RouteDayState"] => ({ route_id: r.route_id, business_date: SEED_DATE, state: r.state === "not_started" ? "not_started" : r.state === "final_submitted" ? "final_submitted" : r.state === "sales_submitted" ? "sales_submitted" : "in_field", submit_cycle: 1, submit_voided: false, route_code: String(r.route_id), route_name: r.route_name, logged_in_at: r.logged_in_at, sales_submitted_at: r.submitted_at, final_submitted_at: r.state === "final_submitted" ? r.submitted_at : null });
    const body: Schemas["LoginSubmitStatus"] = { as_of: asOf, business_date: SEED_DATE, kpis: kp(rows), not_logged_in: rows.filter((r) => r.state === "not_started").map(rd), logged_in_not_submitted: rows.filter((r) => r.state === "logged_in" || r.state === "in_field").map(rd), submitted: rows.filter((r) => r.state === "sales_submitted" || r.state === "final_submitted").map(rd), exceptions: rows.filter((r) => r.exception).map(rd), zones: [...new Set(rows.map((r) => r.zone_id))].map((z) => ({ zone_id: z, final_submitted: rows.filter((r) => r.zone_id === z).every((r) => r.state === "final_submitted") })) };
    return send(200, body), true;
  }

  if (path === "/v1/dashboards/sync-health") {
    const rows = routesInScope(user.scope.nodes);
    const items: Schemas["SyncHealthRow"][] = rows.filter((r) => r.user_id).map((r, i) => ({ user_id: r.user_id!, username: `sr${r.user_id}`, route_ids: [r.route_id], device_id: 500 + i, device_model: "Redmi 9A", app_version: "1.0.0", last_contact_at: asOf, last_batch_at: asOf, pending_rows_reported: r.offline_memos, rejected_count: 0, quarantined_count: r.suspicious, held_rows_alert: false, submit_count_mismatch: false, trust_level: "normal", sync_p95_s: 12 + i }));
    const body: Schemas["SyncHealthPage"] = { as_of: asOf, summary: { devices: items.length, devices_with_pending: items.filter((i) => i.pending_rows_reported > 0).length, held_rows_alerts: 0, rejected: 0, quarantined: sum(items, (i) => i.quarantined_count), mismatched_route_days: 0, ...(user.role === "TSO" ? {} : { config_ack_pct: 87.5, pending_photos: { count: 4, oldest_age_s: 5400 } }), quarantine_backlog: sum(items, (i) => i.quarantined_count) }, items, next_cursor: null, by_zone: [...new Set(rows.map((r) => r.zone_id))].map((z) => { const zr = rows.filter((r) => r.zone_id === z); const lg = zr.filter((r) => r.state !== "not_started").length; const sb = zr.filter((r) => r.state === "sales_submitted" || r.state === "final_submitted").length; return { zone_id: z, login_pct: pct(lg, zr.length), submit_pct: pct(sb, lg), final_submitted: zr.every((r) => r.state === "final_submitted"), trickle_p95_s: 15, quarantined: sum(zr, (r) => r.suspicious), pending_photos: 1, config_ack_pct: 87.5 }; }) };
    return send(200, body), true;
  }

  if (path === "/v1/dashboards/geo-validation") {
    const rows = routesInScope(user.scope.nodes);
    const visits = sum(rows, (r) => r.visited);
    const body: Schemas["GeoValidationSummary"] = { as_of: asOf, from: SEED_DATE, to: SEED_DATE, node: { type: "national", id: 0 }, visits, geo_valid_pct: pct(sum(rows, (r) => r.geo_valid), visits), force_sale_pct: pct(sum(rows, (r) => r.force_sale), visits), mock_pct: pct(sum(rows, (r) => r.mock_visits), visits), geo_mismatch_pct: 0, suspicious_pct: pct(sum(rows, (r) => r.suspicious), visits), signals_by_code: { GEO_MOCK: sum(rows, (r) => r.mock_visits) }, children: [] };
    return send(200, body), true;
  }

  if (path === "/v1/dashboards/daily-tracking/actions" && method === "POST") {
    const b = (await x.body()) as Schemas["TrackingActionRequest"] | undefined;
    if (!b || typeof b.note !== "string" || !b.note.trim() || !b.route_id) return send(400, problem(400, "ERR_VALIDATION")), true;
    const route = routesInScope(user.scope.nodes).find((r) => r.route_id === b.route_id);
    if (!route) return send(403, problem(403, "ERR_OUT_OF_SCOPE")), true;
    const dhaka = new Date(new Date(nowIso).getTime() + 6 * 3600_000).toISOString();
    if (b.business_date > dhaka.slice(0, 10) || (b.business_date === dhaka.slice(0, 10) && Number(dhaka.slice(11, 13)) < 17)) return send(409, problem(409, "ERR_REQUEST_STATE")), true;
    const prior = store.actions.find((a) => a.action_uuid === b.action_uuid);
    if (prior) return send(201, prior), true;
    const action: Schemas["TrackingAction"] = { action_uuid: b.action_uuid, route_id: b.route_id, business_date: b.business_date, note: b.note, created_by_user_id: user.id, created_at: nowIso, notified_user_ids: route.user_id ? [route.user_id, 2001] : [2001] };
    store.actions.push(action);
    return send(201, action), true;
  }

  const LEAVE_TERRITORY: Record<number, number> = { 2001: 334, 2005: 335 };
  const leaveVisible = (l: Schemas["LeaveApplication"]): boolean => {
    const terr = LEAVE_TERRITORY[l.user_id];
    return l.user_id === user.id || (terr !== undefined && routesInScope(user.scope.nodes).some((r) => r.territory_id === terr));
  };
  if (path === "/v1/leave" && method === "GET") {
    const st = x.url.searchParams.get("status");
    return send(200, { items: store.leave.filter((l) => leaveVisible(l) && (!st || l.status === st)), next_cursor: null } satisfies Schemas["LeavePage"]), true;
  }
  const lv = /^\/v1\/leave\/([0-9a-f-]{36})\/decision$/.exec(path);
  if (lv && method === "POST") {
    if (user.role !== "DMO") return send(403, problem(403, "ERR_FORBIDDEN")), true;
    const b = (await x.body()) as Schemas["DecisionRequest"] | undefined;
    const row = store.leave.find((l) => l.leave_uuid === lv[1] && leaveVisible(l));
    if (!row) return send(404, problem(404, "ERR_NOT_FOUND")), true;
    if (!b || (b.decision !== "approve" && b.decision !== "reject")) return send(400, problem(400, "ERR_VALIDATION")), true;
    if (row.status !== "pending") return send(409, problem(409, "ERR_REQUEST_STATE")), true;
    row.status = b.decision === "approve" ? "approved" : "rejected";
    row.decided_by_user_id = user.id;
    row.decided_at = nowIso;
    return send(200, row), true;
  }

  if (path === "/v1/tutorials") {
    const items: Schemas["TutorialItem"][] = [
      { tutorial_id: 1, kind: "manual", title_en: "SR manual", title_bn: "এসআর ম্যানুয়াল", url: "https://files.example/sr.pdf", sort: 1 },
      { tutorial_id: 2, kind: "manual", title_en: "AMO manual", title_bn: "এএমও ম্যানুয়াল", url: "https://files.example/amo.pdf", sort: 2 },
      { tutorial_id: 3, kind: "manual", title_en: "TSO manual", title_bn: "টিএসও ম্যানুয়াল", url: "https://files.example/tso.pdf", sort: 3 },
      { tutorial_id: 4, kind: "manual", title_en: "Web manual", title_bn: "ওয়েব ম্যানুয়াল", url: "https://files.example/web.pdf", sort: 4 },
      ...store.tutorialExtra,
    ];
    return send(200, { items }), true;
  }

  const signalsInScope = (): Schemas["RiskSignal"][] =>
    routesInScope(user.scope.nodes)
      .filter((r) => r.suspicious > 0 || r.mock_visits > 0)
      .map((r, i): Schemas["RiskSignal"] => {
        const id = 900 + i;
        const decided = store.exceptionDecisions[String(id)];
        const resampled = !decided && i === 0; // an AMO dismissal the server re-queued to the TSO (cfg.sec.fraud.dismissal_resample_pct)
        return { signal_id: id, code: "GEO_MOCK", severity: 3, business_date: SEED_DATE, subject_type: "route", subject_id: String(r.route_id), user_id: r.user_id, route_id: r.route_id, zone_id: r.zone_id, score: 80, evidence: { mock_visits: r.mock_visits }, status: decided ?? "open", config_version: 318, created_at: asOf, last_review: resampled ? { action: "dismissed", reviewer_user_id: 1004, note: "ok", at: asOf } : decided ? { action: decided, reviewer_user_id: user.id, at: nowIso } : null };
      });
  if (path === "/v1/risk-signals") {
    const st = x.url.searchParams.get("status");
    return send(200, { items: signalsInScope().filter((sg) => !st || sg.status === st), next_cursor: null }), true;
  }
  const rs = /^\/v1\/risk-signals\/(\d+)\/review$/.exec(path);
  if (rs && method === "POST") {
    const b = (await x.body()) as { action?: "reviewed" | "dismissed" | "confirmed"; review_uuid?: string } | undefined;
    if (!b?.action || !b.review_uuid) return send(400, problem(400, "ERR_VALIDATION")), true;
    const sig = signalsInScope().find((sg) => sg.signal_id === Number(rs[1]));
    if (!sig) return send(404, problem(404, "ERR_NOT_FOUND")), true;
    store.exceptionDecisions[rs[1]!] = b.action;
    return send(200, signalsInScope().find((sg) => sg.signal_id === Number(rs[1]))), true;
  }
  if (path === "/v1/day/exceptions") return send(200, { items: [{ exception_uuid: "33333333-3333-4333-8333-333333333333", raised_by_user_id: 1006, reason_code: "rain", route_ids: [10352], from_date: SEED_DATE, to_date: SEED_DATE, note: "Heavy rain", status: "pending", raised_at: asOf }], next_cursor: null } satisfies Schemas["DayExceptionPage"]), true;
  if (path === "/v1/team/locations") {
    const items: Schemas["TeamLocation"][] = routesInScope(user.scope.nodes).filter((r) => r.user_id && r.state !== "not_started").map((r) => ({ user_id: r.user_id!, full_name: r.user_name ?? "", route_ids: [r.route_id], last_fix: { lat: 23.79 + r.route_id / 1e7, lng: 90.4, accuracy_m: 20, at: asOf, source: "visit", age_min: 12 } }));
    return send(200, { as_of: asOf, items } satisfies Schemas["TeamLocationList"]), true;
  }

  const geoM = /^\/v1\/admin\/geo\/(wing|division|territory|zone)$/.exec(path);
  if (geoM && method === "GET") {
    const reach = routesInScope(user.scope.nodes);
    const ok = (n: (typeof GEO)[number]): boolean => reach.some((r) => (n.level === "wing" ? r.wing_id === n.id : n.level === "division" ? r.division_id === n.id : n.level === "territory" ? r.territory_id === n.id : r.zone_id === n.id));
    const parent = x.url.searchParams.get("parent_id");
    const items = GEO.filter((n) => n.level === geoM[1] && ok(n) && (!parent || n.parent_id === Number(parent))).map((n) => ({ id: n.id, level: n.level, code: `${n.level}-${n.id}`, name: n.name, parent_id: n.parent_id, status: "active" }));
    return send(200, { items, next_cursor: null }), true;
  }
  if (path === "/v1/admin/routes" && method === "GET") {
    const zone = x.url.searchParams.get("zone_id");
    const rows = routesInScope(user.scope.nodes).filter((r) => !zone || r.zone_id === Number(zone));
    const withUsers = x.url.searchParams.get("include") === "assignees";
    return send(200, { items: rows.map((r) => ({ id: r.route_id, code: String(r.route_id), name: r.route_name, display_label: r.route_name, zone_id: r.zone_id, kind: r.kind, visit_kind: "daily", visit_days_mask: 127, status: "active", ...(withUsers ? { assignees: r.user_id ? [{ user_id: r.user_id, full_name: r.user_name ?? `User ${r.user_id}`, role: r.kind === "amo" ? "AMO" : "SR", username: `u${r.user_id}` }] : [] } : {}) })), next_cursor: null }), true;
  }
  if (path === "/v1/admin/route-assignments" && method === "GET") {
    const items = routesInScope(user.scope.nodes).filter((r) => r.user_id).map((r, i) => ({ id: 700 + i, route_id: r.route_id, user_id: r.user_id!, kind: "primary", valid_from: "2026-10-01", valid_to: null, reason: null, created_at: asOf }));
    return send(200, { items, next_cursor: null }), true;
  }
  const pn = /^\/v1\/admin\/product-nodes\/(category|segment|brand|variant)$/.exec(path);
  if (pn && method === "GET") {
    const parent = x.url.searchParams.get("parent_id");
    return send(200, { items: PRODUCT_NODES.filter((n) => n.level === pn[1] && (!parent || n.parent_id === Number(parent))), next_cursor: null }), true;
  }
  if (path === "/v1/admin/outlets" && method === "GET") {
    const zone = x.url.searchParams.get("zone_id");
    const reach = new Map(routesInScope(user.scope.nodes).map((r) => [r.route_id, r]));
    const items = OUTLETS.filter((o) => reach.has(o.route_id) && (!zone || reach.get(o.route_id)!.zone_id === Number(zone))).map((o) => ({ id: o.outlet_id, code: o.code, name: o.name, owner_name: hasPii(user.role) ? o.owner_name : "", zone_id: reach.get(o.route_id)!.zone_id, route_id: o.route_id, cluster_id: 1, channel: "GT", location_confirmed: o.lat !== null, outlet_kind: "retail", price_type: "retail", status: o.status, lat: o.lat, lng: o.lng }));
    return send(200, { items, next_cursor: null }), true;
  }
  if (path.startsWith("/v1/admin/config/") || path === "/v1/admin/devices") {
    if (!ADMIN_READ.includes(user.role)) return send(403, problem(403, "ERR_FORBIDDEN")), true;
    if (path === "/v1/admin/config/versions") return send(200, { items: [{ version: 318, kind: "change", committed_at: asOf, committed_by: 3001, summary: "Seeded", max_risk_class: 1 }], next_cursor: null }), true;
    if (/^\/v1\/admin\/config\/reach\/\d+$/.test(path)) return send(200, { version: 318, committed_at: asOf, devices_targeted: 8, devices_applied: 8, devices_acked: 6, devices_pending: 2, p95_reach_min: 14, by_zone: [] }), true;
    if (path === "/v1/admin/devices") {
      const items = routesInScope(user.scope.nodes).filter((r) => r.user_id).map((r, i) => ({ device_id: 500 + i, device_uuid: randomUUID(), flavour: "sr", status: "active", device_owner: true, lockdown_level: 1, trust_level: "normal", enrolled_at: asOf, device_info: null, app_version: "1.0.0", bound_users: [], last_status: { pending_media: r.offline_memos, pending_rows: r.offline_memos } }));
      return send(200, { items, next_cursor: null }), true;
    }
  }
  if (path === "/v1/admin/skus" && method === "GET") return send(200, { items: SKUS.map((s) => ({ id: s.id, code: s.code, variant_id: 61, category_code: "cigarette", name: s.name, short_name: s.name, base_unit: "stick", base_per_pack: 20, entry_unit_default: "stick", report_factor: "1.000", sort: s.id, status: s.status })), next_cursor: null }), true;

  if (path === "/v1/auth/change-password" && method === "POST") {
    const b = (await x.body()) as Schemas["ChangePasswordRequest"] | undefined;
    if (!b || typeof b.current_password !== "string" || typeof b.new_password !== "string") return send(400, problem(400, "ERR_VALIDATION")), true;
    if (b.current_password !== user.password) return send(400, problem(400, "ERR_VALIDATION", { errors: [{ pointer: "/current_password", code: "invalid" }] })), true;
    if (b.new_password.length < 8) return send(400, problem(400, "ERR_VALIDATION", { errors: [{ pointer: "/new_password", code: "too_short" }] })), true;
    store.passwords[user.id] = b.new_password;
    return x.raw(204, "application/json", ""), true;
  }
  return false;
}
