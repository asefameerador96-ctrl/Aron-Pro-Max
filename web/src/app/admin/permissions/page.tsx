import { PermissionsView } from "@/components/admin/config/permissions-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { ALL_ROLES, ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import type { PermissionMatrix } from "@/lib/admin/types";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

export default async function PermissionsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const r = await apiGet<PermissionMatrix>("/v1/admin/permissions", session.at);
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);
  const pick = one(sp.role, 12);
  const role = pick && (ALL_ROLES as readonly string[]).includes(pick) ? pick : "";
  return <PermissionsView locale={locale} matrix={r.data} role={role} canWrite={canOp("permissions.put", session.user.role)} />;
}
