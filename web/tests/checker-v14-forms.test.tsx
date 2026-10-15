// CHECKER v14 (T1 money): the dues OpForm and the paper backfill form submit handlers, driven directly with a stubbed useState
// (no DOM). OpForm hook order: values, reason, busy, errors, banner, ver, uuids. PaperBackfillForm: v, lines, reason, uuid, errors, banner, busy.
import type * as ReactNS from "react";
import type { FormEvent, ReactElement, ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";

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
afterEach(() => vi.unstubAllGlobals());

const fields = [
  { name: "outlet_id", label: "Outlet", kind: "int" as const, required: true },
  { name: "kind", label: "Kind", kind: "enum" as const, required: true, options: [{ value: "correction", label: "c" }, { value: "write_off", label: "w" }] },
  { name: "amount_mtk", label: "Amount", kind: "int" as const, required: true, scale: 1000, min: -(10 ** 13), max: 10 ** 13 },
  { name: "memo_client_uuid", label: "Memo", kind: "text" as const, nullable: true, maxLength: 36 },
];
const UUID = "11111111-1111-4111-8111-111111111111";

async function dues(values: Record<string, string>, reply: { status: number; body: unknown } = { status: 201, body: { data: {} } }) {
  const { OpForm } = await import("@/components/admin/kit/op-form");
  state.overrides = new Map<number, unknown>([[0, { outlet_id: "55", kind: "correction", memo_client_uuid: "", ...values }], [1, "Disputed due after a return"], [6, { client_uuid: UUID }]]);
  state.setters = new Map();
  state.idx = 0;
  const posted: Record<string, unknown>[] = [];
  vi.stubGlobal("fetch", async (_u: string, init: { body: string }) => {
    posted.push((JSON.parse(init.body) as { body: Record<string, unknown> }).body);
    return new Response(JSON.stringify(reply.body), { status: reply.status });
  });
  const el = OpForm({ op: "dues.create", uuidMembers: ["client_uuid"], fields, submitLabel: "x", successKey: "dues.created" }) as ReactElement<{ onSubmit: (e: FormEvent) => Promise<void> }>;
  await el.props.onSubmit({ preventDefault() {} } as FormEvent);
  const errors = ((state.setters.get(3) ?? []).filter((e) => e && Object.keys(e as object).length) as Record<string, string>[]).at(-1) ?? {};
  return { body: posted[0] ?? null, errors, uuidSets: (state.setters.get(6) ?? []) as Record<string, string>[] };
}
const problem = (code: string, errors: { pointer: string; code: string }[] = []) => ({ status: code === "ERR_VALIDATION" ? 400 : 409, body: { type: "about:blank", title: "x", status: 400, code, errors } });

describe("dues form: taka text to milli-taka", () => {
  it("exact conversions: Bengali digits, whitespace, three decimals, cap", async () => {
    expect((await dues({ amount_mtk: "-১২৩৪.৫" })).body?.amount_mtk).toBe(-1_234_500);
    expect((await dues({ amount_mtk: "  -5  " })).body?.amount_mtk).toBe(-5000);
    expect((await dues({ amount_mtk: "-0.001" })).body?.amount_mtk).toBe(-1);
    expect((await dues({ amount_mtk: "-10000000000" })).body?.amount_mtk).toBe(-(10 ** 13));
  });
  it("refused, never posted: 1.0005, 1e3, 0x10, -0, 0.000, above the cap, unicode minus, plus sign", async () => {
    for (const s of ["1.0005", "1e3", "0x10", "-0", "0.000", "-10000000000.001", "−500", "+500", "1 000"]) expect((await dues({ amount_mtk: s })).body, s).toBeNull();
  });
  it("a typed zero names the problem (error.field.zero), not the generic 'invalid'", async () => {
    expect((await dues({ amount_mtk: "0" })).errors.amount_mtk).toBe("error.field.zero");
  });
  it("a decimal comma ('1,5') is refused, not read as 15 taka (commas are stripped anywhere)", async () => {
    expect((await dues({ amount_mtk: "-1,5" })).body).toBeNull();
  });
});

describe("dues form: server errors land on the field, whatever the pointer style", () => {
  for (const p of ["body.amount_mtk", "/body/amount_mtk", "/amount_mtk", "body/amount_mtk"]) {
    it(p, async () => {
      for (const code of ["zero", "above_max", "write_off_must_be_negative"]) expect((await dues({ amount_mtk: "-5" }, problem("ERR_VALIDATION", [{ pointer: p, code }]))).errors.amount_mtk).toBe(`error.field.${code}`);
    });
  }
  it("memo and reason pointers", async () => {
    const r = await dues({ amount_mtk: "-5", memo_client_uuid: UUID }, problem("ERR_VALIDATION", [{ pointer: "body.memo_client_uuid", code: "not_an_outlet_memo" }, { pointer: "body.reason", code: "too_short" }]));
    expect(r.errors).toEqual({ memo_client_uuid: "error.field.not_an_outlet_memo", reason: "error.field.too_short" });
  });
});

describe("dues form: idempotency key", () => {
  it("a failure keeps the uuid (the retry reuses it); a success renews it", async () => {
    const failed = await dues({ amount_mtk: "-5" }, problem("ERR_VALIDATION", [{ pointer: "body.amount_mtk", code: "zero" }]));
    expect(failed.body?.client_uuid).toBe(UUID);
    expect(failed.uuidSets).toEqual([]);
    const net = await dues({ amount_mtk: "-5" }, { status: 503, body: {} });
    expect(net.uuidSets).toEqual([]);
    const ok = await dues({ amount_mtk: "-5" });
    expect(ok.uuidSets).toHaveLength(1);
    expect(ok.uuidSets[0]!.client_uuid).not.toBe(UUID);
  });
});

function findSave(node: ReactNode): (() => Promise<void>) | null {
  if (!node || typeof node !== "object") return null;
  if (Array.isArray(node)) {
    for (const n of node) {
      const f = findSave(n as ReactNode);
      if (f) return f;
    }
    return null;
  }
  const el = node as ReactElement<{ children?: ReactNode; onClick?: () => Promise<void>; type?: string }>;
  if (el.type === "button" && el.props.children === "de.save") return el.props.onClick ?? null;
  return findSave(el.props?.children);
}

async function backfill(reply: { status: number; body: unknown }, lines = 4) {
  const { PaperBackfillForm } = await import("@/components/admin/config/paper-backfill-form");
  state.overrides = new Map<number, unknown>([
    [0, { memo_no: "sr334001-261014-017", business_date: "2026-10-14", user_id: "5", route_id: "9", outlet_id: "55", paid: "0" }],
    [1, Array.from({ length: lines }, () => ({ sku_id: "1", qty: "10", kind: "sale" }))],
    [2, "Phone died in the field"],
    [3, UUID],
  ]);
  state.setters = new Map();
  state.idx = 0;
  const posted: Record<string, unknown>[] = [];
  vi.stubGlobal("fetch", async (_u: string, init: { body: string }) => {
    posted.push((JSON.parse(init.body) as { body: Record<string, unknown> }).body);
    return new Response(JSON.stringify(reply.body), { status: reply.status });
  });
  await findSave(PaperBackfillForm() as ReactElement)!();
  return { body: posted[0] ?? null, errors: ((state.setters.get(4) ?? []) as Record<string, string>[]).at(-1) ?? {}, banners: (state.setters.get(5) ?? []) as { ok: boolean; text: string }[], uuidSets: state.setters.get(3) ?? [] };
}

describe("paper backfill form", () => {
  it("posts integer milli-taka, no discount member, the given uuid", async () => {
    const r = await backfill({ status: 200, body: { data: { memo_client_uuid: UUID, status: "created", net_mtk: 1 } } });
    expect(r.body).toMatchObject({ client_uuid: UUID, paid_mtk: 0 });
    expect(r.body).not.toHaveProperty("discount_mtk");
    expect(r.uuidSets).toHaveLength(1);
  });
  it("duplicate_memo_no is a success status", async () => {
    const r = await backfill({ status: 200, body: { data: { memo_client_uuid: UUID, status: "duplicate_memo_no", net_mtk: 1 } } });
    expect(r.banners.at(-1)).toEqual({ ok: true, text: "de.status.duplicate_memo_no" });
  });
  it("a server error on line 4 is shown on line 4, not on line 1", async () => {
    const r = await backfill(problem("ERR_VALIDATION", [{ pointer: "body.lines[3].qty_base", code: "out_of_range" }]));
    expect(r.errors).toHaveProperty("line3");
    expect(r.errors).not.toHaveProperty("line0");
  });
  it("paid above the cap shows above_max on the paid field (pointer body.paid_mtk)", async () => {
    const r = await backfill(problem("ERR_VALIDATION", [{ pointer: "body.paid_mtk", code: "above_max" }]));
    expect(r.errors.paid).toBe("error.field.above_max");
  });
});
