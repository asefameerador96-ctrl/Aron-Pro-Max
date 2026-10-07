import Link from "next/link";
import { MASTER_GROUPS, type MasterGroup } from "@/components/admin/crud/meta";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";
import { CODE_LISTS } from "../_codelists/registry";
import { ENTITIES } from "../_entities/registry";
import type { MessageKey } from "@/lib/i18n";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";

// Pages of the configuration lane that are master data by nature: listed here so the hub is the one navigation (F-ADM-046).
const HUB_LINKS: readonly { group: MasterGroup; labelKey: MessageKey; href: string; testId: string }[] = [
  { group: "products", labelKey: "menu.admin.prices", href: "/admin/prices", testId: "hub-prices" },
  { group: "products", labelKey: "menu.admin.sales_plan", href: "/admin/sales-plan", testId: "hub-sales-plan" },
  { group: "routes", labelKey: "menu.admin.sr_transfer", href: "/admin/sr-transfer", testId: "hub-sr-transfer" },
  { group: "lists", labelKey: "menu.config.calendar", href: "/admin/calendar", testId: "hub-calendar" },
  { group: "lists", labelKey: "menu.config.code_lists", href: "/admin/code-lists", testId: "hub-code-lists" },
  { group: "lists", labelKey: "menu.config.qc_faults", href: "/admin/qc-faults", testId: "hub-qc-faults" },
  { group: "lists", labelKey: "menu.config.print_templates", href: "/admin/print-templates", testId: "hub-print-templates" },
];

// One navigation to every master-data table, grouped (F-ADM-046). Entities appear here by themselves.
export default async function MasterDataHub() {
  const [locale, session] = await Promise.all([getLocale(), requireSession()]);
  const visible = ENTITIES.filter((e) => e.readRoles.includes(session.user.role));
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t(locale, "menu.admin.master_data")}</h1>
      {MASTER_GROUPS.map((g) => {
        const items = visible.filter((e) => e.group === g);
        const lists = CODE_LISTS.filter((l) => l.group === g);
        const links = hasRole(session.user.role, ADMIN_PORTAL_ROLES) ? HUB_LINKS.filter((l) => l.group === g) : [];
        if (items.length === 0 && lists.length === 0 && links.length === 0) return null;
        return (
          <section key={g} aria-labelledby={`g-${g}`} data-testid={`group-${g}`}>
            <h2 id={`g-${g}`} className="mb-2 text-lg font-semibold text-slate-800">
              {t(locale, `masterdata.group.${g}`)}
            </h2>
            <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
              {items.map((e) => (
                <li key={e.slug}>
                  <Link href={`/admin/${e.slug}`} className="block rounded-lg border border-slate-200 bg-white p-4 shadow-sm hover:border-brand-600">
                    {t(locale, e.labelKey)}
                  </Link>
                </li>
              ))}
              {links.map((l) => (
                <li key={l.href}>
                  <Link href={l.href} data-testid={l.testId} className="block rounded-lg border border-slate-200 bg-white p-4 shadow-sm hover:border-brand-600">
                    {t(locale, l.labelKey)}
                  </Link>
                </li>
              ))}
              {lists.map((l) => (
                <li key={l.key}>
                  <Link href={`/admin/code-lists/${l.key}`} data-testid={`codelist-${l.key}`} className="block rounded-lg border border-slate-200 bg-white p-4 shadow-sm hover:border-brand-600">
                    {t(locale, l.labelKey)}
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}
