"use client";
// Google Maps panel (N-047). Loads the Maps script only when mounted (dashboard FF Geo Location tile, geofence page, team
// location), only after the BFF has counted the load against the daily cap, and never at build time. Without a key (tests, the
// test account) it renders the same pins as an accessible list, so the figures are visible either way.
import { useEffect, useRef, useState } from "react";

export interface MapPin {
  id: string;
  lat: number;
  lng: number;
  label: string;
  kind: "outlet" | "fix";
}

export interface MapLabels {
  placeholder: string;
  capped: string;
  failed: string;
  outlets: string;
  fixes: string;
  pins: string;
}

type Phase = "idle" | "ready" | "no_key" | "capped" | "failed";

// Minimal structural types for the pieces of the Maps API used here (no @types/google.maps dependency).
interface GMaps {
  maps: {
    Map: new (el: HTMLElement, opts: Record<string, unknown>) => { fitBounds: (b: unknown) => void };
    Marker: new (opts: Record<string, unknown>) => unknown;
    LatLngBounds: new () => { extend: (p: { lat: number; lng: number }) => void };
  };
}
declare global {
  interface Window {
    google?: GMaps;
  }
}

let loader: Promise<GMaps> | null = null;
function loadScript(key: string): Promise<GMaps> {
  if (window.google?.maps) return Promise.resolve(window.google);
  loader ??= new Promise<GMaps>((resolve, reject) => {
    const s = document.createElement("script");
    s.src = `https://maps.googleapis.com/maps/api/js?key=${encodeURIComponent(key)}&v=weekly&loading=async`;
    s.async = true;
    s.onload = () => (window.google?.maps ? resolve(window.google) : reject(new Error("maps")));
    s.onerror = () => reject(new Error("maps"));
    document.head.appendChild(s);
  });
  return loader;
}

export type MapsResult = { status: "ready"; g: GMaps } | { status: "capped" | "no_key" | "failed" };

/** The one way to get the Maps API: the BFF counts the load against the daily cap and hands out the key to a signed-in user. */
export async function requestMaps(): Promise<MapsResult> {
  try {
    const res = await fetch("/api/bff/maps/load", { method: "POST" });
    if (res.status === 429) return { status: "capped" };
    const body = (await res.json()) as { enabled?: boolean; key?: string };
    if (!body.enabled || !body.key) return { status: "no_key" };
    return { status: "ready", g: await loadScript(body.key) };
  } catch {
    return { status: "failed" };
  }
}

export function MapPanel({ pins, labels }: { pins: MapPin[]; labels: MapLabels }) {
  const el = useRef<HTMLDivElement>(null);
  const [phase, setPhase] = useState<Phase>("idle");
  const loaded = useRef<{ g: GMaps; map: InstanceType<GMaps["maps"]["Map"]> } | null>(null);
  const drawn = useRef<{ setMap: (m: unknown) => void }[]>([]);

  // One Maps load per mount (it counts against the daily cap); a change of pins only redraws the markers.
  useEffect(() => {
    let dead = false;
    (async () => {
      try {
        const r = await requestMaps();
        if (r.status !== "ready") return void (!dead && setPhase(r.status));
        const g = r.g;
        if (dead || !el.current) return;
        loaded.current = { g, map: new g.maps.Map(el.current, { center: { lat: 23.78, lng: 90.4 }, zoom: 11, mapTypeControl: false, streetViewControl: false, fullscreenControl: true }) };
        setPhase("ready");
      } catch {
        if (!dead) setPhase("failed");
      }
    })();
    return () => {
      dead = true;
    };
  }, []);

  useEffect(() => {
    const l = loaded.current;
    if (phase !== "ready" || !l) return;
    for (const m of drawn.current) m.setMap(null);
    const bounds = new l.g.maps.LatLngBounds();
    drawn.current = pins.map((p) => {
      bounds.extend({ lat: p.lat, lng: p.lng });
      // SymbolPath.CIRCLE is 0; outlets blue, SR positions orange (the colour filter of the dashboard map).
      return new l.g.maps.Marker({ map: l.map, position: { lat: p.lat, lng: p.lng }, title: p.label, icon: { path: 0, scale: 7, fillColor: p.kind === "outlet" ? "#2a78d6" : "#eb6834", fillOpacity: 1, strokeColor: "#ffffff", strokeWeight: 2 } }) as { setMap: (m: unknown) => void };
    });
    if (pins.length > 0) l.map.fitBounds(bounds);
  }, [pins, phase]);

  const outlets = pins.filter((p) => p.kind === "outlet").length;
  const fixes = pins.length - outlets;
  return (
    <div data-testid="map-panel" data-phase={phase} data-pin-count={pins.length}>
      {phase === "ready" ? <div ref={el} data-testid="map-root" className="h-72 rounded bg-slate-100" /> : <div ref={el} className="hidden" />}
      {phase !== "ready" ? (
        <div className="rounded border border-dashed border-slate-300 bg-slate-100 p-3 text-sm text-slate-700">
          {phase === "capped" ? <p data-testid="map-capped">{labels.capped}</p> : phase === "failed" ? <p data-testid="map-failed">{labels.failed}</p> : <p data-testid="map-placeholder">{labels.placeholder}</p>}
          <p className="mt-1 text-xs text-slate-600" data-testid="map-counts">
            {labels.outlets}: {outlets} · {labels.fixes}: {fixes}
          </p>
          <ul className="mt-2 max-h-40 overflow-auto text-xs" aria-label={labels.pins} data-testid="map-pin-list">
            {pins.map((p) => (
              <li key={p.id} data-kind={p.kind}>
                {p.label} ({p.lat.toFixed(4)}, {p.lng.toFixed(4)})
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}
