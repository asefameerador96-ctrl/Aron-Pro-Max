// F-WEB-063 report export log viewer: who exported which report, with which filters, how many rows, and whether PII was included.
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { NextLink, PageHeading } from "@/components/admin/kit/page";
import type { ExportLogEntry } from "@/lib/admin/types";
import { formatDateTime, formatNumber, t, type Locale } from "@/lib/i18n";

export function ExportLogView({ locale, rows, filters, nextHref }: { locale: Locale; rows: ExportLogEntry[]; filters: { user_id: string; report_key: string; from: string; to: string }; nextHref: string | null }) {
  const columns: Column<ExportLogEntry>[] = [
    { key: "at", header: t(locale, "audit.col.at"), render: (e) => <time dateTime={e.created_at}>{formatDateTime(locale, e.created_at)}</time> },
    { key: "u", header: t(locale, "audit.col.actor"), render: (e) => e.username ?? formatNumber(locale, e.user_id, { useGrouping: false }) },
    { key: "r", header: t(locale, "xl.col.report"), render: (e) => e.report_key },
    { key: "f", header: t(locale, "xl.col.format"), render: (e) => e.format },
    { key: "n", header: t(locale, "xl.col.rows"), render: (e) => formatNumber(locale, e.rows), align: "right" },
    { key: "p", header: t(locale, "xl.col.pii"), render: (e) => <span className={e.pii_included ? "font-semibold text-red-700" : ""}>{t(locale, e.pii_included ? "common.yes" : "common.no")}</span> },
    { key: "flt", header: t(locale, "xl.col.filters"), render: (e) => <code className="text-xs">{Object.entries(e.filters ?? {}).map(([k, v]) => `${k}=${typeof v === "object" ? JSON.stringify(v) : String(v)}`).join(", ") || "—"}</code> },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "xl.title")} intro={t(locale, "xl.intro")} />
      <FilterBar
        controls={[
          { param: "user_id", label: t(locale, "audit.filter.actor"), kind: "int", value: filters.user_id },
          { param: "report_key", label: t(locale, "xl.col.report"), kind: "search", value: filters.report_key },
          { param: "from", label: t(locale, "cfgc.from"), kind: "search", value: filters.from },
          { param: "to", label: t(locale, "cfgc.to"), kind: "search", value: filters.to },
        ]}
        applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/export-log"
      />
      <DataTable columns={columns} rows={rows} rowKey={(e) => e.export_id} empty={t(locale, "common.empty")} caption={t(locale, "xl.title")} />
      <NextLink href={nextHref} label={t(locale, "common.next")} />
    </div>
  );
}
