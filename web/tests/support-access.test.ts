// SUPPORT keeps only sync health on the dashboards (docs/24 s8.5, AZ-2): menu, report list and route gate narrow together.
import { describe, expect, it } from "vitest";
import { accessFor } from "@/lib/auth/roles";
import { menuFor } from "@/lib/menu/menu";
import { REPORT_ROLES, WEB_REPORTS, canSeeReport } from "@/lib/reports/catalog";

describe("SUPPORT on the dashboards", () => {
  it("the dashboard menu offers sync health, credentials, tutorial and exceptions (risk signals); no report, product or business dashboard", () => {
    const main = menuFor("SUPPORT").filter((s) => s.group !== "admin" && s.group !== "config").flatMap((s) => s.items.map((i) => i.href));
    expect(main.sort()).toEqual(["/credentials", "/exceptions", "/sync-health", "/tutorial"]);
  });
  it("no report is visible to SUPPORT, every report still is for the other web roles", () => {
    expect(REPORT_ROLES).not.toContain("SUPPORT");
    expect(WEB_REPORTS.some((r) => canSeeReport(r, "SUPPORT"))).toBe(false);
    for (const role of ["TSO", "DMO", "WM", "TOP", "ANALYST"] as const) expect(WEB_REPORTS.some((r) => canSeeReport(r, role)), role).toBe(true);
  });
  it("the gate refuses the other dashboard pages and the report BFF, and keeps the admin portal and sync health", () => {
    for (const p of ["/daily-tracking", "/tso-daily-tracking", "/final-submit", "/leave", "/routes", "/products/brand", "/reports/retailers", "/api/bff/reports/retailers/export"]) expect(accessFor("SUPPORT", p), p).toBe("forbidden");
    for (const p of ["/", "/sync-health", "/credentials", "/tutorial", "/exceptions", "/device-otp", "/api/bff/password", "/api/bff/maps/load", "/admin/devices", "/_next/static/x.js"]) expect(accessFor("SUPPORT", p), p).toBe("ok");
  });
  it("the audit log page is closed to SUPPORT (no read route), the config audit page is not", () => {
    expect(accessFor("SUPPORT", "/admin/audit")).toBe("forbidden");
    expect(accessFor("ADMIN", "/admin/audit")).toBe("ok");
    expect(accessFor("SUPPORT", "/admin/config/audit")).toBe("ok");
    expect(menuFor("SUPPORT").flatMap((x) => x.items.map((i) => i.href))).not.toContain("/admin/audit");
  });
  it("other roles are untouched", () => {
    for (const r of ["TSO", "DMO", "ANALYST"] as const) for (const p of ["/daily-tracking", "/reports/retailers", "/sync-health"]) expect(accessFor(r, p)).toBe("ok");
  });
});
