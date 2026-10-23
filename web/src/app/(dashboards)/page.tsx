import { redirect } from "next/navigation";
import { Banner } from "@/components/kit/banner";
import { AutoRefresh, RetryButton } from "@/components/dash/table-actions";
import { BreakdownCard, ChannelCallsCard, ChannelStdCard, CprCard, FinalSubmitCard, GeoCard, LoginSubmitCard, SalesCards } from "@/components/national/cards";
import { FfGeoCard } from "@/components/national/ff-geo-card";
import { FilterPanel } from "@/components/national/filter-panel";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { mapsKey } from "@/lib/maps/guard";
import { businessDate, formatBusinessDate, problemMessage, t } from "@/lib/i18n";
import { clock } from "@/lib/national/fmt";
import { parseBreakdown, parseQuery, toSearch } from "@/lib/national/query";
import { getBreakdown, getChannelCalls, getChannelStd, getCpr, getFilters, getFinalSubmit, getGeoFencing, getLoginSubmit, getSales } from "@/lib/national/server";

// The national dashboard (owner directive 2026-10-22, docs/34 s2, D-DASH-02): filter panel, the twelve cards of the owner's screenshot and
// brief, built from one slice each. The cards refresh only when Apply Filter is pressed (the filter is the URL); the page re-reads every
// minute while it is open. Scope (geography, brands) is cut by the server from the token: the page never sends it.
export default async function DashboardPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const [sp, locale, session] = await Promise.all([searchParams, getLocale(), requireSession()]);
  if (session.user.role === "SUPPORT") redirect("/sync-health"); // SUPPORT has no dashboard home (docs/24 s8.5: sync health only)
  const today = businessDate();
  const q = parseQuery(sp, today);
  const { measure, by } = parseBreakdown(sp);
  const token = session.at;
  const [filters, sales, cpr, chCalls, final, login, chStd, breakdown, geo] = await Promise.all([
    getFilters(token), getSales(token, q), getCpr(token, q), getChannelCalls(token, q), getFinalSubmit(token, q), getLoginSubmit(token, q), getChannelStd(token, q), getBreakdown(token, q, measure, by), getGeoFencing(token, q),
  ]);

  const stamps = [sales, cpr, chCalls, final, login, chStd, breakdown, geo].flatMap((s) => (s.ok && s.data.as_of ? [s.data.as_of] : []));
  const asOf = stamps.length > 0 ? stamps.reduce((a, b) => (a > b ? a : b)) : null;
  const live = q.date === today;
  // A BRAND token is refused (403) on every card without a brand dimension: that is how the page knows to show product cards only.
  const productOnly = !cpr.ok && cpr.status === 403;
  // Refused even on the product cards: the token carries no dashboard grant (an ADMIN without the module). Nothing to draw, say so.
  const noAccess = productOnly && !sales.ok && sales.status === 403;
  const hrefFor = (m: typeof measure, b: typeof by) => `/?${toSearch(q, { bm: m === "std" ? undefined : m, bb: b === "brand" ? undefined : b }).toString()}`;

  if (noAccess) {
    return (
      <div className="space-y-4" data-testid="national-dashboard">
        <h1 className="sr-only">{t(locale, "nat.title")}</h1>
        <Banner tone="warning" role="status" testId="nat-no-access">{t(locale, "nat.no_access")}</Banner>
      </div>
    );
  }

  return (
    <div className="space-y-4" data-testid="national-dashboard">
      <h1 className="sr-only">{t(locale, "nat.title")}</h1>
      <div className="flex flex-wrap items-center justify-between gap-2 text-caption text-secondary" data-testid="nat-stamp">
        <p>
          <time dateTime={q.date} data-testid="nat-date">{formatBusinessDate(locale, q.date)}</time>
          {asOf ? <span data-testid="nat-last-updated"> · {t(locale, "nat.last_updated", { time: clock(locale, asOf) })}</span> : null}
        </p>
        <p>{live ? t(locale, "nat.till_time", { time: clock(locale, asOf ?? new Date().toISOString()) }) : t(locale, "nat.final_day")}</p>
      </div>
      {filters.ok ? <FilterPanel filters={filters.data} initial={q} today={today} /> : (
        <Banner tone="danger" role="alert" testId="nat-filters-error">
          <span className="flex flex-wrap items-center gap-3">{problemMessage(locale, filters.problem.code)}<RetryButton msg={t(locale, "nat.card_retry")} /></span>
        </Banner>
      )}
      {productOnly ? <p className="text-caption text-secondary" data-testid="nat-product-only">{t(locale, "nat.card_hidden_note")}</p> : null}
      <div className="grid grid-cols-1 gap-4 md:grid-cols-2 xl:grid-cols-12" data-testid="nat-grid">
        <SalesCards slot={sales} locale={locale} />
        <CprCard slot={cpr} locale={locale} />
        <ChannelCallsCard slot={chCalls} locale={locale} />
        <FinalSubmitCard slot={final} locale={locale} />
        <LoginSubmitCard slot={login} locale={locale} />
        <ChannelStdCard slot={chStd} locale={locale} />
        <BreakdownCard slot={breakdown} locale={locale} hrefFor={hrefFor} measure={measure} by={by} />
        <GeoCard slot={geo} locale={locale} />
        {productOnly || (!geo.ok && geo.status === 403) ? null : <FfGeoCard search={toSearch(q).toString()} date={q.date} mapsEnabled={mapsKey() !== null} />}
      </div>
      <AutoRefresh />
    </div>
  );
}
