import { describe, expect, it } from "vitest";
import { bn } from "@/lib/i18n/messages-bn";
import { en } from "@/lib/i18n/messages-en";
import { ALL_ROLES } from "@/lib/auth/roles";
import { problemMessage, t } from "@/lib/i18n";
import type { MessageKey } from "@/lib/i18n";

// Contract 1.4.0 condition (lead, 2026-10-22): a role or problem code this build does not know must never break the page.
describe("contract 1.4.0 role and problem code tolerance", () => {
  it("labels every contract role, NATIONAL and BRAND included, in both languages", () => {
    for (const r of ALL_ROLES) {
      expect((en as Record<string, string>)[`role.${r}`], r).toBeTruthy();
      expect((bn as Record<string, string>)[`role.${r}`], r).toBeTruthy();
    }
    expect(ALL_ROLES).toContain("NATIONAL");
    expect(ALL_ROLES).toContain("BRAND");
  });

  it("shows the portal refusal in Bangla and English and falls back for a code it does not know", () => {
    expect(problemMessage("en", "ERR_AUTH_PORTAL_NOT_ALLOWED")).toMatch(/field app/);
    expect(problemMessage("bn", "ERR_AUTH_PORTAL_NOT_ALLOWED")).not.toBe(problemMessage("bn", "ERR_SOME_FUTURE_CODE"));
    expect(problemMessage("en", "ERR_SOME_FUTURE_CODE")).toBe(t("en", "error.generic"));
  });

  it("does not throw on an unknown role label", () => {
    expect(() => t("bn", "role.SOME_FUTURE_ROLE" as MessageKey)).not.toThrow();
  });
});
