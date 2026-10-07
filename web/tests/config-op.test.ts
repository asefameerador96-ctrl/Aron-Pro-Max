// The whitelisted admin-operation proxy (lib/admin/op-server.ts): roles, reason, path safety, forwarding.
import { describe, expect, it } from "vitest";
import { OPS } from "@/lib/admin/ops";
import { admin, op, support, tso, setupMock } from "./helpers/harness";

const h = setupMock();
const REASON = "Eid holiday announced by the ministry";

describe("POST /api/bff/admin-op", () => {
  it("forwards a whitelisted write with the reason in the body member the contract names", async () => {
    h.stub({ method: "POST", path: "/v1/admin/calendar/holidays", fn: () => ({ status: 201, body: { id: 9 } }) });
    const res = await op(await admin(), { op: "holiday.create", body: { date: "2026-10-20", kind: "holiday", scope_type: "global", scope_id: 0, name_en: "Eid" }, reason: REASON });
    expect(res.status).toBe(201);
    expect(await res.json()).toEqual({ data: { id: 9 } });
    expect(h.calls()[0]?.body).toEqual({ date: "2026-10-20", kind: "holiday", scope_type: "global", scope_id: 0, name_en: "Eid", reason: REASON });
  });

  it("refuses a missing or short reason before calling the API", async () => {
    h.stub({ method: "POST", path: "/v1/admin/calendar/holidays", fn: () => ({ status: 201, body: {} }) });
    const cookies = await admin();
    for (const reason of [undefined, "", "too short", "😀".repeat(5)]) {
      const res = await op(cookies, { op: "holiday.create", body: {}, reason });
      expect(res.status).toBe(400);
    }
    expect(h.calls()).toHaveLength(0);
  });

  it("a reason in the browser body cannot override the validated one", async () => {
    h.stub({ method: "POST", path: "/v1/admin/calendar/holidays", fn: () => ({ status: 201, body: {} }) });
    await op(await admin(), { op: "holiday.create", body: { reason: "x" }, reason: REASON });
    expect((h.calls()[0]?.body as { reason: string }).reason).toBe(REASON);
  });

  it("refuses an unknown operation and never takes a path from the browser", async () => {
    const cookies = await admin();
    expect((await op(cookies, { op: "nope" })).status).toBe(400);
    expect((await op(cookies, { op: "__proto__" })).status).toBe(400);
    expect((await op(cookies, { path: "/v1/admin/users", reason: REASON })).status).toBe(400);
    expect(h.calls()).toHaveLength(0);
  });

  it("role gate: SUPPORT cannot declare a holiday, TSO cannot reach the portal at all", async () => {
    h.stub({ method: "POST", path: "/v1/admin/calendar/holidays", fn: () => ({ status: 201, body: {} }) });
    expect((await op(await support(), { op: "holiday.create", body: {}, reason: REASON })).status).toBe(403);
    expect((await op(await tso(), { op: "holiday.create", body: {}, reason: REASON })).status).toBe(403);
    expect(h.calls()).toHaveLength(0);
  });

  it("passes the API problem through unchanged", async () => {
    h.stub({ method: "POST", path: "/v1/admin/calendar/holidays", fn: () => ({ status: 409, body: { type: "urn:aron:problem:err_conflict", title: "c", status: 409, code: "ERR_CONFLICT", request_id: "00000000-0000-4000-8000-000000000000" } }) });
    const res = await op(await admin(), { op: "holiday.create", body: {}, reason: REASON });
    expect(res.status).toBe(409);
    expect((await res.json()).code).toBe("ERR_CONFLICT");
  });

  it("every whitelisted path starts with /v1/ and names only {placeholders}", () => {
    for (const [key, def] of Object.entries(OPS)) {
      expect(def.path, key).toMatch(/^\/v1\/[a-z0-9\-/{}_]+$/);
    }
  });
});
