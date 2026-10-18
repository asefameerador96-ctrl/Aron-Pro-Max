// Pure row builders for the home extras and the drill-down tables (no I/O, so they are unit tested). Every figure comes from
// the stored aggregates the server already scoped by token; nothing is invented, and a missing denominator is null.
import type { Schemas } from "@/contract/types";

export type TrackingRow = Schemas["DailyTrackingRow"];
export type SummaryChild = Schemas["DashboardSummary"]["children"][number];
export type CategoryVolume = Schemas["CategoryVolume"];

export interface SrRow {
  key: string;
  sr: string | null;
  routes: string[];
  planned: number;
  visited: number;
  calls: number;
  memos: number;
  strike: number | null;
  net_mtk: number;
}

/** Strike rate as the dashboard defines it: successful calls over outlets visited. */
export const strikeOf = (calls: number, visited: number): number | null => (visited > 0 ? Math.round((calls / visited) * 1000) / 10 : null);

/** One row per SR (a route with no assigned user is its own row), best net sales first. */
export function srRows(items: readonly TrackingRow[]): SrRow[] {
  const by = new Map<string, SrRow>();
  for (const r of items) {
    const key = r.user_id != null ? `u${r.user_id}` : `r${r.route_id}`;
    const cur = by.get(key) ?? { key, sr: r.user_name ?? null, routes: [], planned: 0, visited: 0, calls: 0, memos: 0, strike: null, net_mtk: 0 };
    cur.routes.push(r.route_name);
    cur.planned += r.target_outlets;
    cur.visited += r.visited_outlets;
    cur.calls += r.successful_calls;
    cur.memos += r.active_memo_count;
    cur.net_mtk += r.net_mtk;
    by.set(key, cur);
  }
  return [...by.values()].map((r) => ({ ...r, strike: strikeOf(r.calls, r.visited) })).sort((a, b) => b.net_mtk - a.net_mtk || (a.sr ?? "").localeCompare(b.sr ?? ""));
}

export interface RankRow {
  key: string;
  name: string;
  calls: number;
  memos: number;
  strike: number | null;
  net_mtk: number;
}

/** TSO / zone ranking from the summary children, best net sales first. */
export function rankRows(children: readonly SummaryChild[]): RankRow[] {
  return children
    .map((c) => ({ key: `${c.node.type}-${c.node.id}`, name: c.node.name ?? c.node.code ?? String(c.node.id), calls: c.kpis.successful_calls, memos: c.kpis.active_memo_count, strike: c.kpis.strike_rate_pct, net_mtk: c.kpis.net_mtk }))
    .sort((a, b) => b.net_mtk - a.net_mtk || a.name.localeCompare(b.name));
}

export interface RouteCatRow {
  key: string;
  route: string;
  sr: string | null;
  qty: number;
  unit: CategoryVolume["base_unit"] | null;
  net_mtk: number;
}

/** Routes that sold a category, with the quantity in the category's own unit; routes with no sale of it are left out. */
export function categoryRoutes(items: readonly TrackingRow[], code: string): RouteCatRow[] {
  const out: RouteCatRow[] = [];
  for (const r of items) {
    const c = r.by_category?.find((x) => x.category_code === code);
    if (c && (c.qty_base !== 0 || c.net_mtk !== 0)) out.push({ key: String(r.route_id), route: r.route_name, sr: r.user_name ?? null, qty: c.qty_base, unit: c.base_unit, net_mtk: c.net_mtk });
  }
  return out.sort((a, b) => b.net_mtk - a.net_mtk || a.route.localeCompare(b.route));
}

export interface CallsRow {
  key: string;
  route: string;
  sr: string | null;
  planned: number;
  visited: number;
  calls: number;
  cpr: number | null;
  strike: number | null;
}

/** Calls by route: CPR is calls over planned outlets, strike is calls over visited outlets. */
export function callsRows(items: readonly TrackingRow[]): CallsRow[] {
  return items
    .map((r) => ({ key: String(r.route_id), route: r.route_name, sr: r.user_name ?? null, planned: r.target_outlets, visited: r.visited_outlets, calls: r.successful_calls, cpr: r.target_outlets > 0 ? Math.round((r.successful_calls / r.target_outlets) * 1000) / 10 : null, strike: strikeOf(r.successful_calls, r.visited_outlets) }))
    .sort((a, b) => b.calls - a.calls || a.route.localeCompare(b.route));
}
