import { notFound } from "next/navigation";
import { Forbidden } from "@/components/forbidden";
import { UserAccessEditor, type BrandOption } from "@/components/admin/user-access-editor";
import { apiGet } from "@/components/admin/kit/page";
import { loadRefOptionsChecked } from "@/components/admin/crud/server";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { isAppOnly, type UserAccess } from "@/lib/admin/access-grants";
import { problemMessage, t, type MessageKey } from "@/lib/i18n";

// Module grants of an ADMIN and brand scope of a BRAND user (docs/34 s1). Read by the portal roles, written by SUPERADMIN only.
// The grants API is PROVISIONAL (REQUEST: docs/requests/backend-admin-user-access.md): the mock serves it.
export default async function UserAccessPage({ params }: { params: Promise<{ id: string }> }) {
  const [{ id }, session, locale] = await Promise.all([params, requireSession(), getLocale()]);
  if (!/^[0-9]{1,15}$/.test(id)) notFound();
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const access = await apiGet<UserAccess>(`/v1/admin/users/${id}/access`, session.at);
  if (!access.ok) {
    if (access.status === 404) notFound();
    return <p role="alert" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">{problemMessage(locale, access.problem.code)}</p>;
  }
  const user = await apiGet<{ username: string; full_name: string }>(`/v1/admin/users/${id}`, session.at);
  // Brand options only when the page can use them: the brand level of the product tree.
  const brandLoad = access.data.role === "BRAND" ? await loadRefOptionsChecked({ path: "/v1/admin/product-nodes/{level}", params: { level: "brand" }, label: ["name"] }, session.at) : { options: [], failed: false };
  const brands: BrandOption[] = brandLoad.options.map((o) => ({ id: Number(o.value), label: o.label }));
  const roleKey = (isAppOnly(access.data.role) ? `role.opt.${access.data.role}` : `role.${access.data.role}`) as MessageKey;
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "access.title")}</h1>
      <p className="text-slate-700" data-testid="access-user">
        {user.ok ? `${user.data.username} · ${user.data.full_name} · ` : ""}
        {t(locale, roleKey)}
      </p>
      {brandLoad.failed ? <p role="alert" data-testid="brands-load-failed" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">{t(locale, "access.load_failed")}</p> : null}
      <UserAccessEditor access={access.data} brands={brands} canWrite={session.user.role === "SUPERADMIN"} />
    </div>
  );
}
