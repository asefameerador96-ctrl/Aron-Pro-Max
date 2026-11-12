// Loyalty programmes (portal-loyalty-web, D-LOY-WEB-01): role matrix, module wiring, menu, exact decimal / integer helpers, form checks
// and the display text. Pure logic; the BFF against the mock is in loyalty-bff.test.ts.
import { describe, expect, it } from "vitest";
import { moduleOfApiPath, moduleOfPage, canSee } from "@/lib/admin/modules";
import { GRANT_MODULES, MODULE_KEYS } from "@/lib/admin/access-grants";
import { accessFor, ADMIN_PATH_ROLES } from "@/lib/auth/roles";
import { MENU, menuFor } from "@/lib/menu/menu";
import { t } from "@/lib/i18n";
import {
  activationBlocked, basisUnit, canActivateProgramme, canReadLoyalty, canWriteGifts, canWriteLoyalty, cashValueMtk, decimal3Text, decimal3Wire, emptyEnrolForm, emptyForm, emptyGiftForm, emptyTier,
  formFromProgramme, guardedPercent, loyaltyAccess, nextAttempt, nextProgrammeStatuses, parseDecimal3, parsePoints, remainingStd, validateEnrol, validateForm, validateGift, type LoyaltyForm, type Programme,
} from "@/lib/admin/loyalty";
import { channelLabel, groupedDecimal, kindLabel, nameOf, percentText, pointsText, programmePeriod, statusLabel, stdText, thresholdText, tierName } from "@/lib/admin/loyalty-view";

const U = "0b6d6c0e-8a5b-4d56-9f0a-000000000001";
const good = (over: Partial<LoyaltyForm> = {}): LoyaltyForm => ({ ...emptyForm(), code: "ASTHA-Q1", name_en: "Astha Q1", active_from: "2027-01-01", active_to: "2027-03-31", reason: "Owner approved the quarter", ...over });
const codes = (r: { errors: { field: string; code: string }[] }) => r.errors.map((e) => `${e.field}:${e.code}`);

describe("role matrix and wiring", () => {
  it("SUPERADMIN reads and writes; ADMIN by grant; every other role has nothing", () => {
    expect(loyaltyAccess("SUPERADMIN", null)).toBe("w");
    expect(loyaltyAccess("ADMIN", { loyalty: "r" })).toBe("r");
    expect(loyaltyAccess("ADMIN", { loyalty: "w" })).toBe("w");
    expect(loyaltyAccess("ADMIN", { discounts: "w" })).toBe("none");
    expect(loyaltyAccess("ADMIN", null)).toBe("w"); // no module filter: grants not deployed yet (accessOf)
    for (const r of ["NATIONAL", "BRAND", "WM", "DMO", "TSO", "SR", "AMO", "SUPPORT", "ANALYST", "TOP", undefined] as const) {
      expect(canReadLoyalty(r, { loyalty: "w" }), String(r)).toBe(false);
      expect(canWriteLoyalty(r, { loyalty: "w" }), String(r)).toBe(false);
    }
    expect(canReadLoyalty("ADMIN", { loyalty: "r" })).toBe(true);
    expect(canWriteLoyalty("ADMIN", { loyalty: "r" })).toBe(false);
  });
  it("activation and the gift catalogue are Super Admin only (Q3)", () => {
    expect(canActivateProgramme("SUPERADMIN")).toBe(true);
    expect(canActivateProgramme("ADMIN")).toBe(false);
    expect(canWriteGifts("SUPERADMIN")).toBe(true);
    expect(canWriteGifts("ADMIN")).toBe(false);
  });
  it("status moves: draft -> active only for the Super Admin; active -> inactive for a writer; ended has none", () => {
    expect(nextProgrammeStatuses("draft", "SUPERADMIN", null)).toEqual(["active"]);
    expect(nextProgrammeStatuses("draft", "ADMIN", { loyalty: "w" })).toEqual([]);
    expect(nextProgrammeStatuses("active", "ADMIN", { loyalty: "w" })).toEqual(["inactive"]);
    expect(nextProgrammeStatuses("active", "ADMIN", { loyalty: "r" })).toEqual([]);
    expect(nextProgrammeStatuses("inactive", "SUPERADMIN", null)).toEqual([]);
  });
  it("the module is granted, labelled and routed: pages, API paths (re-homed off targets), menu, path roles", () => {
    expect(GRANT_MODULES).toContain("loyalty");
    expect(MODULE_KEYS.loyalty).toBe("grant.module.loyalty");
    expect(moduleOfPage("/admin/loyalty")).toBe("loyalty");
    expect(moduleOfPage("/admin/loyalty/12/edit")).toBe("loyalty");
    for (const p of ["/v1/admin/programmes", "/v1/admin/programmes/3/enrolments", "/v1/admin/gifts", "/v1/admin/gifts/4", "/v1/admin/loyalty/programmes/3/status"]) expect(moduleOfApiPath(p), p).toBe("loyalty");
    expect(moduleOfApiPath("/v1/programmes/loyalty-balances")).not.toBe("loyalty"); // the phone surface stays on targets while it is 501
    expect(ADMIN_PATH_ROLES.find((o) => o.prefix === "/admin/loyalty")?.roles).toEqual(["ADMIN", "SUPERADMIN"]);
    expect(accessFor("SUPERADMIN", "/admin/loyalty")).toBe("ok");
    expect(accessFor("ADMIN", "/api/bff/admin/loyalty/5/status")).toBe("ok");
    for (const r of ["SUPPORT", "NATIONAL", "BRAND", "WM", "DMO", "TSO", "SR", "AMO"] as const) expect(accessFor(r, "/admin/loyalty"), r).toBe("forbidden");
  });
  it("the page is not listed for an ADMIN without the grant, and is for SUPERADMIN and a granted ADMIN", () => {
    const hrefs = (role: "ADMIN" | "SUPERADMIN", grants: Parameters<typeof menuFor>[3]) => menuFor(role, MENU, null, grants).flatMap((s) => s.items.map((i) => i.href));
    expect(hrefs("SUPERADMIN", null)).toContain("/admin/loyalty");
    expect(hrefs("ADMIN", { loyalty: "r" })).toContain("/admin/loyalty");
    expect(hrefs("ADMIN", { discounts: "w" })).not.toContain("/admin/loyalty");
    expect(canSee({ discounts: "w" }, moduleOfPage("/admin/loyalty"))).toBe(false);
  });
});

describe("exact numbers (BigInt, no float)", () => {
  it("Decimal3 parses exactly, up to 13 whole digits, never negative", () => {
    expect(parseDecimal3("1500")).toBe(1_500_000n);
    expect(parseDecimal3("1500.5")).toBe(1_500_500n);
    expect(parseDecimal3("0.001")).toBe(1n);
    expect(parseDecimal3("১২৩.৪৫")).toBe(123_450n); // Bangla digits
    expect(parseDecimal3("9999999999999.999")).toBe(9_999_999_999_999_999n); // beyond 2^53: BigInt keeps it
    for (const bad of ["", "-1", "-0.5", "1.2345", "1,5", "abc", "1e3", "12345678901234", ".5", "5."]) expect(parseDecimal3(bad), bad).toBeNull();
  });
  it("wire and short text round-trip", () => {
    expect(decimal3Wire(1_500_500n)).toBe("1500.500");
    expect(decimal3Wire(0n)).toBe("0.000");
    expect(decimal3Text(1_500_500n)).toBe("1500.5");
    expect(decimal3Text(2_000_000n)).toBe("2000");
    expect(decimal3Text(1n)).toBe("0.001");
  });
  it("percent is guarded: a zero or negative target gives none (the -37,500% bug), never NaN or Infinity", () => {
    expect(guardedPercent("3000.000", "6000.000")).toBe("50.00");
    expect(guardedPercent("1750.125", "3500.250")).toBe("50.00");
    expect(guardedPercent("1", "3")).toBe("33.33"); // floored
    expect(guardedPercent("7000", "6000")).toBe("116.66"); // over target is shown
    expect(guardedPercent("400", "0.000")).toBeNull();
    expect(guardedPercent("400", "-100")).toBeNull();
    expect(guardedPercent("x", "100")).toBeNull();
    expect(guardedPercent("0", "100")).toBe("0.00");
  });
  it("remaining never goes below zero", () => {
    expect(remainingStd("3000", "6000")).toBe("3000");
    expect(remainingStd("7000", "6000")).toBe("0");
    expect(remainingStd("1.5", "2")).toBe("0.5");
    expect(remainingStd("bad", "2")).toBeNull();
  });
  it("cash value is points x milli-taka per point in BigInt text; invalid inputs give none", () => {
    expect(cashValueMtk(199, 2000)).toBe("398000"); // Tk 398
    expect(cashValueMtk(0, 2000)).toBe("0");
    expect(cashValueMtk(Number.MAX_SAFE_INTEGER, 2000)).toBe((BigInt(Number.MAX_SAFE_INTEGER) * 2000n).toString());
    expect(cashValueMtk(1.5, 2000)).toBeNull();
    expect(cashValueMtk(-1, 2000)).toBeNull();
    expect(cashValueMtk(5, 0.1)).toBeNull();
  });
  it("points cost is an integer 1..100000 or empty", () => {
    expect(parsePoints("")).toBeNull();
    expect(parsePoints(" 200 ")).toBe(200);
    expect(parsePoints("২০০")).toBe(200);
    expect(parsePoints("100000")).toBe(100_000);
    for (const bad of ["0", "100001", "1.5", "-3", "abc", "1234567"]) expect(parsePoints(bad), bad).toBe("invalid");
  });
  it("the threshold unit word is STD as stored; a field unit uses the category word (packet, box, dozen)", () => {
    expect(basisUnit("std")).toBe("std");
    expect(basisUnit("pack", "cigarette")).toBe("packet");
    expect(basisUnit("pack", "bidi")).toBe("packet");
    expect(basisUnit("pack", "lighter")).toBe("box");
    expect(basisUnit("dozen", "match")).toBe("dozen");
  });
  it("activation is blocked after the end date (Asia/Dhaka date compared as text)", () => {
    expect(activationBlocked("2026-11-10", "2026-11-11")).toBe(true);
    expect(activationBlocked("2026-11-11", "2026-11-11")).toBe(false);
  });
});

describe("programme form to the wire body", () => {
  it("a good form maps to ProgrammeWrite with integer-exact STD thresholds and the request_uuid", () => {
    const r = validateForm(good({ tiers: [{ tier_code: "gold", label_en: "Gold", label_bn: "", threshold: "3000.5" }, { tier_code: "platinum", label_en: "Platinum", label_bn: "প্ল্যাটিনাম", threshold: "5000" }], channels: ["Astha"] }), "create", U);
    expect(r.errors).toEqual([]);
    expect(r.body).toEqual({
      request_uuid: U, kind: "astha", code: "ASTHA-Q1", name_en: "Astha Q1", name_bn: null, period_label: null, active_from: "2027-01-01", active_to: "2027-03-31", channels: ["Astha"], change_reason: "Owner approved the quarter",
      tiers: [{ tier_code: "gold", label_en: "Gold", label_bn: null, threshold_value: "3000.500", threshold_basis: "std" }, { tier_code: "platinum", label_en: "Platinum", label_bn: "প্ল্যাটিনাম", threshold_value: "5000.000", threshold_basis: "std" }],
    });
  });
  it("an empty threshold stays null: nothing is invented", () => {
    const r = validateForm(good({ tiers: [{ ...emptyTier(), tier_code: "silver", label_en: "Silver" }] }), "create", U);
    expect(r.body?.tiers[0]?.threshold_value).toBeNull();
  });
  it("required fields, patterns, lengths and the date range", () => {
    expect(codes(validateForm(emptyForm(), "create", U))).toEqual(["code:required", "name_en:required", "active_from:required", "active_to:required", "reason:reason_short"]);
    expect(codes(validateForm(good({ code: "-bad" }), "create", U))).toEqual(["code:pattern"]);
    expect(codes(validateForm(good({ name_en: "x".repeat(121) }), "create", U))).toEqual(["name_en:too_long"]);
    expect(codes(validateForm(good({ name_bn: "ক".repeat(121) }), "create", U))).toEqual(["name_bn:too_long"]);
    expect(codes(validateForm(good({ period_label: "p".repeat(41) }), "create", U))).toEqual(["period_label:too_long"]);
    expect(codes(validateForm(good({ active_to: "2026-12-31" }), "create", U))).toEqual(["active_to:range"]);
    expect(codes(validateForm(good({ active_from: "2027-02-30" }), "create", U))).toEqual(["active_from:invalid"]);
    expect(codes(validateForm(good({ reason: "short" }), "create", U))).toEqual(["reason:reason_short"]);
    expect(codes(validateForm(good({ reason: "r".repeat(501) }), "create", U))).toEqual(["reason:reason_long"]);
  });
  it("on edit the code is not re-checked (it cannot change)", () => {
    expect(validateForm(good({ code: "" }), "edit", U).errors).toEqual([]);
  });
  it("tiers: codes unique and well formed, thresholds rising, a threshold must be an exact non-negative decimal", () => {
    const tier = (tier_code: string, threshold: string) => ({ tier_code, label_en: tier_code, label_bn: "", threshold });
    expect(codes(validateForm(good({ tiers: [tier("gold", "10"), tier("gold", "20")] }), "create", U))).toEqual(["tier.1.tier_code:duplicate"]);
    expect(codes(validateForm(good({ tiers: [tier("Gold", "10")] }), "create", U))).toEqual(["tier.0.tier_code:pattern"]);
    expect(codes(validateForm(good({ tiers: [tier("a", "10")] }), "create", U))).toEqual(["tier.0.tier_code:pattern"]);
    expect(codes(validateForm(good({ tiers: [tier("gold", "20"), tier("silver", "20")] }), "create", U))).toEqual(["tier.1.threshold:not_rising"]);
    expect(codes(validateForm(good({ tiers: [tier("gold", "20"), tier("silver", "10")] }), "create", U))).toEqual(["tier.1.threshold:not_rising"]);
    expect(codes(validateForm(good({ tiers: [tier("gold", "-5")] }), "create", U))).toEqual(["tier.0.threshold:invalid"]);
    expect(codes(validateForm(good({ tiers: [tier("gold", "1.2345")] }), "create", U))).toEqual(["tier.0.threshold:invalid"]);
    expect(codes(validateForm(good({ tiers: [tier("gold", "10"), tier("silver", ""), tier("bronze", "5")] }), "create", U))).toEqual(["tier.2.threshold:not_rising"]); // an empty one is skipped, the order still rises
    expect(validateForm(good({ tiers: [{ tier_code: "gold", label_en: "", label_bn: "", threshold: "" }] }), "create", U).errors).toEqual([{ field: "tier.0.label_en", code: "required" }]);
    expect(codes(validateForm(good({ tiers: Array.from({ length: 21 }, (_, i) => tier(`t${i}x`, String(i + 1))) }), "create", U))).toContain("tiers:too_many");
  });
  it("a programme opens into the form with thresholds as short text and the reason empty", () => {
    const p: Programme = { programme_id: 1, kind: "astha", code: "A", name_en: "A", name_bn: null, period_label: "2026-Q4", active_from: "2026-10-01", active_to: "2026-12-31", status: "draft", version: 3, channels: ["GT"], tiers: [{ tier_code: "gold", label_en: "Gold", label_bn: null, threshold_value: "3000.500", threshold_basis: "std" }, { tier_code: "silver", label_en: "Silver", label_bn: "সিলভার", threshold_value: null, threshold_basis: "std" }] };
    const f = formFromProgramme(p);
    expect(f.tiers).toEqual([{ tier_code: "gold", label_en: "Gold", label_bn: "", threshold: "3000.5" }, { tier_code: "silver", label_en: "Silver", label_bn: "সিলভার", threshold: "" }]);
    expect(f.reason).toBe("");
    expect(f.channels).toEqual(["GT"]);
    // opening and saving again changes nothing on the wire
    expect(validateForm({ ...f, reason: "Same programme saved again" }, "edit", U).body?.tiers).toEqual(p.tiers);
  });
});

describe("gift and enrolment forms", () => {
  const gift = (over = {}) => ({ ...emptyGiftForm("7"), code: "chair", name_en: "Chair", points: "200", reason: "Owner approved the gift", ...over });
  it("a gift maps to GiftWrite; points stay an integer; empty points mean an Astha gift", () => {
    expect(validateGift(gift(), "create", U).body).toEqual({ request_uuid: U, programme_id: 7, code: "chair", name_en: "Chair", name_bn: null, points_cost: 200, tier_codes: [], status: "active", change_reason: "Owner approved the gift" });
    expect(validateGift(gift({ points: "" }), "create", U).body?.points_cost).toBeNull();
  });
  it("gift checks: programme, code pattern, points range, name, reason", () => {
    expect(codes(validateGift(emptyGiftForm(), "create", U))).toEqual(["programme_id:required", "code:required", "name_en:required", "reason:reason_short"]);
    expect(codes(validateGift(gift({ code: "Chair" }), "create", U))).toEqual(["code:pattern"]);
    expect(codes(validateGift(gift({ points: "0" }), "create", U))).toEqual(["points:range"]);
    expect(codes(validateGift(gift({ points: "100001" }), "create", U))).toEqual(["points:range"]);
    expect(codes(validateGift(gift({ points: "2.5" }), "create", U))).toEqual(["points:range"]);
    expect(validateGift(gift({ code: "" }), "edit", U).errors).toEqual([]);
  });
  const enrol = (over = {}) => ({ ...emptyEnrolForm(), outlet_id: "101", valid_from: "2027-01-01", reason: "Owner enrolled the outlet", ...over });
  it("an enrolment maps to one row; the target is an exact non-negative Decimal3", () => {
    expect(validateEnrol(enrol({ tier_code: "gold", base_target: "3500.25", valid_to: "2027-03-31" }), 5, U).body).toEqual({
      batch_uuid: U, change_reason: "Owner enrolled the outlet",
      rows: [{ programme_id: 5, outlet_id: 101, league_label: null, tier_code: "gold", base_target: "3500.250", valid_from: "2027-01-01", valid_to: "2027-03-31" }],
    });
    expect(validateEnrol(enrol(), 5, U).body?.rows[0]?.base_target).toBeNull();
  });
  it("a negative or malformed target, a bad outlet, bad dates and a short reason are refused", () => {
    expect(codes(validateEnrol(enrol({ base_target: "-37500" }), 5, U))).toEqual(["base_target:invalid"]);
    expect(codes(validateEnrol(enrol({ base_target: "1.2345" }), 5, U))).toEqual(["base_target:invalid"]);
    expect(codes(validateEnrol(enrol({ outlet_id: "0" }), 5, U))).toEqual(["outlet_id:invalid"]);
    expect(codes(validateEnrol(enrol({ outlet_id: "" }), 5, U))).toEqual(["outlet_id:required"]);
    expect(codes(validateEnrol(enrol({ valid_from: "" }), 5, U))).toEqual(["valid_from:required"]);
    expect(codes(validateEnrol(enrol({ valid_to: "2026-12-31" }), 5, U))).toEqual(["valid_to:range"]);
    expect(codes(validateEnrol(enrol({ reason: "no" }), 5, U))).toEqual(["reason:reason_short"]);
  });
});

describe("request_uuid per submission", () => {
  it("the same body keeps the uuid, a changed body gets a new one", () => {
    let n = 0;
    const make = () => `u${++n}`;
    const a = nextAttempt(null, "k1", make);
    expect(nextAttempt(a, "k1", make)).toBe(a);
    expect(nextAttempt(a, "k2", make).uuid).toBe("u2");
  });
});

describe("display text (en and bn)", () => {
  it("status, kind, channel and name by locale; English money reads Tk", () => {
    expect(statusLabel("en", "inactive")).toBe("Ended");
    expect(statusLabel("bn", "draft")).toBe("খসড়া");
    expect(kindLabel("en", "diamond_league")).toBe("Diamond League");
    expect(kindLabel("bn", "astha")).toBe("আস্থা");
    expect(channelLabel("en", "HoReCa")).toBe("HoReCa");
    expect(channelLabel("en", "ZZ")).toBe("ZZ");
    expect(nameOf("bn", { name_en: "A", name_bn: "ক" })).toBe("ক");
    expect(nameOf("bn", { name_en: "A", name_bn: null })).toBe("A");
    expect(t("en", "loy.cash_value", { amount: "398" })).toBe("Tk 398");
    expect(t("bn", "loy.cash_value", { amount: "398" })).toBe("৳398");
  });
  it("STD figures carry their unit word, grouped, in the UI digits", () => {
    expect(stdText("en", "3000.500")).toBe("3,000.5 STD");
    expect(stdText("bn", "5000.000")).toBe("৫,০০০ এসটিডি");
    expect(stdText("en", null)).toBe("—");
    expect(stdText("en", "bad")).toBe("—");
    expect(groupedDecimal("en", "1234567.25")).toBe("1,234,567.25");
    expect(thresholdText("en", { tier_code: "a", label_en: "A", label_bn: null, threshold_value: null, threshold_basis: "std" })).toBe("Not set");
    expect(thresholdText("en", { tier_code: "a", label_en: "A", label_bn: null, threshold_value: "10.000", threshold_basis: "std" })).toBe("10 STD");
    expect(tierName("bn", { label_en: "Gold", label_bn: "গোল্ড" })).toBe("গোল্ড");
    expect(tierName("bn", { label_en: "Gold", label_bn: null })).toBe("Gold");
  });
  it("points and percent", () => {
    expect(pointsText("en", 1200)).toBe("1,200 points");
    expect(pointsText("bn", 1200)).toContain("পয়েন্ট");
    expect(percentText("en", "50.00")).toBe("50.00%");
    expect(percentText("bn", "50.00")).toBe("৫০.০০%");
    expect(percentText("en", null)).toBe("—");
    expect(programmePeriod({ period_label: null, active_from: "2026-10-01", active_to: "2026-12-31" })).toBe("2026-10-01 – 2026-12-31");
    expect(programmePeriod({ period_label: "2026-Q4", active_from: "x", active_to: "y" })).toBe("2026-Q4");
  });
});
