// T1 checker (F-ADM-030b "same memo as backfill"): client-side attacks on BackfillMatchForm, driven without a DOM by mocking React state.
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import { BackfillMatchForm } from "@/components/admin/config/backfill-match-form";
import { formatNumber, t as tr, type Locale, type MessageKey } from "@/lib/i18n";

const hooks = vi.hoisted(() => ({ cells: [] as unknown[], i: 0, locale: "en" as "en" | "bn", refreshes: 0 }));
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
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: () => void hooks.refreshes++, push() {} }) }));
vi.mock("@/components/i18n-provider", () => ({
  useI18n: () => ({
    locale: hooks.locale,
    t: (k: MessageKey, v?: Record<string, string | number>) => tr(hooks.locale as Locale, k, v),
    number: (n: number) => formatNumber(hooks.locale as Locale, n),
    problem: (c: string | undefined) => `problem:${c}`,
  }),
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
const textOf = (n: unknown): string => (Array.isArray(n) ? n.map(textOf).join("") : typeof n === "string" || typeof n === "number" ? String(n) : n && typeof n === "object" && "props" in n ? textOf((n as El).props.children) : "");
function render() {
  hooks.i = 0;
  const found: El[] = [];
  walk((BackfillMatchForm as unknown as (p: unknown) => ReactElement)({ quarantineId: 7 }), (e) => found.push(e));
  return { by: (id: string) => found.find((e) => e.props["data-testid"] === id), found };
}
const REASON = "Same memo keyed on paper";
type Reply = { status: number; body?: unknown; raw?: string };
let calls: Record<string, unknown>[] = [];
function stub(replies: Reply[]) {
  calls = [];
  vi.stubGlobal("fetch", async (_url: string, init: { body: string }) => {
    calls.push(JSON.parse(init.body) as Record<string, unknown>);
    const r = replies.shift() ?? { status: 200, body: {} };
    return new Response(r.raw !== undefined ? r.raw : JSON.stringify(r.body), { status: r.status });
  });
}
function typeReason(v = REASON) {
  (render().found.find((e) => e.props.id === "bm-reason")!.props.onChange as (v: string) => void)(v);
}
async function go(replies: Reply[]) {
  stub(replies);
  typeReason();
  (render().by("bm-submit")!.props.onClick as () => void)();
  await new Promise((r) => setTimeout(r, 5));
}
const errText = () => textOf(render().by("bm-error")?.props.children);
beforeEach(() => {
  hooks.cells = [];
  hooks.locale = "en";
  hooks.refreshes = 0;
});

describe("checker bm: 503 retry when the first attempt was committed", () => {
  it("503 then 409 ERR_REQUEST_STATE: the UI says the first attempt probably went through and re-reads the item", async () => {
    await go([{ status: 503, body: { code: "ERR_SERVICE_UNAVAILABLE" } }, { status: 409, body: { code: "ERR_REQUEST_STATE" } }]);
    expect(calls).toHaveLength(2);
    // Today it shows the generic "cannot be matched now: ... no paper twin, another SR, voided ..." list as an error.
    // Expected: a distinct, plain message ("the first try may have gone through; the page was reloaded, check the item") and a refresh.
    expect(errText()).not.toBe(tr("en", "bm.state"));
    expect(hooks.refreshes).toBeGreaterThan(0);
  });
  it("a plain 409 ERR_REQUEST_STATE (no retry) refreshes the stale page so the item shows its real status", async () => {
    await go([{ status: 409, body: { code: "ERR_REQUEST_STATE" } }]);
    expect(hooks.refreshes).toBeGreaterThan(0);
  });
});

describe("checker bm: busy, success and stale state", () => {
  it("while busy the button is disabled (second click blocked by the DOM)", async () => {
    let release!: () => void;
    calls = [];
    vi.stubGlobal("fetch", (_u: string, init: { body: string }) => {
      calls.push(JSON.parse(init.body) as Record<string, unknown>);
      return new Promise<Response>((r) => (release = () => r(new Response(JSON.stringify({ data: { paper_memo_id: 1, released: [], kept_open: [] } }), { status: 200 }))));
    });
    typeReason();
    (render().by("bm-submit")!.props.onClick as () => void)();
    expect(render().by("bm-submit")!.props.disabled).toBe(true);
    release();
    await new Promise((r) => setTimeout(r, 5));
    expect(render().by("bm-submit")!.props.disabled).toBe(false);
    expect(calls).toHaveLength(1);
  });
  it("success clears the reason and refreshes, so a second click is refused locally (no replay POST)", async () => {
    await go([{ status: 200, body: { data: { paper_memo_id: 91, released: ["a"], kept_open: [] } } }]);
    expect(hooks.refreshes).toBe(1);
    const before = calls.length;
    (render().by("bm-submit")!.props.onClick as () => void)();
    await new Promise((r) => setTimeout(r, 5));
    expect(calls.length).toBe(before);
  });
  it("success role=status, error role=alert", async () => {
    await go([{ status: 200, body: { data: { paper_memo_id: 91, released: [], kept_open: [] } } }]);
    expect(render().by("bm-ok")!.props.role).toBe("status");
    hooks.cells = [];
    await go([{ status: 404, body: { code: "ERR_NOT_FOUND" } }]);
    expect(render().by("bm-error")!.props.role).toBe("alert");
  });
  it("the paper memo id is shown like every other id (no grouping separator)", async () => {
    hooks.locale = "bn";
    await go([{ status: 200, body: { data: { paper_memo_id: 1234567, released: [], kept_open: [] } } }]);
    const txt = textOf(render().by("bm-ok")!.props.children);
    expect(txt).toContain(formatNumber("bn", 1234567, { useGrouping: false }));
  });
});

describe("checker bm: odd responses", () => {
  it("non-JSON 502 and an empty 200 never show success and never crash", async () => {
    await go([{ status: 502, raw: "<html>Bad gateway</html>" }]);
    expect(render().by("bm-ok")).toBeUndefined();
    expect(errText()).toContain("problem:");
    hooks.cells = [];
    await go([{ status: 200, raw: "" }]);
    expect(render().by("bm-ok")).toBeUndefined();
    expect(render().by("bm-error")).toBeTruthy();
  });
  it("ERR_CONFLICT with errors missing still shows the heading; HTML in a message stays text", async () => {
    await go([{ status: 409, body: { code: "ERR_CONFLICT" } }]);
    expect(errText()).toContain("Differences");
    hooks.cells = [];
    await go([{ status: 409, body: { code: "ERR_CONFLICT", errors: [{ pointer: "/lines", code: "mismatch", message: "<img src=x onerror=alert(1)>" }, { code: "mismatch" }] } }]);
    const found = render().found;
    expect(found.some((e) => "dangerouslySetInnerHTML" in e.props)).toBe(false);
    expect(textOf(render().by("bm-diffs")!.props.children)).toContain("<img src=x onerror=alert(1)>");
  });
  it("very many differences render without throwing", async () => {
    const errors = Array.from({ length: 500 }, (_, i) => ({ pointer: `/lines/${i}`, code: "mismatch", message: "x".repeat(2000) }));
    await go([{ status: 409, body: { code: "ERR_CONFLICT", errors } }]);
    expect(render().by("bm-diffs")).toBeTruthy();
  });
});

describe("checker bm: Bangla per-difference list", () => {
  it("in Bangla each difference names the field in Bangla, not only the raw JSON pointer and the server's English text", async () => {
    hooks.locale = "bn";
    await go([{ status: 409, body: { code: "ERR_CONFLICT", errors: [{ pointer: "/payload/gross_mtk", code: "mismatch", message: "phone 120000, paper 100000" }] } }]);
    expect(errText()).toContain(tr("bn", "bm.conflict")); // the heading is Bangla (holds)
    const line = textOf(render().by("bm-diffs")!.props.children).replace("/payload/gross_mtk", "").replace("phone 120000, paper 100000", "");
    expect(/[ঀ-৿]/.test(line)).toBe(true); // fails: nothing Bangla on the line itself
  });
});
