// Checker: Bangla catalogue values for the dashboard page rows must be Bangla, not English.
import { describe, expect, it } from "vitest";
import { dashEn } from "@/lib/i18n/messages-dash-en";
import { dashBn } from "@/lib/i18n/messages-dash-bn";

const BN = /[ঀ-৿]/;
describe("dash catalogues (pages)", () => {
  it("no Bangla value is English text", () => {
    const en = dashEn as Record<string, string>;
    const bn = dashBn as Record<string, string>;
    const bad = Object.keys(en).filter((k) => !BN.test(bn[k] ?? "") && /[A-Za-z]{3,}/.test(bn[k] ?? "x"));
    expect(bad).toEqual([]);
  });
  it("bn differs from en for page keys", () => {
    const en = dashEn as Record<string, string>;
    const bn = dashBn as Record<string, string>;
    const same = Object.keys(en).filter((k) => /^(tracking|exceptions|leave|routes|tutorial|products|tile|dashboard)\./.test(k) && en[k] === bn[k] && /[A-Za-z]{3,}/.test(en[k]!));
    expect(same).toEqual([]);
  });
});
