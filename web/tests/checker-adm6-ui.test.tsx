// CHECKER ADM-6 (T2) for F-ADM-020: drives components/admin/definition-manager.tsx without a DOM (state cells are mocked, as in checker-adm2-editor).
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactElement } from "react";
import { DefinitionManager } from "@/components/admin/definition-manager";
import type { Locale, MessageKey } from "@/lib/i18n";

const hooks = vi.hoisted(() => ({ cells: [] as unknown[], i: 0, locale: "en" as string }));
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
vi.mock("@/components/i18n-provider", async () => {
  const i18n = await import("@/lib/i18n");
  return { useI18n: () => ({ locale: hooks.locale, t: (k: MessageKey) => i18n.t(hooks.locale as Locale, k), number: (n: number) => String(n), problem: (c: string) => String(c) }) };
});

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
type Props = { kind: "surveys" | "rubrics" | "content"; items: Record<string, unknown>[]; canWrite: boolean };
let props: Props;
const setup = (p: Partial<Props>, locale = "en") => {
  hooks.cells = [];
  hooks.i = 0;
  hooks.locale = locale;
  props = { kind: "surveys", items: [], canWrite: true, ...p };
};
const render = (): ReactElement => {
  hooks.i = 0;
  return (DefinitionManager as unknown as (p: object) => ReactElement)(props);
};
const all = (type?: string): El[] => {
  const out: El[] = [];
  walk(render(), (e) => {
    if (!type || e.type === type) out.push(e);
  });
  return out;
};
const byId = (id: string) => all().find((e) => e.props["data-testid"] === id || e.props.id === id);
const byLabel = (label: string, n = 0) => all().filter((e) => e.props["aria-label"] === label)[n];
const click = (id: string) => (byId(id)?.props.onClick as () => unknown)();
const change = (el: El | undefined, v: string) => (el!.props.onChange as (ev: { target: { value: string } }) => void)({ target: { value: v } });
const setReason = (v: string) => walk(render(), (e) => { if (typeof e.props.onChange === "function" && "error" in e.props && "value" in e.props && "onChange" in e.props && e.type !== "input") (e.props.onChange as (x: string) => void)(v); });
const text = (n: unknown): string => (typeof n === "string" || typeof n === "number" ? String(n) : Array.isArray(n) ? n.map(text).join("") : n && typeof n === "object" && "props" in n ? text((n as El).props.children) : "");
const REASON = "Reason that is long enough";

let sent: { url: string; method: string; body: Record<string, unknown> }[] = [];
const realFetch = globalThis.fetch;
beforeEach(() => {
  sent = [];
  globalThis.fetch = vi.fn(async (url: string, init?: { method?: string; body?: string }) => {
    sent.push({ url, method: init?.method ?? "GET", body: init?.body ? JSON.parse(init.body) : {} });
    return new Response(JSON.stringify({ data: {} }), { status: 200 });
  }) as unknown as typeof fetch;
});
afterEach(() => { globalThis.fetch = realFetch; });

describe("survey editor (F-ADM-020)", () => {
  function twoQuestionSurvey() {
    setup({ kind: "surveys" });
    click("def-new");
    change(byId("f-title_en"), "POSM");
    change(byId("f-valid_from"), "2026-11-01");
    change(byId("key-0"), "q1");
    change(byId("label-0"), "Is the poster up?");
    click("def-add-row");
    change(byId("key-1"), "q2");
    change(byId("label-1"), "Photo of the poster");
    setReason(REASON);
  }

  it("DEFECT: the 'show only if' answer shows Yes but submits null until the admin touches it", async () => {
    twoQuestionSurvey();
    change(byLabel("Show only if", 1), "q1");
    // The visible default of the answer select is its first option (Yes = "true"); the submitted state must match what is shown.
    const answer = byLabel("Answer", 0);
    const firstOption = (answer!.props.children as El[])[0]!.props.value;
    expect(firstOption).toBe("true");
    expect(answer!.props.value, "select state must equal the option the browser displays").toBe(firstOption);
    await click("def-submit");
    const q2 = (sent.at(-1)!.body.questions as Record<string, unknown>[])[1]!;
    expect(q2.show_if_bool).toBe(true);
  });

  it("DEFECT: renaming or removing a referenced question leaves a stale show_if_key that the API refuses with a bare validation error", async () => {
    twoQuestionSurvey();
    change(byLabel("Show only if", 1), "q1");
    change(byLabel("Answer", 0), "true");
    change(byId("key-0"), "alpha"); // q2 still says show_if_key = q1
    await click("def-submit");
    const body = sent.at(-1)?.body;
    const q2 = (body?.questions as Record<string, unknown>[] | undefined)?.[1];
    // Either the editor refuses to submit, or the condition follows the rename; it must never send a reference to a key that no longer exists.
    const keys = ((body?.questions as Record<string, unknown>[] | undefined) ?? []).map((q) => q.key);
    if (q2) expect(keys).toContain(q2.show_if_key);
  });

  it("DEFECT: row inputs of every question share one accessible name (WCAG 2.4.6, 1.3.1)", () => {
    twoQuestionSurvey();
    const names = all("input").map((e) => e.props["aria-label"]).filter((n): n is string => typeof n === "string" && n.length > 0);
    const keyNames = names.filter((n) => n.startsWith("Key"));
    expect(keyNames).toHaveLength(2);
    expect(new Set(keyNames).size, "the two Key inputs need different names (for example 'Key 1', 'Key 2')").toBe(2);
  });

  it("DEFECT: there is no control for status, so a definition (and a rubric) can never be deactivated or reactivated from the page", () => {
    setup({ kind: "surveys", items: [{ survey_id: 1, version: 1, kind: "posm", title_en: "Old", status: "inactive", valid_from: "2026-01-01", valid_to: null, questions: [{ question_id: 1, answer_type: "bool", label_en: "Q" }] }] });
    click("edit-1");
    const statusControl = all().find((e) => /status/i.test(String(e.props["aria-label"] ?? "")) || /status/i.test(String(e.props.id ?? "")) || /status/i.test(String(e.props["data-testid"] ?? "")) || e.props.name === "status");
    expect(statusControl, "SurveyWrite/RubricWrite/ContentWrite.status is the only way to deactivate (contract: PATCH 'or deactivate')").toBeTruthy();
  });

  it("DEFECT: the list shows neither validity nor assignment of a definition", () => {
    setup({ kind: "content", items: [{ content_id: 3, version: 1, kind: "av", title_en: "Film", status: "active", valid_from: "2026-11-01", valid_to: "2026-12-01", sequence: 1, assigned_scope: [{ node_type: "zone", node_id: 14 }] }] });
    const rowText = all("tr").map((e) => text(e)).join("|");
    expect(rowText).toContain("2026-11-01");
    expect(rowText).toContain("2026-12-01");
  });
});

describe("rubric template from a read", () => {
  it("DEFECT: disabled placeholder criteria (enabled: false) are copied as live criteria into the new version", () => {
    setup({
      kind: "rubrics",
      items: [{ rubric_id: 1, version: 2, kind: "joint_call", status: "active", criteria: [
        { criterion_id: 1, label_en: "Greets", answer_type: "score_1_5", enabled: true },
        { criterion_id: 2, label_en: "Placeholder 4", answer_type: "score_1_5", enabled: false },
        { criterion_id: 3, label_en: "Placeholder 5", answer_type: "score_1_5", enabled: false },
      ] }],
    });
    click("edit-1");
    const rows = all("div").filter((e) => /^row-\d+$/.test(String(e.props["data-testid"])));
    // docs/15 F-ADM-020: 3 known items plus 2 disabled placeholders; the write has no `enabled`, so a disabled one must not silently go live.
    expect(rows.length).toBe(1);
  });
});

describe("content editor", () => {
  const fileOf = (name: string, type: string, size: number) => ({ name, type, size }) as unknown as File;
  async function submitWithFile(file: File, kindValue: string) {
    setup({ kind: "content" });
    click("def-new");
    change(all("select").find((e) => e.props.id === "f-kind"), kindValue);
    change(byId("f-title_en"), "Film");
    change(byId("f-valid_from"), "2026-11-01");
    change(byId("f-valid_to"), "2026-12-01");
    (byId("f-file")!.props.onChange as (ev: unknown) => void)({ target: { files: [file] } });
    setReason(REASON);
    await click("def-submit");
  }
  const fieldError = (htmlFor: string) => String(all().find((e) => e.props.htmlFor === htmlFor)?.props.error ?? "");
  const errorOf = (id: string) => fieldError(id.replace("error-", ""));

  it("DEFECT: an oversize KV image or AV video says 'larger than 100 MB' (the limits are 300 KB and 20 MB)", async () => {
    await submitWithFile(fileOf("a.png", "image/png", 400_000), "kv");
    expect(errorOf("error-f-file")).not.toContain("100");
    await submitWithFile(fileOf("a.mp4", "video/mp4", 25_000_000), "av");
    expect(errorOf("error-f-file")).not.toContain("100");
  });

  it("DEFECT: Bengali digits are refused in the play order and the node id (every other admin number field normalises them)", async () => {
    setup({ kind: "content" });
    click("def-new");
    change(byId("f-title_en"), "Film");
    change(byId("f-valid_from"), "2026-11-01");
    change(byId("f-valid_to"), "2026-12-01");
    change(byId("f-sequence"), "২");
    (byId("f-file")!.props.onChange as (ev: unknown) => void)({ target: { files: [fileOf("a.mp4", "video/mp4", 1000)] } });
    setReason(REASON);
    await click("def-submit");
    expect(String(all().find((e) => e.props.htmlFor === "f-sequence")?.props.error ?? "")).toBe("");
  });
});

describe("raw English left in a Bangla UI", () => {
  it("DEFECT: the list and the selects show internal codes (posm, bool, stars_1_5, zone) in the Bangla screen", () => {
    setup({ kind: "surveys", items: [{ survey_id: 1, version: 1, kind: "posm", title_en: "POSM", status: "active", valid_from: "2026-01-01", questions: [{ question_id: 1, answer_type: "bool", label_en: "Q" }] }] }, "bn");
    const cell = all("td").map((e) => text(e));
    expect(cell, "the kind column must be a localised label, not the code").not.toContain("posm");
    click("def-new");
    const opts = all("option").map((e) => text(e));
    for (const raw of ["posm", "amo_survey", "tso_visit_query", "bool", "num", "option", "text", "photo_only"]) expect(opts, raw).not.toContain(raw);
  });
  it("DEFECT: a rubric row has no title, so its name falls back to the raw kind code", () => {
    setup({ kind: "rubrics", items: [{ rubric_id: 1, version: 1, kind: "joint_call", status: "active", criteria: [] }] }, "bn");
    const first = all("td").map((e) => text(e));
    expect(first).not.toContain("joint_call");
  });
});

describe("i18n parity for def.* (en and bn)", () => {
  it("every def.* key has a Bangla value in Bengali script that differs from the English one", async () => {
    const { en } = await import("@/lib/i18n/messages-en");
    const { bn } = await import("@/lib/i18n/messages-bn");
    const keys = Object.keys(en).filter((k) => k.startsWith("def.")) as (keyof typeof en)[];
    expect(keys.length).toBeGreaterThan(20);
    for (const k of keys) {
      expect(bn[k], k).toBeTruthy();
      expect(bn[k], k).toMatch(/[\u0980-\u09FF]/);
      expect(bn[k], k).not.toBe(en[k]);
    }
  });
});
