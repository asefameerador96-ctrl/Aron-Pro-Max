import { n } from "@/components/dash/tiles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { listLeave } from "@/lib/dash/server";
import { formatBusinessDate, problemMessage, t, type MessageKey } from "@/lib/i18n";
import { LeaveDecision } from "./leave-decision"; // tso-manual W1 (LV-19)
import { applicantLines, loadLeaveTypes, typeLabel } from "./leave-rows"; // tso-manual W1 (LV-19)

// Leave approval (F-WEB-046): a DMO decides a TSO's leave. The decision is the server's (role and reach); the TSO sees the new status
// at the next list refresh in the app. Only a DMO is offered the buttons; everyone else sees the list read-only.
export default async function LeavePage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const [sp, locale, session] = await Promise.all([searchParams, getLocale(), requireSession()]);
  const status = sp.status === "approved" || sp.status === "rejected" || sp.status === "pending" ? sp.status : "pending";
  const [r, types] = await Promise.all([listLeave(session.at, status), loadLeaveTypes(session.at)]); // tso-manual W1: type labels
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
        <div role="region" aria-label={t(locale, "menu.leave")} tabIndex={0} className="overflow-x-auto rounded-lg border border-slate-200 bg-white shadow-sm focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
          <table className="min-w-full divide-y divide-slate-200 text-sm" data-testid="leave-table">
            <thead className="bg-slate-50 text-left">
              <tr>
                {(["tsoman.leave.col.applicant", "tsoman.leave.col.territory", "leave.col.type", "leave.col.from", "leave.col.to", "leave.col.days", "leave.col.reason", "filter.status", "common.actions"] as const).map((k) => (
                  <th key={k} scope="col" className="whitespace-nowrap px-3 py-2 font-semibold text-slate-700">
                    {t(locale, k)}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {r.data.items.map((l) => {
                // tso-manual W1 (LV-19): the applicant by name and username, the territory, and the type's label
                const who = applicantLines(l);
                return (
                  <tr key={l.leave_uuid} data-leave={l.leave_uuid} data-status={l.status}>
                    <td className="px-3 py-2" data-testid={`applicant-${l.leave_uuid}`}>
                      <span className="block font-semibold">{who.name}</span>
                      {who.username ? <span className="block text-xs text-slate-500">{who.username}</span> : null}
                    </td>
                    <td className="px-3 py-2">{l.territory_name ?? "—"}</td>
                    <td className="px-3 py-2">{typeLabel(locale, types, l.leave_type_code)}</td>
                    <td className="whitespace-nowrap px-3 py-2">{formatBusinessDate(locale, l.from_date)}</td>
                    <td className="whitespace-nowrap px-3 py-2">{formatBusinessDate(locale, l.to_date)}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{n(locale, l.days)}</td>
                    <td className="px-3 py-2">{l.reason}</td>
                    <td className="px-3 py-2">{t(locale, `leave.status.${l.status}` as MessageKey)}</td>
                    <td className="px-3 py-2">
                      {canDecide && l.status === "pending" ? <LeaveDecision leaveUuid={l.leave_uuid} /> : null}
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
