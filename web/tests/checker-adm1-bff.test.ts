// Checker ADM-1: BFF write path of the geography, route, assignment and user rows against their acceptance text and docs/24 s8.5.
import { describe, expect, it } from "vitest";
import { geoEntities } from "@/app/admin/_entities/geo";
import { routes } from "@/app/admin/_entities/routes";
import { valuesSchema } from "@/components/admin/crud/validation";
import { businessDate } from "@/lib/i18n";
import { setupMock } from "./helpers/bff";

const { signIn, create, update, act } = setupMock();
const REASON = "Reason typed by the administrator";
const zone = geoEntities.find((e) => e.slug === "zones")!;

// CB1 to CB3 (a create stores its reason in the audit row) are NOT kept: ClusterWrite, RouteWrite and UserWrite have no reason
// member in the contract, so no BFF can store it. docs/requests/web-admin-create-reason.md asks for one; until then the create form
// says so (e2e/master.spec.ts, "create forms say when the reason cannot be stored").

describe("docs/24 s8.5: only SUPERADMIN writes ADMIN roles", () => {
  it("CB4: an ADMIN cannot create a SUPERADMIN (or ADMIN) user through the portal BFF", async () => {
    const c = await signIn("madmin1");
    const res = await create("users", { values: { username: "boss001", full_name: "Boss", role: "SUPERADMIN", locale: "bn" }, reason: REASON }, c);
    expect(res.status).toBe(403);
  });
  it("CB5: an ADMIN cannot promote an existing user to SUPERADMIN", async () => {
    const c = await signIn("madmin1");
    const res = await update("users", "1001", { values: { role: "SUPERADMIN" }, reason: REASON, version: 1 }, c);
    expect(res.status).toBe(403);
  });
});

describe("Bengali digits and dates are normalised/validated before the request leaves the BFF", () => {
  it("CB6: a Bengali-digit PDA contact number is accepted (the users phone already normalises)", () => {
    const r = valuesSchema(zone, "update").safeParse({ pda_contact_no: "০১৭১২৩৪৫৬৭৮" });
    expect(r.success).toBe(true);
  });
  it("CB7: a Bengali-digit sequence number is accepted", () => {
    const r = valuesSchema(routes, "update").safeParse({ sequence_no: "১২" });
    expect(r.success).toBe(true);
  });
  it("CB8: an impossible calendar date (2026-02-30, 2026-13-45) is refused for effective_from", () => {
    const s = valuesSchema(routes, "update");
    expect(s.safeParse({ effective_from: "2026-02-30" }).success).toBe(false);
    expect(s.safeParse({ effective_from: "2026-13-45" }).success).toBe(false);
  });
});

describe("F-ADM-003: re-attributes nothing historic", () => {
  const daysAgo = (n: number) => businessDate(new Date(Date.now() - n * 86_400_000));
  it("CB9: ending an assignment on a date before today (Dhaka) is refused, because it would pull past days off the SR", async () => {
    const c = await signIn("madmin1");
    const res = await act("route-assignments", "1", "end", { values: { valid_to: daysAgo(3) }, reason: REASON }, c);
    expect(res.status).toBe(400);
  });
  it("CB10: creating an assignment that starts before today (Dhaka) is refused, because it would re-attribute past days", async () => {
    const c = await signIn("madmin1");
    const res = await create("route-assignments", { values: { route_id: "4", user_id: "1002", kind: "cover", valid_from: daysAgo(30) }, reason: REASON }, c);
    expect(res.status).toBe(400);
  });
});
