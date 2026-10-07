import { Forbidden } from "@/components/forbidden";
import { DefinitionManager } from "@/components/admin/definition-manager";
import { loadAllRows } from "@/components/admin/crud/server";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { t } from "@/lib/i18n";

// Rubric definitions (F-ADM-020). SUPPORT reads; ADMIN and SUPERADMIN publish a new version.
export default async function Page() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const list = await loadAllRows("/v1/admin/rubrics", session.at);
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "def.rubrics.title")}</h1>
      {list.failed ? <p role="alert" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">{t(locale, "error.ref_load")}</p> : null}
      <DefinitionManager kind="rubrics" items={list.rows as never} canWrite={hasRole(session.user.role, ["ADMIN", "SUPERADMIN"])} />
    </div>
  );
}
