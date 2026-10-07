import { SupervisorTargetsView } from "@/components/admin/config/supervisor-targets-view";
import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import type { SupervisorTarget } from "@/lib/admin/types";
import { ADMIN_ONLY_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate } from "@/lib/i18n";

export default async function SupervisorTargetsPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_ONLY_ROLES)) return <Forbidden locale={locale} />;
  const m = one(sp.month, 7);
  const month = m && /^\d{4}-(0[1-9]|1[0-2])$/.test(m) ? m : businessDate().slice(0, 7);
  const z = one(sp.zone_id, 15);
  const zone = z && /^[1-9]\d{0,14}$/.test(z) ? z : "";
  let rows: SupervisorTarget[] | null = null;
  if (zone) {
    const r = await apiGet<{ items: SupervisorTarget[] }>("/v1/admin/supervisor-targets", session.at, { month, zone_id: zone });
    if (!r.ok) return onApiFailure(r.status, r.problem, locale);
    rows = r.data.items;
  }
  return <SupervisorTargetsView locale={locale} month={month} zone={zone} rows={rows} canWrite={canOp("supervisor-targets.put", session.user.role)} />;
}
