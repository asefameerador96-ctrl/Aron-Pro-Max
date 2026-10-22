"use client";
// The map of "Sale Position" (brief s5.4): the outlet's base position (B), one marker per sale (New Sale red, Repeat yellow, Edited teal) and a
// red line from the base to each. A sale's pop-up carries its status, outlet code, distance and, for a call outside the fence, the force-sale
// photo with a "View Image" button (an in-page viewer). The list below the map repeats every sale in text, with or without a map key.
import { useEffect, useRef, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { clock, distance } from "@/lib/national/fmt";
import { PIN_FILL, pinIcon } from "@/lib/national/marker-icons";
import type { SaleKind, SalePositionSlice } from "@/lib/national/types";
import { useGoogleMap, type GmInfo, type Phase } from "./gmap";

const KIND_FILL: Record<SaleKind, string> = { new: PIN_FILL.red, repeat: PIN_FILL.yellow, edited: PIN_FILL.teal };

export function SalePositionView({ slice, mapsEnabled }: { slice: SalePositionSlice; mapsEnabled: boolean }) {
  const { t, locale } = useI18n();
  const el = useRef<HTMLDivElement>(null);
  const { phase: loaded, g, map } = useGoogleMap(el, mapsEnabled, { mapTypeControl: true });
  const phase: Phase = mapsEnabled ? loaded : "no_key";
  const info = useRef<GmInfo | null>(null);
  const [viewing, setViewing] = useState<string | null>(null);
  const photo = slice.sales.find((s) => s.id === viewing)?.photo_url ?? null;
  const close = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!g || !map) return;
    const drawn: { setMap: (m: unknown) => void }[] = [];
    info.current ??= new g.maps.InfoWindow();
    const bounds = new g.maps.LatLngBounds();
    const icon = (url: string) => ({ url, scaledSize: new g.maps.Size(28, 38), anchor: new g.maps.Point(14, 38) });
    drawn.push(new g.maps.Marker({ map, position: slice.base, title: t("nat.sp.base"), icon: icon(pinIcon(PIN_FILL.blue, "B")) }));
    bounds.extend(slice.base);
    for (const s of slice.sales) {
      drawn.push(new g.maps.Polyline({ map, path: [slice.base, { lat: s.lat, lng: s.lng }], strokeColor: PIN_FILL.red, strokeWeight: 3 }));
      const m = new g.maps.Marker({ map, position: { lat: s.lat, lng: s.lng }, title: t(`nat.sp.${s.kind}`), icon: icon(pinIcon(KIND_FILL[s.kind])) });
      m.addListener("click", () => {
        const root = document.createElement("div");
        root.style.cssText = "font:14px Roboto,Arial,sans-serif;color:#1a1a1a;max-width:240px;line-height:1.5";
        const h = document.createElement("div");
        h.style.fontWeight = "700";
        h.textContent = t(`nat.sp.${s.kind}`);
        root.append(h);
        if (s.photo_url) {
          const img = document.createElement("img");
          img.src = s.photo_url;
          img.alt = t("nat.sp.photo_alt");
          img.style.cssText = "width:80px;height:60px;object-fit:cover;display:block;margin:4px 0";
          const b = document.createElement("button");
          b.type = "button";
          b.textContent = t("nat.sp.view_image");
          b.style.cssText = "background:#1565c0;color:#fff;border:0;border-radius:4px;padding:6px 10px;font-weight:700;cursor:pointer";
          b.onclick = () => setViewing(s.id);
          root.append(img, b);
        }
        for (const [l, v] of [[t("nat.sp.outlet"), slice.outlet_code], [t("nat.sp.status"), t(`nat.sp.${s.kind}`)], [t("nat.sp.distance"), distance(locale, s.distance_m)]] as const) {
          const p = document.createElement("div");
          const b = document.createElement("b");
          b.textContent = `${l}: `;
          p.append(b, document.createTextNode(v));
          root.append(p);
        }
        info.current?.setContent(root);
        info.current?.open({ map, anchor: m });
      });
      drawn.push(m);
      bounds.extend({ lat: s.lat, lng: s.lng });
    }
    map.fitBounds(bounds, 60);
    return () => {
      for (const m of drawn) m.setMap(null);
      info.current?.close();
    };
  }, [g, map, slice, t, locale]);

  useEffect(() => {
    if (!viewing) return;
    close.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setViewing(null);
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [viewing]);

  const placeholder = phase === "no_key" ? t("nat.ff.placeholder") : phase === "capped" ? t("nat.ff.capped") : phase === "failed" ? t("nat.ff.failed") : null;
  return (
    <div data-testid="sale-position" data-phase={phase} className="space-y-3 text-primary">
      <div className="glass-data overflow-hidden">
        <div ref={el} className={phase === "ready" ? "h-[26rem] w-full" : "hidden"} data-testid="sale-position-root" />
        {placeholder ? <p className="m-4 rounded-card border border-dashed border-divider bg-raised p-3 text-body text-secondary" data-testid="sale-position-placeholder">{placeholder}</p> : null}
      </div>
      <section className="glass-data p-(--card-pad)" aria-label={t("nat.sp.list")}>
        {slice.sales.length === 0 ? <p className="text-body text-secondary" data-testid="sale-position-none">{t("nat.sp.none")}</p> : (
          <ul className="divide-y divide-divider" data-testid="sale-position-list">
            {slice.sales.map((s) => {
              const hit = s.distance_m <= slice.radius_m;
              return (
                <li key={s.id} data-kind={s.kind} className="flex flex-wrap items-center gap-x-4 gap-y-1 py-2 text-body">
                  <span className="size-3 shrink-0 rounded-full" style={{ background: KIND_FILL[s.kind] }} aria-hidden="true" />
                  <span className="text-body-strong">{t(`nat.sp.${s.kind}`)}</span>
                  <span className="tabular-nums text-secondary">{clock(locale, s.at, true)}</span>
                  <span className="tabular-nums">{t("nat.sp.distance")}: {distance(locale, s.distance_m)}</span>
                  <span className={hit ? "text-success-on-container" : "text-danger-on-container"}>{t(hit ? "nat.sp.hit" : "nat.sp.miss", { radius: slice.radius_m })}</span>
                  {s.photo_url ? <button type="button" onClick={() => setViewing(s.id)} data-testid={`view-image-${s.id}`} className="rounded-chip bg-accent px-3 py-1 text-body-strong text-on-accent hover:brightness-110 focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">{t("nat.sp.view_image")}</button> : null}
                </li>
              );
            })}
          </ul>
        )}
      </section>
      {photo ? (
        <div role="dialog" aria-modal="true" aria-label={t("nat.sp.photo_alt")} data-testid="photo-viewer" className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4" onClick={() => setViewing(null)}>
          <div className="glass-pop max-h-full max-w-xl p-3" onClick={(e) => e.stopPropagation()}>
            {/* eslint-disable-next-line @next/next/no-img-element */}
            <img src={photo} alt={t("nat.sp.photo_alt")} className="max-h-[70vh] w-full object-contain" />
            <button ref={close} type="button" onClick={() => setViewing(null)} className="mt-2 rounded-chip bg-accent px-4 py-2 text-body-strong text-on-accent">{t("nat.close")}</button>
          </div>
        </div>
      ) : null}
    </div>
  );
}
