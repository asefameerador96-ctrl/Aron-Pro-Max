// Geography options for the cascade selectors (wing → division → territory → house → zone), loaded in the caller's reach.
import { rawRequest } from "@/lib/api/raw";
import type { components } from "@/contract/types";

export type GeoNode = components["schemas"]["GeoNode"];
export const GEO_LEVELS = ["wing", "division", "territory", "house", "zone"] as const;
export type GeoLevel = (typeof GEO_LEVELS)[number];

export interface GeoSelection {
  wing?: string;
  division?: string;
  territory?: string;
  house?: string;
  zone?: string;
}

/** Options per level: a level lists children of the level above once that one is chosen (wings always). */
export async function loadGeoOptions(token: string, sel: GeoSelection): Promise<Record<GeoLevel, GeoNode[]>> {
  const out = { wing: [], division: [], territory: [], house: [], zone: [] } as Record<GeoLevel, GeoNode[]>;
  let parent: string | undefined;
  for (const level of GEO_LEVELS) {
    if (level !== "wing" && !parent) break;
    const r = await rawRequest<{ items: GeoNode[] }>({ method: "GET", path: `/v1/admin/geo/${level}`, token, query: { parent_id: parent, status: "active", limit: 500 } });
    if (!r.ok) break;
    out[level] = r.data.items;
    parent = sel[level] && /^[1-9]\d{0,14}$/.test(sel[level]!) && r.data.items.some((n) => String(n.id) === sel[level]) ? sel[level] : undefined;
  }
  return out;
}
