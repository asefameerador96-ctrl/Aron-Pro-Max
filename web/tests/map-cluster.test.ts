// Pin clustering for the dashboard map: same cell groups, far pins stay apart, zooming in splits a cluster, kinds are kept.
import { describe, expect, it } from "vitest";
import { clusterPins } from "@/lib/maps/cluster";
import type { ClusterPin } from "@/lib/maps/cluster";

const pin = (id: string, lat: number, lng: number, kind: ClusterPin["kind"] = "outlet"): ClusterPin => ({ id, lat, lng, label: id, kind });

describe("clusterPins", () => {
  const near = [pin("a", 23.7937, 90.4066), pin("b", 23.7938, 90.4067), pin("c", 23.7939, 90.4068, "fix")];
  it("groups pins in one cell into one count marker with the mean position", () => {
    const c = clusterPins(near, 11);
    expect(c).toHaveLength(1);
    expect(c[0]!.count).toBe(3);
    expect(c[0]!.kind).toBe("mixed");
    expect(c[0]!.lat).toBeCloseTo(23.7938, 4);
  });
  it("keeps a single kind when all pins share it", () => {
    expect(clusterPins(near.slice(0, 2), 11)[0]!.kind).toBe("outlet");
  });
  it("keeps far apart pins separate and splits a cluster when zooming in", () => {
    expect(clusterPins([pin("a", 23.7, 90.3), pin("b", 24.9, 91.2)], 11)).toHaveLength(2);
    expect(clusterPins(near, 22).length).toBeGreaterThan(1);
  });
  it("is empty for no pins and clamps an absurd zoom", () => {
    expect(clusterPins([], 11)).toEqual([]);
    expect(clusterPins(near, 99).length).toBeGreaterThan(0);
  });
});
