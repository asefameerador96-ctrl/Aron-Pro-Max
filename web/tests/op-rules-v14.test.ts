// Dues adjustment (T1, money) and paper backfill rules applied by the BFF before the API call.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { MAX_ADJUSTMENT_MTK, opRules } from "@/lib/admin/op-rules";
import { paperEntryEnabled } from "@/lib/admin/features";

// D-WEB-TEST-CLOCK-01 (after D-TEST-DATE-PIN-01): a fixed instant inside one Dhaka business day (15:00), so a run that crosses 18:00Z cannot see two days.
beforeEach(() => {
  vi.setSystemTime(new Date("2026-10-24T09:00:00Z"));
});
afterEach(() => {
  vi.useRealTimers();
});

const U = "3f2b8c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e";
const dues = (b: Record<string, unknown>) => opRules("dues.create", { client_uuid: U, outlet_id: 5, kind: "correction", amount_mtk: 1000, ...b }).map((e) => `${e.pointer}:${e.code}`);

describe("dues.create", () => {
  it("accepts the cap exactly, in both signs", () => {
    expect(dues({ amount_mtk: MAX_ADJUSTMENT_MTK })).toEqual([]);
    expect(dues({ amount_mtk: -MAX_ADJUSTMENT_MTK })).toEqual([]);
  });
  it("names the problem: zero, above_max, write_off_must_be_negative, not a safe integer", () => {
    expect(dues({ amount_mtk: 0 })).toEqual(["/amount_mtk:zero"]);
    expect(dues({ amount_mtk: MAX_ADJUSTMENT_MTK + 1 })).toEqual(["/amount_mtk:above_max"]);
    expect(dues({ amount_mtk: -MAX_ADJUSTMENT_MTK - 1 })).toEqual(["/amount_mtk:above_max"]);
    expect(dues({ kind: "write_off", amount_mtk: 500 })).toEqual(["/amount_mtk:write_off_must_be_negative"]);
    expect(dues({ kind: "write_off", amount_mtk: -500 })).toEqual([]);
    for (const a of [1.5, "10", null, Number.MAX_SAFE_INTEGER + 2, NaN]) expect(dues({ amount_mtk: a }), String(a)).toEqual(["/amount_mtk:invalid"]);
  });
  it("memo_client_uuid is optional, null or a uuid", () => {
    expect(dues({ memo_client_uuid: null })).toEqual([]);
    expect(dues({ memo_client_uuid: U })).toEqual([]);
    expect(dues({ memo_client_uuid: "not-a-uuid" })).toEqual(["/memo_client_uuid:invalid"]);
  });
});

describe("paper-backfill.create", () => {
  // Read the clock when called, not at collection: the pinned instant is set in beforeEach.
  const day = (back: number) => new Date(Date.now() + 6 * 3600_000 - back * 86_400_000).toISOString().slice(0, 10);
  const base = () => ({ client_uuid: U, memo_no: "sr334001-261005-017", business_date: day(1), user_id: 5, route_id: 9, outlet_id: 55, lines: [{ sku_id: 1, qty_base: 10, line_kind: "sale" }], paid_mtk: 0 });
  const run = (b: Record<string, unknown>) => opRules("paper-backfill.create", { ...base(), ...b }).map((e) => `${e.pointer}:${e.code}`);
  it("accepts a memo inside the 7-day window", () => {
    expect(run({})).toEqual([]);
    expect(run({ business_date: day(7) })).toEqual([]);
  });
  it("refuses a day outside the window, in the future, or a bad memo number", () => {
    expect(run({ business_date: day(8) })).toEqual(["/business_date:window"]);
    expect(run({ business_date: day(-2) })).toEqual(["/business_date:window"]);
    expect(run({ memo_no: "SR1-1" })).toEqual(["/memo_no:invalid"]);
  });
  it("lines and money are checked; a discount is refused while programmes are deferred", () => {
    expect(run({ lines: [] })).toEqual(["/lines:invalid"]);
    expect(run({ lines: [{ sku_id: 1, qty_base: 0, line_kind: "sale" }] })).toEqual(["/lines/0:invalid"]);
    expect(run({ lines: [{ sku_id: 1, qty_base: 1, line_kind: "gift" }] })).toEqual(["/lines/0:invalid"]);
    expect(run({ paid_mtk: -1 })).toEqual(["/paid_mtk:invalid"]);
    expect(run({ discount_mtk: 100 })).toEqual(["/discount_mtk:invalid"]);
  });
});

describe("feature flag", () => {
  it("is off unless exactly 1", () => {
    expect(paperEntryEnabled({})).toBe(false);
    expect(paperEntryEnabled({ ARON_FEATURE_PAPER_ENTRY: "true" })).toBe(false);
    expect(paperEntryEnabled({ ARON_FEATURE_PAPER_ENTRY: "1" })).toBe(true);
  });
});
