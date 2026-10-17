// F-ADM-030b: role gate and visibility of the "same memo as backfill" action.
import { describe, expect, it, vi } from "vitest";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));

// Role gate and visibility (BFF + page): SUPPORT, ADMIN, SUPERADMIN; the form only on an open memo_no_duplicate item.
import { QuarantineView } from "@/components/admin/config/quarantine-view";
import type { QuarantineItem } from "@/lib/admin/types";
import { admin, op, setupMock, support, tso } from "./helpers/harness";
import { html } from "./helpers/render";

const h = setupMock();
describe("backfill match: roles and visibility", () => {
  it("SUPPORT and ADMIN pass through to the API; a TSO gets 403 with no API call", async () => {
    h.stub({ method: "POST", path: /\/v1\/admin\/quarantine\/7\/match-backfill$/, fn: () => ({ status: 200, body: { paper_memo_id: 1, paper_memo_uuid: "u", released: [], kept_open: [], voided_paper_visit_uuid: null, phone_visit_uuid: null } }) });
    const body = { op: "quarantine.match-backfill", params: { quarantine_id: "7" }, reason: "Same memo keyed on paper" };
    expect((await op(await support(), body)).status).toBe(200);
    expect((await op(await admin(), body)).status).toBe(200);
    const before = h.calls().length;
    expect((await op(await tso(), body)).status).toBe(403);
    expect(h.calls().length).toBe(before);
  });
  it("the reason is required (BFF 400, no API call) and params cannot climb the path", async () => {
    const before = h.calls().length;
    expect((await op(await admin(), { op: "quarantine.match-backfill", params: { quarantine_id: "7" } })).status).toBe(400);
    expect((await op(await admin(), { op: "quarantine.match-backfill", params: { quarantine_id: ".." }, reason: "Same memo keyed on paper" })).status).toBe(400);
    expect(h.calls().length).toBe(before);
  });
  const item = (code: string, status: string): QuarantineItem => ({ quarantine_id: 7, client_uuid: "u", type: "memo", code, status, user_id: 5, business_date: "2026-10-06", received_at: "2026-10-06T04:00:00.000Z", payload: {} }) as unknown as QuarantineItem;
  const view = (sel: QuarantineItem, canMatch: boolean) => html(<QuarantineView locale="en" rows={[sel]} filters={{ status: "", code: "", zone_id: "" }} basePath="/admin/quarantine" nextHref={null} canWrite canMatch={canMatch} selected={sel} />);
  it("the form shows only for an open memo_no_duplicate item and a role that may match", () => {
    expect(view(item("memo_no_duplicate", "open"), true)).toContain("backfill-match");
    expect(view(item("memo_no_duplicate", "open"), false)).not.toContain("backfill-match");
    expect(view(item("memo_no_duplicate", "accepted"), true)).not.toContain("backfill-match");
    expect(view(item("unknown_outlet", "open"), true)).not.toContain("backfill-match");
  });
});
