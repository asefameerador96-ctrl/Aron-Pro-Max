// CHECKER (F-ADM-036 dues adjustment and write-off): attempts to refute money handling, sign semantics,
// outlet id validation, Bangla input and the decision note rule. No DOM in this suite, so the OpForm submit
// handler is driven directly with a stubbed useState (hook order: values, reason, busy, errors, banner, ver, uuids).
import type * as ReactNS from "react";
import type { FormEvent, ReactElement } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { admin, op, setupMock } from "./helpers/harness";

const state = { overrides: new Map<number, unknown>(), setters: new Map<number, unknown[]>(), idx: 0 };
vi.mock("react", async (orig) => {
  const r = await orig<typeof ReactNS>();
  return {
    ...r,
    useState: (init: unknown) => {
      const i = state.idx++;
      const v = state.overrides.has(i) ? state.overrides.get(i) : typeof init === "function" ? (init as () => unknown)() : init;
      return [v, (x: unknown) => state.setters.set(i, [...(state.setters.get(i) ?? []), x])];
    },
  };
});
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
vi.mock("@/components/i18n-provider", () => ({ useI18n: () => ({ t: (k: string) => k, problem: (c: string) => String(c), locale: "en" }) }));
const h = setupMock();

const fields = [
  { name: "outlet_id", label: "Outlet", kind: "int" as const, required: true },
  { name: "kind", label: "Kind", kind: "enum" as const, required: true, options: [{ value: "correction", label: "c" }, { value: "write_off", label: "w" }] },
  { name: "amount_mtk", label: "Amount", kind: "int" as const, required: true, scale: 1000 },
];

/** Submits the dues form (same field definitions as dues-view.tsx) and returns the posted body, or the local errors. */
async function submit(values: Record<string, string>): Promise<{ body: Record<string, unknown> | null; errors: Record<string, string> }> {
  const { OpForm } = await import("@/components/admin/kit/op-form");
  state.overrides = new Map<number, unknown>([[0, { outlet_id: "55", kind: "correction", ...values }], [1, "Disputed due after a return"]]);
  state.setters = new Map();
  state.idx = 0;
  let posted: Record<string, unknown> | null = null;
  vi.stubGlobal("fetch", async (_u: string, init: { body: string }) => {
    posted = (JSON.parse(init.body) as { body: Record<string, unknown> }).body;
    return new Response(JSON.stringify({ data: {} }), { status: 201 });
  });
  const el = OpForm({ op: "dues.create", uuidMembers: ["client_uuid"], fields, submitLabel: "x", successKey: "dues.created" }) as ReactElement<{ onSubmit: (e: FormEvent) => Promise<void> }>;
  await el.props.onSubmit({ preventDefault() {} } as FormEvent);
  const errs = (state.setters.get(3) ?? []).filter((e) => e && Object.keys(e as object).length) as Record<string, string>[];
  return { body: posted, errors: errs.at(-1) ?? {} };
}
afterEach(() => vi.unstubAllGlobals());

describe("dues form: taka -> milli-taka", () => {
  it("baseline: -500 taka becomes -500000 mtk with a client uuid", async () => {
    const r = await submit({ amount_mtk: "-500" });
    expect(r.body?.amount_mtk).toBe(-500000);
    expect(typeof r.body?.client_uuid).toBe("string");
  });
  it("an amount finer than one milli-taka (12.3456 Tk) is refused, not silently rounded to 12346 mtk", async () => {
    const r = await submit({ amount_mtk: "12.3456" });
    expect(r.body).toBeNull();
  });
  it("rounding is symmetric in sign: -1.0005 and 1.0005 must not land on different magnitudes", async () => {
    const pos = (await submit({ amount_mtk: "1.0005" })).body?.amount_mtk as number | undefined;
    const neg = (await submit({ amount_mtk: "-1.0005" })).body?.amount_mtk as number | undefined;
    // either both refused (preferred) or both the same magnitude
    expect(pos === undefined && neg === undefined ? true : Math.abs(pos ?? 0) === Math.abs(neg ?? 0)).toBe(true);
  });
  it("hex input (0x10) is not a taka amount", async () => {
    const r = await submit({ amount_mtk: "0x10" });
    expect(r.body).toBeNull();
  });
  it("exponent input (1e3) is not accepted as 1000 taka", async () => {
    const r = await submit({ amount_mtk: "1e3" });
    expect(r.body).toBeNull();
  });
  it("an amount whose milli-taka exceeds 2^53 is refused (no silent precision loss)", async () => {
    const r = await submit({ amount_mtk: "9007199254740.993" });
    if (r.body) expect(Number.isSafeInteger(r.body.amount_mtk)).toBe(true);
  });
  it("an amount beyond int64 milli-taka (1e16 taka) is refused locally", async () => {
    const r = await submit({ amount_mtk: "10000000000000000" });
    expect(r.body).toBeNull();
  });
  it("a zero adjustment (0, or -0.0004 rounding to -0) is refused: it changes no balance", async () => {
    expect((await submit({ amount_mtk: "0" })).body).toBeNull();
    expect((await submit({ amount_mtk: "-0.0004" })).body).toBeNull();
  });
  it("Bangla digits as shown in the bn hint (-৫০০) are accepted as -500 taka", async () => {
    const r = await submit({ amount_mtk: "-৫০০" });
    expect(r.body?.amount_mtk).toBe(-500000);
  });
});

describe("dues form: outlet id", () => {
  it("outlet id 0 or negative is refused locally (contract Id minimum 1)", async () => {
    expect((await submit({ outlet_id: "0", amount_mtk: "-5" })).body).toBeNull();
    expect((await submit({ outlet_id: "-3", amount_mtk: "-5" })).body).toBeNull();
  });
  it("an outlet id beyond 2^53 is refused rather than sent rounded", async () => {
    const r = await submit({ outlet_id: "9007199254740993", amount_mtk: "-5" });
    if (r.body) expect(Number.isSafeInteger(r.body.outlet_id)).toBe(true);
  });
});

describe("dues proxy rules", () => {
  const body = (kind: string, amount_mtk: number) => ({ client_uuid: "88888888-8888-4888-8888-888888888888", outlet_id: 55, kind, amount_mtk });
  it("a write-off that RAISES the due (positive amount) is refused before the API", async () => {
    h.stub({ method: "POST", path: "/v1/admin/dues-adjustments", fn: () => ({ status: 201, body: {} }) });
    expect((await op(await admin(), { op: "dues.create", body: body("write_off", 500000), reason: "Shop closed, owner left" })).status).toBe(400);
  });
  it("a fractional amount_mtk posted straight to the proxy is refused (integer milli-taka)", async () => {
    h.stub({ method: "POST", path: "/v1/admin/dues-adjustments", fn: () => ({ status: 201, body: {} }) });
    expect((await op(await admin(), { op: "dues.create", body: body("correction", -500.5), reason: "Disputed due after a return" })).status).toBe(400);
  });
});
