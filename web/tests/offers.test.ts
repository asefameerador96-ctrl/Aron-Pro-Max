import { describe, expect, it } from "vitest";
import { GRANT_MODULES } from "@/lib/admin/access-grants";
import { moduleOfApiPath, moduleOfPage } from "@/lib/admin/modules";
import {
  activationBlocked, bpText, canActivateOffer, canReadOffers, canWriteOffers, emptyForm, fieldQtyToBase, formFromOffer, formatBp, mtkText, nextStatuses, offerAccess, percentToBp, perFieldTakaToPerBase, splitBase, diffPatch, deriveUnits, displayUnit, nextAttempt, reunit, ruleOf, unitCtx, validateForm,
  type Offer, type OfferForm, type OfferSkuUnit,
} from "@/lib/admin/offers";
import { amountPerUnitText, qtyText, valueText } from "@/lib/admin/offers-view";
import { menuFor } from "@/lib/menu/menu";

const P20: OfferSkuUnit = { sku_id: 2, base_unit: "stick", pack_factor: 20, field_unit: "pack" };
const P10: OfferSkuUnit = { sku_id: 1, base_unit: "stick", pack_factor: 10, field_unit: "pack" };
const DOZ: OfferSkuUnit = { sku_id: 9, base_unit: "dozen", pack_factor: 1, field_unit: "dozen" };
const BOX: OfferSkuUnit = { sku_id: 8, base_unit: "piece", pack_factor: 25, field_unit: "pack" };

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
  it("the display word follows units rule 5 from the contract's one QtyUnit vocabulary: packet for cigarettes and bidi, box of 25 for lighters, dozen for matches", () => {
    expect(displayUnit(P20)).toBe("packet");
    expect(displayUnit(BOX)).toBe("box");
    expect(displayUnit(DOZ)).toBe("dozen");
    expect(displayUnit({ field_unit: "piece", base_unit: "piece" })).toBe("piece");
    expect(displayUnit({ field_unit: "stick", base_unit: "stick" })).toBe("stick");
    // an explicit base word wins over the category word: a cigarette counted in sticks stays "stick", a match counted in packs is a dozen
    expect(displayUnit({ field_unit: "stick", base_unit: "stick", category_code: "cigarette" })).toBe("stick");
    expect(displayUnit({ field_unit: "pack", base_unit: "stick", category_code: "match" })).toBe("dozen");
    expect(unitCtx([BOX])).toEqual({ packFactor: 25, fieldUnit: "box", baseUnit: "piece" });
    expect(qtyText("en", 50, unitCtx([BOX]))).toBe("2 box(es) (50 piece(s))");
    expect(qtyText("bn", 50, unitCtx([BOX]))).toContain("২ বক্স");
  });
  it("bidi is counted in packets of its own stick count (pack_factor 25 and 12, base stick): the word is packet and a mix of the two is base units", () => {
    const B25: OfferSkuUnit = { sku_id: 20, base_unit: "stick", pack_factor: 25, field_unit: "pack" };
    const B12: OfferSkuUnit = { sku_id: 21, base_unit: "stick", pack_factor: 12, field_unit: "pack" };
    expect(displayUnit(B25)).toBe("packet");
    expect(displayUnit(B12)).toBe("packet");
    expect(unitCtx([B25])).toEqual({ packFactor: 25, fieldUnit: "packet", baseUnit: "stick" });
    expect(unitCtx([B25, { ...B25, sku_id: 22 }])?.packFactor).toBe(25);
    expect(unitCtx([B25, B12])).toBeNull(); // two pack sizes: quantities stay in sticks
    expect(qtyText("en", 50, unitCtx([B25]))).toBe("2 packet(s) (50 stick(s))");
    expect(qtyText("en", 36, unitCtx([B12]))).toBe("3 packet(s) (36 stick(s))");
    expect(fieldQtyToBase("2", 12)).toBe(24);
  });
  it("the category decides the word when the caller has it; the base unit is the fallback", () => {
    expect(displayUnit({ field_unit: "pack", base_unit: "stick", category_code: "bidi" })).toBe("packet");
    expect(displayUnit({ field_unit: "pack", base_unit: "piece", category_code: "lighter" })).toBe("box");
    expect(displayUnit({ field_unit: "dozen", base_unit: "dozen", category_code: "match" })).toBe("dozen");
    expect(displayUnit({ field_unit: "pack", base_unit: "piece" })).toBe("box"); // no category on the wire: base unit
    expect(displayUnit({ field_unit: "piece", base_unit: "piece", category_code: "other" })).toBe("piece");
    expect(displayUnit({ field_unit: "pack", base_unit: "stick", category_code: "lighter" })).toBe("box"); // the category wins over a base-unit guess
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
  const unitOf = (id: number) => ({ 1: P10, 2: P20, 8: BOX, 9: DOZ } as Record<number, OfferSkuUnit>)[id];
  const server = (o: Partial<Offer>): Offer => ({ id: 1, code: "A", group_code: "G", offer_type: "free_qty", level: "line", title_en: "T", title_bn: null, valid_from: "2026-11-01", valid_to: "2026-11-30", threshold_qty_base: 65, reward_sku_id: 2, reward_qty_base: 10, qualifying: [{ product_level: "sku", product_id: 2 }], scope: [], stacking: "exclusive", status: "draft", version: 1, ...o }) as Offer;
  const save = (o: Offer, edit: (f: OfferForm) => OfferForm, uo = unitOf) => {
    const f0 = formFromOffer(o, uo);
    const f = edit({ ...f0, reason: "A reason that is long enough" });
    const r = validateForm(f, deriveUnits(f, uo), "edit");
    expect(r.errors).toEqual([]);
    return diffPatch(ruleOf(o), r.body!, "0b6d6c0e-8a5b-4d56-9f0a-3f1d3a4b5c6d");
  };
  it("an edit does not ask for the code and a patch carries only what changed (never code or group)", () => {
    const o = server({ offer_type: "pct_discount", discount_bp: 250, threshold_qty_base: null, reward_sku_id: null, reward_qty_base: null });
    const patch = save(o, (f) => ({ ...f, percent: "5" }));
    expect(patch).toEqual({ request_uuid: "0b6d6c0e-8a5b-4d56-9f0a-3f1d3a4b5c6d", discount_bp: 500, change_reason: "A reason that is long enough" });
  });
  it("NO TRUNCATION: a quantity that is not a whole number of packs opens in base units and a title-only edit leaves every quantity untouched", () => {
    const o = server({}); // threshold 65 sticks on a pack of 20, reward 10 sticks
    const f = formFromOffer(o, unitOf);
    expect(f.threshold).toBe("65");
    expect(f.inBase.threshold).toBe(true);
    expect(f.rewardQty).toBe("10");
    expect(f.inBase.reward).toBe(true);
    const patch = save(o, (x) => ({ ...x, title_en: "Renamed" }));
    expect(patch).toEqual({ request_uuid: "0b6d6c0e-8a5b-4d56-9f0a-3f1d3a4b5c6d", title_en: "Renamed", change_reason: "A reason that is long enough" });
    expect(JSON.stringify(patch)).not.toMatch(/qty_base|discount/);
    const whole = validateForm({ ...f, reason: "A reason that is long enough" }, deriveUnits(f, unitOf), "edit").body!; // the full snapshot is byte-identical too
    expect(whole).toMatchObject({ threshold_qty_base: 65, reward_qty_base: 10 });
  });
  it("a whole number of packs opens in the field unit and saves back to the same base value", () => {
    const o = server({ threshold_qty_base: 60, reward_qty_base: 20 });
    const f = formFromOffer(o, unitOf);
    expect([f.threshold, f.rewardQty, f.inBase.threshold, f.inBase.reward]).toEqual(["3", "1", false, false]);
    expect(save(o, (x) => ({ ...x, title_en: "Renamed" }))).not.toHaveProperty("threshold_qty_base");
    expect(save(o, (x) => ({ ...x, threshold: "4" }))).toMatchObject({ threshold_qty_base: 80 });
  });
  it("ONE UNIT SOURCE: when the SKU units are not known, opening and saving are both in base units (nothing multiplied or divided)", () => {
    const none = () => undefined;
    const o = server({ offer_type: "amount_per_unit", discount_mtk_per_unit: 100, threshold_qty_base: null, reward_sku_id: null, reward_qty_base: null });
    const f = formFromOffer(o, none);
    expect(f.perUnit).toBe("0.1"); // per base unit, as stored
    expect(deriveUnits(f, none)).toEqual({ qualifying: null, reward: null });
    expect(save(o, (x) => ({ ...x, title_en: "Renamed" }), none)).not.toHaveProperty("discount_mtk_per_unit");
    expect(save(o, (x) => ({ ...x, perUnit: "0.2" }), none)).toMatchObject({ discount_mtk_per_unit: 200 });
    // and with the units known the SAME stored value opens as a price per packet
    expect(formFromOffer(o, unitOf).perUnit).toBe("2");
  });
  it("a stored member of another offer type is never nulled: a title-only edit of a percentage offer with a threshold sends exactly three members", () => {
    const o = server({ offer_type: "pct_discount", discount_bp: 250, threshold_qty_base: 60, reward_sku_id: 2, reward_qty_base: 20 });
    const patch = save(o, (f) => ({ ...f, title_en: "Renamed" }));
    expect(patch).toEqual({ request_uuid: "0b6d6c0e-8a5b-4d56-9f0a-3f1d3a4b5c6d", title_en: "Renamed", change_reason: "A reason that is long enough" });
    // changing the TYPE rewrites the rule on purpose: the old type's value is cleared
    const typed = save(o, (f) => ({ ...f, offer_type: "amount_per_unit", perUnit: "2" }));
    expect(typed).toMatchObject({ offer_type: "amount_per_unit", discount_mtk_per_unit: 100, discount_bp: null });
  });
  it("a type change pins every nulled member: free_qty to pct and to amount clear the free_qty members; a kept free_qty leaves a stored discount_bp alone", () => {
    const fq = server({ discount_bp: 250 }); // free_qty (65 / reward 2 x 10) that also stores a percentage
    const toPct = save(fq, (f) => ({ ...f, offer_type: "pct_discount", percent: "5" }));
    expect(toPct).toEqual({ request_uuid: "0b6d6c0e-8a5b-4d56-9f0a-3f1d3a4b5c6d", offer_type: "pct_discount", discount_bp: 500, threshold_qty_base: null, reward_sku_id: null, reward_qty_base: null, change_reason: "A reason that is long enough" });
    const toAmt = save(fq, (f) => ({ ...f, offer_type: "amount_per_unit", perUnit: "2" }));
    expect(toAmt).toEqual({ request_uuid: "0b6d6c0e-8a5b-4d56-9f0a-3f1d3a4b5c6d", offer_type: "amount_per_unit", discount_mtk_per_unit: 100, discount_bp: null, threshold_qty_base: null, reward_sku_id: null, reward_qty_base: null, change_reason: "A reason that is long enough" });
    const kept = save(fq, (f) => ({ ...f, title_en: "Renamed" }));
    expect(kept).toEqual({ request_uuid: "0b6d6c0e-8a5b-4d56-9f0a-3f1d3a4b5c6d", title_en: "Renamed", change_reason: "A reason that is long enough" }); // discount_bp 250 untouched
  });
  it("reunit clears a threshold and a reward quantity that are not valid numbers in their old unit", () => {
    const f1 = base({ offer_type: "free_qty", threshold: "1.5", rewardSku: "2", rewardQty: "x" });
    const mixed = { ...f1, qualifying: [...f1.qualifying, { product_level: "sku" as const, product_id: 1 }], rewardSku: "1" };
    const re = reunit(mixed, f1, deriveUnits(f1, unitOf), deriveUnits(mixed, unitOf));
    expect([re.threshold, re.inBase.threshold]).toEqual(["", false]);
    expect([re.rewardQty, re.inBase.reward]).toEqual(["", false]);
  });
  it("a scope round-trips: it opens as stored and a title-only edit sends no scope; an edit does not ask for the code", () => {
    const o = server({ offer_type: "pct_discount", discount_bp: 250, threshold_qty_base: null, reward_sku_id: null, reward_qty_base: null, scope: [{ node_type: "wing", node_id: 1 }, { node_type: "route", node_id: 7 }] });
    const f = formFromOffer(o, unitOf);
    expect(f.scope).toEqual([{ node_type: "wing", node_id: 1 }, { node_type: "route", node_id: 7 }]);
    expect(f.code).toBe("A");
    expect(validateForm({ ...f, code: "", reason: "A reason that is long enough" }, deriveUnits(f, unitOf), "edit").errors).toEqual([]); // code "" in edit mode is fine
    const patch = save(o, (x) => ({ ...x, title_en: "Renamed" }));
    expect(patch).not.toHaveProperty("scope");
    expect(save(o, (x) => ({ ...x, scope: x.scope.slice(0, 1) }))).toMatchObject({ scope: [{ node_type: "wing", node_id: 1 }] });
  });
  it("trimmed titles and an empty Bangla title are not changes", () => {
    const o = server({ offer_type: "pct_discount", discount_bp: 250, threshold_qty_base: null, reward_sku_id: null, reward_qty_base: null, title_en: "  T  ", title_bn: "   " });
    expect(save(o, (f) => f)).toBeNull();
  });
  it("an amount that is invalid in the old unit is cleared for re-entry, never reinterpreted in the new unit", () => {
    const f1 = base({ offer_type: "amount_per_unit", perUnit: "0.001" }); // not exact per packet of 20
    const mixed = { ...f1, qualifying: [...f1.qualifying, { product_level: "sku" as const, product_id: 1 }] };
    const re = reunit(mixed, f1, deriveUnits(f1, unitOf), deriveUnits(mixed, unitOf));
    expect(re.perUnit).toBe("");
  });
  it("nothing changed gives no patch", () => {
    expect(save(server({}), (f) => f)).toBeNull();
  });
  it("changing the products re-expresses typed amounts and quantities with the same base value", () => {
    const f1 = { ...base({ offer_type: "amount_per_unit", perUnit: "2" }) }; // 2 taka per packet of 20 sticks
    const before = deriveUnits(f1, unitOf);
    const added = { ...f1, qualifying: [...f1.qualifying, { product_level: "sku" as const, product_id: 1 }] }; // a pack of 10 joins
    const after = deriveUnits(added, unitOf);
    expect(after.qualifying).toBeNull(); // mixed packs: base units
    expect(reunit(added, f1, before, after).perUnit).toBe("0.1"); // 100 milli-taka per stick, not 2000
    const f2 = base({ offer_type: "free_qty", threshold: "3" });
    const mixed = { ...f2, qualifying: [...f2.qualifying, { product_level: "sku" as const, product_id: 1 }] };
    const re = reunit(mixed, f2, deriveUnits(f2, unitOf), deriveUnits(mixed, unitOf));
    expect(re.threshold).toBe("60"); // 3 packets of 20, now counted in base units
    const back = reunit(f2, mixed, deriveUnits(mixed, unitOf), deriveUnits(f2, unitOf));
    expect(back.threshold).toBe("3");
    const odd = reunit({ ...f2, threshold: "65" }, { ...mixed, threshold: "65" }, deriveUnits(mixed, unitOf), deriveUnits(f2, unitOf));
    expect([odd.threshold, odd.inBase.threshold]).toEqual(["65", true]); // 65 sticks is not whole packs of 20: stays a base entry
  });
  it("request_uuid is kept for a retry of the same body and regenerated for a changed body", () => {
    let n = 0;
    const make = () => `u${++n}`;
    const a = nextAttempt(null, "body-1", make);
    expect(nextAttempt(a, "body-1", make)).toBe(a);
    expect(a.uuid).toBe("u1");
    expect(nextAttempt(a, "body-2", make).uuid).toBe("u2");
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
    expect(qtyText("en", 5, u)).toBe("5 stick(s)");
    expect(qtyText("en", 7, null)).toBe("7 base unit(s)");
    expect(qtyText("en", 5, unitCtx([DOZ]))).toBe("5 dozen");
    expect(qtyText("bn", 60, u)).toContain("৩ প্যাকেট");
  });
  it("money is exact taka per field unit; percent is from basis points", () => {
    expect(amountPerUnitText("en", 100, unitCtx([P20]))).toBe("2.000");
    expect(amountPerUnitText("en", 100, null)).toBe("0.100");
    expect(valueText("en", offer({ discount_bp: 1250 }))).toBe("12.50% off");
    expect(valueText("en", offer({ offer_type: "amount_per_unit", discount_mtk_per_unit: 100 }))).toBe("Tk 2.000 off per packet");
    expect(valueText("en", offer({ offer_type: "amount_per_unit", discount_mtk_per_unit: 100, qualifying: [{ product_level: "brand", product_id: 1 }] }))).toBe("Tk 0.100 off per base unit");
    expect(valueText("en", offer({ offer_type: "free_qty", threshold_qty_base: 60, reward_sku_id: 2, reward_qty_base: 20 }))).toBe("Buy 3 packet(s) (60 stick(s)), get 1 packet(s) (20 stick(s)) free");
    expect(valueText("en", offer({ offer_type: "drp_slide" }))).toBe("No rule set");
  });
});
