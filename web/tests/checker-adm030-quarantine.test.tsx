// CHECKER (F-ADM-030 / F-ADM-051 / F-ADM-050): attempts to refute PII masking, paging and resolve consistency.
import { describe, expect, it, vi } from "vitest";
import { QuarantinePageContent } from "@/components/admin/config/quarantine-page";
import { maskPayload } from "@/lib/admin/mask";
import type { QuarantineItem } from "@/lib/admin/types";
import { admin, op, setupMock, support, token } from "./helpers/harness";
import { html } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }), redirect: () => { throw new Error("redirect"); } }));
const session = { current: null as null | { user: { role: string }; at: string } };
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => session.current }));
vi.mock("@/lib/auth/service", async (orig) => ({ ...(await orig<Record<string, unknown>>()), getLocale: async () => "en" }));
const h = setupMock();

// A realistic outlet_change_request record (contract OutletChangeRequestPayload + GeoFix).
const record = {
  type: "outlet_change_request",
  client_uuid: "22222222-2222-4222-8222-222222222222",
  payload: {
    request_type: "new",
    proposed: { name: "Mayer Doa Store", owner_name: "Rahim Uddin", contact_number: "01712345678", lat: 23.79412, lng: 90.40431 },
    fix: { purpose: "outlet_request", fix_status: "ok", lat: 23.79415, lng: 90.40433, accuracy_m: 8, altitude_m: 12.5, provider: "fused", is_mock: false, reused: false, device: {} },
    photo_uuids: ["33333333-3333-4333-8333-333333333333"],
    note: "Owner asked to call 01898765432 after 5pm",
    answer_text: "মালিকের নম্বর ০১৭১২৩৪৫৬৭৮",
  },
};

describe("maskPayload against contract record members (docs/21 s4: raw GPS is N for admin/support)", () => {
  const m = JSON.stringify(maskPayload(record));
  it("keeps the names and the outlet phone masked (baseline)", () => {
    expect(m).not.toContain("Rahim");
    expect(m).not.toContain("01712345678");
  });
  it("masks the raw GPS fix (fix.lat / fix.lng / altitude)", () => {
    expect(m).not.toContain("23.79415");
    expect(m).not.toContain("90.40433");
  });
  it("masks the proposed outlet coordinates", () => {
    expect(m).not.toContain("23.79412");
  });
  it("masks a Bangladesh phone number inside free text (docs/21 s4 scrub pattern (\\+?88)?01[3-9]\\d{8})", () => {
    expect(m).not.toContain("01898765432");
  });
  it("masks a phone number written in Bengali digits inside free text", () => {
    expect(m).not.toContain("০১৭১২৩৪৫৬৭৮");
  });
  it("masks members of an object nested under a PII key", () => {
    const n = JSON.stringify(maskPayload({ owner: { first: "Rahim", last: "Uddin" }, address: { line1: "House 12, Road 5, Banani" } }));
    expect(n).not.toContain("Rahim");
    expect(n).not.toContain("Banani");
  });
});

const item = (id: number): QuarantineItem => ({ quarantine_id: id, client_uuid: record.client_uuid, type: "outlet_change_request", code: "unknown_outlet", status: "open", user_id: 5, business_date: "2026-10-06", received_at: "2026-10-06T04:00:00.000Z", payload: record } as unknown as QuarantineItem);

describe("QuarantinePageContent paging", () => {
  it("the Review link of a row on page 2 keeps the cursor, so the row can be opened", async () => {
    session.current = { user: { role: "ADMIN" }, at: token(await admin()) };
    h.stub({ method: "GET", path: "/v1/admin/quarantine", fn: (c) => ({ status: 200, body: c.query.cursor === "page2" ? { items: [item(77)], next_cursor: null } : { items: [item(1)], next_cursor: "page2" } }) });
    const el = await QuarantinePageContent({ searchParams: Promise.resolve({ cursor: "page2" }), basePath: "/admin/quarantine" });
    const m = html(el);
    const href = m.match(/href="([^"]*item=77[^"]*)"/)?.[1]?.replace(/&amp;/g, "&") ?? "";
    expect(href).toContain("cursor=page2");
    // following the link as rendered: the row must be found and the detail shown
    const sp = Object.fromEntries(new URLSearchParams(href.split("?")[1] ?? ""));
    const opened = html(await QuarantinePageContent({ searchParams: Promise.resolve(sp), basePath: "/admin/quarantine" }));
    expect(opened).toContain("quarantine-detail");
  });
});

describe("quarantine.resolve consistency", () => {
  it("SUPPORT cannot resolve (baseline)", async () => {
    expect((await op(await support(), { op: "quarantine.resolve", params: { quarantine_id: "4" }, body: { action: "discard" }, reason: "Duplicate row from the old phone" })).status).toBe(403);
  });
  it("accept_with_fix without a fixed_record is refused before it reaches the API (contract: required for accept_with_fix)", async () => {
    h.stub({ method: "POST", path: "/v1/admin/quarantine/4/resolve", fn: () => ({ status: 200, body: {} }) });
    const r = await op(await admin(), { op: "quarantine.resolve", params: { quarantine_id: "4" }, body: { action: "accept_with_fix", fixed_record: null }, reason: "Outlet exists under the old code" });
    expect(r.status).toBe(400);
  });
  it("a fixed_record copied from the masked payload (mask sentinels) is refused", async () => {
    h.stub({ method: "POST", path: "/v1/admin/quarantine/4/resolve", fn: () => ({ status: 200, body: {} }) });
    const fixed = maskPayload(record);
    const r = await op(await admin(), { op: "quarantine.resolve", params: { quarantine_id: "4" }, body: { action: "accept_with_fix", fixed_record: fixed }, reason: "Outlet exists under the old code" });
    expect(r.status).toBe(400);
  });
});

describe("P13 sync health page (F-ADM-050)", () => {
  const page = { as_of: "2026-10-06T05:00:00.000Z", summary: { devices: 1, devices_with_pending: 0, held_rows_alerts: 0, rejected: 0, quarantined: 0, mismatched_route_days: 0 }, next_cursor: null, items: [] };
  it("defaults to today's Dhaka business date and forwards only_problems", async () => {
    const { default: SyncHealthAdminPage } = await import("@/app/admin/config/sync-health/page");
    session.current = { user: { role: "SUPPORT" }, at: token(await support()) };
    h.stub({ method: "GET", path: "/v1/dashboards/sync-health", fn: () => ({ status: 200, body: page }) });
    html(await SyncHealthAdminPage({ searchParams: Promise.resolve({ only_problems: "true" }) }));
    const call = h.calls().find((c) => c.path === "/v1/dashboards/sync-health");
    const dhaka = new Date(Date.now() + 6 * 3600_000).toISOString().slice(0, 10);
    expect(call?.query.business_date).toBe(dhaka);
    expect(call?.query.only_problems).toBe("true");
  });
  it("an impossible calendar date in the URL falls back instead of being sent to the API", async () => {
    const { default: SyncHealthAdminPage } = await import("@/app/admin/config/sync-health/page");
    session.current = { user: { role: "ADMIN" }, at: token(await admin()) };
    h.stub({ method: "GET", path: "/v1/dashboards/sync-health", fn: () => ({ status: 200, body: page }) });
    html(await SyncHealthAdminPage({ searchParams: Promise.resolve({ business_date: "2026-02-30" }) }));
    expect(h.calls().find((c) => c.path === "/v1/dashboards/sync-health")?.query.business_date).not.toBe("2026-02-30");
  });
});
