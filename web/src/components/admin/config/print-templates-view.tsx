// F-ADM-067 print template management: memo, stock slip, summary and cancel templates as versioned data.
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { OpForm, type OpFieldDef } from "@/components/admin/kit/op-form";
import { Card, PageHeading } from "@/components/admin/kit/page";
import type { PrintTemplate } from "@/lib/admin/types";
import { formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";

export const TEMPLATE_KINDS = ["cash_memo", "credit_memo", "offer_memo", "drp_memo", "zero_memo", "edited_memo", "stock_slip", "day_summary", "due_receipt", "void_slip"] as const;

export function PrintTemplatesView({ locale, rows, canWrite }: { locale: Locale; rows: PrintTemplate[]; canWrite: boolean }) {
  const columns: Column<PrintTemplate>[] = [
    { key: "k", header: t(locale, "cfgc.col.kind"), render: (r) => t(locale, `pt.kind.${r.kind}` as MessageKey) },
    { key: "v", header: t(locale, "cfgh.col.version"), render: (r) => formatNumber(locale, r.version, { useGrouping: false }), align: "right" },
    { key: "f", header: t(locale, "pt.columns"), render: (r) => formatNumber(locale, r.font_columns), align: "right" },
    { key: "s", header: t(locale, "pt.size"), render: (r) => `${formatNumber(locale, r.template_json.length)} B`, align: "right" },
  ];
  const fields: OpFieldDef[] = [
    { name: "kind", label: t(locale, "cfgc.col.kind"), kind: "enum", required: true, options: TEMPLATE_KINDS.map((k) => ({ value: k, label: t(locale, `pt.kind.${k}` as MessageKey) })) },
    { name: "font_columns", label: t(locale, "pt.columns"), kind: "enum", required: true, asNumber: true, options: [{ value: "32", label: "32" }, { value: "42", label: "42" }], hint: t(locale, "pt.columns.hint") },
    { name: "effective_from", label: t(locale, "pt.effective"), kind: "date", required: true, hint: t(locale, "pt.effective.hint") },
    { name: "template_json", label: t(locale, "pt.json"), kind: "textarea", required: true, maxLength: 20000, jsonString: true, hint: t(locale, "pt.json.hint") },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "pt.title")} intro={t(locale, "pt.intro")} />
      <DataTable columns={columns} rows={[...rows].sort((a, b) => a.kind.localeCompare(b.kind) || b.version - a.version)} rowKey={(r) => `${r.kind}-${r.version}`} empty={t(locale, "common.empty")} caption={t(locale, "pt.title")} />
      {canWrite ? (
        <Card title={t(locale, "pt.new")}>
          <OpForm op="print-template.create" fields={fields} submitLabel={t(locale, "pt.new")} successKey="pt.created" testId="template-form" />
        </Card>
      ) : null}
    </div>
  );
}
