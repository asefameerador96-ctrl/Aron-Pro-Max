import { describe, expect, it } from "vitest";
import { packPins, unpackPins } from "@/lib/national/ff-pack";
import type { FfGeoSlice } from "@/lib/national/types";

const slice: FfGeoSlice = {
  business_date: "2026-10-22", as_of: "2026-10-22T06:00:00.000Z",
  pins: [
    { route_id: 7, lat: 23.7808123, lng: 90.4167891, geo_pct_bp: 9500, color: "green", at: "2026-10-22T05:59:01.000Z" },
    { route_id: 8, lat: 23.78, lng: 90.41, geo_pct_bp: null, color: "red", at: "2026-10-22T05:00:00.000Z" },
    { route_id: 9, lat: 24, lng: 91, geo_pct_bp: 7000, color: "amber", at: "2026-10-22T04:00:00.000Z" },
  ],
};

describe("FF Geo pin packing", () => {
  it("round-trips every member; coordinates keep 5 decimals, a missing share stays null", () => {
    const back = unpackPins(packPins(slice));
    expect(back.pins.map((p) => [p.route_id, p.geo_pct_bp, p.color, p.at])).toEqual(slice.pins.map((p) => [p.route_id, p.geo_pct_bp, p.color, p.at]));
    expect(back.pins[0]!.lat).toBe(23.78081);
    expect(back.pins[0]!.lng).toBe(90.41679);
    expect(back.business_date).toBe("2026-10-22");
    expect(back.as_of).toBe(slice.as_of);
  });
  it("is much smaller than the object form at national size", () => {
    const many: FfGeoSlice = { ...slice, pins: Array.from({ length: 6933 }, (_, i) => ({ route_id: 100000 + i, lat: 23 + (i % 997) / 1000, lng: 90 + (i % 991) / 1000, geo_pct_bp: i % 10001, color: (["red", "amber", "green"] as const)[i % 3]!, at: "2026-10-22T05:59:01.000Z" })) };
    expect(JSON.stringify(packPins(many)).length).toBeLessThan(JSON.stringify(many).length * 0.5);
  });
});
