// CHECKER ADM-2 (T2): F-ADM-004 product metadata against ProductNodeWrite/Patch and SkuWrite/Patch in contract/openapi.yaml.
import { describe, expect, it } from "vitest";
import { productNodeEntities, skus } from "@/app/admin/_entities/products";
import { valuesSchema } from "@/components/admin/crud/validation";

const seg = productNodeEntities.find((e) => e.slug === "segments")!;
const sku = { code: "MaxR-10S", variant_id: "3", category_code: "cigarette", name: "Max R 10s", short_name: "MaxR10", base_unit: "stick", base_per_pack: "10", entry_unit_default: "pack", report_factor: "1.5", sort: "1" };

describe("sort (integer, required)", () => {
  it("AD2-12: a required sort left blank is refused, not stored as 0", () => {
    expect(valuesSchema(skus, "create").safeParse({ ...sku, sort: "" }).success).toBe(false);
    expect(valuesSchema(seg, "create").safeParse({ parent_id: "2", name: "x", sort: "  " }).success).toBe(false);
  });
  it("AD2-13: a crafted null / true / [] sort is refused, not coerced to 0 or 1", () => {
    for (const sort of [null, true, []]) expect(valuesSchema(seg, "create").safeParse({ parent_id: "2", name: "x", sort }).success, JSON.stringify(sort)).toBe(false);
  });
  it("AD2-14: an unsafe integer (1e20) is refused", () => {
    expect(valuesSchema(seg, "create").safeParse({ parent_id: "2", name: "x", sort: "99999999999999999999" }).success).toBe(false);
  });
  it("AD2-15: the contract has no minimum on sort, so a negative sort (-1, to place a node first) is accepted", () => {
    expect(valuesSchema(seg, "create").safeParse({ parent_id: "2", name: "x", sort: "-1" }).success).toBe(true);
  });
});

describe("SkuWrite / SkuPatch members", () => {
  it("AD2-16: pack size 1..1000 and Decimal3 factor boundaries", () => {
    const s = valuesSchema(skus, "create");
    expect(s.safeParse({ ...sku }).success).toBe(true);
    expect(s.safeParse({ ...sku, base_per_pack: "0" }).success).toBe(false);
    expect(s.safeParse({ ...sku, base_per_pack: "1001" }).success).toBe(false);
    expect(s.safeParse({ ...sku, base_per_pack: "1000" }).success).toBe(true);
    expect(s.safeParse({ ...sku, report_factor: "1.2345" }).success).toBe(false);
    expect(s.safeParse({ ...sku, report_factor: "12345678901234" }).success).toBe(false);
    expect(s.safeParse({ ...sku, report_factor: "১২.৫" }).success).toBe(true);
  });
  it("AD2-17: code, variant, category, units and pack size are create-only; patch accepts nothing else", () => {
    const u = valuesSchema(skus, "update");
    for (const k of ["code", "variant_id", "category_code", "base_unit", "base_per_pack", "entry_unit_default"]) expect(u.safeParse({ [k]: "1" }).success, k).toBe(false);
    expect(u.safeParse({ name: "n", short_name: "s", name_bn: null, report_unit: null, report_factor: "2", sort: "2", status: "inactive" }).success).toBe(true);
  });
  it("AD2-18: a short_name of 21 code points is refused, 20 Bengali letters are accepted", () => {
    const s = valuesSchema(skus, "create");
    expect(s.safeParse({ ...sku, short_name: "x".repeat(21) }).success).toBe(false);
    expect(s.safeParse({ ...sku, short_name: "ক".repeat(20) }).success).toBe(true);
  });
});

describe("ProductNodeWrite / Patch members", () => {
  it("AD2-19: a category has no parent member; segment, brand, variant require one", () => {
    const cat = productNodeEntities[0]!;
    expect(valuesSchema(cat, "create").safeParse({ name: "c", sort: "1", parent_id: "1" }).success).toBe(false);
    for (const e of productNodeEntities.slice(1)) expect(valuesSchema(e, "create").safeParse({ name: "c", sort: "1" }).success, e.slug).toBe(false);
  });
  it("AD2-20: code is create-only and at most 40; name 1..120", () => {
    expect(valuesSchema(seg, "update").safeParse({ code: "x" }).success).toBe(false);
    expect(valuesSchema(seg, "create").safeParse({ parent_id: "2", name: "n", sort: "1", code: "c".repeat(41) }).success).toBe(false);
    expect(valuesSchema(seg, "create").safeParse({ parent_id: "2", name: "n".repeat(121), sort: "1" }).success).toBe(false);
  });
});
