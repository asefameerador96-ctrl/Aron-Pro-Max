// Checker (F-WEB-050 / F-WEB-052): drives the client grids through a tiny hook harness (no DOM in this repo's vitest).
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactElement, ReactNode } from "react";

// ---- minimal hook runtime: useState slots by call order, context default, memo = call ----
const hooks = vi.hoisted(() => ({ slots: [] as unknown[], i: 0 }));
vi.mock("react", async (orig) => {
  const real = (await orig()) as Record<string, unknown>;
  const useState = (init: unknown) => {
    const k = hooks.i++;
    if (!(k in hooks.slots)) hooks.slots[k] = typeof init === "function" ? (init as () => unknown)() : init;
    const set = (v: unknown) => { hooks.slots[k] = typeof v === "function" ? (v as (p: unknown) => unknown)(hooks.slots[k]) : v; };
    return [hooks.slots[k], set];
  };
  return { ...real, default: real, useState, useContext: () => ({ locale: "en" }), useMemo: (f: () => unknown) => f() };
});
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));

import { QcGrid } from "@/components/admin/config/qc-grid";
import { WebEntryGrid } from "@/components/admin/config/web-entry-grid";

type El = ReactElement<Record<string, unknown> & { children?: ReactNode }>;
function walk(n: ReactNode, out: El[] = []): El[] {
  if (Array.isArray(n)) n.forEach((x) => walk(x, out));
  else if (n && typeof n === "object" && "props" in n) {
    out.push(n as El);
    walk((n as El).props.children, out);
  }
  return out;
}
function render<P>(C: (p: P) => ReactNode, p: P): El[] {
  hooks.i = 0;
  return walk(C(p));
}
const byLabel = (els: El[], label: string) => els.find((e) => e.props["aria-label"] === label)!;
const button = (els: El[]) => els.find((e) => e.type === "button" && typeof e.props.onClick === "function")!;

let sent: Record<string, unknown>[] = [];
let reply: { status: number; body: unknown } = { status: 200, body: {} };
beforeEach(() => {
  hooks.slots = [];
  sent = [];
  reply = { status: 200, body: {} };
  vi.stubGlobal("fetch", async (_u: string, init: { body: string }) => {
    sent.push(JSON.parse(init.body));
    return new Response(JSON.stringify(reply.body), { status: reply.status, headers: { "Content-Type": "application/json" } });
  });
});
afterEach(() => vi.unstubAllGlobals());

const base = { routeId: 9, date: "2026-10-07", targetOutlets: 40, initialCalls: 4, appOverlap: false, canWrite: true };

describe("checker WebEntryGrid", () => {
  it("DEFECT (T1): a re-save keeps the stored lines of SKUs not in the grid (inactive / beyond the 500 active SKUs) instead of silently replacing them away", async () => {
    const props = { ...base, skus: [{ id: 1, label: "Gold Leaf" }], saved: true, initialLines: [{ sku_id: 1, issue_qty_base: 10, return_qty_base: 2, memo_count: 3 }, { sku_id: 2, issue_qty_base: 500, return_qty_base: 0, memo_count: 7 }] };
    let els = render(WebEntryGrid, props);
    // supply the re-save reason through the ReasonField's onChange
    const rf = els.find((e) => typeof e.type === "function" && "onChange" in e.props && "value" in e.props && !("aria-label" in e.props))!;
    (rf.props.onChange as (v: string) => void)("Corrected issue after the audit");
    els = render(WebEntryGrid, props);
    await (button(els).props.onClick as () => Promise<void>)();
    expect(sent).toHaveLength(1);
    const lines = (sent[0]!.body as { lines: { sku_id: number }[] }).lines;
    expect(lines.map((l) => l.sku_id)).toContain(2);
  });

  it("DEFECT: quantities typed with Bangla digits are kept (converted), not silently stripped", () => {
    const props = { ...base, skus: [{ id: 1, label: "Gold Leaf" }], saved: false, initialLines: [] };
    let els = render(WebEntryGrid, props);
    (byLabel(els, "Gold Leaf Issue").props.onChange as (e: unknown) => void)({ target: { value: "১২" } });
    els = render(WebEntryGrid, props);
    expect(byLabel(els, "Gold Leaf Issue").props.value).toBe("12");
  });

  it("DEFECT: when the page's saved flag is stale (another user saved meanwhile) and the API refuses the reason-less re-save, the grid lets the user give a reason", async () => {
    const props = { ...base, skus: [{ id: 1, label: "Gold Leaf" }], saved: false, initialLines: [] };
    let els = render(WebEntryGrid, props);
    (byLabel(els, "Gold Leaf Issue").props.onChange as (e: unknown) => void)({ target: { value: "5" } });
    els = render(WebEntryGrid, props);
    reply = { status: 400, body: { type: "x", title: "x", status: 400, code: "ERR_VALIDATION", errors: [{ pointer: "/change_reason", code: "required" }] } };
    await (button(els).props.onClick as () => Promise<void>)();
    expect(sent[0]).not.toHaveProperty("reason");
    els = render(WebEntryGrid, props);
    const hasReason = els.some((e) => typeof e.type === "function" && (e.type as { name?: string }).name === "ReasonField");
    expect(hasReason).toBe(true);
  });

  it("held: a failed save retries with the same client_uuid; a success renews it", async () => {
    const props = { ...base, skus: [{ id: 1, label: "Gold Leaf" }], saved: false, initialLines: [] };
    let els = render(WebEntryGrid, props);
    (byLabel(els, "Gold Leaf Issue").props.onChange as (e: unknown) => void)({ target: { value: "5" } });
    reply = { status: 500, body: { code: "ERR_INTERNAL" } };
    els = render(WebEntryGrid, props);
    await (button(els).props.onClick as () => Promise<void>)();
    reply = { status: 200, body: {} };
    els = render(WebEntryGrid, props);
    await (button(els).props.onClick as () => Promise<void>)();
    els = render(WebEntryGrid, props);
    await (button(els).props.onClick as () => Promise<void>)();
    const u = sent.map((s) => (s.body as { client_uuid: string }).client_uuid);
    expect(u[0]).toBe(u[1]);
    expect(u[2]).not.toBe(u[1]);
  });
});

describe("checker QcGrid", () => {
  it("DEFECT: a QC quantity typed with Bangla digits is kept, not silently stripped", () => {
    const props = { source: "market" as const, zoneId: 334, routeId: 9, date: "2026-10-07", skus: [{ id: 1, label: "Gold Leaf" }], faults: [{ code: "broken_stick", label: "Broken", group: "MFC" }] };
    let els = render(QcGrid, props);
    (byLabel(els, "Gold Leaf Broken").props.onChange as (e: unknown) => void)({ target: { value: "৩" } });
    els = render(QcGrid, props);
    expect(byLabel(els, "Gold Leaf Broken").props.value).toBe("3");
  });
  it("held: market never sends a reason; warehouse refuses a short reason before any call", async () => {
    const props = { source: "warehouse" as const, zoneId: 334, date: "2026-10-07", skus: [{ id: 1, label: "Gold Leaf" }], faults: [{ code: "broken_stick", label: "Broken", group: "MFC" }] };
    let els = render(QcGrid, props);
    (byLabel(els, "Gold Leaf Broken").props.onChange as (e: unknown) => void)({ target: { value: "3" } });
    els = render(QcGrid, props);
    await (button(els).props.onClick as () => Promise<void>)();
    expect(sent).toHaveLength(0);
  });
});
