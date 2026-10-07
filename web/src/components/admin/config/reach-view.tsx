// F-ADM-044 Config page P7: devices per config version, the acknowledged share and the pending list per zone.
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { Card, NextLink, PageHeading } from "@/components/admin/kit/page";
import { sharePct } from "@/lib/admin/config-load";
import type { ConfigPendingDevicePage, ConfigReach } from "@/lib/admin/types";
import { formatDateTime, formatNumber, t, type Locale } from "@/lib/i18n";
import { ReachWidget } from "./reach-widget";

type ZoneRow = NonNullable<ConfigReach["by_zone"]>[number];
type PendingRow = ConfigPendingDevicePage["items"][number];

export function ReachView({ locale, reach, pending, version, zone, nextHref }: { locale: Locale; reach: ConfigReach | null; pending: PendingRow[]; version: number | null; zone: string; nextHref: string | null }) {
  const pct = (n: number, d: number) => { const v = sharePct(n, d); return v === null ? "—" : `${formatNumber(locale, v, { maximumFractionDigits: 1 })}%`; };
  const zoneCols: Column<ZoneRow>[] = [
    { key: "zone", header: t(locale, "cfgc.col.zone"), render: (r) => formatNumber(locale, r.zone_id, { useGrouping: false }) },
    { key: "t", header: t(locale, "cfgr.col.targeted"), render: (r) => formatNumber(locale, r.targeted), align: "right" },
    { key: "a", header: t(locale, "cfgr.col.applied"), render: (r) => formatNumber(locale, r.applied), align: "right" },
    { key: "s", header: t(locale, "cfgr.col.share"), render: (r) => pct(r.applied, r.targeted), align: "right" },
  ];
  const pendCols: Column<PendingRow>[] = [
    { key: "d", header: t(locale, "cfgr.col.device"), render: (r) => formatNumber(locale, r.device_id, { useGrouping: false }) },
    { key: "u", header: t(locale, "cfgc.col.user"), render: (r) => formatNumber(locale, r.user_id, { useGrouping: false }) },
    { key: "z", header: t(locale, "cfgc.col.zone"), render: (r) => formatNumber(locale, r.zone_id, { useGrouping: false }) },
    { key: "v", header: t(locale, "cfgr.col.applied_version"), render: (r) => formatNumber(locale, r.applied_version, { useGrouping: false }) },
    { key: "l", header: t(locale, "cfgr.col.lag"), render: (r) => formatNumber(locale, r.lag_min), align: "right" },
    { key: "c", header: t(locale, "cfgr.col.last_contact"), render: (r) => (r.last_contact_at ? formatDateTime(locale, r.last_contact_at) : "—") },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "cfgr.title")} />
      <FilterBar
        controls={[
          { param: "version", label: t(locale, "cfgr.version"), kind: "int", value: version === null ? "" : String(version) },
          { param: "zone", label: t(locale, "cfgr.zone_filter"), kind: "int", value: zone },
        ]}
        applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/config/reach"
      />
      <Card>
        <ReachWidget locale={locale} reach={reach} />
      </Card>
      {reach?.by_zone?.length ? (
        <Card title={t(locale, "cfgr.by_zone")}>
          <DataTable columns={zoneCols} rows={reach.by_zone} rowKey={(r) => String(r.zone_id)} empty={t(locale, "common.empty")} caption={t(locale, "cfgr.by_zone")} />
        </Card>
      ) : null}
      <Card title={t(locale, "cfgr.pending.title")}>
        <DataTable columns={pendCols} rows={pending} rowKey={(r) => String(r.device_id)} empty={t(locale, "cfgr.pending.empty")} caption={t(locale, "cfgr.pending.title")} />
        <NextLink href={nextHref} label={t(locale, "common.next")} />
      </Card>
    </div>
  );
}
