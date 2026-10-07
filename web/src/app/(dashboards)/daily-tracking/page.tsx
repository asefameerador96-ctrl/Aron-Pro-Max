import { BucketCards, TrackingTable } from "@/components/dash/tracking";
import { TileMeta } from "@/components/reports/tile-meta";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { canTakeAction, countBuckets } from "@/lib/dash/buckets";
import { getDailyTracking, previousDate } from "@/lib/dash/server";
import { businessDate, problemMessage, t } from "@/lib/i18n";

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// Daily Tracking Dashboard (F-WEB-038): route buckets (100, 90-100, 80-90, below 80), the exception bucket distinct from not logged in,
// and a yesterday comparator. The contract has no same-time comparator yet (docs/requests/web-dashboard-contract-gaps.md, item 1), so the
// comparator is the previous business day's figures and the page says so. From 17:00 Dhaka each route can take action.
export default async function DailyTrackingPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const [sp, locale, session] = await Promise.all([searchParams, getLocale(), requireSession()]);
  const today = businessDate();
  const date = sp.date && DATE.test(sp.date) ? sp.date : today;
  const [cur, prev] = await Promise.all([getDailyTracking(session.at, date), getDailyTracking(session.at, previousDate(date))]);
  const canAct = canTakeAction(date, today);
  return (
    <div className="space-y-4" data-testid="daily-tracking">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <h1 className="text-2xl font-bold">{t(locale, "menu.daily_tracking")}</h1>
        <form method="get" className="flex items-end gap-2 text-sm">
          <input type="date" name="date" defaultValue={date} max={today} aria-label={t(locale, "filter.date")} className="rounded border border-slate-300 bg-white px-2 py-1" />
          <button type="submit" className="rounded border border-slate-300 bg-white px-3 py-1">
            {t(locale, "common.filter")}
          </button>
        </form>
      </div>
      {cur.ok ? (
        <>
          <TileMeta locale={locale} businessDate={cur.data.business_date} asOf={cur.data.as_of} today={today} />
          <BucketCards locale={locale} items={cur.data.items} compare={prev.ok ? countBuckets(prev.data.items) : null} compareLabel={t(locale, "tracking.yesterday")} />
          <p className="text-xs text-slate-600" data-testid="comparator-note">
            {t(locale, "tracking.comparator_note")}
          </p>
          <p className="text-sm" data-testid="take-action-state">
            {canAct ? t(locale, "tracking.action_open") : t(locale, "tracking.action_after")}
          </p>
          <TrackingTable locale={locale} items={cur.data.items} businessDate={date} canAct={canAct} />
        </>
      ) : (
        <p role="alert" className="rounded bg-red-50 p-3 text-red-900">
          {problemMessage(locale, cur.problem.code)}
        </p>
      )}
    </div>
  );
}
