// Mock of GET /v1/dashboards/flagged-memos (contract 1.8.0, FlaggedMemoPage). The real endpoint is backend-core's; this serves a fixed
// seed of flagged memos with the same scope, date, flag and paging rules so the page and its tests run without it. Kept in its own file
// (web-admin) and registered with one line in mock/dash.ts.
import type { Schemas } from "../src/contract/types";
import type { DashCtx } from "./dash";
import { ROUTES, SEED_DATE } from "./seed";

type Memo = Schemas["FlaggedMemoView"];
const U = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
const base = (n: number, over: Partial<Memo>): Memo => ({
  memo_client_uuid: U(n), memo_no: `sr1001-261007-${1000 + n}`, business_date: SEED_DATE, status: "active", user_id: 1001, user_code: "sr1001", user_name: "Testing Banani", outlet_id: 5000 + n, outlet_name: `Banani Store ${n}`,
  route_id: 10231, zone_id: 3341, territory_id: 334, division_id: 10, wing_id: 1, gross_mtk: 7_935_000, flags: ["zero_price_line"], stored_at: `${SEED_DATE}T08:${String(n % 60).padStart(2, "0")}:00.000Z`, voided_at: null, ...over,
});

// Rows 1 to 6 cover the states the page draws; rows 100 and up are 55 plain price mismatches of one day (two pages of 50).
const SEED: Memo[] = [
  base(1, {}),
  base(2, { route_id: 10232, user_id: 1002, user_code: "sr1002", user_name: "Kamal Hossain", flags: ["price_mismatch", "arithmetic_mismatch"], gross_mtk: 215_400_000 }),
  base(3, { route_id: 10233, zone_id: 3342, user_id: 1003, user_code: "sr1003", user_name: "Jamal Uddin", status: "void", flags: ["pack_factor_mismatch"], voided_at: `${SEED_DATE}T09:30:00.000Z` }),
  base(4, { route_id: null, zone_id: 3342, user_id: 1003, user_code: "sr1003", user_name: "Jamal Uddin", flags: ["price_mismatch"], outlet_name: "Outlet without a route" }),
  base(5, { route_id: 10351, zone_id: 3351, territory_id: 335, user_id: 1005, user_code: "sr1005", user_name: "Selim Reza", flags: ["future_check_x"], outlet_name: "Gulshan Store" }),
  base(6, { status: "superseded", flags: ["arithmetic_mismatch"], gross_mtk: 3_200_000 }),
  ...Array.from({ length: 55 }, (_, i) => base(100 + i, { business_date: "2026-10-05", route_id: 10351, zone_id: 3351, territory_id: 335, user_id: 1005, user_code: "sr1005", user_name: "Selim Reza", flags: ["price_mismatch"], gross_mtk: 1_000_000 + i, stored_at: `2026-10-05T10:${String(i).padStart(2, "0")}:00.000Z` })),
];

// `from` and `to` default to today (contract); the seed day is 2026-10-07, so a test names its dates.
const SEED_TODAY = new Date().toISOString().slice(0, 10);
const daysBetween = (a: string, b: string) => Math.round((Date.parse(`${b}T00:00:00Z`) - Date.parse(`${a}T00:00:00Z`)) / 86_400_000);
const idAt = (m: Memo, level: string): number | null => ({ wing: m.wing_id, division: m.division_id, territory: m.territory_id, zone: m.zone_id, route: m.route_id })[level as "wing"] ?? null;
const inReach = (nodes: { type: string; id: number }[], m: Memo) => nodes.some((n) => n.type === "national" || idAt(m, n.type) === n.id);

export function handleFlagged(x: DashCtx): boolean {
  if (x.path !== "/v1/dashboards/flagged-memos" || x.method !== "GET") return false;
  const { user, send, problem, url } = x;
  if (user.role === "BRAND") return send(403, problem(403, "ERR_FORBIDDEN")), true;
  const q = url.searchParams;
  const from = q.get("from") ?? SEED_TODAY;
  const to = q.get("to") ?? SEED_TODAY;
  if (daysBetween(from, to) < 0 || daysBetween(from, to) > 92) return send(400, problem(400, "ERR_VALIDATION", { errors: [{ pointer: "query.to", code: "invalid_value" }] })), true;
  const level = q.get("level");
  const nodeId = Number(q.get("node_id"));
  let rows = SEED.filter((m) => inReach(user.scope.nodes, m) && m.business_date >= from && m.business_date <= to);
  if (level && level !== "national" && nodeId) {
    // A node outside the caller's reach is refused whether or not a memo sits in it; inside the reach it narrows the list (possibly to nothing).
    const known = ROUTES.filter((r) => ({ wing: r.wing_id, division: r.division_id, territory: r.territory_id, zone: r.zone_id, route: r.route_id })[level as "wing"] === nodeId);
    const reached = user.scope.nodes.some((n) => n.type === "national" || (n.type === level && n.id === nodeId)) || known.some((r) => inReach(user.scope.nodes, { wing_id: r.wing_id, division_id: r.division_id, territory_id: r.territory_id, zone_id: r.zone_id, route_id: r.route_id } as Memo));
    if (!reached) return send(403, problem(403, "ERR_OUT_OF_SCOPE")), true;
    rows = rows.filter((m) => idAt(m, level) === nodeId);
  }
  const flag = q.get("flag");
  if (flag) rows = rows.filter((m) => m.flags.includes(flag));
  rows = [...rows].sort((a, b) => (a.stored_at === b.stored_at ? a.memo_client_uuid.localeCompare(b.memo_client_uuid) : b.stored_at.localeCompare(a.stored_at)));
  const limit = Math.min(Math.max(Number(q.get("limit")) || 50, 1), 200);
  const offset = Number(Buffer.from(q.get("cursor") ?? "MA", "base64url").toString()) || 0;
  const page = rows.slice(offset, offset + limit);
  const next = offset + limit < rows.length ? Buffer.from(String(offset + limit)).toString("base64url") : null;
  const body: Schemas["FlaggedMemoPage"] = { items: page, next_cursor: next };
  return send(200, body), true;
}
