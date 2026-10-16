// F-ADM-038 Config page P1: config home. The version and the pending count come straight from the config service.
import Link from "next/link";
import { Card, PageHeading, Stat } from "@/components/admin/kit/page";
import { Icon, type IconName } from "@/components/kit/icons";
import type { PendingCount } from "@/lib/admin/config-load";
import type { ConfigChange, ConfigReach } from "@/lib/admin/types";
import { formatNumber, t, type Locale, type MessageKey } from "@/lib/i18n";
import { ChangesTable } from "./changes-table";
import { ReachWidget } from "./reach-widget";

export const HOME_LINKS: readonly { href: string; key: MessageKey; icon: IconName }[] = [
  { href: "/admin/config/keys", key: "cfgh.link.keys", icon: "key" },
  { href: "/admin/config/rules", key: "cfgh.link.rules", icon: "list" },
  { href: "/admin/config/switches", key: "cfgh.link.switches", icon: "toggle" },
  { href: "/admin/config/geofence", key: "cfgh.link.geofence", icon: "radius" },
  { href: "/admin/config/reach", key: "cfgh.link.reach", icon: "location" },
  { href: "/admin/config/changes", key: "cfgh.link.changes", icon: "entry" },
  { href: "/admin/config/history", key: "cfgh.link.history", icon: "audit" },
  { href: "/admin/config/flags", key: "cfgh.link.flags", icon: "flag" },
];

export function ConfigHomeView({ locale, version, pending, recent, reach }: { locale: Locale; version: number | null; pending: PendingCount | null; recent: ConfigChange[]; reach: ConfigReach | null }) {
  return (
    <div className="space-y-6">
      <PageHeading title={t(locale, "cfgh.title")} intro={t(locale, "cfgh.intro")} />
      <div className="grid grid-cols-2 gap-(--gap) sm:grid-cols-4">
        <Stat label={t(locale, "cfgh.version")} value={version === null ? "—" : formatNumber(locale, version, { useGrouping: false })} testId="config-version" />
        <Stat
          label={t(locale, "cfgh.pending")}
          value={pending === null ? "—" : pending.more ? t(locale, "cfgh.pending_more", { count: formatNumber(locale, pending.count) }) : formatNumber(locale, pending.count)}
          testId="pending-count"
        />
      </div>
      <nav aria-label={t(locale, "cfgh.title")}>
        <ul className="grid gap-(--gap) sm:grid-cols-2 lg:grid-cols-4">
          {HOME_LINKS.map((l) => (
            <li key={l.href}>
              <Link href={l.href} className="glass-card flex min-h-20 items-center gap-3 p-4 text-primary transition-[box-shadow] duration-fast ease-out-soft hover:shadow-sheet focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus">
                <span className="flex size-10 shrink-0 items-center justify-center rounded-full bg-accent-container text-accent-on-container" aria-hidden="true">
                  <Icon name={l.icon} size={20} />
                </span>
                <span className="min-w-0 flex-1 text-body-strong text-primary [overflow-wrap:anywhere]">{t(locale, l.key)}</span>
                <Icon name="chevron-right" size={18} className="text-secondary" />
              </Link>
            </li>
          ))}
        </ul>
      </nav>
      <Card title={t(locale, "cfgc.reach.applied")}>
        <ReachWidget locale={locale} reach={reach} />
      </Card>
      <Card title={t(locale, "cfgh.recent")} data>
        <ChangesTable locale={locale} rows={recent} caption={t(locale, "cfgh.recent")} />
      </Card>
    </div>
  );
}
