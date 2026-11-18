import Link from "next/link";
import { KpiTargetsUpload } from "@/components/admin/kpi-targets-upload";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { apiGet, Card, one, PageHeading, type SearchParams } from "@/components/admin/kit/page";
import { onApiFailure } from "@/components/admin/crud/pages";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { isKpiKind, KPI_KINDS, type KpiTargetList } from "@/lib/admin/kpi-targets";
import { formatMtkExact } from "@/lib/admin/money";
import { canSee, canWriteModule, loadGrants } from "@/lib/admin/modules";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate, formatNumber, t } from "@/lib/i18n";
import type { MessageKey } from "@/lib/i18n/messages-en";

const ROUTE = /^[A-Za-z0-9][A-Za-z0-9._/-]{0,39}$/;

// KPI targets of the SR programme screens (contract 1.22.0). Read shows what is stored for one route and month; upload and commit need
// the targets module at w (the server is the authority, this page only mirrors it).
export default async function KpiTargetsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const grants = await loadGrants(session.at, session.user.role);
  if (!canSee(grants, "targets")) return <Forbidden locale={locale} />;
  const current = businessDate().slice(0, 7);
  const kindParam = one(sp.kind, 20);
  const kind = isKpiKind(kindParam) ? kindParam : "brand";
  const m = one(sp.month, 7);
  const month = m && /^\d{4}-(0[1-9]|1[0-2])$/.test(m) ? m : current;
  const routeParam = one(sp.route_code, 40);
  const route = routeParam && ROUTE.test(routeParam) ? routeParam : undefined;
  const list = route ? await apiGet<KpiTargetList>("/v1/admin/route-kpi-targets", session.at, { route_code: route, month }) : null;
  if (list && !list.ok) return onApiFailure(list.status, list.problem, locale);
  const data = list?.ok ? list.data : null;
  const brandCols: Column<KpiTargetList["brand_rows"][number]>[] = [
    { key: "brand", header: t(locale, "kt.col.brand_code"), render: (r) => `${r.brand_code} · ${r.brand_name}` },
    { key: "memo", header: t(locale, "kt.col.memo_target"), align: "right", render: (r) => formatNumber(locale, r.memo_target) },
    { key: "kpi", header: t(locale, "kt.col.kpi_alloc_mtk"), align: "right", render: (r) => formatMtkExact(locale, r.kpi_alloc_mtk) },
  ];
  const progCols: Column<KpiTargetList["programme_rows"][number]>[] = [
    { key: "programme", header: t(locale, "kt.col.programme_code"), render: (r) => `${r.programme_code} · ${r.programme_name}` },
    { key: "qty", header: t(locale, "kt.col.target_qty"), align: "right", render: (r) => r.target_qty },
  ];
  const campCols: Column<NonNullable<KpiTargetList["campaign_rows"]>[number]>[] = [
    { key: "outlet", header: t(locale, "kt.col.outlet_code"), render: (r) => r.outlet_code },
    { key: "programme", header: t(locale, "kt.col.programme_code"), render: (r) => `${r.programme_code} · ${r.programme_name}` },
    { key: "std", header: t(locale, "kt.col.std_target"), align: "right", render: (r) => r.std_target },
    { key: "memo", header: t(locale, "kt.col.memo_target"), align: "right", render: (r) => formatNumber(locale, r.memo_target) },
  ];
  const campSkuCols: Column<NonNullable<KpiTargetList["campaign_sku_rows"]>[number]>[] = [
    { key: "outlet", header: t(locale, "kt.col.outlet_code"), render: (r) => r.outlet_code },
    { key: "programme", header: t(locale, "kt.col.programme_code"), render: (r) => r.programme_code },
    { key: "sku", header: t(locale, "kt.col.sku_code"), render: (r) => r.sku_code },
    { key: "qty", header: t(locale, "kt.col.qty_base"), align: "right", render: (r) => formatNumber(locale, r.qty_base) },
  ];
  const campaigns = data?.campaign_rows ?? [];
  const campaignSkus = data?.campaign_sku_rows ?? [];
  const mr = data?.month_row ?? null;
  const none = t(locale, "kt.stored_cpr_none");
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "kt.title")} intro={t(locale, "kt.intro")} />
      <nav aria-label={t(locale, "kt.section_nav")} data-testid="kt-sections" className="flex flex-wrap gap-2">
        {KPI_KINDS.map((k) => (
          <Link key={k} href={`/admin/kpi-targets?kind=${k}`} data-testid={`kt-kind-${k}`} aria-current={k === kind ? "page" : undefined} className={`min-h-11 rounded border px-3 py-2 text-sm ${k === kind ? "border-brand-600 bg-brand-600 text-white" : "border-slate-300 bg-white hover:bg-slate-100"}`}>
            {t(locale, `kt.kind.${k}` as MessageKey)}
          </Link>
        ))}
      </nav>
      {/* upload and commit need targets: w (D-WEB-ADMIN-MENU-01) */}
      {canOp("kpi-targets.commit", session.user.role) && canWriteModule(grants, "targets") ? (
        <Card>
          <KpiTargetsUpload key={kind} kind={kind} currentMonth={current} />
        </Card>
      ) : (
        <p data-testid="kt-read-only" className="text-sm text-slate-600">{t(locale, "kt.read_only")}</p>
      )}
      <Card title={t(locale, "kt.stored_title")} data>
        <p className="mb-2 text-sm text-slate-600">{t(locale, "kt.stored_hint")}</p>
        <FilterBar
          controls={[
            { param: "route_code", label: t(locale, "kt.route"), kind: "search", value: route ?? "" },
            { param: "month", label: t(locale, "kt.month"), kind: "search", value: month },
          ]}
          applyLabel={t(locale, "common.filter")}
          clearLabel={t(locale, "common.clear")}
          allLabel={t(locale, "common.all")}
          clearHref="/admin/kpi-targets"
        >
          <input type="hidden" name="kind" value={kind} />
        </FilterBar>
        {routeParam && !route ? <p role="alert" className="text-sm text-red-800">{t(locale, "kt.route_bad")}</p> : null}
        {data ? (
          data.brand_rows.length === 0 && data.programme_rows.length === 0 && campaigns.length === 0 && campaignSkus.length === 0 && !mr ? (
            <p data-testid="kt-stored-empty" className="text-sm text-slate-600">{t(locale, "kt.stored_none")}</p>
          ) : (
            <div className="space-y-4" data-testid="kt-stored">
              <section>
                <h3 className="mb-1 text-sm font-semibold">{t(locale, "kt.stored_month")}</h3>
                {mr ? (
                  <dl data-testid="kt-stored-month" className="grid grid-cols-1 gap-1 text-sm sm:grid-cols-3">
                    <div><dt className="text-slate-600">{t(locale, "kt.col.kpi_alloc_mtk")}</dt><dd className="tabular-nums">{formatMtkExact(locale, mr.kpi_alloc_mtk)}</dd></div>
                    <div><dt className="text-slate-600">{t(locale, "kt.col.cpr_target_bp")}</dt><dd className="tabular-nums">{mr.cpr_target_bp === null ? none : formatNumber(locale, mr.cpr_target_bp)}</dd></div>
                    <div><dt className="text-slate-600">{t(locale, "kt.col.cpr_alloc_mtk")}</dt><dd className="tabular-nums">{mr.cpr_alloc_mtk === null ? none : formatMtkExact(locale, mr.cpr_alloc_mtk)}</dd></div>
                  </dl>
                ) : (
                  <p className="text-sm text-slate-600">{t(locale, "kt.stored_no_month")}</p>
                )}
              </section>
              <section>
                <h3 className="mb-1 text-sm font-semibold">{t(locale, "kt.stored_brands")}</h3>
                <DataTable columns={brandCols} rows={data.brand_rows} rowKey={(r) => r.brand_code} empty={t(locale, "kt.stored_empty_brands")} caption={t(locale, "kt.stored_brands")} />
              </section>
              <section>
                <h3 className="mb-1 text-sm font-semibold">{t(locale, "kt.stored_programmes")}</h3>
                <DataTable columns={progCols} rows={data.programme_rows} rowKey={(r) => r.programme_code} empty={t(locale, "kt.stored_empty_programmes")} caption={t(locale, "kt.stored_programmes")} />
              </section>
              <section>
                <h3 className="mb-1 text-sm font-semibold">{t(locale, "kt.stored_campaigns")}</h3>
                <DataTable columns={campCols} rows={campaigns} rowKey={(r) => `${r.outlet_code}|${r.programme_code}`} empty={t(locale, "kt.stored_empty_campaigns")} caption={t(locale, "kt.stored_campaigns")} />
              </section>
              <section>
                <h3 className="mb-1 text-sm font-semibold">{t(locale, "kt.stored_campaign_skus")}</h3>
                <DataTable columns={campSkuCols} rows={campaignSkus} rowKey={(r) => `${r.outlet_code}|${r.programme_code}|${r.sku_code}`} empty={t(locale, "kt.stored_empty_campaign_skus")} caption={t(locale, "kt.stored_campaign_skus")} />
              </section>
            </div>
          )
        ) : null}
      </Card>
    </div>
  );
}
