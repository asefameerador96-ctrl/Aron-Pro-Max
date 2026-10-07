import { Forbidden } from "@/components/forbidden";
import { TransferForm, type OpenAssignment } from "@/components/admin/transfer-form";
import { FilterBar } from "@/components/admin/kit/filter-bar";
import { loadAllRows } from "@/components/admin/crud/server";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate, t } from "@/lib/i18n";

// SR transfer between routes (F-ADM-071): a new primary assignment from a date and the old one ending at it. Targets belong to
// the route, not the SR, so they stay where they are; the SR's next bundle carries the new route (server side).
export default async function SrTransferPage({ searchParams }: { searchParams: Promise<Record<string, string | string[] | undefined>> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const raw = Array.isArray(sp.user_id) ? sp.user_id[0] : sp.user_id;
  const userId = raw && /^[0-9]{1,15}$/.test(raw) ? Number(raw) : null;
  const [users, routes] = await Promise.all([loadAllRows("/v1/admin/users", session.at, { status: "active" }), loadAllRows("/v1/admin/routes", session.at, { status: "active" })]);
  const srs = users.rows.filter((u) => u.role === "SR");
  const controls = [{ param: "user_id", label: t(locale, "entity.field.user"), kind: "enum" as const, value: userId ? String(userId) : "", options: srs.map((u) => ({ value: String(u.id), label: `${u.username} · ${u.full_name}` })) }];
  const routeLabel = (id: unknown) => {
    const r = routes.rows.find((x) => x.id === id);
    return r ? `${r.code} · ${r.name}` : String(id);
  };
  let content = <p className="text-slate-600">{t(locale, "transfer.pick_sr")}</p>;
  if (userId !== null) {
    if (!srs.some((u) => u.id === userId)) return <Forbidden locale={locale} />;
    const today = businessDate();
    const asg = await loadAllRows("/v1/admin/route-assignments", session.at, { user_id: userId, valid_on: today });
    const open: OpenAssignment[] = asg.rows.map((a) => ({ id: Number(a.id), routeId: Number(a.route_id), routeLabel: routeLabel(a.route_id), kind: String(a.kind), validFrom: String(a.valid_from) }));
    content = <TransferForm userId={userId} open={open} routes={routes.rows.map((r) => ({ value: String(r.id), label: `${r.code} · ${r.name}` }))} minDate={today} />;
  }
  return (
    <div className="space-y-4">
      <h1 className="text-2xl font-bold">{t(locale, "transfer.title")}</h1>
      <FilterBar controls={controls} applyLabel={t(locale, "common.filter")} clearLabel={t(locale, "common.clear")} allLabel={t(locale, "common.all")} clearHref="/admin/sr-transfer" />
      {users.failed || routes.failed ? <p role="alert" className="rounded border border-red-200 bg-red-50 p-3 text-red-800">{t(locale, "error.ref_load")}</p> : null}
      {content}
    </div>
  );
}
