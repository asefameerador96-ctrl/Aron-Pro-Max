import { DuesView } from "@/components/admin/config/dues-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import type { DuesAdjustmentPage } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

export default async function DuesPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const st = one(sp.status, 10);
  const status = st && ["pending", "approved", "rejected"].includes(st) ? st : "";
  const o = one(sp.outlet_id, 15);
  const outlet = o && /^[1-9]\d{0,14}$/.test(o) ? o : "";
  const r = await apiGet<DuesAdjustmentPage>("/v1/admin/dues-adjustments", session.at, { status, outlet_id: outlet, cursor: one(sp.cursor, 512), limit: 50 });
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);
  return <DuesView locale={locale} rows={r.data.items} status={status} outlet={outlet} nextHref={r.data.next_cursor ? `/admin/dues${qs({ status, outlet_id: outlet, cursor: r.data.next_cursor })}` : null} userId={session.user.user_id} canCreate={canOp("dues.create", session.user.role)} canDecide={canOp("dues.decide", session.user.role)} />;
}
