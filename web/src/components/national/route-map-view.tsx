"use client";
// The map of "Map Details" (brief s5.3): the route's outlets as black (not called yet), green (called inside the geo-fence) and red (called
// outside, photo required) pins, the sale-start flag, the SR's last position, and a line through the call positions in time order.
// The outlet pop-up shows the call's category-wise sales and links to the outlet's Sale Position page. Without a Maps key the same outlets are
// listed with their state, so the page is useful and says plainly why the picture is missing.
import Link from "next/link";
import { useEffect, useRef } from "react";
import { useI18n } from "@/components/i18n-provider";
import { clock } from "@/lib/national/fmt";
import { flagIcon, PIN_FILL, personIcon, pinIcon } from "@/lib/national/marker-icons";
import { CATEGORIES, type RouteMapSlice } from "@/lib/national/types";
import { infoNode, useGoogleMap, type GmInfo, type GmMarker, type Phase } from "./gmap";

const STATE_FILL = { not_called: PIN_FILL.black, geo: PIN_FILL.green, photo: PIN_FILL.red } as const;

export function RouteMapView({ slice, date, mapsEnabled }: { slice: RouteMapSlice; date: string; mapsEnabled: boolean }) {
  const { t, locale, number } = useI18n();
  const el = useRef<HTMLDivElement>(null);
  const { phase: loaded, g, map } = useGoogleMap(el, mapsEnabled, { mapTypeControl: true });
  const phase: Phase = mapsEnabled ? loaded : "no_key";
  const info = useRef<GmInfo | null>(null);

  useEffect(() => {
    if (!g || !map) return;
    const drawn: { setMap: (m: unknown) => void }[] = [];
    info.current ??= new g.maps.InfoWindow();
    const bounds = new g.maps.LatLngBounds();
    const icon = (url: string, w: number, h: number) => ({ url, scaledSize: new g.maps.Size(w, h), anchor: new g.maps.Point(w / 2, h) });
    const add = (m: GmMarker, p: { lat: number; lng: number }) => {
      drawn.push(m);
      bounds.extend(p);
    };
    for (const o of slice.outlets) {
      const m = new g.maps.Marker({ map, position: { lat: o.lat, lng: o.lng }, title: o.name, icon: icon(pinIcon(STATE_FILL[o.state]), 24, 33) });
      m.addListener("click", () => {
        const sales = o.sales ? CATEGORIES.map((c) => ({ label: t(`nat.rm.cat.${c}`), value: number(o.sales![c]) })) : [];
        info.current?.setContent(infoNode([
          { label: t("nat.rm.outlet"), value: o.name },
          { label: t("nat.rm.contact"), value: o.contact ?? "—" },
          { label: t("nat.rm.code"), value: o.code },
          { label: t("nat.rm.cluster"), value: o.cluster },
          ...(o.at ? [{ label: t("nat.rm.time"), value: clock(locale, o.at, true) }] : []),
          ...sales,
        ], { href: `/dashboard/sale-position/${o.id}?date=${date}`, text: t("nat.rm.open_sale") }));
        info.current?.open({ map, anchor: m });
      });
      add(m, o);
    }
    if (slice.start) add(new g.maps.Marker({ map, position: slice.start, title: t("nat.rm.legend.start"), icon: icon(flagIcon(), 30, 34) }), slice.start);
    if (slice.last_position) add(new g.maps.Marker({ map, position: slice.last_position, title: t("nat.rm.legend.sr"), icon: icon(personIcon(), 26, 38) }), slice.last_position);
    if (slice.path.length > 1) drawn.push(new g.maps.Polyline({ map, path: slice.path.map((p) => ({ lat: p.lat, lng: p.lng })), strokeColor: "#29b6f6", strokeWeight: 3, strokeOpacity: 0.95 }));
    if (slice.outlets.length + slice.path.length > 0) map.fitBounds(bounds, 40);
    return () => {
      for (const m of drawn) m.setMap(null);
      info.current?.close();
    };
  }, [g, map, slice, date, t, locale, number]);

  const placeholder = phase === "no_key" ? t("nat.ff.placeholder") : phase === "capped" ? t("nat.ff.capped") : phase === "failed" ? t("nat.ff.failed") : null;
  return (
    <div data-testid="route-map" data-phase={phase} className="glass-data overflow-hidden text-primary">
      <div ref={el} className={phase === "ready" ? "h-[26rem] w-full" : "hidden"} data-testid="route-map-root" />
      {placeholder ? (
        <div className="space-y-2 p-4">
          <p className="rounded-card border border-dashed border-divider bg-raised p-3 text-body text-secondary" data-testid="route-map-placeholder">{placeholder}</p>
          {slice.outlets.length === 0 ? <p className="text-body text-secondary">{t("nat.rm.no_position")}</p> : (
            <ul aria-label={t("nat.rm.list")} className="max-h-80 divide-y divide-divider overflow-auto rounded-card bg-raised" data-testid="route-map-list">
              {slice.outlets.map((o) => (
                <li key={o.id} data-state={o.state} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-body">
                  <span className="size-3 shrink-0 rounded-full" style={{ background: STATE_FILL[o.state] }} aria-hidden="true" />
                  <span className="min-w-40 flex-1 truncate" title={`${o.code} · ${o.name}`}>{o.code} · {o.name}</span>
                  <span className="text-caption text-secondary">{t(`nat.rm.state.${o.state}`)}{o.at ? ` · ${clock(locale, o.at)}` : ""}</span>
                  <Link href={`/dashboard/sale-position/${o.id}?date=${date}`} className="text-body-strong text-accent hover:underline">{t("nat.rm.open_sale")}</Link>
                </li>
              ))}
            </ul>
          )}
        </div>
      ) : null}
    </div>
  );
}
