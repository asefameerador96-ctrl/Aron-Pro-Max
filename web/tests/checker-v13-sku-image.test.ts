// CHECKER v1.3 (T2): F-ADM-065 SKU pack image through the BFF (asset ticket, SkuWrite/SkuPatch image_asset_id).
import { describe, expect, it } from "vitest";
import { POST as assetPost } from "@/app/api/bff/admin/assets/route";
import { skus } from "@/app/admin/_entities/products";
import { valuesSchema } from "@/components/admin/crud/validation";
import { csp } from "@/lib/security-headers";
import { req, setupMock } from "./helpers/bff";

const { mock, signIn, create, update } = setupMock();
const REASON = "Reason typed by the administrator";
const U1 = "0b8f1c4e-6a4d-4c0e-9f21-3d5a7b9c1e22";
const U2 = "7c9e6679-7425-40de-944b-e07fc1f90ae7";
const SHA = "a".repeat(64);
const ticket = (body: unknown, c: Record<string, string>) => assetPost(req("/api/bff/admin/assets", "POST", body, c));
const tk = (o: Record<string, unknown> = {}) => ({ asset_id: U1, purpose: "sku_image", mime: "image/png", bytes: 1000, sha256: SHA, ...o });
const sku = { code: "ImgT-1", variant_id: 3, category_code: "cigarette", name: "Img", short_name: "Img", base_unit: "stick", base_per_pack: 10, entry_unit_default: "pack", report_factor: "1.5", sort: 1 };
const firstSkuId = () => String((mock.state.tables.skus as unknown as { id: number }[])[0]!.id);

describe("asset ticket role gating and body", () => {
  it("V13-1: SUPPORT gets 403, ADMIN gets a ticket", async () => {
    expect((await ticket(tk(), await signIn("msupport1"))).status).toBe(403);
    expect((await ticket(tk(), await signIn("madmin1"))).status).toBe(200);
  });
  it("V13-2: no session is 401", async () => {
    expect((await ticket(tk(), {})).status).toBe(401);
  });
  it("V13-3: wrong type, oversize, zero, unknown member, bad uuid, bad sha, string bytes are all 400", async () => {
    const c = await signIn("madmin1");
    for (const o of [{ mime: "image/gif" }, { mime: "application/pdf" }, { bytes: 307_201 }, { bytes: 0 }, { bytes: "10" }, { bytes: 1.5 }, { extra: 1 }, { asset_id: U1.toUpperCase() }, { asset_id: "x" }, { sha256: "z" }, { purpose: "nope" }, { purpose: "__proto__" }]) {
      expect((await ticket(tk(o), c)).status, JSON.stringify(o)).toBe(400);
    }
    expect((await ticket(tk({ bytes: 307_200 }), c)).status).toBe(200);
  });
});

describe("SKU image_asset_id writes", () => {
  it("V13-4: create with an image stores image_url, update replaces it, '' removes it, audit carries the reason", async () => {
    const c = await signIn("madmin1");
    const made = await create("skus", { values: { ...sku, image_asset_id: U1 }, reason: REASON }, c);
    expect(made.status).toBe(201);
    const row = ((await made.json()) as { row: { id: number; version: number; image_url: string | null } }).row;
    expect(row.image_url).toContain(U1);
    const r2 = await update("skus", String(row.id), { values: { image_asset_id: U2 }, reason: REASON, version: row.version }, c);
    expect(r2.status).toBe(200);
    const row2 = ((await r2.json()) as { row: { version: number; image_url: string } }).row;
    expect(row2.image_url).toContain(U2);
    const r3 = await update("skus", String(row.id), { values: { image_asset_id: "" }, reason: REASON, version: row2.version }, c);
    expect(r3.status).toBe(200);
    expect(((await r3.json()) as { row: { image_url: unknown } }).row.image_url).toBeNull();
    const audits = (mock.state.audit as unknown as { entity?: string; reason?: unknown }[]).filter((a) => a.entity === "sku");
    expect(audits.some((a) => a.reason === REASON)).toBe(true);
  });
  it("V13-5: SUPPORT cannot create or patch", async () => {
    const s = await signIn("msupport1");
    expect((await create("skus", { values: { ...sku, image_asset_id: U1 }, reason: REASON }, s)).status).toBe(403);
    expect((await update("skus", firstSkuId(), { values: { image_asset_id: U1 }, reason: REASON, version: 1 }, s)).status).toBe(403);
  });
  it("V13-6: bad uuid, v1 uuid, number, uppercase, image_url member are 400", async () => {
    const c = await signIn("madmin1");
    for (const v of ["not-a-uuid", "c232ab00-9414-11ec-b3c8-9f6bdeced846", 5, U1.toUpperCase(), true, [], {}]) {
      const r = await update("skus", firstSkuId(), { values: { image_asset_id: v }, reason: REASON, version: 1 }, c);
      expect(r.status, JSON.stringify(v)).toBe(400);
    }
    expect((await update("skus", firstSkuId(), { values: { image_url: "https://x/y.png" }, reason: REASON, version: 1 }, c)).status).toBe(400);
  });
  it("V13-7: schema output for blank / whitespace is null (removal), uuid is trimmed", () => {
    const s = valuesSchema(skus, "update");
    expect(s.parse({ image_asset_id: "  " })).toEqual({ image_asset_id: null });
    expect(s.parse({ image_asset_id: ` ${U1} ` })).toEqual({ image_asset_id: U1 });
  });
});

describe("CSP", () => {
  it("V13-8: img-src gains the blob origin only when ARON_BLOB_ORIGIN is a clean origin", () => {
    process.env.ARON_BLOB_ORIGIN = "https://acct.blob.core.windows.net";
    expect(csp("n").split("; ").find((d) => d.startsWith("img-src"))).toContain("https://acct.blob.core.windows.net");
    process.env.ARON_BLOB_ORIGIN = "https://*.blob.core.windows.net";
    expect(csp("n")).not.toContain("*.blob");
    delete process.env.ARON_BLOB_ORIGIN;
  });
});
