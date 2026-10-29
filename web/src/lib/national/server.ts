// Typed reads of the national dashboard slices (contract 1.4.0, lane backend-reports). Server only. One function per card; each calls the
// generated operation and maps the answer to the view-model of `types.ts`, so the cards do not depend on the wire shape. A 403 on a card means
// the caller's token does not reach it (BRAND users get the product cards only): the page leaves that card out instead of showing an error.
import type { Schemas } from "@/contract/types";
import { rawRequest } from "@/lib/api/raw";
import type { ApiOutcome } from "@/lib/api/client";
import { CATEGORIES, CHANNELS, type BreakdownBy, type BreakdownMeasure, type BreakdownSlice, type CategoryCode, type ChannelCode, type ChannelSlice, type CprSlice, type FfGeoSlice, type FfPopup, type FinalSubmitSlice, type GeoFencingSlice, type LoginSubmitSlice, type NationalFilters, type NationalQuery, type Option, type ProductOption, type RouteMapSlice, type SalePositionSlice, type SalesSlice, type Stamp, type UnitCode } from "./types";

const BASE = "/v1/dashboards";
type Wire<K extends keyof Schemas> = Schemas[K];

/** Run the request and map a successful answer; failures pass through unchanged. */
async function run<W, T>(req: Parameters<typeof rawRequest>[0], map: (w: W) => T | Promise<T>): Promise<ApiOutcome<T>> {
  const r = await rawRequest<W>(req);
  return r.ok ? { ...r, data: await map(r.data) } : r;
}
const post = <W, T>(token: string, path: string, body: unknown, map: (w: W) => T | Promise<T>) => run<W, T>({ method: "POST", path: `${BASE}/${path}`, token, body }, map);
const get = <W, T>(token: string, path: string, query: Record<string, string | undefined>, map: (w: W) => T | Promise<T>) => run<W, T>({ method: "GET", path: `${BASE}/${path}`, token, query }, map);

/** The filter the user picked, as the contract's request body. Members left out mean "everything in my scope". */
export function toFilter(q: NationalQuery): Wire<"NationalDashboardFilter"> {
  return {
    date: q.date,
    ...(q.wing && { wing_ids: q.wing }), ...(q.division && { division_ids: q.division }), ...(q.territory && { territory_ids: q.territory }), ...(q.house && { house_ids: q.house }), ...(q.zone && { zone_ids: q.zone }),
    ...(q.category && { category_codes: q.category }), ...(q.segment && { segment_codes: q.segment }), ...(q.brand && { brand_codes: q.brand }), ...(q.variant && { variant_codes: q.variant }),
  };
}

const stamp = (w: { date: string; last_updated_at: string | null; as_of: string }): Stamp => ({ business_date: w.date, as_of: w.last_updated_at ?? w.as_of });
const num = (s: string | null | undefined): number | null => (s === null || s === undefined || s === "" || !Number.isFinite(Number(s)) ? null : Number(s));
const isCategory = (c: string): c is CategoryCode => (CATEGORIES as readonly string[]).includes(c);
const channels = (w: { channels: { channel: ChannelCode; value: number; share_pct_bp: number | null }[] }) => (CHANNELS as readonly string[]).flatMap((c) => w.channels.filter((x) => x.channel === c).map((x) => ({ code: x.channel, value: x.value, share_bp: x.share_pct_bp })));

export const getFilters = (token: string) =>
  get<Wire<"NationalFilterOptions">, NationalFilters>(token, "national/filter-options", {}, (w) => {
    const node = (n: Wire<"FilterNode">, parent: number | null | undefined): Option => ({ id: n.id, name: n.name ?? n.code, parent: parent ?? null });
    const code = (n: Wire<"FilterCode">, parent: string | null | undefined): ProductOption => ({ code: n.code, name: n.name ?? n.code, parent: parent ?? null });
    return {
      wings: w.wings.map((n) => node(n, null)), divisions: w.divisions.map((n) => node(n, n.wing_id)), territories: w.territories.map((n) => node(n, n.division_id)),
      houses: w.houses.map((n) => node(n, n.territory_id)), zones: w.zones.map((n) => node(n, n.territory_id)),
      categories: w.categories.map((n) => code(n, null)), segments: w.segments.map((n) => code(n, n.category_code)), brands: w.brands.map((n) => code(n, n.segment_code)), variants: w.variants.map((n) => code(n, n.brand_code)),
      view: { product_only: false }, // decided by the page from the cards the token may not read
    };
  });

export const getSales = (token: string, q: NationalQuery) =>
  post<Wire<"NationalSales">, SalesSlice>(token, "national/sales", toFilter(q), (w) => ({
    ...stamp(w),
    cards: w.cards.filter((c) => isCategory(c.category_code)).map((c) => ({ category: c.category_code as CategoryCode, unit: c.base_unit as UnitCode, total: c.total_sales, daily_target: num(c.daily_target), pct_bp: c.achievement_pct_bp, remaining: num(c.remaining), monthly_target_qty: c.monthly_target_qty })), // tso-manual W1: + monthly_target_qty
    working_days: w.working_days, // tso-manual W1 (D-TSO-MAN-10): the ring's W
  }));
export const getCpr = (token: string, q: NationalQuery) => post<Wire<"NationalCpr">, CprSlice>(token, "national/cpr", toFilter(q), (w) => ({ ...stamp(w), target_outlets: w.target_outlets, successful_calls: w.successful_calls, cpr_pct_bp: w.cpr_pct_bp }));
export const getChannelCalls = (token: string, q: NationalQuery) => post<Wire<"NationalChannelCalls">, ChannelSlice>(token, "national/channel-calls", toFilter(q), (w) => ({ ...stamp(w), channels: channels(w), denominator: w.total + w.unclassified })); // tso-manual X3: the share's denominator (R-W-06)
export const getChannelStd = (token: string, q: NationalQuery) => post<Wire<"NationalChannelStd">, ChannelSlice>(token, "national/channel-std", toFilter(q), (w) => ({ ...stamp(w), channels: channels(w), denominator: w.total })); // tso-manual X3: the share's denominator (R-W-06)
export const getFinalSubmit = (token: string, q: NationalQuery) => post<Wire<"NationalFinalSubmit">, FinalSubmitSlice>(token, "national/final-submit", toFilter(q), (w) => ({ ...stamp(w), total_zone: w.total_zones, service_zone: w.service_zones, submitted: w.final_submitted, submit_pct_bp: w.submit_pct_bp }));
export const getLoginSubmit = (token: string, q: NationalQuery) => post<Wire<"NationalLoginSubmit">, LoginSubmitSlice>(token, "national/login-submit", toFilter(q), (w) => ({ ...stamp(w), target_routes: w.target_routes, logged_in: w.successful_login, submitted: w.submitted, login_pct_bp: w.login_pct_bp, submit_pct_bp: w.submit_pct_bp }));
export const getGeoFencing = (token: string, q: NationalQuery) => post<Wire<"NationalGeoFencing">, GeoFencingSlice>(token, "national/geo-fencing", toFilter(q), (w) => ({ ...stamp(w), total_calls: w.total_calls, geo_validation: w.geo_validated, geo_pct_bp: w.geo_pct_bp }));
export const getBreakdown = (token: string, q: NationalQuery, measure: BreakdownMeasure, by: BreakdownBy) =>
  post<Wire<"NationalBreakdown">, BreakdownSlice>(token, "national/breakdown", { filter: toFilter(q), measure, dimension: by, ...(q.category?.length === 1 && { category_code: q.category[0] }) }, (w) => ({
    ...stamp(w), measure: w.measure, by: w.dimension, rows: w.items.map((i) => ({ key: i.code, label: i.name ?? i.code, value: i.value, unit: w.unit ?? null })),
  }));
export const getFfGeo = (token: string, q: NationalQuery) =>
  post<Wire<"FfGeoPins">, FfGeoSlice>(token, "national/ff-geo/pins", { filter: toFilter(q) }, (w) => ({ ...stamp(w), pins: w.pins.map((p) => ({ route_id: p.route_id, lat: p.lat, lng: p.lng, geo_pct_bp: p.geo_pct_bp, color: p.color, at: p.position_at })) }));
export const getFfPopup = (token: string, routeId: number, date: string) =>
  get<Wire<"FfGeoRoutePopup">, FfPopup>(token, `national/ff-geo/routes/${routeId}`, { date }, (w) => ({ sr_name: w.sr_name, contact: w.contact, territory: w.territory.name ?? w.territory.code, territory_code: w.territory.code, zone: w.zone.name ?? w.zone.code, route: w.route.name ?? w.route.code, position_at: w.position_at, miss_pct_bp: w.miss_pct_bp }));

export const getRouteMap = (token: string, routeId: number, date: string) =>
  get<Wire<"RouteMap">, RouteMapSlice>(token, `route-map/${routeId}`, { date }, (w) => {
    const qtyOf = (o: Wire<"RouteMapOutlet">) => Object.fromEntries(CATEGORIES.map((c) => [c, o.sales.find((s) => s.category_code === c)?.qty ?? 0])) as Record<CategoryCode, number>;
    const hours = (sec: number | null | undefined) => (sec === null || sec === undefined ? null : Math.round((sec / 3600) * 100) / 100);
    return {
      ...stamp(w), sr_name: w.sr_name ?? "—", territory_code: w.territory.code, territory_name: w.territory.name ?? w.territory.code, zone: w.zone.name ?? w.zone.code, route: w.route.name ?? w.route.code,
      start: w.sale_start, last_position: w.sr_last_position, path: w.path,
      outlets: w.outlets.map((o) => ({ id: o.outlet_id, code: o.code, name: o.name ?? o.code, contact: o.contact ?? null, cluster: o.cluster_name ?? "—", lat: o.lat, lng: o.lng, state: o.state === "geo_validated" ? "geo" : o.state === "photo_validation" ? "photo" : "not_called", at: o.call_time, sales: o.call_time ? qtyOf(o) : null })),
      target_vs_achievement: w.target_vs_achievement.filter((t) => isCategory(t.category_code)).map((t) => ({ category: t.category_code as CategoryCode, pct_bp: t.achievement_pct_bp, sales: t.sales, daily_target: t.daily_target, monthly_target_qty: t.monthly_target_qty, working_days: t.working_days })), // tso-manual W1: + sales, daily_target; X3: + monthly_target_qty, working_days
      last_sale_place: w.last_sale_place ?? null,
      cpr: { target_outlets: w.cpr.target_outlets, successful_calls: w.cpr.successful_calls, geo_validation: w.cpr.geo_validated, cpr_pct_bp: w.cpr.cpr_pct_bp },
      time: { first_sale: w.time_card.first_sale_time ?? null, last_sale: w.time_card.last_sale_time ?? null, total_hours: hours(w.time_card.total_sale_seconds), avg_hours: hours(w.time_card.average_sale_seconds), distance_m: w.time_card.distance_covered_m },
    };
  });

export const getSalePosition = (token: string, outletId: number, date: string) =>
  get<Wire<"SalePosition">, SalePositionSlice>(token, `sale-position/${outletId}`, { date }, async (w) => {
    // A force-sale photo is a media id: ask for its short-lived read URL now, so the page shows it (a failure only drops the picture).
    const photo = async (id: string | null | undefined): Promise<string | null> => {
      if (!id) return null;
      const r = await rawRequest<Wire<"MediaReadUrl">>({ method: "GET", path: `/v1/media/${id}/read-url`, token });
      return r.ok ? r.data.url : null;
    };
    const photos = await Promise.all(w.sales.map((s) => photo(s.photo_media_uuid)));
    return { ...stamp(w), zone: w.zone.name ?? w.zone.code, route: w.route.name ?? w.route.code, outlet_code: w.outlet_code, radius_m: w.radius_m, header_distance_m: w.header_distance_m ?? null, base: w.base, sales: w.sales.map((s, i) => ({ id: s.memo_uuid, kind: s.kind, lat: s.lat, lng: s.lng, distance_m: s.distance_m, at: s.at, photo_url: photos[i] ?? null })) };
  });
