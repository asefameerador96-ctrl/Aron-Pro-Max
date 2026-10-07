// N-045 enrolment QR page and N-046 app-block list page.
import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";
import { AppBlockView } from "@/components/admin/config/app-block-view";
import { EnrolmentView, complianceOf } from "@/components/admin/config/enrolment-view";
import { QrCode } from "@/components/admin/config/qr-code";
import type { ConfigKey, Device, EnrolmentToken } from "@/lib/admin/types";
import { DEFAULT_BLOCKED, groupOf, validatePackage } from "@/lib/admin/packages";
import { admin, op, setupMock, support, tso } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const h = setupMock();

describe("QR", () => {
  it("renders a real QR for the provisioning JSON (modules, no HTML injection)", () => {
    const payload = JSON.stringify({ "android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME": "com.aktcl.aron.sr/com.aktcl.aron.dpc.AronDeviceAdminReceiver", x: "<script>alert(1)</script>" });
    const m = html(<QrCode text={payload} label="QR" />);
    expect(m).toContain('data-testid="qr-svg"');
    expect(Number(m.match(/data-modules="(\d+)"/)?.[1])).toBeGreaterThan(20);
    expect(m).not.toContain("<script");
    expect((m.match(/<rect/g) ?? []).length).toBeGreaterThan(200);
  });
});

const tok = (id: number): EnrolmentToken => ({ token_id: id, token_prefix: "q8Zt3k", flavour: "sr", lockdown_level: "prod", max_uses: 50, used_count: 3, zone_id: 5012, expires_at: "2026-10-07T12:00:00.000Z", created_by: 2, created_at: "2026-10-06T06:00:00.000Z", revoked_at: null });
const dev = (id: number, over: Partial<Device>): Device => ({ device_id: id, flavour: "sr", status: "active", device_owner: true, bound_users: [], policy_version_applied: 5, device_info: { manufacturer: "Samsung", model: "A14", os_api_level: 34, os_version: "14", abi: "arm64-v8a", ram_mb: 4096 }, last_contact_at: null, ...over }) as Device;

describe("EnrolmentView", () => {
  it("shows the QR form, active codes with revoke, and each enrolled phone's model, policy version and compliance", () => {
    const m = html(<EnrolmentView locale="en" tokens={[tok(7)]} devices={[dev(1, {}), dev(2, { device_owner: false }), dev(3, { policy_version_applied: 3 })]} currentPolicy={5} canWrite />);
    const x = text(m);
    expect(m).toContain('data-testid="enrolment-form"');
    expect(m).toContain("revoke-token-7");
    expect(x).toContain("Samsung A14");
    expect(x).toContain("Compliant");
    expect(x).toContain("Not device owner");
    expect(x).toContain("Policy not applied");
    expect(x).toContain("q8Zt3k");
  });
  it("read-only roles see no form and no revoke", () => {
    const m = html(<EnrolmentView locale="en" tokens={[tok(7)]} devices={[]} currentPolicy={5} canWrite={false} />);
    expect(m).not.toContain("enrolment-form");
    expect(m).not.toContain("revoke-token-7");
  });
  it("compliance logic", () => {
    expect(complianceOf(dev(1, {}), 5)).toBe("ok");
    expect(complianceOf(dev(1, { policy_version_applied: null }), 5)).toBe("policy_behind");
    expect(complianceOf(dev(1, { device_owner: false }), null)).toBe("not_owner");
  });
  it("create passes the QR payload through once and needs no reason; TSO refused; revoke sends no body", async () => {
    h.stub({ method: "POST", path: "/v1/admin/enrolment-tokens", fn: () => ({ status: 201, body: { token: tok(8), enrolment_token: "x".repeat(43), qr_payload: {}, qr_text: "{\"a\":1}" } }) });
    h.stub({ method: "POST", path: "/v1/admin/enrolment-tokens/8/revoke", fn: () => ({ status: 200, body: tok(8) }) });
    const body = { flavour: "sr", lockdown_level: "prod", max_uses: 50, expires_in_h: 24, zone_id: 5012 };
    const r = await op(await support(), { op: "enrolment.create", body });
    expect(r.status).toBe(201);
    expect((await r.json()).data.qr_text).toBe('{"a":1}');
    expect(h.calls()[0]?.body).toEqual(body);
    expect((await op(await tso(), { op: "enrolment.create", body })).status).toBe(403);
    expect((await op(await admin(), { op: "enrolment.revoke", params: { token_id: "8" } })).status).toBe(200);
    expect(h.calls()[1]?.body).toBeUndefined();
  });
});

describe("app block list", () => {
  it("the default blocked list matches docs/24 s10.6 and the sponsor's categories", () => {
    const doc = readFileSync("../docs/24-build-spec.md", "utf8");
    const section = doc.slice(doc.indexOf("### 10.6 Default lists"), doc.indexOf("## 11. Geo-integrity"));
    const blocked = section.split("\n").find((l) => l.startsWith("- Blocked:")) ?? "";
    const pkgs = [...blocked.matchAll(/`([a-z0-9_.]+)`/g)].map((m) => m[1]!).filter((p) => p.includes(".") && !p.startsWith("cfg."));
    expect([...pkgs].sort()).toEqual([...DEFAULT_BLOCKED].sort());
    expect(groupOf("com.whatsapp")).toBe("messaging");
    expect(groupOf("com.dts.freefireth")).toBe("games");
    expect(groupOf("com.unknown.app")).toBe("other");
  });
  it("validates package names, duplicates and the cap", () => {
    expect(validatePackage("com.facebook.katana", [], 300)).toBeNull();
    expect(validatePackage("facebook", [], 300)).toBe("invalid");
    expect(validatePackage("com.x y", [], 300)).toBe("invalid");
    expect(validatePackage("com.a.b", ["com.a.b"], 300)).toBe("duplicate");
    expect(validatePackage("com.a.c", ["com.a.b"], 1)).toBe("too_many");
    expect(validatePackage(`com.${"a".repeat(130)}.b`, [], 300)).toBe("invalid");
  });
  const key = (k: string, t2: ConfigKey["value_type"], def: unknown, bounds = {}): ConfigKey => ({ key: k, area: "device", kind: "S", value_type: t2, default_value: def as never, bounds, scope_levels: ["global"], risk_class: 2, effect: "B", delivery: "dev", requires_ack: false, future_dated_only: false, editor_permission: "cfg.edit.device", description_en: "" });
  const keys = [key("cfg.device.blocked_packages", "list", ["com.facebook.katana"], { max_items: 300 }), key("cfg.device.allowed_packages", "list", []), key("cfg.device.always_allowed_packages", "list", ["com.whatsapp"]), key("cfg.device.blocking_enabled", "bool", true)];
  it("lists the three package lists with groups and the settings forms, for writers only", () => {
    const m = html(<AppBlockView locale="en" keys={keys} values={{}} version={321} canWrite />);
    expect(m).toContain('data-testid="pkg-cfg.device.blocked_packages"');
    expect(text(m)).toContain("com.facebook.katana");
    expect(text(m)).toContain("Social media");
    expect(text(m)).toContain("Messaging");
    expect(text(m)).toContain("config version 321");
    expect(m).toContain('data-key="cfg.device.blocking_enabled"');
    const ro = html(<AppBlockView locale="en" keys={keys} values={{}} version={321} canWrite={false} />);
    expect(ro).not.toContain("data-key=");
    expect(text(ro)).not.toContain("Add package name Add");
  });
  it("saving the list is one config change of the whole list", async () => {
    h.stub({ method: "POST", path: "/v1/admin/config/changes", fn: () => ({ status: 201, body: { status: "applied" } }) });
    const r = await op(await admin(), { op: "config.change", body: { changes: [{ key: "cfg.device.blocked_packages", scope_type: "global", scope_id: 0, value: ["com.facebook.katana", "com.new.game"] }] }, reason: "Add a new game seen in the field" });
    expect(r.status).toBe(201);
    expect(h.calls()[0]?.body).toMatchObject({ changes: [{ value: ["com.facebook.katana", "com.new.game"] }] });
  });
});
