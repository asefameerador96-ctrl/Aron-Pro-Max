// Seeded business day for the web dashboard tests: one wing, one division, two territories (334 Banani, 335 Gulshan), six routes.
// Every report in the mock is a projection of these rows, so a test can assert the control totals of the seeded day.
export const SEED_DATE = "2026-10-07";

export interface SeedRoute {
  route_id: number;
  route_name: string;
  kind: "sr" | "amo";
  zone_id: number;
  territory_id: number;
  division_id: number;
  wing_id: number;
  user_id: number | null;
  user_name: string | null;
  target_outlets: number;
  visited: number;
  successful: number;
  memos: number;
  gross_mtk: number;
  net_mtk: number;
  geo_valid: number;
  force_sale: number;
  mock_visits: number;
  suspicious: number;
  state: "not_started" | "logged_in" | "in_field" | "sales_submitted" | "final_submitted";
  exception: boolean;
  offline_memos: number;
  free_qty: number;
  logged_in_at: string | null;
  submitted_at: string | null;
}

const r = (route_id: number, route_name: string, zone_id: number, territory_id: number, user_id: number | null, user_name: string | null, o: Partial<SeedRoute>): SeedRoute => ({
  route_id, route_name, kind: "sr", zone_id, territory_id, division_id: 10, wing_id: 1, user_id, user_name,
  target_outlets: 43, visited: 43, successful: 1, memos: 1, gross_mtk: 7_935_000, net_mtk: 7_935_000, geo_valid: 40, force_sale: 3, mock_visits: 0, suspicious: 0,
  state: "final_submitted", exception: false, offline_memos: 0, free_qty: 0, logged_in_at: "2026-10-07T02:10:00.000Z", submitted_at: "2026-10-07T10:40:00.000Z", ...o,
});

export const ROUTES: SeedRoute[] = [
  r(10231, "RouteDaily", 3341, 334, 1001, "Testing Banani", { free_qty: 12 }),
  r(10232, "Banani North", 3341, 334, 1002, "Kamal Hossain", { target_outlets: 40, visited: 35, successful: 14, memos: 14, gross_mtk: 215_400_000, net_mtk: 210_000_000, geo_valid: 33, force_sale: 2, offline_memos: 5, state: "sales_submitted", submitted_at: "2026-10-07T10:20:00.000Z" }),
  r(10233, "Banani South", 3342, 334, 1003, "Jamal Uddin", { target_outlets: 38, visited: 30, successful: 22, memos: 22, gross_mtk: 330_000_000, net_mtk: 325_500_000, geo_valid: 25, force_sale: 5, mock_visits: 2, suspicious: 1, state: "in_field", offline_memos: 9, submitted_at: null }),
  r(10234, "Banani AMO", 3342, 334, 1004, "Rafiq Amin", { kind: "amo", target_outlets: 20, visited: 0, successful: 0, memos: 0, gross_mtk: 0, net_mtk: 0, geo_valid: 0, force_sale: 0, state: "not_started", logged_in_at: null, submitted_at: null }),
  r(10351, "Gulshan East", 3351, 335, 1005, "Selim Reza", { target_outlets: 45, visited: 40, successful: 30, memos: 30, gross_mtk: 480_000_000, net_mtk: 470_000_000, geo_valid: 38, force_sale: 2 }),
  r(10352, "Gulshan West", 3351, 335, 1006, "Tariq Aziz", { target_outlets: 42, visited: 20, successful: 9, memos: 9, gross_mtk: 99_000_000, net_mtk: 99_000_000, geo_valid: 18, force_sale: 2, exception: true, state: "logged_in", submitted_at: null }),
];

export interface SeedOutlet {
  outlet_id: number;
  code: string;
  name: string;
  owner_name: string;
  phone: string;
  route_id: number;
  territory_id: number;
  status: "active" | "inactive";
  sub_channel: string;
  lat: number | null;
  lng: number | null;
}
export const OUTLETS: SeedOutlet[] = ROUTES.flatMap((rt) =>
  [1, 2].map((n, i): SeedOutlet => ({
    outlet_id: rt.route_id * 10 + n,
    code: `O-${rt.route_id}-${n}`,
    name: `Outlet ${rt.route_name} ${n}`,
    owner_name: `Owner ${rt.route_id}${n}`,
    phone: `0171${rt.route_id}${n}00`.slice(0, 11),
    route_id: rt.route_id,
    territory_id: rt.territory_id,
    status: i === 1 && rt.route_id === 10233 ? "inactive" : "active",
    sub_channel: n === 1 ? "Grocery" : "Pan-cigarette",
    lat: n === 1 ? 23.7937 + rt.route_id / 1e6 : null,
    lng: n === 1 ? 90.4066 : null,
  })),
);

export interface SeedSku { id: number; code: string; name: string; retail_mtk: number; trade_mtk: number; status: "active" | "inactive"; category_id: number }
export const SKUS: SeedSku[] = [
  { id: 1, code: "SKU-001", name: "Sample King 20s", retail_mtk: 7_935, trade_mtk: 7_500, status: "active", category_id: 1 },
  { id: 2, code: "SKU-002", name: "Sample Lights 10s", retail_mtk: 4_450, trade_mtk: 4_200, status: "active", category_id: 1 },
  { id: 3, code: "SKU-003", name: "Sample Bidi 25s", retail_mtk: 1_200, trade_mtk: 1_100, status: "inactive", category_id: 2 },
];

export interface SeedNode { id: number; level: "wing" | "division" | "territory" | "zone"; name: string; parent_id: number | null }
export const GEO: SeedNode[] = [
  { id: 1, level: "wing", name: "Dhaka Wing", parent_id: null },
  { id: 10, level: "division", name: "Dhaka North", parent_id: 1 },
  { id: 334, level: "territory", name: "Banani", parent_id: 10 },
  { id: 335, level: "territory", name: "Gulshan", parent_id: 10 },
  { id: 3341, level: "zone", name: "Banani Zone 1", parent_id: 334 },
  { id: 3342, level: "zone", name: "Banani Zone 2", parent_id: 334 },
  { id: 3351, level: "zone", name: "Gulshan Zone 1", parent_id: 335 },
];

const BRAND_NAMES = ["Sample", "Basic", "Gold Leaf", "Navy", "Star", "Royal", "Classic", "Premier", "Dhaka", "Padma", "Meghna", "Jamuna", "Surma", "Karnaphuli", "Shapla", "Rupsha", "Teesta"];
export const PRODUCT_NODES: { id: number; level: "category" | "segment" | "brand" | "variant"; parent_id: number | null; name: string; sort: number; status: "active" | "inactive" }[] = [
  { id: 1, level: "category", parent_id: null, name: "Cigarette", sort: 1, status: "active" },
  { id: 2, level: "category", parent_id: null, name: "Bidi", sort: 2, status: "active" },
  { id: 11, level: "segment", parent_id: 1, name: "Premium", sort: 1, status: "active" },
  { id: 12, level: "segment", parent_id: 1, name: "Low", sort: 2, status: "active" },
  ...BRAND_NAMES.map((name, i) => ({ id: 21 + i, level: "brand" as const, parent_id: i % 2 === 0 ? 11 : 12, name, sort: i + 1, status: i === 1 ? ("inactive" as const) : ("active" as const) })),
  { id: 61, level: "variant", parent_id: 21, name: "King Size", sort: 1, status: "active" },
  { id: 62, level: "variant", parent_id: 21, name: "Lights", sort: 2, status: "active" },
];

/** Scope filter for rows: which seeded routes a set of scope nodes reaches. Server-side only, like the real API. */
export function routesInScope(nodes: { type: string; id: number }[]): SeedRoute[] {
  return ROUTES.filter((rt) =>
    nodes.some((n) => n.type === "national" || (n.type === "wing" && n.id === rt.wing_id) || (n.type === "division" && n.id === rt.division_id) || (n.type === "territory" && n.id === rt.territory_id) || (n.type === "zone" && n.id === rt.zone_id) || (n.type === "route" && n.id === rt.route_id)),
  );
}

export function sum<T>(rows: readonly T[], f: (x: T) => number): number {
  return rows.reduce((a, x) => a + f(x), 0);
}
export const pct = (n: number, d: number): number | null => (d === 0 ? null : Math.round((n / d) * 10_000) / 100);

/** The control totals of the seeded day for a scope (what every report in the acceptance tests must add up to). */
export function controlTotals(rows: readonly SeedRoute[]) {
  return {
    routes: rows.length,
    target_outlets: sum(rows, (x) => x.target_outlets),
    visited: sum(rows, (x) => x.visited),
    successful: sum(rows, (x) => x.successful),
    memos: sum(rows, (x) => x.memos),
    net_mtk: sum(rows, (x) => x.net_mtk),
  };
}
