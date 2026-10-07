// Survey, rubric and content definitions (F-ADM-020): the checks shared by the BFF handlers, the editors and the tests.
// Mirrors SurveyWrite, RubricWrite and ContentWrite; the reason is the ChangeReason `change_reason` member of each.
import { codePoints, isRealDate, REASON_MAX, REASON_MIN } from "@/components/admin/crud/validation";
import { UUID_V4, type FieldIssue } from "./tutorials";

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const KEY = /^[a-z][a-z0-9_]{1,40}$/;

export const SURVEY_KINDS = ["posm", "amo_survey", "tso_visit_query"] as const;
export const SURVEY_ANSWERS = ["bool", "num", "option", "text", "photo_only"] as const;
export const RUBRIC_KINDS = ["joint_call", "retailer_questionnaire"] as const;
export const RUBRIC_ANSWERS = ["stars_1_5", "bool", "text"] as const;
export const CONTENT_KINDS = ["av", "kv"] as const;
export const SCOPE_TYPES = ["wing", "division", "territory", "zone", "route", "outlet"] as const;
export const DEF_MAX_ITEMS = 50;

const oneOf = (list: readonly string[], v: unknown) => typeof v === "string" && list.includes(v);

function unknownMembers(b: Record<string, unknown>, allowed: readonly string[], issues: FieldIssue[]) {
  for (const k of Object.keys(b)) if (!allowed.includes(k)) issues.push({ pointer: `/${k}`, code: "unknown_member" });
}
function title(b: Record<string, unknown>, field: string, max: number, required: boolean, issues: FieldIssue[]): string | null {
  const v = b[field];
  if (v === undefined || v === null) {
    if (required) issues.push({ pointer: `/${field}`, code: "required" });
    return null;
  }
  const s = typeof v === "string" ? v.trim() : "";
  if (typeof v !== "string") issues.push({ pointer: `/${field}`, code: "invalid" });
  else if (!s) {
    if (required) issues.push({ pointer: `/${field}`, code: "required" });
    return null;
  } else if (codePoints(s) > max) issues.push({ pointer: `/${field}`, code: "too_long" });
  return s || null;
}
function reason(b: Record<string, unknown>, issues: FieldIssue[]): string {
  const r = typeof b.change_reason === "string" ? b.change_reason.trim() : "";
  const n = codePoints(r);
  if (n < REASON_MIN) issues.push({ pointer: "/change_reason", code: "too_short" });
  else if (n > REASON_MAX) issues.push({ pointer: "/change_reason", code: "too_long" });
  return r;
}
function date(v: unknown, pointer: string, issues: FieldIssue[]): string | null {
  if (typeof v !== "string" || !isRealDate(v)) {
    issues.push({ pointer, code: "invalid" });
    return null;
  }
  return v;
}
type Out = { issues: FieldIssue[]; body?: Record<string, unknown> };
const done = (issues: FieldIssue[], body: Record<string, unknown>): Out => (issues.length ? { issues } : { issues, body });

export function checkSurveyWrite(b: unknown): Out {
  const issues: FieldIssue[] = [];
  if (!isObj(b)) return { issues: [{ pointer: "", code: "invalid" }] };
  unknownMembers(b, ["kind", "title_en", "title_bn", "questions", "valid_from", "valid_to", "points_per_photo", "status", "change_reason"], issues);
  if (!oneOf(SURVEY_KINDS, b.kind)) issues.push({ pointer: "/kind", code: "invalid" });
  const title_en = title(b, "title_en", 120, true, issues);
  const title_bn = title(b, "title_bn", 120, false, issues);
  const qs = b.questions;
  const keys = new Set<string>();
  const questions: Record<string, unknown>[] = [];
  const boolKeys = new Set<string>();
  if (!Array.isArray(qs) || qs.length < 1 || qs.length > DEF_MAX_ITEMS) issues.push({ pointer: "/questions", code: "invalid" });
  else
    qs.forEach((q, i) => {
      const p = `/questions/${i}`;
      if (!isObj(q)) return void issues.push({ pointer: p, code: "invalid" });
      for (const k of Object.keys(q)) if (!["key", "answer_type", "label_en", "label_bn", "required", "show_if_key", "show_if_bool", "photo"].includes(k)) issues.push({ pointer: `${p}/${k}`, code: "unknown_member" });
      if (typeof q.key !== "string" || !KEY.test(q.key)) issues.push({ pointer: `${p}/key`, code: "invalid" });
      else if (keys.has(q.key)) issues.push({ pointer: `${p}/key`, code: "duplicate" });
      else keys.add(q.key);
      if (!oneOf(SURVEY_ANSWERS, q.answer_type)) issues.push({ pointer: `${p}/answer_type`, code: "invalid" });
      const en = typeof q.label_en === "string" ? q.label_en.trim() : "";
      if (!en || codePoints(en) > 300) issues.push({ pointer: `${p}/label_en`, code: en ? "too_long" : "required" });
      const bn = q.label_bn === undefined || q.label_bn === null ? null : typeof q.label_bn === "string" ? q.label_bn.trim() || null : undefined;
      if (bn === undefined || (bn !== null && codePoints(bn) > 300)) issues.push({ pointer: `${p}/label_bn`, code: "invalid" });
      for (const f of ["required", "photo"]) if (q[f] !== undefined && typeof q[f] !== "boolean") issues.push({ pointer: `${p}/${f}`, code: "invalid" });
      if (q.show_if_key !== undefined && q.show_if_key !== null && (typeof q.show_if_key !== "string" || !KEY.test(q.show_if_key))) issues.push({ pointer: `${p}/show_if_key`, code: "invalid" });
      // A condition may only look at an EARLIER question of the same survey (no forward or self reference).
      else if (typeof q.show_if_key === "string" && !keys.has(q.show_if_key)) issues.push({ pointer: `${p}/show_if_key`, code: "unknown_reference" });
      else if (typeof q.show_if_key === "string" && q.show_if_key === q.key) issues.push({ pointer: `${p}/show_if_key`, code: "unknown_reference" });
      if (q.show_if_bool !== undefined && q.show_if_bool !== null && typeof q.show_if_bool !== "boolean") issues.push({ pointer: `${p}/show_if_bool`, code: "invalid" });
      // A condition is a pair (question, yes or no) and looks only at a yes/no question: anything else could never show.
      const hasKey = typeof q.show_if_key === "string";
      const hasBool = typeof q.show_if_bool === "boolean";
      if (hasKey !== hasBool) issues.push({ pointer: `${p}/${hasKey ? "show_if_bool" : "show_if_key"}`, code: "required" });
      if (hasKey && !issues.some((x) => x.pointer.startsWith(`${p}/show_if`)) && boolKeys.has(String(q.show_if_key)) === false) issues.push({ pointer: `${p}/show_if_key`, code: "unknown_reference" });
      if (q.answer_type === "bool" && typeof q.key === "string") boolKeys.add(q.key);
      questions.push({ ...q, label_en: en, label_bn: bn });
    });
  const valid_from = date(b.valid_from, "/valid_from", issues);
  let valid_to: string | null = null;
  if (b.valid_to !== undefined && b.valid_to !== null) valid_to = date(b.valid_to, "/valid_to", issues);
  if (valid_from && valid_to && valid_to < valid_from) issues.push({ pointer: "/valid_to", code: "before_start" });
  if (b.kind === "amo_survey" && typeof b.points_per_photo === "number" && b.points_per_photo > 0) issues.push({ pointer: "/points_per_photo", code: "invalid" }); // never for the AMO survey
  if (b.points_per_photo !== undefined && b.points_per_photo !== null && (typeof b.points_per_photo !== "number" || !Number.isInteger(b.points_per_photo) || b.points_per_photo < 0 || b.points_per_photo > 100000)) issues.push({ pointer: "/points_per_photo", code: "invalid" });
  if (b.status !== undefined && !oneOf(["active", "inactive"], b.status)) issues.push({ pointer: "/status", code: "invalid" });
  const change_reason = reason(b, issues);
  const body: Record<string, unknown> = { kind: b.kind, title_en, title_bn, questions, valid_from, valid_to, change_reason };
  if (b.points_per_photo !== undefined) body.points_per_photo = b.points_per_photo;
  if (b.status !== undefined) body.status = b.status;
  return done(issues, body);
}

export function checkRubricWrite(b: unknown): Out {
  const issues: FieldIssue[] = [];
  if (!isObj(b)) return { issues: [{ pointer: "", code: "invalid" }] };
  unknownMembers(b, ["kind", "criteria", "status", "change_reason"], issues);
  if (!oneOf(RUBRIC_KINDS, b.kind)) issues.push({ pointer: "/kind", code: "invalid" });
  const keys = new Set<string>();
  const criteria: Record<string, unknown>[] = [];
  if (!Array.isArray(b.criteria) || b.criteria.length < 1 || b.criteria.length > DEF_MAX_ITEMS) issues.push({ pointer: "/criteria", code: "invalid" });
  else
    b.criteria.forEach((c, i) => {
      const p = `/criteria/${i}`;
      if (!isObj(c)) return void issues.push({ pointer: p, code: "invalid" });
      for (const k of Object.keys(c)) if (!["key", "label_en", "label_bn", "answer_type"].includes(k)) issues.push({ pointer: `${p}/${k}`, code: "unknown_member" });
      if (typeof c.key !== "string" || !KEY.test(c.key)) issues.push({ pointer: `${p}/key`, code: "invalid" });
      else if (keys.has(c.key)) issues.push({ pointer: `${p}/key`, code: "duplicate" });
      else keys.add(c.key);
      if (!oneOf(RUBRIC_ANSWERS, c.answer_type)) issues.push({ pointer: `${p}/answer_type`, code: "invalid" });
      const en = typeof c.label_en === "string" ? c.label_en.trim() : "";
      if (!en || codePoints(en) > 300) issues.push({ pointer: `${p}/label_en`, code: en ? "too_long" : "required" });
      const bn = c.label_bn === undefined || c.label_bn === null ? null : typeof c.label_bn === "string" ? c.label_bn.trim() || null : undefined;
      if (bn === undefined || (bn !== null && codePoints(bn) > 300)) issues.push({ pointer: `${p}/label_bn`, code: "invalid" });
      criteria.push({ key: c.key, label_en: en, label_bn: bn, answer_type: c.answer_type });
    });
  if (b.status !== undefined && !oneOf(["active", "inactive"], b.status)) issues.push({ pointer: "/status", code: "invalid" });
  const change_reason = reason(b, issues);
  const body: Record<string, unknown> = { kind: b.kind, criteria, change_reason };
  if (b.status !== undefined) body.status = b.status;
  return done(issues, body);
}

export function checkContentWrite(b: unknown): Out {
  const issues: FieldIssue[] = [];
  if (!isObj(b)) return { issues: [{ pointer: "", code: "invalid" }] };
  unknownMembers(b, ["kind", "title_en", "title_bn", "asset_id", "valid_from", "valid_to", "sequence", "assigned_scope", "status", "change_reason"], issues);
  if (!oneOf(CONTENT_KINDS, b.kind)) issues.push({ pointer: "/kind", code: "invalid" });
  const title_en = title(b, "title_en", 120, true, issues);
  const title_bn = title(b, "title_bn", 120, false, issues);
  if (typeof b.asset_id !== "string" || !UUID_V4.test(b.asset_id)) issues.push({ pointer: "/asset_id", code: "invalid" });
  const valid_from = date(b.valid_from, "/valid_from", issues);
  const valid_to = date(b.valid_to, "/valid_to", issues);
  if (valid_from && valid_to && valid_to < valid_from) issues.push({ pointer: "/valid_to", code: "before_start" });
  if (typeof b.sequence !== "number" || !Number.isInteger(b.sequence) || b.sequence < 1 || b.sequence > 20) issues.push({ pointer: "/sequence", code: "invalid" });
  const scope: { node_type: string; node_id: number }[] = [];
  if (b.assigned_scope !== undefined) {
    const seen = new Set<string>();
    if (!Array.isArray(b.assigned_scope) || b.assigned_scope.length > 200) issues.push({ pointer: "/assigned_scope", code: "invalid" });
    else
      b.assigned_scope.forEach((n, i) => {
        const p = `/assigned_scope/${i}`;
        if (!isObj(n) || Object.keys(n).some((k) => k !== "node_type" && k !== "node_id")) return void issues.push({ pointer: p, code: "invalid" });
        if (!oneOf(SCOPE_TYPES, n.node_type)) issues.push({ pointer: `${p}/node_type`, code: "invalid" });
        if (typeof n.node_id !== "number" || !Number.isSafeInteger(n.node_id) || n.node_id < 1) issues.push({ pointer: `${p}/node_id`, code: "invalid" });
        else if (seen.has(`${n.node_type}:${n.node_id}`)) issues.push({ pointer: p, code: "duplicate" });
        else {
          seen.add(`${n.node_type}:${n.node_id}`);
          scope.push({ node_type: String(n.node_type), node_id: n.node_id });
        }
      });
  }
  if (b.status !== undefined && !oneOf(["active", "inactive"], b.status)) issues.push({ pointer: "/status", code: "invalid" });
  const change_reason = reason(b, issues);
  const body: Record<string, unknown> = { kind: b.kind, title_en, title_bn, asset_id: b.asset_id, valid_from, valid_to, sequence: b.sequence, change_reason };
  if (b.assigned_scope !== undefined) body.assigned_scope = scope;
  if (b.status !== undefined) body.status = b.status;
  return done(issues, body);
}
