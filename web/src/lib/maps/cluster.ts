// Lightweight pin clustering for the dashboard map (no runtime dependency): pins that fall into the same grid cell at the current
// zoom become one count marker. The cell is about `CELL_PX` screen pixels wide at the equator-scaled Web Mercator resolution.
/** Structurally the ClusterPin of the map panel (kept local so only the panel imports the Maps module). */
export interface ClusterPin {
  id: string;
  lat: number;
  lng: number;
  label: string;
  kind: "outlet" | "fix";
}

export const CELL_PX = 56;

export interface PinCluster {
  key: string;
  lat: number;
  lng: number;
  count: number;
  /** "outlet" or "fix" when every pin of the cluster has that kind, "mixed" otherwise. */
  kind: "outlet" | "fix" | "mixed";
  pins: ClusterPin[];
}

export function clusterPins(pins: readonly ClusterPin[], zoom: number): PinCluster[] {
  const degPerPx = 360 / (256 * 2 ** Math.max(0, Math.min(22, zoom)));
  const cell = degPerPx * CELL_PX;
  const cells = new Map<string, ClusterPin[]>();
  for (const p of pins) {
    const k = `${Math.floor(p.lat / cell)}:${Math.floor(p.lng / cell)}`;
    const arr = cells.get(k);
    if (arr) arr.push(p);
    else cells.set(k, [p]);
  }
  return [...cells.entries()].map(([key, ps]) => {
    const kinds = new Set(ps.map((p) => p.kind));
    return {
      key,
      lat: ps.reduce((s, p) => s + p.lat, 0) / ps.length,
      lng: ps.reduce((s, p) => s + p.lng, 0) / ps.length,
      count: ps.length,
      kind: kinds.size === 1 ? ps[0]!.kind : "mixed",
      pins: ps,
    };
  });
}
