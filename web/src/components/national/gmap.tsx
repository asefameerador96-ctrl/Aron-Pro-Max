"use client";
// Shared Google Maps plumbing of the national dashboard maps (FF Geo, Route map, Sale Position). The key comes from the BFF only (the build
// secret, never in the page source); every load is counted against the daily cap by `requestMaps`. Without a key, or when the cap is spent,
// or when the script fails, `phase` says so and each map shows its own text fallback instead of an empty box.
import { useEffect, useRef, useState, type RefObject } from "react";
import { requestMaps } from "@/components/map-panel";

export interface LatLng {
  lat: number;
  lng: number;
}
export interface GmMarker {
  setMap: (m: unknown) => void;
  addListener: (ev: string, cb: () => void) => unknown;
}
export interface GmMap {
  fitBounds: (b: unknown, pad?: number) => void;
  addListener: (ev: string, cb: () => void) => unknown;
  getZoom: () => number | undefined;
  setZoom: (z: number) => void;
  setCenter: (c: LatLng) => void;
}
export interface GmInfo {
  setContent: (n: Node | string) => void;
  open: (o: Record<string, unknown>) => void;
  close: () => void;
}
/** The pieces of the Maps API used here (no @types/google.maps dependency, as in map-panel). */
export interface Gm {
  maps: {
    Map: new (el: HTMLElement, o: Record<string, unknown>) => GmMap;
    Marker: new (o: Record<string, unknown>) => GmMarker;
    Polyline: new (o: Record<string, unknown>) => { setMap: (m: unknown) => void };
    InfoWindow: new (o?: Record<string, unknown>) => GmInfo;
    LatLngBounds: new () => { extend: (p: LatLng) => void };
    Size: new (w: number, h: number) => unknown;
    Point: new (x: number, y: number) => unknown;
  };
}
export type Phase = "idle" | "ready" | "no_key" | "capped" | "failed";

/** Load Maps once for this mount (when `enabled`) and create the map in `el`. */
export function useGoogleMap(el: RefObject<HTMLDivElement | null>, enabled: boolean, options: Record<string, unknown>): { phase: Phase; g: Gm | null; map: GmMap | null } {
  const [state, setState] = useState<{ phase: Phase; g: Gm | null; map: GmMap | null }>({ phase: "idle", g: null, map: null });
  const opts = useRef(options);
  useEffect(() => {
    if (!enabled) return;
    let dead = false;
    (async () => {
      const r = await requestMaps();
      if (dead) return;
      if (r.status !== "ready") return setState({ phase: r.status, g: null, map: null });
      const g = r.g as unknown as Gm;
      if (!el.current) return;
      try {
        const map = new g.maps.Map(el.current, { center: { lat: 23.78, lng: 90.4 }, zoom: 7, streetViewControl: false, fullscreenControl: true, zoomControl: true, mapTypeControl: false, ...opts.current });
        setState({ phase: "ready", g, map });
      } catch {
        setState({ phase: "failed", g: null, map: null });
      }
    })();
    return () => {
      dead = true;
    };
  }, [el, enabled]);
  return state;
}

/** A DOM node for an info window with escaped text (values come from the API: never HTML). */
export function infoNode(rows: { label?: string; value: string; strong?: boolean }[], link?: { href: string; text: string }): HTMLElement {
  const root = document.createElement("div");
  root.style.cssText = "font:14px Roboto,Arial,sans-serif;color:#1a1a1a;max-width:260px;line-height:1.45";
  for (const r of rows) {
    const p = document.createElement("div");
    if (r.label) {
      const b = document.createElement("b");
      b.textContent = `${r.label}: `;
      p.append(b);
    }
    p.append(document.createTextNode(r.value));
    if (r.strong) p.style.fontWeight = "700";
    root.append(p);
  }
  if (link) {
    const a = document.createElement("a");
    a.href = link.href;
    a.textContent = link.text;
    a.style.cssText = "display:inline-block;margin-top:6px;color:#1565c0;font-weight:700";
    root.append(a);
  }
  return root;
}
