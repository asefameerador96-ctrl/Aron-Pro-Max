// Typed reads of the national dashboard slices. Server only. One function per card (docs/34 s2); a 403 on a card means the caller's token
// does not reach it (BRAND users get the product cards only), and the page then leaves that card out instead of showing an error.
import { rawRequest } from "@/lib/api/raw";
import type { ApiOutcome } from "@/lib/api/client";
import { toSearch } from "./query";
import type { BreakdownBy, BreakdownMeasure, BreakdownSlice, CprSlice, ChannelSlice, FfGeoSlice, FinalSubmitSlice, GeoFencingSlice, LoginSubmitSlice, NationalFilters, NationalQuery, RouteMapSlice, SalePositionSlice, SalesSlice } from "./types";

const BASE = "/v1/dashboards/national";
const get = <T>(token: string, name: string, q: NationalQuery | { date: string }, extra: Record<string, string> = {}): Promise<ApiOutcome<T>> =>
  rawRequest<T>({ method: "GET", path: `${BASE}/${name}`, token, query: Object.fromEntries(toSearch(q as NationalQuery, extra)) });

export const getFilters = (token: string) => get<NationalFilters>(token, "filters", { date: "" });
export const getSales = (token: string, q: NationalQuery) => get<SalesSlice>(token, "sales", q);
export const getCpr = (token: string, q: NationalQuery) => get<CprSlice>(token, "cpr", q);
export const getChannelCalls = (token: string, q: NationalQuery) => get<ChannelSlice>(token, "channel-calls", q);
export const getFinalSubmit = (token: string, q: NationalQuery) => get<FinalSubmitSlice>(token, "final-submit", q);
export const getLoginSubmit = (token: string, q: NationalQuery) => get<LoginSubmitSlice>(token, "login-submit", q);
export const getChannelStd = (token: string, q: NationalQuery) => get<ChannelSlice>(token, "channel-std", q);
export const getBreakdown = (token: string, q: NationalQuery, measure: BreakdownMeasure, by: BreakdownBy) => get<BreakdownSlice>(token, "breakdown", q, { measure, by });
export const getGeoFencing = (token: string, q: NationalQuery) => get<GeoFencingSlice>(token, "geo-fencing", q);
export const getFfGeo = (token: string, q: NationalQuery) => get<FfGeoSlice>(token, "ff-geo", q);
export const getRouteMap = (token: string, routeId: number, date: string) => get<RouteMapSlice>(token, `route-map/${routeId}`, { date });
export const getSalePosition = (token: string, outletId: number, date: string) => get<SalePositionSlice>(token, `sale-position/${outletId}`, { date });
