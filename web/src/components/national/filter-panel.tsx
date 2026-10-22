"use client";
// The filter of the national dashboard (brief s1): Wing, Division, Territory, House, Zone; Category, Segment, Brand, Variant; a date; the yellow
// Apply Filter. Cards change only on Apply (the URL changes, the server re-reads every card). A narrower parent drops children that no longer
// belong to it. Options come from the server already cut to the caller's scope. The Filter button at the top right hides or shows the panel.
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { Icon } from "@/components/kit/icons";
import { useI18n } from "@/components/i18n-provider";
import { toSearch } from "@/lib/national/query";
import type { NationalFilters, NationalQuery, Option, ProductOption } from "@/lib/national/types";
import { MultiSelect } from "./multi-select";

type GeoKey = "wing" | "division" | "territory" | "house" | "zone";
type ProdKey = "category" | "segment" | "brand" | "variant";

const GEO: { key: GeoKey; list: keyof NationalFilters; parent: GeoKey | null }[] = [
  { key: "wing", list: "wings", parent: null },
  { key: "division", list: "divisions", parent: "wing" },
  { key: "territory", list: "territories", parent: "division" },
  { key: "house", list: "houses", parent: "territory" },
  { key: "zone", list: "zones", parent: "territory" },
];
const PROD: { key: ProdKey; list: keyof NationalFilters; parent: ProdKey | null }[] = [
  { key: "category", list: "categories", parent: null },
  { key: "segment", list: "segments", parent: "category" },
  { key: "brand", list: "brands", parent: "segment" },
  { key: "variant", list: "variants", parent: "brand" },
];

export function FilterPanel({ filters, initial, today }: { filters: NationalFilters; initial: NationalQuery; today: string }) {
  const { t } = useI18n();
  const router = useRouter();
  const [shown, setShown] = useState(true);
  const [date, setDate] = useState(initial.date);
  const [geo, setGeo] = useState<Record<GeoKey, string[]>>(() => Object.fromEntries(GEO.map((g) => [g.key, (initial[g.key] ?? []).map(String)])) as Record<GeoKey, string[]>);
  const [prod, setProd] = useState<Record<ProdKey, string[]>>(() => Object.fromEntries(PROD.map((g) => [g.key, initial[g.key] ?? []])) as Record<ProdKey, string[]>);

  // Options of each level: all of the level, narrowed by what is chosen one level up (nothing chosen up there means no narrowing).
  const geoOptions = useMemo(() => {
    const out = {} as Record<GeoKey, Option[]>;
    for (const g of GEO) {
      const all = filters[g.list] as Option[];
      const up = g.parent ? geo[g.parent] : [];
      out[g.key] = up.length > 0 ? all.filter((o) => o.parent !== null && up.includes(String(o.parent))) : all;
    }
    return out;
  }, [filters, geo]);
  const prodOptions = useMemo(() => {
    const out = {} as Record<ProdKey, ProductOption[]>;
    for (const g of PROD) {
      const all = filters[g.list] as ProductOption[];
      const up = g.parent ? prod[g.parent] : [];
      out[g.key] = up.length > 0 ? all.filter((o) => o.parent !== null && up.includes(o.parent)) : all;
    }
    return out;
  }, [filters, prod]);

  const setGeoLevel = (key: GeoKey, v: string[]) =>
    setGeo((cur) => {
      const next = { ...cur, [key]: v };
      // Drop children that fall outside the new selection (house and zone both hang from territory).
      for (const g of GEO) {
        if (!g.parent || next[g.parent].length === 0) continue;
        const ok = new Set((filters[g.list] as Option[]).filter((o) => o.parent !== null && next[g.parent!].includes(String(o.parent))).map((o) => String(o.id)));
        next[g.key] = next[g.key].filter((id) => ok.has(id));
      }
      return next;
    });
  const setProdLevel = (key: ProdKey, v: string[]) =>
    setProd((cur) => {
      const next = { ...cur, [key]: v };
      for (const g of PROD) {
        if (!g.parent || next[g.parent].length === 0) continue;
        const ok = new Set((filters[g.list] as ProductOption[]).filter((o) => o.parent !== null && next[g.parent!].includes(o.parent)).map((o) => o.code));
        next[g.key] = next[g.key].filter((c) => ok.has(c));
      }
      return next;
    });

  const apply = (e: React.FormEvent) => {
    e.preventDefault();
    const q: NationalQuery = { date };
    for (const g of GEO) {
      const all = (filters[g.list] as Option[]).length;
      const picked = geo[g.key].map(Number);
      if (picked.length > 0 && picked.length < all) q[g.key] = picked;
    }
    for (const g of PROD) {
      const all = (filters[g.list] as ProductOption[]).length;
      if (prod[g.key].length > 0 && prod[g.key].length < all) q[g.key] = prod[g.key];
    }
    router.push(`/?${toSearch(q).toString()}`);
  };
  const reset = () => {
    setGeo({ wing: [], division: [], territory: [], house: [], zone: [] });
    setProd({ category: [], segment: [], brand: [], variant: [] });
    setDate(today);
    router.push("/");
  };

  const sel = (key: string, list: { id: string; name: string }[], value: string[], on: (v: string[]) => void, counted: boolean) => (
    <MultiSelect key={key} testId={`filter-${key}`} label={t(`nat.f.${key}` as never)} options={list} value={value} onChange={on} allText={counted ? t("nat.f.all_selected", { n: list.length }) : t("nat.f.all")} someText={(n) => t("nat.f.some_selected", { n })} clearLabel={t("nat.f.clear")} searchLabel={t("nat.f.search")} noneText={t("nat.f.none_found")} />
  );

  return (
    <div className="space-y-2">
      <div className="no-print flex justify-end">
        <button type="button" aria-expanded={shown} aria-controls="nat-filter-panel" onClick={() => setShown((s) => !s)} data-testid="filter-toggle" className="inline-flex min-h-(--ctl-h) items-center gap-2 rounded-chip bg-filter-fill px-4 text-body-strong text-on-accent hover:brightness-110 focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
          <Icon name="list" size={18} />
          {t("nat.filter.toggle")}
        </button>
      </div>
      <form id="nat-filter-panel" role="search" aria-label={t("nat.filter.panel")} hidden={!shown} onSubmit={apply} data-testid="filter-panel" className="no-print space-y-3">
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
          {GEO.map((g) => sel(g.key, geoOptions[g.key].map((o) => ({ id: String(o.id), name: o.name })), geo[g.key], (v) => setGeoLevel(g.key, v), true))}
        </div>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-5">
          {PROD.map((g) => sel(g.key, prodOptions[g.key].map((o) => ({ id: o.code, name: o.name })), prod[g.key], (v) => setProdLevel(g.key, v), false))}
          <label className="block">
            <span className="mb-1 block text-label font-semibold text-accent">{t("nat.f.date")}</span>
            <input type="date" name="date" value={date} max={today} onChange={(e) => setDate(e.target.value)} data-testid="filter-date" className="min-h-(--ctl-h) w-full rounded-chip border border-input bg-raised px-3 text-body text-primary focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus" />
          </label>
        </div>
        <div className="flex flex-wrap justify-end gap-2">
          <button type="button" onClick={reset} data-testid="filter-reset" className="inline-flex min-h-(--ctl-h) items-center rounded-chip px-4 text-body-strong text-accent hover:bg-pressed focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
            {t("nat.filter.reset")}
          </button>
          <button type="submit" data-testid="filter-apply" className="inline-flex min-h-(--ctl-h) items-center gap-2 rounded-chip bg-filter-fill px-5 text-body-strong text-on-accent hover:brightness-110 focus-visible:outline-(length:--focus-w) focus-visible:outline-offset-2 outline-focus">
            {t("nat.filter.apply")}
            <Icon name="filter" size={16} />
          </button>
        </div>
      </form>
    </div>
  );
}
