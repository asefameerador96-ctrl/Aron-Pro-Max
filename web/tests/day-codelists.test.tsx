// F-ADM-029 / F-ADM-052 day control, F-ADM-023 / F-ADM-060 code lists, the team-op proxy.
import { describe, expect, it, vi } from "vitest";
import { POST as teamPost } from "@/app/api/bff/team-op/route";
import { CodeListEditor } from "@/components/admin/config/code-list-editor";
import { CodeListsView } from "@/components/admin/config/code-lists-view";
import { DayControlView } from "@/components/admin/config/day-control-view";
import { validateItems } from "@/lib/admin/code-lists";
import { lateRows, missingCheckoutRows } from "@/lib/admin/reports";
import type { CodeItem, ReportResult } from "@/lib/admin/types";
import { admin, op, req, setupMock, support, tso } from "./helpers/harness";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));
const h = setupMock();

const col = (key: string, type: ReportResult["columns"][number]["type"] = "string") => ({ key, label_en: key, type });
const rep = (columns: ReportResult["columns"], rows: ReportResult["rows"]): ReportResult => ({ report_key: "final-submit-log", as_of: "2026-10-06T04:00:00.000Z", columns, rows, page: 1, page_size: 100, total_rows: rows.length });

describe("day control lists", () => {
  it("late rows are the ones with a late column set; unknown when the report has none", () => {
    const r = rep([col("route"), col("late_rows", "integer")], [{ route: "R1", late_rows: 0 }, { route: "R2", late_rows: 4 }]);
    expect(lateRows(r)).toEqual({ rows: [{ route: "R2", late_rows: 4 }], known: true });
    expect(lateRows(rep([col("route")], [{ route: "R1" }])).known).toBe(false);
  });
  it("missing check-outs: check-in present, check-out empty", () => {
    const r = rep([col("user"), col("check_in_at", "timestamp"), col("check_out_at", "timestamp")], [{ user: "a", check_in_at: "x", check_out_at: null }, { user: "b", check_in_at: "x", check_out_at: "y" }, { user: "c", check_in_at: null, check_out_at: null }]);
    expect(missingCheckoutRows(r).rows.map((x) => x.user)).toEqual(["a"]);
  });
  it("the view shows the reopen form to admins only and the seeded late batch", () => {
    const late = { rows: [{ route: "R2", late_rows: 4 }], columns: [col("route"), col("late_rows", "integer")], known: true };
    const base = { locale: "en" as const, zone: "334", date: "2026-10-06", late, missing: null, withMissing: false, basePath: "/admin/day" };
    const m = html(<DayControlView {...base} canWrite />);
    expect(m).toContain('data-testid="reopen-form"');
    expect(text(m)).toContain("R2");
    expect(html(<DayControlView {...base} canWrite={false} />)).not.toContain("reopen-form");
    const p15 = text(html(<DayControlView {...base} withMissing missing={{ rows: [], columns: [], known: true }} canWrite />));
    expect(p15).toContain("Missing check-outs");
    expect(p15).toContain("Every check-in has a check-out");
    expect(text(html(<DayControlView {...base} late={{ ...late, known: false }} canWrite />))).toContain("cannot be built");
  });
  it("reopen goes through the proxy with zone, date and reason; support cannot", async () => {
    h.stub({ method: "POST", path: "/v1/day/reopen", fn: () => ({ status: 200, body: { zone_id: 334, business_date: "2026-10-06", reopened_at: "2026-10-07T04:00:00.000Z", reopened_by: 1 } }) });
    const body = { zone_id: 334, business_date: "2026-10-06" };
    expect((await op(await admin(), { op: "day.reopen", body, reason: "Wrong memo totals found after final" })).status).toBe(200);
    expect(h.calls()[0]?.body).toEqual({ ...body, reason: "Wrong memo totals found after final" });
    expect((await op(await support(), { op: "day.reopen", body, reason: "Wrong memo totals found after final" })).status).toBe(403);
  });
});

const item = (code: string, over: Partial<CodeItem> = {}): CodeItem => ({ code, label_en: code, label_bn: null, sort: 10, valid_from: "2026-01-01", valid_to: null, attrs: { group: "MFC", applies_to: "app" }, ...over });
const attrs = [{ name: "group", options: ["MFC", "MKT"] }, { name: "applies_to", options: ["app", "web"] }];

describe("code lists", () => {
  it("validates codes, duplicates, labels and the qc attributes", () => {
    expect(validateItems([item("burst_pack")], attrs)).toEqual([]);
    expect(validateItems([item("Bad Code")], attrs)[0]).toMatchObject({ field: "code", code: "invalid" });
    expect(validateItems([item("a_b"), item("a_b")], attrs).some((e) => e.code === "duplicate")).toBe(true);
    expect(validateItems([item("a_b", { label_en: " " })], attrs)[0]).toMatchObject({ field: "label_en" });
    expect(validateItems([item("a_b", { attrs: { group: "X", applies_to: "app" } })], attrs)[0]).toMatchObject({ field: "group" });
  });
  it("QC fault page: eleven codes with group and applies_to selectors, retire instead of delete", () => {
    const eleven = Array.from({ length: 11 }, (_, i) => item(`fault_${i + 1}`, { attrs: { group: i < 5 ? "MFC" : "MKT", applies_to: i % 2 ? "web" : "app" } }));
    const m = html(<CodeListsView locale="en" lists={[{ list_key: "qc_fault_type", items: eleven }]} selected="qc_fault_type" choices={null} basePath="/admin/qc-faults" today="2026-10-07" canWrite titleKey="qc.title" />);
    expect((m.match(/data-testid="row-fault_/g) ?? []).length).toBe(11);
    expect(m).toContain("Manufacturing (MFC)");
    expect(m).toContain("Marketing (MKT)");
    expect(m).not.toMatch(/Delete|Remove/);
    expect(text(m)).toContain("Retire");
    expect(m).toContain('name="reason"');
  });
  it("a read-only role cannot edit labels", () => {
    const m = html(<CodeListEditor listKey="force_reason" initial={[item("far_outlet", { attrs: undefined })]} attrs={[]} today="2026-10-07" canWrite={false} />);
    expect(m).toContain("disabled");
    expect(m).not.toContain('name="reason"');
  });
  it("the reason tables page offers every reason list and shows Bangla labels in bn", () => {
    const m = text(html(<CodeListsView locale="bn" lists={[{ list_key: "force_reason", items: [item("far_outlet", { label_bn: "দূরের আউটলেট", attrs: undefined })] }]} selected="force_reason" choices={["force_reason", "edit_reason"]} basePath="/admin/code-lists" today="2026-10-07" canWrite titleKey="cl.title" />));
    expect(m).toContain("জোর করে বিক্রির কারণ");
  });
  it("save is one PUT of the list with the reason as change_reason; SUPPORT cannot", async () => {
    h.stub({ method: "PUT", path: "/v1/admin/code-lists/qc_fault_type", fn: () => ({ status: 200, body: { list_key: "qc_fault_type", items: [] } }) });
    const body = { items: [{ code: "burst_pack", label_en: "Burst pack", sort: 10, valid_from: "2026-10-07", valid_to: null }] };
    expect((await op(await admin(), { op: "code-list.put", params: { list_key: "qc_fault_type" }, body, reason: "New fault seen in the market" })).status).toBe(200);
    expect(h.calls()[0]?.body).toEqual({ ...body, change_reason: "New fault seen in the market" });
    expect((await op(await support(), { op: "code-list.put", params: { list_key: "qc_fault_type" }, body, reason: "New fault seen in the market" })).status).toBe(403);
  });
});

describe("team-op proxy", () => {
  const post = async (cookies: Record<string, string>, body: unknown) => teamPost(req("/api/bff/team-op", "POST", body, cookies));
  it("a TSO can final-submit (client uuid forwarded) but cannot use the admin ops", async () => {
    h.stub({ method: "POST", path: "/v1/day/final-submit", fn: () => ({ status: 200, body: { zone_id: 334 } }) });
    const body = { zone_id: 334, business_date: "2026-10-06", client_uuid: "44444444-4444-4444-8444-444444444444" };
    expect((await post(await tso(), { op: "day.final-submit", body })).status).toBe(200);
    expect(h.calls()[0]?.body).toEqual(body);
    expect((await post(await tso(), { op: "day.reopen", body })).status).toBe(400); // not in the team whitelist
    expect((await op(await tso(), { op: "day.reopen", body, reason: "Trying the admin route as TSO" })).status).toBe(403);
  });
  it("delete section data needs a reason and is refused for roles outside the list", async () => {
    h.stub({ method: "POST", path: "/v1/admin/data-void", fn: () => ({ status: 200, body: {} }) });
    const body = { client_uuid: "55555555-5555-4555-8555-555555555555", route_id: 9, business_date: "2026-10-06", scope: "web_entry" };
    expect((await post(await tso(), { op: "day.data-void", body })).status).toBe(400);
    expect((await post(await tso(), { op: "day.data-void", body, reason: "Wrong route entered by mistake" })).status).toBe(200);
    expect(h.calls()[0]?.body).toEqual({ ...body, reason: "Wrong route entered by mistake" });
  });
});
