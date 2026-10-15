// CHECKER v14 (T1 money): dues adjustment and paper backfill through the real BFF (POST /api/bff/admin-op, /api/bff/team-op)
// against the contract mock. Attempts role bypass, body smuggling, flag bypass and path tricks.
import { afterEach, describe, expect, it, vi } from "vitest";
import { POST as teamPost } from "@/app/api/bff/team-op/route";
import { problemMessage } from "@/lib/i18n";
import { admin, op, req, setupMock, signIn, support, tso } from "./helpers/harness";

const h = setupMock();
afterEach(() => vi.unstubAllEnvs());

const U = "3f2b8c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e";
const REASON = "Disputed due after a return";
const dues = (b: Record<string, unknown> = {}) => ({ client_uuid: U, outlet_id: 55, kind: "correction", amount_mtk: -500_000, ...b });
const dhaka = (back: number) => new Date(Date.now() + 6 * 3600_000 - back * 86_400_000).toISOString().slice(0, 10);
const paper = (b: Record<string, unknown> = {}) => ({ client_uuid: U, memo_no: "sr334001-261005-017", business_date: dhaka(1), user_id: 5, route_id: 9, outlet_id: 55, lines: [{ sku_id: 1, qty_base: 10, line_kind: "sale" }], paid_mtk: 0, ...b });
const superadmin = () => signIn("super1", "super-pass-1", true);
const errs = async (r: Response) => ((await r.json()) as { errors?: { pointer: string; code: string }[] }).errors?.map((e) => `${e.pointer}:${e.code}`) ?? [];

describe("dues: role gate at the BFF", () => {
  it("ADMIN and SUPERADMIN propose; SUPPORT and TSO are refused before any API call", async () => {
    h.stub({ method: "POST", path: "/v1/admin/dues-adjustments", fn: () => ({ status: 201, body: {} }) });
    expect((await op(await admin(), { op: "dues.create", body: dues(), reason: REASON })).status).toBe(201);
    expect((await op(await superadmin(), { op: "dues.create", body: dues(), reason: REASON })).status).toBe(201);
    const before = h.calls().length;
    expect((await op(await support(), { op: "dues.create", body: dues(), reason: REASON })).status).toBe(403);
    expect((await op(await tso(), { op: "dues.create", body: dues(), reason: REASON })).status).toBe(403);
    expect(h.calls().length).toBe(before);
  });
  it("only SUPERADMIN decides; ADMIN is 403 without an API call; team-op does not know the op", async () => {
    h.stub({ method: "POST", path: "/v1/admin/dues-adjustments/9/decision", fn: () => ({ status: 200, body: {} }) });
    expect((await op(await admin(), { op: "dues.decide", params: { adjustment_id: "9" }, body: { decision: "approve" }, reason: REASON })).status).toBe(403);
    expect((await teamPost(req("/api/bff/team-op", "POST", { op: "dues.decide", params: { adjustment_id: "9" }, body: { decision: "approve" }, reason: REASON }, await admin()))).status).toBe(400);
    expect(h.calls()).toHaveLength(0);
    expect((await op(await superadmin(), { op: "dues.decide", params: { adjustment_id: "9" }, body: { decision: "approve" }, reason: REASON })).status).toBe(200);
    expect(h.calls()[0]?.body).toEqual({ decision: "approve", note: REASON });
  });
  it("a path segment '..' in params never reaches another API path", async () => {
    h.stub({ method: "POST", path: /.*/, fn: () => ({ status: 200, body: {} }) });
    const r = await op(await superadmin(), { op: "dues.decide", params: { adjustment_id: ".." }, body: { decision: "approve" }, reason: REASON });
    expect(h.calls().map((c) => c.path)).toEqual([]); // fillPath accepts "..", new URL() normalises it to /v1/admin/decision
    expect(r.status).toBe(400);
  });
});

describe("dues: validation at the BFF (no API call, Bangla/English per field)", () => {
  it("zero, above_max, write_off sign (kind switched after typing), not-a-number: each a field code", async () => {
    const c = await admin();
    expect(await errs(await op(c, { op: "dues.create", body: dues({ amount_mtk: 0 }), reason: REASON }))).toEqual(["/amount_mtk:zero"]);
    expect(await errs(await op(c, { op: "dues.create", body: dues({ amount_mtk: -(10 ** 13) - 1 }), reason: REASON }))).toEqual(["/amount_mtk:above_max"]);
    expect(await errs(await op(c, { op: "dues.create", body: dues({ kind: "write_off", amount_mtk: 500_000 }), reason: REASON }))).toEqual(["/amount_mtk:write_off_must_be_negative"]);
    for (const a of ["1e3", "0x10", " 5 ", 1.0005, [5], { v: 5 }, true]) expect(await errs(await op(c, { op: "dues.create", body: dues({ amount_mtk: a }), reason: REASON })), String(a)).toEqual(["/amount_mtk:invalid"]);
    expect(await errs(await op(c, { op: "dues.create", body: dues(), reason: "short" }))).toEqual(["/reason:too_short"]);
    expect(h.calls()).toHaveLength(0);
  });
  it("-0 is zero (JSON -0 parses to -0)", async () => {
    const r = await op(await admin(), { op: "dues.create", body: { ...dues(), amount_mtk: -0 }, reason: REASON });
    expect(r.status).toBe(400);
  });
  it("409 states pass through with their code (separation of duties, already decided, conflict)", async () => {
    for (const code of ["ERR_SEPARATION_OF_DUTIES", "ERR_REQUEST_STATE", "ERR_CONFLICT"]) {
      h.mock.reset();
      h.stub({ method: "POST", path: "/v1/admin/dues-adjustments/9/decision", fn: () => ({ status: 409, body: { type: "about:blank", title: "c", status: 409, code } }) });
      const r = await op(await superadmin(), { op: "dues.decide", params: { adjustment_id: "9" }, body: { decision: "approve" }, reason: REASON });
      expect(r.status).toBe(409);
      expect(((await r.json()) as { code: string }).code).toBe(code);
      for (const l of ["en", "bn"] as const) expect(problemMessage(l, code)).not.toBe(problemMessage(l, "ERR_SOMETHING_UNKNOWN"));
    }
  });
});

describe("paper backfill: dark by default, read per request", () => {
  it("off (unset, '0', 'true', 'yes') answers 404 with no API call for every allowed role; '1' flips it without a restart", async () => {
    h.stub({ method: "POST", path: "/v1/admin/data-entry", fn: () => ({ status: 200, body: { memo_client_uuid: U, status: "created", net_mtk: 0 } }) });
    for (const v of [undefined, "0", "true", "yes", " 1"]) {
      vi.stubEnv("ARON_FEATURE_PAPER_ENTRY", v as string);
      for (const c of [await support(), await admin(), await superadmin()]) expect((await op(c, { op: "paper-backfill.create", body: paper(), reason: REASON })).status, String(v)).toBe(404);
    }
    expect(h.calls()).toHaveLength(0);
    vi.stubEnv("ARON_FEATURE_PAPER_ENTRY", "1");
    expect((await op(await support(), { op: "paper-backfill.create", body: paper(), reason: REASON })).status).toBe(200);
  });
  it("team-op cannot reach the paper entry while dark", async () => {
    const r = await teamPost(req("/api/bff/team-op", "POST", { op: "paper-backfill.create", body: paper(), reason: REASON }, await admin()));
    expect(r.status).toBe(400);
    expect(h.calls()).toHaveLength(0);
  });
  it("TSO is refused even when on", async () => {
    vi.stubEnv("ARON_FEATURE_PAPER_ENTRY", "1");
    expect((await op(await tso(), { op: "paper-backfill.create", body: paper(), reason: REASON })).status).toBe(403);
  });
});

describe("paper backfill rules at the BFF (flag on)", () => {
  it("no discount; window 7 days; lines 1..60, qty 1..10,000,000; paid >= 0", async () => {
    vi.stubEnv("ARON_FEATURE_PAPER_ENTRY", "1");
    const c = await support();
    const run = async (b: Record<string, unknown>) => errs(await op(c, { op: "paper-backfill.create", body: paper(b), reason: REASON }));
    expect(await run({ discount_mtk: 1 })).toEqual(["/discount_mtk:invalid"]);
    expect(await run({ discount_mtk: "0" })).toEqual(["/discount_mtk:invalid"]);
    expect(await run({ business_date: dhaka(8) })).toEqual(["/business_date:window"]);
    expect(await run({ lines: [] })).toEqual(["/lines:invalid"]);
    expect(await run({ lines: Array.from({ length: 61 }, () => ({ sku_id: 1, qty_base: 1, line_kind: "sale" })) })).toEqual(["/lines:invalid"]);
    expect(await run({ lines: [{ sku_id: 1, qty_base: 10_000_001, line_kind: "sale" }] })).toEqual(["/lines/0:invalid"]);
    expect(await run({ lines: [{ sku_id: 1, qty_base: "10", line_kind: "sale" }] })).toEqual(["/lines/0:invalid"]);
    expect(await run({ paid_mtk: -1 })).toEqual(["/paid_mtk:invalid"]);
    expect(h.calls()).toHaveLength(0);
  });
  it("promotion / free line kinds are deferred programmes (docs/27; the API takes only `sale`): refused at the BFF", async () => {
    vi.stubEnv("ARON_FEATURE_PAPER_ENTRY", "1");
    h.stub({ method: "POST", path: "/v1/admin/data-entry", fn: () => ({ status: 400, body: { type: "about:blank", title: "v", status: 400, code: "ERR_VALIDATION", errors: [{ pointer: "body.lines[0].line_kind", code: "line_kind_not_supported" }] } }) });
    for (const k of ["drp_reward", "promo_free", "free_sample"]) {
      const r = await op(await support(), { op: "paper-backfill.create", body: paper({ lines: [{ sku_id: 1, qty_base: 10, line_kind: k }] }), reason: REASON });
      expect(await errs(r), k).toEqual(["/lines/0:invalid"]);
    }
    expect(h.calls()).toHaveLength(0);
  });
  it("ERR_ENTRY_WINDOW_CLOSED (the API's own window, cfg.entry.paper_backfill_window_days) has a message in both languages", () => {
    for (const l of ["en", "bn"] as const) expect(problemMessage(l, "ERR_ENTRY_WINDOW_CLOSED")).not.toBe(problemMessage(l, "ERR_SOMETHING_UNKNOWN"));
  });
});
