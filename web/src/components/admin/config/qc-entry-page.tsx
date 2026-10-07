import { QcEntryView } from "./qc-entry-view";
import { loadAllSkus } from "@/lib/admin/skus";
import { apiGet, one, type SearchParams } from "@/components/admin/kit/page";
import { onApiFailure } from "@/components/admin/crud/pages";
import { Forbidden } from "@/components/forbidden";
import { canTeamOp } from "@/lib/admin/access";
import { GEO_LEVELS, loadGeoOptions, type GeoLevel } from "@/lib/admin/geo";
import { isRealDate } from "@/lib/admin/time";
import type { CodeList, Route, Sku } from "@/lib/admin/types";
import { requireSession } from "@/lib/auth/require";
import { getLocale } from "@/lib/auth/service";
import { businessDate } from "@/lib/i18n";

export async function QcEntryPageContent({ searchParams, source }: { searchParams: Promise<SearchParams>; source: "market" | "warehouse" }) {
  const [session, locale, sp] = await Promise.all([requireSession(), getLocale(), searchParams]);
  if (!canTeamOp("qc-entry.save", session.user.role)) return <Forbidden locale={locale} />;
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
  if (source === "market" && selection.zone) {
    const r = await apiGet<{ items: Route[] }>("/v1/admin/routes", session.at, { zone_id: selection.zone, status: "active", limit: 500 });
    if (!r.ok) return onApiFailure(r.status, r.problem, locale);
    routes = r.data.items;
  }
  const rid = one(sp.route, 15);
  const routeId = rid && /^[1-9]\d{0,14}$/.test(rid) && routes?.some((r) => String(r.id) === rid) ? rid : "";
  let faultItems: CodeList["items"] | null = null;
  let skus: Sku[] = [];
  if (selection.zone && (source === "warehouse" || routeId)) {
    const [c, s] = await Promise.all([apiGet<{ lists: CodeList[] }>("/v1/admin/code-lists", session.at), loadAllSkus(session.at)]);
    faultItems = c.ok ? (c.data.lists.find((l) => l.list_key === "qc_fault_type")?.items ?? []) : null;
    skus = s;
  }
  return <QcEntryView locale={locale} source={source} options={options} selection={selection} date={date} today={today} routes={routes} routeId={routeId} faultItems={faultItems} skus={skus} />;
}
