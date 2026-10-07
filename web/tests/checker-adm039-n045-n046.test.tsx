// CHECKER (independent, T1) for F-ADM-039 geofence radius, N-045 enrolment QR, N-046 app-block list.
// Each failing test names a defect; passing ones are refutation attempts that held.
import { readFileSync } from "node:fs";
import { describe, expect, it, vi } from "vitest";
import { AppBlockView } from "@/components/admin/config/app-block-view";
import { complianceOf } from "@/components/admin/config/enrolment-view";
import { QrCode } from "@/components/admin/config/qr-code";
import { parseConfigInput } from "@/lib/admin/config";
import { groupOf } from "@/lib/admin/packages";
import type { ConfigKey, Device } from "@/lib/admin/types";
import { admin, setupMock, token } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }), notFound: () => { throw new Error("notFound"); }, redirect: () => { throw new Error("redirect"); } }));
const session = { current: null as null | { user: { role: string }; at: string } };
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => session.current }));
vi.mock("@/lib/auth/service", async (orig) => ({ ...(await orig<Record<string, unknown>>()), getLocale: async () => "en" }));
const cfg = vi.hoisted(() => ({ keys: [] as unknown[], values: [] as unknown[] }));
vi.mock("@/lib/admin/config-load", async (orig) => ({
  ...(await orig<Record<string, unknown>>()),
  listKeys: async () => ({ ok: true, status: 200, data: { items: cfg.keys } }),
  latestVersion: async () => ({ ok: true, status: 200, data: { config_version: 900, version: 900 } }),
  versionDetail: async () => ({ ok: true, status: 200, data: { config_version: 900, committed_at: "2026-10-06T00:00:00.000Z", values: cfg.values } }),
}));
setupMock();

// Seeded registry row (db/migrations/V0006__cfg_key_registry.sql line 11): bounds are DYNAMIC only.
const RADIUS_BOUNDS = { dynamic_min: "cfg.geo.radius_min_m", dynamic_max: "cfg.geo.radius_max_m" };

describe("F-ADM-039 radius bounds (docs/24 s9.5: radius_min_m..radius_max_m, hard 10..5000)", () => {
  for (const bad of ["0", "-50", "5", "999999"]) {
    it(`the save form refuses radius ${bad} m with the seeded (dynamic) bounds`, () => {
      expect(parseConfigInput("int", bad, RADIUS_BOUNDS as never, "cfg.geo.radius_m").ok).toBe(false);
    });
  }
  it("a normal radius is accepted (control)", () => {
    expect(parseConfigInput("int", "120", RADIUS_BOUNDS as never, "cfg.geo.radius_m")).toEqual({ ok: true, value: 120 });
  });
});

describe("N-046 blocking settings bounds", () => {
  it("cfg.device.blocking_hard_end_time refuses 03:00 (registry bound 12:00..23:59)", () => {
    expect(parseConfigInput("time", "03:00", {}, "cfg.device.blocking_hard_end_time").ok).toBe(false);
  });
});

describe("N-045 QR encoding of qr_text", () => {
  const base = (ssid: string) => JSON.stringify({ "android.app.extra.PROVISIONING_WIFI_SSID": ssid, "android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE": { "aron.enrolment_token": "A".repeat(43) } });
  it("encodes non-ASCII text byte-exact (UTF-8): two different SSIDs must not give the same QR", () => {
    // U+0995 (Bengali KA) and U+0195 share the low byte 0x95; qrcode-generator's default stringToBytes keeps only c & 0xff.
    const a = html(<QrCode text={base("অফিস-ক")} label="QR" />);
    const b = html(<QrCode text={base("অফিস-ƕ")} label="QR" />);
    expect(a).not.toEqual(b);
  });
  it("a ~1200 character payload renders (control)", () => {
    const m = html(<QrCode text={base("x").padEnd(1200, " ")} label="QR" />);
    expect(Number(m.match(/data-modules="(\d+)"/)?.[1])).toBeGreaterThan(100);
  });
  it("a payload inside the contract's qr_text maxLength (4000) does not crash the page", () => {
    expect(() => html(<QrCode text={base("x").padEnd(2600, " ")} label="QR" />)).not.toThrow();
  });
});

describe("N-045 compliance column", () => {
  it("a phone that applied the current device policy is not 'policy behind' because an unrelated config key moved the global version", () => {
    // policy_version only advances when cfg.device.*/cfg.geo.* change (delta policy_changed, docs/24 s10.2); the page passes the latest
    // config version of ANY key as currentPolicy.
    const d = { device_id: 1, flavour: "sr", status: "active", device_owner: true, bound_users: [], policy_version_applied: 880, config_version_applied: 900 } as unknown as Device;
    expect(complianceOf(d, 900)).toBe("ok");
  });
});

describe("F-ADM-039 map loader", () => {
  it("with loading=async the Maps loader needs a callback or importLibrary; onload alone does not guarantee google.maps.Map", () => {
    const src = readFileSync("src/components/admin/config/radius-map.tsx", "utf8");
    if (src.includes("loading=async")) expect(/callback=|importLibrary/.test(src)).toBe(true);
  });
});

const key = (k: string, t2: ConfigKey["value_type"], def: unknown, bounds = {}, levels: ConfigKey["scope_levels"] = ["global"]): ConfigKey => ({ key: k, area: "device", kind: "S", value_type: t2, default_value: def as never, bounds, scope_levels: levels, risk_class: 2, effect: "B", delivery: "dev", requires_ack: false, future_dated_only: false, editor_permission: "cfg.edit.security", description_en: "" });
const keys = [
  key("cfg.device.blocked_packages", "list", ["com.facebook.katana"], { max_items: 300 }, ["global", "zone"]),
  key("cfg.device.allowed_packages", "list", [], { max_items: 300 }),
  key("cfg.device.always_allowed_packages", "list", ["com.google.android.dialer"], { max_items: 100 }),
  key("cfg.device.app_control_mode", "enum", "blocklist", { enum: ["blocklist", "allowlist"] }),
  key("cfg.device.blocking_enabled", "bool", true),
];

describe("N-046 app-block page", () => {
  it("setting labels are localized (bn page shows no raw cfg.* key as a label)", () => {
    const x = text(html(<AppBlockView locale="bn" keys={keys} values={{}} version={3} canWrite />, "bn"));
    expect(x).not.toMatch(/cfg\.device\.[a-z_]+/);
  });
  it("app-control mode options are localized, not raw enum values", () => {
    const m = html(<AppBlockView locale="bn" keys={keys} values={{}} version={3} canWrite />, "bn");
    expect(m).not.toMatch(/>blocklist<|>allowlist</);
  });
  it("always-allowed essentials (dialer) are labelled 'Essential', not 'Other'", () => {
    expect(groupOf("com.google.android.dialer")).toBe("essential");
  });
  it("the editor starts from the GLOBAL row, not a zone override that happens to come later in the version", async () => {
    session.current = { user: { role: "ADMIN" }, at: token(await admin()) };
    cfg.keys = keys;
    cfg.values = [
      { key: "cfg.device.blocked_packages", value: ["com.facebook.katana", "com.tencent.ig"], scope_type: "global", scope_id: 0, effective_from: null, requires_ack: false },
      { key: "cfg.device.blocked_packages", value: ["com.zone.only"], scope_type: "zone", scope_id: 5012, effective_from: null, requires_ack: false },
    ];
    const { default: Page } = await import("@/app/admin/config/app-block/page");
    const m = html(await Page());
    const blocked = m.slice(m.indexOf('data-testid="pkg-cfg.device.blocked_packages"'), m.indexOf('data-testid="pkg-cfg.device.allowed_packages"'));
    expect(blocked).toContain("com.tencent.ig");
    expect(blocked).not.toContain("com.zone.only");
  });
});
