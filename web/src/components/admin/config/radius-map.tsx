"use client";
// Radius visualisation: a circle of the candidate radius on a Google map. Maps load only through the shared capped BFF
// (requestMaps in components/map-panel.tsx: daily cap, session needed, key never in client code). Without a key (tests, the
// test account) a calm placeholder shows instead.
import { useEffect, useRef, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { requestMaps } from "@/components/map-panel";

interface Circle {
  setCenter: (c: object) => void;
  setRadius: (r: number) => void;
}
interface MapsApi {
  maps: {
    Map: new (el: HTMLElement, o: object) => { addListener: (e: string, f: (ev: { latLng: { lat(): number; lng(): number } }) => void) => void };
    Circle: new (o: object) => Circle;
  };
}

export function RadiusMap({ radiusM }: { radiusM: number }) {
  const { t } = useI18n();
  const host = useRef<HTMLDivElement>(null);
  const radiusRef = useRef(radiusM);
  const circle = useRef<Circle | null>(null);
  const [phase, setPhase] = useState<"loading" | "ready" | "none" | "capped" | "failed">("loading");

  useEffect(() => {
    let dead = false;
    void (async () => {
      const r = await requestMaps();
      if (dead) return;
      if (r.status !== "ready" || !host.current) return setPhase(r.status === "ready" ? "failed" : r.status === "no_key" ? "none" : r.status);
      const g = r.g as unknown as MapsApi;
      const center = { lat: 23.78, lng: 90.41 };
      const map = new g.maps.Map(host.current, { center, zoom: 15, mapTypeControl: false, streetViewControl: false, fullscreenControl: false });
      circle.current = new g.maps.Circle({ map, center, radius: radiusRef.current, strokeColor: "#1d5fd1", fillColor: "#3b82f6", fillOpacity: 0.2 });
      map.addListener("click", (ev) => circle.current?.setCenter({ lat: ev.latLng.lat(), lng: ev.latLng.lng() }));
      setPhase("ready");
    })();
    return () => {
      dead = true;
    };
  }, []);

  useEffect(() => {
    radiusRef.current = radiusM;
    circle.current?.setRadius(radiusM);
  }, [radiusM]);

  const box = "h-[26rem] w-full overflow-hidden rounded-[var(--radius-sheet)]";
  return (
    <div className={`${box} relative bg-[var(--surface-solid)]`} data-testid={phase === "ready" ? "map-root" : "map-placeholder"} data-phase={phase}>
      <div ref={host} className="h-full w-full" aria-label={t("geo.map")} />
      {phase !== "ready" ? (
        <p className="absolute inset-0 flex items-center justify-center p-6 text-center text-sm text-[var(--text-secondary)]">
          {phase === "loading" ? t("common.loading") : phase === "capped" ? t("geo.map.capped") : phase === "failed" ? t("geo.map.failed") : t("geo.map.placeholder")}
        </p>
      ) : null}
    </div>
  );
}
