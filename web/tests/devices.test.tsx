// F-ADM-009 / F-ADM-048 / F-ADM-078: device list, actions, replace wizard.
import { describe, expect, it, vi } from "vitest";
import { DeviceDetailView } from "@/components/admin/config/device-detail-view";
import { DeviceReplaceView } from "@/components/admin/config/device-replace-view";
import { DevicesView } from "@/components/admin/config/devices-view";
import { parseLabDays } from "@/components/admin/config/lab-exemption-card";
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

describe("lab exemption (D-OWNER-MDM-02 iii)", () => {
  it("grant is a PUT with days and note and no reason; only ADMIN and SUPERADMIN may, never SUPPORT or TSO", async () => {
    h.stub({ method: "PUT", path: "/v1/admin/devices/9/lab-exemption", fn: () => ({ status: 200, body: dev(9) }) });
    const r = await op(await admin(), { op: "device.lab-exemption.grant", params: { device_id: "9" }, body: { days: 3, note: "ADB walk" }, reason: "ignored reason text here" });
    expect(r.status).toBe(200);
    expect(h.calls()[0]?.body).toEqual({ days: 3, note: "ADB walk" });
    expect((await op(await support(), { op: "device.lab-exemption.grant", params: { device_id: "9" }, body: { days: 3 } })).status).toBe(403);
    expect((await op(await tso(), { op: "device.lab-exemption.grant", params: { device_id: "9" }, body: { days: 3 } })).status).toBe(403);
  });
  it("withdraw is a DELETE without a body", async () => {
    h.stub({ method: "DELETE", path: "/v1/admin/devices/9/lab-exemption", fn: () => ({ status: 200, body: dev(9) }) });
    const r = await op(await admin(), { op: "device.lab-exemption.revoke", params: { device_id: "9" }, body: { x: 1 } });
    expect(r.status).toBe(200);
    expect(h.calls()[0]?.body).toBeUndefined();
    expect((await op(await support(), { op: "device.lab-exemption.revoke", params: { device_id: "9" } })).status).toBe(403);
  });
  it("days are whole numbers from 1 to 14", () => {
    for (const ok of ["1", "7", "14", " 3 "]) expect(parseLabDays(ok)).not.toBeNull();
    for (const bad of ["", "0", "15", "2.5", "-1", "abc", "014x", "100"]) expect(parseLabDays(bad)).toBeNull();
  });
  it("the card shows the state, offers the action only to a writer and only when the server can sign", () => {
    const card = (over: Partial<Device>, canLab: boolean) => html(<DeviceDetailView locale="en" device={dev(1, over)} history={[]} directives={[]} canWrite canLab={canLab} />);
    const none = card({ lab_exemption_available: true }, true);
    expect(text(none)).toContain("No exemption");
    expect(none).toContain("lab-grant");
    expect(none).not.toContain("lab-revoke");
    const on = card({ lab_exemption_available: true, lab_exemption: { granted_at: "2026-11-18T04:00:00.000Z", expires_at: "2026-11-20T04:00:00.000Z", granted_by: 1, note: "ADB walk" } }, true);
    expect(text(on)).toContain("Active until");
    expect(text(on)).toContain("ADB walk");
    expect(on).toContain("lab-revoke");
    const off = card({ lab_exemption_available: false }, true);
    expect(off).toContain("lab-unavailable");
    expect(off).not.toContain("lab-grant");
    const reader = card({ lab_exemption_available: true }, false);
    expect(reader).not.toContain("lab-grant");
    expect(text(reader)).toContain("No exemption");
    expect(html(<DeviceDetailView locale="bn" device={dev(1, { lab_exemption_available: true })} history={[]} directives={[]} canWrite canLab />, "bn")).toContain("ডেভেলপার অপশন ছাড়");
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
