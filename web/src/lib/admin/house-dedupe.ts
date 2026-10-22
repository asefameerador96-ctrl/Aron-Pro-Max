// House de-duplication (docs/34 s2 "House level"): the dashboard filter lists each ACTIVE house once, so two active houses with the
// same name in one territory must be resolved. Two houses are the same when their names match after trimming, case folding and
// removing spaces and punctuation, within one territory (parent). The rule is deliberately simple and shown to the user.

export interface HouseRow {
  id: number;
  code: string;
  name: string;
  name_bn?: string | null;
  parent_id: number | null;
  status: "active" | "inactive";
  version: number;
  created_at?: string;
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
