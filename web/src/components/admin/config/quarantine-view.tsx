// F-ADM-030 sync quarantine review and F-ADM-051 Config page P14: parked rows by reason, PII masked, resolve.
import Link from "next/link";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { OpForm, type OpFieldDef } from "@/components/admin/kit/op-form";
import { Card, NextLink, PageHeading } from "@/components/admin/kit/page";
import { maskPayload } from "@/lib/admin/mask";
import type { QuarantineItem } from "@/lib/admin/types";
import { formatBusinessDate, formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";

const STATUSES = ["open", "accepted", "accepted_with_fix", "discarded"] as const;
const ACTIONS = ["accept", "accept_with_fix", "discard", "return_to_device"] as const;

export function QuarantineView({ locale, rows, filters, basePath, nextHref, canWrite, titleKey = "qr.title", selected, cursor }: { locale: Locale; rows: QuarantineItem[]; filters: { status: string; code: string; zone_id: string }; basePath: string; nextHref: string | null; canWrite: boolean; titleKey?: "qr.title" | "cfgp14.title"; selected: QuarantineItem | null; cursor?: string }) {
  const columns: Column<QuarantineItem>[] = [
    { key: "id", header: t(locale, "cfgc.col.id"), render: (q) => formatNumber(locale, q.quarantine_id, { useGrouping: false }) },
    { key: "code", header: t(locale, "qr.col.reason"), render: (q) => q.code },
    { key: "type", header: t(locale, "cfgc.col.kind"), render: (q) => q.type },
    { key: "user", header: t(locale, "cfgc.col.user"), render: (q) => formatNumber(locale, q.user_id, { useGrouping: false }) },
    { key: "date", header: t(locale, "cfgc.col.date"), render: (q) => formatBusinessDate(locale, q.business_date) },
    { key: "recv", header: t(locale, "qr.col.received"), render: (q) => formatDateTime(locale, q.received_at) },
    { key: "st", header: t(locale, "cfgc.col.status"), render: (q) => t(locale, `qr.status.${q.status}` as MessageKey) },
    { key: "open", header: t(locale, "common.actions"), render: (q) => <Link className="text-brand-700 underline" href={`${basePath}?${new URLSearchParams({ ...(filters.status ? { status: filters.status } : {}), ...(filters.code ? { code: filters.code } : {}), ...(filters.zone_id ? { zone_id: filters.zone_id } : {}), ...(cursor ? { cursor } : {}), item: String(q.quarantine_id) }).toString()}`}>{t(locale, "qr.review")}</Link> },
  ];
  const fields: OpFieldDef[] = [
    { name: "action", label: t(locale, "qr.action"), kind: "enum", required: true, options: ACTIONS.map((a) => ({ value: a, label: t(locale, `qr.action.${a}` as MessageKey) })) },
    { name: "fixed_record", label: t(locale, "qr.fixed"), kind: "json", nullable: true, hint: t(locale, "qr.fixed.hint") },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, titleKey)} intro={t(locale, "qr.intro")} />
      <FilterBar
        controls={[
          { param: "status", label: t(locale, "cfgc.col.status"), kind: "enum", value: filters.status, options: STATUSES.map((s) => ({ value: s, label: t(locale, `qr.status.${s}` as MessageKey) })) },
          { param: "code", label: t(locale, "qr.col.reason"), kind: "search", value: filters.code },
          { param: "zone_id", label: t(locale, "cfgr.zone_filter"), kind: "int", value: filters.zone_id },
        ]}
        applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref={basePath}
      />
      <DataTable columns={columns} rows={rows} rowKey={(q) => String(q.quarantine_id)} empty={t(locale, "common.empty")} caption={t(locale, titleKey)} />
      <NextLink href={nextHref} label={t(locale, "common.next")} />
      {selected ? (
        <Card title={t(locale, "qr.detail", { id: formatNumber(locale, selected.quarantine_id, { useGrouping: false }) })} testId="quarantine-detail">
          <p className="text-sm">{selected.code}{selected.detail ? ` · ${selected.detail}` : ""}</p>
          <p className="text-xs text-slate-600">{t(locale, "qr.masked")}</p>
          <pre className="max-h-72 overflow-auto rounded bg-slate-100 p-2 text-xs" data-testid="masked-payload">{JSON.stringify(maskPayload(selected.payload), null, 2)}</pre>
          {canWrite && selected.status === "open" ? <OpForm op="quarantine.resolve" params={{ quarantine_id: String(selected.quarantine_id) }} fields={fields} submitLabel={t(locale, "qr.resolve")} successKey="qr.done" testId="resolve-form" /> : null}
        </Card>
      ) : null}
    </div>
  );
}
