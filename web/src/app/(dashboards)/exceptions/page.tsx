import { JsonAction } from "@/components/dash/json-action";
import { n } from "@/components/dash/tiles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { listRiskSignals } from "@/lib/dash/server";
import { formatBusinessDate, problemMessage, t, type MessageKey } from "@/lib/i18n";

const STATUSES = ["open", "reviewed", "dismissed", "confirmed"] as const;

// Exceptions page (F-WEB-057): the web twin of the AMO Exceptions screen. Risk signals of the caller's reach with review, dismiss and
// confirm. A dismissal that the server re-sampled to the TSO comes back as an open signal whose last review was a dismissal: it is
// marked "re-sampled" so the reviewer knows a colleague already dismissed it. Nothing here auto-reverses anything.
export default async function ExceptionsPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const [sp, locale, session] = await Promise.all([searchParams, getLocale(), requireSession()]);
  const status = STATUSES.find((s) => s === sp.status) ?? "open";
  const r = await listRiskSignals(session.at, { status });
  return (
    <div className="space-y-4" data-testid="exceptions">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <h1 className="text-2xl font-bold">{t(locale, "menu.exceptions")}</h1>
        <form method="get" className="flex items-end gap-2 text-sm">
          <select name="status" defaultValue={status} aria-label={t(locale, "filter.status")} className="rounded border border-slate-300 bg-white px-2 py-1">
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {t(locale, `exceptions.status.${s}` as MessageKey)}
              </option>
            ))}
          </select>
          <button type="submit" className="rounded border border-slate-300 bg-white px-3 py-1">
            {t(locale, "common.filter")}
          </button>
        </form>
      </div>
      {!r.ok ? (
        <p role="alert" className="rounded bg-red-50 p-3 text-red-900">
          {problemMessage(locale, r.problem.code)}
        </p>
      ) : r.data.items.length === 0 ? (
        <p data-testid="table-empty" className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">
          {t(locale, "exceptions.empty")}
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
          <table className="min-w-full divide-y divide-slate-200 text-sm" data-testid="exceptions-table">
            <thead className="bg-slate-50 text-left">
              <tr>
                {(["exceptions.col.date", "exceptions.col.code", "exceptions.col.severity", "exceptions.col.subject", "exceptions.col.score", "exceptions.col.status", "common.actions"] as const).map((k) => (
                  <th key={k} scope="col" className="whitespace-nowrap px-3 py-2 font-semibold text-slate-700">
                    {t(locale, k)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {r.data.items.map((s) => {
                const resampled = s.status === "open" && s.last_review?.action === "dismissed";
                return (
                  <tr key={s.signal_id} data-signal={s.signal_id} data-status={s.status} data-resampled={resampled ? "yes" : "no"}>
                    <td className="whitespace-nowrap px-3 py-2">{formatBusinessDate(locale, s.business_date)}</td>
                    <td className="px-3 py-2">{s.code}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{n(locale, s.severity)}</td>
                    <td className="px-3 py-2">{s.route_id ? `${t(locale, "scope.route")} ${n(locale, s.route_id)}` : `${s.subject_type} ${s.subject_id}`}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{n(locale, s.score)}</td>
                    <td className="px-3 py-2">
                      {t(locale, `exceptions.status.${s.status}` as MessageKey)}
                      {resampled ? (
                        <span className="ml-2 rounded-full bg-violet-100 px-2 py-0.5 text-xs text-violet-900" data-testid="resampled-badge">
                          {t(locale, "exceptions.resampled")}
                        </span>
                      ) : null}
                    </td>
                    <td className="px-3 py-2">
                      {s.status === "open" ? (
                        <span className="flex flex-wrap gap-1">
                          {(["reviewed", "dismissed", "confirmed"] as const).map((a) => (
                            <JsonAction key={a} url="/api/bff/risk-review" body={{ signal_id: s.signal_id, action: a }} text={t(locale, `exceptions.action.${a}` as MessageKey)} failed={t(locale, "error.generic")} tone={a === "confirmed" ? "bad" : "neutral"} testId={`review-${a}-${s.signal_id}`} />
                          ))}
                        </span>
                      ) : null}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
