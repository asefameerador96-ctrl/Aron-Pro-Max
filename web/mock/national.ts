// Mock of the national dashboard slices (docs/ui-reference/web/dashboard-brief.md). Deterministic: the same filter always yields the
// same figures; at "everything selected" they reproduce the owner's screenshot of 2026-10-22 (dashboard.md arithmetic checks).
// A selection scales the national figures by the share of its zones (and products). The real slices arrive from backend-reports.
import type { DashCtx } from "./dash";

type Num = number;
const C = { cigarette: "stick", bidi: "stick", lighter: "piece", match: "dozen" } as const;

const WINGS = ["Dhaka", "Chattogram", "Rajshahi"];
const DIVS: [string, number][] = [["Dhaka North", 0], ["Dhaka South", 0], ["Chattogram North", 1], ["Chattogram South", 1], ["Rajshahi", 2], ["Bogura", 2]];
// 12 territories (two per division), 14 houses (one territory has two), 30 zones.
const TERRS = Array.from({ length: 12 }, (_, i) => ({ id: i + 1, name: `Territory ${i + 1}`, division: Math.floor(i / 2) + 1 }));
const HOUSES = Array.from({ length: 14 }, (_, i) => ({ id: i + 1, name: `House ${i + 1}`, territory: Math.min(12, i < 12 ? i + 1 : i - 11) }));
const ZONES = Array.from({ length: 30 }, (_, i) => {
  const territory = (i % 12) + 1;
  return { id: i + 1, name: `Zone ${i + 1}`, territory, house: HOUSES.find((h) => h.territory === territory)!.id, weight: 1 + ((i * 7) % 5) };
});
const TOTAL_W = ZONES.reduce((s, z) => s + z.weight, 0);

const CATS = [["cigarette", "Cigarette"], ["bidi", "Bidi"], ["lighter", "Lighter"], ["match", "Match"]] as const;
const SEGS = [["premium", "Premium", "cigarette"], ["mid", "Mid", "cigarette"], ["low", "Low", "cigarette"], ["bidi", "Bidi", "bidi"], ["lighter", "Lighter", "lighter"], ["match", "Match", "match"]] as const;
// brand: code, name, segment, STD quantity (base unit), memo count (screenshot values).
const BRANDS: [string, string, string, Num, Num][] = [
  ["maxim", "Maxim", "mid", 14850, 11058], ["avon", "Avon", "mid", 4905, 3202], ["aris", "ARIS", "low", 9446, 6223], ["marise", "Marise", "premium", 106000, 73216], ["black-diamond", "Black Diamond", "premium", 147000, 98859],
  ["supreme", "Supreme", "low", 7393, 5579], ["special-abul-bidi", "Special Abul Bidi", "bidi", 6832, 17623], ["ananda-bidi", "Ananda Bidi", "bidi", 5101, 3169], ["abul-bidi-gold", "Abul Bidi Gold", "bidi", 413, 311],
  ["abul-bidi-style", "Abul Bidi Style", "bidi", 2450, 1745], ["flame-box", "Flame Box", "match", 7735, 5909], ["salmon", "Salmon", "lighter", 536, 572], ["esse", "ESSE", "premium", 4061, 2900], ["max", "MAX", "low", 157, 1583],
];
const catOfSeg = (seg: string) => SEGS.find((s) => s[0] === seg)![2];
const BRAND_TOTAL = BRANDS.reduce((s, b) => s + b[3], 0);
const OWN_BRANDS = ["black-diamond", "marise"]; // what a BRAND-level mock user owns

const CH = { GT: [136_000, 26_400_000], DCC: [2_786, 560_000], Astha: [29_894, 8_548_000], RCC: [3_835, 890_000], MT: [124, 16_780], HoReCa: [41, 6_260] } as const;
const SALES_TOTAL = { cigarette: 36_421_040, bidi: 3_488_000, lighter: 106_000, match: 11_165 } as const;
const SALES_TARGET = { cigarette: 75_090_000, bidi: 9_107_000, lighter: 132_300, match: 64_500 } as const;

export type NationalView = "national" | "brand";
let view: NationalView = "national";
export const setNationalView = (v: NationalView) => (view = v);

const list = (v: string | null): string[] => (v ? v.split(",").filter(Boolean) : []);
const ids = (v: string | null): number[] => list(v).map(Number).filter(Number.isInteger);

function scopeZones(role: string, q: URLSearchParams) {
  let zs = ZONES;
  if (role === "TSO") zs = zs.filter((z) => z.territory === 1);
  else if (role === "DMO") zs = zs.filter((z) => TERRS.find((t) => t.id === z.territory)!.division === 1);
  else if (role === "WM") zs = zs.filter((z) => DIVS[TERRS.find((t) => t.id === z.territory)!.division - 1]![1] === 0);
  const pick = (name: string, f: (z: (typeof ZONES)[number], i: number[]) => boolean) => {
    const i = ids(q.get(name));
    if (i.length) zs = zs.filter((z) => f(z, i));
  };
  pick("wing", (z, i) => i.includes(DIVS[TERRS.find((t) => t.id === z.territory)!.division - 1]![1] + 1));
  pick("division", (z, i) => i.includes(TERRS.find((t) => t.id === z.territory)!.division));
  pick("territory", (z, i) => i.includes(z.territory));
  pick("house", (z, i) => i.includes(z.house));
  pick("zone", (z, i) => i.includes(z.id));
  return zs;
}

function brandsIn(q: URLSearchParams) {
  let bs = BRANDS;
  const cat = list(q.get("category")), seg = list(q.get("segment")), br = list(q.get("brand"));
  if (cat.length) bs = bs.filter((b) => cat.includes(catOfSeg(b[2])));
  if (seg.length) bs = bs.filter((b) => seg.includes(b[2]));
  if (br.length) bs = bs.filter((b) => br.includes(b[0]));
  if (view === "brand") bs = bs.filter((b) => OWN_BRANDS.includes(b[0]));
  return bs;
}

/** Date factor: past days are final and a little different; today is "till time" (the screenshot's noon snapshot). */
function dayFactor(date: string, today: string): number {
  if (date === today) return 1;
  let h = 0;
  for (const c of date) h = (h * 31 + c.charCodeAt(0)) % 997;
  return 1.9 + (h % 20) / 100; // a complete day is about twice a noon snapshot
}
const AS_OF = (date: string, today: string) => (date === today ? new Date().toISOString() : `${date}T17:59:00.000Z`);

function rng(seed: number) {
  let s = seed >>> 0 || 1;
  return () => ((s = (Math.imul(s, 1664525) + 1013904223) >>> 0) / 4294967296);
}

const CITIES: [number, number, number][] = [[23.78, 90.4, 2.5], [22.35, 91.8, 1.5], [24.9, 91.87, 1], [24.37, 88.6, 1], [22.8, 89.55, 1], [24.75, 90.4, 1], [25.74, 89.25, 0.7], [22.7, 90.35, 0.7]];
function pinsFor(zs: typeof ZONES) {
  const out: { route_id: number; lat: number; lng: number; geo_pct: number; zone: (typeof ZONES)[number]; n: number }[] = [];
  for (const z of zs) {
    const r = rng(z.id * 97);
    for (let i = 0; i < z.weight + 2; i++) {
      const [la, lo, spread] = CITIES[(z.id * 3 + i) % CITIES.length]!;
      const g = r();
      out.push({ route_id: z.id * 100 + i, lat: la + (r() - 0.5) * 0.4 * spread, lng: lo + (r() - 0.5) * 0.4 * spread, geo_pct: g < 0.15 ? 20 + r() * 49 : g < 0.4 ? 70 + r() * 20 : 91 + r() * 9, zone: z, n: i });
    }
  }
  return out;
}
const r2 = (n: number) => Math.round(n * 100) / 100;

export async function handleNational(x: DashCtx): Promise<boolean> {
  const { path, method, user, send, problem, url } = x;
  if (!path.startsWith("/v1/dashboards/national/") || method !== "GET") return false;
  const q = url.searchParams;
  const date = q.get("date") ?? new Date().toISOString().slice(0, 10);
  const today = new Date().toISOString().slice(0, 10);
  const stamp = { business_date: date, as_of: AS_OF(date, today) };
  const brand = view === "brand";
  const hidden = () => send(403, problem(403, "ERR_FORBIDDEN"));
  const zs = scopeZones(user.role, q);
  const f = (zs.reduce((s, z) => s + z.weight, 0) / TOTAL_W) * dayFactor(date, today);
  const bs = brandsIn(q);
  const pf = brand || list(q.get("category")).length || list(q.get("segment")).length || list(q.get("brand")).length || list(q.get("variant")).length ? bs.reduce((s, b) => s + b[3], 0) / BRAND_TOTAL : 1;
  const sc = (n: number, extra = 1) => Math.round(n * f * extra);
  const name = path.slice("/v1/dashboards/national/".length);

  if (name === "filters") {
    const wingIds = [...new Set(zs.map((z) => DIVS[TERRS.find((t) => t.id === z.territory)!.division - 1]![1] + 1))];
    const divIds = [...new Set(zs.map((z) => TERRS.find((t) => t.id === z.territory)!.division))];
    const terIds = new Set(zs.map((z) => z.territory));
    const houseIds = new Set(zs.map((z) => z.house));
    return send(200, {
      wings: wingIds.map((id) => ({ id, name: WINGS[id - 1], parent: null })),
      divisions: DIVS.map((d, i) => ({ id: i + 1, name: d[0], parent: d[1] + 1 })).filter((d) => divIds.includes(d.id)),
      territories: TERRS.filter((t) => terIds.has(t.id)).map((t) => ({ id: t.id, name: t.name, parent: t.division })),
      houses: HOUSES.filter((h) => houseIds.has(h.id)).map((h) => ({ id: h.id, name: h.name, parent: h.territory })),
      zones: zs.map((z) => ({ id: z.id, name: z.name, parent: z.territory })),
      categories: CATS.map((c) => ({ code: c[0], name: c[1], parent: null })),
      segments: SEGS.map((s) => ({ code: s[0], name: s[1], parent: s[2] })),
      brands: BRANDS.filter((b) => !brand || OWN_BRANDS.includes(b[0])).map((b) => ({ code: b[0], name: b[1], parent: b[2] })),
      variants: BRANDS.filter((b) => !brand || OWN_BRANDS.includes(b[0])).flatMap((b) => [{ code: `${b[0]}-std`, name: `${b[1]} Standard`, parent: b[0] }, { code: `${b[0]}-lt`, name: `${b[1]} Light`, parent: b[0] }]),
      view: { product_only: brand },
    }), true;
  }

  const productCards = ["sales", "channel-std", "breakdown"].includes(name);
  if (brand && !productCards) {
    if (["cpr", "channel-calls", "final-submit", "login-submit", "geo-fencing", "ff-geo"].includes(name) || name.startsWith("route-map/") || name.startsWith("sale-position/")) return hidden(), true;
  }

  if (name === "sales") {
    const cats = brandsIn(q);
    return send(200, {
      ...stamp,
      cards: (Object.keys(C) as (keyof typeof C)[]).map((c) => {
        const share = (() => {
          const all = BRANDS.filter((b) => catOfSeg(b[2]) === c).reduce((s, b) => s + b[3], 0);
          const sel = cats.filter((b) => catOfSeg(b[2]) === c).reduce((s, b) => s + b[3], 0);
          return all > 0 ? sel / all : 0;
        })();
        return { category: c, unit: C[c], total: sc(SALES_TOTAL[c], share), daily_target: r2(Math.round(SALES_TARGET[c] * (f / dayFactor(date, today)) * share)) || null };
      }),
    }), true;
  }
  if (name === "cpr") return send(200, { ...stamp, target_outlets: Math.round(460_000 * (f / dayFactor(date, today)) * pf), successful_calls: sc(174_000, pf) }), true;
  if (name === "channel-calls") return send(200, { ...stamp, channels: Object.entries(CH).map(([code, v]) => ({ code, value: sc(v[0], pf) })) }), true;
  if (name === "channel-std") return send(200, { ...stamp, channels: Object.entries(CH).map(([code, v]) => ({ code, value: sc(v[1], pf) })) }), true;
  if (name === "final-submit") {
    const total = Math.round(1051 * (zs.length / ZONES.length));
    const service = Math.round(1046 * (zs.length / ZONES.length));
    return send(200, { ...stamp, total_zone: total, service_zone: service, submitted: date === today ? 0 : service }), true;
  }
  if (name === "login-submit") {
    const routes = Math.round(6933 * (zs.length / ZONES.length));
    const logged = Math.round(routes * 0.998);
    return send(200, { ...stamp, target_routes: routes, logged_in: logged, submitted: date === today ? Math.round(logged * 0.0009) : Math.round(logged * 0.97) }), true;
  }
  if (name === "geo-fencing") {
    const total = sc(200_000);
    return send(200, { ...stamp, total_calls: total, geo_validation: Math.round(total * 0.9796) }), true;
  }
  if (name === "breakdown") {
    const measure = q.get("measure") === "memo" ? "memo" : "std";
    const by = q.get("by") === "segment" ? "segment" : q.get("by") === "variant" ? "variant" : "brand";
    const rows =
      by === "brand"
        ? bs.map((b) => ({ key: b[0], label: b[1], value: Math.round((measure === "std" ? b[3] : b[4]) * f), unit: measure === "std" ? C[catOfSeg(b[2]) as keyof typeof C] : null }))
        : by === "segment"
          ? SEGS.filter((s) => bs.some((b) => b[2] === s[0])).map((s) => ({ key: s[0], label: s[1], value: Math.round(bs.filter((b) => b[2] === s[0]).reduce((a, b) => a + (measure === "std" ? b[3] : b[4]), 0) * f), unit: measure === "std" ? C[s[2] as keyof typeof C] : null }))
          : bs.flatMap((b) => [["std", "Standard", 0.62], ["lt", "Light", 0.38]].map(([k, l, sh]) => ({ key: `${b[0]}-${k}`, label: `${b[1]} ${l}`, value: Math.round((measure === "std" ? b[3] : b[4]) * f * (sh as number)), unit: measure === "std" ? C[catOfSeg(b[2]) as keyof typeof C] : null })));
    return send(200, { ...stamp, measure, by, rows }), true;
  }
  if (name === "ff-geo") {
    const pins = pinsFor(zs).map((p) => {
      const t = TERRS.find((tt) => tt.id === p.zone.territory)!;
      return { route_id: p.route_id, lat: Math.round(p.lat * 1e5) / 1e5, lng: Math.round(p.lng * 1e5) / 1e5, geo_pct: r2(p.geo_pct), sr_name: `SR ${p.route_id}`, contact: x.piiOpen ? `01700${String(p.route_id).padStart(6, "0")}` : null, territory_id: t.id, territory_name: t.name, zone: p.zone.name, route: `Route ${p.n + 1}`, at: `${date}T0${6 + (p.n % 3)}:${10 + p.n}:00.000Z` };
    });
    return send(200, { ...stamp, pins }), true;
  }
  const rm = /^route-map\/(\d+)$/.exec(name);
  if (rm) {
    const id = Number(rm[1]);
    const pin = pinsFor(ZONES).find((p) => p.route_id === id);
    if (!pin) return send(404, problem(404, "ERR_NOT_FOUND")), true;
    const r = rng(id);
    const t = TERRS.find((tt) => tt.id === pin.zone.territory)!;
    const outlets = Array.from({ length: 50 }, (_, i) => {
      const called = i < 15;
      const state = !called ? "not_called" : i % 5 === 4 ? "photo" : "geo";
      return { id: id * 1000 + i, code: String(3_000_000 + id * 10 + i), name: `Outlet ${i + 1}`, contact: x.piiOpen ? `0171${String(id * 100 + i).padStart(7, "0")}` : null, cluster: `Cluster ${1 + (i % 4)}`, lat: pin.lat + (r() - 0.5) * 0.02, lng: pin.lng + (r() - 0.5) * 0.02, state, at: called ? `${date}T0${3 + Math.floor(i / 6)}:${String(10 + i * 3).padStart(2, "0")}:00.000Z` : null, sales: called ? { cigarette: 120 + i * 7, bidi: i % 3 === 0 ? 200 : 0, lighter: i % 4 === 0 ? 2 : 0, match: i % 2 ? 1 : 0 } : null };
    });
    const called = outlets.filter((o) => o.at);
    const path = called.map((o) => ({ lat: o.lat, lng: o.lng, at: o.at! }));
    const last = path[path.length - 1];
    return send(200, {
      ...stamp, sr_name: `SR ${id}`, territory_id: t.id, territory_name: t.name, zone: pin.zone.name, route: `Route ${pin.n + 1}`,
      start: path[0] ?? null, last_position: last ?? null, path, outlets,
      target_vs_achievement: [{ category: "cigarette", pct: 26 }, { category: "bidi", pct: 0 }, { category: "lighter", pct: 60 }, { category: "match", pct: 68 }],
      last_sale_place: "Rampasha Bazar",
      cpr: { target_outlets: 50, successful_calls: called.length, geo_validation: called.filter((o) => o.state === "geo").length },
      time: { first_sale: called[0]?.at ?? null, last_sale: called[called.length - 1]?.at ?? null, distance_m: 1196.97 },
    }), true;
  }
  const sp = /^sale-position\/(\d+)$/.exec(name);
  if (sp) {
    const id = Number(sp[1]);
    const r = rng(id);
    const baseLat = 23.76 + r() * 0.1, baseLng = 90.35 + r() * 0.1;
    const photo = `data:image/svg+xml;charset=UTF-8,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="160" height="120"><rect width="160" height="120" fill="#8b5a2b"/><path d="M0 100L60 30l40 40 60-50v100z" fill="#c9a36b"/></svg>')}`;
    return send(200, { ...stamp, zone: "Kuakata", route: "Mohepur", outlet_code: String(3_000_000 + id), radius_m: 50, base: { lat: baseLat, lng: baseLng }, sales: [
      { id: `${id}-1`, kind: "new", lat: baseLat - 0.0004, lng: baseLng - 0.0007, distance_m: 81.83, at: `${date}T05:41:01.000Z`, photo_url: photo },
      { id: `${id}-2`, kind: "repeat", lat: baseLat + 0.0001, lng: baseLng + 0.0001, distance_m: 15.4, at: `${date}T07:02:11.000Z`, photo_url: null },
      { id: `${id}-3`, kind: "edited", lat: baseLat + 0.0001, lng: baseLng - 0.0001, distance_m: 17.2, at: `${date}T08:12:40.000Z`, photo_url: null },
    ] }), true;
  }
  return send(404, problem(404, "ERR_NOT_FOUND")), true;
}
