// tso-manual W1, XA-02 and XA-03 (build contract s5 W1; R-F-12; docs/24 s12.3): the TSO Visit Log is a catalogued web report
// (key tso-visit-log, title in en and bn) and the Task Planner carries the columns B3 adds (assigned by, route, label, comment) and
// the server's outlet code filter. Both run through the generic report engine against the contract mock.
import { describe, expect, it } from "vitest";
import type { ScopeSummary } from "@/contract/types";
import { WEB_REPORTS, reportBySlug } from "@/lib/reports/catalog";
import { buildReportQuery } from "@/lib/reports/query";
import { columnLabel } from "@/lib/reports/format";
import { runReportJson } from "@/lib/reports/server";
import { REPORT_KEYS } from "@/lib/admin/report-keys";
import { t } from "@/lib/i18n";
import { setupMock } from "./helpers/harness";
import { SEED_DATE } from "../mock/seed";

setupMock();
async function login(u: string, p: string) {
  const b = (await (await fetch(`${process.env.ARON_API_BASE_URL}/v1/auth/login`, { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ username: u, password: p, client: "web" }) })).json()) as { access_token: string; scope: ScopeSummary };
  return { at: b.access_token, scope: b.scope };
}
const run = async (slug: string, params: Record<string, string> = {}, who: [string, string] = ["tso334", "tso-pass-1"]) => {
  const u = await login(...who);
  const rep = reportBySlug(slug)!;
  const r = await runReportJson(u.at, rep.key, buildReportQuery(rep, { from: SEED_DATE, to: SEED_DATE, ...params }, u.scope, "json", SEED_DATE));
  if (!r.ok) throw new Error(r.problem.code);
  return r.data;
};

describe("XA-02: the TSO Visit Log report", () => {
  it("is catalogued under its contract key with a field-force title in en and bn", () => {
    const rep = reportBySlug("tso-visit-log");
    expect(rep).toMatchObject({ key: "tso-visit-log", area: "field_force", filters: ["period", "geo"] });
    expect((REPORT_KEYS as readonly string[]).includes(rep!.key)).toBe(true);
    expect(t("en", rep!.titleKey)).toBe("TSO Visit Log");
    expect(t("bn", rep!.titleKey)).toBe("টিএসও ভিজিট লগ");
    expect(WEB_REPORTS.filter((r) => r.key === "tso-visit-log")).toHaveLength(1);
  });
  it("lists the plans in reach with both questionnaire answers by key and the delegated task; Bangla column labels", async () => {
    const d = await run("tso-visit-log");
    expect(d.columns.map((c) => c.key)).toEqual(["plan_date", "tso", "zone", "route", "outlet_code", "outlet_name", "status", "completed_at", "sr_visits_regularly", "sr_prints_memo", "delegate_task", "task", "task_status"]);
    expect(d.total_rows).toBe(3);
    expect(JSON.stringify(d.rows)).not.toContain("Gulshan");
    expect(columnLabel("bn", d.columns.find((c) => c.key === "sr_visits_regularly")!)).toBe("SR আপনার দোকান নিয়মিত ভিজিট করে?");
    expect((await run("tso-visit-log", {}, ["tso999", "tso-pass-3"])).rows).toEqual([]);
  });
});

describe("XA-03: the Task Planner columns", () => {
  it("carries assigned by, route, label and comment, and filters by outlet code", async () => {
    expect(reportBySlug("task-planner")!.filters).toContain("outlet_code");
    const d = await run("task-planner");
    for (const k of ["assigned_by", "route", "label", "comment"]) expect(d.columns.map((c) => c.key)).toContain(k);
    expect(d.rows[0]).toMatchObject({ assigned_by: "tso334", label: "Stock check" });
    const one = await run("task-planner", { outlet_code: "O-10232-1" });
    expect(one.rows.map((r) => r.route)).toEqual(["Banani North"]);
  });
});
