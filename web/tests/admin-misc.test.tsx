// F-ADM-057 entry unlocks, F-ADM-067 print templates, F-ADM-025 supervisory targets, F-ADM-036 dues, F-WEB-063 export log,
// F-ADM-064 permissions matrix, F-ADM-055 flags.
import { describe, expect, it, vi } from "vitest";
import { DuesView } from "@/components/admin/config/dues-view";
import { EntryUnlocksView } from "@/components/admin/config/entry-unlocks-view";
import { ExportLogView } from "@/components/admin/config/export-log-view";
import { FlagsView } from "@/components/admin/config/flags-view";
import { PermissionsView } from "@/components/admin/config/permissions-view";
import { PrintTemplatesView } from "@/components/admin/config/print-templates-view";
import { SupervisorTargetsView } from "@/components/admin/config/supervisor-targets-view";
import type { ConfigKey, DuesAdjustment } from "@/lib/admin/types";
import { admin, op, setupMock, support } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const h = setupMock();

describe("entry unlocks", () => {
  const u = (id: number, status: "active" | "expired") => ({ unlock_id: id, scope_type: "zone" as const, scope_id: 334, from: "2026-10-01", to: "2026-10-03", reason: "Phone died on those days", expires_at: "2026-10-08T04:00:00.000Z", created_by_user_id: 1, status });
  it("lists grants, expire only on active ones, create form for writers", () => {
    const m = html(<EntryUnlocksView locale="en" rows={[u(1, "active"), u(2, "expired")]} nextHref={null} canWrite />);
    expect(m).toContain("expire-1");
    expect(m).not.toContain("expire-2");
    expect(m).toContain('data-testid="unlock-form"');
    expect(html(<EntryUnlocksView locale="en" rows={[u(1, "active")]} nextHref={null} canWrite={false} />)).not.toContain("unlock-form");
  });
  it("create sends scope, dates and the reason; SUPPORT may too", async () => {
    h.stub({ method: "POST", path: "/v1/admin/entry-unlocks", fn: () => ({ status: 201, body: u(3, "active") }) });
    const body = { scope_type: "zone", scope_id: 334, from: "2026-10-01", to: "2026-10-03", ttl_h: 24 };
    expect((await op(await admin(), { op: "entry-unlock.create", body, reason: "Phone died on those days" })).status).toBe(201);
    expect(h.calls()[0]?.body).toEqual({ ...body, reason: "Phone died on those days" });
    expect((await op(await support(), { op: "entry-unlock.create", body, reason: "Phone died on those days" })).status).toBe(201); // SUPPORT may (docs/24 s8.5)
  });
});

describe("print templates", () => {
  it("lists versions per kind and offers a new version with valid JSON only", () => {
    const m = html(<PrintTemplatesView locale="en" rows={[{ kind: "cash_memo", version: 2, font_columns: 32, template_json: "{}" }, { kind: "cash_memo", version: 1, font_columns: 32, template_json: "{}" }]} canWrite />);
    expect(text(m)).toContain("Cash memo");
    expect(m).toContain('data-testid="template-form"');
  });
  it("create passes the template text as a string with change_reason", async () => {
    h.stub({ method: "POST", path: "/v1/admin/print-templates", fn: () => ({ status: 201, body: { kind: "cash_memo", version: 3, font_columns: 32, template_json: "{}" } }) });
    const body = { kind: "cash_memo", font_columns: 32, effective_from: "2026-10-10", template_json: '{"lines":[]}' };
    expect((await op(await admin(), { op: "print-template.create", body, reason: "New footer text approved" })).status).toBe(201);
    expect(h.calls()[0]?.body).toEqual({ ...body, change_reason: "New footer text approved" });
  });
});

describe("supervisory targets", () => {
  const rows = [{ user_id: 7, month: "2026-10", total_call_target: 40, control_call_target: 10, joint_call_target: 20, daily_call_target: null }];
  it("asks for a zone first, then shows an editable row per officer", () => {
    expect(text(html(<SupervisorTargetsView locale="en" month="2026-10" zone="" rows={null} canWrite />))).toContain("Enter a zone id");
    const m = html(<SupervisorTargetsView locale="en" month="2026-10" zone="334" rows={rows} canWrite />);
    expect(m).toContain('data-testid="target-7"');
    expect(m).toContain('value="40"');
    expect(html(<SupervisorTargetsView locale="en" month="2026-10" zone="334" rows={rows} canWrite={false} />)).toContain("disabled");
  });
  it("PUT carries the batch uuid and rows with change_reason", async () => {
    h.stub({ method: "PUT", path: "/v1/admin/supervisor-targets", fn: () => ({ status: 200, body: { batch_uuid: "x", inserted: 0, updated: 1, unchanged: 0 } }) });
    const body = { batch_uuid: "77777777-7777-4777-8777-777777777777", rows };
    expect((await op(await admin(), { op: "supervisor-targets.put", body, reason: "October plan from the sales head" })).status).toBe(200);
    expect(h.calls()[0]?.body).toEqual({ ...body, change_reason: "October plan from the sales head" });
  });
});

describe("dues adjustments (maker-checker)", () => {
  const a = (id: number, by: number, status: DuesAdjustment["status"] = "pending"): DuesAdjustment => ({ adjustment_id: id, outlet_id: 55, kind: "correction", amount_mtk: -500_000, reason: "Disputed due after a return", status, created_by_user_id: by, created_at: "2026-10-06T04:00:00.000Z" });
  const base = { locale: "en" as const, status: "", outlet: "", nextHref: null, userId: 1, canCreate: true, canDecide: true };
  it("shows the signed amount in taka; a checker decides others' proposals, not their own", () => {
    const m = html(<DuesView {...base} rows={[a(1, 2), a(2, 1), a(3, 2, "approved")]} />);
    expect(text(m)).toContain("-500.00");
    expect(m).toContain("dues-approve-1");
    expect(m).not.toContain("dues-approve-2");
    expect(text(m)).toContain("Your own proposal");
    expect(m).not.toContain("dues-approve-3");
  });
  it("an ADMIN maker cannot decide; create sends integer milli-taka and a client uuid", async () => {
    h.stub({ method: "POST", path: "/v1/admin/dues-adjustments", fn: () => ({ status: 201, body: a(9, 1) }) });
    h.stub({ method: "POST", path: "/v1/admin/dues-adjustments/9/decision", fn: () => ({ status: 200, body: a(9, 1, "approved") }) });
    const body = { client_uuid: "88888888-8888-4888-8888-888888888888", outlet_id: 55, kind: "correction", amount_mtk: -500000 };
    expect((await op(await admin(), { op: "dues.create", body, reason: "Disputed due after a return" })).status).toBe(201);
    expect(h.calls()[0]?.body).toMatchObject({ amount_mtk: -500000, reason: "Disputed due after a return" });
    expect((await op(await admin(), { op: "dues.decide", params: { adjustment_id: "9" }, body: { decision: "approve" }, reason: "Checked with finance" })).status).toBe(403);
  });
});

describe("export log", () => {
  it("shows who, which report, filters, rows and the PII flag", () => {
    const m = text(html(<ExportLogView locale="en" rows={[{ export_id: "e1", report_key: "std-memo", user_id: 4, username: "wm1", format: "xlsx", rows: 120, pii_included: true, filters: { zone: 334 }, created_at: "2026-10-06T04:00:00.000Z" }]} filters={{ user_id: "", report_key: "", from: "", to: "" }} nextHref={null} />));
    for (const x of ["wm1", "std-memo", "xlsx", "120", "Yes", "zone=334"]) expect(m).toContain(x);
  });
});

describe("permission matrix", () => {
  const matrix = { config_version: 5, roles: [{ role: "TSO" as const, menus: [{ menu_id: "dashboard", actions: ["view" as const] }, { menu_id: "web_entry", actions: ["view" as const, "create" as const] }] }, { role: "WM" as const, menus: [{ menu_id: "dashboard", actions: ["view" as const] }] }], admin_roster: [{ user_id: 1, username: "admin1", role: "ADMIN" as const, mfa_enabled: true }] };
  it("shows the chosen role's grid with its checked actions, and the roster", () => {
    const m = html(<PermissionsView locale="en" matrix={matrix} role="TSO" canWrite />);
    expect(m).toMatch(/aria-label="web_entry create"[^>]*checked/);
    expect(m).not.toMatch(/aria-label="dashboard create"[^>]*checked/);
    expect(text(m)).toContain("admin1");
    expect(text(html(<PermissionsView locale="en" matrix={matrix} role="" canWrite />))).toContain("Choose a role");
  });
  it("read-only roles get disabled boxes and no request button", () => {
    const m = html(<PermissionsView locale="en" matrix={matrix} role="TSO" canWrite={false} />);
    expect(m).toContain("disabled");
    expect(text(m)).not.toContain("Request this change");
  });
  it("put goes through the proxy for SUPERADMIN only", async () => {
    h.stub({ method: "PUT", path: "/v1/admin/permissions/roles/TSO", fn: () => ({ status: 202, body: { change_id: 1, status: "pending_approval" } }) });
    const body = { menus: [{ menu_id: "dashboard", actions: ["view"] }] };
    expect((await op(await admin(), { op: "permissions.put", params: { role: "TSO" }, body, reason: "TSO needs the new menu" })).status).toBe(403);
  });
});

describe("feature flags", () => {
  const flag = (k: string, def: boolean): ConfigKey => ({ key: k, area: "flag", kind: "S", value_type: "bool", default_value: def, bounds: {}, scope_levels: ["global", "role", "zone"], risk_class: 2, effect: "B", delivery: "both", requires_ack: false, future_dated_only: false, editor_permission: "cfg.edit.flag", description_en: "Print on" });
  it("shows the matrix of flags with default and scoped values, and a form per flag for writers", () => {
    const flags = [{ key: flag("cfg.flag.print_enabled", true), values: [{ id: 1, key: "cfg.flag.print_enabled", scope_type: "zone" as const, scope_id: 334, value: false, effective_from: "2026-10-01T00:00:00.000Z", effective_to: null, config_version: 4, set_by: 1, set_at: "2026-10-01T00:00:00.000Z", reason: "Pilot zone without a printer" }] }];
    const m = html(<FlagsView locale="en" flags={flags} canWrite />);
    expect(m).toContain('data-testid="flag-row-cfg.flag.print_enabled"');
    expect(text(m)).toContain("Zone #334");
    expect(text(m)).toContain("Off");
    expect(m).toContain('data-key="cfg.flag.print_enabled"');
    expect(html(<FlagsView locale="en" flags={flags} canWrite={false} />)).not.toContain("data-key=");
  });
});
