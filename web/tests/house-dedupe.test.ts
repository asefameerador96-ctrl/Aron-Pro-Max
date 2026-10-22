import { describe, expect, it } from "vitest";
import { filterListing, houseGroups, houseNameKey, serverGroups, type HouseRow } from "@/lib/admin/house-dedupe";

const h = (id: number, name: string, parent: number | null, status: "active" | "inactive" = "active"): HouseRow => ({ id, code: `H-${id}`, name, parent_id: parent, status, version: 1 });

describe("houseNameKey", () => {
  it("ignores case, spaces and punctuation, keeps Bangla letters and digits", () => {
    expect(houseNameKey("Banani  House.")).toBe(houseNameKey(" banani house"));
    expect(houseNameKey("বনানী হাউস")).toBe("বনানীহাউস");
    expect(houseNameKey("House 2")).not.toBe(houseNameKey("House 3"));
    expect(houseNameKey("---")).toBe("");
  });
});

describe("houseGroups", () => {
  const rows = [h(1, "Banani House", 6), h(2, "banani  house.", 6), h(3, "Banani House", 7), h(4, "Mirpur House", 7), h(5, "Mirpur House", 7, "inactive")];
  it("groups only within one territory", () => {
    const g = houseGroups(rows, false);
    expect(g.map((x) => x.members.map((m) => m.id))).toEqual([[1, 2], [4, 5]]);
  });
  it("onlyOpen keeps groups that still have two or more ACTIVE houses", () => {
    const g = houseGroups(rows, true);
    expect(g).toHaveLength(1);
    expect(g[0]).toMatchObject({ parentId: 6, activeCount: 2 });
  });
  it("a name that is only punctuation never groups", () => {
    expect(houseGroups([h(1, "--", 1), h(2, "..", 1)], false)).toEqual([]);
  });
});

describe("filterListing", () => {
  it("lists each active house once, the oldest wins, inactive never", () => {
    const rows = [h(2, "Banani House", 6), h(1, "banani house", 6), h(3, "Banani House", 6, "inactive"), h(4, "Banani House", 7)];
    expect(filterListing(rows).map((r) => r.id)).toEqual([1, 4]);
  });
});

describe("serverGroups (contract listHouseDuplicates)", () => {
  const list = { groups: [{ territory_id: 6, name_key: "bananihouse", houses: [{ id: 20, code: "H-5", name: "Banani  House.", status: "active" as const, is_active: true, zone_count: 0 }, { id: 10, code: "H-1", name: "Banani House", status: "active" as const, is_active: true, zone_count: 3 }] }, { territory_id: 7, name_key: "mirpurhouse", houses: [{ id: 11, code: "H-2", name: "Mirpur House", status: "active" as const, is_active: true, zone_count: 1 }, { id: 12, code: "H-9", name: "Mirpur House", status: "inactive" as const, is_active: false, zone_count: 0 }] }] };
  const rows = [h(10, "Banani House", 6), h(20, "Banani  House.", 6)].map((r, i) => ({ ...r, version: i + 4 }));
  it("orders members by id, takes the version from the house list and counts active members", () => {
    const g = serverGroups(list, rows, false);
    expect(g[0]!.members.map((m) => [m.id, m.version])).toEqual([[10, 4], [20, 5]]);
    expect(g[0]).toMatchObject({ parentId: 6, activeCount: 2 });
  });
  it("onlyOpen drops a group with one active house", () => {
    expect(serverGroups(list, rows, true).map((x) => x.parentId)).toEqual([6]);
  });
});
