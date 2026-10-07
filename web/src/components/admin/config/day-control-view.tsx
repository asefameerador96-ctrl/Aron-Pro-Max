// F-ADM-029 day reopen and final-submit override, and F-ADM-052 Config page P15 day control (adds the missing check-out list).
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { OpForm, type OpFieldDef } from "@/components/admin/kit/op-form";
import { Card, PageHeading } from "@/components/admin/kit/page";
import type { ReportResult } from "@/lib/admin/types";
import { formatBusinessDate, t, type Locale } from "@/lib/i18n";

export interface DayControlViewProps {
  locale: Locale;
  zone: string;
  date: string;
  late: { rows: ReportResult["rows"]; columns: ReportResult["columns"]; known: boolean } | null;
  missing: { rows: ReportResult["rows"]; columns: ReportResult["columns"]; known: boolean } | null;
  canWrite: boolean;
  withMissing: boolean;
  basePath: string;
}

function ReportList({ locale, data, title, unknownKey, emptyKey }: { locale: Locale; data: NonNullable<DayControlViewProps["late"]>; title: string; unknownKey: "day.unknown_columns"; emptyKey: "day.late.empty" | "day.missing.empty" }) {
  const columns: Column<ReportResult["rows"][number]>[] = data.columns.slice(0, 8).map((c) => ({ key: c.key, header: (locale === "bn" && c.label_bn) || c.label_en, render: (r) => (r[c.key] === null || r[c.key] === undefined ? "—" : String(r[c.key])) }));
  return (
    <Card title={title}>
      {!data.known ? <p className="text-sm text-amber-800">{t(locale, unknownKey)}</p> : <DataTable columns={columns} rows={data.rows} rowKey={(r) => JSON.stringify(r)} empty={t(locale, emptyKey)} caption={title} />}
    </Card>
  );
}

export function DayControlView({ locale, zone, date, late, missing, canWrite, withMissing, basePath }: DayControlViewProps) {
  const fields: OpFieldDef[] = [
    { name: "zone_id", label: t(locale, "cfgc.col.zone"), kind: "int", required: true, initial: zone },
    { name: "business_date", label: t(locale, "cfgc.col.date"), kind: "date", required: true, initial: date },
  ];
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
          <OpForm op="day.reopen" fields={fields} submitLabel={t(locale, "day.reopen.submit")} successKey="day.reopen.done" resetOnSuccess={false} testId="reopen-form" />
        </Card>
      ) : (
        <p className="text-sm text-slate-600">{t(locale, "cfgc.read_only")}</p>
      )}
      {late ? <ReportList locale={locale} data={late} title={t(locale, "day.late.title")} unknownKey="day.unknown_columns" emptyKey="day.late.empty" /> : <p className="text-sm text-slate-600">{t(locale, "day.choose")}</p>}
      {withMissing && missing ? <ReportList locale={locale} data={missing} title={t(locale, "day.missing.title")} unknownKey="day.unknown_columns" emptyKey="day.missing.empty" /> : null}
    </div>
  );
}
