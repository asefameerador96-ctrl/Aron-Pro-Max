// F-ADM-030 / F-ADM-051 quarantine review, F-ADM-050 sync health.
import { describe, expect, it, vi } from "vitest";
import { QuarantineView } from "@/components/admin/config/quarantine-view";
import { SyncHealthView } from "@/components/admin/config/sync-health-view";
import { maskPayload } from "@/lib/admin/mask";
import type { QuarantineItem, SyncHealthPage } from "@/lib/admin/types";
import { admin, op, setupMock } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const h = setupMock();

const q = (id: number, over: Partial<QuarantineItem> = {}): QuarantineItem => ({ quarantine_id: id, client_uuid: "22222222-2222-4222-8222-222222222222", type: "outlet_request", code: "unknown_outlet", status: "open", user_id: 5, business_date: "2026-10-06", received_at: "2026-10-06T04:00:00.000Z", payload: { type: "outlet_request", client_uuid: "x", payload: { owner_name: "Rahim Uddin", contact_phone: "01712345678", qty: 3, outlet: { address: "Banani 11" } } } as never, ...over });
const filters = { status: "", code: "", zone_id: "" };

describe("PII masking", () => {
  it("masks names, phones and addresses at any depth and keeps quantities", () => {
    const m = JSON.stringify(maskPayload(q(1).payload));
    expect(m).not.toContain("Rahim");
    expect(m).not.toContain("01712345678");
    expect(m).not.toContain("Banani");
    expect(m).toContain('"qty":3');
    expect(m).toContain("client_uuid");
  });
});

describe("QuarantineView", () => {
  it("lists parked rows by reason", () => {
    const m = text(html(<QuarantineView locale="en" rows={[q(1), q(2, { code: "unknown_sku", status: "discarded" })]} filters={filters} basePath="/admin/quarantine" nextHref={null} canWrite selected={null} />));
    expect(m).toContain("unknown_outlet");
    expect(m).toContain("unknown_sku");
    expect(m).toContain("Discarded");
  });
  it("the detail shows a masked payload and the four decisions; discarded rows cannot be resolved again", () => {
    const m = html(<QuarantineView locale="en" rows={[q(1)]} filters={filters} basePath="/admin/quarantine" nextHref={null} canWrite selected={q(1)} />);
    expect(m).not.toContain("Rahim");
    expect(m).toContain("••••");
    for (const a of ["accept", "accept_with_fix", "discard", "return_to_device"]) expect(m).toContain(`value="${a}"`);
    expect(html(<QuarantineView locale="en" rows={[]} filters={filters} basePath="/admin/quarantine" nextHref={null} canWrite selected={q(2, { status: "discarded" })} />)).not.toContain("resolve-form");
    expect(html(<QuarantineView locale="en" rows={[]} filters={filters} basePath="/admin/quarantine" nextHref={null} canWrite={false} selected={q(1)} />)).not.toContain("resolve-form");
  });
  it("P14 lists the same rows as the review page for the same data", () => {
    const rows = (m: string) => m.match(/<tbody>[\s\S]*<\/tbody>/)?.[0]?.replace(/\/admin\/(config\/)?quarantine/g, "");
    const a = html(<QuarantineView locale="en" rows={[q(1), q(2)]} filters={filters} basePath="/admin/quarantine" nextHref={null} canWrite selected={null} />);
    const b = html(<QuarantineView locale="en" rows={[q(1), q(2)]} filters={filters} basePath="/admin/config/quarantine" titleKey="cfgp14.title" nextHref={null} canWrite selected={null} />);
    expect(rows(b)).toBe(rows(a));
  });
  it("resolve goes through the proxy with action, fixed record and reason", async () => {
    h.stub({ method: "POST", path: "/v1/admin/quarantine/4/resolve", fn: () => ({ status: 200, body: q(4, { status: "accepted_with_fix" }) }) });
    const body = { action: "accept_with_fix", fixed_record: { type: "outlet_request", client_uuid: "x" } };
    const r = await op(await admin(), { op: "quarantine.resolve", params: { quarantine_id: "4" }, body, reason: "Outlet exists under the old code" });
    expect(r.status).toBe(200);
    expect(h.calls()[0]?.body).toEqual({ ...body, reason: "Outlet exists under the old code" });
  });
});

describe("SyncHealthView", () => {
  const data: SyncHealthPage = { as_of: "2026-10-06T05:00:00.000Z", summary: { devices: 8, devices_with_pending: 2, held_rows_alerts: 1, rejected: 3, quarantined: 4, mismatched_route_days: 1 }, next_cursor: null, items: [{ user_id: 5, device_id: 9, username: "sr334001", app_version: "1.2.0", pending_rows_reported: 6, rejected_count: 0, quarantined_count: 1, held_rows_alert: true, last_contact_at: null, last_sync_error: "timeout", sync_p95_s: 12.34 }] as never };
  it("shows the summary figures exactly as the dashboard endpoint serves them", () => {
    const m = html(<SyncHealthView locale="en" data={data} date="2026-10-06" onlyProblems={false} nextHref={null} />);
    for (const [id, v] of [["sh-devices", 8], ["sh-pending", 2], ["sh-held", 1], ["sh-rejected", 3], ["sh-quarantined", 4], ["sh-mismatch", 1]] as const) expect(m).toContain(`data-testid="${id}">${v}<`);
    expect(text(m)).toContain("sr334001");
    expect(text(m)).toContain("timeout");
    expect(m).toContain("/admin/config/quarantine");
  });
});
