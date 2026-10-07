// F-ADM-029 / F-ADM-052 day control and the team-op proxy. (The code-list editor is the web-admin one: docs/requests/web-config-web-admin-dedupe.md.)
import { describe, expect, it, vi } from "vitest";
import { POST as teamPost } from "@/app/api/bff/team-op/route";
import { DayControlView } from "@/components/admin/config/day-control-view";
import { lateRows, missingCheckoutRows } from "@/lib/admin/reports";
import type { ReportResult } from "@/lib/admin/types";
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
