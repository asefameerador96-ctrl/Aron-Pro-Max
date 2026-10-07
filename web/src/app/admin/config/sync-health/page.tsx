import { SyncHealthView } from "@/components/admin/config/sync-health-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import type { SyncHealthPage } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { isRealDate } from "@/lib/admin/time";
import { businessDate } from "@/lib/i18n";

export default async function SyncHealthAdminPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const d = one(sp.business_date, 10);
  const date = isRealDate(d) ? d : businessDate();
  const onlyProblems = one(sp.only_problems, 5) === "true";
  const r = await apiGet<SyncHealthPage>("/v1/dashboards/sync-health", session.at, { business_date: date, only_problems: onlyProblems ? "true" : undefined, cursor: one(sp.cursor, 512), limit: 100 });
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);
  const nextHref = r.data.next_cursor ? `/admin/config/sync-health${qs({ business_date: date, only_problems: onlyProblems ? "true" : undefined, cursor: r.data.next_cursor })}` : null;
  return <SyncHealthView locale={locale} data={r.data} date={date} onlyProblems={onlyProblems} nextHref={nextHref} />;
}
