import { describe, expect, it } from "vitest";
import { routeAssignments, routes } from "@/app/admin/_entities/routes";
import { users } from "@/app/admin/_entities/users";
import { valuesSchema } from "@/components/admin/crud/validation";
import { setupMock } from "./helpers/bff";

const { mock, signIn, create, update, act } = setupMock();
const REASON = "Reason that is long enough";

describe("routes", () => {
  it("validates the day mask (1..127), kind, code and visit kind", () => {
    const s = valuesSchema(routes, "create");
    const ok = { code: "R-9", name: "Route", zone_id: "14", kind: "sr", visit_days_mask: "21" };
    expect(s.safeParse(ok)).toMatchObject({ success: true, data: { visit_days_mask: 21, zone_id: 14 } });
    expect(s.safeParse({ ...ok, visit_days_mask: "0" }).success).toBe(false);
    expect(s.safeParse({ ...ok, visit_days_mask: "128" }).success).toBe(false);
    expect(s.safeParse({ ...ok, kind: "tso" }).success).toBe(false);
    expect(s.safeParse({ ...ok, visit_kind: "5f" }).success).toBe(false);
    expect(s.safeParse({ ...ok, visit_kind: "" })).toMatchObject({ success: true, data: { visit_kind: null } });
    expect(valuesSchema(routes, "update").safeParse({ code: "X" }).success).toBe(false); // code is create-only
    expect(valuesSchema(routes, "update").safeParse({ effective_from: "2026-10-20" }).success).toBe(true);
    expect(valuesSchema(routes, "update").safeParse({ effective_from: "20/10/2026" }).success).toBe(false);
  });
  it("changes visit days with a future effective date and audits the reason; the label stays apart from the name", async () => {
    const c = await signIn("admin1");
    const res = await update("routes", "2", { values: { visit_days_mask: "42", display_label: "(Sun, Tue, Thu)", effective_from: "2026-10-20" }, reason: REASON, version: 1 }, c);
    expect(res.status).toBe(200);
    expect((await res.json()).row).toMatchObject({ name: "Banani 3F", display_label: "(Sun, Tue, Thu)", visit_days_mask: 42 });
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "route", reason: REASON });
  });
  it("creates a route (the reason is required but cannot be forwarded yet)", async () => {
    const c = await signIn("admin1");
    expect((await create("routes", { values: { code: "R-9", name: "New", zone_id: "14", kind: "sr", visit_days_mask: "127", visit_kind: "daily" }, reason: REASON }, c)).status).toBe(201);
    expect((await create("routes", { values: { code: "R-9", name: "Again", zone_id: "14", kind: "sr", visit_days_mask: "127" }, reason: REASON }, c)).status).toBe(409);
  });
});

describe("route assignments", () => {
  const body = (extra: Record<string, unknown>) => ({ values: { route_id: "1", user_id: "1002", kind: "primary", valid_from: "2026-10-10", ...extra }, reason: REASON });
  it("has no edit and no PATCH", async () => {
    const c = await signIn("admin1");
    expect((await update("route-assignments", "1", { values: { kind: "cover" }, reason: REASON, version: 1 }, c)).status).toBe(404);
  });
  it("rejects an overlapping primary on one route and day (409), accepts a cover", async () => {
    const c = await signIn("admin1");
    const clash = await create("route-assignments", body({}), c);
    expect(clash.status).toBe(409);
    expect((await clash.json()).code).toBe("ERR_MASTER_OVERLAP");
    expect((await create("route-assignments", body({ kind: "cover" }), c)).status).toBe(201);
  });
  it("sends the reason as `reason` (max 300) and nothing else extra", async () => {
    const c = await signIn("admin1");
    expect((await create("route-assignments", body({ route_id: "4", valid_from: "2026-10-10" }), c)).status).toBe(201);
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "route_assignment", reason: REASON });
    const long = await create("route-assignments", { ...body({ route_id: "4", valid_from: "2026-11-10", kind: "cover" }), reason: "x".repeat(301) }, c);
    expect(long.status).toBe(400);
  });
  it("ends an open assignment with a reason; history is kept; ending before it began is refused", async () => {
    const c = await signIn("admin1");
    const bad = await act("route-assignments", "1", "end", { values: { valid_to: "2026-08-01" }, reason: REASON }, c);
    expect(bad.status).toBe(409);
    const res = await act("route-assignments", "1", "end", { values: { valid_to: "2026-10-15" }, reason: REASON }, c);
    expect(res.status).toBe(200);
    expect(mock.state.tables.assignments!.find((r) => r.id === 1)).toMatchObject({ valid_to: "2026-10-15" });
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "route_assignment", reason: REASON });
    expect((await act("route-assignments", "1", "end", { values: { valid_to: "2026-10-15" }, reason: "short" }, c)).status).toBe(400);
    expect((await act("route-assignments", "1", "nope", { values: {}, reason: REASON }, c)).status).toBe(404);
  });
  it("is read-only for support", async () => {
    const c = await signIn("support1");
    expect((await act("route-assignments", "1", "end", { values: { valid_to: "2026-10-15" }, reason: REASON }, c)).status).toBe(403);
    expect(routeAssignments.canCreate).not.toBe(false);
  });
});

describe("users", () => {
  const base = { username: "sr999001", full_name: "New SR", role: "SR", locale: "bn" };
  it("create returns a one-time temporary password; the row is the user", async () => {
    const c = await signIn("admin1");
    const res = await create("users", { values: { ...base, phone: "০১৭১২৩৪৫৬৭৮" }, reason: REASON }, c);
    expect(res.status).toBe(201);
    const b = await res.json();
    expect(b.row).toMatchObject({ username: "sr999001", phone: "01712345678", status: "active" }); // Bengali digits normalised
    expect(b.shown.temporary_password).toMatch(/^Tmp-/);
    expect(typeof b.shown.temporary_password_expires_at).toBe("string");
    expect(JSON.stringify(mock.state.audit)).not.toContain("Tmp-"); // never in the audit log
  });
  it("validates username, phone, role and locale like the contract", () => {
    const s = valuesSchema(users, "create");
    expect(s.safeParse(base).success).toBe(true);
    expect(s.safeParse({ ...base, username: "9bad" }).success).toBe(false);
    expect(s.safeParse({ ...base, role: "BOSS" }).success).toBe(false);
    expect(s.safeParse({ ...base, locale: "fr" }).success).toBe(false);
    expect(s.safeParse({ ...base, phone: "01012345678" }).success).toBe(false);
    expect(s.safeParse({ ...base, pilot: "true" })).toMatchObject({ success: true, data: { pilot: true } });
  });
  it("disables a user through status with a reason and keeps the row", async () => {
    const c = await signIn("admin1");
    const res = await update("users", "1001", { values: { status: "disabled" }, reason: REASON, version: 1 }, c);
    expect(res.status).toBe(200);
    expect((await res.json()).row).toMatchObject({ status: "disabled" });
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "user", reason: REASON, before: { status: "active" }, after: { status: "disabled" } });
  });
  it("credential actions: reset shows the password once, unlock does not; support may do both, ADMIN-only edit stays", async () => {
    const support = await signIn("support1");
    const reset = await act("users", "1001", "reset_password", { values: {}, reason: REASON }, support);
    expect(reset.status).toBe(200);
    expect((await reset.json()).shown.temporary_password).toMatch(/^Tmp-/);
    const unlock = await act("users", "4001", "unlock", { values: {}, reason: REASON }, support);
    expect(unlock.status).toBe(200);
    expect((await unlock.json()).shown).toBeUndefined(); // nothing to show once
    expect((await update("users", "1001", { values: { full_name: "X" }, reason: REASON, version: 1 }, support)).status).toBe(403);
    expect((await act("users", "1001", "reset_password", { values: {}, reason: "short" }, support)).status).toBe(400);
    expect(JSON.stringify(mock.state.audit)).not.toContain("Tmp-");
  });
  it("the action `when` rule hides credential resets for disabled users", () => {
    const reset = users.actions!.find((a) => a.key === "reset_password")!;
    expect(reset.when!({ status: "disabled" })).toBe(false);
    expect(reset.when!({ status: "active" })).toBe(true);
    expect(users.actions!.find((a) => a.key === "unlock")!.when).toBeUndefined();
  });
});
