// tso-manual W1, FS-14 (build contract s5 W1 and s8 "web reopen"; D-TSO-MAN-29): the /admin/day reopen form posts the
// `day.reopen` op with a `client_uuid`, so a resend after a lost answer replays the first reopen instead of reopening twice,
// while any answer (or a changed request) starts a new key. Zone, date (never in the future) and a reason of 10 to 500
// characters are checked before sending; the roles, the window and the audit are the server's (cfg.day.reopen_roles,
// cfg.day.reopen_window_days). The BFF runs against the contract mock (mock/tso-manual.ts).
import { describe, expect, it, vi } from "vitest";
import { DayControlView } from "@/components/admin/config/day-control-view";
import { DayReopenForm, reopenBody, reopenKey, reopenProblems, reopenRefusal, reopenSignature, settle, REOPEN_REASON_MAX } from "@/components/admin/config/day-reopen-form";
import { OPS } from "@/lib/admin/ops";
import { businessDate } from "@/lib/i18n";
import { admin, op, setupMock, support, tso } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const h = setupMock();

const REASON = "Wrong memo totals found after final";
const U1 = "11111111-1111-4111-8111-111111111111";
const U2 = "22222222-2222-4222-8222-222222222222";
const today = businessDate();
const daysAgo = (n: number) => new Date(Date.parse(`${today}T00:00:00Z`) - n * 86_400_000).toISOString().slice(0, 10);

describe("checks before sending", () => {
  const ok = { zone: "3341", date: today, reason: REASON };
  it("zone, date and reason are required", () => {
    expect(reopenProblems({ zone: "", date: "", reason: "" }, today)).toEqual({ zone_id: "required", business_date: "required", reason: "required" });
    expect(reopenProblems(ok, today)).toEqual({});
  });
  it("the zone is a positive id; Bengali digits are read as digits", () => {
    expect(reopenProblems({ ...ok, zone: "0" }, today)).toEqual({ zone_id: "invalid" });
    expect(reopenProblems({ ...ok, zone: "33a" }, today)).toEqual({ zone_id: "invalid" });
    expect(reopenProblems({ ...ok, zone: "৩৩৪১" }, today)).toEqual({});
    expect(reopenBody({ ...ok, zone: "৩৩৪১" }, U1).zone_id).toBe(3341);
  });
  it("a future day or an impossible date is refused; the server judges the window", () => {
    expect(reopenProblems({ ...ok, date: "2999-01-01" }, today)).toEqual({ business_date: "future" });
    expect(reopenProblems({ ...ok, date: "2026-02-30" }, today)).toEqual({ business_date: "invalid" });
    expect(reopenProblems({ ...ok, date: daysAgo(30) }, today)).toEqual({});
  });
  it("the reason is 10 to 500 characters (code points, trimmed): Bangla letters count once", () => {
    expect(reopenProblems({ ...ok, reason: "  tenletters " }, today)).toEqual({}); // 10 after trimming
    expect(reopenProblems({ ...ok, reason: "   short   " }, today)).toEqual({ reason: "too_short" });
    expect(reopenProblems({ ...ok, reason: "ক".repeat(REOPEN_REASON_MAX) }, today)).toEqual({});
    expect(reopenProblems({ ...ok, reason: "ক".repeat(REOPEN_REASON_MAX + 1) }, today)).toEqual({ reason: "too_long" });
  });
  it("the request is DayReopenRequest with client_uuid and the trimmed reason", () => {
    expect(reopenBody({ zone: " 3341 ", date: "2026-10-28", reason: `  ${REASON}  ` }, U1)).toEqual({ zone_id: 3341, business_date: "2026-10-28", reason: REASON, client_uuid: U1 });
  });
});

describe("the idempotency key (client_uuid)", () => {
  const sig = reopenSignature({ zone: "3341", date: "2026-10-28", reason: REASON });
  const other = reopenSignature({ zone: "3342", date: "2026-10-28", reason: REASON });
  const fresh = () => U2;
  it("the first attempt gets a new key", () => {
    expect(reopenKey(null, sig, fresh)).toBe(U2);
  });
  it("no answer or a 5xx keeps the key for a resend of the same request", () => {
    expect(reopenKey(settle(U1, sig, null), sig, fresh)).toBe(U1);
    expect(reopenKey(settle(U1, sig, 503), sig, fresh)).toBe(U1);
  });
  it("a changed zone, date or reason after a lost answer is a new reopen with a new key (never the old zone's replay)", () => {
    expect(reopenKey(settle(U1, sig, null), other, fresh)).toBe(U2);
  });
  it("any answer settles the key: a success or a refusal starts a new one", () => {
    for (const status of [200, 400, 403, 409]) expect(reopenKey(settle(U1, sig, status), sig, fresh), String(status)).toBe(U2);
  });
  it("the key is not part of what identifies the request", () => {
    expect(reopenSignature({ zone: "3341", date: "2026-10-28", reason: REASON })).toBe(sig);
    expect(sig).not.toContain("client_uuid");
  });
});

describe("the form on /admin/day", () => {
  const late = { rows: [], columns: [], known: true };
  const base = { locale: "en" as const, zone: "3341", date: "2026-10-28", late, missing: null, withMissing: false, basePath: "/admin/day" };
  it("shows the zone and date of the filter, the window hint and the reason field; no reopen form without the right", () => {
    const m = html(<DayControlView {...base} canWrite />);
    expect(m).toContain('data-testid="reopen-form"');
    expect(m).toContain('value="3341"');
    expect(m).toContain('value="2026-10-28"');
    expect(m).toContain(`max="${today}"`);
    // the window's length is the server's (cfg.day.reopen_window_days): the hint names no number (fix round)
    expect(text(m)).toContain("Administrators only, and only within the reopen window after the business date");
    expect(text(m)).not.toMatch(/within \d+ days/);
    expect(m).toContain('name="reason"');
    expect(html(<DayControlView {...base} canWrite={false} />)).not.toContain("reopen-form");
  });
  it("Bangla labels and Bengali digits in the counter", () => {
    const m = text(html(<DayReopenForm zone="" date="" today={today} />, "bn"));
    expect(m).toContain("শুধু অ্যাডমিন");
    expect(m).toContain("(০/৫০০)");
    expect(m).toContain("পুনরায় খুলুন");
  });
});

describe("refusals as the form shows them (fix round: B3's answers)", () => {
  it("409 ERR_REQUEST_STATE is the window refusal on the date field, with the server's window when sent", () => {
    expect(reopenRefusal(409, { code: "ERR_REQUEST_STATE", context: { reopen_window_days: 5 } })).toEqual({ fields: { business_date: "window" }, windowDays: 5, code: "ERR_REQUEST_STATE" });
    expect(reopenRefusal(409, { code: "ERR_REQUEST_STATE" })).toEqual({ fields: { business_date: "window" }, windowDays: null, code: "ERR_REQUEST_STATE" });
    expect(reopenRefusal(409, { code: "ERR_REQUEST_STATE", context: { reopen_window_days: "3" } }).windowDays).toBeNull();
  });
  it("the 400 shape (pointer /business_date, code window) is read the same way", () => {
    expect(reopenRefusal(400, { code: "ERR_VALIDATION", errors: [{ pointer: "/business_date", code: "window" }] }).fields).toEqual({ business_date: "window" });
  });
  it("B3's body errors (code invalid_value) land on their field as invalid; other pointers are ignored", () => {
    expect(reopenRefusal(400, { code: "ERR_VALIDATION", errors: [{ pointer: "/reason", code: "invalid_value" }, { pointer: "/client_uuid", code: "invalid_value" }, { pointer: "body.zone_id", code: "too_long" }] }).fields).toEqual({ reason: "invalid", zone_id: "too_long" });
  });
  it("other refusals have no field: the banner shows the problem text", () => {
    expect(reopenRefusal(409, { code: "ERR_DAY_NOT_FINAL_SUBMITTED" })).toEqual({ fields: {}, windowDays: null, code: "ERR_DAY_NOT_FINAL_SUBMITTED" });
    expect(reopenRefusal(403, { code: "ERR_OUT_OF_SCOPE" }).fields).toEqual({});
    expect(reopenRefusal(400, null)).toEqual({ fields: {}, windowDays: null, code: undefined });
  });
});

describe("the op through the BFF and the contract mock", () => {
  const body = (o: Record<string, unknown> = {}) => ({ zone_id: 3341, business_date: today, client_uuid: U1, ...o });
  it("the op is the admin whitelist entry of POST /v1/day/reopen (ADMIN and SUPERADMIN, reason member)", () => {
    expect(OPS["day.reopen"]).toMatchObject({ method: "POST", path: "/v1/day/reopen", roles: ["ADMIN", "SUPERADMIN"], reason: "reason" });
  });
  it("client_uuid and the reason reach the API unchanged", async () => {
    h.stub({ method: "POST", path: "/v1/day/reopen", fn: () => ({ status: 200, body: { zone_id: 3341, business_date: today, reopened_at: "2026-10-29T04:00:00.000Z", reopened_by: 3001 } }) });
    expect((await op(await admin(), { op: "day.reopen", body: body(), reason: REASON })).status).toBe(200);
    expect(h.calls()[0]?.body).toEqual({ ...body(), reason: REASON });
  });
  it("a replay of the same reopen answers the first result and audits once; the same key with another zone-day is 409", async () => {
    const a = await admin();
    const first = await op(a, { op: "day.reopen", body: body(), reason: REASON });
    expect(first.status).toBe(200);
    const r1 = (await first.json()).data;
    const again = await op(a, { op: "day.reopen", body: body(), reason: REASON });
    expect(again.status).toBe(200);
    expect((await again.json()).data).toEqual(r1);
    expect(h.mock.state.audit.filter((e) => e.entity === "zone_day")).toHaveLength(1);
    expect(h.mock.state.audit.at(-1)).toMatchObject({ entity: "zone_day", entity_id: `3341:${today}`, reason: REASON });
    const reused = await op(a, { op: "day.reopen", body: body({ zone_id: 3342 }), reason: REASON });
    expect(reused.status).toBe(409);
    expect((await reused.json()).code).toBe("ERR_CONFLICT");
  });
  it("a second reopen of the same zone-day with a new key: nothing to reopen (409 ERR_DAY_NOT_FINAL_SUBMITTED)", async () => {
    const a = await admin();
    expect((await op(a, { op: "day.reopen", body: body(), reason: REASON })).status).toBe(200);
    const second = await op(a, { op: "day.reopen", body: body({ client_uuid: U2 }), reason: REASON });
    expect(second.status).toBe(409);
    expect((await second.json()).code).toBe("ERR_DAY_NOT_FINAL_SUBMITTED");
  });
  it("outside the 3-day window: 409 ERR_REQUEST_STATE with context.reopen_window_days, as B3 answers (DayService.reopen)", async () => {
    const r = await op(await admin(), { op: "day.reopen", body: body({ business_date: daysAgo(4) }), reason: REASON });
    expect(r.status).toBe(409);
    const p = await r.json();
    expect(p).toMatchObject({ code: "ERR_REQUEST_STATE", context: { reopen_window_days: 3 } });
    // the form shows it on the date field with the server's window, never the generic "This request is already closed."
    expect(reopenRefusal(409, p)).toEqual({ fields: { business_date: "window" }, windowDays: 3, code: "ERR_REQUEST_STATE" });
    expect((await op(await admin(), { op: "day.reopen", body: body({ business_date: daysAgo(3), client_uuid: U2 }), reason: REASON })).status).toBe(200);
  });
  it("a zone outside the caller's reach is 403 ERR_OUT_OF_SCOPE (B3); a future day is outside the window", async () => {
    const out = await op(await admin(), { op: "day.reopen", body: body({ zone_id: 999_999 }), reason: REASON });
    expect([out.status, (await out.json()).code]).toEqual([403, "ERR_OUT_OF_SCOPE"]);
    const future = await op(await admin(), { op: "day.reopen", body: body({ business_date: "2999-01-01", client_uuid: U2 }), reason: REASON });
    expect([future.status, (await future.json()).code]).toEqual([409, "ERR_REQUEST_STATE"]);
  });
  it("a short reason never reaches the API; SUPPORT and TSO are refused at the BFF", async () => {
    expect((await op(await admin(), { op: "day.reopen", body: body(), reason: "too short" })).status).toBe(400);
    expect((await op(await support(), { op: "day.reopen", body: body(), reason: REASON })).status).toBe(403);
    expect((await op(await tso(), { op: "day.reopen", body: body(), reason: REASON })).status).toBe(403);
    expect(h.mock.state.audit.filter((e) => e.entity === "zone_day")).toHaveLength(0);
  });
});
