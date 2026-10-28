// tso-manual W1, DASH-13 (D-TSO-MAN-12, contract 1.10.0): the rows of the Not Logged In / Not Uploaded drill-down, read from
// POST /v1/dashboards/national/login-submit/users?tab=... . One row per (route, user): the route-day SR first ("SR Not Set" when
// the route is vacant), then each AMO whose scope covers the route's zone; Dep Name is the zone. The server derives the scope from
// the token; the filter only narrows inside it (the page never sends a scope of its own). Server only.
import type { Schemas } from "@/contract/types";
import type { ApiOutcome } from "@/lib/api/client";
import { rawRequest } from "@/lib/api/raw";
import { formatNumber, t, type Locale } from "@/lib/i18n";
import type { Col, TRow } from "@/components/dash/data-table";

export const LOGIN_TABS = ["not_logged_in", "not_uploaded"] as const;
export type LoginTab = (typeof LOGIN_TABS)[number];
export type LoginUsers = Schemas["NationalLoginSubmitUsers"];
export type LoginUserRow = Schemas["LoginSubmitUserRow"];

/** The geo levels the drill page carries in its URL (route is narrowed here, the national filter has no route ids). */
type Picked = Partial<Record<"wing" | "division" | "territory" | "zone" | "route", number[]>>;

/** The picked levels as the contract's NationalDashboardFilter (the business date plus the ids; route stays out). */
export function loginUsersFilter(picked: Picked, date: string): Schemas["NationalDashboardFilter"] {
  return {
    date,
    ...(picked.wing?.length ? { wing_ids: picked.wing } : {}),
    ...(picked.division?.length ? { division_ids: picked.division } : {}),
    ...(picked.territory?.length ? { territory_ids: picked.territory } : {}),
    ...(picked.zone?.length ? { zone_ids: picked.zone } : {}),
  };
}

export function getLoginSubmitUsers(token: string, filter: Schemas["NationalDashboardFilter"], tab: LoginTab): Promise<ApiOutcome<LoginUsers>> {
  return rawRequest<LoginUsers>({ method: "POST", path: "/v1/dashboards/national/login-submit/users", token, query: { tab }, body: filter });
}

/** Columns in the order of the app's card (screenshot 8): name, User Name, Route, Dep Name, then the role. */
export function loginUserColumns(locale: Locale): Col[] {
  return [
    { header: t(locale, "table.col.name") },
    { header: t(locale, "tsoman.drill.col.username") },
    { header: t(locale, "table.col.route") },
    { header: t(locale, "tsoman.drill.col.dep_name") },
    { header: t(locale, "tsoman.drill.col.role") },
  ];
}

/** Rows of one tab, in the server's order. `routeId` narrows to one route when the drill was opened for a route. A vacant
 *  route's SR row has no user: its name is "SR Not Set" and its User Name a dash. */
export function loginUserRows(locale: Locale, items: readonly LoginUserRow[], routeId?: number): TRow[] {
  return items
    .filter((r) => routeId === undefined || r.route_id === routeId)
    .map((r) => {
      const vacant = r.user_id === null;
      const name = vacant ? t(locale, "fs.sr_not_set") : (r.display_name ?? r.username ?? "—");
      return {
        key: `${r.route_id}:${r.role}:${r.user_id ?? "none"}`,
        cells: [name, r.username ?? "—", r.route_name || r.route_code, r.dep_name, t(locale, r.role === "AMO" ? "tsoman.role.AMO" : "tsoman.role.SR")],
      };
    });
}

/** The caption under a tab: how many routes are in it (every route, also when the rows were cut). */
export function routeCountText(locale: Locale, data: LoginUsers): string {
  return t(locale, data.tab === "not_logged_in" ? "tsoman.drill.routes_not_logged_in" : "tsoman.drill.routes_not_uploaded", { n: formatNumber(locale, data.route_count) });
}

/** The "list cut" note (F1 ask 11): shown only when the server left rows out; null otherwise. */
export function truncatedText(locale: Locale, data: LoginUsers): string | null {
  return data.truncated ? t(locale, "tsoman.drill.truncated", { shown: formatNumber(locale, data.items.length) }) : null;
}
