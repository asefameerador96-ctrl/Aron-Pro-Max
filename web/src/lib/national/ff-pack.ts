// Compact wire form of the FF Geo pins between the BFF and the browser (a national selection has up to 6,933 routes, 12,000 at the contract's
// cap). One tuple per pin instead of an object with six named members and an ISO time: [route_id, lat, lng, geo_pct_bp or -1, colour index,
// epoch seconds]. Coordinates keep 5 decimals (about 1 m, the contract's own precision for a pin); everything else is exact.
import type { PinColor } from "./query";
import type { FfGeoSlice, FfPin, Stamp } from "./types";

export const PIN_COLORS: readonly PinColor[] = ["red", "amber", "green"];
export type PackedPin = [route_id: number, lat: number, lng: number, geo_pct_bp: number, color: number, at: number];
export interface PackedFfGeo extends Stamp { packed: 1; pins: PackedPin[] }

const r5 = (n: number) => Math.round(n * 1e5) / 1e5;

export function packPins(slice: FfGeoSlice): PackedFfGeo {
  const pins: PackedPin[] = slice.pins.map((p) => [p.route_id, r5(p.lat), r5(p.lng), p.geo_pct_bp ?? -1, PIN_COLORS.indexOf(p.color), Math.floor(Date.parse(p.at) / 1000)]);
  return { business_date: slice.business_date, as_of: slice.as_of, packed: 1, pins };
}

export function unpackPins(p: PackedFfGeo): FfGeoSlice {
  const pins: FfPin[] = p.pins.map(([route_id, lat, lng, bp, c, at]) => ({ route_id, lat, lng, geo_pct_bp: bp < 0 ? null : bp, color: PIN_COLORS[c] ?? "red", at: new Date(at * 1000).toISOString() }));
  return { business_date: p.business_date, as_of: p.as_of, pins };
}
