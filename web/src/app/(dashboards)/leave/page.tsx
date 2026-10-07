import { JsonAction } from "@/components/dash/json-action";
import { n } from "@/components/dash/tiles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { listLeave } from "@/lib/dash/server";
import { formatBusinessDate, problemMessage, t, type MessageKey } from "@/lib/i18n";

// Leave approval (F-WEB-046): a DMO decides a TSO's leave. The decision is the server's (role and reach); the TSO sees the new status
// at the next list refresh in the app. Only a DMO is offered the buttons; everyone else sees the list read-only.
export default async function LeavePage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const [sp, locale, session] = await Promise.all([searchParams, getLocale(), requireSession()]);
  const status = sp.status === "approved" || sp.status === "rejected" || sp.status === "pending" ? sp.status : "pending";
  const r = await listLeave(session.at, status);
  const canDecide = session.user.role === "DMO";
  return (
    <div className="space-y-4" data-testid="leave">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <h1 className="text-2xl font-bold">{t(locale, "menu.leave")}</h1>
        <form method="get" className="flex items-end gap-2 text-sm">
          <select name="status" defaultValue={status} aria-label={t(locale, "filter.status")} className="rounded border border-slate-300 bg-white px-2 py-1">
            {(["pending", "approved", "rejected"] as const).map((s) => (
              <option key={s} value={s}>
                {t(locale, `leave.status.${s}` as MessageKey)}
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
          {t(locale, "leave.empty")}
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm">
          <table className="min-w-full divide-y divide-slate-200 text-sm" data-testid="leave-table">
            <thead className="bg-slate-50 text-left">
              <tr>
                {(["leave.col.user", "leave.col.type", "leave.col.from", "leave.col.to", "leave.col.days", "leave.col.reason", "filter.status", "common.actions"] as const).map((k) => (
                  <th key={k} scope="col" className="whitespace-nowrap px-3 py-2 font-semibold text-slate-700">
                    {t(locale, k)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {r.data.items.map((l) => (
                <tr key={l.leave_uuid} data-leave={l.leave_uuid} data-status={l.status}>
                  <td className="px-3 py-2">{n(locale, l.user_id)}</td>
                  <td className="px-3 py-2">{l.leave_type_code}</td>
                  <td className="whitespace-nowrap px-3 py-2">{formatBusinessDate(locale, l.from_date)}</td>
                  <td className="whitespace-nowrap px-3 py-2">{formatBusinessDate(locale, l.to_date)}</td>
                  <td className="px-3 py-2 text-right tabular-nums">{n(locale, l.days)}</td>
                  <td className="px-3 py-2">{l.reason}</td>
                  <td className="px-3 py-2">{t(locale, `leave.status.${l.status}` as MessageKey)}</td>
                  <td className="px-3 py-2">
                    {canDecide && l.status === "pending" ? (
                      <span className="flex gap-1">
                        <JsonAction url="/api/bff/leave-decision" body={{ leave_uuid: l.leave_uuid, decision: "approve" }} text={t(locale, "leave.approve")} failed={t(locale, "error.generic")} tone="good" testId={`approve-${l.leave_uuid}`} />
                        <JsonAction url="/api/bff/leave-decision" body={{ leave_uuid: l.leave_uuid, decision: "reject" }} text={t(locale, "leave.reject")} failed={t(locale, "error.generic")} tone="bad" testId={`reject-${l.leave_uuid}`} />
                      </span>
                    ) : null}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}
