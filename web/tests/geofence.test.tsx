// F-ADM-039 geofence radius page: scope picker, current value with provenance, what-if, density, calibration, save form.
import { describe, expect, it, vi } from "vitest";
import { GeofenceView, type GeofenceViewProps } from "@/components/admin/config/geofence-view";
import type { ConfigKey } from "@/lib/admin/types";
import { html, text } from "./helpers/render";

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh() {}, push() {} }) }));

const key: ConfigKey = { key: "cfg.geo.radius_m", area: "geo", kind: "S", value_type: "int", default_value: 50, bounds: { min: 10, max: 5000 }, scope_levels: ["global", "territory", "zone", "route", "outlet"], risk_class: 2, effect: "B", delivery: "both", requires_ack: false, future_dated_only: false, editor_permission: "cfg.edit.geo", description_en: "Radius" };
const base: GeofenceViewProps = {
  locale: "en", key_: key, level: "zone", areaId: "5012",
  current: { key: key.key, value: 100, scope_type: "territory", effective_from: null, requires_ack: false },
  whatIfValue: 150, whatIfDays: 30,
  whatIf: { scope_type: "zone", scope_id: 5012, value: 150, days: 30, visits_evaluated: 1200, to_valid: 85, to_invalid: 0, unchanged: 1115 },
  blast: { zones: 1, routes: 14, outlets: 900, users: 14, devices: 12 },
  density: { as_of: "2026-10-06T04:00:00.000Z", radii_m: [50, 100], rows: [{ node: { type: "zone", id: 5012, code: "Z1", name: "Gulshan" }, outlets: 900, density_index_pct: 41.5, median_neighbours: [3, 9] }] },
  calibration: { as_of: "2026-10-06T04:00:00.000Z", rows: [{ territory_id: 334, geo_class: "urban", visits: 800, histogram: [{ upper_m: 25, visits: 300 }, { upper_m: 50, visits: 400 }, { upper_m: 100, visits: 100 }], force_sale_pct: 7.5, suggested_radius_m: 90 }] },
  canWrite: true,
};

describe("GeofenceView", () => {
  it("asks for a level and id before showing anything", () => {
    const m = text(html(<GeofenceView {...base} level="" areaId="" current={null} whatIf={null} blast={null} density={null} calibration={null} whatIfValue={null} />));
    expect(m).toContain("Enter the id of the area");
    expect(m).not.toContain("Radius in force here");
  });
  it("shows the radius in force with where it was set, the map and the save form", () => {
    const m = html(<GeofenceView {...base} />);
    expect(text(m)).toContain("100 m");
    expect(text(m)).toContain("set for a territory");
    expect(m).toContain('data-testid="map-placeholder"'); // no key in tests
    expect(m).toContain('data-key="cfg.geo.radius_m"');
  });
  it("shows the what-if outcome on stored fixes, blast radius, density and calibration with a suggestion link", () => {
    const m = html(<GeofenceView {...base} />);
    const x = text(m);
    expect(x).toContain("1,200 visits checked: 85 would become valid, 0 would become invalid, 1,115 unchanged.");
    expect(x).toContain("1 zones, 14 routes, 900 outlets, 14 users, 12 phones");
    expect(x).toContain("41.5%");
    expect(x).toContain("Median neighbours within 100 m");
    expect(m).toContain("/admin/config/geofence?level=zone&amp;id=5012&amp;value=90&amp;days=30");
  });
  it("read-only roles get no save form", () => {
    expect(html(<GeofenceView {...base} canWrite={false} />)).not.toContain("data-key=");
  });
  it("global scope needs no id", () => {
    expect(html(<GeofenceView {...base} level="global" areaId="0" />)).toContain('data-testid="current-radius"');
  });
  it("Bangla digits and labels in bn", () => {
    const x = text(html(<GeofenceView {...base} locale="bn" />));
    expect(x).toContain("১০০ মি");
    expect(x).toContain("জিওফেন্সের ব্যাসার্ধ");
  });
});
