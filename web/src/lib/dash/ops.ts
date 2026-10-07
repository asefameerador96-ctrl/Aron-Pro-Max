// Pure helpers of the sync-health ops view (F-WEB-045): zone roll-ups of the day's route states and device latency figures.
import type { Schemas } from "@/contract/types";

type Row = Schemas["DailyTrackingRow"];
type Dev = Schemas["SyncHealthRow"];

const LOGGED_IN: readonly Row["state"][] = ["logged_in", "in_field", "synced", "submit_pending_rows", "sales_submitted", "final_submitted"];
const SUBMITTED: readonly Row["state"][] = ["sales_submitted", "final_submitted"];

export interface ZoneRollup {
  zone_id: number;
  routes: number;
  logged_in: number;
  submitted: number;
  final_submitted: number;
  login_pct: number | null;
  submit_pct: number | null;
  /** Every route of the zone is final-submitted. */
  done: boolean;
}

const pct = (n: number, d: number): number | null => (d === 0 ? null : Math.round((n / d) * 1000) / 10);

export function rollupByZone(rows: readonly Row[]): ZoneRollup[] {
  const by = new Map<number, Row[]>();
  for (const r of rows) by.set(r.zone_id, [...(by.get(r.zone_id) ?? []), r]);
  return [...by.entries()]
    .sort(([a], [b]) => a - b)
    .map(([zone_id, rs]) => {
      const logged = rs.filter((r) => LOGGED_IN.includes(r.state)).length;
      const submitted = rs.filter((r) => SUBMITTED.includes(r.state)).length;
      const fin = rs.filter((r) => r.state === "final_submitted").length;
      return { zone_id, routes: rs.length, logged_in: logged, submitted, final_submitted: fin, login_pct: pct(logged, rs.length), submit_pct: pct(submitted, logged), done: fin === rs.length };
    });
}

export function totalsOf(zs: readonly ZoneRollup[]): { login_pct: number | null; submit_pct: number | null; zones_done: number; zones: number } {
  const routes = zs.reduce((a, z) => a + z.routes, 0);
  const logged = zs.reduce((a, z) => a + z.logged_in, 0);
  const submitted = zs.reduce((a, z) => a + z.submitted, 0);
  return { login_pct: pct(logged, routes), submit_pct: pct(submitted, logged), zones_done: zs.filter((z) => z.done).length, zones: zs.length };
}

/** Trickle latency: the slowest and the median device p95 (seconds from capture to server) among devices that reported one. */
export function latency(devs: readonly Dev[]): { worst: number | null; median: number | null } {
  const v = devs.map((d) => d.sync_p95_s).filter((x): x is number => typeof x === "number").sort((a, b) => a - b);
  if (v.length === 0) return { worst: null, median: null };
  const mid = Math.floor(v.length / 2);
  return { worst: v[v.length - 1]!, median: v.length % 2 ? v[mid]! : (v[mid - 1]! + v[mid]!) / 2 };
}
