// T1 checker (F-ADM-030b): role bypass, params and body smuggling through /api/bff/admin-op, and where the form shows.
import { describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));

import { QuarantineView } from "@/components/admin/config/quarantine-view";
import { OPS } from "@/lib/admin/ops";
import type { QuarantineItem } from "@/lib/admin/types";
import { admin, op, setupMock, signIn, support } from "./helpers/harness";
import { html } from "./helpers/render";

const h = setupMock();
const PATH = /\/v1\/admin\/quarantine\/[^/]+\/match-backfill$/;
const OK = { paper_memo_id: 1, paper_memo_uuid: "u", released: [], kept_open: [], voided_paper_visit_uuid: null, phone_visit_uuid: null };
const body = (quarantine_id: unknown, extra: Record<string, unknown> = {}) => ({ op: "quarantine.match-backfill", params: { quarantine_id }, reason: "Same memo keyed on paper", ...extra });
const apiCalls = () => h.calls().filter((c) => PATH.test(c.path));

describe("checker bm: role gate", () => {
  it("the op is SUPPORT, ADMIN, SUPERADMIN only (no TOP, ANALYST, WM, DMO, TSO, SR, AMO)", () => {
    expect([...OPS["quarantine.match-backfill"].roles].sort()).toEqual(["ADMIN", "SUPERADMIN", "SUPPORT"]);
  });
  it.each([
    ["tso334", "tso-pass-1"],
    ["dmo1", "dmo-pass-1"],
    ["wm1", "wm-pass-1"],
    ["analyst1", "analyst-pass-1"],
    ["sr334001", "sr-pass-1"],
  ])("%s gets 403 and the API is never called", async (u, p) => {
    h.stub({ method: "POST", path: PATH, fn: () => ({ status: 200, body: OK }) });
    const r = await op(await signIn(u, p), body("7"));
    expect([401, 403]).toContain(r.status);
    expect(apiCalls()).toHaveLength(0);
  });
  it("SUPERADMIN passes", async () => {
    h.stub({ method: "POST", path: PATH, fn: () => ({ status: 200, body: OK }) });
    expect((await op(await signIn("super1", "super-pass-1", true), body("7"))).status).toBe(200);
  });
});

describe("checker bm: params and body smuggling", () => {
  it.each(["..", "1/../2", "../resolve", "7?x=1", "7#a", "9".repeat(65), "৭", "７", "", " 7", null, {}, [7]])("quarantine_id %j is refused by the BFF with no API call", async (id) => {
    const r = await op(await admin(), body(id));
    expect(r.status).toBe(400);
    expect(apiCalls()).toHaveLength(0);
  });
  it("a non-numeric but pattern-safe id reaches only the fixed path (the API answers 400 path.quarantine_id)", async () => {
    let seen = "";
    h.stub({ method: "POST", path: PATH, fn: (r) => ((seen = r.path), { status: 400, body: { type: "about:blank", title: "bad", status: 400, code: "ERR_VALIDATION" } }) });
    const r = await op(await support(), body("abc"));
    expect(r.status).toBe(400);
    expect(seen === "" || /\/v1\/admin\/quarantine\/abc\/match-backfill$/.test(seen)).toBe(true);
  });
  it("a reason smuggled inside body cannot replace the checked top-level reason", async () => {
    let got: Record<string, unknown> = {};
    h.stub({ method: "POST", path: PATH, fn: (r) => ((got = r.body as Record<string, unknown>), { status: 200, body: OK }) });
    const r = await op(await admin(), body("7", { body: { reason: "x" } }));
    expect(r.status).toBe(200);
    if (got && Object.keys(got).length) expect(got.reason).toBe("Same memo keyed on paper");
  });
  it("a body-only reason (no top-level reason) is refused 400 before the API", async () => {
    const r = await op(await admin(), { op: "quarantine.match-backfill", params: { quarantine_id: "7" }, body: { reason: "Same memo keyed on paper" } });
    expect(r.status).toBe(400);
    expect(apiCalls()).toHaveLength(0);
  });
});

const item = (code: string, status: string, type = "memo"): QuarantineItem => ({ quarantine_id: 7, client_uuid: "u", type, code, status, user_id: 5, business_date: "2026-10-06", received_at: "2026-10-06T04:00:00.000Z", payload: {} }) as unknown as QuarantineItem;
const view = (sel: QuarantineItem) => html(<QuarantineView locale="en" rows={[sel]} filters={{ status: "", code: "", zone_id: "" }} basePath="/admin/quarantine" nextHref={null} canWrite canMatch selected={sel} />);

describe("checker bm: visibility", () => {
  it.each(["accepted", "accepted_with_fix", "discarded"])("not on a %s item", (s) => {
    expect(view(item("memo_no_duplicate", s))).not.toContain("backfill-match");
  });
  // Held children of the memo (lines, collections, voids) carry the same code memo_no_duplicate (IngestService.heldChildren);
  // the server refuses them 409 ERR_REQUEST_STATE, so the action must not be offered there.
  it.each(["memo_line", "collection", "memo_void", "print_event"])("not on a held child of type %s with code memo_no_duplicate", (type) => {
    expect(view(item("memo_no_duplicate", "open", type))).not.toContain("backfill-match");
  });
});
