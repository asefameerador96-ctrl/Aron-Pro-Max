import { CalendarView } from "@/components/admin/config/calendar-view";
import { apiGet, one, type SearchParams } from "@/components/admin/kit/page";
import { onApiFailure } from "@/components/admin/crud/pages";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import type { ConfigKey, Holiday, ResolvedConfigValue } from "@/lib/admin/types";
import { ADMIN_ONLY_ROLES, ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { isRealDate } from "@/lib/admin/time";
import { monthDaysOf } from "@/lib/admin/calendar-days";
import type { Schemas } from "@/contract/types";
import { businessDate } from "@/lib/i18n";

const DAY = 86_400_000;

const addDays = (ymd: string, n: number) => new Date(Date.parse(`${ymd}T00:00:00Z`) + n * DAY).toISOString().slice(0, 10);

export default async function CalendarPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const today = businessDate();
  const f = one(sp.from, 10);
  const from = isRealDate(f) ? f : addDays(today, -7);
  const to = addDays(from, 83);
  // Working days of this month and the next two (national entries): the bases of the route monthly targets.
  const m0 = today.slice(0, 7);
  const monthAfter = (m: string, n: number) => { const d = new Date(Date.parse(`${m}-01T00:00:00Z`)); d.setUTCMonth(d.getUTCMonth() + n); return d.toISOString().slice(0, 7); };
  const months = [m0, monthAfter(m0, 1), monthAfter(m0, 2)];
  const [list, weekend, keys, ...monthLists] = await Promise.all([
    apiGet<{ items: Holiday[] }>("/v1/admin/calendar/holidays", session.at, { from, to }),
    apiGet<ResolvedConfigValue>("/v1/admin/config/resolve", session.at, { key: "cfg.calendar.weekend_days", node_type: "global", node_id: 0 }),
    // The registry (for the edit form) is ADMIN/SUPERADMIN only; SUPPORT reads the weekend days through its resolve grant (D-AUTHZ-Q2).
    hasRole(session.user.role, ADMIN_ONLY_ROLES) ? apiGet<{ items: ConfigKey[] }>("/v1/admin/config/keys", session.at, { area: "calendar" }) : null,
    ...months.map((month) => apiGet<Schemas["WorkingDays"]>("/v1/admin/calendar/working-days", session.at, { month })),
  ]);
  if (!list.ok) return onApiFailure(list.status, list.problem, locale);
  // The server's count (national entries, make-up days included); the table stays hidden when the server cannot answer.
  const monthDays = monthLists.every((m) => m.ok) ? monthLists.map((m) => monthDaysOf((m as { ok: true; data: Schemas["WorkingDays"] }).data)) : null;
  const weekendKey = keys?.ok ? (keys.data.items.find((k) => k.key === "cfg.calendar.weekend_days") ?? null) : null;
  return (
    <div className="space-y-4">
      <CalendarView locale={locale} holidays={list.data.items} weekend={weekend.ok ? weekend.data : null} weekendKey={weekendKey} from={from} to={to} canWrite={canOp("holiday.create", session.user.role)} monthDays={monthDays} />
    </div>
  );
}
