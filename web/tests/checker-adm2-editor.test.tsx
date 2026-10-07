// CHECKER ADM-2 (T2): drive the /admin/code-lists/[key] editor (components/admin/codelist-editor.tsx) and its page without a DOM.
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import { CodeListEditor, type EditorItem } from "@/components/admin/codelist-editor";
import CodeListPage from "@/app/admin/code-lists/[key]/page";
import { setupMock } from "./helpers/bff";

const hooks = vi.hoisted(() => ({ cells: [] as unknown[], i: 0, token: "" }));
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
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }), notFound: () => { throw new Error("notFound"); } }));
vi.mock("@/components/i18n-provider", () => ({ useI18n: () => ({ t: (k: string) => k, number: (n: number) => String(n), problem: (c: string) => String(c) }) }));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => ({ at: hooks.token, user: { role: "ADMIN" } }) }));
vi.mock("@/lib/auth/service", async (orig: () => Promise<Record<string, unknown>>) => ({ ...(await orig()), getLocale: async () => "en" }));

const { mock, signIn, token } = setupMock();

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
const item = (code: string, over: Partial<EditorItem> = {}): EditorItem => ({ code, label_en: code, label_bn: "", sort: "1", valid_from: "2026-01-01", valid_to: "", attrs: {}, saved: true, ...over });
let props: { listKey: string; items: EditorItem[]; attrs: { key: string; label: string; options?: string[] }[]; canWrite: boolean };
const setup = (p: Partial<typeof props>) => { hooks.cells = []; hooks.i = 0; props = { listKey: "channel", items: [item("retail")], attrs: [], canWrite: true, ...p }; };
const render = () => { hooks.i = 0; return (CodeListEditor as unknown as (p: object) => ReactElement)(props); };
const buttons = () => { const out: El[] = []; walk(render(), (e) => { if (e.type === "button") out.push(e); }); return out; };
const clickId = (id: string) => { for (const b of buttons()) if (b.props["data-testid"] === id) (b.props.onClick as () => void)(); };
const setReason = (v: string) => walk(render(), (e) => { if (typeof e.props.onChange === "function" && "error" in e.props && "value" in e.props) (e.props.onChange as (x: string) => void)(v); });
const input = (row: number, label: string): El | undefined => {
  let r = -1;
  let found: El | undefined;
  walk(render(), (e) => {
    if (e.type === "tr" && e.props["data-testid"] === "codelist-row") r++;
    if (r === row && e.type === "input" && e.props["aria-label"] === label) found = e;
  });
  return found;
};
const type = (row: number, label: string, v: string) => (input(row, label)!.props.onChange as (e: { target: { value: string } }) => void)({ target: { value: v } });
let sent: Record<string, unknown>[] = [];
const realFetch = globalThis.fetch;
beforeEach(() => {
  sent = [];
  vi.stubGlobal("fetch", vi.fn(async (u: string | URL | Request, init?: { body?: string }) => {
    if (!String(u).startsWith("/api/bff/")) return realFetch(u as string, init as RequestInit);
    sent.push(JSON.parse(String(init?.body)));
    return { ok: true, json: async () => ({}) };
  }));
});
const save = async () => { clickId("codelist-save"); await new Promise((r) => setTimeout(r, 5)); };

describe("code list editor", () => {
  it("AD2-21: a row added by mistake can be discarded (a blank new row otherwise blocks every save until reload)", () => {
    setup({});
    clickId("codelist-add");
    const ids = buttons().map((b) => String(b.props["data-testid"] ?? ""));
    expect(ids.filter((i) => i !== "codelist-add" && i !== "codelist-save")).not.toHaveLength(0);
  });
  it("AD2-22: after a successful save the new code is locked (saved), like every other saved code", async () => {
    setup({});
    clickId("codelist-add");
    type(1, "codelist.col.code", "extra_one");
    type(1, "codelist.col.label_en", "Extra");
    setReason("A reason that is long enough");
    await save();
    expect(sent).toHaveLength(1);
    expect(input(1, "codelist.col.code")!.props.readOnly).toBe(true);
  });
  it("AD2-23: a Bengali-digit sort (১২) is accepted and sent as 12", async () => {
    setup({});
    type(0, "codelist.col.sort", "১২");
    setReason("A reason that is long enough");
    await save();
    expect(sent).toHaveLength(1);
    expect((sent[0]!.items as { sort: number }[])[0]!.sort).toBe(12);
  });
  it("AD2-24: valid_to before valid_from is refused in the editor before anything is sent", async () => {
    setup({ items: [item("retail", { valid_from: "2026-06-01" })] });
    (input(0, "codelist.col.valid_to")!.props.onChange as (e: { target: { value: string } }) => void)({ target: { value: "2026-01-01" } });
    setReason("A reason that is long enough");
    await save();
    expect(sent).toHaveLength(0);
  });
  it("AD2-25: the row error names the field that is wrong (not only the row number)", async () => {
    setup({});
    type(0, "codelist.col.sort", "abc");
    setReason("A reason that is long enough");
    await save();
    const msgs: string[] = [];
    walk(render(), (e) => { if (e.type === "p" && e.props.role === "alert") msgs.push(JSON.stringify(e.props.children)); });
    expect(msgs.join(" ")).toMatch(/sort/i);
  });
});

describe("page to editor round trip", () => {
  it("AD2-26: saving an untouched list leaves number, boolean and null attrs as they were (no stringifying, no dropping)", async () => {
    await signIn("madmin1");
    hooks.token = token();
    (mock.state.codeLists.channel![0] as { attrs?: unknown }).attrs = { needs_ref: true, fee: 3, memo: null, tag: "x" };
    const tree = (await CodeListPage({ params: Promise.resolve({ key: "channel" }) })) as ReactElement;
    let editorProps: typeof props | undefined;
    walk(tree, (e) => { if (e.type === CodeListEditor) editorProps = e.props as unknown as typeof props; });
    expect(editorProps).toBeDefined();
    hooks.cells = [];
    props = { ...editorProps!, attrs: [] };
    setReason("A reason that is long enough");
    await save();
    expect(sent).toHaveLength(1);
    expect((sent[0]!.items as { attrs: unknown }[])[0]!.attrs).toEqual({ needs_ref: true, fee: 3, memo: null, tag: "x" });
  });
});
