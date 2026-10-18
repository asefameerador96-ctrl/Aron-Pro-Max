// listAudit on the web: who may open the audit pages, and the mock's copy of the server's masking (backend AuditRead.kt).
import { describe, expect, it } from "vitest";
import { MASK, maskAudit } from "../mock/audit-mask";
import type { AuditEntry } from "@/contract/types";
import { accessFor } from "@/lib/auth/roles";

const row = (over: Partial<AuditEntry> = {}): AuditEntry => ({
  id: 7, at: "2026-10-18T04:00:00.000Z", actor_user_id: 3001, actor_username: "admin1", actor_role: "ADMIN", via: "web", entity: "user", entity_id: "42",
  action: "user.update", before: { status: "active", full_name: "Rahim Uddin", designation: "SR", password_hash: "x" }, after: { status: "disabled", full_name: "Rahim U.", designation: "SR", password_hash: "y" },
  reason: "Left the company", request_id: null, row_hash: "a".repeat(64), ...over,
});

describe("audit pages: role gate", () => {
  it("opens /admin/audit and its CSV to ANALYST, SUPPORT, ADMIN and SUPERADMIN only", () => {
    for (const r of ["ANALYST", "SUPPORT", "ADMIN", "SUPERADMIN"] as const) {
      expect(accessFor(r, "/admin/audit"), r).toBe("ok");
      expect(accessFor(r, "/api/bff/admin-export/audit"), r).toBe("ok");
    }
    for (const r of ["TSO", "DMO", "WM", "TOP", "SR", "AMO"] as const) expect(accessFor(r, "/admin/audit"), r).toBe("forbidden");
  });
});

describe("mock audit masking mirrors the server", () => {
  it("ADMIN sees every value except personal data and secret material, and the reason", () => {
    const m = maskAudit(row(), "ADMIN");
    expect(m.after).toEqual({ status: "disabled", full_name: MASK, designation: "SR", password_hash: MASK });
    expect(m.reason).toBe("Left the company");
  });
  it("ANALYST and SUPPORT see changed members only, allowlisted values only, and the reason masked", () => {
    for (const r of ["ANALYST", "SUPPORT"] as const) {
      const m = maskAudit(row(), r);
      expect(m.before).toEqual({ status: "active", full_name: MASK, password_hash: MASK }); // designation did not change
      expect(m.after).toEqual({ status: "disabled", full_name: MASK, password_hash: MASK });
      expect(m.reason).toBe(MASK);
    }
  });
  it("an entity without an allowlist shows names only (default deny)", () => {
    const m = maskAudit(row({ entity: "something_new" }), "ANALYST");
    expect(m.after).toEqual({ status: MASK, full_name: MASK, password_hash: MASK });
  });
  it("an id named after a phone is a reference, not personal data", () => {
    const m = maskAudit(row({ before: { phone_memo_uuid: "a" }, after: { phone_memo_uuid: "b" } }), "ADMIN");
    expect(m.after).toEqual({ phone_memo_uuid: "b" });
  });
});
