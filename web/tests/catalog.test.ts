import { describe, expect, it } from "vitest";
import { PUT as codeListPut } from "@/app/api/bff/admin/code-lists/[key]/route";
import { productNodeEntities, skus } from "@/app/admin/_entities/products";
import { CODE_LISTS } from "@/app/admin/_codelists/registry";
import { valuesSchema } from "@/components/admin/crud/validation";
import { en } from "@/lib/i18n/messages-en";
import { req, setupMock } from "./helpers/bff";

const { mock, signIn, create, update } = setupMock();
const REASON = "Reason that is long enough";
const putList = (key: string, body: unknown, c: Record<string, string>) => codeListPut(req(`/api/bff/admin/code-lists/${key}`, "PUT", body, c), { params: Promise.resolve({ key }) });

describe("product hierarchy", () => {
  it("has four levels, only categories without a parent", () => {
    expect(productNodeEntities.map((e) => e.slug)).toEqual(["categories", "segments", "brands", "variants"]);
    const names = (slug: string) => productNodeEntities.find((e) => e.slug === slug)!.fields.map((f) => f.name);
    expect(names("categories")).not.toContain("parent_id");
    expect(names("variants")).toContain("parent_id");
  });
  it("creates a segment under a category, sets status, sort and Bangla name", async () => {
    const c = await signIn("madmin1");
    const res = await create("segments", { values: { parent_id: "2", name: "Mass", sort: "3", name_bn: "সাধারণ" }, reason: REASON }, c);
    expect(res.status).toBe(201);
    expect((await res.json()).row).toMatchObject({ level: "segment", parent_id: 2, sort: 3, status: "active" });
    const upd = await update("segments", "4", { values: { status: "inactive", sort: "9" }, reason: REASON, version: 1 }, c);
    expect(upd.status).toBe(200);
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "product_node", action: "product_node.update", after: { status: "inactive", sort: 9 } });
  });
  it("a node of another level is not reachable through the wrong path", async () => {
    const c = await signIn("madmin1");
    expect((await update("brands", "1", { values: { sort: "2" }, reason: REASON, version: 1 }, c)).status).toBe(404); // id 1 is a category
  });
});

describe("SKUs", () => {
  const ok = { code: "NEW-10S", variant_id: "7", category_code: "cigarette", name: "New 10s", short_name: "New10", base_unit: "stick", base_per_pack: "10", entry_unit_default: "pack", report_factor: "0.100", sort: "3" };
  it("validates units, pack size range, decimal(3) factor and code like the contract", () => {
    const s = valuesSchema(skus, "create");
    expect(s.safeParse(ok)).toMatchObject({ success: true, data: { base_per_pack: 10, report_factor: "0.100" } });
    expect(s.safeParse({ ...ok, base_per_pack: "0" }).success).toBe(false);
    expect(s.safeParse({ ...ok, base_per_pack: "1001" }).success).toBe(false);
    expect(s.safeParse({ ...ok, base_unit: "pack" }).success).toBe(false); // pack is an entry unit only
    expect(s.safeParse({ ...ok, entry_unit_default: "pack" }).success).toBe(true);
    expect(s.safeParse({ ...ok, report_factor: "0.1234" }).success).toBe(false);
    expect(s.safeParse({ ...ok, report_factor: "০.১০০" })).toMatchObject({ success: true, data: { report_factor: "0.100" } });
    expect(s.safeParse({ ...ok, short_name: "x".repeat(21) }).success).toBe(false);
    expect(s.safeParse({ ...ok, category_code: "cigar" }).success).toBe(false);
    expect(s.safeParse({ ...ok, code: "bad code" }).success).toBe(false);
  });
  it("base unit, pack size, code and variant cannot be patched", () => {
    const u = valuesSchema(skus, "update");
    for (const k of ["code", "base_unit", "base_per_pack", "variant_id", "entry_unit_default", "category_code"]) expect(u.safeParse({ [k]: "x" }).success, k).toBe(false);
    expect(u.safeParse({ short_name: "MaxR", sort: "4", status: "inactive" }).success).toBe(true);
  });
  it("creates and edits with the reason; duplicate codes are 409", async () => {
    const c = await signIn("madmin1");
    expect((await create("skus", { values: ok, reason: REASON }, c)).status).toBe(201);
    expect((await create("skus", { values: ok, reason: REASON }, c)).status).toBe(409);
    expect((await update("skus", "1", { values: { short_name: "MaxR10" }, reason: REASON, version: 1 }, c)).status).toBe(200);
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "sku", reason: REASON });
  });
});

describe("code lists", () => {
  it("registers the three classification lists (reason tables and QC faults have their own pages), each with an English label", () => {
    expect(CODE_LISTS.map((l) => l.key)).toEqual(["channel", "sub_channel", "geo_class"]);
    for (const l of CODE_LISTS) expect(en[l.labelKey], l.key).toBeTruthy();
  });
  const list = async (c: Record<string, string>, key: string) => (await (await fetch(`${process.env.ARON_API_BASE_URL}/v1/admin/code-lists`, { headers: { Authorization: `Bearer ${[...mock.state.access.keys()].pop()}` } })).json()).lists.find((l: { list_key: string }) => l.list_key === key).items as Record<string, unknown>[];
  it("adds an item, edits a label, retires with valid_to; the reason is audited", async () => {
    const c = await signIn("madmin1");
    const items = await list(c, "geo_class");
    expect(items).toHaveLength(4);
    const next = [...items.map((i) => (i.code === "hill" ? { ...i, label_bn: "পার্বত্য", valid_to: "2026-12-31" } : i)), { code: "char_land", label_en: "River char", label_bn: "চর", sort: 5 }];
    const res = await putList("geo_class", { items: next, reason: REASON }, c);
    expect(res.status).toBe(200);
    expect(await list(c, "geo_class")).toHaveLength(5);
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "code_list", reason: REASON });
  });
  it("refuses removing or renaming a saved code, duplicate codes, bad codes, missing labels and a short reason", async () => {
    const c = await signIn("madmin1");
    const items = await list(c, "channel");
    const removed = await putList("channel", { items: items.slice(1), reason: REASON }, c);
    expect(removed.status).toBe(400);
    expect((await removed.json()).errors[0].code).toBe("code_removed");
    const renamed = await putList("channel", { items: items.map((i, n) => (n === 0 ? { ...i, code: "retail2" } : i)), reason: REASON }, c);
    expect(renamed.status).toBe(400);
    expect((await putList("channel", { items: [...items, items[0]], reason: REASON }, c)).status).toBe(400);
    expect((await putList("channel", { items: [...items, { code: "Bad-Code", label_en: "x", sort: 3 }], reason: REASON }, c)).status).toBe(400);
    expect((await putList("channel", { items: [...items, { code: "ok_code", label_en: "", sort: 3 }], reason: REASON }, c)).status).toBe(400);
    expect((await putList("channel", { items, reason: "short" }, c)).status).toBe(400);
    expect((await putList("channel", { items: [...items, { code: "extra_code", label_en: "x", sort: 3, bogus: 1 }], reason: REASON }, c)).status).toBe(400);
    expect((await putList("nope", { items, reason: REASON }, c)).status).toBe(404);
    expect(await list(c, "channel")).toHaveLength(2);
  });
  it("support and TSO cannot write", async () => {
    const support = await signIn("msupport1");
    const items = await list(support, "channel");
    expect((await putList("channel", { items, reason: REASON }, support)).status).toBe(403);
    const tso = await signIn("mtso1");
    expect((await putList("channel", { items, reason: REASON }, tso)).status).toBe(403);
  });
});
