import { describe, expect, it } from "vitest";
import { GRANT_MODULES } from "@/lib/admin/access-grants";
import { moduleOfApiPath, moduleOfPage } from "@/lib/admin/modules";
import {
  activationBlocked, bpText, canActivateOffer, canReadOffers, canWriteOffers, emptyForm, fieldQtyToBase, formFromOffer, formatBp, mtkText, nextStatuses, offerAccess, percentToBp, perFieldTakaToPerBase, splitBase, toPatch, unitCtx, validateForm,
  type Offer, type OfferForm, type OfferSkuUnit,
} from "@/lib/admin/offers";
import { amountPerUnitText, qtyText, valueText } from "@/lib/admin/offers-view";
import { menuFor } from "@/lib/menu/menu";

const P20: OfferSkuUnit = { sku_id: 2, base_unit: "stick", pack_factor: 20, field_unit: "packet" };
const P10: OfferSkuUnit = { sku_id: 1, base_unit: "stick", pack_factor: 10, field_unit: "packet" };
const DOZ: OfferSkuUnit = { sku_id: 9, base_unit: "dozen", pack_factor: 1, field_unit: "dozen" };
const BOX: OfferSkuUnit = { sku_id: 8, base_unit: "piece", pack_factor: 25, field_unit: "box" };

const base = (over: Partial<OfferForm> = {}): OfferForm => ({ ...emptyForm(), code: "RAM-1", title_en: "Ramadan", valid_from: "2026-11-01", valid_to: "2026-11-30", reason: "Owner asked for this offer", qualifying: [{ product_level: "sku", product_id: 2 }], ...over });
const fieldsOf = (r: ReturnType<typeof validateForm>) => r.errors.map((e) => `${e.field}:${e.code}`);

describe("role matrix (one place)", () => {
  it("SUPERADMIN reads and writes; ADMIN follows the discounts grant; every other role sees nothing", () => {
    expect(offerAccess("SUPERADMIN", null)).toBe("w");
    expect(offerAccess("ADMIN", { discounts: "r" })).toBe("r");
    expect(offerAccess("ADMIN", { discounts: "w" })).toBe("w");
    expect(offerAccess("ADMIN", { prices: "w" })).toBe("none");
    expect(offerAccess("ADMIN", null)).toBe("w"); // no module filter deployed: the server stays the authority
    for (const r of ["NATIONAL", "BRAND", "WM", "DMO", "TSO", "SR", "AMO", "SUPPORT", "ANALYST", "TOP"] as const) {
      expect(canReadOffers(r, null)).toBe(false);
      expect(canWriteOffers(r, null)).toBe(false);
    }
    expect(canReadOffers("ADMIN", { discounts: "r" })).toBe(true);
    expect(canWriteOffers("ADMIN", { discounts: "r" })).toBe(false);
  });
  it("only a Super Admin activates a draft; an ADMIN with write may end an active programme", () => {
    expect(canActivateOffer("SUPERADMIN")).toBe(true);
    expect(canActivateOffer("ADMIN")).toBe(false);
    expect(nextStatuses("draft", "SUPERADMIN", null)).toEqual(["active"]);
    expect(nextStatuses("draft", "ADMIN", { discounts: "w" })).toEqual([]);
    expect(nextStatuses("active", "ADMIN", { discounts: "w" })).toEqual(["inactive"]);
    expect(nextStatuses("active", "ADMIN", { discounts: "r" })).toEqual([]);
    expect(nextStatuses("inactive", "SUPERADMIN", null)).toEqual([]);
  });
  it("the module is registered, mapped to its pages and API, and hidden from the menu without a grant", () => {
    expect(GRANT_MODULES).toContain("discounts");
    expect(moduleOfPage("/admin/discounts")).toBe("discounts");
    expect(moduleOfPage("/admin/discounts/12/edit")).toBe("discounts");
    expect(moduleOfApiPath("/v1/admin/offers")).toBe("discounts");
    expect(moduleOfApiPath("/v1/admin/offers/7/status")).toBe("discounts");
    const ids = (role: Parameters<typeof menuFor>[0], grants: Parameters<typeof menuFor>[3]) => menuFor(role, undefined, null, grants).flatMap((s) => s.items.map((i) => i.id));
    expect(ids("SUPERADMIN", null)).toContain("admin-discounts");
    expect(ids("ADMIN", { discounts: "r" })).toContain("admin-discounts");
    expect(ids("ADMIN", { prices: "w" })).not.toContain("admin-discounts");
    for (const r of ["NATIONAL", "BRAND", "WM", "DMO", "TSO", "SUPPORT"] as const) expect(ids(r, null)).not.toContain("admin-discounts");
  });
});

describe("integer money, percent and quantity", () => {
  it("basis points are shown as a percentage with two decimals from integers", () => {
    expect(bpText(1)).toBe("0.01");
    expect(bpText(250)).toBe("2.50");
    expect(bpText(10000)).toBe("100.00");
    expect(formatBp("bn", 1250)).toBe("১২.৫০%");
    expect(percentToBp("12.5")).toBe(1250);
    expect(percentToBp("০.০১")).toBe(1);
    expect(percentToBp("100")).toBe(10000);
    for (const bad of ["0", "100.01", "-1", "1.234", "abc", "", "1,5"]) expect(percentToBp(bad), bad).toBeNull();
  });
  it("milli-taka text is exact", () => {
    expect(mtkText(0)).toBe("0");
    expect(mtkText(100)).toBe("0.1");
    expect(mtkText(2500)).toBe("2.5");
    expect(mtkText(7935)).toBe("7.935");
    expect(mtkText(9_007_199_254_740_991)).toBe("9007199254740.991");
  });
  it("a field quantity becomes base units by the pack factor, and splits back", () => {
    expect(fieldQtyToBase("3", 20)).toBe(60);
    expect(fieldQtyToBase("৩", 20)).toBe(60);
    expect(fieldQtyToBase("2", 25)).toBe(50);
    expect(fieldQtyToBase("0", 20)).toBeNull();
    expect(fieldQtyToBase("1.5", 20)).toBeNull();
    expect(fieldQtyToBase("", 20)).toBeNull();
    expect(splitBase(65, 20)).toEqual({ packs: 3, rest: 5 });
  });
  it("a per-packet amount must divide exactly into base units (nothing is rounded)", () => {
    expect(perFieldTakaToPerBase("2", 20)).toBe(100);
    expect(perFieldTakaToPerBase("0.100", 10)).toBe(10);
    expect(perFieldTakaToPerBase("0.001", 20)).toBeNull();
    expect(perFieldTakaToPerBase("0", 20)).toBeNull();
    expect(perFieldTakaToPerBase("abc", 20)).toBeNull();
  });
  it("units are common only when every SKU counts the same way", () => {
    expect(unitCtx([P20, { ...P20, sku_id: 3 }])).toEqual({ packFactor: 20, fieldUnit: "packet", baseUnit: "stick" });
    expect(unitCtx([P20, P10])).toBeNull();
    expect(unitCtx([])).toBeNull();
  });
});

describe("form to contract", () => {
  const q20 = unitCtx([P20]);
  it("builds an OfferWrite for a price-off per packet: taka per packet becomes milli-taka per stick", () => {
    const r = validateForm(base({ offer_type: "amount_per_unit", perUnit: "2" }), { qualifying: q20, reward: null }, "create");
    expect(r.errors).toEqual([]);
    expect(r.body).toMatchObject({ code: "RAM-1", group_code: "RAM-1", offer_type: "amount_per_unit", discount_mtk_per_unit: 100, discount_bp: null, threshold_qty_base: null, title_bn: null, change_reason: "Owner asked for this offer" });
  });
  it("builds a percentage and a quantity offer; boxes and dozens use their pack factor", () => {
    expect(validateForm(base({ offer_type: "pct_discount", percent: "12.5", group_code: "RAMADAN" }), { qualifying: q20, reward: null }, "create").body).toMatchObject({ discount_bp: 1250, group_code: "RAMADAN" });
    const free = validateForm(base({ offer_type: "free_qty", threshold: "3", rewardSku: "2", rewardQty: "1" }), { qualifying: q20, reward: unitCtx([P20]) }, "create");
    expect(free.body).toMatchObject({ threshold_qty_base: 60, reward_sku_id: 2, reward_qty_base: 20 });
    const box = validateForm(base({ offer_type: "free_qty", threshold: "2", rewardSku: "8", rewardQty: "1" }), { qualifying: unitCtx([BOX]), reward: unitCtx([BOX]) }, "create");
    expect(box.body).toMatchObject({ threshold_qty_base: 50, reward_qty_base: 25 });
    const doz = validateForm(base({ offer_type: "free_qty", threshold: "5", rewardSku: "9", rewardQty: "1" }), { qualifying: unitCtx([DOZ]), reward: unitCtx([DOZ]) }, "create");
    expect(doz.body).toMatchObject({ threshold_qty_base: 5, reward_qty_base: 1 });
  });
  it("with mixed or unknown packs the amounts are per base unit", () => {
    const r = validateForm(base({ offer_type: "amount_per_unit", perUnit: "0.1" }), { qualifying: null, reward: null }, "create");
    expect(r.body?.discount_mtk_per_unit).toBe(100);
  });
  it("refuses the same things the contract and the V0003 CHECKs refuse", () => {
    const u = { qualifying: q20, reward: null };
    expect(fieldsOf(validateForm(base({ code: "" }), u, "create"))).toContain("code:required");
    expect(fieldsOf(validateForm(base({ code: "-bad" }), u, "create"))).toContain("code:pattern");
    expect(fieldsOf(validateForm(base({ code: "x".repeat(41) }), u, "create"))).toContain("code:pattern");
    expect(fieldsOf(validateForm(base({ title_en: " " }), u, "create"))).toContain("title_en:required");
    expect(fieldsOf(validateForm(base({ valid_from: "2026-11-30", valid_to: "2026-11-01" }), u, "create"))).toContain("valid_to:range");
    expect(fieldsOf(validateForm(base({ valid_to: "2026-02-30" }), u, "create"))).toContain("valid_to:invalid");
    expect(fieldsOf(validateForm(base({ valid_to: "" }), u, "create"))).toContain("valid_to:required");
    expect(validateForm(base({ valid_from: "2026-11-01", valid_to: "2026-11-01", offer_type: "pct_discount", percent: "1" }), u, "create").errors).toEqual([]); // one day is allowed
    expect(fieldsOf(validateForm(base({ reason: "too short" }), u, "create"))).toContain("reason:reason_short");
    expect(fieldsOf(validateForm(base({ reason: "x".repeat(501) }), u, "create"))).toContain("reason:reason_long");
    expect(fieldsOf(validateForm(base({ offer_type: "pct_discount", percent: "0" }), u, "create"))).toContain("percent:range");
    expect(fieldsOf(validateForm(base({ offer_type: "pct_discount", percent: "100.01" }), u, "create"))).toContain("percent:range");
    expect(fieldsOf(validateForm(base({ offer_type: "amount_per_unit", perUnit: "0.001" }), u, "create"))).toContain("perUnit:not_exact");
    expect(fieldsOf(validateForm(base({ offer_type: "amount_per_unit", perUnit: "x" }), u, "create"))).toContain("perUnit:invalid");
    expect(fieldsOf(validateForm(base({ offer_type: "free_qty" }), u, "create"))).toEqual(expect.arrayContaining(["threshold:required", "rewardSku:required", "rewardQty:required"]));
  });
  it("an edit does not ask for the code and the patch carries neither code nor group", () => {
    const r = validateForm(base({ code: "", offer_type: "pct_discount", percent: "5" }), { qualifying: q20, reward: null }, "edit");
    expect(r.errors).toEqual([]);
    const patch = toPatch(r.body!, "0b6d6c0e-8a5b-4d56-9f0a-3f1d3a4b5c6d");
    expect(patch).not.toHaveProperty("code");
    expect(patch).not.toHaveProperty("group_code");
    expect(patch).toMatchObject({ request_uuid: "0b6d6c0e-8a5b-4d56-9f0a-3f1d3a4b5c6d", discount_bp: 500, change_reason: "Owner asked for this offer" });
  });
  it("an existing offer round-trips through the form in the field unit", () => {
    const offer = { id: 1, code: "A", group_code: "G", offer_type: "amount_per_unit", level: "line", title_en: "T", title_bn: "টি", valid_from: "2026-11-01", valid_to: "2026-11-30", discount_mtk_per_unit: 100, qualifying: [{ product_level: "sku", product_id: 2 }], scope: [{ node_type: "wing", node_id: 1 }], stacking: "exclusive", status: "draft", version: 1 } as Offer;
    const f = formFromOffer(offer, { qualifying: q20, reward: null });
    expect(f.perUnit).toBe("2");
    expect(f.scope).toEqual([{ node_type: "wing", node_id: 1 }]);
    const again = validateForm({ ...f, reason: "Round trip of the same rule" }, { qualifying: q20, reward: null }, "edit");
    expect(again.body?.discount_mtk_per_unit).toBe(100);
  });
  it("activation is blocked only when the end date is before today (Asia/Dhaka date)", () => {
    expect(activationBlocked("2026-11-10", "2026-11-11")).toBe(true);
    expect(activationBlocked("2026-11-11", "2026-11-11")).toBe(false);
  });
});

describe("display", () => {
  const offer = (o: Partial<Offer>): Offer => ({ id: 1, code: "A", group_code: "A", offer_type: "pct_discount", level: "line", title_en: "T", valid_from: "2026-11-01", valid_to: "2026-11-30", qualifying: [{ product_level: "sku", product_id: 2 }], scope: [], stacking: "exclusive", status: "draft", version: 1, sku_units: [P20], ...o }) as Offer;
  it("quantities show the field unit with the base figure alongside", () => {
    const u = unitCtx([P20]);
    expect(qtyText("en", 60, u)).toBe("3 packet(s) (60 stick(s))");
    expect(qtyText("en", 65, u)).toBe("3 packet(s) + 5 stick(s) (65 stick(s))");
    expect(qtyText("en", 5, u)).toBe("5 stick(s) (5 stick(s))");
    expect(qtyText("en", 7, null)).toBe("7 base unit(s)");
    expect(qtyText("en", 5, unitCtx([DOZ]))).toBe("5 dozen");
    expect(qtyText("bn", 60, u)).toContain("৩ প্যাকেট");
  });
  it("money is exact taka per field unit; percent is from basis points", () => {
    expect(amountPerUnitText("en", 100, unitCtx([P20]))).toBe("2.000");
    expect(amountPerUnitText("en", 100, null)).toBe("0.100");
    expect(valueText("en", offer({ discount_bp: 1250 }))).toBe("12.50% off");
    expect(valueText("en", offer({ offer_type: "amount_per_unit", discount_mtk_per_unit: 100 }))).toBe("৳2.000 off per packet");
    expect(valueText("en", offer({ offer_type: "amount_per_unit", discount_mtk_per_unit: 100, qualifying: [{ product_level: "brand", product_id: 1 }] }))).toBe("৳0.100 off per base unit");
    expect(valueText("en", offer({ offer_type: "free_qty", threshold_qty_base: 60, reward_sku_id: 2, reward_qty_base: 20 }))).toBe("Buy 3 packet(s) (60 stick(s)), get 1 packet(s) (20 stick(s)) free");
    expect(valueText("en", offer({ offer_type: "drp_slide" }))).toBe("No rule set");
  });
});
