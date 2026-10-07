import { describe, expect, it } from "vitest";
import { bn } from "@/lib/i18n/messages-bn";
import { en } from "@/lib/i18n/messages-en";
import { ALL_ROLES } from "@/lib/auth/roles";
import { businessDate, formatBusinessDate, formatDateTime, formatNumber, problemMessage, t } from "@/lib/i18n";

const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();
const BENGALI = /[ঀ-৿]/;

describe("catalogues", () => {
  it("bn and en have exactly the same keys", () => {
    expect(Object.keys(bn).sort()).toEqual(Object.keys(en).sort());
  });
  it("every Bangla value is non-empty, uses the same placeholders, and (except brand/names) is Bangla script", () => {
    const keep = new Set(["app.name", "common.language.en"]);
    for (const [k, v] of Object.entries(bn)) {
      expect(v.trim(), k).not.toBe("");
      expect(placeholders(v), k).toEqual(placeholders(en[k as keyof typeof en]));
      if (!keep.has(k)) expect(BENGALI.test(v), `${k} is not Bangla: ${v}`).toBe(true);
    }
  });
  it("has a label for every contract role", () => {
    for (const r of ALL_ROLES) expect(en[`role.${r}` as keyof typeof en], r).toBeTruthy();
  });
  it("interpolates and localises numbers", () => {
    expect(t("en", "admin.new", { entity: "cluster" })).toBe("New cluster");
    expect(t("bn", "admin.new", { entity: "ক্লাস্টার" })).toBe("নতুন ক্লাস্টার");
    expect(formatNumber("bn", 1234)).toBe("১,২৩৪");
    expect(formatNumber("en", 1234)).toBe("1,234");
  });
  it("formats instants and business dates in Asia/Dhaka (UTC+6)", () => {
    expect(businessDate(new Date("2026-10-05T18:30:00Z"))).toBe("2026-10-06"); // 00:30 next day in Dhaka
    expect(businessDate(new Date("2026-10-05T17:59:00Z"))).toBe("2026-10-05");
    expect(formatBusinessDate("bn", "2026-10-05")).toMatch(/২০২৬/);
    expect(formatDateTime("en", "2026-10-05T18:30:00.000Z")).toMatch(/6 Oct 2026, 00:30/);
  });
  it("maps problem codes to messages and falls back to the generic text", () => {
    expect(problemMessage("en", "ERR_AUTH_INVALID_CREDENTIALS")).toBe("Wrong User ID or password.");
    expect(problemMessage("bn", "ERR_SOMETHING_NEW")).toBe(bn["error.generic"]);
    expect(problemMessage("en", undefined)).toBe(en["error.generic"]);
  });
});
