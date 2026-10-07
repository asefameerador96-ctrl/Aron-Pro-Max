// F-ADM-009 / F-ADM-048 / F-ADM-078: device list, actions, replace wizard.
import { describe, expect, it, vi } from "vitest";
import { DeviceDetailView } from "@/components/admin/config/device-detail-view";
import { DeviceReplaceView } from "@/components/admin/config/device-replace-view";
import { DevicesView } from "@/components/admin/config/devices-view";
import type { Device } from "@/lib/admin/types";
import { admin, op, setupMock, support, tso } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const h = setupMock();

const dev = (id: number, over: Partial<Device> = {}): Device => ({ device_id: id, device_uuid: "11111111-1111-4111-8111-111111111111", flavour: "sr", status: "active", device_owner: true, lockdown_level: "prod", trust_level: "high", enrolled_at: "2026-10-01T04:00:00.000Z", bound_users: [{ user_id: 5, username: "sr334001", bind_ordinal: 1, bound_at: "2026-10-01T04:00:00.000Z", status: "active" }], app_version: "1.2.0", config_version_applied: 318, device_info: { manufacturer: "Xiaomi", model: "Redmi 9", os_api_level: 30, os_version: "11", abi: "arm64-v8a", ram_mb: 3072 }, last_contact_at: "2026-10-06T04:00:00.000Z", integrity_verdict: "pass", pending_rows_reported: 4, last_status: { printer: { bonded_name: "RPP02N" } } as Device["last_status"], ...over } as Device);
const filters = { status: "", trust_level: "", flavour: "", q: "", zone_id: "" };

describe("DevicesView", () => {
  it("shows user, last seen, app and config version, trust, printer, state", () => {
    const m = text(html(<DevicesView locale="en" rows={[dev(1)]} filters={filters} nextHref={null} canWrite />));
    for (const x of ["sr334001", "Xiaomi Redmi 9", "1.2.0", "318", "High (pass)", "RPP02N", "Active"]) expect(m).toContain(x);
  });
  it("offers revoke only to writers, reactivate only when suspended, nothing for a revoked phone", () => {
    const w = html(<DevicesView locale="en" rows={[dev(1), dev(2, { status: "suspended" }), dev(3, { status: "revoked" })]} filters={filters} nextHref={null} canWrite />);
    expect(w).toContain("revoke-1");
    expect(w).toContain("reactivate-2");
    expect(w).not.toContain("revoke-3");
    expect(w).not.toContain("reactivate-3");
    expect(html(<DevicesView locale="en" rows={[dev(1)]} filters={filters} nextHref={null} canWrite={false} />)).not.toContain("revoke-1");
  });
  it("states that revoking keeps local data", () => {
    expect(text(html(<DevicesView locale="en" rows={[]} filters={filters} nextHref={null} canWrite />))).toContain("never deletes what it captured");
  });
});

describe("device.state through the proxy", () => {
  it("revoke goes to the state endpoint with the action and reason; SUPPORT may, TSO may not", async () => {
    h.stub({ method: "POST", path: "/v1/admin/devices/9/state", fn: () => ({ status: 200, body: dev(9, { status: "revoked" }) }) });
    const r = await op(await support(), { op: "device.state", params: { device_id: "9" }, body: { action: "revoke" }, reason: "Phone lost in the field" });
    expect(r.status).toBe(200);
    expect(h.calls()[0]?.body).toEqual({ action: "revoke", reason: "Phone lost in the field" });
    expect((await op(await tso(), { op: "device.state", params: { device_id: "9" }, body: { action: "revoke" }, reason: "Phone lost in the field" })).status).toBe(403);
  });
  it("a directive needs no reason and cannot carry one into the body", async () => {
    h.stub({ method: "POST", path: "/v1/admin/devices/9/directives", fn: () => ({ status: 201, body: {} }) });
    const r = await op(await admin(), { op: "device.directive", params: { device_id: "9" }, body: { type: "send_status" }, reason: "ignored reason text here" });
    expect(r.status).toBe(201);
    expect(h.calls()[0]?.body).toEqual({ type: "send_status" });
  });
});

describe("DeviceDetailView", () => {
  it("lists bound users, history and directive buttons", () => {
    const m = html(<DeviceDetailView locale="en" device={dev(1)} history={[]} directives={[]} canWrite />);
    expect(m).toContain("directive-send_status");
    expect(text(m)).toContain("sr334001");
  });
});

describe("replace wizard", () => {
  it("shows the old phone's pending rows and both modes", () => {
    const m = html(<DeviceReplaceView locale="en" device={dev(1, { pending_rows_reported: 4 })} />);
    expect(m).toContain('data-testid="pending-rows">4<');
    expect(m).toContain("upload_first");
    expect(m).toContain("revoke_now");
  });
  it("replace goes through the proxy and returns the new OTP", async () => {
    h.stub({ method: "POST", path: "/v1/admin/devices/9/replace", fn: () => ({ status: 200, body: { old_device_id: 9, old_state: "replaced", pending_rows: 0, otp: { otp: "4821" } } }) });
    const r = await op(await support(), { op: "device.replace", params: { device_id: "9" }, body: { mode: "upload_first" }, reason: "New phone issued after damage" });
    expect((await r.json()).data.otp.otp).toBe("4821");
    expect(h.calls()[0]?.body).toEqual({ mode: "upload_first", reason: "New phone issued after damage" });
  });
});
