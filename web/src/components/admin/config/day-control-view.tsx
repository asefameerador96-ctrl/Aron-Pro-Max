// F-ADM-029 day reopen and final-submit override, and F-ADM-052 Config page P15 day control (adds the missing check-out list).
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { Card, PageHeading } from "@/components/admin/kit/page";
import type { ReportResult } from "@/lib/admin/types";
import { businessDate, formatBusinessDate, formatNumber, t, type Locale } from "@/lib/i18n";
import { DayReopenForm } from "./day-reopen-form";

export interface DayControlViewProps {
  locale: Locale;
  zone: string;
  date: string;
  late: { rows: ReportResult["rows"]; columns: ReportResult["columns"]; known: boolean; total?: number } | null;
  missing: { rows: ReportResult["rows"]; columns: ReportResult["columns"]; known: boolean; total?: number } | null;
  missingFailed?: boolean;
  canWrite: boolean;
  withMissing: boolean;
  basePath: string;
}

function ReportList({ locale, data, title, unknownKey, emptyKey }: { locale: Locale; data: NonNullable<DayControlViewProps["late"]>; title: string; unknownKey: "day.unknown_columns"; emptyKey: "day.late.empty" | "day.missing.empty" }) {
  const partial = data.total !== undefined && data.total > data.rows.length && data.total > 100;
  const columns: Column<ReportResult["rows"][number]>[] = data.columns.map((c) => ({ key: c.key, header: (locale === "bn" && c.label_bn) || c.label_en, render: (r) => (r[c.key] === null || r[c.key] === undefined ? "—" : String(r[c.key])) }));
  return (
    <Card title={title}>
      {data.total !== undefined && data.total > 100 ? <p role="note" className="text-xs text-warning-on-container">{t(locale, "day.truncated", { n: formatNumber(locale, data.total) })}</p> : null}
      {!data.known ? <p className="text-sm text-warning-on-container">{t(locale, unknownKey)}</p> : <DataTable columns={columns} rows={data.rows} rowKey={(r) => JSON.stringify(r)} empty={t(locale, partial ? "day.truncated_empty" : emptyKey)} caption={title} />}
    </Card>
  );
}

export function DayControlView({ locale, zone, date, late, missing, canWrite, withMissing, basePath, missingFailed }: DayControlViewProps) {
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, withMissing ? "day.p15.title" : "day.title")} intro={t(locale, "day.intro")} />
      <FilterBar
        controls={[{ param: "zone", label: t(locale, "cfgr.zone_filter"), kind: "int", value: zone }, { param: "date", label: t(locale, "cfgc.col.date"), kind: "search", value: date }]}
        applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref={basePath}
      />
      <p className="text-sm text-slate-600">{formatBusinessDate(locale, date)}</p>
      {canWrite ? (
        <Card title={t(locale, "day.reopen.title")}>
          <p className="text-sm text-slate-600">{t(locale, "day.reopen.hint")}</p>
          {/* tso-manual W1 (FS-14, D-TSO-MAN-29): the reopen sends client_uuid, kept only for a resend of the same unanswered request */}
          <DayReopenForm zone={zone} date={date} today={businessDate()} testId="reopen-form" />
        </Card>
      ) : (
        <p className="text-sm text-slate-600">{t(locale, "cfgc.read_only")}</p>
      )}
      {late ? <ReportList locale={locale} data={late} title={t(locale, "day.late.title")} unknownKey="day.unknown_columns" emptyKey="day.late.empty" /> : <p className="text-sm text-slate-600">{t(locale, "day.choose")}</p>}
      {withMissing && missingFailed ? (
        <Card title={t(locale, "day.missing.title")}>
          <p role="alert" className="text-sm text-danger-on-container">{t(locale, "cfgc.error.load")}</p>
        </Card>
      ) : null}
      {withMissing && missing ? <ReportList locale={locale} data={missing} title={t(locale, "day.missing.title")} unknownKey="day.unknown_columns" emptyKey="day.missing.empty" /> : null}
    </div>
  );
}
