import { onApiFailure } from "@/components/admin/crud/pages";
import { one, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import { lateRows, missingCheckoutRows, runReport } from "@/lib/admin/reports";
import { isRealDate } from "@/lib/admin/time";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate } from "@/lib/i18n";
import { DayControlView } from "./day-control-view";

export async function DayControlPageContent({ searchParams, basePath, withMissing }: { searchParams: Promise<SearchParams>; basePath: string; withMissing: boolean }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const z = one(sp.zone, 15);
  const zone = z && /^[1-9]\d{0,14}$/.test(z) ? z : "";
  const d = one(sp.date, 10);
  const date = isRealDate(d) ? d : businessDate();
  let late = null;
  let missing = null;
  let missingFailed = false;
  if (zone) {
    const [fl, att] = await Promise.all([runReport(session.at, "final-submit-log", date, zone), withMissing ? runReport(session.at, "attendance", date, zone) : null]);
    if (!fl.ok) return onApiFailure(fl.status, fl.problem, locale);
    late = { ...lateRows(fl.data), columns: fl.data.columns, total: fl.data.total_rows };
    if (att?.ok) missing = { ...missingCheckoutRows(att.data), columns: att.data.columns, total: att.data.total_rows };
    else if (att) missingFailed = true;
  }
  return <DayControlView locale={locale} zone={zone} date={date} late={late} missing={missing} canWrite={canOp("day.reopen", session.user.role)} withMissing={withMissing} basePath={basePath} missingFailed={missingFailed} />;
}
