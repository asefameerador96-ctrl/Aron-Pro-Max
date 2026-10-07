// CHECKER ADM-6 (T2) for F-ADM-020: validation holes, role gating and If-Match of the definition BFF.
import { describe, expect, it } from "vitest";
import { POST as defPost } from "@/app/api/bff/admin/defs/[kind]/route";
import { PATCH as defPatch } from "@/app/api/bff/admin/defs/[kind]/[id]/route";
import { checkSurveyWrite } from "@/lib/admin/definitions";
import { req, setupMock } from "./helpers/bff";

const { mock, signIn } = setupMock();
const REASON = "Reason that is long enough";
const q = (key: string, extra: Record<string, unknown> = {}) => ({ key, answer_type: "bool", label_en: `Question ${key}`, ...extra });
const survey = (b: Record<string, unknown> = {}) => ({ kind: "posm", title_en: "POSM", valid_from: "2026-11-01", valid_to: null, questions: [q("q1")], change_reason: REASON, ...b });
const post = (kind: string, b: unknown, c: Record<string, string>) => defPost(req(`/api/bff/admin/defs/${kind}`, "POST", b, c), { params: Promise.resolve({ kind }) });
const patch = (kind: string, id: string, b: unknown, c: Record<string, string>) => defPatch(req(`/api/bff/admin/defs/${kind}/${id}`, "PATCH", b, c), { params: Promise.resolve({ kind, id }) });

describe("definition write checks", () => {
  it("DEFECT: a condition on a non-yes/no question is accepted although the condition can only be a boolean (the question can never show)", () => {
    const r = checkSurveyWrite(survey({ questions: [q("q1", { answer_type: "text" }), q("q2", { show_if_key: "q1", show_if_bool: true })] }));
    expect(r.body).toBeUndefined();
  });
  it("DEFECT: show_if_bool without show_if_key (or the reverse) is accepted and silently means nothing", () => {
    expect(checkSurveyWrite(survey({ questions: [q("q1", { show_if_bool: true })] })).body).toBeUndefined();
    expect(checkSurveyWrite(survey({ questions: [q("q1"), q("q2", { show_if_key: "q1" })] })).body).toBeUndefined();
  });
  it("DEFECT: points_per_photo is accepted on the AMO survey (SurveyAdmin: 'never for the AMO survey')", () => {
    expect(checkSurveyWrite(survey({ kind: "amo_survey", points_per_photo: 50 })).body).toBeUndefined();
  });
});

describe("definition BFF", () => {
  it("DEFECT: a non-canonical id (leading zeros) is forwarded to the API as /surveys/001", async () => {
    const c = await signIn("madmin1");
    expect((await post("surveys", survey(), c)).status).toBe(201);
    expect((await patch("surveys", "001", { ...survey(), version: 1 }, c)).status).toBe(400);
  });
  it("update without a version, with a string or float version, or a stale one never reaches a write", async () => {
    const c = await signIn("madmin1");
    await post("surveys", survey(), c);
    const before = mock.state.audit.length;
    for (const v of [undefined, "1", 1.5, 0, -1, null]) expect((await patch("surveys", "1", { ...survey(), version: v }, c)).status, String(v)).toBe(400);
    expect((await patch("surveys", "1", { ...survey(), version: 7 }, c)).status).toBe(412);
    expect(mock.state.audit.length).toBe(before);
  });
  it("every role but ADMIN and SUPERADMIN is refused on create and update; no session is 401", async () => {
    for (const u of ["msupport1", "tso334", "dmo1"] as const) {
      const c = await signIn(u);
      expect((await post("surveys", survey(), c)).status, u).toBe(403);
      expect((await post("content", {}, c)).status, u).toBe(403);
      expect((await patch("rubrics", "1", { version: 1 }, c)).status, u).toBe(403);
    }
    expect((await post("surveys", survey(), {})).status).toBe(401);
  });
  it("prototype names in the :kind path are 404 and never reach the API", async () => {
    const c = await signIn("madmin1");
    for (const k of ["__proto__", "constructor", "toString", "hasOwnProperty", "SURVEYS", "surveys/../x"]) {
      expect((await post(k, survey(), c)).status, k).toBe(404);
      expect((await patch(k, "1", { ...survey(), version: 1 }, c)).status, k).toBe(404);
    }
  });
  it("the reason is mandatory and stored in the audit row of create and update", async () => {
    const c = await signIn("madmin1");
    expect((await post("surveys", survey({ change_reason: "too short" }), c)).status).toBe(400);
    expect((await post("surveys", survey({ change_reason: undefined }), c)).status).toBe(400);
    expect((await post("surveys", survey(), c)).status).toBe(201);
    expect((await patch("surveys", "1", { ...survey(), version: 1 }, c)).status).toBe(200);
    expect(mock.state.audit.slice(-2).map((a) => a.reason)).toEqual([REASON, REASON]);
  });
});
