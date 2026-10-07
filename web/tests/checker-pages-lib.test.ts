// Checker: pure-function defects of routes/products/buckets/strike formatting.
import { describe, expect, it } from "vitest";
import { buildTree, levelRows, type Node } from "@/lib/dash/products";
import { joinRoutes } from "@/lib/dash/routes";
import { canTakeAction } from "@/lib/dash/buckets";
import { pctText } from "@/components/dash/tiles";

const route = (id: number, kind: "sr" | "amo", zone = 1) => ({ id, code: `C${id}`, name: `R${id}`, kind, zone_id: zone, status: "active" }) as never;
const asg = (route_id: number, user_id: number, kind = "primary", valid_to: string | null = null) => ({ id: route_id * 10 + user_id, route_id, user_id, kind, valid_from: "2026-01-01", valid_to, created_at: "x" }) as never;

describe("F-WEB-010 joinRoutes", () => {
  it("zone with two AMO routes, first unassigned: SR route still shows the zone's assigned AMO", () => {
    const lines = joinRoutes([route(1, "amo"), route(2, "amo"), route(3, "sr")], [asg(2, 50)], new Map([[50, "Karim"]]));
    expect(lines.find((l) => l.route_id === 3)!.amo).toBe("Karim");
  });
  it("an ended primary (valid_to in the past) is not shown as the SR", () => {
    const lines = joinRoutes([route(3, "sr")], [asg(3, 60, "primary", "2026-09-01"), asg(3, 61)], new Map([[60, "Old"], [61, "Current"]]), );
    expect(lines[0]!.sr).toBe("Current");
  });
  it("AMO route always shows SR not set", () => {
    expect(joinRoutes([route(1, "amo")], [asg(1, 50)], new Map([[50, "K"]]))[0]!.sr).toBeNull();
  });
});

describe("F-WEB-004..009 products", () => {
  const n = (id: number, level: Node["level"], parent_id: number | null, name: string, sort = 1): Node => ({ id, level, parent_id, name, sort, status: "active" }) as Node;
  it("tree orders equal sort values by name (contract: ties broken by name)", () => {
    const all = { category: [n(1, "category", null, "Zeta"), n(2, "category", null, "Alpha")], segment: [], brand: [], variant: [] };
    expect(buildTree(all, [], "All").children.map((c) => c.label)).toEqual(["Alpha", "Zeta"]);
  });
  it("levelRows survives a parent_id loop and a missing parent", () => {
    const all = { category: [n(1, "category", 2, "A")], segment: [n(2, "segment", 1, "B"), n(3, "segment", 99, "Orphan")], brand: [], variant: [] };
    expect(levelRows("segment", all)).toHaveLength(2);
  });
});

describe("take-action boundaries", () => {
  const d = (s: string) => new Date(s);
  it.each([
    ["2026-10-07T10:59:59Z", false],
    ["2026-10-07T11:00:00Z", true],
    ["2026-10-07T17:59:59Z", true], // 23:59:59 Dhaka
  ])("today at %s -> %s", (iso, exp) => expect(canTakeAction("2026-10-07", "2026-10-07", d(iso))).toBe(exp));
});

describe("strike rate text", () => {
  it("1 of 43 is 2.3%, 0 and null are dashes, 100+ kept", () => {
    expect(pctText("en", (1 / 43) * 100)).toBe("2.3%");
    expect(pctText("en", null)).toBe("—");
    expect(pctText("en", 100)).toBe("100.0%");
    expect(pctText("en", 0)).toBe("0.0%");
  });
});
