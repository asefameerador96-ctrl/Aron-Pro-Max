import { describe, expect, it } from "vitest";
import { accessOf, canSee, canWriteModule, grantsOf, moduleOfApiPath, moduleOfPage } from "@/lib/admin/modules";
import { menuFor } from "@/lib/menu/menu";
import { GRANT_MODULES } from "@/lib/admin/access-grants";

describe("module of an API path (the backend's AdminModules.ROUTES)", () => {
  it.each([
    ["/v1/admin/users/5/credentials", "credentials"],
    ["/v1/admin/users/5/mfa/reset", "credentials"],
    ["/v1/admin/users", "users"],
    ["/v1/admin/geo/house/merge", "masterdata"],
    ["/v1/admin/outlets/import", "outlets"],
    ["/v1/admin/route-targets", "targets"],
    ["/v1/admin/skus/3", "products"],
    ["/v1/admin/releases/9", "mdm"],
    ["/v1/dashboards/daily-tracking/summary", "daily_tracking"],
    ["/v1/dashboards/national", "reports"],
    ["/v1/admin/quarantine/4/resolve", "data_entry"],
  ])("%s -> %s", (path, module) => expect(moduleOfApiPath(path)).toBe(module));
  it("a path no module owns is null", () => expect(moduleOfApiPath("/v1/admin/unknown")).toBeNull());
  it("every page module is one of the 15 modules", () => {
    for (const href of ["/", "/admin/users", "/admin/route-targets", "/admin/config/day", "/admin/mdm", "/entry/qc", "/admin/team"]) expect(GRANT_MODULES).toContain(moduleOfPage(href));
  });
  it("longest prefix wins", () => {
    expect(moduleOfPage("/admin/config/day")).toBe("data_entry");
    expect(moduleOfPage("/admin/config/keys")).toBe("config");
    expect(moduleOfPage("/admin/user-access/5")).toBe("users");
    expect(moduleOfPage("/admin")).toBeNull();
  });
});

describe("grants", () => {
  it("only an ADMIN with a claim is filtered", () => {
    expect(grantsOf("ADMIN", { targets: "r" })).toEqual({ targets: "r" });
    expect(grantsOf("ADMIN", null)).toBeNull();
    expect(grantsOf("SUPERADMIN", { targets: "r" })).toBeNull();
    expect(grantsOf("TSO", { targets: "r" })).toBeNull();
  });
  it("none, read, write", () => {
    const g = { targets: "r" as const, outlets: "w" as const };
    expect([accessOf(g, "targets"), accessOf(g, "outlets"), accessOf(g, "prices")]).toEqual(["r", "w", "none"]);
    expect(canSee(g, "prices")).toBe(false);
    expect(canWriteModule(g, "targets")).toBe(false);
    expect(canWriteModule(g, "outlets")).toBe(true);
    expect(canWriteModule(null, "prices")).toBe(true); // no filter
    expect(canSee(g, null)).toBe(true); // a page of no module stays visible
  });
});

describe("menu follows the claim", () => {
  const ids = (g: Parameters<typeof menuFor>[3]) => menuFor("ADMIN", undefined, null, g).flatMap((s) => s.items.map((i) => i.id));
  it("hides entries of modules without a grant and keeps the others", () => {
    const some = ids({ outlets: "w", masterdata: "r" });
    expect(some).toContain("admin-outlet-requests");
    expect(some).toContain("admin-master-data");
    expect(some).toContain("admin-home"); // no module
    expect(some).not.toContain("admin-mdm");
    expect(some).not.toContain("cfg-devices");
    expect(some).not.toContain("credentials");
  });
  it("no claim means no filter", () => expect(ids(null)).toContain("admin-mdm"));
  it("an empty claim leaves only the pages of no module", () => {
    const none = ids({});
    expect(none).toContain("admin-home");
    expect(none).not.toContain("admin-master-data");
  });
});
