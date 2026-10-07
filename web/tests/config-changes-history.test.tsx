// F-ADM-042 (P5), F-ADM-043 (P6), F-ADM-044 (P7).
import { describe, expect, it, vi } from "vitest";
import { ChangesView } from "@/components/admin/config/changes-view";
import { HistoryView, compareVersions } from "@/components/admin/config/history-view";
import { ReachView } from "@/components/admin/config/reach-view";
import type { ConfigChange, ConfigReach, ConfigVersion, ConfigVersionDetail } from "@/lib/admin/types";
import { admin, op, setupMock } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const h = setupMock();

const change = (id: number, over: Partial<ConfigChange> = {}): ConfigChange => ({ change_id: id, status: "pending_approval", risk_class: 3, changes: [{ key: "cfg.calendar.weekend_days", scope_type: "global", scope_id: 0, value: [5, 6], old_value: [5] }], reason: "Weekend moves from this month", requested_by: 3001, requested_at: "2026-10-06T04:00:00.000Z", blast_radius: { zones: 12, routes: 340, outlets: 9000, devices: 80 }, ...over });

describe("P5 change requests", () => {
  it("shows the diff, the blast radius and the two-person warning; others' requests get approve and reject", () => {
    const m = html(<ChangesView locale="en" rows={[change(1, { requested_by: 3002 })]} status="" nextHref={null} userId={3001} canDecide role="SUPERADMIN" />);
    const x = text(m);
    expect(x).toContain("[5] → [5,6]");
    expect(x).toContain("12 zones, 340 routes, 9,000 outlets, 80 phones");
    expect(x).toContain("needs a second approver");
    expect(m).toContain('data-testid="approve-1"');
    expect(m).toContain('data-testid="reject-1"');
  });
  it("your own request can only be withdrawn", () => {
    const m = html(<ChangesView locale="en" rows={[change(2)]} status="" nextHref={null} userId={3001} canDecide role="SUPERADMIN" />);
    expect(m).not.toContain('data-testid="approve-2"');
    expect(m).toContain('data-testid="cancel-2"');
    expect(text(m)).toContain("second person must approve");
  });
  it("an ADMIN cannot approve a risk-3 change (second SUPERADMIN needed)", () => {
    expect(html(<ChangesView locale="en" rows={[change(5, { requested_by: 9 })]} status="" nextHref={null} userId={1} canDecide role="ADMIN" />)).not.toContain("approve-5");
    expect(html(<ChangesView locale="en" rows={[change(6, { requested_by: 9 })]} status="" nextHref={null} userId={1} canDecide role="SUPERADMIN" />)).toContain("approve-6");
  });
  it("no decision buttons for a read-only role or a decided change", () => {
    expect(html(<ChangesView locale="en" rows={[change(3, { requested_by: 9 })]} status="" nextHref={null} userId={1} canDecide={false} role="ADMIN" />)).not.toContain("approve-3");
    expect(html(<ChangesView locale="en" rows={[change(4, { requested_by: 9, status: "applied" })]} status="" nextHref={null} userId={1} canDecide role="ADMIN" />)).not.toContain("approve-4");
  });
  it("decision goes through the proxy with the note as reason", async () => {
    h.stub({ method: "POST", path: /^\/v1\/admin\/config\/changes\/(\d+)\/decision$/, fn: () => ({ status: 200, body: { status: "applied" } }) });
    const r = await op(await admin(), { op: "config.decide", params: { change_id: "7" }, body: { decision: "approve" }, reason: "Checked with the zone managers" });
    expect(r.status).toBe(200);
    expect(h.calls()[0]).toMatchObject({ path: "/v1/admin/config/changes/7/decision", body: { decision: "approve", note: "Checked with the zone managers" } });
    expect((await op(await admin(), { op: "config.decide", params: { change_id: "7/../x" }, body: {}, reason: "Checked with the zone managers" })).status).toBe(400);
  });
});

const v = (n: number, kind: ConfigVersion["kind"] = "change"): ConfigVersion => ({ version: n, kind, committed_at: "2026-10-06T04:00:00.000Z", committed_by: 1, summary: `v${n} summary`, max_risk_class: 1 });
const detail = (n: number, vals: Record<string, unknown>): ConfigVersionDetail => ({ config_version: n, committed_at: "2026-10-06T04:00:00.000Z", values: Object.entries(vals).map(([key, value]) => ({ key, value: value as never, scope_type: "global" as const, effective_from: null, requires_ack: false })) });

describe("P6 history and rollback", () => {
  it("lists versions with revert and roll-back actions for writers only", () => {
    const w = html(<HistoryView locale="en" versions={[v(3), v(2, "revert")]} nextHref={null} canWrite compare={null} a="" b="" keyFilter={{ key: "", scope_type: "", scope_id: "" }} values={null} />);
    expect(w).toContain('data-testid="revert-3"');
    expect(w).toContain('data-testid="rollback-2"');
    expect(text(w)).toContain("Revert");
    expect(html(<HistoryView locale="en" versions={[v(3)]} nextHref={null} canWrite={false} compare={null} a="" b="" keyFilter={{ key: "", scope_type: "", scope_id: "" }} values={null} />)).not.toContain("revert-3");
  });
  it("compares two versions, listing only differing keys", () => {
    const rows = compareVersions(detail(1, { "cfg.a": 1, "cfg.b": 2 }), detail(2, { "cfg.a": 1, "cfg.b": 3, "cfg.c": true }));
    expect(rows).toEqual([{ key: "cfg.b", a: "2", b: "3" }, { key: "cfg.c", a: "", b: "true" }]);
    expect(text(html(<HistoryView locale="en" versions={[]} nextHref={null} canWrite compare={rows} a="1" b="2" keyFilter={{ key: "", scope_type: "", scope_id: "" }} values={null} />))).toContain("cfg.b");
  });
  it("rollback creates a new version through the proxy (mode in the body, version in the path)", async () => {
    h.stub({ method: "POST", path: /^\/v1\/admin\/config\/versions\/(\d+)\/rollback$/, fn: () => ({ status: 201, body: { change_id: 9, status: "applied" } }) });
    const r = await op(await admin(), { op: "config.rollback", params: { version: "5" }, body: { mode: "revert_this_version" }, reason: "Radius change caused force sales" });
    expect(r.status).toBe(201);
    expect(h.calls()[0]).toMatchObject({ path: "/v1/admin/config/versions/5/rollback", body: { mode: "revert_this_version", reason: "Radius change caused force sales" } });
  });
});

describe("P7 reach", () => {
  const reach: ConfigReach = { version: 5, committed_at: "2026-10-06T04:00:00.000Z", devices_targeted: 10, devices_applied: 7, devices_acked: 5, devices_pending: 3, p95_reach_min: null, by_zone: [{ zone_id: 334, targeted: 4, applied: 3 }] };
  it("shows per-zone share and the pending list", () => {
    const m = text(html(<ReachView locale="en" reach={reach} pending={[{ device_id: 8, user_id: 2, zone_id: 334, applied_version: 4, lag_min: 95, last_contact_at: null }]} version={5} zone="" nextHref={null} />));
    expect(m).toContain("75%");
    expect(m).toContain("5 (50%)");
    expect(m).toContain("95");
  });
  it("says so when nothing is pending", () => {
    expect(text(html(<ReachView locale="en" reach={reach} pending={[]} version={5} zone="" nextHref={null} />))).toContain("Every targeted phone");
  });
});
