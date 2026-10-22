// D-MDM-01 enrolment, alerts and fleet activity pages.
import Link from "next/link";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { OpInline } from "@/components/admin/kit/op-inline";
import { Card, NextLink, PageHeading } from "@/components/admin/kit/page";
import { AutoRefresh } from "@/components/admin/kit/auto-refresh";
import type { EnrolmentToken } from "@/lib/admin/types";
import { MDM_FLAGS, type MdmActivityEntry, type MdmAlert, type MdmLevel } from "@/lib/admin/mdm";
import { formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { activityColumns } from "./device-detail-view";
import { MdmEnrolmentForm } from "./enrolment-form";
import { FlagChip, MdmTabs, usersText } from "./shared";

export function MdmEnrolmentView({ locale, tokens, level }: { locale: Locale; tokens: EnrolmentToken[]; level: MdmLevel }) {
  const cols: Column<EnrolmentToken>[] = [
    { key: "p", header: t(locale, "enr.col.prefix"), render: (k) => <code>{k.token_prefix}…</code> },
    { key: "f", header: t(locale, "dev.col.flavour"), render: (k) => t(locale, `dev.flavour.${k.flavour}` as MessageKey) },
    { key: "l", header: t(locale, "enr.lockdown"), render: (k) => t(locale, k.lockdown_level === "prod" ? "enr.lockdown.prod" : "enr.lockdown.dev") },
    { key: "z", header: t(locale, "enr.zone_col"), render: (k) => (k.zone_id ? formatNumber(locale, k.zone_id, { useGrouping: false }) : "—") },
    { key: "u", header: t(locale, "enr.col.used"), render: (k) => `${formatNumber(locale, k.used_count)} / ${formatNumber(locale, k.max_uses)}`, align: "right" },
    { key: "e", header: t(locale, "enr.col.expires"), render: (k) => formatDateTime(locale, k.expires_at) },
    { key: "n", header: t(locale, "enr.note"), render: (k) => k.note ?? "—" },
    { key: "a", header: t(locale, "common.actions"), render: (k) => (level === "w" && !k.revoked_at ? <OpInline op="mdm.enrolment.revoke" params={{ token_id: String(k.token_id) }} noReason label={t(locale, "enr.revoke")} danger successKey="enr.revoked" testId={`mdm-revoke-${k.token_id}`} /> : null) },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "mdm.tab.enrolment")} intro={t(locale, "enr.intro")} />
      <MdmTabs locale={locale} active="enrolment" />
      {level === "w" ? <MdmEnrolmentForm /> : null}
      <Card title={t(locale, "enr.tokens")}>
        <DataTable columns={cols} rows={tokens} rowKey={(k) => String(k.token_id)} empty={t(locale, "common.empty")} caption={t(locale, "enr.tokens")} />
      </Card>
      <AutoRefresh seconds={30} />
    </div>
  );
}

export function MdmAlertsView({ locale, rows, flag, nextHref }: { locale: Locale; rows: MdmAlert[]; flag: string; nextHref: string | null }) {
  const cols: Column<MdmAlert>[] = [
    { key: "flag", header: t(locale, "mdm.col.flag"), render: (a) => <FlagChip locale={locale} flag={a.flag} /> },
    { key: "dev", header: t(locale, "mdm.col.device"), render: (a) => <Link href={`/admin/mdm/devices/${a.device_id}`} className="text-brand-700 underline">{usersText(a.users ?? []) === "—" ? `#${a.device_id}` : usersText(a.users ?? [])}</Link> },
    { key: "fl", header: t(locale, "dev.col.flavour"), render: (a) => (a.flavour ? t(locale, `dev.flavour.${a.flavour}` as MessageKey) : "—") },
    { key: "since", header: t(locale, "mdm.col.since"), render: (a) => <time dateTime={a.since}>{formatDateTime(locale, a.since)}</time> },
    { key: "det", header: t(locale, "mdm.col.detail"), render: (a) => a.detail ?? "—" },
  ];
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "mdm.tab.alerts")} intro={t(locale, "mdm.alerts.intro")} />
      <MdmTabs locale={locale} active="alerts" />
      <FilterBar
        controls={[{ param: "flag", label: t(locale, "mdm.filter.flag"), kind: "enum", value: flag, options: MDM_FLAGS.map((f) => ({ value: f, label: t(locale, `mdm.flag.${f}` as MessageKey) })) }]}
        applyLabel={t(locale, "common.filter")}
        clearLabel={t(locale, "common.clear")}
        allLabel={t(locale, "common.all")}
        clearHref="/admin/mdm/alerts"
      />
      <DataTable columns={cols} rows={rows} rowKey={(a) => `${a.device_id}-${a.flag}`} empty={t(locale, "mdm.alerts.empty")} caption={t(locale, "mdm.tab.alerts")} />
      <NextLink href={nextHref} label={t(locale, "common.next")} />
      <AutoRefresh seconds={60} />
    </div>
  );
}

export function MdmActivityView({ locale, rows, nextHref }: { locale: Locale; rows: MdmActivityEntry[]; nextHref: string | null }) {
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "mdm.tab.activity")} intro={t(locale, "mdm.activity.intro")} />
      <MdmTabs locale={locale} active="activity" />
      <DataTable columns={activityColumns(locale, true)} rows={rows} rowKey={(e) => `${e.at}-${e.device_id}-${e.kind}-${e.summary}`} empty={t(locale, "common.empty")} caption={t(locale, "mdm.tab.activity")} />
      <NextLink href={nextHref} label={t(locale, "common.next")} />
    </div>
  );
}
