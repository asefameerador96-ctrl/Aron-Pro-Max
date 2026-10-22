// House de-duplication (docs/34 s2 "House level"): the dashboard filter lists each ACTIVE house once, so two active houses with the
// same name in one territory must be resolved. The SERVER decides what a duplicate is (contract listHouseDuplicates: one territory,
// same name after case, spacing and punctuation) and performs the merge (mergeHouses); the client rule below is kept only for the
// dashboard filter listing and as the reference of the rule.
import type { Schemas } from "@/contract/types";

export interface HouseRow {
  id: number;
  code: string;
  name: string;
  name_bn?: string | null;
  parent_id: number | null;
  status: "active" | "inactive";
  version: number;
  created_at?: string;
  /** Zones under the house (server list only). */
  zone_count?: number;
}

export interface HouseGroup {
  parentId: number | null;
  key: string;
  /** Members by id (oldest first). The first ACTIVE member is the suggested keeper. */
  members: HouseRow[];
  activeCount: number;
}

/** Name key: NFKC, lower case, letters, marks (Bangla vowel signs) and digits of any script only. "Banani  House." and "banani house" collide. */
export function houseNameKey(name: string): string {
  return name.normalize("NFKC").toLowerCase().replace(/[^\p{L}\p{M}\p{N}]+/gu, "");
}

/** Groups of two or more houses with the same name key in one territory. `onlyOpen` keeps groups with 2+ ACTIVE members (still a conflict). */
export function houseGroups(rows: readonly HouseRow[], onlyOpen: boolean): HouseGroup[] {
  const by = new Map<string, HouseRow[]>();
  for (const r of rows) {
    const key = houseNameKey(r.name);
    if (key === "") continue;
    const k = `${r.parent_id ?? 0}\u0000${key}`;
    by.set(k, [...(by.get(k) ?? []), r]);
  }
  const groups: HouseGroup[] = [];
  for (const members of by.values()) {
    if (members.length < 2) continue;
    const sorted = [...members].sort((a, b) => a.id - b.id);
    const activeCount = sorted.filter((m) => m.status === "active").length;
    if (onlyOpen && activeCount < 2) continue;
    groups.push({ parentId: sorted[0]!.parent_id, key: houseNameKey(sorted[0]!.name), members: sorted, activeCount });
  }
  return groups.sort((a, b) => (a.parentId ?? 0) - (b.parentId ?? 0) || a.key.localeCompare(b.key));
}

/** Houses the dashboard filter lists: each active house once (name key within a territory), the oldest wins. */
export function filterListing(rows: readonly HouseRow[]): HouseRow[] {
  const seen = new Set<string>();
  const out: HouseRow[] = [];
  for (const r of [...rows].filter((x) => x.status === "active").sort((a, b) => a.id - b.id)) {
    const k = `${r.parent_id ?? 0}\u0000${houseNameKey(r.name)}`;
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(r);
  }
  return out;
}

/** Groups of the server's duplicate list joined with the ordinary house rows (version and creation time, which the list lacks). `onlyOpen` keeps groups with 2+ ACTIVE members. */
export function serverGroups(list: Schemas["HouseDuplicateList"], rows: readonly HouseRow[], onlyOpen: boolean): HouseGroup[] {
  const byId = new Map(rows.map((r) => [r.id, r]));
  const out: HouseGroup[] = [];
  for (const g of list.groups) {
    const members: HouseRow[] = g.houses
      .map((h) => ({ ...(byId.get(h.id) ?? { version: 0 }), id: h.id, code: h.code, name: h.name, parent_id: g.territory_id, status: h.is_active ? ("active" as const) : ("inactive" as const), zone_count: h.zone_count }))
      .sort((a, b) => a.id - b.id);
    const activeCount = members.filter((m) => m.status === "active").length;
    if (onlyOpen && activeCount < 2) continue;
    out.push({ parentId: g.territory_id, key: g.name_key, members, activeCount });
  }
  return out.sort((a, b) => (a.parentId ?? 0) - (b.parentId ?? 0) || a.key.localeCompare(b.key));
}
