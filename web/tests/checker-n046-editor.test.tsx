// CHECKER (independent, T1) for N-046: drive the package list editor without a DOM and try to block what must never be blocked.
import { describe, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import { PackageListEditor } from "@/components/admin/config/package-list-editor";

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
const props = { keyName: "cfg.device.blocked_packages", title: "Blocked", initial: ["com.facebook.katana"], max: 300, canWrite: true };
const render = () => { hooks.i = 0; return (PackageListEditor as unknown as (p: object) => ReactElement)(props); };

function tryAdd(pkg: string): string[] {
  hooks.cells = []; hooks.i = 0;
  walk(render(), (e) => { if (e.props["aria-label"] === "ab.add") (e.props.onChange as (x: unknown) => void)({ target: { value: pkg } }); });
  walk(render(), (e) => { if (e.type === "button" && e.props.children === "ab.add.button") (e.props.onClick as () => void)(); });
  const out: string[] = [];
  walk(render(), (e) => { if (e.type === "span" && typeof e.props.className === "string" && e.props.className.includes("font-mono")) out.push(String(e.props.children)); });
  return out;
}

describe("checker N-046 package list editor", () => {
  it("adds an ordinary package (control)", () => {
    expect(tryAdd("com.new.game")).toEqual(["com.facebook.katana", "com.new.game"]);
  });
  it("refuses to put the Aron app itself on the blocked list", () => {
    expect(tryAdd("com.aktcl.aron.sr")).not.toContain("com.aktcl.aron.sr");
  });
  it("refuses to put an always-allowed essential (the dialer) on the blocked list", () => {
    expect(tryAdd("com.google.android.dialer")).not.toContain("com.google.android.dialer");
  });
});
