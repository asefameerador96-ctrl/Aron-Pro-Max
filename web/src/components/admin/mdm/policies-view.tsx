// D-MDM-01 policies: list, and the page frame for the editor.
import Link from "next/link";
import { DataTable, type Column } from "@/components/admin/kit/data-table";
import { NextLink, PageHeading } from "@/components/admin/kit/page";
import type { MdmLevel, MdmPolicy } from "@/lib/admin/mdm";
import { formatDateTime, formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { MdmTabs } from "./shared";

export function PoliciesListView({ locale, rows, level, nextHref }: { locale: Locale; rows: MdmPolicy[]; level: MdmLevel; nextHref: string | null }) {
  const columns: Column<MdmPolicy>[] = [
    { key: "name", header: t(locale, "mdm.policy.name"), render: (p) => <Link href={`/admin/mdm/policies/${p.policy_id}`} className="text-brand-700 underline">{p.name}</Link> },
    { key: "apps", header: t(locale, "mdm.policy.allowed"), render: (p) => formatNumber(locale, p.allowed_packages.length), align: "right" },
    { key: "win", header: t(locale, "mdm.policy.schedule"), render: (p) => formatNumber(locale, p.block_windows.length), align: "right" },
    { key: "kiosk", header: t(locale, "mdm.policy.kiosk"), render: (p) => t(locale, p.kiosk.enabled ? "common.yes" : "common.no") },
    { key: "loc", header: t(locale, "mdm.policy.location"), render: (p) => t(locale, `mdm.policy.location.${p.location_sampling.mode}.label` as MessageKey) },
    { key: "dev", header: t(locale, "mdm.tab.devices"), render: (p) => formatNumber(locale, p.device_count), align: "right" },
    { key: "ver", header: t(locale, "mdm.col.version"), render: (p) => formatNumber(locale, p.version, { useGrouping: false }), align: "right" },
    { key: "upd", header: t(locale, "mdm.col.updated"), render: (p) => formatDateTime(locale, p.updated_at) },
  ];
  return (
    <div className="space-y-4">
      <PageHeading
        title={t(locale, "mdm.tab.policies")}
        intro={t(locale, "mdm.policies.intro")}
        actions={
          level === "w" ? (
            <Link href="/admin/mdm/policies/new" data-testid="mdm-policy-new" className="rounded-full bg-brand-600 px-4 py-2 text-sm font-semibold text-white hover:bg-brand-700">
              {t(locale, "mdm.policy.new")}
            </Link>
          ) : undefined
        }
      />
      <MdmTabs locale={locale} active="policies" />
      <DataTable columns={columns} rows={rows} rowKey={(p) => String(p.policy_id)} empty={t(locale, "common.empty")} caption={t(locale, "mdm.tab.policies")} />
      <NextLink href={nextHref} label={t(locale, "common.next")} />
    </div>
  );
}

export function PolicyPageFrame({ locale, title, children }: { locale: Locale; title: string; children: React.ReactNode }) {
  return (
    <div className="space-y-4">
      <PageHeading title={title} actions={<Link href="/admin/mdm/policies" className="text-sm text-brand-700 underline">← {t(locale, "mdm.tab.policies")}</Link>} />
      <MdmTabs locale={locale} active="policies" />
      {children}
    </div>
  );
}
