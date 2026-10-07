import { onApiFailure } from "@/components/admin/crud/pages";
import { apiGet, one, type SearchParams } from "@/components/admin/kit/page";
import { Forbidden } from "@/components/forbidden";
import { canTeamOp } from "@/lib/admin/access";
import { GEO_LEVELS, loadGeoOptions, type GeoLevel } from "@/lib/admin/geo";
import { isRealDate } from "@/lib/admin/time";
import type { Route, Sku, WebEntryRouteDay } from "@/lib/admin/types";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate } from "@/lib/i18n";
import { WebEntryView } from "./web-entry-view";

export async function WebEntryPageContent({ searchParams }: { searchParams: Promise<SearchParams> }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!canTeamOp("web-entry.save", session.user.role)) return <Forbidden locale={locale} />;
  const selection: Partial<Record<GeoLevel, string>> = {};
  for (const l of GEO_LEVELS) {
    const v = one(sp[l], 16);
    if (v && /^[1-9]\d{0,14}$/.test(v)) selection[l] = v;
  }
  const today = businessDate();
  const d = one(sp.date, 10);
  const date = isRealDate(d) && d <= today ? d : today;
  const options = await loadGeoOptions(session.at, selection);
  let routes: Route[] | null = null;
  if (selection.zone) {
    const r = await apiGet<{ items: Route[] }>("/v1/admin/routes", session.at, { zone_id: selection.zone, status: "active", limit: 500 });
    if (!r.ok) return onApiFailure(r.status, r.problem, locale);
    routes = r.data.items;
  }
  const rid = one(sp.route, 15);
  const routeId = rid && /^[1-9]\d{0,14}$/.test(rid) && routes?.some((r) => String(r.id) === rid) ? rid : "";
  let entry: WebEntryRouteDay | null = null;
  let skus: Sku[] = [];
  if (routeId) {
    const [e, s] = await Promise.all([apiGet<WebEntryRouteDay>("/v1/web-entry/route-day", session.at, { route_id: routeId, business_date: date }), apiGet<{ items: Sku[] }>("/v1/admin/skus", session.at, { status: "active", limit: 500 })]);
    if (!e.ok) return onApiFailure(e.status, e.problem, locale);
    entry = e.data;
    skus = s.ok ? s.data.items : [];
  }
  return <WebEntryView locale={locale} options={options} selection={selection} date={date} today={today} routes={routes} routeId={routeId} entry={entry} skus={skus} canWrite />;
}
