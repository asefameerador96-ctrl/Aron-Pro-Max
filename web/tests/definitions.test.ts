import { describe, expect, it } from "vitest";
import { POST as defPost } from "@/app/api/bff/admin/defs/[kind]/route";
import { PATCH as defPatch } from "@/app/api/bff/admin/defs/[kind]/[id]/route";
import { checkAssetRequest } from "@/lib/admin/tutorials";
import { checkContentWrite, checkRubricWrite, checkSurveyWrite } from "@/lib/admin/definitions";
import { req, setupMock } from "./helpers/bff";

const { mock, signIn } = setupMock();
const REASON = "Reason that is long enough";
const ASSET = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
const q = (key: string, extra: Record<string, unknown> = {}) => ({ key, answer_type: "bool", label_en: `Question ${key}`, ...extra });
const survey = (b: Record<string, unknown> = {}) => ({ kind: "posm", title_en: "POSM", valid_from: "2026-11-01", valid_to: null, questions: [q("q1"), q("q2", { show_if_key: "q1", show_if_bool: true })], change_reason: REASON, ...b });
const rubric = (b: Record<string, unknown> = {}) => ({ kind: "joint_call", criteria: [{ key: "c1", label_en: "Greets", answer_type: "stars_1_5" }], change_reason: REASON, ...b });
const content = (b: Record<string, unknown> = {}) => ({ kind: "av", title_en: "Film", asset_id: ASSET, valid_from: "2026-11-01", valid_to: "2026-12-01", sequence: 1, assigned_scope: [{ node_type: "zone", node_id: 14 }], change_reason: REASON, ...b });
const bad = (fn: (b: unknown) => { body?: unknown }, make: (b?: Record<string, unknown>) => unknown, cases: Record<string, unknown>[]) => cases.forEach((c) => expect(fn(make(c)).body, JSON.stringify(c)).toBeUndefined());

describe("definition checks (F-ADM-020)", () => {
  it("accepts valid bodies", () => {
    expect(checkSurveyWrite(survey()).body).toBeTruthy();
    expect(checkRubricWrite(rubric()).body).toBeTruthy();
    expect(checkContentWrite(content()).body).toBeTruthy();
  });
  it("surveys: keys unique, a condition looks only at an earlier question, dates real and ordered, 1..50 questions", () => {
    bad(checkSurveyWrite, survey, [
      { questions: [] },
      { questions: [q("q1"), q("q1")] },
      { questions: [q("q1", { show_if_key: "q2" }), q("q2")] },
      { questions: [q("q1", { show_if_key: "q1" })] },
      { questions: [q("Q1")] },
      { questions: [q("q1", { answer_type: "date" })] },
      { questions: Array.from({ length: 51 }, (_, i) => q(`k${i + 10}`)) },
      { valid_from: "2026-13-45" },
      { valid_to: "2026-10-01" },
      { kind: "other" },
      { title_en: " " },
      { change_reason: "short" },
      { extra: 1 },
    ]);
  });
  it("rubrics: the write enum is stars_1_5, keys unique", () => {
    bad(checkRubricWrite, rubric, [{ criteria: [{ key: "c1", label_en: "x", answer_type: "score_1_5" }] }, { criteria: [] }, { criteria: [{ key: "c1", label_en: "a", answer_type: "bool" }, { key: "c1", label_en: "b", answer_type: "bool" }] }, { kind: "x" }]);
  });
  it("content: asset uuid, dates ordered, sequence 1..20, scope unique with ids >= 1", () => {
    bad(checkContentWrite, content, [{ asset_id: "nope" }, { valid_to: "2026-10-01" }, { sequence: 0 }, { sequence: 21 }, { assigned_scope: [{ node_type: "zone", node_id: 1 }, { node_type: "zone", node_id: 1 }] }, { assigned_scope: [{ node_type: "country", node_id: 1 }] }, { assigned_scope: [{ node_type: "zone", node_id: 0 }] }, { kind: "pdf" }]);
  });
  it("content uploads: AV is MP4 up to 20 MB, KV is JPEG or PNG up to 300 KB", () => {
    const a = (b: Record<string, unknown>) => checkAssetRequest({ asset_id: ASSET, purpose: "content_av", mime: "video/mp4", bytes: 1000, sha256: "a".repeat(64), ...b }).body;
    expect(a({})).toBeTruthy();
    expect(a({ bytes: 20_971_521 })).toBeUndefined();
    expect(a({ purpose: "content_kv", mime: "image/png", bytes: 307_200 })).toBeTruthy();
    expect(a({ purpose: "content_kv", mime: "image/png", bytes: 307_201 })).toBeUndefined();
    expect(a({ purpose: "content_kv" })).toBeUndefined();
  });
});

describe("definition BFF", () => {
  const post = (kind: string, b: unknown, c: Record<string, string>) => defPost(req(`/api/bff/admin/defs/${kind}`, "POST", b, c), { params: Promise.resolve({ kind }) });
  const patch = (kind: string, id: string, b: unknown, c: Record<string, string>) => defPatch(req(`/api/bff/admin/defs/${kind}/${id}`, "PATCH", b, c), { params: Promise.resolve({ kind, id }) });
  it("creates a survey and a rubric with an audit row; a new version needs the current If-Match version", async () => {
    const c = await signIn("madmin1");
    expect((await post("surveys", survey(), c)).status).toBe(201);
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "surveys", reason: REASON });
    expect((await post("rubrics", rubric(), c)).status).toBe(201);
    expect((await patch("surveys", "1", { ...survey({ title_en: "POSM v2" }), version: 1 }, c)).status).toBe(200);
    expect((await patch("surveys", "1", { ...survey(), version: 1 }, c)).status).toBe(412);
    expect((await patch("surveys", "1", survey(), c)).status).toBe(400);
  });
  it("refuses an unknown kind, a bad body and a read-only role", async () => {
    const c = await signIn("madmin1");
    expect((await post("gifts", survey(), c)).status).toBe(404);
    expect((await post("__proto__", survey(), c)).status).toBe(404);
    expect((await post("surveys", survey({ questions: [] }), c)).status).toBe(400);
    const s = await signIn("msupport1");
    expect((await post("surveys", survey(), s)).status).toBe(403);
    expect((await patch("surveys", "1", { ...survey(), version: 1 }, s)).status).toBe(403);
  });
});
