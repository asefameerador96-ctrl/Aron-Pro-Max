import { FinalSubmitPanel, zoneFinalOf } from "@/components/dash/final-submit-panel";
import { TeamMapPlate } from "@/components/dash/team-map-plate";
import { Tile, n, pctText, takaText } from "@/components/dash/tiles";
import { Banner } from "@/components/kit/banner";
import { ButtonLink } from "@/components/kit/button";
import { StatusChip } from "@/components/kit/chip";
import { EmptyState } from "@/components/kit/empty-state";
import { HeroCard } from "@/components/kit/hero-card";
import { Icon, type IconName } from "@/components/kit/icons";
import { Meter } from "@/components/kit/meter";
import { RankedBars } from "@/components/kit/ranked-bars";
import { SegmentedLinks } from "@/components/kit/segmented";
import { MapPanel, type MapPin } from "@/components/map-panel";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { getGeoValidation, getLoginSubmit, getSummary, getTeamLocations, listOutlets } from "@/lib/dash/server";
import { isCalendarDate } from "@/lib/dates";
import { businessDate, formatBusinessDate, formatDateTime, problemMessage, t, type Locale, type MessageKey } from "@/lib/i18n";
import { TileMeta, type StaleReason } from "@/components/reports/tile-meta";
import Link from "next/link";

function Err({ locale, code }: { locale: Locale; code: string | undefined }) {
  return (
    <p role="alert" className="flex items-center gap-2 text-body text-danger-on-container" data-testid="tile-error">
      <Icon name="error" className="text-danger" />
      {problemMessage(locale, code)}
    </p>
  );
}

/** YYYY-MM-DD minus `days` (calendar arithmetic on the business date string, no time zone involved). */
function minusDays(ymd: string, days: number): string {
  const [y, m, d] = ymd.split("-").map(Number);
  return new Date(Date.UTC(y!, m! - 1, d! - days)).toISOString().slice(0, 10);
}

const STAT = "glass-card relative flex min-h-32 flex-col gap-2 p-(--card-pad) text-primary";

// The role-aware home dashboard (F-WEB-001) on the Calm Glass layout (docs/design/screens-web.md s1): hero (net sales), stat
// cards with meters, channel and zone ranked bars, the needs-attention list and the team map plate. Everything comes from the
// stored aggregates of the server-derived scope; the page renders on load without any button press. Each card carries its
// business date, as-of stamp and reason chip. No figure is invented: a missing value renders as "no data", never a number.
export default async function DashboardPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const [sp, locale, session] = await Promise.all([searchParams, getLocale(), requireSession()]);
  const today = businessDate();
  const date = isCalendarDate(sp.date) ? sp.date : today;
  const yesterday = minusDays(date, 1);
  const token = session.at;
  const [summary, loginSubmit, geo, team, previous] = await Promise.all([
    getSummary(token, { from: date, to: date }),
    getLoginSubmit(token, date),
    getGeoValidation(token, { from: date, to: date }),
    getTeamLocations(token, date),
    getSummary(token, { from: yesterday, to: yesterday }),
  ]);

  const asOf = summary.ok ? summary.data.as_of : null;
  const k = summary.ok ? summary.data.kpis : null;
  const empty = k !== null && k.target_routes === 0;
  const reasonFor = (live: boolean): StaleReason | undefined => (empty ? "no_data" : live ? undefined : "not_live_today");
  const d = (a: string | null = asOf) => ({ businessDate: date, asOf: a, today, reason: reasonFor(date === today) });
  const meta = (a: string | null = asOf) => <TileMeta locale={locale} businessDate={date} asOf={a} today={today} reason={reasonFor(date === today)} />;

  // Delta against yesterday, only when both days have a figure (never a fake comparison).
  const prevNet = previous.ok && previous.data.kpis.target_routes > 0 ? previous.data.kpis.net_mtk : null;
  const deltaPct = k && prevNet !== null && prevNet > 0 ? ((k.net_mtk - prevNet) / prevNet) * 100 : null;

  // Outlet pins: outlets of the first zones in reach (children of the node when they are zones), plus the last synced fix of each team member.
  const zones = summary.ok ? summary.data.children.filter((c) => c.node.type === "zone").slice(0, 3) : [];
  const outletPages = await Promise.all(zones.map((z) => listOutlets(token, { zone_id: z.node.id })));
  const pins: MapPin[] = [
    ...outletPages.flatMap((p) => (p.ok ? p.data.items : [])).filter((o) => typeof o.lat === "number" && typeof o.lng === "number").map((o): MapPin => ({ id: `o${o.id}`, lat: o.lat!, lng: o.lng!, label: o.name, kind: "outlet" })),
    ...(team.ok ? team.data.items : []).filter((m) => m.last_fix).map((m): MapPin => ({ id: `u${m.user_id}`, lat: m.last_fix!.lat, lng: m.last_fix!.lng, label: m.full_name, kind: "fix" })),
  ];
  const srCount = team.ok ? team.data.items.length : 0;
  const outletCount = pins.filter((p) => p.kind === "outlet").length;

  const ranked = summary.ok ? [...summary.data.children].sort((a, b) => b.kpis.net_mtk - a.kpis.net_mtk).slice(0, 10) : [];
  const ls = loginSubmit.ok ? loginSubmit.data : null;
  const lsError = loginSubmit.ok ? undefined : loginSubmit.problem.code;
  const attention: { id: string; key: MessageKey; icon: IconName; count: number | null; href: string }[] = [
    { id: "not-logged-in", key: "dashboard.not_logged_in", icon: "user", count: ls ? ls.not_logged_in.length : null, href: "/daily-tracking" },
    { id: "not-submitted", key: "dashboard.not_submitted", icon: "clock", count: ls ? ls.logged_in_not_submitted.length : null, href: "/daily-tracking" },
    { id: "suspicious", key: "dashboard.suspicious", icon: "location", count: k ? k.suspicious_visits : null, href: "/exceptions" },
    { id: "exceptions", key: "dashboard.exceptions", icon: "flag", count: ls ? (ls.exceptions?.length ?? 0) : null, href: "/exceptions" },
  ];
  const allOk = attention.every((a) => a.count === 0);
  const sameQs = (q: Record<string, string>) => `/?${new URLSearchParams(q).toString()}`;

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-display text-primary">{t(locale, "dashboard.title")}</h1>
          <p className="text-caption text-secondary">
            <time data-testid="business-date" dateTime={date}>
              {formatBusinessDate(locale, date)}
            </time>
            {asOf ? <span> · {t(locale, "tile.as_of", { at: formatDateTime(locale, asOf) })}</span> : null}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <SegmentedLinks
            label={t(locale, "dashboard.date.label")}
            items={[
              { href: "/", label: t(locale, "dashboard.date.today"), current: date === today, testId: "date-today" },
              { href: sameQs({ date: minusDays(today, 1) }), label: t(locale, "dashboard.date.yesterday"), current: date === minusDays(today, 1), testId: "date-yesterday" },
            ]}
          />
          <details className="relative">
            <summary className="inline-flex min-h-(--ctl-h) cursor-pointer list-none items-center gap-2 rounded-full border border-divider bg-raised px-4 text-body-strong text-accent hover:bg-pressed [&::-webkit-details-marker]:hidden">
              <Icon name="calendar" size={18} />
              {t(locale, "dashboard.date.pick")}
            </summary>
            <form method="get" className="glass-pop absolute end-0 top-12 z-40 flex w-72 flex-col gap-3 p-4">
              <label className="text-label text-secondary">
                {t(locale, "dashboard.business_date")}
                <input type="date" name="date" defaultValue={date} max={today} className="mt-1 min-h-(--ctl-h) w-full rounded-chip border border-input bg-raised px-3 text-body text-primary focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus" />
              </label>
              <button type="submit" className="inline-flex min-h-(--ctl-h) items-center justify-center gap-2 rounded-full border border-divider bg-raised px-5 text-body-strong text-accent hover:bg-pressed focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus">
                <Icon name="filter" size={18} />
                {t(locale, "common.filter")}
              </button>
            </form>
          </details>
          <ButtonLink href={date === today ? "/" : sameQs({ date })} reload variant="secondary" icon="refresh" testId="refresh">
            {t(locale, "dashboard.refresh")}
          </ButtonLink>
        </div>
      </header>

      {!summary.ok ? (
        <Banner tone="danger" role="alert" testId="dashboard-error">
          {problemMessage(locale, summary.problem.code)}
        </Banner>
      ) : null}
      {empty ? <EmptyState icon="sync" title={t(locale, "dashboard.empty.title")} body={t(locale, "dashboard.empty.body")} testId="dashboard-empty" /> : null}

      {k && summary.ok ? (
        <div className="grid grid-cols-1 gap-(--gap) min-[480px]:grid-cols-2 md:grid-cols-6 lg:grid-cols-12" data-testid="dashboard-tiles">
          {/* Row A: hero (net), strike rate, outlets visited */}
          <HeroCard
            testId="tile-sales"
            valueTestId="sales-net"
            className="col-span-full md:col-span-6 lg:col-span-12 xl:col-span-6"
            label={t(locale, "dashboard.kpi.net")}
            amount={takaText(locale, k.net_mtk)}
            href="/reports/summary"
            sub={
              <>
                {t(locale, "dashboard.kpi.gross")}: {takaText(locale, k.gross_mtk)} · {t(locale, "dashboard.kpi.memos")}: {n(locale, k.active_memo_count)}
              </>
            }
            delta={
              deltaPct !== null ? (
                <StatusChip tone={deltaPct >= 0 ? "success" : "warning"} icon={deltaPct >= 0 ? "arrow-up" : "arrow-down"} testId="sales-delta">
                  {deltaPct >= 0 ? "+" : "−"}
                  {pctText(locale, Math.abs(deltaPct))} {t(locale, "dashboard.delta.vs_yesterday")}
                </StatusChip>
              ) : null
            }
            caption={meta()}
          />
          <section data-testid="tile-strike" className={`${STAT} min-[480px]:col-span-1 md:col-span-3 lg:col-span-6 xl:col-span-3`}>
            <h2 className="text-label text-secondary">{t(locale, "dashboard.kpi.strike")}</h2>
            <p className="text-display text-primary tabular-nums" data-testid="strike-rate">
              {pctText(locale, k.strike_rate_pct)}
            </p>
            <Meter pct={k.strike_rate_pct} valueText={pctText(locale, k.strike_rate_pct)} label={t(locale, "dashboard.kpi.strike")} />
            <p className="text-caption text-secondary" data-testid="strike-basis">
              {t(locale, "dashboard.kpi.strike_basis", { calls: n(locale, k.successful_calls), visited: n(locale, k.visited_outlets) })}
            </p>
            {meta()}
          </section>
          <section data-testid="tile-visits" className={`${STAT} min-[480px]:col-span-1 md:col-span-3 lg:col-span-6 xl:col-span-3`}>
            <h2 className="text-label text-secondary">{t(locale, "dashboard.kpi.outlets_visited")}</h2>
            <p className="text-display text-primary tabular-nums">
              {n(locale, k.visited_outlets)}
              <span className="text-body text-secondary">/{n(locale, k.target_outlets)}</span>
            </p>
            <Meter pct={k.target_outlets > 0 ? (k.visited_outlets / k.target_outlets) * 100 : null} valueText={`${n(locale, k.visited_outlets)}/${n(locale, k.target_outlets)}`} label={t(locale, "dashboard.kpi.outlets_visited")} />
            <p className="text-caption text-secondary">{t(locale, "dashboard.kpi.visits_basis", { visited: n(locale, k.visited_outlets), target: n(locale, k.target_outlets) })}</p>
            {meta()}
          </section>

          {/* Row B: geo valid, login and submit, final submit, needs attention */}
          <section data-testid="tile-geo" className={`${STAT} min-[480px]:col-span-1 md:col-span-3 lg:col-span-4 xl:col-span-3`}>
            <h2 className="text-label text-secondary">{t(locale, "dashboard.geo_fencing")}</h2>
            {geo.ok ? (
              <dl data-testid="geo-fencing" className="contents">
                <dt className="sr-only">{t(locale, "dashboard.geo_valid")}</dt>
                <dd className="text-display text-primary tabular-nums" data-testid="geo-valid">
                  {pctText(locale, geo.data.geo_valid_pct)}
                </dd>
                <dd>
                  <Meter pct={geo.data.geo_valid_pct} valueText={pctText(locale, geo.data.geo_valid_pct)} label={t(locale, "dashboard.geo_valid")} />
                </dd>
                <dd className="flex flex-wrap gap-1.5">
                  <StatusChip tone="warning" icon="location">
                    <span className="sr-only">{t(locale, "dashboard.force_sale")} </span>
                    <span aria-hidden="true">{t(locale, "dashboard.force_sale")} </span>
                    <span data-testid="force-sale">{pctText(locale, geo.data.force_sale_pct)}</span>
                  </StatusChip>
                  <StatusChip tone="offline" icon="devices">
                    <span aria-hidden="true">{t(locale, "dashboard.mock")} </span>
                    <span className="sr-only">{t(locale, "dashboard.mock")} </span>
                    <span data-testid="mock-pct">{pctText(locale, geo.data.mock_pct)}</span>
                  </StatusChip>
                  <StatusChip tone="danger" icon="warning">
                    <span aria-hidden="true">{t(locale, "dashboard.suspicious")} </span>
                    <span className="sr-only">{t(locale, "dashboard.suspicious")} </span>
                    <span data-testid="suspicious-pct">{pctText(locale, geo.data.suspicious_pct)}</span>
                  </StatusChip>
                </dd>
              </dl>
            ) : (
              <Err locale={locale} code={geo.problem.code} />
            )}
            {meta(geo.ok ? geo.data.as_of : null)}
          </section>
          <section data-testid="tile-loginsubmit" className={`${STAT} min-[480px]:col-span-1 md:col-span-3 lg:col-span-4 xl:col-span-3`}>
            <h2 className="text-label text-secondary">{t(locale, "dashboard.login_submit")}</h2>
            {ls ? (
              <dl className="flex flex-col gap-2" data-testid="login-submit">
                <div>
                  <dt className="text-caption text-secondary">{t(locale, "dashboard.login_pct")}</dt>
                  <dd className="text-numeral text-primary tabular-nums" data-testid="login-pct">
                    {pctText(locale, ls.kpis.login_pct)}
                  </dd>
                  <dd>
                    <Meter pct={ls.kpis.login_pct} valueText={pctText(locale, ls.kpis.login_pct)} label={t(locale, "dashboard.login_pct")} />
                  </dd>
                  <dd className="text-caption text-secondary">{t(locale, "dashboard.kpi.login_basis", { logged: n(locale, ls.kpis.logged_in_routes), target: n(locale, ls.kpis.target_routes) })}</dd>
                </div>
                <div>
                  <dt className="text-caption text-secondary">{t(locale, "finalsubmit.submit_pct")}</dt>
                  <dd className="text-numeral text-primary tabular-nums" data-testid="submit-pct-ls">
                    {pctText(locale, ls.kpis.submit_pct_of_logged_in)}
                  </dd>
                  <dd>
                    <Meter pct={ls.kpis.submit_pct_of_logged_in} valueText={pctText(locale, ls.kpis.submit_pct_of_logged_in)} label={t(locale, "finalsubmit.submit_pct")} />
                  </dd>
                  <dd className="text-caption text-secondary">{t(locale, "dashboard.kpi.submit_basis", { submitted: n(locale, ls.kpis.sales_submitted_routes), logged: n(locale, ls.kpis.logged_in_routes) })}</dd>
                </div>
              </dl>
            ) : (
              <Err locale={locale} code={lsError} />
            )}
            {meta(ls ? ls.as_of : null)}
          </section>
          <Tile id="finalsubmit" locale={locale} titleKey="dashboard.final_submit" date={d()} className="min-[480px]:col-span-1 md:col-span-3 lg:col-span-4 xl:col-span-3">
            <FinalSubmitPanel locale={locale} kpis={k} zones={summary.data.children} zoneFinal={loginSubmit.ok ? zoneFinalOf(loginSubmit.data.zones) : undefined} />
          </Tile>
          <section data-testid="tile-attention" className={`${STAT} min-[480px]:col-span-1 md:col-span-3 lg:col-span-12 xl:col-span-3`}>
            <h2 className="text-label text-secondary">{t(locale, "dashboard.attention")}</h2>
            {allOk ? (
              <p className="flex items-center gap-2 text-body-strong text-success-on-container" data-testid="attention-ok">
                <Icon name="check-circle" className="text-success" />
                {t(locale, "dashboard.attention.all_ok")}
              </p>
            ) : (
              <ul className="-mx-2 flex flex-col">
                {attention.map((a) => (
                  <li key={a.id}>
                    <Link href={a.href} className="flex min-h-12 items-center gap-3 rounded-chip px-2 text-body text-primary hover:bg-pressed focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus">
                      <Icon name={a.count === 0 ? "check-circle" : a.icon} className={a.count === 0 ? "text-success" : "text-secondary"} />
                      <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">{t(locale, a.key)}</span>
                      <span className="text-body-strong text-primary tabular-nums" data-testid={a.id}>
                        {a.count === null ? "—" : n(locale, a.count)}
                      </span>
                      <Icon name="chevron-right" size={18} className="text-secondary" />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
            <dl className="sr-only">
              <dt>{t(locale, "dashboard.submitted")}</dt>
              <dd data-testid="submitted">{ls ? n(locale, ls.submitted.length) : "—"}</dd>
            </dl>
            {meta(ls ? ls.as_of : null)}
          </section>

          {/* Row C: channels (ranked bars) and zone ranking */}
          <Tile id="channels" locale={locale} titleKey="dashboard.channels" date={d()} data className="col-span-full md:col-span-6 lg:col-span-12 xl:col-span-8">
            {summary.data.by_channel && summary.data.by_channel.length > 0 ? (
              <RankedBars
                testId="channel-list"
                label={t(locale, "dashboard.channels")}
                rows={summary.data.by_channel.map((c) => ({
                  key: c.code,
                  label: c.name ?? c.code,
                  value: Math.max(0, c.net_mtk),
                  valueText: takaText(locale, c.net_mtk),
                  sub: `${n(locale, c.successful_calls)}/${n(locale, c.memo_count)}`,
                }))}
              />
            ) : (
              <p className="text-body text-secondary">{t(locale, "dashboard.channels.empty")}</p>
            )}
          </Tile>
          <Tile id="zones" locale={locale} titleKey="dashboard.zones" date={d()} data className="col-span-full md:col-span-6 lg:col-span-12 xl:col-span-4" actions={<span className="text-caption text-secondary">{t(locale, "dashboard.zones.by_net")}</span>}>
            {ranked.length > 0 ? (
              <RankedBars label={t(locale, "dashboard.zones")} rows={ranked.map((c) => ({ key: `${c.node.type}-${c.node.id}`, label: c.node.name ?? c.node.code ?? String(c.node.id), value: Math.max(0, c.kpis.net_mtk), valueText: takaText(locale, c.kpis.net_mtk) }))} />
            ) : (
              <p className="text-body text-secondary">{t(locale, "dashboard.zones.empty")}</p>
            )}
          </Tile>

          {/* Row D: team map plate, map loads on click only */}
          <Tile id="ffgeo" locale={locale} titleKey="dashboard.ff_geo" date={d(team.ok ? team.data.as_of : null)} className="col-span-full lg:col-span-12">
            <TeamMapPlate
              summary={t(locale, "dashboard.map.summary", { srs: n(locale, srCount), outlets: n(locale, outletCount) })}
              hint={t(locale, "dashboard.map.hint")}
              showLabel={t(locale, "dashboard.map.show")}
              map={
                <MapPanel
                  pins={pins}
                  labels={{ placeholder: t(locale, "dashboard.maps.placeholder"), capped: t(locale, "dashboard.maps.capped"), failed: t(locale, "dashboard.maps.failed"), outlets: t(locale, "dashboard.maps.outlets"), fixes: t(locale, "dashboard.maps.fixes"), pins: t(locale, "dashboard.maps.pins") }}
                />
              }
            />
          </Tile>
        </div>
      ) : null}
    </div>
  );
}
