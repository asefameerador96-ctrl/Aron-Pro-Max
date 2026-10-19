// Team map plate: with no pins there is no "Show map" (a map of nothing would spend a Maps load); otherwise the button stays.
import { describe, expect, it } from "vitest";
import { TeamMapPlate } from "@/components/dash/team-map-plate";
import { html } from "./helpers/render";

describe("TeamMapPlate", () => {
  it("shows the empty note and no button when there is nothing to pin", () => {
    const out = html(<TeamMapPlate summary="0 SRs · 0 outlets" hint="hint" showLabel="Show map" map={null} empty="Nothing to show" />);
    expect(out).toContain('data-testid="map-empty"');
    expect(out).not.toContain('data-testid="map-show"');
  });
  it("keeps the hint and the button when there are pins", () => {
    const out = html(<TeamMapPlate summary="2 SRs · 5 outlets" hint="hint" showLabel="Show map" map={null} />);
    expect(out).toContain('data-testid="map-show"');
    expect(out).not.toContain('data-testid="map-empty"');
  });
});

describe("TeamMapPlate with a Maps key", () => {
  it("renders the plate (and the button as the fallback) on the server; the map mounts when the card scrolls into view", () => {
    const out = html(<TeamMapPlate summary="2 SRs · 5 outlets" hint="hint" showLabel="Show map" map={null} autoLoad />);
    expect(out).toContain('data-testid="map-plate"');
    expect(out).toContain('data-testid="map-show"');
  });
});
