// F-ADM-038 Config page P1: config home. The version and the pending count come straight from the config service.
import Link from "next/link";
import { Card, PageHeading, Stat } from "@/components/admin/kit/page";
import type { PendingCount } from "@/lib/admin/config-load";
import type { ConfigChange, ConfigReach } from "@/lib/admin/types";
import { formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { ChangesTable } from "./changes-table";
import { ReachWidget } from "./reach-widget";

export const HOME_LINKS: readonly { href: string; key: MessageKey }[] = [
  { href: "/admin/config/keys", key: "cfgh.link.keys" },
  { href: "/admin/config/rules", key: "cfgh.link.rules" },
  { href: "/admin/config/switches", key: "cfgh.link.switches" },
  { href: "/admin/config/geofence", key: "cfgh.link.geofence" },
  { href: "/admin/config/reach", key: "cfgh.link.reach" },
  { href: "/admin/config/changes", key: "cfgh.link.changes" },
  { href: "/admin/config/history", key: "cfgh.link.history" },
  { href: "/admin/config/flags", key: "cfgh.link.flags" },
];

export function ConfigHomeView({ locale, version, pending, recent, reach }: { locale: Locale; version: number | null; pending: PendingCount | null; recent: ConfigChange[]; reach: ConfigReach | null }) {
  return (
    <div className="space-y-4">
      <PageHeading title={t(locale, "cfgh.title")} intro={t(locale, "cfgh.intro")} />
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Stat label={t(locale, "cfgh.version")} value={version === null ? "—" : formatNumber(locale, version, { useGrouping: false })} testId="config-version" />
        <Stat
          label={t(locale, "cfgh.pending")}
          value={pending === null ? "—" : pending.more ? t(locale, "cfgh.pending_more", { count: formatNumber(locale, pending.count) }) : formatNumber(locale, pending.count)}
          testId="pending-count"
        />
      </div>
      <Card title={t(locale, "cfgc.reach.applied")}>
        <ReachWidget locale={locale} reach={reach} />
      </Card>
      <nav className="flex flex-wrap gap-2" aria-label={t(locale, "cfgh.title")}>
        {HOME_LINKS.map((l) => (
          <Link key={l.href} href={l.href} className="rounded border border-slate-300 bg-white px-3 py-1.5 text-sm hover:border-brand-600">
            {t(locale, l.key)}
          </Link>
        ))}
      </nav>
      <Card title={t(locale, "cfgh.recent")}>
        <ChangesTable locale={locale} rows={recent} caption={t(locale, "cfgh.recent")} />
      </Card>
    </div>
  );
}
