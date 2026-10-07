import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { getDailyTracking, listAssignments, listRoutes } from "@/lib/dash/server";
import { joinRoutes, linesFromAssignees } from "@/lib/dash/routes";
import { businessDate, problemMessage, t } from "@/lib/i18n";
import { n } from "@/components/dash/tiles";

// Route Planning: Browse Routes (F-WEB-010), read-only. Names come from the route's assignees (one call); older servers fall back to assignment joins.
export default async function RoutesPage() {
  const [locale, session] = await Promise.all([getLocale(), requireSession()]);
  const today = businessDate();
  const routes = await listRoutes(session.at, { include: "assignees" });
  if (!routes.ok)
    return (
      <p role="alert" className="rounded bg-red-50 p-3 text-red-900">
        {problemMessage(locale, routes.problem.code)}
      </p>
    );
  // One call with the assignees (contract v1.2). An older server omits `assignees`: then fall back to the assignment and tracking joins.
  let lines;
  if (routes.data.items.every((r) => r.assignees !== undefined)) lines = linesFromAssignees(routes.data.items);
  else {
    const [assignments, tracking] = await Promise.all([listAssignments(session.at, today), getDailyTracking(session.at, today)]);
    const names = new Map<number, string>();
    if (tracking.ok) for (const r of tracking.data.items) if (r.user_id && r.user_name) names.set(r.user_id, r.user_name);
    lines = joinRoutes(routes.data.items, assignments.ok ? assignments.data.items : [], names);
  }
  const notSet = t(locale, "routes.not_set");
  return (
    <div className="space-y-4" data-testid="routes">
      <h1 className="text-2xl font-bold">{t(locale, "menu.routes")}</h1>
      {lines.length === 0 ? (
        <p data-testid="table-empty" className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">
          {t(locale, "report.empty")}
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
          <table className="min-w-full divide-y divide-slate-200 text-sm" data-testid="routes-table">
            <thead className="bg-slate-50 text-left">
              <tr>
                {(["tracking.col.route", "routes.col.kind", "routes.col.amo", "routes.col.sr", "routes.col.visit", "filter.status"] as const).map((k) => (
                  <th key={k} scope="col" className="whitespace-nowrap px-3 py-2 font-semibold text-slate-700">
                    {t(locale, k)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {lines.map((l) => (
                <tr key={l.route_id} data-route={l.route_id} data-kind={l.kind}>
                  <td className="whitespace-nowrap px-3 py-2">{l.name}</td>
                  <td className="px-3 py-2">{t(locale, l.kind === "amo" ? "routes.kind.amo" : "routes.kind.sr")}</td>
                  <td className="px-3 py-2" data-col="amo">
                    {l.amo ?? notSet}
                  </td>
                  <td className="px-3 py-2" data-col="sr">
                    {l.sr ?? notSet}
                  </td>
                  <td className="px-3 py-2">{l.visit_kind ?? "—"}</td>
                  <td className="px-3 py-2">{t(locale, l.status === "active" ? "entity.status.active" : "entity.status.inactive")}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="px-3 py-2 text-xs text-slate-500">{t(locale, "report.rows", { n: n(locale, lines.length) })}</p>
        </div>
      )}
    </div>
  );
}
