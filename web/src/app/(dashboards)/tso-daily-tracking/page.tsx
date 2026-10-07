import { BucketCards, BUCKET_KEY } from "@/components/dash/tracking";
import { n, pctText } from "@/components/dash/tiles";
import { TileMeta } from "@/components/reports/tile-meta";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { BUCKETS } from "@/lib/dash/buckets";
import { getDailyTracking } from "@/lib/dash/server";
import { businessDate, problemMessage, t } from "@/lib/i18n";

const DATE = /^\d{4}-\d{2}-\d{2}$/;

// TSO Daily Tracking Dashboard (F-WEB-028): routes of the TSO's territory in the 100, 90 to 100, 80 to 90 and below 80 percent buckets,
// plus the two non-selling buckets. The scope is the server's; the page never names a territory.
export default async function TsoDailyTrackingPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const [sp, locale, session] = await Promise.all([searchParams, getLocale(), requireSession()]);
  const today = businessDate();
  const date = sp.date && DATE.test(sp.date) ? sp.date : today;
  const r = await getDailyTracking(session.at, date);
  if (!r.ok)
    return (
      <p role="alert" className="rounded bg-red-50 p-3 text-red-900">
        {problemMessage(locale, r.problem.code)}
      </p>
    );
  return (
    <div className="space-y-4" data-testid="tso-daily-tracking">
      <h1 className="text-2xl font-bold">{t(locale, "menu.tso_daily_tracking")}</h1>
      <TileMeta locale={locale} businessDate={r.data.business_date} asOf={r.data.as_of} today={today} />
      <BucketCards locale={locale} items={r.data.items} />
      <div className="grid gap-3 md:grid-cols-2">
        {BUCKETS.map((b) => (
          <section key={b} className="rounded-lg border border-slate-200 bg-white p-3" data-testid={`bucket-${b}`}>
            <h2 className="text-sm font-semibold">{t(locale, BUCKET_KEY[b])}</h2>
            <ul className="mt-1 space-y-0.5 text-sm">
              {r.data.items
                .filter((i) => i.bucket === b)
                .map((i) => (
                  <li key={i.route_id} className="flex justify-between gap-2">
                    <span>{i.route_name}</span>
                    <span className="tabular-nums">
                      {n(locale, i.visited_outlets)}/{n(locale, i.target_outlets)} · {pctText(locale, i.tilldate_target_achievement_pct)}
                    </span>
                  </li>
                ))}
            </ul>
          </section>
        ))}
      </div>
    </div>
  );
}
