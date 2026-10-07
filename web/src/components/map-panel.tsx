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

export function MapPanel({ pins, labels }: { pins: MapPin[]; labels: MapLabels }) {
  const el = useRef<HTMLDivElement>(null);
  const [phase, setPhase] = useState<Phase>("idle");

  useEffect(() => {
    let dead = false;
    (async () => {
      try {
        const res = await fetch("/api/bff/maps/load", { method: "POST" });
        if (res.status === 429) return void (!dead && setPhase("capped"));
        const body = (await res.json()) as { enabled?: boolean; key?: string };
        if (!body.enabled || !body.key) return void (!dead && setPhase("no_key"));
        const g = await loadScript(body.key);
        if (dead || !el.current) return;
        const map = new g.maps.Map(el.current, { center: { lat: 23.78, lng: 90.4 }, zoom: 11, mapTypeControl: false, streetViewControl: false });
        const bounds = new g.maps.LatLngBounds();
        for (const p of pins) {
          new g.maps.Marker({ map, position: { lat: p.lat, lng: p.lng }, title: p.label });
          bounds.extend({ lat: p.lat, lng: p.lng });
        }
        if (pins.length > 0) map.fitBounds(bounds);
        setPhase("ready");
      } catch {
        if (!dead) setPhase("failed");
      }
    })();
    return () => {
      dead = true;
    };
  }, [pins]);

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
