// Contract v1.3 follow-up (web-admin): option codes, acting-scope end date, feedback filters, round-trip of reads.
import { describe, expect, it, vi } from "vitest";

vi.mock("@/lib/api/raw", () => ({ rawRequest: vi.fn(async () => ({ ok: true, status: 200, data: { role: "AMO" }, response: new Response(null) })) }));

import { feedback } from "@/app/admin/_entities/feedback";
import { sanitizeFilters } from "@/components/admin/crud/server";
import { masterOpRules } from "@/components/admin/master-op-rules";
import { checkSurveyWrite } from "@/lib/admin/definitions";

const REASON = "Reason that is long enough";
const survey = (questions: unknown[]) => ({ kind: "posm", title_en: "POSM", valid_from: "2026-11-01", valid_to: null, questions, change_reason: REASON });
const q = (extra: Record<string, unknown> = {}) => ({ key: "q1", answer_type: "option", label_en: "Shelf", option_codes: ["good", "poor"], ...extra });

describe("survey option_codes", () => {
  it("an option question carries 1..20 distinct codes in the key pattern", () => {
    expect(checkSurveyWrite(survey([q()])).body).toBeDefined();
    for (const option_codes of [[], undefined, ["Good"], ["a"], ["good", "good"], Array.from({ length: 21 }, (_, i) => `code_${i}`), "good"]) expect(checkSurveyWrite(survey([q({ option_codes })])).body, JSON.stringify(option_codes)).toBeUndefined();
  });
  it("other answer types take no options", () => {
    expect(checkSurveyWrite(survey([q({ answer_type: "bool", option_codes: ["good"] })])).body).toBeUndefined();
    expect(checkSurveyWrite(survey([q({ answer_type: "bool", option_codes: [] })])).body).toBeDefined();
  });
});

describe("user-scope.put with an acting end date (exclusive)", () => {
  const put = (nodes: unknown[], valid_from = "2099-01-10") => masterOpRules("user-scope.put", { valid_from, nodes, change_reason: REASON }, { id: 5 }, "t", "ADMIN");
  it("accepts a node end after the start and refuses equal, earlier or malformed ends", async () => {
    expect(await put([{ node_type: "zone", node_id: 3, valid_to: "2099-01-11" }])).toEqual([]);
    expect(await put([{ node_type: "zone", node_id: 3, valid_to: null }])).toEqual([]);
    for (const valid_to of ["2099-01-10", "2099-01-09", "2099-02-30", 20990111, ""]) {
      const e = await put([{ node_type: "zone", node_id: 3, valid_to }]);
      expect(e.map((x) => x.pointer), String(valid_to)).toContain("/body/nodes/0/valid_to");
    }
  });
  it("still refuses unknown members of a node", async () => {
    expect((await put([{ node_type: "zone", node_id: 3, until: "2099-02-01" }])).length).toBeGreaterThan(0);
  });
});

describe("feedback filters (category_code by code, status)", () => {
  it("keeps a valid category code and status, drops anything else", () => {
    expect(sanitizeFilters(feedback, { category_code: "route", status: "new" })).toEqual({ category_code: "route", status: "new" });
    expect(sanitizeFilters(feedback, { category_code: "Route; drop", status: "bogus" })).toEqual({});
    expect(sanitizeFilters(feedback, { category_code: "12", status: "" })).toEqual({});
  });
});

describe("outlet-request.create rules (POST /v1/outlet-requests)", () => {
  const U = "3f2b8c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e";
  const make = (request_type: string, proposed: unknown, extra: Record<string, unknown> = {}) => masterOpRules("outlet-request.create", { request_uuid: U, request_type, outlet_id: 5, proposed, note: REASON, ...extra }, {}, "t", "TSO");
  it("accepts each type with exactly its members", async () => {
    expect(await make("info", { owner_name: "Md. Rahim", contact_number: "01712345678", sub_channel_id: 11 })).toEqual([]);
    expect(await make("location", { lat: 23.8, lng: 90.4 })).toEqual([]);
    expect(await make("cluster", { cluster_id: 4 })).toEqual([]);
    expect(await make("close", { close_reason_code: "permanent" })).toEqual([]);
  });
  it("refuses new and route_add (app only), foreign members, a missing member and a bad uuid", async () => {
    for (const t of ["new", "route_add", "x"]) expect((await make(t, { name: "A" })).map((e) => e.pointer), t).toContain("/body/request_type");
    expect((await make("cluster", { cluster_id: 4, lat: 1 })).length).toBeGreaterThan(0);
    expect((await make("location", { lat: 23.8 })).map((e) => e.pointer)).toContain("/body/proposed/lng");
    expect((await make("location", { lat: 91, lng: 90 })).length).toBeGreaterThan(0);
    expect((await make("info", {})).map((e) => e.pointer)).toContain("/body/proposed");
    expect((await make("info", { contact_number: "0123" })).length).toBeGreaterThan(0);
    expect((await make("close", { close_reason_code: "Closed For Good" })).length).toBeGreaterThan(0);
    expect((await make("cluster", { cluster_id: 4 }, { request_uuid: "nope" })).map((e) => e.pointer)).toContain("/body/request_uuid");
    expect((await make("cluster", { cluster_id: 4 }, { fix: {} })).map((e) => e.pointer)).toContain("/body/fix");
  });
});
