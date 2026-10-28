// Wave 4 (docs/34 s1): module grants and brand scope of a user through the whitelisted op, against the provisional mock.
import { describe, expect, it } from "vitest";
import { accessKind, accessRules, accessShapeRules, GRANT_MODULES, isAppOnly, modulesBody, PORTAL_LEVELS, USER_ROLE_OPTIONS } from "@/lib/admin/access-grants";
import { OPS } from "@/lib/admin/ops";
import { madmin, op, setupMock, superadmin, support } from "./helpers/harness";

const h = setupMock();
const REASON = "Granting the outlets module for the audit";
const MOCK_BASE = () => process.env.ARON_API_BASE_URL!;

describe("portal levels", () => {
  it("lists the owner's seven levels first, then app-only roles, and marks SR and AMO as app only", () => {
    expect([...PORTAL_LEVELS]).toEqual(["SUPERADMIN", "ADMIN", "NATIONAL", "BRAND", "WM", "DMO", "TSO"]);
    expect(USER_ROLE_OPTIONS.slice(0, 7)).toEqual([...PORTAL_LEVELS]);
    expect(isAppOnly("SR") && isAppOnly("AMO")).toBe(true);
    expect(PORTAL_LEVELS.some((r) => isAppOnly(r))).toBe(false);
  });
  it("offers modules to ADMIN, brands to BRAND and nothing to the other roles", () => {
    expect(accessKind("ADMIN")).toBe("modules");
    expect(accessKind("BRAND")).toBe("brands");
    for (const r of ["SUPERADMIN", "NATIONAL", "WM", "DMO", "TSO", "SR", "AMO", "SUPPORT"]) expect(accessKind(r)).toBe("none");
  });
  it("modulesBody sends only granted modules", () => {
    const all = Object.fromEntries(GRANT_MODULES.map((m) => [m, "none"])) as Parameters<typeof modulesBody>[0];
    expect(modulesBody({ ...all, outlets: "w", reports: "r" })).toEqual({ outlets: "w", reports: "r" });
    expect(modulesBody(all)).toEqual({});
  });
});

describe("modules", () => {
  it("sixteen modules including data entry, indent, leaderboard, daily tracking and support (R-F-11)", () => {
    expect(GRANT_MODULES).toHaveLength(16); // tso-manual W1: + support (R-F-11)
    for (const m of ["data_entry", "indent", "leaderboard", "daily_tracking", "support"]) expect(GRANT_MODULES as readonly string[]).toContain(m);
  });
});

describe("access rules", () => {
  it("accepts a module write for ADMIN and a brand write for BRAND", () => {
    expect(accessRules("ADMIN", { modules: { outlets: "w" }, change_reason: REASON })).toEqual([]);
    expect(accessRules("BRAND", { brand_ids: [5, 6] })).toEqual([]);
  });
  it("refuses the wrong member for the role, unknown modules, bad levels, duplicate or empty brands", () => {
    expect(accessRules("TSO", { modules: {} })).toContainEqual({ pointer: "/modules", code: "role_mismatch" });
    expect(accessRules("ADMIN", { brand_ids: [1] })).toContainEqual({ pointer: "/brand_ids", code: "role_mismatch" });
    expect(accessShapeRules({ modules: { nope: "r" } })).toContainEqual({ pointer: "/modules/nope", code: "unknown_member" });
    expect(accessShapeRules({ modules: { outlets: "x" } })).toContainEqual({ pointer: "/modules/outlets", code: "invalid" });
    expect(accessShapeRules({ brand_ids: [1, 1] })).toContainEqual({ pointer: "/brand_ids", code: "invalid" });
    expect(accessShapeRules({ brand_ids: [] })).toContainEqual({ pointer: "/brand_ids", code: "required" });
    expect(accessShapeRules({ brand_ids: [0] })).toContainEqual({ pointer: "/brand_ids", code: "invalid" });
    expect(accessShapeRules({})).toContainEqual({ pointer: "/modules", code: "required" });
    expect(accessShapeRules({ modules: {}, brand_ids: [1] }).length).toBeGreaterThan(0);
    expect(accessShapeRules({ scope: "all", modules: {} })).toContainEqual({ pointer: "/scope", code: "unknown_member" });
  });
});

describe("POST /api/bff/admin-op user-access.put", () => {
  it("is a PUT with If-Match and the reason in change_reason, for Super Admin only", () => {
    expect(OPS["user-access.put"]).toMatchObject({ method: "PUT", reason: "change_reason", ifMatch: true });
    expect([...OPS["user-access.put"].roles]).toEqual(["SUPERADMIN"]);
  });

  it("Super Admin writes the modules of an ADMIN: version in If-Match, reason in the body, audited", async () => {
    const c = await superadmin();
    const res = await op(c, { op: "user-access.put", params: { id: 3001 }, body: { modules: { outlets: "w", prices: "r" } }, reason: REASON, version: 2 });
    expect(res.status).toBe(200);
    const stored = await (await fetch(`${MOCK_BASE()}/v1/admin/users/3001/access`, { headers: { Authorization: `Bearer ${[...h.mock.state.access.keys()].pop()}` } })).json();
    expect(stored).toMatchObject({ role: "ADMIN", version: 3, modules: { outlets: "w", prices: "r" } });
    expect(h.mock.state.audit.at(-1)).toMatchObject({ entity: "user_access", reason: REASON });
  });

  it("a stale version answers 412 and changes nothing", async () => {
    const res = await op(await superadmin(), { op: "user-access.put", params: { id: 3001 }, body: { modules: {} }, reason: REASON, version: 1 });
    expect(res.status).toBe(412);
    expect(h.mock.state.custom.access[3001]!.version).toBe(2);
  });

  it("an ADMIN or SUPPORT caller is refused at the proxy; no API call is made", async () => {
    expect((await op(await madmin(), { op: "user-access.put", params: { id: 3001 }, body: { modules: {} }, reason: REASON, version: 2 })).status).toBe(403);
    expect((await op(await support(), { op: "user-access.put", params: { id: 3001 }, body: { modules: {} }, reason: REASON, version: 2 })).status).toBe(403);
    expect(h.calls()).toHaveLength(0);
  });

  it("requires a reason and a version", async () => {
    const c = await superadmin();
    expect((await op(c, { op: "user-access.put", params: { id: 3001 }, body: { modules: {} }, reason: "short", version: 2 })).status).toBe(400);
    expect((await op(c, { op: "user-access.put", params: { id: 3001 }, body: { modules: {} }, reason: REASON })).status).toBe(400);
  });

  it("the server refuses grants for a role that cannot hold them (TSO modules, ADMIN brands) and an unknown brand", async () => {
    const c = await superadmin();
    expect((await op(c, { op: "user-access.put", params: { id: 2001 }, body: { modules: { outlets: "r" } }, reason: REASON, version: 1 })).status).toBe(400);
    expect((await op(c, { op: "user-access.put", params: { id: 3001 }, body: { brand_ids: [5] }, reason: REASON, version: 2 })).status).toBe(400);
    expect((await op(c, { op: "user-access.put", params: { id: 5002 }, body: { brand_ids: [999] }, reason: REASON, version: 1 })).status).toBe(400);
    expect((await op(c, { op: "user-access.put", params: { id: 5002 }, body: { brand_ids: [5, 6] }, reason: REASON, version: 1 })).status).toBe(200);
  });
});
