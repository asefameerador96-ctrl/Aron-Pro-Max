// The filter of the national dashboard as URL parameters: comma lists per level, `date`, and the breakdown setting. The page reads
// them on the server, so "cards refresh only on Apply Filter" is a plain form submit and every view is a shareable link.
import { isCalendarDate } from "@/lib/dates";
import type { BreakdownBy, BreakdownMeasure, NationalQuery } from "./types";

type Params = Record<string, string | string[] | undefined>;
const first = (v: string | string[] | undefined): string | undefined => (Array.isArray(v) ? v[0] : v);

const GEO_KEYS = ["wing", "division", "territory", "house", "zone"] as const;
const PRODUCT_KEYS = ["category", "segment", "brand", "variant"] as const;
export const FILTER_KEYS = [...GEO_KEYS, ...PRODUCT_KEYS] as const;

const ids = (v: string | undefined): number[] | undefined => {
  const out = (v ?? "").split(",").map(Number).filter((n) => Number.isSafeInteger(n) && n > 0);
  return out.length > 0 ? out : undefined;
};
const codes = (v: string | undefined): string[] | undefined => {
  const out = (v ?? "").split(",").filter((c) => /^[a-z0-9-]{1,40}$/.test(c));
  return out.length > 0 ? out : undefined;
};

export function parseQuery(sp: Params, today: string): NationalQuery {
  const date = first(sp.date);
  const q: NationalQuery = { date: isCalendarDate(date) && date <= today ? date : today };
  for (const k of GEO_KEYS) {
    const v = ids(first(sp[k]));
    if (v) q[k] = v;
  }
  for (const k of PRODUCT_KEYS) {
    const v = codes(first(sp[k]));
    if (v) q[k] = v;
  }
  return q;
}

/** Query string for the API (and, with the same members, for the page link). Members equal to "everything" are simply left out. */
export function toSearch(q: NationalQuery, extra: Record<string, string | undefined> = {}): URLSearchParams {
  const p = new URLSearchParams();
  p.set("date", q.date);
  for (const k of FILTER_KEYS) {
    const v = q[k];
    if (v && v.length > 0) p.set(k, v.join(","));
  }
  for (const [k, v] of Object.entries(extra)) if (v !== undefined) p.set(k, v);
  return p;
}

export function parseBreakdown(sp: Params): { measure: BreakdownMeasure; by: BreakdownBy } {
  const m = first(sp.bm) === "memo" ? "memo" : "std";
  const b = first(sp.bb);
  return { measure: m, by: b === "segment" || b === "variant" ? b : "brand" };
}

export type PinColor = "red" | "amber" | "green";
/** Pin colour by the route's geo-fencing % today (brief 5.2): red below 70, amber 70 to 90, green above 90. */
export function pinColor(geoPct: number): PinColor {
  return geoPct < 70 ? "red" : geoPct <= 90 ? "amber" : "green";
}
