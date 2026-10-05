import type { AuditEntry } from "@/contract/types";
import { formatDateTime, t, type Locale } from "@/lib/i18n";

export function AuditHistory({ entries, locale }: { entries: readonly AuditEntry[]; locale: Locale }) {
  return (
    <section aria-labelledby="history-title" className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm" data-testid="audit-history">
      <h2 id="history-title" className="mb-2 text-lg font-semibold">
        {t(locale, "admin.history")}
      </h2>
      {entries.length === 0 ? (
        <p className="text-sm text-slate-600">{t(locale, "admin.history.empty")}</p>
      ) : (
        <ol className="space-y-3">
          {entries.map((e) => (
            <li key={e.id} className="border-l-2 border-brand-100 pl-3 text-sm" data-testid="audit-entry">
              <p className="text-slate-600">
                <time dateTime={e.at}>{formatDateTime(locale, e.at)}</time> · {e.action} · {t(locale, "admin.history.by", { name: e.actor_username ?? String(e.actor_user_id ?? "") })}
              </p>
              {e.reason ? (
                <p className="mt-0.5 text-slate-900" data-testid="audit-reason">
                  {e.reason}
                </p>
              ) : null}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
