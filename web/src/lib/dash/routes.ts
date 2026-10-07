// Browse Routes (F-WEB-010): join routes with their primary assignees. An AMO route has no SR ("SR Not Set" is normal); an SR
// route shows the AMO who owns the AMO route of the same zone, when there is one.
import type { Schemas } from "@/contract/types";

export interface RouteLine {
  route_id: number;
  code: string;
  name: string;
  kind: "sr" | "amo";
  visit_kind: string | null;
  zone_id: number;
  status: "active" | "inactive";
  /** Assigned user of an AMO route, or the zone's AMO for an SR route; null = not set. */
  amo: string | null;
  /** Assigned user of an SR route; always null for an AMO route. */
  sr: string | null;
}

export function joinRoutes(routes: readonly Schemas["Route"][], assignments: readonly Schemas["RouteAssignment"][], names: ReadonlyMap<number, string>): RouteLine[] {
  const nameOf = (uid: number | undefined): string | null => (uid === undefined ? null : (names.get(uid) ?? `#${uid}`));
  const primary = new Map<number, number>();
  for (const a of assignments) if (a.kind === "primary" && !primary.has(a.route_id)) primary.set(a.route_id, a.user_id);
  const amoByZone = new Map<number, string | null>();
  for (const r of routes) if (r.kind === "amo" && !amoByZone.has(r.zone_id)) amoByZone.set(r.zone_id, nameOf(primary.get(r.id)));
  return routes.map((r) => ({
    route_id: r.id,
    code: r.code,
    name: r.display_label ?? r.name,
    kind: r.kind,
    visit_kind: r.visit_kind ?? null,
    zone_id: r.zone_id,
    status: r.status,
    amo: r.kind === "amo" ? nameOf(primary.get(r.id)) : (amoByZone.get(r.zone_id) ?? null),
    sr: r.kind === "amo" ? null : nameOf(primary.get(r.id)),
  }));
}
