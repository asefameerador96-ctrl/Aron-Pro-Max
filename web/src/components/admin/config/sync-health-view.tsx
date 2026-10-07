// F-ADM-050 Config page P13: sync health. Same endpoint and figures as the operations dashboard (/v1/dashboards/sync-health).
import Link from "next/link";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { NextLink, PageHeading, Stat } from "@/components/admin/kit/page";
import type { SyncHealthPage, SyncHealthRow } from "@/lib/admin/types";
import { formatDateTime, formatNumber, t, type Locale } from "@/lib/i18n";

export function SyncHealthView({ locale, data, date, onlyProblems, nextHref }: { locale: Locale; data: SyncHealthPage; date: string; onlyProblems: boolean; nextHref: string | null }) {
  const s = data.summary;
  const n = (v: number) => formatNumber(locale, v);
  const columns: Column<SyncHealthRow>[] = [
    { key: "u", header: t(locale, "cfgc.col.user"), render: (r) => r.username },
    { key: "d", header: t(locale, "dev.col.model"), render: (r) => r.device_model ?? "—" },
    { key: "a", header: t(locale, "dev.col.app"), render: (r) => r.app_version },
    { key: "p", header: t(locale, "sh.col.pending"), render: (r) => <span className={r.held_rows_alert ? "font-semibold text-[var(--danger)]" : ""}>{n(r.pending_rows_reported)}</span>, align: "right" },
    { key: "r", header: t(locale, "sh.rejected"), render: (r) => n(r.rejected_count), align: "right" },
    { key: "q", header: t(locale, "sh.quarantined"), render: (r) => n(r.quarantined_count), align: "right" },
    { key: "c", header: t(locale, "sh.col.last_contact"), render: (r) => (r.last_contact_at ? formatDateTime(locale, r.last_contact_at) : "—") },
    { key: "e", header: t(locale, "sh.col.error"), render: (r) => r.last_sync_error ?? "—" },
    { key: "s", header: t(locale, "sh.col.p95"), render: (r) => (r.sync_p95_s == null ? "—" : formatNumber(locale, r.sync_p95_s, { maximumFractionDigits: 1 })), align: "right" },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "sh.title")} intro={t(locale, "sh.intro")} actions={<Link href="/admin/config/quarantine" className="text-sm text-brand-700 underline">{t(locale, "sh.to_quarantine")}</Link>} />
      <p className="text-xs text-slate-500">{t(locale, "sh.as_of", { time: formatDateTime(locale, data.as_of) })}</p>
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <Stat label={t(locale, "sh.devices")} value={n(s.devices)} testId="sh-devices" />
        <Stat label={t(locale, "sh.with_pending")} value={n(s.devices_with_pending)} testId="sh-pending" />
        <Stat label={t(locale, "sh.held")} value={n(s.held_rows_alerts)} testId="sh-held" />
        <Stat label={t(locale, "sh.rejected")} value={n(s.rejected)} testId="sh-rejected" />
        <Stat label={t(locale, "sh.quarantined")} value={n(s.quarantined)} testId="sh-quarantined" />
        <Stat label={t(locale, "sh.mismatch")} value={n(s.mismatched_route_days)} testId="sh-mismatch" />
      </div>
      <FilterBar
        controls={[
          { param: "business_date", label: t(locale, "sh.date"), kind: "search", value: date },
          { param: "only_problems", label: t(locale, "sh.only_problems"), kind: "enum", value: onlyProblems ? "true" : "", options: [{ value: "true", label: t(locale, "common.yes") }] },
        ]}
        applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/config/sync-health"
      />
      <DataTable columns={columns} rows={data.items} rowKey={(r) => String(r.device_id)} empty={t(locale, "common.empty")} caption={t(locale, "sh.title")} />
      <NextLink href={nextHref} label={t(locale, "common.next")} />
    </div>
  );
}
