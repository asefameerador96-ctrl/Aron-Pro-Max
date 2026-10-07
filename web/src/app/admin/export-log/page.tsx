import { ExportLogView } from "@/components/admin/config/export-log-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, qs, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { isRealDate } from "@/lib/admin/time";
import type { ExportLogPage } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";

export default async function ExportLogPageRoute({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const u = one(sp.user_id, 15);
  const k = one(sp.report_key, 40);
  const f = one(sp.from, 10);
  const to = one(sp.to, 10);
  const filters = { user_id: u && /^[1-9]\d{0,14}$/.test(u) ? u : "", report_key: k && /^[a-z0-9-]{2,40}$/.test(k) ? k : "", from: isRealDate(f) ? f : "", to: isRealDate(to) ? to : "" };
  const r = await apiGet<ExportLogPage>("/v1/report-exports", session.at, { ...filters, cursor: one(sp.cursor, 512), limit: 100 });
  if (!r.ok) return onApiFailure(r.status, r.problem, locale);
  return <ExportLogView locale={locale} rows={r.data.items} filters={filters} nextHref={r.data.next_cursor ? `/admin/export-log${qs({ ...filters, cursor: r.data.next_cursor })}` : null} />;
}
