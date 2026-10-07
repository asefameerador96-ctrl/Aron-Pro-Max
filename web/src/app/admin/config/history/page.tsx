import { HistoryView, compareVersions } from "@/components/admin/config/history-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { versionDetail } from "@/lib/admin/config-load";
import type { ConfigVersionPage } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

const id = (v: string | undefined) => (v && /^\d{1,10}$/.test(v) ? v : undefined);

export default async function HistoryPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const r = await apiGet<ConfigVersionPage>("/v1/admin/config/versions", session.at, { cursor: one(sp.cursor, 512), limit: 50 });
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);
  const a = id(one(sp.a, 10));
  const b = id(one(sp.b, 10));
  let compare = null;
  if (a && b) {
    const [da, db] = await Promise.all([versionDetail(session.at, Number(a)), versionDetail(session.at, Number(b))]);
    if (da.ok && db.ok) compare = compareVersions(da.data, db.data);
  }
  const nextHref = r.data.next_cursor ? `/admin/config/history${qs({ a, b, cursor: r.data.next_cursor })}` : null;
  return <HistoryView locale={locale} versions={r.data.items} nextHref={nextHref} canWrite={canOp("config.rollback", session.user.role)} compare={compare} a={a ?? ""} b={b ?? ""} />;
}
