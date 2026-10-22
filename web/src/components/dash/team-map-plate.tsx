"use client";
// Team map plate (screens-web s1 "Team map"): a glass plate with the SR and outlet counts and one secondary button. The map
// (passed in by the page as `map`, so only page.tsx imports MapPanel) mounts only on that click, so a dashboard visit costs
// no Maps load (N-047, MAPS_DAILY_CAP).
import { useEffect, useRef, useState, type ReactNode } from "react";
import { Button } from "@/components/kit/button";
import { Icon } from "@/components/kit/icons";

/** `empty` replaces the hint and the button when there is nothing to pin (a map of no pins would spend a Maps load for nothing). */
export function TeamMapPlate({ summary, hint, showLabel, map, empty, autoLoad = false }: { summary: string; hint: string; showLabel: string; map: ReactNode; empty?: string; /** a Maps key is configured: mount the map when the card scrolls into view (the daily cap still guards every load) */ autoLoad?: boolean }) {
  const [shown, setShown] = useState(false);
  const plate = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const el = plate.current;
    if (!autoLoad || empty || !el || typeof IntersectionObserver === "undefined") return;
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) {
        setShown(true);
        io.disconnect();
      }
    }, { rootMargin: "200px" });
    io.observe(el);
    return () => io.disconnect();
  }, [autoLoad, empty]);
  if (shown) return <>{map}</>;
  return (
    <div ref={plate} className="flex min-h-60 flex-col items-center justify-center gap-3 rounded-card bg-raised p-6 text-center" data-testid="map-plate">
      <span className="flex size-16 items-center justify-center rounded-full bg-accent-container text-accent-on-container" aria-hidden="true">
        <Icon name="map" size={32} />
      </span>
      <p className="text-body-strong text-primary">{summary}</p>
      {empty ? (
        <p className="max-w-prose text-caption text-secondary" data-testid="map-empty">{empty}</p>
      ) : (
        <>
          <p className="max-w-prose text-caption text-secondary">{hint}</p>
          <Button variant="secondary" icon="map" onClick={() => setShown(true)} testId="map-show">
            {showLabel}
          </Button>
        </>
      )}
    </div>
  );
}
