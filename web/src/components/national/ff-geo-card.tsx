"use client";
// FF Geo Location (brief s5.2): one pin per route at the SR's latest position, clustered with a count when zoomed out, coloured Red / Amber /
// Green by today's geo-fencing % (below 70, 70 to 90, above 90), "Filter by Color", and a route pop-up with a link to the route's live map.
// The pins load from the browser after the cards (a national selection has thousands). Without a Maps key the card shows a clear placeholder
// and the same routes as a list, so nothing is hidden by the missing key.
import Link from "next/link";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useI18n } from "@/components/i18n-provider";
import { clusterPins } from "@/lib/maps/cluster";
import { clock, pct } from "@/lib/national/fmt";
import { clusterIcon, PIN_FILL, pinIcon } from "@/lib/national/marker-icons";
import type { PinColor } from "@/lib/national/query";
import type { FfGeoSlice, FfPin, FfPopup } from "@/lib/national/types";
import { infoNode, useGoogleMap, type GmInfo, type GmMarker, type Phase } from "./gmap";
import { MultiSelect } from "./multi-select";

type Load = { s: "loading" } | { s: "error"; code: string } | { s: "ok"; pins: FfPin[]; date: string };
const COLORS: PinColor[] = ["red", "amber", "green"];

/** Remounts the card on a new filter or a retry, so each load starts from "loading" without a state reset inside an effect. */
export function FfGeoCard(props: { search: string; date: string; mapsEnabled: boolean }) {
  const [tries, setTries] = useState(0);
  return <FfGeoInner key={`${props.search}#${tries}`} {...props} onRetry={() => setTries((n) => n + 1)} />;
}

function FfGeoInner({ search, date, mapsEnabled, onRetry }: { search: string; date: string; mapsEnabled: boolean; onRetry: () => void }) {
  const { t, locale, problem } = useI18n();
  const [load, setLoad] = useState<Load>({ s: "loading" });
  const [colors, setColors] = useState<string[]>([]);
  const [zoom, setZoom] = useState(7);
  const el = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const ctl = new AbortController();
    fetch(`/api/bff/national/ff-geo?${search}`, { signal: ctl.signal, credentials: "same-origin" })
      .then(async (r) => {
        const body = (await r.json().catch(() => null)) as (FfGeoSlice & { code?: string }) | null;
        if (r.ok && body && Array.isArray(body.pins)) setLoad({ s: "ok", pins: body.pins, date: body.business_date });
        else setLoad({ s: "error", code: body?.code ?? "ERR_INTERNAL" });
      })
      .catch((e: unknown) => {
        if ((e as { name?: string }).name !== "AbortError") setLoad({ s: "error", code: "ERR_SERVICE_UNAVAILABLE" });
      });
    return () => ctl.abort();
  }, [search]);

  const pins = useMemo(() => (load.s === "ok" ? load.pins : []), [load]);
  const active = colors.length === 0 || colors.length === COLORS.length ? COLORS : (colors as PinColor[]);
  const shown = useMemo(() => pins.filter((p) => active.includes(p.color)), [pins, active]);

  const { phase: loaded, g, map } = useGoogleMap(el, mapsEnabled && load.s === "ok" && pins.length > 0, { zoomControl: true });
  const phase: Phase = mapsEnabled ? loaded : "no_key"; // no key configured: say so at once, without spending a Maps load
  const drawn = useRef<GmMarker[]>([]);
  const info = useRef<GmInfo | null>(null);
  const fitted = useRef("");

  useEffect(() => {
    if (!map) return;
    const l = map.addListener("zoom_changed", () => setZoom(map.getZoom() ?? 7));
    return () => void l;
  }, [map]);

  // The pop-up is read when a pin is clicked (the server decides who sees the name and the phone); "—" shows while it loads or when refused.
  const popup = useCallback(
    (p: FfPin, d: FfPopup | null) => infoNode(
      [
        { label: t("nat.pop.sr"), value: d?.sr_name ?? "—" },
        { label: t("nat.pop.contact"), value: d?.contact ?? "—" },
        { label: t("nat.pop.territory"), value: d ? `${d.territory} (${d.territory_code})` : "—" },
        { label: t("nat.pop.zone"), value: d?.zone ?? "—" },
        { label: t("nat.pop.route"), value: d?.route ?? String(p.route_id) },
        { label: t("nat.pop.time"), value: `${date} ${clock(locale, d?.position_at ?? p.at)}` },
        { label: t("nat.pop.miss"), value: d?.miss_pct !== null && d?.miss_pct !== undefined ? pct(locale, d.miss_pct, 2) : "—" },
      ],
      { href: `/dashboard/route-map/${p.route_id}?date=${date}`, text: t("nat.pop.open") },
    ),
    [t, locale, date],
  );

  useEffect(() => {
    if (!g || !map) return;
    for (const m of drawn.current) m.setMap(null);
    drawn.current = [];
    info.current ??= new g.maps.InfoWindow();
    const bounds = new g.maps.LatLngBounds();
    for (const p of shown) bounds.extend({ lat: p.lat, lng: p.lng });
    const key = `${search}|${active.join(",")}`;
    if (shown.length > 0 && fitted.current !== key) {
      fitted.current = key;
      map.fitBounds(bounds, 40);
    }
    const byId = new Map(shown.map((p) => [String(p.route_id), p]));
    drawn.current = clusterPins(shown.map((p) => ({ id: String(p.route_id), lat: p.lat, lng: p.lng, label: String(p.route_id), kind: "outlet" as const })), zoom).map((c) => {
      const base = { map, position: { lat: c.lat, lng: c.lng } };
      if (c.count > 1) {
        const m = new g.maps.Marker({ ...base, title: String(c.count), icon: { url: clusterIcon(c.count) } });
        m.addListener("click", () => {
          map.setCenter({ lat: c.lat, lng: c.lng });
          map.setZoom(Math.min(18, (map.getZoom() ?? 7) + 2));
        });
        return m;
      }
      const p = byId.get(c.pins[0]!.id)!;
      const m = new g.maps.Marker({ ...base, title: `${t("nat.pop.route")} ${p.route_id}`, icon: { url: pinIcon(PIN_FILL[p.color]), scaledSize: new g.maps.Size(28, 38), anchor: new g.maps.Point(14, 38) } });
      m.addListener("click", () => {
        info.current?.setContent(popup(p, null));
        info.current?.open({ map, anchor: m });
        fetch(`/api/bff/national/ff-popup?route_id=${p.route_id}&date=${date}`, { credentials: "same-origin" })
          .then((r) => (r.ok ? (r.json() as Promise<FfPopup>) : null))
          .then((d) => d && info.current?.setContent(popup(p, d)))
          .catch(() => undefined);
      });
      return m;
    });
  }, [g, map, shown, zoom, search, active, popup, date, t]);

  const colorOptions = COLORS.map((c) => ({ id: c, name: t(`nat.ff.${c}`) }));
  const placeholder = phase === "no_key" ? t("nat.ff.placeholder") : phase === "capped" ? t("nat.ff.capped") : phase === "failed" ? t("nat.ff.failed") : null;
  const showList = load.s === "ok" && pins.length > 0 && phase !== "ready" && phase !== "idle";

  return (
    <section data-testid="nat-ffgeo" aria-labelledby="nat-ffgeo-h" className="glass-data relative flex min-h-80 flex-col gap-3 p-(--card-pad) text-primary md:col-span-2 xl:col-span-8">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 id="nat-ffgeo-h" className="text-title text-accent">{t("nat.ff")}</h2>
        <div className="flex items-center gap-2">
          <span className="text-body-strong text-accent">{t("nat.ff.filter_color")}:</span>
          <div className="w-52">
            <MultiSelect testId="ff-colors" label={t("nat.ff.filter_color")} options={colorOptions} value={colors} onChange={setColors} allText={t("nat.ff.all_colors")} someText={(n) => t("nat.f.some_selected", { n })} clearLabel={t("nat.f.clear")} searchLabel={t("nat.f.search")} noneText={t("nat.f.none_found")} />
          </div>
        </div>
      </div>
      {load.s === "loading" ? <div className="h-96 animate-pulse rounded-card bg-skeleton" role="status" aria-label={t("nat.ff")} data-testid="ff-loading" /> : null}
      {load.s === "error" ? (
        <p role="alert" className="flex items-center gap-2 text-body text-danger-on-container" data-testid="ff-error">
          {t("nat.card_error")} {problem(load.code)}
          <button type="button" onClick={onRetry} className="rounded-chip px-3 py-1 text-body-strong text-accent hover:bg-pressed">{t("nat.card_retry")}</button>
        </p>
      ) : null}
      {load.s === "ok" && pins.length === 0 ? <p className="py-10 text-center text-body text-secondary" data-testid="ff-empty">{t("nat.ff.no_pins")}</p> : null}
      {load.s === "ok" && pins.length > 0 ? (
        <div data-testid="ff-map" data-phase={phase} data-pins={shown.length}>
          <div ref={el} className={phase === "ready" ? "h-96 w-full rounded-card" : "hidden"} data-testid="ff-map-root" />
          {placeholder ? <p className="rounded-card border border-dashed border-divider bg-raised p-3 text-body text-secondary" data-testid="ff-placeholder">{placeholder}</p> : null}
          {showList ? (
            <div className="mt-2">
              <p className="mb-1 text-caption text-secondary" data-testid="ff-count">{t("nat.ff.count", { n: shown.length })}</p>
              <ul aria-label={t("nat.ff.pins")} className="max-h-72 divide-y divide-divider overflow-auto rounded-card bg-raised" data-testid="ff-list">
                {shown.slice(0, 100).map((p) => {
                  const c = p.color;
                  return (
                    <li key={p.route_id} data-color={c} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-body">
                      <span className="size-3 shrink-0 rounded-full" style={{ background: PIN_FILL[c] }} aria-hidden="true" />
                      <span className="sr-only">{t(`nat.ff.${c}`)}</span>
                      <span className="min-w-0 flex-1 truncate">{t("nat.pop.route")} {p.route_id} · {clock(locale, p.at)}</span>
                      <span className="tabular-nums text-secondary">{p.geo_pct === null ? "—" : pct(locale, p.geo_pct, 2)}</span>
                      <Link href={`/dashboard/route-map/${p.route_id}?date=${date}`} className="text-body-strong text-accent hover:underline">{t("nat.pop.open")}</Link>
                    </li>
                  );
                })}
              </ul>
            </div>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}
