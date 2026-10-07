// F-ADM-033 working-day calendar: weekend days (config key) and the holiday table with its declare form.
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { OpForm, type OpFieldDef } from "@/components/admin/kit/op-form";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { Card, PageHeading } from "@/components/admin/kit/page";
import type { Holiday, ResolvedConfigValue } from "@/lib/admin/types";
import { formatBusinessDate, t, type Locale, type MessageKey } from "@/lib/i18n";
import { ConfigSetForm } from "./config-set-form";
import type { ConfigKey } from "@/lib/admin/types";

export const WEEKDAYS = [1, 2, 3, 4, 5, 6, 7] as const;
const KINDS = ["holiday", "makeup_day", "emergency_off"] as const;
const SCOPES = ["global", "wing", "division", "territory", "zone"] as const;

export interface CalendarViewProps {
  locale: Locale;
  holidays: Holiday[];
  weekend: ResolvedConfigValue | null;
  weekendKey: ConfigKey | null;
  from: string;
  to: string;
  canWrite: boolean;
}

export function CalendarView({ locale, holidays, weekend, weekendKey, from, to, canWrite }: CalendarViewProps) {
  const days = Array.isArray(weekend?.value) ? (weekend.value as unknown[]).map(Number) : [];
  const columns: Column<Holiday>[] = [
    { key: "date", header: t(locale, "cfgc.col.date"), render: (h) => <time dateTime={h.date}>{formatBusinessDate(locale, h.date)}</time> },
    { key: "kind", header: t(locale, "cfgc.col.kind"), render: (h) => t(locale, `cal.kind.${h.kind}` as MessageKey) },
    { key: "name", header: t(locale, "cfgc.col.name"), render: (h) => (locale === "bn" && h.name_bn ? h.name_bn : h.name_en) },
    { key: "scope", header: t(locale, "cfgc.col.scope"), render: (h) => `${t(locale, `cfgc.scope.${h.scope_type}` as MessageKey)}${h.scope_type === "global" ? "" : ` #${h.scope_id}`}` },
    { key: "selling", header: t(locale, "cal.col.selling"), render: (h) => t(locale, h.selling_day ? "cal.selling.yes" : "cal.selling.no") },
  ];
  const fields: OpFieldDef[] = [
    { name: "date", label: t(locale, "cfgc.col.date"), kind: "date", required: true },
    { name: "kind", label: t(locale, "cfgc.col.kind"), kind: "enum", required: true, hint: t(locale, "cal.add.emergency_hint"), options: KINDS.map((k) => ({ value: k, label: t(locale, `cal.kind.${k}` as MessageKey) })) },
    { name: "scope_type", label: t(locale, "cfgc.scope.type"), kind: "enum", required: true, options: SCOPES.map((k) => ({ value: k, label: t(locale, `cfgc.scope.${k}` as MessageKey) })) },
    { name: "scope_id", label: t(locale, "cfgc.scope.id"), kind: "int", required: true, initial: "0" },
    { name: "name_en", label: t(locale, "cal.col.name_en"), kind: "text", required: true, maxLength: 120 },
    { name: "name_bn", label: t(locale, "cal.col.name_bn"), kind: "text", nullable: true, maxLength: 120 },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "cal.title")} intro={t(locale, "cal.intro")} />
      <Card title={t(locale, "cal.weekend.title")} testId="weekend-card">
        <p>
          {t(locale, "cal.weekend.current")}:{" "}
          <strong data-testid="weekend-days">{days.length ? days.map((d) => t(locale, `cal.day.${d}` as MessageKey)).join(", ") : "—"}</strong>
        </p>
        {canWrite && weekendKey ? (
          <>
            <p className="text-sm text-slate-600">{t(locale, "cal.weekend.hint")}</p>
            <ConfigSetForm keyName={weekendKey.key} valueType={weekendKey.value_type} bounds={weekendKey.bounds} scopeLevels={weekendKey.scope_levels} scope={{ type: "global", id: 0 }} current={weekend?.value ?? weekendKey.default_value} label={t(locale, "cal.weekend.title")} testId="weekend-form" />
          </>
        ) : null}
      </Card>
      <Card title={t(locale, "cal.list.title")}>
        <p className="text-sm text-slate-600">
          {formatBusinessDate(locale, from)} – {formatBusinessDate(locale, to)} · {t(locale, "cal.range.hint")}
        </p>
        <FilterBar controls={[{ param: "from", label: t(locale, "cfgc.from"), kind: "search", value: from }]} applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/calendar" />
        <DataTable columns={columns} rows={holidays} rowKey={(h) => String(h.id)} empty={t(locale, "common.empty")} caption={t(locale, "cal.list.title")} />
      </Card>
      {canWrite ? (
        <Card title={t(locale, "cal.add.title")}>
          <OpForm op="holiday.create" fields={fields} submitLabel={t(locale, "cal.add.submit")} successKey="cal.add.ok" testId="holiday-form" />
        </Card>
      ) : (
        <p className="text-sm text-slate-600">{t(locale, "cfgc.read_only")}</p>
      )}
    </div>
  );
}
