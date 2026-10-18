// Drill-down URLs: one route, `/drill/<kind>`, with the business date and the Filter's scope ids kept in the query string
// so a drill-down can be bookmarked, shared and printed, and the way back keeps the same filter.
export const DRILL_KINDS = ["category", "channel", "calls", "login"] as const;
export type DrillKind = (typeof DRILL_KINDS)[number];

export const isDrillKind = (v: string): v is DrillKind => (DRILL_KINDS as readonly string[]).includes(v);

export function drillHref(kind: DrillKind, params: Record<string, string> = {}): string {
  const qs = new URLSearchParams(params).toString();
  return `/drill/${kind}${qs ? `?${qs}` : ""}`;
}
