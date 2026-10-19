// The sidebar tree of the existing Aron dashboard (PARITY-SPEC s4, owner reference): Dashboard, Retailer, Products, Route Planning,
// Reports and Outlet as expandable parents, then Credentials, Tutorial, Performance Leaderboard and Daily Tracking Dashboard as
// single entries. Entries the old portal did not have (Sync health, Exceptions, Final Submit, Leave approval ...) sit under the
// parent they belong to. Administration and Configuration stay their own parents after the owner's list. This only arranges
// the role-filtered items from `menuFor`; which items a role sees is still decided there. Target, Astha and Superstar stay out (docs/27).
import type { MessageKey } from "@/lib/i18n";
import type { MenuItem, MenuSection } from "./menu";

export interface NavNode {
  id: string;
  /** Absent for a single entry (rendered as a plain link, not a collapsible parent). */
  labelKey?: MessageKey;
  items: MenuItem[];
}

type Parent = "retailer" | "products" | "route" | "reports" | "outlet";
const TOP: readonly string[] = ["dashboard", "credentials", "tutorial", "report-leaderboard", "daily-tracking"];
const PARENT_OF: Record<string, Parent> = {
  "report-retailers": "retailer", "admin-outlet-requests": "retailer", "admin-outlet-change": "retailer", "admin-wholesale": "retailer",
  "report-skus": "products", "products-tree": "products", "products-category": "products", "products-segment": "products", "products-brand": "products", "products-variant": "products",
  routes: "route", leave: "route", "report-task-planner": "route", "report-route-std": "route", "report-geo-capture": "route",
  "report-by-outlet": "outlet", "report-by-outlet-by-day": "outlet", "report-sr-outlets": "outlet", "admin-radius": "outlet", "report-geofence-calibration": "outlet",
};
const PARENT_LABEL: Record<Parent, MessageKey> = { retailer: "nav.retailer", products: "nav.products", route: "nav.route_planning", reports: "nav.reports", outlet: "nav.outlet" };
const PARENT_ORDER: readonly Parent[] = ["retailer", "products", "route", "reports", "outlet"];

export function arrangeNav(sections: readonly MenuSection[]): NavNode[] {
  const items = sections.flatMap((s) => s.items.map((i) => ({ i, group: s.group })));
  const buckets = new Map<Parent, MenuItem[]>(PARENT_ORDER.map((p) => [p, []]));
  const extra = new Map<string, { labelKey: MessageKey; items: MenuItem[] }>();
  for (const { i, group } of items) {
    if (TOP.includes(i.id)) continue;
    if (group === "admin" || group === "config") {
      const e = extra.get(group) ?? { labelKey: sections.find((s) => s.group === group)!.labelKey, items: [] };
      e.items.push(i);
      extra.set(group, e);
      continue;
    }
    // Everything the owner's tree does not name (new pages, the other reports) belongs to Reports.
    buckets.get(PARENT_OF[i.id] ?? "reports")!.push(i);
  }
  const byId = new Map(items.map(({ i }) => [i.id, i]));
  const single = (id: string): NavNode[] => (byId.has(id) ? [{ id, items: [byId.get(id)!] }] : []);
  const parents = PARENT_ORDER.flatMap((p): NavNode[] => (buckets.get(p)!.length ? [{ id: p, labelKey: PARENT_LABEL[p], items: buckets.get(p)! }] : []));
  // Owner order: Dashboard, Retailer, Products, Route Planning, Reports, Outlet, Credentials, Tutorial, Performance Leaderboard, Daily Tracking.
  const nodes = [...single("dashboard"), ...parents, ...single("credentials"), ...single("tutorial"), ...single("report-leaderboard"), ...single("daily-tracking")];
  for (const [id, e] of extra) nodes.push({ id, labelKey: e.labelKey, items: e.items });
  return nodes;
}
