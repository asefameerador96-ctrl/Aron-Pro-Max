// CHECKER (independent, T2) for F-ADM-023 / F-ADM-060: drive CodeListEditor without a DOM.
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import { CodeListEditor } from "@/components/admin/config/code-list-editor";
import type { CodeItem } from "@/lib/admin/types";

const hooks = vi.hoisted(() => ({ cells: [] as unknown[], i: 0 }));
vi.mock("react", async (orig: () => Promise<Record<string, unknown>>) => {
  const real = await orig();
  return {
    ...real,
    useState: (init: unknown) => {
      const k = hooks.i++;
      if (!(k in hooks.cells)) hooks.cells[k] = typeof init === "function" ? (init as () => unknown)() : init;
      return [hooks.cells[k], (v: unknown) => void (hooks.cells[k] = typeof v === "function" ? (v as (p: unknown) => unknown)(hooks.cells[k]) : v)];
    },
  };
});
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
vi.mock("@/components/i18n-provider", () => ({ useI18n: () => ({ t: (k: string) => k, problem: (c: string) => String(c) }) }));

type El = { type?: unknown; props: Record<string, unknown> };
function walk(n: unknown, f: (e: El) => void) {
  if (Array.isArray(n)) return n.forEach((c) => walk(c, f));
  if (!n || typeof n !== "object") return;
  const e = n as { props?: Record<string, unknown>; type?: unknown };
  if (e.props) {
    f({ type: e.type, props: e.props });
    walk(e.props.children, f);
  }
}
const qcAttrs = [
  { name: "group", label: "g", options: [{ value: "MFC", label: "MFC" }, { value: "MKT", label: "MKT" }] },
  { name: "applies_to", label: "a", options: [{ value: "app", label: "app" }, { value: "web", label: "web" }] },
];
const item = (code: string, over: Partial<CodeItem> = {}): CodeItem => ({ code, label_en: code, label_bn: null, sort: 10, valid_from: "2026-01-01", valid_to: null, ...over });
let props: { listKey: string; initial: CodeItem[]; attrs: typeof qcAttrs; today: string; canWrite: boolean };
const render = () => { hooks.i = 0; return (CodeListEditor as unknown as (p: object) => ReactElement)(props); };
const setup = (p: Partial<typeof props>) => { hooks.cells = []; hooks.i = 0; props = { listKey: "qc_fault_type", initial: [], attrs: qcAttrs, today: "2026-10-07", canWrite: true, ...p }; };
const click = (label: string, rowId?: string) => {
  let hit = 0;
  let inRow = !rowId;
  walk(render(), (e) => {
    if (rowId && e.type === "tr") inRow = e.props["data-testid"] === rowId;
    if (e.type === "button" && inRow && e.props.children === label) { (e.props.onClick as () => void)(); hit++; }
  });
  return hit;
};
const setReason = (v: string) => walk(render(), (e) => { if (typeof e.props.onChange === "function" && "error" in e.props && "id" in e.props) (e.props.onChange as (x: string) => void)(v); });
const rows = () => { const ids: string[] = []; walk(render(), (e) => { if (e.type === "tr" && e.props["data-testid"]) ids.push(String(e.props["data-testid"])); }); return ids; };
let sent: { body: { op: string; body: { items: Record<string, unknown>[] } } }[] = [];
beforeEach(() => {
  sent = [];
  vi.stubGlobal("fetch", vi.fn(async (_u: string, init: { body: string }) => { sent.push({ body: JSON.parse(init.body) }); return { ok: true, json: async () => ({}) }; }));
});
const save = async () => { click("common.save"); await new Promise((r) => setTimeout(r, 5)); return sent.map((s) => s.body.body.items); };

describe("code list editor", () => {
  it("a row added by mistake can be discarded (otherwise the whole form is stuck on a blank code)", () => {
    setup({ initial: [item("a_b", { attrs: { group: "MFC", applies_to: "app" } })] });
    click("cl.add");
    expect(rows()).toHaveLength(2);
    // press every button in the new row; one of them must remove it
    walk(render(), () => {});
    for (const label of ["cl.retire", "cl.revive", "common.delete", "cl.remove", "cl.discard"]) click(label, "row-new-1");
    expect(rows()).toHaveLength(1);
  });

  it("an existing qc item with no attrs does not show a selected value the data does not have", () => {
    setup({ initial: [item("legacy_fault")] });
    let selectValue: unknown = "unset";
    const options: string[] = [];
    walk(render(), (e) => { if (e.type === "select" && e.props["aria-label"] === "g") { selectValue = e.props.value; walk(e.props.children, (o) => options.push(String(o.props.value))); } });
    expect(options).toContain(selectValue as string);
  });

  it("retiring a row never produces valid_to before valid_from", async () => {
    setup({ initial: [item("future_x", { valid_from: "2026-12-01", attrs: { group: "MFC", applies_to: "app" } })] });
    click("cl.retire", "row-future_x");
    setReason("Retire this fault code now");
    const [items] = await save();
    expect(items).toBeDefined();
    const it0 = items![0]!;
    expect(String(it0.valid_to) >= String(it0.valid_from)).toBe(true);
  });

  it("never sends an empty items array (CodeListWrite minItems 1)", async () => {
    setup({ initial: [] });
    setReason("Initial seeding of the list");
    const all = await save();
    for (const items of all) expect(items.length).toBeGreaterThanOrEqual(1);
  });

  it("sort stays a safe integer", async () => {
    setup({ initial: [item("a_b", { attrs: { group: "MFC", applies_to: "app" } })] });
    walk(render(), (e) => { if (e.props["aria-label"] === "cl.sort") (e.props.onChange as (x: unknown) => void)({ target: { value: "9".repeat(30) } }); });
    setReason("Reorder the list please");
    const all = await save();
    for (const items of all) expect(Number.isSafeInteger(items[0]!.sort)).toBe(true);
  });

  it("PUT carries every existing item including retired ones, and no attrs for lists without attr fields", async () => {
    setup({ listKey: "force_reason", attrs: [] as never, initial: [item("a_b"), item("c_d", { valid_to: "2026-02-01" })] });
    setReason("Rename a label only");
    const [items] = await save();
    expect(items!.map((x) => x.code)).toEqual(["a_b", "c_d"]);
    expect(items![1]!.valid_to).toBe("2026-02-01");
    expect("attrs" in items![0]!).toBe(false);
  });
});
