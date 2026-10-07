// CHECKER (F-ADM-029, F-WEB-051, F-ADM-052 read-only): attempts to refute day control and web Final Submit.
// Every test here failed against the code at the time of the check; each names the spec line it holds the code to.
import { describe, expect, it, vi } from "vitest";
import { POST as teamPost } from "@/app/api/bff/team-op/route";
import { DayControlPageContent } from "@/components/admin/config/day-control-page";
import { FinalSubmitView, type FinalSubmitViewProps } from "@/components/admin/config/final-submit-view";
import { formatMtk } from "@/lib/admin/money";
import { lateRows } from "@/lib/admin/reports";
import type { FinalSubmitPreview, ReportResult } from "@/lib/admin/types";
import { admin, req, setupMock, token, tso } from "./helpers/harness";
import { html, text } from "./helpers/render";

const cap = vi.hoisted(() => ({ inline: [] as Record<string, unknown>[], form: [] as Record<string, unknown>[] }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }), redirect: () => { throw new Error("redirect"); } }));
vi.mock("@/components/admin/kit/op-inline", () => ({ OpInline: (p: Record<string, unknown>) => { cap.inline.push(p); return null; } }));
vi.mock("@/components/admin/kit/op-form", () => ({ OpForm: (p: Record<string, unknown>) => { cap.form.push(p); return <form data-testid={String(p.testId)} />; } }));
const session = { current: null as null | { user: { role: string }; at: string } };
vi.mock("@/lib/auth/require", () => ({ requireSession: async () => session.current }));
vi.mock("@/lib/auth/service", async (orig) => ({ ...(await orig<Record<string, unknown>>()), getLocale: async () => "en" }));
const h = setupMock();

const route = (id: number, over: Partial<FinalSubmitPreview["routes"][number]> = {}) => ({ route_id: id, route_code: `R-${id}`, route_name: `Route ${id}`, kind: "sr" as const, state: "sales_submitted" as const, had_data: true, memo_count: 3, net_mtk: 10_000, ff_user_id: 5, ff_user_name: "Rahim", pending_rows_reported: 0, ...over });
const preview = (over: Partial<FinalSubmitPreview> = {}): FinalSubmitPreview => ({ zone_id: 334, business_date: "2026-10-06", already_submitted: false, routes: [route(1)], warnings: [], submitted_at: null, submitted_by: null, ...over });
const base: FinalSubmitViewProps = { locale: "en", options: { wing: [], division: [], territory: [], house: [], zone: [] }, selection: { zone: "334" }, date: "2026-10-06", today: "2026-10-06", preview: preview(), canSubmit: true, canVoid: true };
const col = (key: string, type: ReportResult["columns"][number]["type"] = "string") => ({ key, label_en: key, type });
const rep = (columns: ReportResult["columns"], rows: ReportResult["rows"], total = rows.length): ReportResult => ({ report_key: "final-submit-log", as_of: "2026-10-06T04:00:00.000Z", columns, rows, page: 1, page_size: 100, total_rows: total });

describe("F-WEB-051 Delete Section Data scope (D-438, docs/19 cfg.web.delete_section_data_scope = web_entry_only)", () => {
  it("the page voids web-entry rows by default, not every app memo of the route-day", () => {
    cap.inline.length = 0;
    html(<FinalSubmitView {...base} />);
    const body = cap.inline[0]?.body as Record<string, unknown> | undefined;
    expect(body?.scope).toBe("web_entry");
  });
  it("the team-op proxy refuses a TSO voiding app memos (scope all / app_memos needs ops_admin and two persons)", async () => {
    h.stub({ method: "POST", path: "/v1/admin/data-void", fn: () => ({ status: 200, body: {} }) });
    const body = { client_uuid: "66666666-6666-4666-8666-666666666666", route_id: 9, business_date: "2026-10-06", scope: "all" };
    const r = await teamPost(req("/api/bff/team-op", "POST", { op: "day.data-void", body, reason: "Wrong route entered by mistake" }, await tso()));
    expect(r.status).toBe(403);
    expect(h.calls().length).toBe(0);
  });
});

describe("F-WEB-051 DSS advisory and back-date banner (D-184: the page carries the app's messages and the DSS advisory)", () => {
  it("the DSS advisory is shown before Final Submit even when every route is submitted and there are no warnings", () => {
    expect(html(<FinalSubmitView {...base} />)).toContain('data-testid="dss-advisory"');
  });
  it("the back-date banner carries the cut-off and 'call support' text (docs/15 F-WEB-051)", () => {
    const m = text(html(<FinalSubmitView {...base} date="2026-10-04" />));
    expect(m.toLowerCase()).toContain("support");
  });
});

describe("F-WEB-051 Final Submit confirm (docs/19 cfg.day.final_submit_confirm = true, D-198)", () => {
  it("the irreversible Final Submit asks for an explicit confirm before posting", () => {
    cap.form.length = 0;
    html(<FinalSubmitView {...base} />);
    const p = cap.form.find((f) => f.op === "day.final-submit");
    expect(p).toBeDefined();
    expect(Object.keys(p!).some((k) => /confirm/i.test(k))).toBe(true);
  });
});

describe("money (integer milli-taka)", () => {
  it("a small negative amount that rounds to zero is not shown as -0.00", () => {
    expect(formatMtk("en", -4)).toBe("0.00");
  });
});

describe("F-ADM-029 late rows after the final", () => {
  it("the spec's own flag name (after_final_submit, docs/15 and docs/19 P15) is recognised", () => {
    const r = rep([col("route"), col("after_final_submit", "bool")], [{ route: "R1", after_final_submit: false }, { route: "R2", after_final_submit: true }]);
    expect(lateRows(r)).toEqual({ rows: [{ route: "R2", after_final_submit: true }], known: true });
  });
  it("a timestamp column such as latest_sync_at does not mark every row late", () => {
    const r = rep([col("route"), col("late_rows", "integer"), col("latest_sync_at", "timestamp")], [{ route: "R1", late_rows: 0, latest_sync_at: "2026-10-06T10:00:00Z" }]);
    expect(lateRows(r).rows).toEqual([]);
  });
});

describe("F-ADM-052 P15 page", () => {
  const sp = (o: Record<string, string>) => Promise.resolve(o);
  it("a failed attendance report is not silently hidden on P15 (the missing check-out list must say it could not load)", async () => {
    session.current = { user: { role: "ADMIN" }, at: token(await admin()) };
    h.stub({ method: "POST", path: "/v1/reports/final-submit-log/query", fn: () => ({ status: 200, body: rep([col("route"), col("late_rows", "integer")], []) }) });
    h.stub({ method: "POST", path: "/v1/reports/attendance/query", fn: () => ({ status: 503, body: { type: "about:blank", title: "x", status: 503, code: "ERR_UNAVAILABLE" } }) });
    const m = text(html(await DayControlPageContent({ searchParams: sp({ zone: "334", date: "2026-10-06" }), basePath: "/admin/config/day", withMissing: true })));
    expect(m).toContain("Missing check-outs");
  });
  it("late list does not claim 'Nothing arrived after the final' when only page 1 of a larger log was read", async () => {
    session.current = { user: { role: "ADMIN" }, at: token(await admin()) };
    const rows = Array.from({ length: 100 }, (_, i) => ({ route: `R${i}`, late_rows: 0 }));
    h.stub({ method: "POST", path: "/v1/reports/final-submit-log/query", fn: () => ({ status: 200, body: rep([col("route"), col("late_rows", "integer")], rows, 250) }) });
    const m = text(html(await DayControlPageContent({ searchParams: sp({ zone: "334", date: "2026-10-06" }), basePath: "/admin/day", withMissing: false })));
    expect(m).not.toContain("Nothing arrived after the final");
  });
});
