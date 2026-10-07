// Checker ADM-9 (F-ADM-005, T1 money rails): the price grid client and the prices page, driven through a tiny hook harness
// (no DOM in this repo's vitest). A failing test here is a confirmed defect against the contract or docs/24 s7.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactElement, ReactNode } from "react";

const hooks = vi.hoisted(() => ({ slots: [] as unknown[], i: 0, locale: "en" as "en" | "bn" }));
vi.mock("react", async (orig) => {
  const real = (await orig()) as Record<string, unknown>;
  const useState = (init: unknown) => {
    const k = hooks.i++;
    if (!(k in hooks.slots)) hooks.slots[k] = typeof init === "function" ? (init as () => unknown)() : init;
    const set = (v: unknown) => { hooks.slots[k] = typeof v === "function" ? (v as (p: unknown) => unknown)(hooks.slots[k]) : v; };
    return [hooks.slots[k], set];
  };
  const useRef = (init: unknown) => {
    const k = hooks.i++;
    if (!(k in hooks.slots)) hooks.slots[k] = { current: init };
    return hooks.slots[k];
  };
  return { ...real, default: real, useState, useRef, useContext: () => ({ locale: hooks.locale }), useMemo: (f: () => unknown) => f() };
});
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));

// Page-level mocks (server component): API answers and session.
let apiRows: Record<string, { ok: boolean; items: Record<string, unknown>[] }> = {};
vi.mock("@/lib/api/raw", () => ({
  rawRequest: vi.fn(async (r: { path: string }) => {
    const a = apiRows[r.path] ?? { ok: true, items: [] };
    return a.ok ? { ok: true, status: 200, data: { items: a.items, next_cursor: null }, response: new Response(null) } : { ok: false, status: 503, problem: { code: "ERR_SERVICE_UNAVAILABLE" } };
  }),
}));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => ({ user: { role: "ADMIN" }, at: "tok" }), currentPath: async () => "/admin/prices" }));
vi.mock("@/lib/auth/service", () => ({ getLocale: async () => hooks.locale }));

import { PriceGrid, type PriceSku } from "@/components/admin/price-grid";
import { ReasonField } from "@/components/admin/kit/reason-field";
import PricesPage from "@/app/admin/prices/page";
import { t } from "@/lib/i18n";

type El = ReactElement<Record<string, unknown> & { children?: ReactNode }>;
function walk(n: ReactNode, out: El[] = []): El[] {
  if (Array.isArray(n)) n.forEach((x) => walk(x, out));
  else if (n && typeof n === "object" && "props" in n) {
    out.push(n as El);
    walk((n as El).props.children, out);
  }
  return out;
}
type Props = Parameters<typeof PriceGrid>[0];
const render = (p: Props): El[] => {
  hooks.i = 0;
  return walk(PriceGrid(p) as ReactNode);
};
const byLabel = (els: El[], l: string) => els.find((e) => e.props["aria-label"] === l)!;
const byTestId = (els: El[], id: string) => els.find((e) => e.props["data-testid"] === id);
const textOf = (n: ReactNode): string => (Array.isArray(n) ? n.map(textOf).join("") : typeof n === "string" || typeof n === "number" ? String(n) : n && typeof n === "object" && "props" in n ? textOf((n as El).props.children) : "");

type Sent = { op: string; body: { batch_uuid: string; valid_from: string; prices: { sku_id: number; price_type: string; amount_mtk: number; per_base_qty?: number }[] }; reason: string };
let sent: Sent[] = [];
let replies: (() => Promise<Response>)[] = [];
beforeEach(() => {
  hooks.slots = [];
  hooks.locale = "en";
  sent = [];
  replies = [];
  apiRows = {};
  vi.stubGlobal("fetch", async (_u: string, init: { body: string }) => {
    sent.push(JSON.parse(init.body));
    const next = replies.shift();
    return next ? next() : new Response(JSON.stringify({ data: {} }), { status: 200 });
  });
});
afterEach(() => vi.unstubAllGlobals());

const json = (status: number, body: unknown) => async () => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const PREVIEW_OK = { data: { batch_uuid: "x", skus_affected: 1, outlets_affected: 120, devices_affected: 8, max_change_pct: 5, approval_required: false } };
const TODAY = "2026-10-07";
const TYPE_LABELS = { outlet: "Outlet", cc: "CC", distributor: "Distributor", reporting: "Reporting", nto: "NTO" } as const;
const SKUS: PriceSku[] = [{ id: 1, code: "GL-20S", name: "Gold Leaf", current: { outlet: 1_200_000, cc: 1_150_000 } }];
const props = (over: Partial<Props> = {}): Props => ({ skus: SKUS, minDate: TODAY, canWrite: true, typeLabels: TYPE_LABELS, ...over });

/** Types a cell, a date and a reason, re-rendering between each, and returns the latest tree. */
function fill(p: Props, cells: [string, string][], date?: string, reason = "Price revision for October"): El[] {
  let els = render(p);
  for (const [label, v] of cells) {
    (byLabel(els, label).props.onChange as (e: unknown) => void)({ target: { value: v } });
    els = render(p);
  }
  if (date !== undefined) {
    (els.find((e) => e.props.id === "f-valid_from")!.props.onChange as (e: unknown) => void)({ target: { value: date } });
    els = render(p);
  }
  (els.find((e) => e.type === ReasonField)!.props.onChange as (v: string) => void)(reason);
  return render(p);
}
const click = async (els: El[], id: string) => (byTestId(els, id)!.props.onClick as () => Promise<void>)();

describe("checker-adm9 PriceGrid: what is previewed is what is published", () => {
  it("guard (passes): preview and publish send the identical body (batch_uuid, valid_from, prices, reason) as integer mtk", async () => {
    const p = props();
    let els = fill(p, [["GL-20S Outlet", "1260.5"], ["GL-20S NTO", "০.০০১"]], "2026-10-10");
    replies.push(json(200, PREVIEW_OK), json(201, { data: { items: [], price_list_version: 3 } }));
    await click(els, "price-preview");
    els = render(p);
    await click(els, "price-publish");
    expect(sent).toHaveLength(2);
    expect(sent[0]!.op).toBe("price.preview");
    expect(sent[1]!.op).toBe("price.publish");
    expect(sent[1]!.body).toEqual(sent[0]!.body);
    expect(sent[1]!.reason).toBe(sent[0]!.reason);
    expect(sent[0]!.body.prices).toEqual([{ sku_id: 1, price_type: "outlet", amount_mtk: 1_260_500 }, { sku_id: 1, price_type: "nto", amount_mtk: 1 }]);
  });

  it("guard (passes): a retry after a lost publish response reuses the batch_uuid; any edit mints a new one", async () => {
    const p = props();
    let els = fill(p, [["GL-20S Outlet", "1260"]], "2026-10-10");
    const lost = async (): Promise<Response> => { throw new TypeError("network"); };
    replies.push(json(200, PREVIEW_OK), lost, lost);
    await click(els, "price-preview");
    els = render(p);
    await click(els, "price-publish"); // response lost
    els = render(p);
    await click(els, "price-publish"); // retry
    expect(sent[2]!.body.batch_uuid).toBe(sent[1]!.body.batch_uuid);
    els = render(p);
    (byLabel(els, "GL-20S CC").props.onChange as (e: unknown) => void)({ target: { value: "1160" } });
    els = render(p);
    replies.push(json(200, PREVIEW_OK));
    await click(els, "price-preview");
    expect(sent[3]!.body.batch_uuid).not.toBe(sent[1]!.body.batch_uuid);
  });

  it("DEFECT (medium): the default effective date is today, which the contract and the BFF refuse (future Dhaka date only); the grid lets it through", async () => {
    const p = props();
    const els = fill(p, [["GL-20S Outlet", "1260"]]); // date left at its default
    replies.push(json(200, PREVIEW_OK));
    await click(els, "price-preview");
    // Either the grid blocks today client-side (no request) or it defaults to a future date; it must never send today.
    for (const s of sent) expect(s.body.valid_from > TODAY).toBe(true);
    // and the date picker's own minimum must be a future date
    expect(String(els.find((e) => e.props.id === "f-valid_from")!.props.min) > TODAY).toBe(true);
  });

  it("DEFECT (high): a batch the preview flagged for a second approver is reported as 'Published' when the API answers the contract's 201 SkuPriceList", async () => {
    const p = props();
    let els = fill(p, [["GL-20S Outlet", "2000"]], "2026-10-10");
    replies.push(json(200, { data: { ...PREVIEW_OK.data, max_change_pct: 66.67, approval_required: true } }), json(201, { data: { items: [{ id: 9, sku_id: 1, price_type: "outlet", amount_mtk: 2_000_000, per_base_qty: 1, valid_from: "2026-10-10", valid_to: null }], price_list_version: 4 } }));
    await click(els, "price-preview");
    els = render(p);
    await click(els, "price-publish");
    els = render(p);
    const banner = byTestId(els, "form-ok") ?? byTestId(els, "form-error");
    expect(textOf(banner?.props.children)).toBe(t("en", "prices.pending_approval"));
  });

  it("DEFECT (high): per_base_qty is lost: a row priced per 10 sticks, raised 1 % as shown, is published 10x too high per stick", async () => {
    // The API row in force: 79,350 mtk for 10 sticks (per_base_qty 10) = 7,935 mtk a stick (docs/24 s7, SkuPrice.per_base_qty).
    apiRows = {
      "/v1/admin/skus": { ok: true, items: [{ id: 1, code: "MaxR-10S", name: "Max Red 10s", base_unit: "stick", base_per_pack: 10, status: "active" }] },
      "/v1/admin/prices": { ok: true, items: [{ id: 5, sku_id: 1, price_type: "outlet", amount_mtk: 79_350, per_base_qty: 10, valid_from: "2026-09-01", valid_to: null }] },
    };
    const page = (await PricesPage()) as El;
    const grid = walk(page).find((e) => e.type === PriceGrid)!;
    const p = grid.props as unknown as Props;
    // The admin sees the amount in force (placeholder) and types it up by 1 %.
    const shown = String(byLabel(render(p), "MaxR-10S Outlet").props.placeholder);
    const raised = (Number(shown) * 1.01).toFixed(3);
    hooks.slots = [];
    const els = fill(p, [["MaxR-10S Outlet", raised]], "2026-10-10");
    replies.push(json(200, PREVIEW_OK));
    await click(els, "price-preview");
    const row = sent[0]!.body.prices[0]!;
    const perStick = row.amount_mtk / (row.per_base_qty ?? 1);
    expect(perStick).toBeGreaterThan(7_935 * 0.98);
    expect(perStick).toBeLessThan(7_935 * 1.05); // sent: 80,144 mtk with no per_base_qty (server default 1) = 80.144 Tk a stick
  });

  it("DEFECT (low): when the price list fails to load, the grid stays writable with every price shown as '—'", async () => {
    apiRows = {
      "/v1/admin/skus": { ok: true, items: [{ id: 1, code: "GL-20S", name: "Gold Leaf", status: "active" }] },
      "/v1/admin/prices": { ok: false, items: [] },
    };
    const page = (await PricesPage()) as El;
    const grid = walk(page).find((e) => e.type === PriceGrid)!;
    expect(grid.props.canWrite).toBe(false);
  });

  it("DEFECT (low): in Bangla the money example in the note is printed with ASCII digits (12.500), not ১২.৫০০", () => {
    hooks.locale = "bn";
    const els = render(props());
    const note = els.filter((e) => e.type === "p").map((e) => textOf(e.props.children)).find((s) => s.includes("12") || s.includes("১২"))!;
    expect(note).toContain("১২.৫০০");
  });

  it("guard (passes): the five types are each editable per SKU, Bengali digits shown for the price in force in bn", () => {
    hooks.locale = "bn";
    const els = render(props());
    for (const ty of Object.values(TYPE_LABELS)) expect(byLabel(els, `GL-20S ${ty}`)).toBeTruthy();
    expect(byLabel(els, "GL-20S Outlet").props.placeholder).toBe("১২০০.০০০");
    expect(byLabel(els, "GL-20S NTO").props.placeholder).toBe("—");
  });
});
