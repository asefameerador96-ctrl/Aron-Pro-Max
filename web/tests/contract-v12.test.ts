// Contract v1.2 members on the dashboard side: each degrades when the server value is null or missing.
import { describe, expect, it } from "vitest";
import { linesFromAssignees } from "@/lib/dash/routes";
import { zoneFinalOf } from "@/components/dash/final-submit-panel";

const route = (id: number, kind: "sr" | "amo", assignees?: unknown) => ({ id, code: `C${id}`, name: `R${id}`, kind, zone_id: 1, status: "active", ...(assignees ? { assignees } : {}) }) as never;
const u = (user_id: number, full_name: string, role: "SR" | "AMO") => ({ user_id, full_name, role, username: `u${user_id}` });

describe("F-WEB-010 routes with assignees", () => {
  it("SR route shows its SR and the zone's AMO; AMO route never an SR; empty assignees read as not set", () => {
    const lines = linesFromAssignees([route(1, "amo", [u(5, "Karim", "AMO")]), route(2, "sr", [u(6, "Jamal", "SR")]), route(3, "sr", [])]);
    expect(lines.find((l) => l.route_id === 2)).toMatchObject({ sr: "Jamal", amo: "Karim" });
    expect(lines.find((l) => l.route_id === 1)).toMatchObject({ sr: null, amo: "Karim" });
    expect(lines.find((l) => l.route_id === 3)!.sr).toBeNull();
  });
});

describe("F-WEB-047 zone final state", () => {
  it("is undefined when the server sends no zones (the KPI estimate stays), a map otherwise", () => {
    expect(zoneFinalOf(undefined)).toBeUndefined();
    expect(zoneFinalOf([{ zone_id: 3, final_submitted: true }, { zone_id: 4, final_submitted: false }])!.get(3)).toBe(true);
  });
});
