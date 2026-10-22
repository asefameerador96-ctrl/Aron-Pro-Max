// D-MDM-01 device list: enrolment state, owner mode, user, app flavour and version, last seen, battery, network, policy, compliance flags.
import Link from "next/link";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { AutoRefresh } from "@/components/admin/kit/auto-refresh";
import { NextLink, PageHeading } from "@/components/admin/kit/page";
import { MDM_FLAGS, MDM_STATES, type MdmDevice, type MdmPolicy } from "@/lib/admin/mdm";
import { formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { FlagChip, FlagChips, MdmTabs, usersText } from "./shared";

export interface DeviceFilters {
  status: string;
  flavour: string;
  flag: string;
  policy_id: string;
  q: string;
}

export function DevicesListView({ locale, rows, policies, filters, nextHref }: { locale: Locale; rows: MdmDevice[]; policies: MdmPolicy[]; filters: DeviceFilters; nextHref: string | null }) {
  const columns: Column<MdmDevice>[] = [
    { key: "user", header: t(locale, "dev.col.users"), render: (d) => usersText(d.users) },
    { key: "state", header: t(locale, "mdm.col.enrolment"), render: (d) => t(locale, `dev.state.${d.status}` as MessageKey) },
    { key: "owner", header: t(locale, "mdm.col.owner"), render: (d) => t(locale, d.device_owner ? "mdm.owner.yes" : "mdm.owner.no") },
    {
      key: "app",
      header: t(locale, "mdm.col.app"),
      render: (d) => (
        <span className="flex flex-wrap items-center gap-1">
          <span>{t(locale, `dev.flavour.${d.flavour}` as MessageKey)}</span>
          <span>{d.app_version ?? "—"}</span>
          {d.compliance_flags.includes("outdated_app") ? <FlagChip locale={locale} flag="outdated_app" /> : null}
        </span>
      ),
    },
    { key: "seen", header: t(locale, "dev.col.last_seen"), render: (d) => (d.last_contact_at ? <time dateTime={d.last_contact_at}>{formatDateTime(locale, d.last_contact_at)}</time> : t(locale, "dev.never")) },
    { key: "bat", header: t(locale, "mdm.col.battery"), render: (d) => (d.battery_pct == null ? "—" : `${formatNumber(locale, d.battery_pct)}%`), align: "right" },
    { key: "net", header: t(locale, "mdm.col.network"), render: (d) => (d.network ? t(locale, `mdm.net.${d.network}` as MessageKey) : "—") },
    { key: "pol", header: t(locale, "mdm.col.policy"), render: (d) => d.policy_name ?? t(locale, "mdm.policy.none") },
    { key: "flags", header: t(locale, "mdm.col.flags"), render: (d) => <FlagChips locale={locale} flags={d.compliance_flags} /> },
    {
      key: "go",
      header: t(locale, "common.actions"),
      render: (d) => (
        <Link href={`/admin/mdm/devices/${d.device_id}`} data-testid={`mdm-open-${d.device_id}`} className="rounded border border-slate-300 px-2 py-1 text-xs hover:bg-slate-100">
          {t(locale, "dev.action.details")}
        </Link>
      ),
    },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "mdm.title")} intro={t(locale, "mdm.intro")} />
      <MdmTabs locale={locale} active="devices" />
      <FilterBar
        controls={[
          { param: "q", label: t(locale, "common.search"), kind: "search", value: filters.q },
          { param: "status", label: t(locale, "dev.filter.status"), kind: "enum", value: filters.status, options: MDM_STATES.map((s) => ({ value: s, label: t(locale, `dev.state.${s}` as MessageKey) })) },
          { param: "flavour", label: t(locale, "dev.filter.flavour"), kind: "enum", value: filters.flavour, options: ["sr", "amo", "tso"].map((f) => ({ value: f, label: t(locale, `dev.flavour.${f}` as MessageKey) })) },
          { param: "flag", label: t(locale, "mdm.filter.flag"), kind: "enum", value: filters.flag, options: MDM_FLAGS.map((f) => ({ value: f, label: t(locale, `mdm.flag.${f}` as MessageKey) })) },
          { param: "policy_id", label: t(locale, "mdm.filter.policy"), kind: "enum", value: filters.policy_id, options: policies.map((p) => ({ value: String(p.policy_id), label: p.name })) },
        ]}
        applyLabel={t(locale, "common.filter")}
        clearLabel={t(locale, "common.clear")}
        allLabel={t(locale, "common.all")}
        clearHref="/admin/mdm"
      />
      <DataTable columns={columns} rows={rows} rowKey={(d) => String(d.device_id)} empty={t(locale, "common.empty")} caption={t(locale, "mdm.title")} />
      <NextLink href={nextHref} label={t(locale, "common.next")} />
      <AutoRefresh seconds={60} />
    </div>
  );
}
