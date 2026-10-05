import { MapPanel } from "@/components/map-panel";
import { getLocale } from "@/lib/auth/service";
import { businessDate, formatBusinessDate, t, type MessageKey } from "@/lib/i18n";

const KPIS: MessageKey[] = ["dashboard.kpi.sales", "dashboard.kpi.memos", "dashboard.kpi.visits", "dashboard.kpi.logged_in"];

// Empty scoped dashboard shell (N-010). Figures come from /v1/dashboards/* in the dashboard rows; the scope is the server's.
export default async function DashboardPage() {
  const locale = await getLocale();
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-bold">{t(locale, "dashboard.title")}</h1>
        <p className="text-sm text-slate-600">
          {t(locale, "dashboard.business_date")}: <time data-testid="business-date">{formatBusinessDate(locale, businessDate())}</time>
        </p>
      </div>
      <ul className="grid grid-cols-2 gap-3 md:grid-cols-4">
        {KPIS.map((k) => (
          <li key={k} className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <p className="text-sm text-slate-600">{t(locale, k)}</p>
            <p className="mt-1 text-2xl font-semibold text-slate-400">—</p>
          </li>
        ))}
      </ul>
      <section className="rounded-lg border border-slate-200 bg-white p-6 text-center shadow-sm" data-testid="dashboard-empty">
        <h2 className="text-lg font-semibold">{t(locale, "dashboard.empty.title")}</h2>
        <p className="mt-1 text-slate-600">{t(locale, "dashboard.empty.body")}</p>
      </section>
      <MapPanel locale={locale} />
    </div>
  );
}
