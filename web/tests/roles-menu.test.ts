import { describe, expect, it } from "vitest";
import { ADMIN_PORTAL_ROLES, ALL_ROLES, MFA_ROLES, WEB_ROLES, accessFor, routeGroup } from "@/lib/auth/roles";
import { MENU, menuFor, type MenuItem } from "@/lib/menu/menu";

describe("route groups and role gate", () => {
  it("classifies paths", () => {
    expect(routeGroup("/login")).toBe("public");
    expect(routeGroup("/api/bff/login")).toBe("public");
    expect(routeGroup("/api/bff/mfa/verify")).toBe("public");
    expect(routeGroup("/admin")).toBe("admin");
    expect(routeGroup("/admin/clusters/5")).toBe("admin");
    expect(routeGroup("/api/bff/admin/clusters")).toBe("admin");
    expect(routeGroup("/administrator")).toBe("dashboards"); // not a prefix match on a word
    expect(routeGroup("/")).toBe("dashboards");
  });
  it("admin routes: only the portal roles; everyone else is forbidden; anonymous is unauthenticated", () => {
    for (const r of ALL_ROLES) {
      expect(accessFor(r, "/admin/clusters"), r).toBe((ADMIN_PORTAL_ROLES as readonly string[]).includes(r) ? "ok" : "forbidden");
    }
    expect(accessFor(undefined, "/admin")).toBe("unauthenticated");
    expect(accessFor(undefined, "/login")).toBe("ok");
  });
  it("dashboards: web roles only (SR and AMO use the field apps)", () => {
    for (const r of ALL_ROLES) expect(accessFor(r, "/"), r).toBe((WEB_ROLES as readonly string[]).includes(r) ? "ok" : "forbidden");
    expect(WEB_ROLES).not.toContain("SR");
    expect(WEB_ROLES).not.toContain("AMO");
  });
  it("TOTP is required for ADMIN, SUPERADMIN and SUPPORT (docs/24 s6.5)", () => {
    expect([...MFA_ROLES].sort()).toEqual(["ADMIN", "SUPERADMIN", "SUPPORT"]);
  });
});

describe("menu is driven from data", () => {
  it("shows the admin group only to the portal roles", () => {
    expect(menuFor("TSO").map((s) => s.group)).toEqual(["main"]);
    expect(menuFor("ADMIN").map((s) => s.group)).toEqual(["main", "admin"]);
    expect(menuFor("ANALYST").map((s) => s.group)).toEqual(["main"]);
  });
  it("includes the clusters entity contributed by the registry", () => {
    const admin = menuFor("ADMIN").find((s) => s.group === "admin");
    expect(admin?.items.map((i) => i.href)).toEqual(["/admin", "/admin/clusters", "/admin/audit"]);
  });
  it("a new item with a role list appears for exactly those roles", () => {
    const extra: MenuItem = { id: "x", labelKey: "menu.dashboard", href: "/x", roles: ["DMO"], group: "main" };
    expect(menuFor("DMO", [...MENU, extra]).flatMap((s) => s.items).some((i) => i.id === "x")).toBe(true);
    expect(menuFor("TSO", [...MENU, extra]).flatMap((s) => s.items).some((i) => i.id === "x")).toBe(false);
  });
});
