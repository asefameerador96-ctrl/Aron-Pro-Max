import { ChangesView } from "@/components/admin/config/changes-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { one, qs, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { changesOf } from "@/lib/admin/config-load";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

const STATUSES = ["pending_approval", "scheduled", "applied", "rejected", "cancelled", "expired", "reverted"];

export default async function ChangesPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const s = one(sp.status, 24);
  const status = s && STATUSES.includes(s) ? s : "";
  const r = await changesOf(session.at, { status: status || undefined, cursor: one(sp.cursor, 512), limit: 50 });
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);
  const nextHref = r.data.next_cursor ? `/admin/config/changes${qs({ status, cursor: r.data.next_cursor })}` : null;
  return <ChangesView locale={locale} rows={r.data.items} status={status} nextHref={nextHref} userId={session.user.user_id} canDecide={canOp("config.decide", session.user.role)} role={session.user.role} />;
}
