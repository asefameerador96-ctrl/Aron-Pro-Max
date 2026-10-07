import { PrintTemplatesView } from "@/components/admin/config/print-templates-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import type { PrintTemplate } from "@/lib/admin/types";
import { ADMIN_ONLY_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

export default async function PrintTemplatesPage() {
  const [session, locale] = await Promise.all([requireSession(), getLocale()]);
  if (!hasRole(session.user.role, ADMIN_ONLY_ROLES)) return <Forbidden locale={locale} />;
  const r = await apiGet<{ items: PrintTemplate[] }>("/v1/admin/print-templates", session.at);
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);
  return <PrintTemplatesView locale={locale} rows={r.data.items} canWrite={canOp("print-template.create", session.user.role)} />;
}
