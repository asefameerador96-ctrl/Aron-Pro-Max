// Typed reads of the dashboard endpoints (contract v1.1). Server only. One place, so pages stay thin and tests can stub the API.
import type { Schemas } from "@/contract/types";
import { rawRequest } from "@/lib/api/raw";
import type { ApiOutcome } from "@/lib/api/client";

export type Summary = Schemas["DashboardSummary"];
export type Kpis = Schemas["DashboardKpis"];

type Q = Record<string, string | number | undefined>;
const get = <T>(token: string, path: string, query?: Q): Promise<ApiOutcome<T>> => rawRequest<T>({ method: "GET", path, token, query });

export const getSummary = (token: string, q: Q = {}) => get<Summary>(token, "/v1/dashboards/summary", q);
export const getLoginSubmit = (token: string, date: string) => get<Schemas["LoginSubmitStatus"]>(token, "/v1/dashboards/login-submit", { business_date: date });
export const getGeoValidation = (token: string, q: Q = {}) => get<Schemas["GeoValidationSummary"]>(token, "/v1/dashboards/geo-validation", q);
export const getTeamLocations = (token: string, date: string) => get<Schemas["TeamLocationList"]>(token, "/v1/team/locations", { business_date: date });
export const getDailyTracking = (token: string, date: string) => get<Schemas["DailyTrackingPage"]>(token, "/v1/dashboards/daily-tracking", { business_date: date, limit: 500 });
export const getSyncHealth = (token: string, date: string, q: Q = {}) => get<Schemas["SyncHealthPage"]>(token, "/v1/dashboards/sync-health", { business_date: date, limit: 500, ...q });
export const listLeave = (token: string, status?: string) => get<Schemas["LeavePage"]>(token, "/v1/leave", { status, limit: 200 });
export const listTutorials = (token: string) => get<Schemas["TutorialList"]>(token, "/v1/tutorials");
export const listRiskSignals = (token: string, q: Q = {}) => get<Schemas["RiskSignalPage"]>(token, "/v1/risk-signals", { limit: 200, ...q });
export const listDayExceptions = (token: string, q: Q = {}) => get<Schemas["DayExceptionPage"]>(token, "/v1/day/exceptions", { limit: 200, ...q });
export const listRoutes = (token: string, q: Q = {}) => get<Schemas["RoutePage"]>(token, "/v1/admin/routes", { limit: 500, ...q });
export const listAssignments = (token: string, date: string) => get<Schemas["RouteAssignmentPage"]>(token, "/v1/admin/route-assignments", { valid_on: date, limit: 500 });
export const listProductNodes = (token: string, level: string, q: Q = {}) => get<Schemas["ProductNodePage"]>(token, `/v1/admin/product-nodes/${level}`, { limit: 500, ...q });
export const listSkus = (token: string) => get<Schemas["SkuPage"]>(token, "/v1/admin/skus", { limit: 500 });

/** The calendar day before a business date (YYYY-MM-DD), by UTC arithmetic: Dhaka has no DST. */
export function previousDate(ymd: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(ymd) || Number.isNaN(Date.parse(`${ymd}T00:00:00Z`))) throw new RangeError("not a date");
  const d = new Date(`${ymd}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() - 1);
  return d.toISOString().slice(0, 10);
}

/** Dhaka wall-clock hour of an instant (0 to 23). */
export function dhakaHour(now: Date = new Date()): number {
  return Number(new Intl.DateTimeFormat("en-GB", { timeZone: "Asia/Dhaka", hour: "2-digit", hourCycle: "h23" }).format(now));
}

export const listOutlets = (token: string, q: Q = {}) => get<{ items: Schemas["Outlet"][] }>(token, "/v1/admin/outlets", { limit: 200, ...q });

/** Config acknowledgement of the newest committed config version (needs the admin read permission; null when not allowed). */
export async function getConfigAck(token: string): Promise<{ version: number; acked: number; targeted: number; pct: number | null } | null> {
  const v = await get<Schemas["ConfigVersionPage"]>(token, "/v1/admin/config/versions", { limit: 1 });
  const latest = v.ok ? v.data.items[0] : undefined;
  if (!latest) return null;
  const r = await get<Schemas["ConfigReach"]>(token, `/v1/admin/config/reach/${latest.version}`);
  if (!r.ok) return null;
  const { devices_acked: acked, devices_targeted: targeted } = r.data;
  return { version: latest.version, acked, targeted, pct: targeted === 0 ? null : Math.round((acked / targeted) * 1000) / 10 };
}

/** Photos still on phones, summed from the device-reported `pending_media` (admin read permission; null when not allowed). */
export async function getPendingPhotos(token: string): Promise<number | null> {
  const r = await get<Schemas["DevicePage"]>(token, "/v1/admin/devices", { limit: 500 });
  return r.ok ? r.data.items.reduce((a, d) => a + (d.last_status?.pending_media ?? 0), 0) : null;
}
