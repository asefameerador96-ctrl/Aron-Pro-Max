// F-ADM-013 / 038 / 040 / 041 / 044: console, home, switches, reach widget.
import { describe, expect, it, vi } from "vitest";
import { ConfigConsoleView } from "@/components/admin/config/config-console-view";
import { ConfigHomeView } from "@/components/admin/config/config-home-view";
import { ReachWidget } from "@/components/admin/config/reach-widget";
import { parseConfigInput } from "@/lib/admin/config";
import { pendingCount, sharePct } from "@/lib/admin/config-load";
import type { ConfigKey, ConfigReach, ResolvedConfigValue } from "@/lib/admin/types";
import { admin, op, setupMock, token } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const h = setupMock();

const key = (k: string, over: Partial<ConfigKey> = {}): ConfigKey => ({ key: k, area: "geo", kind: "S", value_type: "int", default_value: 50, bounds: { min: 10, max: 5000 }, scope_levels: ["global", "zone"], risk_class: 2, effect: "B", delivery: "both", requires_ack: false, future_dated_only: false, editor_permission: "cfg.edit.geo", description_en: "Radius in metres", description_bn: "মিটারে ব্যাসার্ধ", ...over });
const reach: ConfigReach = { version: 318, committed_at: "2026-10-06T04:00:00.000Z", devices_targeted: 8, devices_applied: 6, devices_acked: 4, devices_pending: 2, p95_reach_min: 12 };
const cur: Record<string, ResolvedConfigValue> = { "cfg.geo.radius_m": { key: "cfg.geo.radius_m", value: 100, scope_type: "global", effective_from: null, requires_ack: false } };
const props = { locale: "en" as const, basePath: "/admin/config/keys", titleKey: "cfgk.title" as const, keys: [key("cfg.geo.radius_m"), key("cfg.sys.kill", { kind: "O", value_type: "bool", default_value: false, bounds: {}, area: "sys", risk_class: 3 })], areas: ["geo", "sys"], area: undefined, values: cur, reach, canWrite: true };

describe("console", () => {
  it("lists keys with risk class, current value and provenance, plus the reach widget", () => {
    const m = text(html(<ConfigConsoleView {...props} />));
    expect(m).toContain("cfg.geo.radius_m");
    expect(m).toContain("Risk 2 (delayed)");
    expect(m).toContain("Risk 3 (two approvers)");
    expect(m).toContain("100");
    expect(m).toContain("set for all of Aron");
    expect(m).toContain("registry default"); // key without a stored value
    expect(m).toContain("6 (75%)");
  });
  it("each editable key has a typed form with a mandatory reason; read-only roles get none", () => {
    expect(html(<ConfigConsoleView {...props} />)).toContain('data-key="cfg.geo.radius_m"');
    expect(html(<ConfigConsoleView {...props} />)).toContain('name="reason"');
    const ro = html(<ConfigConsoleView {...props} canWrite={false} />);
    expect(ro).not.toContain("data-key=");
  });
  it("switches ask for a duration", () => {
    expect(text(html(<ConfigConsoleView {...props} switches />))).toContain("Duration (hours)");
    expect(text(html(<ConfigConsoleView {...props} />))).not.toContain("Duration (hours)");
  });
  it("shows Bangla descriptions in bn", () => {
    expect(text(html(<ConfigConsoleView {...props} locale="bn" />))).toContain("মিটারে ব্যাসার্ধ");
  });
});

describe("typed validation", () => {
  it("int within bounds, out of bounds, not a number", () => {
    const b = { min: 10, max: 5000 };
    expect(parseConfigInput("int", "150", b)).toEqual({ ok: true, value: 150 });
    expect(parseConfigInput("int", "5", b)).toEqual({ ok: false, code: "too_small" });
    expect(parseConfigInput("int", "9000", b)).toEqual({ ok: false, code: "too_big" });
    expect(parseConfigInput("int", "1.5", b)).toEqual({ ok: false, code: "invalid" });
    expect(parseConfigInput("int", "", b)).toEqual({ ok: false, code: "required" });
  });
  it("time, bool, enum, pct, list", () => {
    expect(parseConfigInput("time", "17:30")).toEqual({ ok: true, value: "17:30" });
    expect(parseConfigInput("time", "25:00").ok).toBe(false);
    expect(parseConfigInput("bool", "true")).toEqual({ ok: true, value: true });
    expect(parseConfigInput("enum", "x", { enum: ["a", "b"] })).toEqual({ ok: false, code: "not_allowed" });
    expect(parseConfigInput("pct", "101").ok).toBe(false);
    expect(parseConfigInput("list", "5, 6")).toEqual({ ok: true, value: [5, 6] });
  });
});

describe("home", () => {
  it("shows the version and pending count exactly as served, and recent changes", () => {
    const m = text(html(<ConfigHomeView locale="en" version={318} pending={{ count: 3, more: false }} recent={[]} reach={reach} />));
    expect(m).toContain("318");
    expect(html(<ConfigHomeView locale="en" version={318} pending={{ count: 3, more: false }} recent={[]} reach={reach} />)).toContain('data-testid="pending-count">3<');
    expect(text(html(<ConfigHomeView locale="en" version={318} pending={{ count: 100, more: true }} recent={[]} reach={null} />))).toContain("100+");
  });
  it("pendingCount equals the number of pending changes the service lists", async () => {
    h.stub({ method: "GET", path: "/v1/admin/config/changes", fn: (c) => ({ status: 200, body: { items: c.query.status === "pending_approval" ? [{}, {}] : [], next_cursor: null } }) });
    const r = await pendingCount(token(await admin()));
    expect(r.ok && r.data).toEqual({ count: 2, more: false });
  });
});

describe("reach widget", () => {
  it("acknowledged share equals acknowledged / targeted", () => {
    expect(sharePct(4, 8)).toBe(50);
    expect(sharePct(1, 3)).toBe(33.3);
    expect(sharePct(0, 0)).toBeNull();
    expect(html(<ReachWidget locale="en" reach={reach} />)).toContain("4 (50%)");
    expect(text(html(<ReachWidget locale="en" reach={null} />))).toContain("No devices");
  });
});

describe("config.change through the proxy", () => {
  it("sends the change with the reason and returns its status", async () => {
    h.stub({ method: "POST", path: "/v1/admin/config/changes", fn: () => ({ status: 201, body: { status: "pending_approval", change_id: 7 } }) });
    const r = await op(await admin(), { op: "config.change", body: { changes: [{ key: "cfg.geo.radius_m", scope_type: "global", scope_id: 0, value: 120 }] }, reason: "Dense market needs a wider radius" });
    expect(r.status).toBe(201);
    expect((await r.json()).data.status).toBe("pending_approval");
    expect(h.calls()[0]?.body).toMatchObject({ reason: "Dense market needs a wider radius", changes: [{ value: 120 }] });
  });
});
