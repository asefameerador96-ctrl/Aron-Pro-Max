"use client";
// The dashboard map with the "Filter by colour" select of the current portal: all pins, outlets only, or SR positions only.
// The Maps script loads once (MapPanel); the select only redraws markers.
import { useMemo, useState } from "react";
import { MapPanel, type MapLabels, type MapPin } from "@/components/map-panel";
import { INPUT_CLASS } from "@/components/kit/button";

type Mode = "all" | "outlet" | "fix";

export function FilteredMap({ pins, labels, filterMsg, allMsg, outletsMsg, fixesMsg }: { pins: MapPin[]; labels: MapLabels; filterMsg: string; allMsg: string; outletsMsg: string; fixesMsg: string }) {
  const [mode, setMode] = useState<Mode>("all");
  const shown = useMemo(() => (mode === "all" ? pins : pins.filter((p) => p.kind === mode)), [pins, mode]);
  return (
    <div className="flex flex-col gap-3">
      <label className="flex flex-wrap items-center gap-2 text-label text-secondary">
        {filterMsg}
        <select value={mode} onChange={(e) => setMode(e.target.value as Mode)} className={INPUT_CLASS} data-testid="map-colour-filter">
          <option value="all">{allMsg}</option>
          <option value="outlet">{outletsMsg}</option>
          <option value="fix">{fixesMsg}</option>
        </select>
      </label>
      <MapPanel pins={shown} labels={labels} />
    </div>
  );
}
