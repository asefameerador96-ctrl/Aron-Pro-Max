import Link from "next/link";
import { MASTER_GROUPS } from "@/components/admin/crud/meta";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";
import { ENTITIES } from "../_entities/registry";

// One navigation to every master-data table, grouped (F-ADM-046). Entities appear here by themselves.
export default async function MasterDataHub() {
  const [locale, session] = await Promise.all([getLocale(), requireSession()]);
  const visible = ENTITIES.filter((e) => e.readRoles.includes(session.user.role));
  return (
    <div className="space-y-6">
      <h1 className="text-2xl font-bold">{t(locale, "menu.admin.master_data")}</h1>
      {MASTER_GROUPS.map((g) => {
        const items = visible.filter((e) => e.group === g);
        if (items.length === 0) return null;
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
            </ul>
          </section>
        );
      })}
    </div>
  );
}
