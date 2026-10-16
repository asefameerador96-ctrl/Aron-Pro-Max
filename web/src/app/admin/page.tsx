import Link from "next/link";
import { PageHeading } from "@/components/admin/kit/page";
import { Icon, type IconName } from "@/components/kit/icons";
import { getLocale } from "@/lib/auth/service";
import { requireSession } from "@/lib/auth/require";
import { t, type MessageKey } from "@/lib/i18n";
import { ENTITIES } from "./_entities/registry";

/** Admin home: glass tiles with icons to the portal's hubs (routes unchanged). */
export default async function AdminHome() {
  const [locale, session] = await Promise.all([getLocale(), requireSession()]);
  const visible = ENTITIES.filter((e) => e.readRoles.includes(session.user.role));
  const tiles: { href: string; key: MessageKey; icon: IconName }[] = [...(visible.length > 0 ? [{ href: "/admin/master-data", key: "menu.admin.master_data" as const, icon: "master" as const }] : []), { href: "/admin/audit", key: "menu.admin.audit", icon: "audit" }];
  return (
    <div className="space-y-6">
      <PageHeading title={t(locale, "admin.title")} intro={t(locale, "admin.home.intro")} />
      <ul className="grid gap-(--gap) sm:grid-cols-2 lg:grid-cols-3">
        {tiles.map((x) => (
          <li key={x.href}>
            <Link href={x.href} className="glass-card flex min-h-28 items-center gap-4 p-(--card-pad) text-primary transition-[box-shadow,transform] duration-fast ease-out-soft hover:shadow-sheet focus-visible:outline-2 focus-visible:outline-offset-2 outline-focus">
              <span className="flex size-12 shrink-0 items-center justify-center rounded-full bg-accent-container text-accent-on-container" aria-hidden="true">
                <Icon name={x.icon} size={24} />
              </span>
              <span className="min-w-0 flex-1 text-heading text-primary [overflow-wrap:anywhere]">{t(locale, x.key)}</span>
              <Icon name="chevron-right" className="text-secondary" />
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
