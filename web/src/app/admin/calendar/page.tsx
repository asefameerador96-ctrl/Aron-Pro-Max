import { CalendarView } from "@/components/admin/config/calendar-view";
import { apiGet, one, type SearchParams } from "@/components/admin/kit/page";
import { onApiFailure } from "@/components/admin/crud/pages";
import { Forbidden } from "@/components/forbidden";
import { canOp } from "@/lib/admin/access";
import type { ConfigKey, Holiday, ResolvedConfigValue } from "@/lib/admin/types";
import { ADMIN_PORTAL_ROLES, hasRole } from "@/lib/auth/roles";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate } from "@/lib/i18n";

const DAY = 86_400_000;
const isDate = (s: string | undefined): s is string => !!s && /^\d{4}-\d{2}-\d{2}$/.test(s);
const addDays = (ymd: string, n: number) => new Date(Date.parse(`${ymd}T00:00:00Z`) + n * DAY).toISOString().slice(0, 10);

export default async function CalendarPage({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!hasRole(session.user.role, ADMIN_PORTAL_ROLES)) return <Forbidden locale={locale} />;
  const today = businessDate();
  const f = one(sp.from, 10);
  const from = isDate(f) ? f : addDays(today, -7);
  const to = addDays(from, 83);
  const [list, weekend, keys] = await Promise.all([
    apiGet<{ items: Holiday[] }>("/v1/admin/calendar/holidays", session.at, { from, to }),
    apiGet<ResolvedConfigValue>("/v1/admin/config/resolve", session.at, { key: "cfg.calendar.weekend_days", node_type: "global", node_id: 0 }),
    apiGet<{ items: ConfigKey[] }>("/v1/admin/config/keys", session.at, { area: "calendar" }),
  ]);
  if (!list.ok) return onApiFailure(list.status, list.problem, locale);
  const weekendKey = keys.ok ? (keys.data.items.find((k) => k.key === "cfg.calendar.weekend_days") ?? null) : null;
  return <CalendarView locale={locale} holidays={list.data.items} weekend={weekend.ok ? weekend.data : null} weekendKey={weekendKey} from={from} to={to} canWrite={canOp("holiday.create", session.user.role)} />;
}
