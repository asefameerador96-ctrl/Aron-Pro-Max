// CHECKER v1.3 (T2): contract v1.3 stub removal. Code-list refs, definition checks, acting-scope end date, feedback filters.
import { describe, expect, it, vi } from "vitest";

const raw = vi.hoisted(() => ({ data: { lists: [] as unknown[] } as unknown, ok: true }));
vi.mock("@/lib/api/raw", () => ({
  rawRequest: vi.fn(async (r: { path: string }) => (raw.ok ? { ok: true, status: 200, data: r.path.includes("/users/") ? { role: "AMO" } : raw.data, response: new Response(null) } : { ok: false, status: 500, problem: { code: "ERR_INTERNAL" }, response: new Response(null) })),
}));

import { feedback } from "@/app/admin/_entities/feedback";
import { outlets } from "@/app/admin/_entities/outlets";
import { loadRefOptionsChecked, sanitizeFilters } from "@/components/admin/crud/server";
import { masterOpRules } from "@/components/admin/master-op-rules";
import { checkRubricWrite, checkSurveyWrite } from "@/lib/admin/definitions";

const REASON = "Reason that is long enough";
const subRef = outlets.fields.find((f) => f.name === "sub_channel_id")!.ref!;
const list = (items: unknown[]) => ({ lists: [{ list_key: "sub_channel", items }] });

describe("sub_channel code-list ref", () => {
  it("V13-S1: options use CodeItem.id as value, sorted, label en · bn", async () => {
    raw.ok = true;
    raw.data = list([{ id: 7, code: "b", label_en: "B", label_bn: "বি", sort: 2 }, { id: 3, code: "a", label_en: "A", label_bn: null, sort: 1 }]);
    const r = await loadRefOptionsChecked(subRef, "t");
    expect(r.options).toEqual([{ value: "3", label: "A" }, { value: "7", label: "B · বি" }]);
    expect(r.failed).toBe(false);
  });
  it("V13-S2: a missing list (or items without id) is reported as failed, not as an empty required select", async () => {
    raw.ok = true;
    raw.data = { lists: [] };
    expect((await loadRefOptionsChecked(subRef, "t")).failed).toBe(true);
    raw.data = list([{ code: "a", label_en: "A", sort: 1 }]);
    expect((await loadRefOptionsChecked(subRef, "t")).failed).toBe(true);
  });
  it("V13-S3: an item whose valid_to has passed is not offered", async () => {
    raw.ok = true;
    raw.data = list([{ id: 1, code: "old", label_en: "Old", sort: 1, valid_to: "2020-01-01" }, { id: 2, code: "new", label_en: "New", sort: 2 }]);
    expect((await loadRefOptionsChecked(subRef, "t")).options.map((o) => o.value)).toEqual(["2"]);
  });
  it("V13-S4: HTTP failure is failed:true", async () => {
    raw.ok = false;
    expect((await loadRefOptionsChecked(subRef, "t")).failed).toBe(true);
    raw.ok = true;
  });
});

describe("feedback filters", () => {
  it("V13-S5: category codes with digits and underscores pass, uppercase / leading digit / 1-char / 42-char do not", () => {
    expect(sanitizeFilters(feedback, { category_code: "app_bug2" })).toEqual({ category_code: "app_bug2" });
    for (const v of ["App", "1abc", "a", "a".repeat(42), "a-b", " a b"]) expect(sanitizeFilters(feedback, { category_code: v }), v).toEqual({});
  });
});

describe("survey / rubric checks after a v1.3 read", () => {
  const base = { kind: "posm", title_en: "T", valid_from: "2026-11-01", valid_to: null, change_reason: REASON };
  const q = (key: string, extra: Record<string, unknown> = {}) => ({ key, answer_type: "bool", label_en: key, label_bn: null, required: false, photo: false, ...extra });
  it("V13-S6: a condition on an earlier bool question passes; null show_if members pass; on a non-bool or later one is refused", () => {
    expect(checkSurveyWrite({ ...base, questions: [q("q1"), q("q2", { show_if_key: "q1", show_if_bool: true })] }).body).toBeDefined();
    expect(checkSurveyWrite({ ...base, questions: [q("q1", { show_if_key: null, show_if_bool: null })] }).body).toBeDefined();
    expect(checkSurveyWrite({ ...base, questions: [q("q1", { answer_type: "num" }), q("q2", { show_if_key: "q1", show_if_bool: true })] }).body).toBeUndefined();
    expect(checkSurveyWrite({ ...base, questions: [q("q1", { show_if_key: "q2", show_if_bool: true }), q("q2")] }).body).toBeUndefined();
  });
  it("V13-S7: option codes - uppercase and Bengali digits refused, option question without codes refused, 20 accepted", () => {
    const o = (option_codes: unknown) => checkSurveyWrite({ ...base, questions: [q("q1", { answer_type: "option", option_codes })] }).body;
    expect(o(["Good"])).toBeUndefined();
    expect(o(["a১"])).toBeUndefined();
    expect(o([])).toBeUndefined();
    expect(o(Array.from({ length: 20 }, (_, i) => `c${i}x`))).toBeDefined();
  });
  it("V13-S8: rubric accepts score_1_5 and the old stars_1_5, nothing else", () => {
    const r = (answer_type: string) => checkRubricWrite({ kind: "joint_call", criteria: [{ key: "c1", answer_type, label_en: "x", label_bn: null }], change_reason: REASON }).body;
    expect(r("score_1_5")).toBeDefined();
    expect(r("stars_1_5")).toBeDefined();
    expect(r("stars")).toBeUndefined();
  });
});

describe("user-scope.put acting end", () => {
  const put = (nodes: unknown[], valid_from: unknown = "2099-01-10") => masterOpRules("user-scope.put", { valid_from, nodes, change_reason: REASON }, { id: 5 }, "t", "ADMIN");
  it("V13-S9: end is exclusive and must be after valid_from; a national node may carry one; a wing node of an AMO is refused", async () => {
    expect(await put([{ node_type: "zone", node_id: 3, valid_to: "2099-01-11" }])).toEqual([]);
    expect((await put([{ node_type: "zone", node_id: 3, valid_to: "2099-01-10" }])).length).toBeGreaterThan(0);
    expect((await put([{ node_type: "zone", node_id: 3, valid_to: "2099-01-11" }], null)).length).toBeGreaterThan(0);
  });
  it("V13-S10: valid_to with Bengali digits or a time part is refused", async () => {
    for (const valid_to of ["২০৯৯-০১-১১", "2099-01-11T00:00:00Z", "2099-1-11"]) expect((await put([{ node_type: "zone", node_id: 3, valid_to }])).length, valid_to).toBeGreaterThan(0);
  });
});
