import { AutoRefresh, RetryButton } from "@/components/dash/table-actions";
import { RankTable, SrTable } from "@/components/dash/tables";
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
import { ColumnChart, Donut, Ring } from "@/components/dash/charts";
import { FilteredMap } from "@/components/dash/filtered-map";
import type { MapPin } from "@/components/map-panel";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { getDailyTracking, getGeoValidation, getLoginSubmit, getSummary, getTeamLocations, listOutlets } from "@/lib/dash/server";
import { channelLabel } from "@/lib/dash/channel";
import { drillHref } from "@/lib/dash/drill";
import { rankRows, srRows } from "@/lib/dash/rows";
import { zonesUnder } from "@/lib/dash/scope-zones";
import { formatLakhCrore, mtkToTaka, pctChange } from "@/lib/dash/lakh";
import { InfoTip } from "@/components/dash/info-tip";
import { mapsKey } from "@/lib/maps/guard";
import { HomeFilter } from "@/components/dash/home-filter";
import { ScopeFilter } from "@/components/reports/scope-filter";
import { GEO_LEVELS, boundGeo, geoFromParams } from "@/lib/reports/query";
import { isCalendarDate } from "@/lib/dates";
import { businessDate, formatBusinessDate, formatDateTime, problemMessage, t, type Locale, type MessageKey } from "@/lib/i18n";
import { TileMeta, type StaleReason } from "@/components/reports/tile-meta";
import type { LakhWords } from "@/lib/dash/lakh";
import Link from "next/link";
import { redirect } from "next/navigation";

function Err({ locale, code }: { locale: Locale; code: string | undefined }) {
  return (
    <p role="alert" className="flex items-center gap-2 text-body text-danger-on-container" data-testid="tile-error">
      <Icon name="error" className="text-danger" />
      {problemMessage(locale, code)}
      <RetryButton msg={t(locale, "table.retry")} />
    </p>
  );
}

/** "Details" link of a card to its drill-down (hidden in print). */
function DrillLink({ href, msg, testId }: { href: string; msg: string; testId: string }) {
  return (
    <Link href={href} data-testid={testId} className="no-print inline-flex min-h-(--ctl-h) items-center gap-1 text-label text-accent hover:underline focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
      <span className="@max-[17rem]/tile:sr-only">{msg}</span>
      <Icon name="chevron-right" size={16} />
    </Link>
  );
}

/** Card heading row with the drill-down link (same look as the Tile heading). */
function SectionHead({ msg, link }: { msg: string; link: React.ReactNode }) {
  return (
    <div className="flex min-h-[3.25rem] items-start justify-between gap-2 border-b border-accent-graphic/40 pb-2">
      <h2 className="min-w-0 flex-1 line-clamp-2 text-body-strong text-accent" title={msg}>{msg}</h2>
      <div className="shrink-0">{link}</div>
    </div>
  );
}

/** YYYY-MM-DD minus `days` (calendar arithmetic on the business date string, no time zone involved). */
function minusDays(ymd: string, days: number): string {
  const [y, m, d] = ymd.split("-").map(Number);
  return new Date(Date.UTC(y!, m! - 1, d! - days)).toISOString().slice(0, 10);
}

const STAT = "glass-data @container relative flex min-h-32 flex-col gap-2 p-(--card-pad) text-primary";

// The role-aware home dashboard (F-WEB-001) on the Calm Glass layout (docs/design/screens-web.md s1): hero (net sales), stat
// cards with meters, channel and zone ranked bars, the needs-attention list and the team map plate. Everything comes from the
// stored aggregates of the server-derived scope; the page renders on load without any button press. Each card carries its
// business date, as-of stamp and reason chip. No figure is invented: a missing value renders as "no data", never a number.
export default async function DashboardPage({ searchParams }: { searchParams: Promise<Record<string, string | undefined>> }) {
  const [sp, locale, session] = await Promise.all([searchParams, getLocale(), requireSession()]);
  if (session.user.role === "SUPPORT") redirect("/sync-health"); // SUPPORT has no dashboard home (docs/24 s8.5: sync health only)
  const today = businessDate();
  const date = isCalendarDate(sp.date) ? sp.date : today;
  const yesterday = minusDays(date, 1);
  const lastWeek = minusDays(date, 7);
  const brandMode = sp.bm === "memo" ? "memo" : "value";
  const token = session.at;
  // Scope narrowing from the Filter (F-WEB-041): the deepest chosen level; levels fixed by the token and ids outside the
  // caller's own nodes are dropped, and the server re-checks reach (403 outside it).
  const picked = boundGeo(session.scope, geoFromParams(sp)) ?? {};
  const deepest = [...GEO_LEVELS].reverse().find((l) => picked[l]?.[0] !== undefined);
  const node = deepest ? { level: deepest, node_id: picked[deepest]![0]! } : {};
  const filtered = deepest !== undefined;
  const role = session.user.role;
  const srRole = role === "TSO" || role === "AMO"; // TSO and AMO get the SR table, everyone else the TSO / zone ranking
  const [summary, loginSubmit, geo, team, previous, weekAgo, tracking, trackZones] = await Promise.all([
    getSummary(token, { from: date, to: date, ...node }),
    getLoginSubmit(token, date, node),
    getGeoValidation(token, { from: date, to: date, ...node }),
    getTeamLocations(token, date, node.level === "zone" ? node.node_id : undefined),
    getSummary(token, { from: yesterday, to: yesterday, ...node }),
    getSummary(token, { from: lastWeek, to: lastWeek, ...node }),
    srRole ? getDailyTracking(token, date) : Promise.resolve(null),
    srRole ? zonesUnder(token, node, date) : Promise.resolve(null),
  ]);

  const asOf = summary.ok ? summary.data.as_of : null;
  const k = summary.ok ? summary.data.kpis : null;
  const empty = k !== null && k.target_routes === 0;
  const reasonFor = (live: boolean): StaleReason | undefined => (empty ? "no_data" : live ? undefined : "not_live_today");
  const d = (a: string | null = asOf) => ({ businessDate: date, asOf: a, today, reason: reasonFor(date === today) });
  const meta = (a: string | null = asOf) => <TileMeta locale={locale} businessDate={date} asOf={a} today={today} reason={reasonFor(date === today)} quiet />;

  // Delta against the previous day, only when both days have a figure (never a fake comparison); the label says "vs yesterday"
  // only when the page shows today, otherwise "vs previous day".
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
  const lw: LakhWords = { lakh: t(locale, "unit.lakh"), crore: t(locale, "unit.crore") };
  const CATS = ["cigarette", "bidi", "lighter", "match"];
  const catRank = (c: string) => (CATS.includes(c) ? CATS.indexOf(c) : CATS.length);
  const cats = summary.ok ? [...summary.data.by_category].sort((a, b) => catRank(a.category_code) - catRank(b.category_code) || a.category_code.localeCompare(b.category_code)) : [];
  const catTotalNet = cats.reduce((s, c) => s + Math.max(0, c.net_mtk), 0);
  const prevCats = weekAgo.ok && weekAgo.data.kpis.target_routes > 0 ? weekAgo.data.by_category : [];
  const catTitle = (code: string): string => (CATS.includes(code) ? t(locale, `dashboard.cat.${code}` as MessageKey) : t(locale, "dashboard.cat.other", { name: code }));
  const unitWord = (u: string): string => t(locale, `unit.${u}` as MessageKey);
  const channels = summary.ok ? (summary.data.by_channel ?? []) : [];
  const channelNetTotal = channels.reduce((s2, c) => s2 + Math.max(0, c.net_mtk), 0);
  const callsTotal = channels.reduce((s2, c) => s2 + c.successful_calls, 0);
  const brands = summary.ok ? [...(summary.data.by_brand ?? [])].sort((a, b) => (brandMode === "memo" ? b.memo_count - a.memo_count : b.net_mtk - a.net_mtk)).slice(0, 12) : [];
  const brandRows = brands.map((b) => ({ key: b.code, label: b.name ?? b.code, value: brandMode === "memo" ? b.memo_count : Math.max(0, b.net_mtk), valueText: brandMode === "memo" ? n(locale, b.memo_count) : formatLakhCrore(locale, mtkToTaka(b.net_mtk), lw) }));
  const cprPct = k && k.target_outlets > 0 ? (k.successful_calls / k.target_outlets) * 100 : null;
  const geoQs = Object.fromEntries(GEO_LEVELS.filter((l) => picked[l]?.[0] !== undefined).map((l) => [l, String(picked[l]![0])]));
  const dl = (kind: Parameters<typeof drillHref>[0], extra: Record<string, string> = {}) => drillHref(kind, { ...(date === today ? {} : { date }), ...geoQs, ...extra });
  const detailsMsg = t(locale, "drill.open");
  const stamp = { asOf, businessDate: date, today };
  const sr = tracking?.ok ? srRows(tracking.data.items.filter((r) => trackZones === null || trackZones === undefined || trackZones.has(r.zone_id))) : [];
  const sameQs = (q: Record<string, string>) => {
    const qs = new URLSearchParams({ ...q, ...geoQs }).toString();
    return qs ? `/overview?${qs}` : "/overview";
  };

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <h1 className="sr-only">{t(locale, "dashboard.title")}</h1>
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
              { href: sameQs({}), label: t(locale, "dashboard.date.today"), current: date === today, testId: "date-today" },
              { href: sameQs({ date: minusDays(today, 1) }), label: t(locale, "dashboard.date.yesterday"), current: date === minusDays(today, 1), testId: "date-yesterday" },
            ]}
          />
          <HomeFilter msg={t(locale, "common.filter")} applyMsg={t(locale, "common.filter")} resetMsg={t(locale, "dashboard.filter.reset")} resetHref={date === today ? "/overview" : sameQs({ date })} active={filtered}>
            <label className="text-label text-secondary">
              {t(locale, "dashboard.business_date")}
              <input type="date" name="date" defaultValue={date} max={today} className="mt-1 min-h-(--ctl-h) w-full rounded-chip border border-input bg-raised px-3 text-body text-primary focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus" />
            </label>
            <input type="hidden" name="bm" value={brandMode} />
            <ScopeFilter token={token} scope={session.scope} locale={locale} params={sp} />
          </HomeFilter>
          <ButtonLink href={date === today && !filtered ? "/overview" : sameQs(date === today ? {} : { date })} reload variant="secondary" icon="refresh" testId="refresh">
            {t(locale, "dashboard.refresh")}
          </ButtonLink>
        </div>
      </header>

      {!summary.ok ? (
        <Banner tone="danger" role="alert" testId="dashboard-error">
          <span className="flex flex-wrap items-center gap-3">
            {problemMessage(locale, summary.problem.code)}
            <RetryButton msg={t(locale, "table.retry")} />
          </span>
        </Banner>
      ) : null}
      {empty ? <EmptyState icon="sync" title={t(locale, "dashboard.empty.title")} body={t(locale, "dashboard.empty.body")} testId="dashboard-empty" /> : null}

      {k && summary.ok ? (
        <div className="grid grid-cols-1 gap-(--gap) min-[480px]:grid-cols-2 md:grid-cols-6 lg:grid-cols-12" data-testid="dashboard-tiles">
          {/* Row 0: sales by category (current portal row 1). The ring is the category's share of net sales; the chip is the
              quantity against the same weekday last week. Target achievement is deferred (docs/27). */}
          {cats.length > 0 ? (
            cats.slice(0, 4).map((c) => {
              const prev = prevCats.find((p) => p.category_code === c.category_code);
              const change = pctChange(c.qty_base, prev?.qty_base);
              const share = catTotalNet > 0 ? (Math.max(0, c.net_mtk) / catTotalNet) * 100 : null;
              return (
                <Tile tipAtBottom key={c.category_code} id={`cat-${c.category_code}`} locale={locale} titleKey="dashboard.cat.heading" heading={catTitle(c.category_code)} date={d()} data actions={<DrillLink href={dl("category", { code: c.category_code })} msg={detailsMsg} testId={`drill-cat-${c.category_code}`} />} tip={<InfoTip msg={t(locale, "dashboard.cat.info")} openMsg={t(locale, "dashboard.info.open")} />} className="col-span-full min-[480px]:col-span-1 md:col-span-3 lg:col-span-6 xl:col-span-3">
                  <div className="flex items-center justify-between gap-3">
                    <div className="min-w-0 flex-1">
                      <p className="fit-number whitespace-nowrap text-title block text-primary tabular-nums" data-testid={`cat-qty-${c.category_code}`}>
                        {formatLakhCrore(locale, c.qty_base, lw)}
                      </p>
                      <p className="whitespace-nowrap text-caption font-semibold text-cyan">
                        {t(locale, "dashboard.cat.total")} · {unitWord(c.base_unit)}
                      </p>
                      <p className="text-caption text-secondary tabular-nums" data-testid={`cat-net-${c.category_code}`}>
                        {t(locale, "dashboard.cat.net", { amount: formatLakhCrore(locale, mtkToTaka(c.net_mtk), lw) })}
                      </p>
                    </div>
                    <div className="flex shrink-0 flex-col items-center gap-1">
                      <Ring pct={share} valueText={pctText(locale, share)} label={t(locale, "dashboard.cat.share")} size={76} gradient hue={CATS.includes(c.category_code) ? CATS.indexOf(c.category_code) : 4} testId={`cat-ring-${c.category_code}`} />
                      <span className="max-w-20 text-center text-caption text-secondary">{t(locale, "dashboard.cat.share")}</span>
                    </div>
                  </div>
                  <div className="mt-2 pe-10">
                    {change !== null ? (
                  <StatusChip tone={Math.abs(change) < 0.05 ? "quiet" : change > 0 ? "success" : "warning"} icon={Math.abs(change) < 0.05 ? null : change > 0 ? "arrow-up" : "arrow-down"} testId={`cat-delta-${c.category_code}`}>
                        {Math.abs(change) < 0.05 ? "" : change > 0 ? "+" : "−"}
                    {pctText(locale, Math.abs(change))} {t(locale, "dashboard.cat.vs_week")}
                  </StatusChip>
                ) : null}
                  </div>
                </Tile>
              );
            })
          ) : (
            <p className="col-span-full text-body text-secondary" data-testid="cat-empty">
              {t(locale, "dashboard.cat.empty")}
            </p>
          )}

          <section data-testid="tile-visits" className={`${STAT} min-[480px]:col-span-1 md:col-span-3 lg:col-span-6 xl:col-span-3`}>
            <SectionHead msg={t(locale, "dashboard.cpr")} link={<DrillLink href={dl("calls")} msg={detailsMsg} testId="drill-calls" />} />
            <div className="flex items-center gap-3" data-testid="cpr">
              <dl className="min-w-0 flex-1 whitespace-nowrap text-caption text-secondary">
                <dt>{t(locale, "dashboard.cpr.planned")}</dt>
                <dd className="text-body-strong text-primary tabular-nums" data-testid="cpr-planned">{formatLakhCrore(locale, k.target_outlets, lw)}</dd>
                <dt>{t(locale, "dashboard.cpr.calls")}</dt>
                <dd className="text-body-strong text-primary tabular-nums" data-testid="cpr-calls">{formatLakhCrore(locale, k.successful_calls, lw)}</dd>
              </dl>
              <Ring pct={cprPct} valueText={pctText(locale, cprPct)} label={t(locale, "dashboard.cpr.basis")} size={84} hue={4} testId="cpr-ring" />
            </div>
            <h3 className="text-label text-secondary">{t(locale, "dashboard.kpi.outlets_visited")}</h3>
            <p className="fit-number whitespace-nowrap text-numeral text-primary tabular-nums">
              {n(locale, k.visited_outlets)}
              <span className="text-body text-secondary">/{n(locale, k.target_outlets)}</span>
            </p>
            <Meter pct={k.target_outlets > 0 ? (k.visited_outlets / k.target_outlets) * 100 : null} valueText={`${n(locale, k.visited_outlets)}/${n(locale, k.target_outlets)}`} label={t(locale, "dashboard.kpi.outlets_visited")} />
            {meta()}
          </section>

          <Tile id="channels" locale={locale} titleKey="dashboard.channels.calls" date={d()} data actions={<DrillLink href={dl("channel")} msg={detailsMsg} testId="drill-channels" />} className="col-span-full min-[480px]:col-span-1 md:col-span-3 lg:col-span-6 xl:col-span-3">
            {channels.length > 0 ? (
              <Donut
                testId="channel-calls-donut"
                radial
                legendFirst
                label={t(locale, "dashboard.channels.calls")}
                centerText={formatLakhCrore(locale, callsTotal, lw)}
                slices={channels.map((c) => ({ key: c.code, label: channelLabel(locale, c.code, c.name), value: c.successful_calls, valueText: formatLakhCrore(locale, c.successful_calls, lw), shareText: callsTotal > 0 ? pctText(locale, (c.successful_calls / callsTotal) * 100) : "—", href: dl("channel", { code: c.code }) }))}
                size={92}
              />
            ) : (
              <p className="text-body text-secondary">{t(locale, "dashboard.channels.empty")}</p>
            )}
          </Tile>
          <Tile id="finalsubmit" locale={locale} titleKey="dashboard.final_submit" date={d()} className="min-[480px]:col-span-1 md:col-span-3 lg:col-span-6 xl:col-span-3">
            <FinalSubmitPanel locale={locale} kpis={k} zones={summary.data.children} zoneFinal={loginSubmit.ok ? zoneFinalOf(loginSubmit.data.zones) : undefined} />
          </Tile>
          <section data-testid="tile-loginsubmit" className={`${STAT} min-[480px]:col-span-1 md:col-span-3 lg:col-span-6 xl:col-span-3`}>
            <SectionHead msg={t(locale, "dashboard.login_submit")} link={<DrillLink href={dl("login")} msg={detailsMsg} testId="drill-login" />} />
            {ls ? (
              <dl className="flex flex-col gap-2" data-testid="login-submit">
                <div>
                  <dt className="flex items-center gap-1 text-caption text-secondary"><Icon name="download" size={16} className="text-accent" />{t(locale, "dashboard.login_pct")}</dt>
                  <dd className="fit-number whitespace-nowrap text-numeral text-primary tabular-nums" data-testid="login-pct">
                    {pctText(locale, ls.kpis.login_pct)}
                  </dd>
                  <dd>
                    <Meter pct={ls.kpis.login_pct} valueText={pctText(locale, ls.kpis.login_pct)} label={t(locale, "dashboard.login_pct")} />
                  </dd>
                  <dd className="text-caption text-secondary">{t(locale, "dashboard.kpi.login_basis", { logged: n(locale, ls.kpis.logged_in_routes), target: n(locale, ls.kpis.target_routes) })}</dd>
                </div>
                <div>
                  <dt className="flex items-center gap-1 text-caption text-secondary"><Icon name="upload" size={16} className="text-accent" />{t(locale, "dashboard.submit_status")}</dt>
                  <dd className="fit-number whitespace-nowrap text-numeral text-primary tabular-nums" data-testid="submit-pct-ls">
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
          {/* Row C: channels (ranked bars, span 4) and the zone ranking (span 8, screens-web s1) */}
          <Tile id="channels-std" locale={locale} titleKey="dashboard.channels.net" date={d()} data actions={<DrillLink href={dl("channel")} msg={detailsMsg} testId="drill-channels-std" />} className="col-span-full md:col-span-6 lg:col-span-12 xl:col-span-4">
            {channels.length > 0 ? (
              <div className="space-y-4">
              <Donut pie legendFirst
                size={96}
                testId="channel-std-donut"
                label={t(locale, "dashboard.channels.net")}
                slices={channels.map((c) => ({ key: c.code, label: channelLabel(locale, c.code, c.name), value: Math.max(0, c.net_mtk), valueText: formatLakhCrore(locale, mtkToTaka(c.net_mtk), lw), shareText: channelNetTotal > 0 ? pctText(locale, (Math.max(0, c.net_mtk) / channelNetTotal) * 100) : "—", href: dl("channel", { code: c.code }) }))}
              />
              </div>
            ) : (
              <p className="text-body text-secondary">{t(locale, "dashboard.channels.empty")}</p>
            )}
          </Tile>
          {/* Row C2: brand breakdown with the STD (net sales) / Memo (memo count) toggle of the current portal */}
          <Tile
            id="brands"
            locale={locale}
            titleKey="dashboard.brand.heading"
            date={d()}
            data
            className="col-span-full lg:col-span-12 xl:col-span-8"
            titleSlot={
              <SegmentedLinks
                label={t(locale, "dashboard.brand.mode")}
                items={[
                  { href: sameQs({ ...(date === today ? {} : { date }), bm: "value" }), label: t(locale, "dashboard.brand.value"), current: brandMode === "value", testId: "brand-mode-value" },
                  { href: sameQs({ ...(date === today ? {} : { date }), bm: "memo" }), label: t(locale, "dashboard.brand.memo"), current: brandMode === "memo", testId: "brand-mode-memo" },
                ]}
              />
            }
            actions={
              <Link href="/reports/std-memo" aria-label={t(locale, "dashboard.brand.gear")} title={t(locale, "dashboard.brand.gear")} data-testid="brand-gear" className="no-print inline-flex size-(--ctl-h) items-center justify-center rounded-full text-secondary hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
                <Icon name="config" size={20} />
              </Link>
            }
          >
            {brands.length >= 1 ? (
              <ColumnChart testId="brand-chart" label={t(locale, "dashboard.brand.heading")} rows={brandRows} axisText={(v) => (brandMode === "memo" ? n(locale, v) : formatLakhCrore(locale, mtkToTaka(v), lw))} />
            ) : brands.length > 0 ? (
              // Two or three brands read better as ranked bars than as a few wide columns (design review).
              <RankedBars testId="brand-chart" label={t(locale, "dashboard.brand.heading")} rows={brandRows} />
            ) : (
              <p className="text-body text-secondary">{t(locale, "dashboard.brand.empty")}</p>
            )}
          </Tile>

          {/* Row B: geo valid, login and submit, final submit, needs attention */}
          <section data-testid="tile-geo" className={`${STAT} min-[480px]:col-span-1 md:col-span-6 lg:col-span-12 xl:col-span-4`}>
            <h2 className="border-b border-accent-graphic/40 pb-2 text-body-strong text-accent">{t(locale, "dashboard.geo_fencing")}</h2>
            {geo.ok ? (
              <dl data-testid="geo-fencing" className="contents">
                <dt className="sr-only">{t(locale, "dashboard.geo_valid")}</dt>
                <dd className="flex items-center justify-between gap-3">
                  <span className="min-w-0 flex-1 text-caption text-secondary">
                    {/* The ring prints the percent; the side shows the server's counts behind it (geo_valid_visits of visits). */}
                    {t(locale, "dashboard.geo.validated")}
                    <span className="block text-body-strong text-primary tabular-nums" data-testid="geo-visits">{t(locale, "dashboard.geo.validated_of", { n: n(locale, geo.data.geo_valid_visits), m: n(locale, geo.data.visits) })}</span>
                  </span>
                  <Ring pct={geo.data.geo_valid_pct} valueText={pctText(locale, geo.data.geo_valid_pct)} label={t(locale, "dashboard.geo_valid")} size={104} hue={4} testId="geo-ring" />
                </dd>
                <dd className="flex flex-wrap gap-1.5">
                  <StatusChip tone="warning" icon="location">
                    <span className="sr-only">{t(locale, "dashboard.force_sale")} </span>
                    <span aria-hidden="true">{t(locale, "dashboard.force_sale")} </span>
                    <span data-testid="force-sale">{pctText(locale, geo.data.force_sale_pct)}</span>
                  </StatusChip>
                  <StatusChip tone="warning" icon="devices">
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
          {/* Row D: team map plate, map loads on click only */}
          <Tile id="ffgeo" locale={locale} titleKey="dashboard.ff_geo" date={d(team.ok ? team.data.as_of : null)} className="col-span-full lg:col-span-12 xl:col-span-8">
            <TeamMapPlate
              autoLoad={mapsKey() !== null}
              summary={t(locale, "dashboard.map.summary", { srs: n(locale, srCount), outlets: n(locale, outletCount) })}
              hint={t(locale, "dashboard.map.hint")}
              showLabel={t(locale, "dashboard.map.show")}
              empty={pins.length === 0 ? t(locale, "dashboard.map.empty") : undefined}
              map={
                <FilteredMap
                  filterMsg={t(locale, "dashboard.map.filter")}
                  allMsg={t(locale, "dashboard.map.filter.all")}
                  outletsMsg={t(locale, "dashboard.map.filter.outlets")}
                  fixesMsg={t(locale, "dashboard.map.filter.fixes")}
                  pins={pins}
                  labels={{ placeholder: t(locale, "dashboard.maps.placeholder"), capped: t(locale, "dashboard.maps.capped"), failed: t(locale, "dashboard.maps.failed"), outlets: t(locale, "dashboard.maps.outlets"), fixes: t(locale, "dashboard.maps.fixes"), pins: t(locale, "dashboard.maps.pins") }}
                />
              }
            />
          </Tile>
          {/* More: net sales hero, strike rate, needs attention and the zone ranking */}
          {/* Row A: hero (net), strike rate, outlets visited */}
          <HeroCard
            testId="tile-sales"
            valueTestId="sales-net"
            plain
            className="col-span-full md:col-span-6 lg:col-span-12 xl:col-span-6"
            label={t(locale, "dashboard.kpi.net")}
            amount={formatLakhCrore(locale, mtkToTaka(k.net_mtk), lw)}
            href="/reports/summary"
            sub={
              <>
                {t(locale, "dashboard.kpi.gross")}: {formatLakhCrore(locale, mtkToTaka(k.gross_mtk), lw)} · {t(locale, "dashboard.kpi.memos")}: {n(locale, k.active_memo_count)}
              </>
            }
            delta={
              deltaPct !== null ? (
                <StatusChip tone={Math.abs(deltaPct) < 0.05 ? "quiet" : deltaPct > 0 ? "success" : "warning"} icon={Math.abs(deltaPct) < 0.05 ? null : deltaPct > 0 ? "arrow-up" : "arrow-down"} testId="sales-delta">
                  {Math.abs(deltaPct) < 0.05 ? "" : deltaPct > 0 ? "+" : "−"}
                  {pctText(locale, Math.abs(deltaPct))} {t(locale, date === today ? "dashboard.delta.vs_yesterday" : "dashboard.delta.vs_previous_day")}
                </StatusChip>
              ) : null
            }
            caption={meta()}
          />
          <section data-testid="tile-strike" className={`${STAT} min-[480px]:col-span-1 md:col-span-3 lg:col-span-6 xl:col-span-3`}>
            <SectionHead msg={t(locale, "dashboard.kpi.strike")} link={<DrillLink href={dl("calls")} msg={detailsMsg} testId="drill-strike" />} />
            <p className="fit-number whitespace-nowrap text-display text-primary tabular-nums" data-testid="strike-rate">
              {pctText(locale, k.strike_rate_pct)}
            </p>
            <Meter pct={k.strike_rate_pct} valueText={pctText(locale, k.strike_rate_pct)} label={t(locale, "dashboard.kpi.strike")} />
            <p className="text-caption text-secondary" data-testid="strike-basis">
              {t(locale, "dashboard.kpi.strike_basis", { calls: n(locale, k.successful_calls), visited: n(locale, k.visited_outlets) })}
            </p>
            {meta()}
          </section>
          <section data-testid="tile-attention" className={`${STAT} min-[480px]:col-span-1 md:col-span-3 lg:col-span-12 xl:col-span-3`}>
            <h2 className="border-b border-accent-graphic/40 pb-2 text-body-strong text-accent">{t(locale, "dashboard.attention")}</h2>
            {allOk ? (
              <p className="flex items-center gap-2 text-body-strong text-success-on-container" data-testid="attention-ok">
                <Icon name="check-circle" className="text-success" />
                {t(locale, "dashboard.attention.all_ok")}
              </p>
            ) : (
              <ul className="-mx-2 flex flex-col">
                {attention.map((a) => (
                  <li key={a.id}>
                    <Link href={a.href} className="flex min-h-12 items-center gap-3 rounded-chip px-2 text-body text-primary hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
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

          <Tile id="zones" locale={locale} titleKey="dashboard.zones" date={d()} data className={`col-span-full ${srRole ? "" : "hidden"}`} actions={<span className="text-caption text-secondary">{t(locale, "dashboard.zones.by_net")}</span>}>
            {ranked.length > 0 ? (
              <RankedBars label={t(locale, "dashboard.zones")} rows={ranked.map((c) => ({ key: `${c.node.type}-${c.node.id}`, label: c.node.name ?? c.node.code ?? String(c.node.id), value: Math.max(0, c.kpis.net_mtk), valueText: takaText(locale, c.kpis.net_mtk) }))} />
            ) : (
              <p className="text-body text-secondary">{t(locale, "dashboard.zones.empty")}</p>
            )}
          </Tile>


          {/* Role extras: TSO and AMO see their SRs, DMO, HQ and the rest see the TSO / zone ranking */}
          <div className="col-span-full" data-testid="role-extras">
            {srRole ? (
              <SrTable locale={locale} stamp={{ ...stamp, asOf: tracking?.ok ? tracking.data.as_of : asOf }} rows={sr} error={tracking && !tracking.ok ? tracking.problem.code : undefined} note={t(locale, "home.extras.sr_note")} />
            ) : (
              <RankTable locale={locale} stamp={stamp} rows={rankRows(summary.data.children)} note={t(locale, "home.extras.rank_note")} />
            )}
          </div>
        </div>
      ) : null}
      <AutoRefresh />
      <p className="no-print text-caption text-secondary" data-testid="auto-refresh-note">
        {t(locale, "dashboard.auto_refresh")}
      </p>
    </div>
  );
}
