// CHECKER (independent, T2) for F-ADM-057, F-ADM-067, F-ADM-025, F-WEB-063, F-ADM-064, F-ADM-055.
import type * as PageMod from "@/components/admin/kit/page";
import type { ReactElement } from "react";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { renderToStaticMarkup } from "react-dom/server";
import { beforeEach, describe, expect, it, vi } from "vitest";

const hooks = vi.hoisted(() => ({ cells: [] as unknown[], i: 0, role: "SUPPORT" as string }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }), notFound() { throw new Error("notFound"); } }));
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => ({ at: "tok", user: { role: hooks.role } }) }));
vi.mock("@/lib/auth/service", () => ({ getLocale: async () => "en" }));
const api = vi.hoisted(() => ({ apiGet: vi.fn(), listKeys: vi.fn() }));
vi.mock("@/components/admin/kit/page", async (orig) => ({ ...(await orig<typeof PageMod>()), apiGet: api.apiGet }));
vi.mock("@/lib/admin/config-load", async (orig) => ({ ...(await orig<Record<string, unknown>>()), listKeys: api.listKeys }));

import PermissionsPage from "@/app/admin/permissions/page";
import PrintTemplatesPage from "@/app/admin/print-templates/page";
import SupervisorTargetsPage from "@/app/admin/supervisor-targets/page";
import FlagsPage from "@/app/admin/config/flags/page";
import ExportLogPage from "@/app/admin/export-log/page";
import { FlagsView } from "@/components/admin/config/flags-view";
import { PermissionsView } from "@/components/admin/config/permissions-view";
import { PrintTemplatesView } from "@/components/admin/config/print-templates-view";
import { SupervisorTargetsView } from "@/components/admin/config/supervisor-targets-view";
import { I18nProvider } from "@/components/i18n-provider";
import { canOp } from "@/lib/admin/access";

const ok = <T,>(data: T) => ({ ok: true as const, status: 200, data });
const render = async (p: Promise<ReactElement> | ReactElement) => renderToStaticMarkup(await p);
const under = (el: ReactElement) => renderToStaticMarkup(<I18nProvider locale="en">{el}</I18nProvider>);
beforeEach(() => { vi.resetAllMocks(); hooks.role = "SUPPORT"; });

const matrix = { config_version: 5, roles: [{ role: "TSO", menus: [{ menu_id: "dashboard", actions: ["view"] }] }], admin_roster: [{ user_id: 1, username: "admin1", role: "ADMIN", mfa_enabled: true }] };

describe("role gates against docs/24 s8.5", () => {
  it("F-ADM-057: SUPPORT may create and expire entry unlocks (s8.5 row 'entry unlocks' W; cfg.web.entry_unlock_roles default [SUPPORT, ADMIN])", () => {
    expect(canOp("entry-unlock.create", "SUPPORT")).toBe(true);
    expect(canOp("entry-unlock.expire", "SUPPORT")).toBe(true);
  });
  it("F-ADM-064: SUPPORT has no access to the permission matrix (s8.5: SUPPORT '-', ADMIN R, SUPERADMIN W)", async () => {
    api.apiGet.mockResolvedValue(ok(matrix));
    const h = await render(PermissionsPage({ searchParams: Promise.resolve({}) }) as Promise<ReactElement>);
    expect(h).not.toContain("admin1");
  });
  it("F-ADM-067: SUPPORT has no access to print templates (s8.5: only ADMIN and SUPERADMIN)", async () => {
    api.apiGet.mockResolvedValue(ok({ items: [{ kind: "cash_memo", version: 1, font_columns: 32, template_json: "{}" }] }));
    const h = await render(PrintTemplatesPage() as Promise<ReactElement>);
    expect(h).not.toContain("Cash memo");
  });
  it("F-ADM-025: SUPPORT has no access to supervisor targets (s8.5: SUPPORT '-')", async () => {
    api.apiGet.mockResolvedValue(ok({ items: [{ user_id: 7, month: "2026-10", total_call_target: 4, control_call_target: 1, joint_call_target: 1, daily_call_target: null }] }));
    const h = await render(SupervisorTargetsPage({ searchParams: Promise.resolve({ zone_id: "334", month: "2026-10" }) }) as Promise<ReactElement>);
    expect(h).not.toContain("target-7");
  });
});

describe("F-ADM-025 supervisor target editor", () => {
  it("a month or zone with no stored rows still offers a way to add an AMO (otherwise a new month can never be filled)", () => {
    const m = under(<SupervisorTargetsView locale="en" month="2026-11" zone="334" rows={[]} canWrite />);
    // some control other than a reason box that takes a user id
    expect(m).toMatch(/aria-label="[^"]*(user|officer|AMO|add)[^"]*"|name="user_id"|data-testid="add-/i);
  });
});

describe("F-ADM-067 print templates", () => {
  it("effective_from is future-dated per contract: the date input refuses past dates (min attribute)", () => {
    const m = under(<PrintTemplatesView locale="en" rows={[]} canWrite />);
    const input = /<input[^>]*name="effective_from"[^>]*>/.exec(m)?.[0] ?? "";
    expect(input).toMatch(/min="/);
  });
});

describe("F-ADM-064 permission editor", () => {
  it("a menu that no role holds yet can be added (menu ids are not only the union of existing grants)", () => {
    const empty = { ...matrix, roles: [{ role: "TSO" as const, menus: [] }, { role: "WM" as const, menus: [] }] };
    const m = under(<PermissionsView locale="en" matrix={empty as never} role="TSO" canWrite />);
    const nonCheckbox = (m.match(/<input[^>]*>/g) ?? []).filter((x) => !/type="checkbox"/.test(x));
    expect(nonCheckbox.length).toBeGreaterThan(0);
  });
  it("the web shell menu is driven by the matrix (cfg.web.menu_by_role), not only by static code", () => {
    const walk = (d: string): string[] => readdirSync(d).flatMap((f) => (statSync(join(d, f)).isDirectory() ? walk(join(d, f)) : [join(d, f)]));
    const files = [...walk("src/components"), ...walk("src/lib/menu")].filter((f) => /\.tsx?$/.test(f) && !/admin\/config\/permission/.test(f));
    const readers = files.filter((f) => /menu_by_role|\/v1\/admin\/permissions|\/permissions"/.test(readFileSync(f, "utf8")));
    expect(readers).not.toEqual([]);
  });
});

describe("F-ADM-055 flags", () => {
  const key = (o: Record<string, unknown> = {}) => ({ key: "cfg.flag.print_enabled", area: "flag", kind: "S", value_type: "bool", default_value: true, bounds: {}, scope_levels: ["global", "zone"], risk_class: 2, effect: "B", delivery: "both", requires_ack: false, future_dated_only: false, editor_permission: "cfg.edit.flag", description_en: "Print on", ...o });
  const val = (o: Record<string, unknown> = {}) => ({ id: 1, key: "cfg.flag.print_enabled", scope_type: "zone", scope_id: 334, value: false, effective_from: "2026-10-01T00:00:00.000Z", effective_to: null, config_version: 4, set_by: 1, set_at: "2026-10-01T00:00:00.000Z", reason: "Pilot zone without a printer", ...o });
  it("a time-boxed override (effective_to in the future) is still active and must be listed", async () => {
    hooks.role = "ADMIN";
    api.listKeys.mockResolvedValue(ok({ items: [key()] }));
    api.apiGet.mockResolvedValue(ok({ items: [val({ effective_to: "2099-01-01T00:00:00.000Z" })], next_cursor: null }));
    const h = await render(FlagsPage() as Promise<ReactElement>);
    expect(h).toContain("#334");
  });
  it("a flag whose key is future_dated_only offers the effective-from date (waves cannot be scheduled otherwise)", () => {
    const m = under(<FlagsView locale="en" flags={[{ key: key({ future_dated_only: true }) as never, values: [] }]} canWrite />);
    expect(m).toMatch(/type="date"/);
  });
  it("a value that has not started yet is marked as scheduled, not shown like a live override", () => {
    const m = under(<FlagsView locale="en" flags={[{ key: key() as never, values: [val({ effective_from: "2099-01-01T00:00:00.000Z" }) as never] }]} canWrite={false} />);
    expect(m.replace(/<[^>]+>/g, " ")).toMatch(/schedul|pending|not yet|future/i);
  });
});

describe("F-WEB-063 export log page", () => {
  it("a mistyped ?report_key= (not a registry key) must give an empty list, not an error page", async () => {
    hooks.role = "ADMIN";
    api.apiGet.mockImplementation(async (_p: string, _t: string, q: Record<string, unknown>) => (q.report_key ? { ok: false, status: 400, problem: { code: "ERR_VALIDATION", status: 400, title: "x" } } : ok({ items: [], next_cursor: null })));
    const h = await render(ExportLogPage({ searchParams: Promise.resolve({ report_key: "no-such-report" }) }) as Promise<ReactElement>);
    expect(h).toMatch(/xl-|Report export log|No /i);
    expect(h).not.toMatch(/ERR_VALIDATION|went wrong|Bad request/i);
  });
});
