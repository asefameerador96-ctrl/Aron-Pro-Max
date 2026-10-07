// CHECKER ADM-8 (T2) for F-TSO-025: drives components/admin/radius-proposal.tsx without a DOM (state cells mocked, as in checker-adm6-ui).
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import { RadiusProposal } from "@/components/admin/radius-proposal";
import { t as tr, type Locale, type MessageKey } from "@/lib/i18n";

const hooks = vi.hoisted(() => ({ cells: [] as unknown[], i: 0, calls: [] as unknown[], reply: { ok: true, data: { status: "pending_approval" } } as unknown }));
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
vi.mock("@/components/i18n-provider", () => ({
  useI18n: () => ({ locale: "en", t: (k: MessageKey, v?: Record<string, string | number>) => tr("en" as Locale, k, v), number: (n: number) => String(n), problem: (c: string) => String(c) }),
}));
vi.mock("@/components/admin/master-op-client", () => ({
  callMasterOp: async (op: string, args: unknown) => {
    hooks.calls.push({ op, args });
    return hooks.reply;
  },
}));

type El = { type?: unknown; props: Record<string, unknown> };
function walk(n: unknown, f: (e: El) => void) {
  if (Array.isArray(n)) return n.forEach((c) => walk(c, f));
  if (!n || typeof n !== "object") return;
  const e = n as { props?: Record<string, unknown>; type?: unknown };
  if (e.props) {
    f(e as El);
    walk(e.props.children, f);
    if (typeof e.type === "function") {
      try {
        walk((e.type as (p: unknown) => unknown)(e.props), f);
      } catch {
        /* hooks outside render */
      }
    }
  }
}
function render(): { find: (pred: (e: El) => boolean) => El | undefined; texts: string[] } {
  hooks.i = 0;
  const tree = (RadiusProposal as unknown as (p: unknown) => ReactElement)({ territories: [{ id: 6, label: "T-334 · Banani" }] });
  const texts: string[] = [];
  const found: El[] = [];
  walk(tree, (e) => {
    found.push(e);
    if (typeof e.props.children === "string") texts.push(e.props.children);
  });
  return { find: (p) => found.find(p), texts };
}
const fill = (id: string, v: string) => {
  const r = render();
  (r.find((e) => e.props.id === id)!.props.onChange as (ev: unknown) => void)({ target: { value: v } });
};
const submit = async () => {
  const r = render();
  await (r.find((e) => e.props["data-testid"] === "radius-submit")!.props.onClick as () => Promise<void>)();
  return render();
};

beforeEach(() => {
  hooks.cells = [];
  hooks.calls = [];
  hooks.reply = { ok: true, data: { status: "pending_approval" } };
});

describe("radius proposal UI", () => {
  it("Bengali digits are read as the number they show", async () => {
    fill("f-radius", "১২০");
    fill("reason", "Dense market near the station");
    await submit();
    expect(hooks.calls).toHaveLength(1);
    expect((hooks.calls[0] as { args: { body: { changes: { value: number }[] } } }).args.body.changes[0]!.value).toBe(120);
  });
  it("a reason made of zero-width characters is refused on the client, like the server does", async () => {
    fill("f-radius", "120");
    fill("reason", "​".repeat(12));
    await submit();
    expect(hooks.calls).toHaveLength(0);
  });
  it("the page does not claim an outcome the API did not give", async () => {
    hooks.reply = { ok: true, data: null };
    fill("f-radius", "120");
    fill("reason", "Dense market near the station");
    const r = await submit();
    expect(r.texts.join(" ")).not.toMatch(/waiting for approval/i);
  });
  it("a status without its own text never shows a raw placeholder or the word undefined", async () => {
    hooks.reply = { ok: true, data: { status: "rejected" } };
    fill("f-radius", "120");
    fill("reason", "Dense market near the station");
    const r = await submit();
    const msg = r.texts.join(" ");
    expect(msg).not.toMatch(/\{status\}|undefined/);
  });
});
