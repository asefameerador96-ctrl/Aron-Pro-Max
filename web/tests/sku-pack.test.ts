import { describe, expect, it } from "vitest";
import { BASE_UNIT_OF, PACK_LABEL, packPreview } from "@/lib/admin/sku-pack";
import { skus } from "@/app/admin/_entities/products";
import { en } from "@/lib/i18n/messages-en";
import { bn } from "@/lib/i18n/messages-bn";

const p = (category: string, basePerPack: string, baseUnit = BASE_UNIT_OF[category as keyof typeof BASE_UNIT_OF] ?? "stick", entryUnit = "pack") => packPreview({ category, baseUnit, basePerPack, entryUnit });

describe("packPreview (docs/34 s4.2)", () => {
  it("cigarettes in packets of 20 or 10 sticks: '1 packet = 20 sticks'", () => {
    expect(p("cigarette", "20")).toMatchObject({ label: "packet", sentence: { key: "skupack.preview.packet", vars: { n: 20 } }, warnings: [] });
    expect(p("cigarette", "10").sentence?.vars).toEqual({ n: 10 });
    expect(en["skupack.preview.packet"].replace("{n}", "20")).toBe("1 packet = 20 sticks");
  });
  it("bidi in packets with its own stick count; lighters as a box of 25; matches in dozens", () => {
    expect(p("bidi", "25")).toMatchObject({ label: "packet", warnings: [] });
    expect(p("lighter", "25")).toMatchObject({ label: "box", sentence: { key: "skupack.preview.box", vars: { n: 25 } }, warnings: [] });
    expect(p("match", "1")).toMatchObject({ label: "dozen", sentence: { key: "skupack.preview.match" }, warnings: [] });
    expect(PACK_LABEL).toEqual({ cigarette: "packet", bidi: "packet", lighter: "box", match: "dozen" });
  });
  it("warns, never blocks, on an unusual size, base unit or entry unit", () => {
    expect(p("cigarette", "15").warnings).toEqual(["skupack.warn.cigarette_size"]);
    expect(p("lighter", "12").warnings).toEqual(["skupack.warn.lighter_size"]);
    expect(p("match", "12").warnings).toEqual(["skupack.warn.match_size"]);
    expect(p("cigarette", "20", "piece").warnings).toEqual(["skupack.warn.base_unit"]);
    expect(p("cigarette", "20", "stick", "stick").warnings).toEqual(["skupack.warn.entry_unit"]);
    expect(p("cigarette", "15").sentence).not.toBeNull();
  });
  it("no sentence while the inputs are incomplete or out of range; the label shows once the category is known", () => {
    expect(p("", "20")).toEqual({ label: null, sentence: null, warnings: [] });
    expect(p("cigarette", "")).toMatchObject({ label: "packet", sentence: null });
    expect(p("cigarette", "0").sentence).toBeNull();
    expect(p("cigarette", "1001").sentence).toBeNull();
    expect(p("cigarette", "2.5").sentence).toBeNull();
  });
  it("Bangla sentence and labels exist and differ from English", () => {
    for (const k of ["skupack.preview.packet", "skupack.preview.box", "skupack.label.packet", "skupack.label.box", "skupack.label.dozen"] as const) expect(bn[k]).not.toBe(en[k]);
    expect(bn["skupack.preview.packet"]).toContain("প্যাকেট");
  });
});

describe("SKU entity", () => {
  it("has the pack fields and the live preview", () => {
    expect(skus.preview).toBe("sku-pack");
    for (const f of ["base_per_pack", "entry_unit_default", "base_unit", "category_code"]) expect(skus.fields.some((x) => x.name === f)).toBe(true);
    expect(skus.fields.find((x) => x.name === "base_per_pack")).toMatchObject({ required: true, min: 1, max: 1000 });
  });
});
