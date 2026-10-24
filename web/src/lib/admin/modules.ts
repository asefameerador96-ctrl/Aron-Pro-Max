// ADMIN module grants applied to the portal (D-WEB-ADMIN-MENU-01, docs/34 s1): the sidebar, the master-data hub and the write buttons
// mirror the `modules` claim of GET /v1/me. The server stays the authority (AdminModules.kt answers 403 for a route outside the
// grants); this file only hides what would be refused. Module names and the API route -> module map are the backend's
// (backend/platform/.../AdminModules.kt, ROUTES, first match wins): keep them identical.
import { cache } from "react";
import type { Role } from "@/contract/types";
import { rawRequest } from "@/lib/api/raw";
import type { GrantModule } from "./access-grants";

/** `{module: "r"|"w"}` an ADMIN holds; null = no module filter (any other role, SUPERADMIN, or grants not deployed yet). */
export type ModuleGrants = Partial<Record<GrantModule, "r" | "w">>;
export type ModuleAccess = "none" | "r" | "w";

/** The claim read for the signed-in user: only an ADMIN is judged by modules. */
export function grantsOf(role: Role, modules: Record<string, "r" | "w"> | null | undefined): ModuleGrants | null {
  return role === "ADMIN" && modules ? (modules as ModuleGrants) : null;
}

export function accessOf(grants: ModuleGrants | null, module: GrantModule | null): ModuleAccess {
  if (grants === null || module === null) return "w";
  return grants[module] ?? "none";
}
export const canSee = (grants: ModuleGrants | null, module: GrantModule | null): boolean => accessOf(grants, module) !== "none";
export const canWriteModule = (grants: ModuleGrants | null, module: GrantModule | null): boolean => accessOf(grants, module) === "w";

const compile = (pattern: string): RegExp => new RegExp(`^${pattern.replace(/[.+?^$()|[\]\\]/g, "\\$&").replace(/\{[^}/]+\}/g, "[^/]+").replace(/\/\*\*$/, "(?:/.*)?")}$`);

/** The backend's AdminModules.ROUTES (path patterns, most specific first). */
const API_ROUTES: readonly (readonly [string, GrantModule])[] = [
  ["/v1/admin/users/{id}/credentials/**", "credentials"], ["/v1/admin/users/{id}/mfa/**", "credentials"], ["/v1/admin/device-otps/**", "credentials"],
  ["/v1/admin/users/**", "users"], ["/v1/leave/**", "users"],
  ["/v1/admin/permissions/**", "config"], ["/v1/admin/config/**", "config"], ["/v1/config/**", "config"], ["/v1/admin/audit/**", "config"],
  ["/v1/admin/calendar/**", "masterdata"], ["/v1/admin/geo/**", "masterdata"], ["/v1/admin/clusters/**", "masterdata"], ["/v1/admin/code-lists/**", "masterdata"],
  ["/v1/admin/content/**", "masterdata"], ["/v1/admin/tutorials/**", "masterdata"], ["/v1/admin/surveys/**", "masterdata"], ["/v1/admin/rubrics/**", "masterdata"], ["/v1/admin/print-templates/**", "masterdata"],
  ["/v1/admin/routes/**", "routes"], ["/v1/admin/route-assignments/**", "routes"], ["/v1/routes/**", "routes"], ["/v1/visit-plans/**", "routes"],
  ["/v1/admin/outlets/**", "outlets"], ["/v1/outlets/**", "outlets"], ["/v1/outlet-requests/**", "outlets"],
  ["/v1/admin/skus/**", "products"], ["/v1/admin/product-nodes/**", "products"], ["/v1/admin/assets/**", "products"], ["/v1/sku-images/**", "products"],
  ["/v1/admin/prices/**", "prices"],
  ["/v1/admin/targets/**", "targets"], ["/v1/admin/route-targets/**", "targets"], ["/v1/admin/target-revisions/**", "targets"], ["/v1/admin/supervisor-targets/**", "targets"], ["/v1/admin/sales-plans/**", "targets"],
  ["/v1/admin/devices/**", "mdm"], ["/v1/admin/enrolment-tokens/**", "mdm"], ["/v1/admin/releases/**", "mdm"], ["/v1/admin/mdm/**", "mdm"], ["/v1/admin/notifications/**", "mdm"], ["/v1/support/**", "mdm"],
  ["/v1/admin/data-entry/**", "data_entry"], ["/v1/admin/data-void/**", "data_entry"], ["/v1/admin/entry-unlocks/**", "data_entry"], ["/v1/admin/dues-adjustments/**", "data_entry"], ["/v1/admin/quarantine/**", "data_entry"], ["/v1/web-entry/**", "data_entry"], ["/v1/day/**", "data_entry"],
  ["/v1/admin/indent/**", "indent"], ["/v1/indent/**", "indent"], ["/v1/leaderboard/**", "leaderboard"],
  ["/v1/dashboards/daily-tracking/**", "daily_tracking"], ["/v1/tasks/**", "daily_tracking"], ["/v1/risk-signals/**", "daily_tracking"],
  ["/v1/dashboards/**", "reports"], ["/v1/reports/**", "reports"], ["/v1/report-exports/**", "reports"], ["/v1/team/**", "reports"], ["/v1/memos/**", "reports"], ["/v1/feedback/**", "reports"], ["/v1/media/**", "reports"],
];
const API_COMPILED = API_ROUTES.map(([p, m]) => [compile(p), m] as const);

/** Module of an API path (what the entity tables call); null for a path no module owns. */
export function moduleOfApiPath(path: string): GrantModule | null {
  return API_COMPILED.find(([re]) => re.test(path))?.[1] ?? null;
}

/** Module of a portal page, longest prefix first. Pages of no module (the admin home, tutorial) stay visible. */
const PAGES: readonly (readonly [string, GrantModule])[] = [
  ["/admin/team", "credentials"], ["/admin/device-otps", "credentials"], ["/credentials", "credentials"],
  ["/admin/user-access", "users"], ["/admin/users", "users"], ["/admin/sr-lifecycle", "routes"], ["/admin/sr-transfer", "routes"], ["/admin/routes", "routes"], ["/admin/route-assignments", "routes"], ["/routes", "routes"],
  ["/admin/outlet-requests", "outlets"], ["/admin/outlet-change", "outlets"], ["/admin/outlet-import", "outlets"], ["/admin/outlets", "outlets"], ["/admin/wholesale-marking", "outlets"],
  ["/admin/prices", "prices"], ["/admin/products", "products"], ["/admin/skus", "products"], ["/admin/categories", "products"], ["/admin/segments", "products"], ["/admin/brands", "products"], ["/admin/variants", "products"], ["/products", "products"],
  ["/admin/route-targets", "targets"], ["/admin/supervisor-targets", "targets"], ["/admin/sales-plan", "targets"],
  ["/admin/mdm", "mdm"], ["/admin/devices", "mdm"], ["/admin/releases", "mdm"], ["/admin/enrolment", "mdm"],
  ["/admin/data-entry", "data_entry"], ["/admin/entry-unlocks", "data_entry"], ["/admin/dues", "data_entry"], ["/admin/quarantine", "data_entry"], ["/admin/config/day", "data_entry"], ["/entry", "data_entry"], ["/final-submit/submit", "data_entry"],
  ["/admin/config", "config"], ["/admin/permissions", "config"], ["/admin/audit", "config"],
  ["/admin/master-data", "masterdata"], ["/admin/calendar", "masterdata"], ["/admin/house-dedupe", "masterdata"], ["/admin/code-lists", "masterdata"], ["/admin/print-templates", "masterdata"], ["/admin/tutorials", "masterdata"], ["/admin/surveys", "masterdata"], ["/admin/rubrics", "masterdata"], ["/admin/content", "masterdata"], ["/admin/clusters", "masterdata"],
  ["/admin/export-log", "reports"], ["/reports", "reports"],
  ["/daily-tracking", "daily_tracking"], ["/tso-daily-tracking", "daily_tracking"], ["/leave", "users"],
  ["/", "reports"], ["/exceptions", "reports"], ["/flagged-memos", "reports"], ["/sync-health", "reports"], ["/final-submit", "reports"],
];
const PAGES_SORTED = [...PAGES].sort((a, b) => b[0].length - a[0].length);

export function moduleOfPage(href: string): GrantModule | null {
  const path = href.split("?")[0]!;
  for (const [prefix, m] of PAGES_SORTED) if (prefix === "/" ? path === "/" : path === prefix || path.startsWith(`${prefix}/`)) return m;
  return null;
}

/** The signed-in user's grants, read once per request. */
export const loadGrants = cache(async (token: string, role: Role): Promise<ModuleGrants | null> => {
  if (role !== "ADMIN") return null;
  const r = await rawRequest<{ modules?: Record<string, "r" | "w"> | null }>({ method: "GET", path: "/v1/me", token });
  return r.ok ? grantsOf(role, r.data.modules) : null;
});
