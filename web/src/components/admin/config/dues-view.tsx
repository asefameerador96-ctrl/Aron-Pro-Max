// F-ADM-036 dues adjustment and write-off (maker-checker): a finance row corrects a disputed due; a different person approves.
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { OpForm, type OpFieldDef } from "@/components/admin/kit/op-form";
import { OpInline } from "@/components/admin/kit/op-inline";
import { Card, NextLink, PageHeading } from "@/components/admin/kit/page";
import { formatMtk } from "@/lib/admin/money";
import type { DuesAdjustment } from "@/lib/admin/types";
import { formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";

export function DuesView({ locale, rows, status, outlet, nextHref, userId, canCreate, canDecide }: { locale: Locale; rows: DuesAdjustment[]; status: string; outlet: string; nextHref: string | null; userId: number; canCreate: boolean; canDecide: boolean }) {
  const columns: Column<DuesAdjustment>[] = [
    { key: "o", header: t(locale, "dues.col.outlet"), render: (a) => formatNumber(locale, a.outlet_id, { useGrouping: false }) },
    { key: "k", header: t(locale, "cfgc.col.kind"), render: (a) => t(locale, `dues.kind.${a.kind}` as MessageKey) },
    { key: "a", header: t(locale, "dues.col.amount"), render: (a) => formatMtk(locale, a.amount_mtk), align: "right" },
    { key: "r", header: t(locale, "cfgh.col.reason"), render: (a) => a.reason },
    { key: "s", header: t(locale, "cfgc.col.status"), render: (a) => t(locale, `dues.status.${a.status}` as MessageKey) },
    { key: "c", header: t(locale, "cfgc.col.created"), render: (a) => formatDateTime(locale, a.created_at) },
    {
      key: "act",
      header: t(locale, "common.actions"),
      render: (a) => {
        if (a.status !== "pending" || !canDecide) return null;
        if (a.created_by_user_id === userId) return <span className="text-xs text-slate-600">{t(locale, "dues.own")}</span>;
        return (
          <div className="flex gap-2" data-testid={`dues-${a.adjustment_id}`}>
            <OpInline op="dues.decide" params={{ adjustment_id: String(a.adjustment_id) }} body={{ decision: "approve" }} label={t(locale, "cfgp5.approve")} successKey="dues.decided" testId={`dues-approve-${a.adjustment_id}`} />
            <OpInline op="dues.decide" params={{ adjustment_id: String(a.adjustment_id) }} body={{ decision: "reject" }} label={t(locale, "cfgp5.reject")} danger successKey="dues.decided" testId={`dues-reject-${a.adjustment_id}`} />
          </div>
        );
      },
    },
  ];
  const fields: OpFieldDef[] = [
    { name: "outlet_id", label: t(locale, "dues.col.outlet"), kind: "int", required: true },
    { name: "kind", label: t(locale, "cfgc.col.kind"), kind: "enum", required: true, options: [{ value: "correction", label: t(locale, "dues.kind.correction") }, { value: "write_off", label: t(locale, "dues.kind.write_off") }] },
    { name: "amount_mtk", label: t(locale, "dues.amount_tk"), kind: "int", required: true, scale: 1000, hint: t(locale, "dues.amount.hint") },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "dues.title")} intro={t(locale, "dues.intro")} />
      <FilterBar
        controls={[{ param: "outlet_id", label: t(locale, "dues.col.outlet"), kind: "int", value: outlet }, { param: "status", label: t(locale, "cfgc.col.status"), kind: "enum", value: status, options: (["pending", "approved", "rejected"] as const).map((s) => ({ value: s, label: t(locale, `dues.status.${s}` as MessageKey) })) }]}
        applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/dues"
      />
      <DataTable columns={columns} rows={rows} rowKey={(a) => String(a.adjustment_id)} empty={t(locale, "common.empty")} caption={t(locale, "dues.title")} />
      <NextLink href={nextHref} label={t(locale, "common.next")} />
      {canCreate ? (
        <Card title={t(locale, "dues.new")}>
          <OpForm op="dues.create" uuidMembers={["client_uuid"]} fields={fields} submitLabel={t(locale, "dues.new")} successKey="dues.created" testId="dues-form" />
        </Card>
      ) : null}
    </div>
  );
}
