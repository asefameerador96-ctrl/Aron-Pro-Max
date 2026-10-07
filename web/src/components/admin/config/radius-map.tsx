"use client";
// Radius visualisation: a circle of the candidate radius on a Google map. The key comes from NEXT_PUBLIC_MAPS_WEB_KEY
// (GitHub secret MAPS_WEB_KEY, build time); without it the panel is a placeholder so builds and tests need no secret.
// The shared loader and cost guard of N-047 (web-dashboard) replace the script injection below when they land.
import { useEffect, useRef, useState } from "react";
import { useI18n } from "@/components/i18n-provider";

interface GMaps {
  maps: {
    Map: new (el: HTMLElement, o: object) => { addListener: (e: string, f: (ev: { latLng: { lat(): number; lng(): number } }) => void) => void };
    Circle: new (o: object) => { setCenter: (c: object) => void; setRadius: (r: number) => void };
  };
}

export function RadiusMap({ radiusM }: { radiusM: number }) {
  const { t } = useI18n();
  const key = process.env.NEXT_PUBLIC_MAPS_WEB_KEY;
  const host = useRef<HTMLDivElement>(null);
  const [failed, setFailed] = useState(false);
  const radiusRef = useRef(radiusM);
  const circle = useRef<{ setCenter: (c: object) => void; setRadius: (r: number) => void } | null>(null);

  useEffect(() => {
    if (!key || !host.current) return;
    const el = host.current;
    let cancelled = false;
    const start = () => {
      const g = (window as unknown as { google?: GMaps }).google;
      if (!g || cancelled) return;
      const center = { lat: 23.78, lng: 90.41 };
      const map = new g.maps.Map(el, { center, zoom: 15, mapTypeControl: false, streetViewControl: false });
      circle.current = new g.maps.Circle({ map, center, radius: radiusRef.current, strokeColor: "#1d4ed8", fillColor: "#3b82f6", fillOpacity: 0.2 });
      map.addListener("click", (ev) => circle.current?.setCenter({ lat: ev.latLng.lat(), lng: ev.latLng.lng() }));
    };
    if ((window as unknown as { google?: GMaps }).google) start();
    else {
      const w = window as unknown as { __aronMapsReady?: () => void };
      w.__aronMapsReady = start;
      if (!document.getElementById("aron-maps-js")) {
        const s = document.createElement("script");
        s.id = "aron-maps-js";
        s.src = `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(key)}&callback=__aronMapsReady`;
        s.async = true;
        s.onerror = () => setFailed(true);
        document.head.appendChild(s);
      }
    }
    return () => {
      cancelled = true;
    };
  }, [key]);

  useEffect(() => {
    radiusRef.current = radiusM;
    circle.current?.setRadius(radiusM);
  }, [radiusM]);

  if (!key || failed) {
    return (
      <div data-testid="map-placeholder" className="flex h-48 items-center justify-center rounded border border-dashed border-slate-300 bg-slate-100 text-sm text-slate-600">
        {t("geo.map.placeholder")}
      </div>
    );
  }
  return <div ref={host} data-testid="map-root" data-maps="enabled" aria-label={t("geo.map")} className="h-72 rounded bg-slate-100" />;
}
