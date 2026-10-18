import type { Schemas } from "@/contract/types";
import { notFound } from "next/navigation";
import { CallsTable, CategoryRouteTable, type Stamp } from "@/components/dash/tables";
import { DataTable, type Col, type TRow } from "@/components/dash/data-table";
import { AutoRefresh } from "@/components/dash/table-actions";
import { n, takaText } from "@/components/dash/tiles";
import { ButtonLink } from "@/components/kit/button";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { csvInt, csvTaka } from "@/lib/dash/csv";
import { isDrillKind } from "@/lib/dash/drill";
import { callsRows, categoryRoutes, type TrackingRow } from "@/lib/dash/rows";
import { zonesUnder } from "@/lib/dash/scope-zones";
import { getDailyTracking, getLoginSubmit, getSummary } from "@/lib/dash/server";
import { isCalendarDate } from "@/lib/dates";
import { GEO_LEVELS, boundGeo, geoFromParams } from "@/lib/reports/query";
import { businessDate, formatBusinessDate, formatDateTime, t, type MessageKey } from "@/lib/i18n";

const CATS = ["cigarette", "bidi", "lighter", "match"];

// Drill-downs from the home cards (F-WEB-001): one page per card family, the business date and the Filter's scope ids in the
// URL. Every table has CSV and print. Rows come from the same server reads as the home, narrowed to the picked node.
export default async function DrillPage({ params, searchParams }: { params: Promise<{ kind: string }>; searchParams: Promise<Record<string, string | undefined>> }) {
  const [{ kind }, sp, locale, session] = await Promise.all([params, searchParams, getLocale(), requireSession()]);
  if (!isDrillKind(kind)) notFound();
  const today = businessDate();
  const date = isCalendarDate(sp.date) ? sp.date : today;
  const token = session.at;
  const picked = boundGeo(session.scope, geoFromParams(sp)) ?? {};
  const deepest = [...GEO_LEVELS].reverse().find((l) => picked[l]?.[0] !== undefined);
  const node = deepest ? { level: deepest, node_id: picked[deepest]![0]! } : {};
  const backQs = new URLSearchParams({ ...(date === today ? {} : { date }), ...Object.fromEntries(GEO_LEVELS.filter((l) => picked[l]?.[0] !== undefined).map((l) => [l, String(picked[l]![0])])) }).toString();

  const [tracking, summary, ls, zones] = await Promise.all([
    getDailyTracking(token, date), // also gives the SR names for the login lists
    kind === "category" || kind === "channel" ? getSummary(token, { from: date, to: date, ...node }) : Promise.resolve(null),
    kind === "login" ? getLoginSubmit(token, date, node) : Promise.resolve(null),
    zonesUnder(token, node, date),
  ]);
  const rows: TrackingRow[] = tracking?.ok ? tracking.data.items.filter((r) => zones === null || zones.has(r.zone_id)) : [];
  const trackErr = tracking && !tracking.ok ? tracking.problem.code : undefined;
  const stamp = (asOf: string | null): Stamp => ({ asOf, businessDate: date, today });
  const trackStamp = stamp(tracking?.ok ? tracking.data.as_of : null);
  const nameOf = new Map(rows.map((r) => [r.route_id, r.user_name ?? null]));

  let title = "";
  let body: React.ReactNode = null;
  if (kind === "category") {
    const code = sp.code ?? "";
    const name = CATS.includes(code) ? t(locale, `dashboard.cat.${code}` as MessageKey) : t(locale, "dashboard.cat.other", { name: code });
    title = t(locale, "drill.title.category", { name });
    const brands = summary?.ok ? (summary.data.by_brand ?? []) : [];
    const brandCols: Col[] = [{ header: t(locale, "table.col.brand") }, { header: t(locale, "table.col.calls"), numeric: true }, { header: t(locale, "table.col.memos"), numeric: true }, { header: t(locale, "table.col.sales"), numeric: true }];
    const brandRows: TRow[] = [...brands].sort((a, b) => b.net_mtk - a.net_mtk).map((b) => ({ key: b.code, cells: [b.name ?? b.code, n(locale, b.successful_calls), n(locale, b.memo_count), takaText(locale, b.net_mtk)], csv: [b.name ?? b.code, csvInt(locale, b.successful_calls), csvInt(locale, b.memo_count), csvTaka(locale, b.net_mtk)] }));
    const cat = categoryRoutes(rows, code);
    body = (
      <>
        <CategoryRouteTable locale={locale} stamp={trackStamp} rows={cat} heading={t(locale, "drill.cat.routes", { name })} error={trackErr} />
        <DataTable id="cat-brands" locale={locale} heading={t(locale, "drill.cat.brands")} columns={brandCols} rows={brandRows} csvName="brands" {...stamp(summary?.ok ? summary.data.as_of : null)} error={summary && !summary.ok ? summary.problem.code : undefined} note={t(locale, "drill.cat.brands_note")} />
      </>
    );
  } else if (kind === "channel") {
    title = t(locale, "drill.title.channel");
    const channels = summary?.ok ? (summary.data.by_channel ?? []) : [];
    const chosen = sp.code;
    const cols: Col[] = [{ header: t(locale, "table.col.channel") }, { header: t(locale, "table.col.calls"), numeric: true }, { header: t(locale, "table.col.memos"), numeric: true }, { header: t(locale, "table.col.sales"), numeric: true }];
    const chRows: TRow[] = [...channels]
      .sort((a, b) => (a.code === chosen ? -1 : b.code === chosen ? 1 : b.successful_calls - a.successful_calls))
      .map((c) => ({ key: c.code, cells: [c.name ?? c.code, n(locale, c.successful_calls), n(locale, c.memo_count), takaText(locale, c.net_mtk)], csv: [c.name ?? c.code, csvInt(locale, c.successful_calls), csvInt(locale, c.memo_count), csvTaka(locale, c.net_mtk)] }));
    body = (
      <>
        <DataTable id="channels" locale={locale} heading={t(locale, "drill.channel.title")} columns={cols} rows={chRows} csvName="channels" {...stamp(summary?.ok ? summary.data.as_of : null)} error={summary && !summary.ok ? summary.problem.code : undefined} />
        <CallsTable locale={locale} stamp={trackStamp} rows={callsRows(rows)} heading={t(locale, "drill.channel.routes")} note={t(locale, "drill.channel.routes_note")} error={trackErr} />
      </>
    );
  } else if (kind === "calls") {
    title = t(locale, "drill.title.calls");
    body = <CallsTable locale={locale} stamp={trackStamp} rows={callsRows(rows)} error={trackErr} />;
  } else {
    title = t(locale, "drill.title.login");
    const cols: Col[] = [{ header: t(locale, "table.col.route") }, { header: t(locale, "table.col.sr") }, { header: t(locale, "table.col.logged_at") }, { header: t(locale, "table.col.submitted_at") }];
    const mk = (list: Schemas["RouteDayState"][]): TRow[] =>
      list.map((r) => {
        const sr = nameOf.get(r.route_id) ?? t(locale, "table.unassigned");
        const li = r.logged_in_at ? formatDateTime(locale, r.logged_in_at) : "—";
        const su = r.sales_submitted_at ? formatDateTime(locale, r.sales_submitted_at) : "—";
        return { key: String(r.route_id), cells: [r.route_name ?? r.route_code ?? String(r.route_id), sr, li, su] };
      });
    const err = ls && !ls.ok ? ls.problem.code : undefined;
    const st = stamp(ls?.ok ? ls.data.as_of : null);
    body = (
      <>
        <DataTable id="not-logged-in" locale={locale} heading={t(locale, "drill.login.title")} columns={cols} rows={ls?.ok ? mk(ls.data.not_logged_in) : []} csvName="not-logged-in" {...st} error={err} note={t(locale, "drill.login.note")} />
        <DataTable id="not-uploaded" locale={locale} heading={t(locale, "drill.login.not_uploaded")} columns={cols} rows={ls?.ok ? mk(ls.data.logged_in_not_submitted) : []} csvName="not-uploaded" {...st} error={err} />
      </>
    );
  }

  return (
    <div className="space-y-6" data-testid={`drill-${kind}`}>
      <AutoRefresh />
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-display text-primary">{title}</h1>
          <p className="text-caption text-secondary">
            <time dateTime={date}>{formatBusinessDate(locale, date)}</time>
          </p>
        </div>
        <span className="no-print">
          <ButtonLink href={backQs ? `/?${backQs}` : "/"} variant="secondary" icon="chevron-left" testId="drill-back">
            {t(locale, "drill.back")}
          </ButtonLink>
        </span>
      </header>
      <div className="space-y-(--gap)">{body}</div>
    </div>
  );
}
