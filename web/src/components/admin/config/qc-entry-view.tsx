import Link from "next/link";
import { GeoCascade, type CascadeLevel } from "@/components/admin/kit/geo-cascade";
import { Card, PageHeading, qs } from "@/components/admin/kit/page";
import { GEO_LEVELS, type GeoLevel, type GeoNode } from "@/lib/admin/geo";
import { activeFaults } from "@/lib/admin/qc-entry";
import type { CodeItem, Route, Sku } from "@/lib/admin/types";
import { formatBusinessDate, t, type Locale, type MessageKey } from "@/lib/i18n";
import { QcGrid } from "./qc-grid";

export interface QcEntryViewProps {
  locale: Locale;
  source: "market" | "warehouse";
  options: Record<GeoLevel, GeoNode[]>;
  selection: Partial<Record<GeoLevel, string>>;
  date: string;
  today: string;
  routes: Route[] | null;
  routeId: string;
  faultItems: CodeItem[] | null;
  skus: Sku[];
}

/** F-WEB-052 Market QC (zone, route, date) and F-WEB-060 Warehouse QC (zone, one date): the same grid. */
export function QcEntryView({ locale, source, options, selection, date, today, routes, routeId, faultItems, skus }: QcEntryViewProps) {
  const base = source === "market" ? "/entry/qc" : "/entry/warehouse-qc";
  const levels: CascadeLevel[] = GEO_LEVELS.map((l) => ({ param: l, label: t(locale, `otp.level.${l}` as MessageKey), value: selection[l] ?? "", options: options[l].map((n) => ({ value: String(n.id), label: locale === "bn" && n.name_bn ? n.name_bn : n.name })) }));
  const faults = faultItems ? activeFaults(faultItems, today).map((f) => ({ code: f.code, label: locale === "bn" && f.label_bn ? f.label_bn : f.label_en, group: String(f.attrs?.group ?? "") })) : [];
  const ready = selection.zone && (source === "warehouse" || routeId);
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, source === "market" ? "qcg.market.title" : "qcg.wh.title")} intro={t(locale, source === "market" ? "qcg.market.intro" : "qcg.wh.intro")} />
      <GeoCascade levels={levels} viewLabel={t(locale, "otp.view")} allLabel={t(locale, "common.all")} action={base} extra={[{ name: "date", value: date, label: t(locale, "cfgc.col.date"), type: "date" }]} />
      {source === "market" && routes ? (
        <Card title={t(locale, "we.routes")}>
          <ul className="flex flex-wrap gap-2">
            {routes.map((r) => (
              <li key={r.id}>
                <Link href={`${base}${qs({ ...selection, date, route: r.id })}`} className={`rounded border px-3 py-1 text-sm ${String(r.id) === routeId ? "border-brand-600 bg-brand-50" : "border-slate-300 bg-white"}`}>
                  {r.code} · {r.name}
                </Link>
              </li>
            ))}
          </ul>
        </Card>
      ) : null}
      {!ready ? (
        <p className="rounded border border-dashed border-slate-300 bg-white p-6 text-center text-slate-600">{t(locale, source === "market" ? "qcg.choose_route" : "otp.choose_zone")}</p>
      ) : faultItems === null ? (
        <p role="alert" className="rounded bg-[color-mix(in_srgb,var(--danger)_12%,transparent)] p-3 text-sm text-[var(--danger)]" data-testid="faults-unavailable">{t(locale, "qcg.faults_unavailable")}</p>
      ) : (
        <Card title={formatBusinessDate(locale, date)}>
          <p className="text-xs text-slate-600">{t(locale, "qcg.separate")}</p>
          <QcGrid key={`${selection.zone}-${routeId}-${date}`} source={source} zoneId={Number(selection.zone)} routeId={source === "market" ? Number(routeId) : undefined} date={date} skus={skus.map((s) => ({ id: s.id, label: s.short_name ?? s.name }))} faults={faults} />
        </Card>
      )}
    </div>
  );
}
