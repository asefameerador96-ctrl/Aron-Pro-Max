// Checker ADM-9 (F-ADM-005, T1 money rails): the price publish path through the BFF and the contract mock.
// A failing test here is a confirmed defect against the contract (PricePublishRequest, SkuPrice, createPrices) or docs/24 s7.
import { afterEach, describe, expect, it, vi } from "vitest";
import { POST as opPost } from "@/app/api/bff/master-op/route";
import { mtkToTaka, parseTakaToMtk } from "@/lib/admin/price-money";
import { businessDate } from "@/lib/i18n";
import { req, setupMock } from "./helpers/bff";

const { mock, signIn, token } = setupMock();
const REASON = "Price revision for the October list";
const plus = (n: number) => businessDate(new Date(Date.now() + n * 86_400_000));
const op = (body: unknown, c: Record<string, string>) => opPost(req("/api/bff/master-op", "POST", body, c));
const B1 = "a1a1a1a1-a1a1-4a1a-8a1a-a1a1a1a1a1a1";
const B2 = "b2b2b2b2-b2b2-4b2b-8b2b-b2b2b2b2b2b2";
const publish = (prices: unknown, c: Record<string, string>, batch = B1, valid_from = plus(2)) => op({ op: "price.publish", body: { batch_uuid: batch, valid_from, prices }, reason: REASON }, c);
const preview = (prices: unknown, c: Record<string, string>, batch = B1, valid_from = plus(2)) => op({ op: "price.preview", body: { batch_uuid: batch, valid_from, prices }, reason: REASON }, c);
const api = (path: string) => fetch(`${process.env.ARON_API_BASE_URL}${path}`, { headers: { authorization: `Bearer ${token()}` } }).then((r) => r.json() as Promise<{ items: { sku_id: number; price_type: string; amount_mtk: number; per_base_qty: number; valid_from: string; valid_to: string | null }[] }>);

afterEach(() => vi.useRealTimers());

describe("checker-adm9 money parsing / formatting (price-money.ts)", () => {
  it("guards: integer mtk, never float, strict input (passes)", () => {
    expect(parseTakaToMtk("7.935")).toBe(7935);
    expect(parseTakaToMtk("০.০০১")).toBe(1);
    expect(parseTakaToMtk("007.5")).toBe(7500);
    expect(parseTakaToMtk(" 12.5\t")).toBe(12500);
    expect(parseTakaToMtk("999999999999.999")).toBe(999_999_999_999_999);
    expect(Number.isSafeInteger(parseTakaToMtk("999999999999.999")!)).toBe(true);
    for (const bad of ["1,200", "1 200", "12.", ".5", "+5", "−5", "-0", "1e3", "1E3", "0x10", "Infinity", "NaN", "１２", "12.5.1", "", "   "]) expect(parseTakaToMtk(bad)).toBeNull();
    expect(mtkToTaka(7935)).toBe("7.935");
    expect(mtkToTaka(0)).toBe("0.000");
    expect(mtkToTaka(-1500)).toBe("-1.500");
  });
  it("DEFECT (low): mtkToTaka drops the sign of a negative amount under one taka (-500 mtk prints as 0.500)", () => {
    expect(mtkToTaka(-500)).toBe("-0.500");
    expect(mtkToTaka(-1)).toBe("-0.001");
  });
});

describe("checker-adm9 price publish through the mock (contract fidelity)", () => {
  it("DEFECT (high, mock): per_base_qty sent on publish is stored, not silently replaced by 1 (contract SkuPrice.per_base_qty)", async () => {
    const c = await signIn("madmin1");
    const res = await publish([{ sku_id: 1, price_type: "nto", amount_mtk: 79_350, per_base_qty: 10 }], c);
    expect(res.status).toBeLessThan(300);
    const row = mock.state.custom.prices.find((p) => p.sku_id === 1 && p.price_type === "nto" && p.valid_to === null)!;
    expect(row.amount_mtk).toBe(79_350);
    expect(row.per_base_qty).toBe(10); // stored as 1: the price becomes 79.350 Tk per stick instead of per 10 sticks
  });

  it("DEFECT (medium, mock): GET /v1/admin/prices?valid_on=today keeps returning the price in force today after a future-dated publish", async () => {
    const c = await signIn("madmin1");
    expect((await publish([{ sku_id: 1, price_type: "outlet", amount_mtk: 1_260_000 }], c)).status).toBeLessThan(300);
    const today = businessDate();
    const items = (await api(`/v1/admin/prices?valid_on=${today}&sku_id=1&price_type=outlet`)).items;
    // The new price applies from today+2; today the 1_200_000 row is still in force. The mock returns only the future row,
    // so the price page shows the future price as "current" and the old one vanishes.
    expect(items.map((p) => p.amount_mtk)).toEqual([1_200_000]);
  });

  it("DEFECT (medium, mock): a replayed publish of a pending_approval batch reports 'applied' (the grid then says 'Published')", async () => {
    const c = await signIn("madmin1");
    const first = (await (await publish([{ sku_id: 1, price_type: "outlet", amount_mtk: 2_000_000 }], c)).json()).data;
    expect(first.status).toBe("pending_approval");
    const replay = (await (await publish([{ sku_id: 1, price_type: "outlet", amount_mtk: 2_000_000 }], c)).json()).data;
    expect(replay.replayed).toBe(true);
    expect(replay.status).toBe("pending_approval");
  });

  it("DEFECT (high, mock): a pending_approval batch is not in force: its prices do not replace the open rows before a second approver decides", async () => {
    const c = await signIn("madmin1");
    const first = (await (await publish([{ sku_id: 1, price_type: "outlet", amount_mtk: 2_000_000 }], c)).json()).data;
    expect(first.status).toBe("pending_approval");
    const open = mock.state.custom.prices.filter((p) => p.sku_id === 1 && p.price_type === "outlet" && p.valid_to === null);
    expect(open.map((p) => p.amount_mtk)).toEqual([1_200_000]); // the mock already closed 1_200_000 and opened 2_000_000
  });

  it("DEFECT (medium, mock): the same batch_uuid with a different body is a 409, not a silent 'replayed' that drops the new prices", async () => {
    const c = await signIn("madmin1");
    expect((await publish([{ sku_id: 1, price_type: "outlet", amount_mtk: 1_260_000 }], c)).status).toBeLessThan(300);
    const res = await publish([{ sku_id: 2, price_type: "outlet", amount_mtk: 2_500_000 }], c);
    expect(res.status).toBe(409);
  });

  it("guard (passes): a retry after a lost response does not double the rows or the audit", async () => {
    const c = await signIn("madmin1");
    const body = [{ sku_id: 1, price_type: "outlet", amount_mtk: 1_260_000 }, { sku_id: 2, price_type: "cc", amount_mtk: 2_300_000 }];
    const before = mock.state.custom.prices.length;
    for (let i = 0; i < 3; i++) expect((await publish(body, c)).status).toBeLessThan(300);
    expect(mock.state.custom.prices.length).toBe(before + 2);
    expect(mock.state.audit.filter((a) => a.entity === "price_batch")).toHaveLength(1);
    expect((await publish(body, c, B2)).status).toBeLessThan(300); // a new batch is a new publish
    expect(mock.state.audit.filter((a) => a.entity === "price_batch")).toHaveLength(2);
  });

  it("DEFECT (medium, mock): createPrices answers 201 with a SkuPriceList (items + price_list_version), as the contract says", async () => {
    const c = await signIn("madmin1");
    const res = await publish([{ sku_id: 1, price_type: "outlet", amount_mtk: 1_260_000 }], c);
    expect(res.status).toBe(201);
    const data = (await res.json()).data;
    expect(Array.isArray(data.items)).toBe(true);
    expect(typeof data.price_list_version).toBe("number");
  });

  it("DEFECT (medium, mock): the price audit records the old and new amount of every row, not only a row count", async () => {
    const c = await signIn("madmin1");
    await publish([{ sku_id: 1, price_type: "outlet", amount_mtk: 1_260_000 }], c);
    const a = mock.state.audit.find((x) => x.entity === "price_batch")!;
    const s = JSON.stringify({ before: a.before, after: a.after });
    expect(s).toContain("1200000");
    expect(s).toContain("1260000");
  });

  it("DEFECT (low, mock): preview against a zero price in force returns a contract-valid max_change_pct (not Infinity / null)", async () => {
    const c = await signIn("madmin1");
    mock.state.custom.prices.push({ id: 99, sku_id: 2, price_type: "nto", amount_mtk: 0, per_base_qty: 1, valid_from: "2026-09-01", valid_to: null });
    const data = (await (await preview([{ sku_id: 2, price_type: "nto", amount_mtk: 100_000 }], c)).json()).data;
    expect(typeof data.max_change_pct).toBe("number");
    expect(Number.isFinite(data.max_change_pct)).toBe(true);
  });

  it("guard (passes): Dhaka midnight decides 'today' (18:00 UTC), not UTC", async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(new Date("2026-10-07T17:59:00Z")); // 23:59 Dhaka, 7 Oct
    let c = await signIn("madmin1");
    expect((await preview([{ sku_id: 1, price_type: "outlet", amount_mtk: 1_210_000 }], c, B1, "2026-10-08")).status).toBe(200);
    vi.setSystemTime(new Date("2026-10-07T18:00:30Z")); // 00:00 Dhaka, 8 Oct: the 8th is now today, refused
    c = await signIn("madmin1");
    expect((await preview([{ sku_id: 1, price_type: "outlet", amount_mtk: 1_210_000 }], c, B1, "2026-10-08")).status).toBe(400);
    expect((await preview([{ sku_id: 1, price_type: "outlet", amount_mtk: 1_210_000 }], c, B1, "2026-10-09")).status).toBe(200);
  });
});
