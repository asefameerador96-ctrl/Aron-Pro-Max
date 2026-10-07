// F-TSO-022 / F-ADM-022: the panel's columns and states, the cascade loader, the issue operation.
import { describe, expect, it, vi } from "vitest";
import { DeviceOtpView } from "@/components/admin/config/device-otp-view";
import { loadGeoOptions, type GeoNode } from "@/lib/admin/geo";
import type { DeviceOtp } from "@/lib/admin/types";
import { admin, op, support, token, tso, setupMock } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const h = setupMock();

const node = (id: number, level: GeoNode["level"], name: string, parent_id: number | null): GeoNode => ({ id, level, code: `${level}${id}`, name, name_bn: null, status: "active", parent_id, created_at: "2026-10-01T00:00:00.000Z", updated_at: "2026-10-01T00:00:00.000Z", version: 1 } as unknown as GeoNode);
const otp = (user_id: number, over: Partial<DeviceOtp> = {}): DeviceOtp => ({ user_id, employee_code: `E${user_id}`, zone_code: "Z334", zone_name: "Banani", username: `sr${user_id}`, full_name: `SR ${user_id}`, created_at: "2026-10-06T04:00:00.000Z", expires_at: "2099-01-01T00:00:00.000Z", attempts: 1, otp: "6818", zone_id: 334, device_model: "Redmi 9", ...over });
const empty = { wing: [], division: [], territory: [], house: [], zone: [] };
const base = { locale: "en" as const, action: "/device-otp", options: empty, selection: { zone: "334" }, q: "", nextHref: null, canIssue: false, titleKey: "otp.panel.title" as const, nowMs: Date.parse("2026-10-06T05:00:00Z") };

describe("DeviceOtpView (TSO panel)", () => {
  it("lists every SR with the manual's columns and the OTP", () => {
    const m = text(html(<DeviceOtpView {...base} items={[otp(1), otp(2, { otp: null })]} />));
    for (const c of ["Sr No.", "Field Force ID", "Field Force Name", "Username", "Zone ID", "Create Time", "OTP"]) expect(m).toContain(c);
    expect(m).toContain("6818");
    expect(m).toContain("SR 2");
  });
  it("shows 'No Data' for an empty zone and a prompt before a zone is chosen", () => {
    expect(text(html(<DeviceOtpView {...base} items={[]} />))).toContain("No Data");
    expect(text(html(<DeviceOtpView {...base} items={null} selection={{}} />))).toContain("Choose a zone");
  });
  it("shows the Field Force ID (employee code) and the zone name, not the stand-ins", () => {
    const m = text(html(<DeviceOtpView {...base} items={[otp(1), otp(2, { employee_code: null, zone_name: null, zone_code: null })]} />));
    expect(m).toContain("E1");
    expect(m).toContain("Banani (Z334)");
    expect(m).toContain("Zone");
  });
  it("is view only: no issue button, no log columns", () => {
    const m = html(<DeviceOtpView {...base} items={[otp(1)]} />);
    expect(m).not.toContain("issue-1");
    expect(m).not.toContain("Attempts");
  });
  it("hides a value the server withheld (null) instead of inventing one", () => {
    const m = html(<DeviceOtpView {...base} items={[otp(1, { otp: null })]} />);
    expect(m).not.toContain('data-testid="otp-value"');
  });
  it("uses Bangla labels and digits in bn", () => {
    const m = text(html(<DeviceOtpView {...base} locale="bn" items={[otp(1)]} />));
    expect(m).toContain("ফিল্ড ফোর্সের নাম");
    expect(m).toContain("৩৩৪");
  });
});

describe("DeviceOtpView (admin)", () => {
  it("adds the log columns and a re-issue action per user", () => {
    const m = html(<DeviceOtpView {...base} action="/admin/device-otps" canIssue titleKey="otp.admin.title" items={[otp(7), otp(8, { expires_at: "2020-01-01T00:00:00.000Z", otp: null })]} />);
    expect(m).toContain('data-testid="issue-7"');
    expect(m).toContain('data-testid="issue-8"');
    const t2 = text(m);
    for (const c of ["Attempts", "Expires", "Phone"]) expect(t2).toContain(c);
    expect(t2).toContain("Expired");
    expect(t2).toContain("Redmi 9");
  });
});

describe("geo cascade loader", () => {
  it("asks for children of the chosen parent only, and stops where nothing is chosen", async () => {
    h.stub({ method: "GET", path: /^\/v1\/admin\/geo\/(\w+)$/, fn: (c) => ({ status: 200, body: { next_cursor: null, items: c.path.endsWith("wing") ? [node(1, "wing", "Dhaka", null), node(2, "wing", "Ctg", null)] : c.path.endsWith("division") ? [node(10, "division", "North", Number(c.query.parent_id))] : [] } }) });
    const o = await loadGeoOptions(token(await admin()), {});
    expect(o.wing).toHaveLength(2);
    expect(o.division).toHaveLength(0);
    expect(h.calls().map((c) => c.path)).toEqual(["/v1/admin/geo/wing"]);
  });
});

describe("POST device-otp.issue", () => {
  it("issues for a user with the reason; SUPPORT may, TSO may not through the portal proxy", async () => {
    h.stub({ method: "POST", path: "/v1/admin/device-otps", fn: () => ({ status: 201, body: otp(5, { otp: "1234" }) }) });
    const r = await op(await support(), { op: "device-otp.issue", body: { user_id: 5 }, reason: "Phone replaced, rep cannot log in" });
    expect(r.status).toBe(201);
    expect((await r.json()).data.otp).toBe("1234");
    expect(h.calls()[0]?.body).toEqual({ user_id: 5, reason: "Phone replaced, rep cannot log in" });
    expect((await op(await tso(), { op: "device-otp.issue", body: { user_id: 5 }, reason: "Phone replaced, rep cannot log in" })).status).toBe(403);
    expect((await op(await admin(), { op: "device-otp.issue", body: { user_id: 5 }, reason: "short" })).status).toBe(400);
  });
});
