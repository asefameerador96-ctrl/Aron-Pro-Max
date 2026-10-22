// The national dashboard slices (docs/ui-reference/web/dashboard-brief.md, D-DASH-02), typed by hand until backend-reports publishes
// them in the contract (docs/34 s2). One shape per card; every figure is a base unit (sticks, pieces, dozens) or a count, never a
// formatted string. Scope (geography, brands) is decided by the server from the token; the client only sends the filter the user picked.
import type { PinColor } from "./query";

export const CATEGORIES = ["cigarette", "bidi", "lighter", "match"] as const;
export type CategoryCode = (typeof CATEGORIES)[number];
export type UnitCode = "stick" | "piece" | "dozen";

export const CHANNELS = ["GT", "DCC", "Astha", "RCC", "MT", "HoReCa"] as const;
export type ChannelCode = (typeof CHANNELS)[number];

export interface Stamp {
  business_date: string;
  /** ISO instant of the newest synced event behind the figures (the "last updated" time). */
  as_of: string | null;
}

export interface Option {
  id: number;
  name: string;
  /** Id of the parent option one level up (division -> wing, territory -> division, house -> territory, zone -> territory). */
  parent: number | null;
}
export interface ProductOption {
  code: string;
  name: string;
  /** Code of the parent level (segment -> category, brand -> segment, variant -> brand). */
  parent: string | null;
}
export interface NationalFilters {
  wings: Option[];
  divisions: Option[];
  territories: Option[];
  /** Active houses only, each once (UI-WEB-14). */
  houses: Option[];
  zones: Option[];
  categories: ProductOption[];
  segments: ProductOption[];
  brands: ProductOption[];
  variants: ProductOption[];
  view: NationalView;
}

/** What the user picked. An absent member means "all in my scope". */
export interface NationalQuery {
  date: string;
  wing?: number[];
  division?: number[];
  territory?: number[];
  house?: number[];
  zone?: number[];
  category?: string[];
  segment?: string[];
  brand?: string[];
  variant?: string[];
}

export interface SalesCard {
  category: CategoryCode;
  unit: UnitCode;
  total: number;
  /** Full-day target of the selected routes (monthly target / working days); null when no target is loaded. */
  daily_target: number | null;
  /** Total against the daily target in basis points (0 to 10000 and beyond), as the server computes it; null without a target. */
  pct_bp: number | null;
  /** Target minus sales, never below 0 (null without a target). */
  remaining: number | null;
}
export interface SalesSlice extends Stamp {
  cards: SalesCard[];
}
export interface CprSlice extends Stamp {
  target_outlets: number;
  successful_calls: number;
  cpr_pct_bp: number | null;
}
export interface ChannelCount {
  code: ChannelCode;
  value: number;
  /** Share of the channel total in basis points. */
  share_bp: number | null;
}
export interface ChannelSlice extends Stamp {
  channels: ChannelCount[];
}
export interface FinalSubmitSlice extends Stamp {
  total_zone: number;
  service_zone: number;
  submitted: number;
  submit_pct_bp: number | null;
}
export interface LoginSubmitSlice extends Stamp {
  target_routes: number;
  logged_in: number;
  submitted: number;
  login_pct_bp: number | null;
  submit_pct_bp: number | null;
}
export type BreakdownMeasure = "std" | "memo";
export type BreakdownBy = "segment" | "brand" | "variant";
export interface BreakdownRow {
  key: string;
  label: string;
  value: number;
  /** STD mode: the unit of the value, so mixed units are never silently added or compared. */
  unit: UnitCode | null;
}
export interface BreakdownSlice extends Stamp {
  measure: BreakdownMeasure;
  by: BreakdownBy;
  rows: BreakdownRow[];
}
export interface GeoFencingSlice extends Stamp {
  total_calls: number;
  geo_validation: number;
  geo_pct_bp: number | null;
}

export interface FfPin {
  route_id: number;
  lat: number;
  lng: number;
  /** Today's share of calls inside the geo-fence in basis points (null: no call yet). */
  geo_pct_bp: number | null;
  /** The server's colour of the pin (Red below 70, Amber 70 to 90, Green above 90). */
  color: PinColor;
  /** Time of the latest position. */
  at: string;
}
/** The pop-up of one route pin, read on click (the server decides who sees the name and the phone). */
export interface FfPopup {
  sr_name: string | null;
  contact: string | null;
  territory: string;
  territory_code: string;
  zone: string;
  route: string;
  position_at: string | null;
  miss_pct_bp: number | null;
}
export interface FfGeoSlice extends Stamp {
  pins: FfPin[];
}

export type OutletMarkState = "not_called" | "geo" | "photo";
export interface RouteOutlet {
  id: number;
  code: string;
  name: string;
  contact: string | null;
  cluster: string;
  lat: number;
  lng: number;
  state: OutletMarkState;
  /** Call time (ISO), for called outlets. */
  at: string | null;
  sales: Record<CategoryCode, number> | null;
}
export interface RouteMapSlice extends Stamp {
  sr_name: string;
  territory_code: string;
  territory_name: string;
  zone: string;
  route: string;
  start: { lat: number; lng: number; at: string } | null;
  last_position: { lat: number; lng: number; at: string } | null;
  path: { lat: number; lng: number; at: string }[];
  outlets: RouteOutlet[];
  target_vs_achievement: { category: CategoryCode; pct_bp: number | null }[];
  last_sale_place: string | null;
  cpr: { target_outlets: number; successful_calls: number; geo_validation: number; cpr_pct_bp: number | null };
  time: { first_sale: string | null; last_sale: string | null; total_hours: number | null; avg_hours: number | null; distance_m: number };
}

export type SaleKind = "new" | "repeat" | "edited";
export interface SalePoint {
  id: string;
  kind: SaleKind;
  lat: number;
  lng: number;
  distance_m: number;
  at: string;
  /** Force-sale photo (a URL the portal may show), only when the call was outside the fence. */
  photo_url: string | null;
}
export interface SalePositionSlice extends Stamp {
  zone: string;
  route: string;
  outlet_code: string;
  radius_m: number;
  /** Distance of the header line (base to the first sale position), null with no sale. */
  header_distance_m: number | null;
  base: { lat: number; lng: number };
  sales: SalePoint[];
}

/** Which cards the caller's token may see (BRAND users get the product cards only, docs/34 s1). */
export interface NationalView {
  product_only: boolean;
}
