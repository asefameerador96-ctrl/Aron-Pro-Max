// F-WEB-050 Web Entry (route-day aggregate entry). Date, geography cascade, route, then the per-SKU grid.
import Link from "next/link";
import { GeoCascade, type CascadeLevel } from "@/components/admin/kit/geo-cascade";
import { Card, PageHeading, qs } from "@/components/admin/kit/page";
import { GEO_LEVELS, type GeoLevel, type GeoNode } from "@/lib/admin/geo";
import type { Route, Sku, WebEntryRouteDay } from "@/lib/admin/types";
import { formatBusinessDate, t, type Locale, type MessageKey } from "@/lib/i18n";
import { WebEntryGrid } from "./web-entry-grid";

export interface WebEntryViewProps {
  locale: Locale;
  options: Record<GeoLevel, GeoNode[]>;
  selection: Partial<Record<GeoLevel, string>>;
  date: string;
  today: string;
  routes: Route[] | null;
  routeId: string;
  entry: WebEntryRouteDay | null;
  skus: Sku[];
  canWrite: boolean;
  classes?: number[];
}

export function WebEntryView({ locale, options, selection, date, today, routes, routeId, entry, skus, canWrite, classes = [] }: WebEntryViewProps) {
  const levels: CascadeLevel[] = GEO_LEVELS.map((l) => ({ param: l, label: t(locale, `otp.level.${l}` as MessageKey), value: selection[l] ?? "", options: options[l].map((n) => ({ value: String(n.id), label: locale === "bn" && n.name_bn ? n.name_bn : n.name })) }));
  const gridSkus = skus.map((s) => ({ id: s.id, label: `${s.short_name ?? s.name}` }));
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "we.title")} intro={t(locale, "we.intro")} />
      <GeoCascade levels={levels} viewLabel={t(locale, "otp.view")} allLabel={t(locale, "common.all")} action="/entry/web" extra={[{ name: "date", value: date, label: t(locale, "cfgc.col.date"), type: "date" }]} />
      {date < today ? <p role="status" data-testid="backdate-banner" className="rounded border border-[var(--warning)] bg-[color-mix(in_srgb,var(--warning)_14%,transparent)] p-3 text-sm text-[var(--warning)]">{t(locale, "we.backdate", { date: formatBusinessDate(locale, date) })}</p> : null}
      {routes === null ? (
        <p className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">{t(locale, "otp.choose_zone")}</p>
      ) : (
        <Card title={t(locale, "we.routes")}>
          <ul className="flex flex-wrap gap-2" data-testid="route-list">
            {routes.map((r) => (
              <li key={r.id}>
                <Link href={`/entry/web${qs({ ...selection, date, route: r.id })}`} className={`rounded border px-3 py-1 text-sm ${String(r.id) === routeId ? "border-brand-600 bg-brand-50" : "border-slate-300 bg-white"}`}>
                  {r.code} · {r.name}
                </Link>
              </li>
            ))}
          </ul>
          {routes.length === 0 ? <p className="text-sm text-slate-600">{t(locale, "common.empty")}</p> : null}
        </Card>
      )}
      {entry ? (
        <Card title={`${t(locale, "we.entry_of")} ${formatBusinessDate(locale, date)}`}>
          <p className="text-xs text-slate-600">{t(locale, "we.astha_note")}</p>
          <WebEntryGrid key={`${entry.route_id}-${date}-${entry.saved_at ?? "new"}`} routeId={entry.route_id} date={date} skus={gridSkus} initialLines={entry.lines} targetOutlets={entry.target_outlets} initialCalls={entry.successful_calls} saved={entry.saved_at != null} appOverlap={entry.app_overlap} canWrite={canWrite} classes={classes} />
        </Card>
      ) : null}
    </div>
  );
}
