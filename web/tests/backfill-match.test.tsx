// F-ADM-030b: the "same memo as backfill" action: success lists, 404, 409 ERR_REQUEST_STATE, 409 ERR_CONFLICT per-difference list, one retry on 503.
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import { BackfillMatchForm } from "@/components/admin/config/backfill-match-form";
import { t as tr, type Locale, type MessageKey } from "@/lib/i18n";

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
vi.mock("@/components/i18n-provider", () => ({
  useI18n: () => ({ locale: "en", t: (k: MessageKey, v?: Record<string, string | number>) => tr("en" as Locale, k, v), number: (n: number) => String(n), problem: (c: string) => `problem:${c}` }),
}));

type El = { type?: unknown; props: Record<string, unknown> };
function walk(n: unknown, f: (e: El) => void) {
  if (Array.isArray(n)) return n.forEach((c) => walk(c, f));
  if (typeof n === "string" || typeof n === "number") return;
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
const textOf = (n: unknown): string => (Array.isArray(n) ? n.map(textOf).join("") : typeof n === "string" || typeof n === "number" ? String(n) : n && typeof n === "object" && "props" in n ? textOf((n as El).props.children) : "");
function render() {
  hooks.i = 0;
  const found: El[] = [];
  walk((BackfillMatchForm as unknown as (p: unknown) => ReactElement)({ quarantineId: 7 }), (e) => found.push(e));
  return { by: (id: string) => found.find((e) => e.props["data-testid"] === id), found };
}
const REASON = "Same memo keyed on paper";
async function go(replies: { status: number; body: unknown }[]) {
  const calls: { url: string; body: Record<string, unknown> }[] = [];
  vi.stubGlobal("fetch", async (url: string, init: { body: string }) => {
    calls.push({ url, body: JSON.parse(init.body) as Record<string, unknown> });
    const r = replies.shift() ?? { status: 200, body: {} };
    return new Response(JSON.stringify(r.body), { status: r.status });
  });
  (render().found.find((e) => typeof e.props.onChange === "function" && e.props.id === "bm-reason")!.props.onChange as (v: string) => void)(REASON);
  (render().by("bm-submit")!.props.onClick as () => void)();
  await new Promise((r) => setTimeout(r, 5));
  return calls;
}
beforeEach(() => {
  hooks.cells = [];
});

describe("backfill match form", () => {
  it("posts the whitelisted op for the item with the reason", async () => {
    const calls = await go([{ status: 200, body: { data: { paper_memo_id: 91, paper_memo_uuid: "x", released: ["a", "b"], kept_open: ["c"], voided_paper_visit_uuid: null, phone_visit_uuid: null } } }]);
    expect(calls).toHaveLength(1);
    expect(calls[0]!.url).toBe("/api/bff/admin-op");
    expect(calls[0]!.body).toEqual({ op: "quarantine.match-backfill", params: { quarantine_id: "7" }, reason: REASON });
    expect(textOf(render().by("bm-ok")!.props.children)).toContain("91");
    expect(textOf(render().by("bm-released")!.props.children)).toContain("2");
    expect(textOf(render().by("bm-kept")!.props.children)).toContain("1");
  });
  it("a short reason sends nothing", async () => {
    const calls: unknown[] = [];
    vi.stubGlobal("fetch", async () => void calls.push(1));
    (render().found.find((e) => e.props.id === "bm-reason")!.props.onChange as (v: string) => void)("short");
    (render().by("bm-submit")!.props.onClick as () => void)();
    await new Promise((r) => setTimeout(r, 5));
    expect(calls).toHaveLength(0);
  });
  it("retries once on 503 and shows the result of the retry", async () => {
    const calls = await go([{ status: 503, body: { code: "ERR_SERVICE_UNAVAILABLE" } }, { status: 200, body: { data: { paper_memo_id: 5, released: [], kept_open: [] } } }]);
    expect(calls).toHaveLength(2);
    expect(render().by("bm-ok")).toBeTruthy();
  });
  it("a second 503 is not retried again", async () => {
    const calls = await go([{ status: 503, body: { code: "ERR_SERVICE_UNAVAILABLE" } }, { status: 503, body: { code: "ERR_SERVICE_UNAVAILABLE" } }, { status: 200, body: { data: {} } }]);
    expect(calls).toHaveLength(2);
    expect(textOf(render().by("bm-error")!.props.children)).toContain("ERR_SERVICE_UNAVAILABLE");
  });
  it("ERR_CONFLICT lists every difference with its pointer and message", async () => {
    await go([{ status: 409, body: { code: "ERR_CONFLICT", errors: [{ pointer: "/lines/0/qty_base", code: "mismatch", message: "phone 10, paper 12" }, { pointer: "/paid_mtk", code: "mismatch", message: "phone 500, paper 400" }] } }]);
    const txt = textOf(render().by("bm-diffs")!.props.children);
    expect(txt).toContain("/lines/0/qty_base");
    expect(txt).toContain("phone 10, paper 12");
    expect(txt).toContain("/paid_mtk");
    expect(textOf(render().by("bm-error")!.props.children)).toContain("Differences");
  });
  it("ERR_REQUEST_STATE (also a repeat of a done match) and 404 get plain texts", async () => {
    await go([{ status: 409, body: { code: "ERR_REQUEST_STATE" } }]);
    expect(textOf(render().by("bm-error")!.props.children)).toContain("repeated");
    hooks.cells = [];
    await go([{ status: 404, body: { code: "ERR_NOT_FOUND" } }]);
    expect(textOf(render().by("bm-error")!.props.children)).toContain("not found");
  });
});

