import Link from "next/link";
import { getLocale } from "@/lib/auth/service";
import { requireSession } from "@/lib/auth/require";
import { t } from "@/lib/i18n";
import { ENTITIES } from "./_entities/registry";

export default async function AdminHome() {
  const [locale, session] = await Promise.all([getLocale(), requireSession()]);
  const visible = ENTITIES.filter((e) => e.readRoles.includes(session.user.role));
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "admin.title")}</h1>
      <p className="text-slate-600">{t(locale, "admin.home.intro")}</p>
      <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {visible.map((e) => (
          <li key={e.slug}>
            <Link href={`/admin/${e.slug}`} className="block rounded-lg border border-slate-200 bg-white p-4 shadow-sm hover:border-brand-600">
              {t(locale, e.labelKey)}
            </Link>
          </li>
        ))}
        <li>
          <Link href="/admin/audit" className="block rounded-lg border border-slate-200 bg-white p-4 shadow-sm hover:border-brand-600">
            {t(locale, "menu.admin.audit")}
          </Link>
        </li>
      </ul>
    </div>
  );
}
