import { FinalSubmitPanel } from "@/components/dash/final-submit-panel";
import { Big, Tile, n, pctText, takaText } from "@/components/dash/tiles";
import { MapPanel, type MapPin } from "@/components/map-panel";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { getGeoValidation, getLoginSubmit, getSummary, getTeamLocations, listOutlets } from "@/lib/dash/server";
import { isCalendarDate } from "@/lib/dates";
import { businessDate, formatBusinessDate, problemMessage, t, type Locale } from "@/lib/i18n";
import type { StaleReason } from "@/components/reports/tile-meta";


function Err({ locale, code }: { locale: Locale; code: string | undefined }) {
  return (
    <p role="alert" className="text-sm text-red-800" data-testid="tile-error">
      {problemMessage(locale, code)}
    </p>
  );
}

// The role-aware home dashboard (F-WEB-001): sales, strike rate, channels, final-submit status, login and submit status,
// geo fencing and the field-force location map. Everything comes from the stored aggregates of the server-derived scope;
// the page renders on load without any button press. Each tile carries its business date, as-of stamp and reason chip.
export default async function DashboardPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const [sp, locale, session] = await Promise.all([searchParams, getLocale(), requireSession()]);
  const today = businessDate();
  const date = isCalendarDate(sp.date) ? sp.date : today;
  const token = session.at;
  const [summary, loginSubmit, geo, team] = await Promise.all([getSummary(token, { from: date, to: date }), getLoginSubmit(token, date), getGeoValidation(token, { from: date, to: date }), getTeamLocations(token, date)]);

  const asOf = summary.ok ? summary.data.as_of : null;
  const k = summary.ok ? summary.data.kpis : null;
  const empty = k !== null && k.target_routes === 0;
  const reasonFor = (live: boolean): StaleReason | undefined => (empty ? "no_data" : live ? undefined : "not_live_today");
  const d = (a: string | null = asOf) => ({ businessDate: date, asOf: a, today, reason: reasonFor(date === today) });

  // Outlet pins: outlets of the first zones in reach (children of the node when they are zones), plus the last synced fix of each team member.
  const zones = summary.ok ? summary.data.children.filter((c) => c.node.type === "zone").slice(0, 3) : [];
  const outletPages = await Promise.all(zones.map((z) => listOutlets(token, { zone_id: z.node.id })));
  const pins: MapPin[] = [
    ...outletPages.flatMap((p) => (p.ok ? p.data.items : [])).filter((o) => typeof o.lat === "number" && typeof o.lng === "number").map((o): MapPin => ({ id: `o${o.id}`, lat: o.lat!, lng: o.lng!, label: o.name, kind: "outlet" })),
    ...(team.ok ? team.data.items : []).filter((m) => m.last_fix).map((m): MapPin => ({ id: `u${m.user_id}`, lat: m.last_fix!.lat, lng: m.last_fix!.lng, label: m.full_name, kind: "fix" })),
  ];

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-end justify-between gap-2">
        <h1 className="text-2xl font-bold">{t(locale, "dashboard.title")}</h1>
        <form method="get" className="flex items-end gap-2 text-sm">
          <label>
            <span className="block text-xs text-slate-500">{t(locale, "dashboard.business_date")}</span>
            <input type="date" name="date" defaultValue={date} max={today} className="rounded border border-slate-300 bg-white px-2 py-1" />
          </label>
          <button type="submit" className="rounded border border-slate-300 bg-white px-3 py-1">
            {t(locale, "common.filter")}
          </button>
        </form>
      </div>
      <p className="text-sm text-slate-600">
        <time data-testid="business-date" dateTime={date}>
          {formatBusinessDate(locale, date)}
        </time>
      </p>
      {!summary.ok ? (
        <p role="alert" className="rounded bg-red-50 p-3 text-red-900" data-testid="dashboard-error">
          {problemMessage(locale, summary.problem.code)}
        </p>
      ) : null}
      {empty ? (
        <section className="rounded-lg border border-slate-200 bg-white p-6 text-center shadow-sm" data-testid="dashboard-empty">
          <h2 className="text-lg font-semibold">{t(locale, "dashboard.empty.title")}</h2>
          <p className="mt-1 text-slate-600">{t(locale, "dashboard.empty.body")}</p>
        </section>
      ) : null}
      {k && summary.ok ? (
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3" data-testid="dashboard-tiles">
          <Tile id="sales" locale={locale} titleKey="dashboard.kpi.sales" date={d()}>
            <Big testId="sales-net">{takaText(locale, k.net_mtk)}</Big>
            <p className="text-sm text-slate-600">
              {t(locale, "dashboard.kpi.gross")}: {takaText(locale, k.gross_mtk)} · {t(locale, "dashboard.kpi.memos")}: {n(locale, k.active_memo_count)}
            </p>
          </Tile>
          <Tile id="strike" locale={locale} titleKey="dashboard.kpi.strike" date={d()}>
            <Big testId="strike-rate">{pctText(locale, k.strike_rate_pct)}</Big>
            <p className="text-sm text-slate-600" data-testid="strike-basis">
              {t(locale, "dashboard.kpi.strike_basis", { calls: n(locale, k.successful_calls), visited: n(locale, k.visited_outlets) })}
            </p>
          </Tile>
          <Tile id="visits" locale={locale} titleKey="dashboard.kpi.visits" date={d()}>
            <Big>
              {n(locale, k.visited_outlets)} / {n(locale, k.target_outlets)}
            </Big>
          </Tile>
          <Tile id="channels" locale={locale} titleKey="dashboard.channels" date={d()}>
            <ul className="space-y-1 text-sm" data-testid="channel-list">
              {summary.data.by_channel?.map((c) => (
                <li key={c.code} className="flex justify-between gap-2">
                  <span>{c.name ?? c.code}</span>
                  <span className="tabular-nums">
                    {takaText(locale, c.net_mtk)} · {n(locale, c.successful_calls)}/{n(locale, c.memo_count)}
                  </span>
                </li>
              ))}
            </ul>
          </Tile>
          <Tile id="finalsubmit" locale={locale} titleKey="dashboard.final_submit" date={d()}>
            <FinalSubmitPanel locale={locale} kpis={k} zones={summary.data.children} />
          </Tile>
          <Tile id="loginsubmit" locale={locale} titleKey="dashboard.login_submit" date={d(loginSubmit.ok ? loginSubmit.data.as_of : null)}>
            {loginSubmit.ok ? (
              <dl className="grid grid-cols-2 gap-2 text-sm" data-testid="login-submit">
                <Stat locale={locale} msg="dashboard.login_pct" value={pctText(locale, loginSubmit.data.kpis.login_pct)} id="login-pct" />
                <Stat locale={locale} msg="finalsubmit.submit_pct" value={pctText(locale, loginSubmit.data.kpis.submit_pct_of_logged_in)} id="submit-pct-ls" />
                <Stat locale={locale} msg="dashboard.not_logged_in" value={n(locale, loginSubmit.data.not_logged_in.length)} id="not-logged-in" />
                <Stat locale={locale} msg="dashboard.not_submitted" value={n(locale, loginSubmit.data.logged_in_not_submitted.length)} id="not-submitted" />
                <Stat locale={locale} msg="dashboard.submitted" value={n(locale, loginSubmit.data.submitted.length)} id="submitted" />
                <Stat locale={locale} msg="dashboard.exceptions" value={n(locale, loginSubmit.data.exceptions?.length ?? 0)} id="exceptions" />
              </dl>
            ) : (
              <Err locale={locale} code={loginSubmit.problem.code} />
            )}
          </Tile>
          <Tile id="geo" locale={locale} titleKey="dashboard.geo_fencing" date={d(geo.ok ? geo.data.as_of : null)}>
            {geo.ok ? (
              <dl className="grid grid-cols-2 gap-2 text-sm" data-testid="geo-fencing">
                <Stat locale={locale} msg="dashboard.geo_valid" value={pctText(locale, geo.data.geo_valid_pct)} id="geo-valid" />
                <Stat locale={locale} msg="dashboard.force_sale" value={pctText(locale, geo.data.force_sale_pct)} id="force-sale" />
                <Stat locale={locale} msg="dashboard.mock" value={pctText(locale, geo.data.mock_pct)} id="mock-pct" />
                <Stat locale={locale} msg="dashboard.suspicious" value={pctText(locale, geo.data.suspicious_pct)} id="suspicious-pct" />
              </dl>
            ) : (
              <Err locale={locale} code={geo.problem.code} />
            )}
          </Tile>
          <Tile id="ffgeo" locale={locale} titleKey="dashboard.ff_geo" date={d(team.ok ? team.data.as_of : null)} wide>
            <MapPanel
              pins={pins}
              labels={{ placeholder: t(locale, "dashboard.maps.placeholder"), capped: t(locale, "dashboard.maps.capped"), failed: t(locale, "dashboard.maps.failed"), outlets: t(locale, "dashboard.maps.outlets"), fixes: t(locale, "dashboard.maps.fixes"), pins: t(locale, "dashboard.maps.pins") }}
            />
          </Tile>
        </div>
      ) : null}
    </div>
  );
}

function Stat({ locale, msg, value, id }: { locale: Locale; msg: Parameters<typeof t>[1]; value: string; id: string }) {
  return (
    <div>
      <dt className="text-slate-500">{t(locale, msg)}</dt>
      <dd className="font-semibold tabular-nums" data-testid={id}>
        {value}
      </dd>
    </div>
  );
}
