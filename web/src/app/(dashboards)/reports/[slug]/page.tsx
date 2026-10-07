import { notFound } from "next/navigation";
import { ReportFilters } from "@/components/reports/report-filters";
import { ReportTable } from "@/components/reports/report-table";
import { TileMeta } from "@/components/reports/tile-meta";
import { rawRequest } from "@/lib/api/raw";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate, formatNumber, problemMessage, t } from "@/lib/i18n";
import { canSeeReport, reportBySlug } from "@/lib/reports/catalog";
import { buildReportQuery } from "@/lib/reports/query";
import { getExportJob, getMe, runReportJson, type GeoOption } from "@/lib/reports/server";
import type { Schemas } from "@/contract/types";

type Params = Record<string, string | string[] | undefined>;

function link(slug: string, params: Params, extra: Record<string, string>, drop: string[] = []): string {
  const u = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) {
    if (drop.includes(k) || v === undefined) continue;
    for (const x of Array.isArray(v) ? v : [v]) u.append(k, x);
  }
  for (const [k, v] of Object.entries(extra)) u.set(k, v);
  return `${slug}?${u.toString()}`;
}

export default async function ReportPage({ params, searchParams }: { params: Promise<{ slug: string }>; searchParams: Promise<Params> }) {
  const [{ slug }, sp, locale, session] = await Promise.all([params, searchParams, getLocale(), requireSession()]);
  const report = reportBySlug(slug);
  if (!report || !canSeeReport(report, session.user.role)) notFound();
  const today = businessDate();
  const query = buildReportQuery(report, sp, session.scope, "json", today);
  const token = session.at;

  const [me, result, cats, skus, job] = await Promise.all([
    getMe(token),
    runReportJson(token, report.key, query),
    report.filters.includes("category")
      ? rawRequest<{ items: Schemas["ProductNode"][] }>({ method: "GET", path: "/v1/admin/product-nodes/category", token, query: { status: "active", limit: 100 } })
      : Promise.resolve(null),
    report.filters.includes("products") ? rawRequest<{ items: Schemas["Sku"][] }>({ method: "GET", path: "/v1/admin/skus", token, query: { status: "active", limit: 500 } }) : Promise.resolve(null),
    typeof sp.job === "string" && /^[0-9a-f-]{36}$/.test(sp.job) ? getExportJob(token, sp.job) : Promise.resolve(null),
  ]);
  const categories: GeoOption[] = cats?.ok ? cats.data.items.map((c) => ({ id: c.id, label: locale === "bn" ? (c.name_bn ?? c.name) : c.name })) : [];
  const skuOptions: GeoOption[] = skus?.ok ? skus.data.items.map((s) => ({ id: s.id, label: `${s.short_name} (${s.code})` })) : [];
  const pii = me.ok ? Boolean(me.data.pii) : false;

  const qs = (extra: Record<string, string>, drop: string[] = []) => link(`/reports/${slug}`, sp, extra, drop);
  const exportHref = (format: string) => `/api/bff/reports/${slug}/export${qs({ format }, ["page", "job"]).slice(`/reports/${slug}`.length)}`;
  const page = query.output.page ?? 1;
  const size = query.output.page_size ?? 50;

  return (
    <div className="space-y-4" data-testid={`report-${report.key}`}>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h1 className="text-2xl font-bold">{t(locale, report.titleKey)}</h1>
        {result.ok ? <TileMeta locale={locale} businessDate={query.period.date ?? query.period.to ?? today} asOf={result.data.as_of} today={today} /> : null}
      </div>
      <ReportFilters report={report} locale={locale} params={sp} token={token} scope={session.scope} categories={categories} skus={skuOptions} today={today} />
      {job?.ok ? (
        <p role="status" className="rounded bg-sky-50 p-3 text-sm text-sky-900" data-testid="export-job">
          {job.data.status === "done" && job.data.download_url ? (
            <a className="font-semibold underline" href={job.data.download_url}>
              {t(locale, "report.export.ready")}
            </a>
          ) : (
            t(locale, "report.export.queued")
          )}
        </p>
      ) : null}
      {!result.ok ? (
        <p role="alert" className="rounded bg-red-50 p-3 text-red-900" data-testid="report-error">
          {problemMessage(locale, result.problem.code)}
        </p>
      ) : (
        <>
          <div className="flex flex-wrap gap-2">
            {report.excel === false ? null : (
              <a href={exportHref("xlsx")} className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm font-semibold" data-testid="get-excel">
                {t(locale, "report.get_excel")}
              </a>
            )}
            {report.print ? (
              <a href={exportHref("print")} target="_blank" rel="noreferrer" className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm font-semibold" data-testid="print-view">
                {t(locale, "report.print")}
              </a>
            ) : null}
          </div>
          <ReportTable result={result.data} locale={locale} pii={pii} />
          <nav className="flex items-center gap-3 text-sm" aria-label={t(locale, "report.paging")}>
            {page > 1 ? (
              <a href={qs({ page: String(page - 1) })} data-testid="prev-page" className="underline">
                {t(locale, "common.previous")}
              </a>
            ) : null}
            <span>{t(locale, "report.page_of", { page: formatNumber(locale, page), pages: formatNumber(locale, Math.max(1, Math.ceil(result.data.total_rows / size))) })}</span>
            {page * size < result.data.total_rows ? (
              <a href={qs({ page: String(page + 1) })} data-testid="next-page" className="underline">
                {t(locale, "common.next")}
              </a>
            ) : null}
          </nav>
        </>
      )}
    </div>
  );
}
