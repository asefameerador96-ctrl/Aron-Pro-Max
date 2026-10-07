import { describe, expect, it } from "vitest";
import { POST as bulkPost } from "@/app/api/bff/admin/wholesale-marking/route";
import { outletRequests, outlets } from "@/app/admin/_entities/outlets";
import { valuesSchema } from "@/components/admin/crud/validation";
import { accessFor } from "@/lib/auth/roles";
import { req, setupMock } from "./helpers/bff";

const { mock, signIn, create, update, act } = setupMock();
const REASON = "Reason that is long enough";
const U1 = "11111111-1111-4111-8111-111111111111"; // new, pending
const U2 = "22222222-2222-4222-8222-222222222222"; // new, verified (by 1003)
const U3 = "33333333-3333-4333-8333-333333333333"; // close, verified
const U4 = "44444444-4444-4444-8444-444444444444"; // info, pending
const bulk = (body: unknown, c: Record<string, string>) => bulkPost(req("/api/bff/admin/wholesale-marking", "POST", body, c));

describe("outlets (retailer detail)", () => {
  it("has the four sections of the retailer detail in order", () => {
    const sections = [...new Set(outlets.fields.map((f) => f.section).filter(Boolean))];
    expect(sections).toEqual(["outlet.section.basic", "outlet.section.address", "outlet.section.business", "outlet.section.additional"]);
  });
  it("validates phone (Bengali digits accepted), coordinates, channel and geo class", () => {
    const u = valuesSchema(outlets, "update");
    expect(u.safeParse({ contact_number: "০১৭১২৩৪৫৬৭৮" })).toMatchObject({ success: true, data: { contact_number: "01712345678" } });
    expect(u.safeParse({ contact_number: "0123" }).success).toBe(false);
    expect(u.safeParse({ lat: "23.79", lng: "90.4" })).toMatchObject({ success: true, data: { lat: 23.79, lng: 90.4 } });
    expect(u.safeParse({ lat: "91" }).success).toBe(false);
    expect(u.safeParse({ lng: "-181" }).success).toBe(false);
    expect(u.safeParse({ lat: "abc" }).success).toBe(false);
    expect(u.safeParse({ lat: "" }).success).toBe(false); // PATCH cannot clear coordinates (contract)
    expect(u.safeParse({ channel: "GT", geo_class: "Rural" }).success).toBe(true);
    expect(u.safeParse({ channel: "XX" }).success).toBe(false);
    expect(u.safeParse({ zone_id: "14" }).success).toBe(false); // zone is create-only
    expect(u.safeParse({ code: "X1" }).success).toBe(false);
  });
  it("edits with the reason and If-Match; closing needs the status field and a reason", async () => {
    const c = await signIn("madmin1");
    const ok = await update("outlets", "1", { values: { owner_name: "Md. Rahim Uddin", lat: "23.8", status: "closed" }, reason: REASON, version: 1 }, c);
    expect(ok.status).toBe(200);
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "outlet", reason: REASON, after: { status: "closed", lat: 23.8 } });
    expect((await update("outlets", "1", { values: { owner_name: "X Y" }, reason: REASON, version: 1 }, c)).status).toBe(412); // stale
  });
  it("creates with the reason sent as change_reason; the code is generated when empty", async () => {
    const c = await signIn("madmin1");
    const res = await create("outlets", { values: { name: "Fresh Store", owner_name: "Owner One", zone_id: "14", cluster_id: "1", channel: "GT", outlet_kind: "retail" }, reason: REASON }, c);
    expect(res.status).toBe(201);
    expect((await res.json()).row.code).toMatch(/^OUT-/);
    expect(mock.state.audit.at(-1)).toMatchObject({ action: "outlet.create", reason: REASON });
  });
  it("reopens a wrongly closed outlet through the reopen action with If-Match and a reason; only closed ones offer it", async () => {
    const reopen = outlets.actions!.find((a) => a.key === "reopen")!;
    expect(reopen.when!({ status: "closed" })).toBe(true);
    expect(reopen.when!({ status: "active" })).toBe(false);
    const c = await signIn("madmin1");
    const noVersion = await act("outlets", "4", "reopen", { values: {}, reason: REASON }, c);
    expect(noVersion.status).toBe(400);
    const ok = await act("outlets", "4", "reopen", { values: {}, reason: REASON, version: 1 }, c);
    expect(ok.status).toBe(200);
    expect(mock.state.tables.outlets!.find((o) => o.id === 4)).toMatchObject({ status: "active", version: 2 });
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "outlet", reason: REASON, before: { status: "closed" }, after: { status: "active" } });
    expect((await act("outlets", "4", "reopen", { values: {}, reason: REASON, version: 1 }, c)).status).toBe(412); // stale
    const support = await signIn("msupport1");
    expect((await act("outlets", "4", "reopen", { values: {}, reason: REASON, version: 2 }, support)).status).toBe(403);
  });
});

describe("outlet approval panel", () => {
  it("is open to the approving and reading roles, not to the rest of the admin portal", () => {
    for (const r of ["DMO", "WM", "ADMIN", "SUPERADMIN", "TSO", "TOP", "ANALYST"] as const) expect(accessFor(r, "/admin/outlet-requests"), r).toBe("ok");
    expect(accessFor("DMO", "/admin/clusters")).toBe("forbidden");
    expect(accessFor("DMO", "/admin")).toBe("forbidden");
    expect(accessFor("SUPPORT", "/admin/outlet-requests")).toBe("forbidden"); // matrix: no outlet requests for SUPPORT
    expect(accessFor("SR", "/admin/outlet-requests")).toBe("forbidden");
    expect(accessFor("DMO", "/api/bff/admin/outlet-requests/x/approve")).toBe("ok");
    expect(accessFor("DMO", "/api/bff/admin/users")).toBe("forbidden");
    expect(outletRequests.idKind).toBe("uuid");
  });
  it("filters by type and status (mock list)", async () => {
    const c = await signIn("dmo1");
    expect(c).toBeTruthy();
    const list = async (q: string) => (await (await fetch(`${process.env.ARON_API_BASE_URL}/v1/outlet-requests?${q}`, { headers: { Authorization: `Bearer ${[...mock.state.access.keys()].pop()}` } })).json()).items as { request_uuid: string }[];
    expect((await list("request_type=new")).map((r) => r.request_uuid)).toEqual([U1, U2]);
    expect((await list("request_type=close")).map((r) => r.request_uuid)).toEqual([U3]);
    expect((await list("status=pending")).map((r) => r.request_uuid)).toEqual([U1, U4]);
  });
  it("verify (pending) then approve (verified): a new outlet gets a code; a closure closes the outlet; both audited with the reason", async () => {
    const dmo = await signIn("dmo1");
    const v = await act("outlet-requests", U1, "verify", { values: { sub_channel_id: "11", geo_class: "Urban" }, reason: "Checked the photos and the address" }, dmo);
    expect(v.status).toBe(200);
    expect((await act("outlet-requests", U1, "verify", { values: { sub_channel_id: "11" }, reason: REASON }, dmo)).status).toBe(409); // already verified
    const before = mock.state.tables.outlets!.length;
    const ok = await act("outlet-requests", U2, "approve", { values: { outlet_code: "DHK-NEW-9", route_id: "1" }, reason: "Verified on the ground by the AMO" }, dmo);
    expect(ok.status).toBe(200);
    expect(mock.state.tables.outlets!).toHaveLength(before + 1);
    expect(mock.state.tables.outlets!.at(-1)).toMatchObject({ code: "DHK-NEW-9", name: "Verified Fresh Store" });
    const close = await act("outlet-requests", U3, "approve", { values: {}, reason: "Owner confirmed the permanent closure" }, dmo);
    expect(close.status).toBe(200);
    expect(mock.state.tables.outlets!.find((o) => o.id === 2)).toMatchObject({ status: "closed" });
    expect(mock.state.audit.at(-1)).toMatchObject({ entity: "outlet_request", reason: "Owner confirmed the permanent closure" });
  });
  it("approve is refused for a pending request; reject needs a reason and closes the request", async () => {
    const dmo = await signIn("dmo1");
    const pending = await act("outlet-requests", U1, "approve", { values: {}, reason: REASON }, dmo);
    expect(pending.status).toBe(409);
    expect((await pending.json()).code).toBe("ERR_REQUEST_STATE");
    expect((await act("outlet-requests", U4, "reject", { values: {}, reason: "short" }, dmo)).status).toBe(400);
    expect((await act("outlet-requests", U4, "reject", { values: {}, reason: "Not the owner of record" }, dmo)).status).toBe(200);
    expect(mock.state.tables.outletRequests!.find((r) => r.request_uuid === U4)).toMatchObject({ status: "rejected", rejection_reason: "Not the owner of record" });
    expect((await act("outlet-requests", U4, "reject", { values: {}, reason: "Rejecting a second time" }, dmo)).status).toBe(409);
  });
  it("separation of duties: the verifier cannot approve", async () => {
    mock.state.tables.outletRequests!.find((r) => r.request_uuid === U2)!.verified_by_user_id = 2004; // dmo1 verified it
    const dmo = await signIn("dmo1");
    const res = await act("outlet-requests", U2, "approve", { values: {}, reason: REASON }, dmo);
    expect(res.status).toBe(409);
    expect((await res.json()).code).toBe("ERR_SEPARATION_OF_DUTIES");
  });
  it("TSO and TOP read but cannot act; ids must be UUIDs", async () => {
    const tso = await signIn("mtso1");
    expect((await act("outlet-requests", U4, "reject", { values: {}, reason: REASON }, tso)).status).toBe(403);
    const dmo = await signIn("dmo1");
    expect((await act("outlet-requests", "not-a-uuid", "reject", { values: {}, reason: REASON }, dmo)).status).toBe(404);
    expect((await act("outlet-requests", "1", "reject", { values: {}, reason: REASON }, dmo)).status).toBe(404);
  });
});

describe("wholesale bulk marking", () => {
  const BATCH = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  it("marks outlets once per batch_uuid; a retry replays; an unchanged outlet is counted, not rewritten", async () => {
    const c = await signIn("madmin1");
    const body = { batch_uuid: BATCH, outlet_kind: "wholesale", outlet_ids: [1, 3, 5], reason: "Quarterly wholesale review" };
    const r1 = await (await bulk(body, c)).json();
    expect(r1).toMatchObject({ updated: 2, unchanged: 1, replayed: false });
    const audits = mock.state.audit.length;
    expect(audits).toBe(2); // one audit row per changed outlet
    const r2 = await (await bulk(body, c)).json();
    expect(r2).toMatchObject({ updated: 2, unchanged: 1, replayed: true });
    expect(mock.state.audit.length).toBe(audits);
  });
  it("validates: reason, uuid, ids (1..5000, unique, integers), kind; only ADMIN roles", async () => {
    const c = await signIn("madmin1");
    const ok = { batch_uuid: BATCH, outlet_kind: "wholesale", outlet_ids: [1], reason: REASON };
    for (const bad of [{ ...ok, reason: "short" }, { ...ok, batch_uuid: "nope" }, { ...ok, outlet_ids: [] }, { ...ok, outlet_ids: [1, 1] }, { ...ok, outlet_ids: [1.5] }, { ...ok, outlet_ids: Array.from({ length: 5001 }, (_, i) => i + 1) }, { ...ok, outlet_kind: "x" }, { ...ok, extra: 1 }]) {
      expect((await bulk(bad, c)).status).toBe(400);
    }
    const support = await signIn("msupport1");
    expect((await bulk(ok, support)).status).toBe(403);
    expect(mock.state.audit).toHaveLength(0);
  });
});
