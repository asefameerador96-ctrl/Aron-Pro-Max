// Mock of the national dashboard slices (docs/ui-reference/web/dashboard-brief.md). Deterministic: the same filter always yields the
// same figures; at "everything selected" they reproduce the owner's screenshot of 2026-10-22 (dashboard.md arithmetic checks).
// A selection scales the national figures by the share of its zones (and products). The real slices arrive from backend-reports.
import type { DashCtx } from "./dash";
import { loginSubmitUsers } from "./tso-manual"; // tso-manual W1 (step 2)
import type { Schemas } from "../src/contract/types"; // tso-manual W1 (step 2)

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

function brandsIn(q: URLSearchParams, role: string) {
  let bs = BRANDS;
  const cat = list(q.get("category")), seg = list(q.get("segment")), br = list(q.get("brand"));
  if (cat.length) bs = bs.filter((b) => cat.includes(catOfSeg(b[2])));
  if (seg.length) bs = bs.filter((b) => seg.includes(b[2]));
  if (br.length) bs = bs.filter((b) => br.includes(b[0]));
  if (role === "BRAND") bs = bs.filter((b) => OWN_BRANDS.includes(b[0]));
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
/** A ratio as integer basis points (10000 = 100%), null without a denominator. */
const bp = (part: number, whole: number): number | null => (whole > 0 ? Math.round((part * 10000) / whole) : null);

const PHOTO = `data:image/svg+xml;charset=UTF-8,${encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="160" height="120"><rect width="160" height="120" fill="#8b5a2b"/><path d="M0 100L60 30l40 40 60-50v100z" fill="#c9a36b"/></svg>')}`;

/** The filter of a POST body as the comma-list parameters the helpers above read. */
function filterParams(b: Record<string, unknown> | null): URLSearchParams {
  const f = ((b && typeof b.filter === "object" ? b.filter : b) ?? {}) as Record<string, unknown>;
  const q = new URLSearchParams();
  for (const [from, to] of [["wing_ids", "wing"], ["division_ids", "division"], ["territory_ids", "territory"], ["house_ids", "house"], ["zone_ids", "zone"], ["category_codes", "category"], ["segment_codes", "segment"], ["brand_codes", "brand"], ["variant_codes", "variant"]] as const) {
    const v = f[from];
    if (Array.isArray(v) && v.length) q.set(to, v.join(","));
  }
  if (typeof f.date === "string") q.set("date", f.date);
  return q;
}
const colorOf = (g: number) => (g < 70 ? "red" : g <= 90 ? "amber" : "green");

/** The national dashboard operations of contract 1.4.0 (lane backend-reports): POST cards, GET filter options, route map, sale position. */
export async function handleNational(x: DashCtx): Promise<boolean> {
  const { path, method, user, send, problem, url } = x;
  const media = /^\/v1\/media\/([0-9a-f-]{36})\/read-url$/.exec(path);
  if (media && method === "GET") return send(200, { url: PHOTO, expires_at: new Date(Date.now() + 300_000).toISOString() }), true;
  const national = path.startsWith("/v1/dashboards/national/");
  const mapPage = /^\/v1\/dashboards\/(route-map|sale-position)\/(\d+)$/.exec(path);
  if (!national && !mapPage) return false;
  const body = method === "POST" ? ((await x.body()) as Record<string, unknown> | null) : null;
  const q = method === "POST" ? filterParams(body) : url.searchParams;
  const today = new Date(Date.now() + 6 * 3_600_000).toISOString().slice(0, 10); // Asia/Dhaka business date, like the server
  const date = q.get("date") ?? today;
  const asOf = AS_OF(date, today);
  const common = { date, till_time: date === today, as_of: asOf, last_updated_at: asOf };
  const brand = user.role === "BRAND";
  const forbidden = () => (send(403, problem(403, "ERR_FORBIDDEN")), true);
  const zs = scopeZones(user.role, q);
  const dayless = zs.reduce((s, z) => s + z.weight, 0) / TOTAL_W;
  const f = dayless * dayFactor(date, today);
  const bs = brandsIn(q, user.role);
  const pf = brand || list(q.get("category")).length || list(q.get("segment")).length || list(q.get("brand")).length || list(q.get("variant")).length ? bs.reduce((s, b) => s + b[3], 0) / BRAND_TOTAL : 1;
  const sc = (n: number, extra = 1) => Math.round(n * f * extra);
  const name = national ? path.slice("/v1/dashboards/national/".length) : "";
  const productCards = ["sales", "channel-std", "breakdown", "filter-options", "segment-share"]; // tso-manual W1: segment-share (as queryNationalSales)
  if (user.id === 2009) return forbidden(); // an ADMIN without the dashboard module grant (docs/34 s1)
  // BRAND: cards without a brand dimension are refused (docs/34 s1).
  if (brand && ((national && !productCards.includes(name)) || mapPage)) return forbidden();

  if (name === "filter-options" && method === "GET") {
    const div = (zid: number) => TERRS.find((t) => t.id === zid)!.division;
    const wingOf = (d: number) => DIVS[d - 1]![1] + 1;
    const divIds = [...new Set(zs.map((z) => div(z.territory)))];
    const terIds = new Set(zs.map((z) => z.territory));
    const houseIds = new Set(zs.map((z) => z.house));
    const bsAll = BRANDS.filter((b) => !brand || OWN_BRANDS.includes(b[0]));
    return send(200, {
      ...common,
      wings: [...new Set(divIds.map(wingOf))].map((id) => ({ id, code: `W${id}`, name: WINGS[id - 1] })),
      divisions: DIVS.map((d, i) => ({ id: i + 1, code: `D${i + 1}`, name: d[0], wing_id: d[1] + 1 })).filter((d) => divIds.includes(d.id)),
      territories: TERRS.filter((t) => terIds.has(t.id)).map((t) => ({ id: t.id, code: String(1300 + t.id), name: t.name, wing_id: wingOf(t.division), division_id: t.division })),
      houses: HOUSES.filter((h) => houseIds.has(h.id)).map((h) => ({ id: h.id, code: `H${h.id}`, name: h.name, territory_id: h.territory })),
      zones: zs.map((z) => ({ id: z.id, code: `Z${z.id}`, name: z.name, territory_id: z.territory, house_id: z.house })),
      categories: CATS.map((c) => ({ code: c[0], name: c[1] })),
      segments: SEGS.map((s) => ({ code: s[0], name: s[1], category_code: s[2] })),
      brands: bsAll.map((b) => ({ code: b[0], name: b[1], category_code: catOfSeg(b[2]), segment_code: b[2] })),
      variants: bsAll.flatMap((b) => [{ code: `${b[0]}-std`, name: `${b[1]} Standard`, brand_code: b[0] }, { code: `${b[0]}-lt`, name: `${b[1]} Light`, brand_code: b[0] }]),
    }), true;
  }
  if (name === "freshness" && method === "GET") return send(200, { ...common, tables: [{ table: "summary_sales", last_updated_at: asOf }] }), true;

  if (name === "sales") {
    const cats = bs;
    return send(200, {
      ...common, working_days: 26,
      cards: (Object.keys(C) as (keyof typeof C)[]).map((c) => {
        const all = BRANDS.filter((b) => catOfSeg(b[2]) === c).reduce((s, b) => s + b[3], 0);
        const sel = cats.filter((b) => catOfSeg(b[2]) === c).reduce((s, b) => s + b[3], 0);
        const share = all > 0 ? sel / all : 0;
        const total = sc(SALES_TOTAL[c], share);
        const target = Math.round(SALES_TARGET[c] * dayless * share);
        return { category_code: c, base_unit: C[c], total_sales: total, daily_target: target > 0 ? `${target}.000` : null, sales_so_far: total, remaining: target > 0 ? `${Math.max(0, target - total)}.000` : null, achievement_pct_bp: bp(total, target), target_routes: zs.length * 4 };
      }),
    }), true;
  }
  if (name === "cpr") {
    const target = Math.round(460_000 * dayless * pf), calls = sc(174_000, pf);
    return send(200, { ...common, target_outlets: target, successful_calls: calls, cpr_pct_bp: bp(calls, target) }), true;
  }
  const channelBody = (col: 0 | 1) => {
    const rows = Object.entries(CH).map(([channel, v]) => ({ channel, value: sc(v[col], pf) }));
    const total = rows.reduce((s, r) => s + r.value, 0);
    return { rows: rows.map((r) => ({ ...r, share_pct_bp: bp(r.value, total) })), total };
  };
  if (name === "channel-calls") { const c = channelBody(0); return send(200, { ...common, channels: c.rows, total: c.total, unclassified: 0 }), true; }
  if (name === "channel-std") { const c = channelBody(1); return send(200, { ...common, channels: c.rows, total: c.total, unit: "stick" }), true; }
  if (name === "final-submit") {
    const total = Math.round(1051 * (zs.length / ZONES.length)), service = Math.round(1046 * (zs.length / ZONES.length)), done = date === today ? 0 : service;
    return send(200, { ...common, total_zones: total, service_zones: service, final_submitted: done, remaining: service - done, submit_pct_bp: bp(done, service) }), true;
  }
  // tso-manual W1 (step 2): the national operations of contract 1.10.0. Integer basis points, rounded half up once from the counts.
  const bpOf = (n: number, d: number): number | null => (d > 0 ? Math.floor((20_000 * n + d) / (2 * d)) : null);
  if (name === "login-submit/users" && method === "POST") {
    // D-TSO-MAN-12 over the seeded day of mock/seed.ts (the drill page's geo ids), not this file's zones.
    const r = loginSubmitUsers(user.scope.nodes, body && typeof body === "object" ? body : null, url.searchParams.get("tab"));
    if (!r) return send(400, problem(400, "ERR_VALIDATION", { errors: [{ pointer: "/tab", code: "invalid" }] })), true;
    return send(200, { ...common, ...r } satisfies Schemas["NationalLoginSubmitUsers"]), true;
  }
  if (name === "brand-cpr" && method === "POST") {
    // D-TSO-MAN-06: outlets that bought each brand; denominator successful calls (the cfg default), both readings sent.
    const target = Math.round(460_000 * dayless * pf), calls = sc(174_000, pf);
    const brands = bs
      .map((b, i) => ({ brand_code: b[0], brand_name: b[1], outlets_bought: Math.min(calls, Math.round(calls * (0.6 - (i % 7) * 0.08))) }))
      .map((b) => ({ ...b, outlets_bought: Math.max(0, b.outlets_bought) }))
      .sort((a, b) => b.outlets_bought - a.outlets_bought || a.brand_code.localeCompare(b.brand_code))
      .map((b) => ({ ...b, cpr_bp: bpOf(b.outlets_bought, calls), cpr_of_successful_bp: bpOf(b.outlets_bought, calls), cpr_of_target_bp: bpOf(b.outlets_bought, target) }));
    return send(200, { ...common, target_outlets: target, successful_calls: calls, denominator: "successful_calls", brands } satisfies Schemas["NationalBrandCpr"]), true;
  }
  if (name === "segment-share" && method === "POST") {
    // D-TSO-MAN-11: value share of all sale-line value; volume share inside the segment's unit family (never across units).
    const MTK_PER_BASE = { stick: 7_935, piece: 25_000, dozen: 12_000 } as const;
    const segs = SEGS.map(([code, nameS, cat]) => {
      const base_unit = C[cat as keyof typeof C];
      const qty_base = bs.filter((b) => b[2] === code).reduce((a, b) => a + Math.round(b[3] * f), 0);
      return { segment_code: code, segment_name: nameS, category_code: cat, base_unit, qty_base, value_mtk: qty_base * MTK_PER_BASE[base_unit] };
    }).filter((x) => x.qty_base > 0);
    const totalValue = segs.reduce((a, x) => a + x.value_mtk, 0);
    const families = (["stick", "piece", "dozen"] as const).map((u) => ({ base_unit: u, total_qty_base: segs.filter((x) => x.base_unit === u).reduce((a, x) => a + x.qty_base, 0) })).filter((x) => x.total_qty_base > 0);
    const famTotal = (u: string) => families.find((x) => x.base_unit === u)?.total_qty_base ?? 0;
    const segments = segs
      .map((x) => ({ ...x, value_share_bp: bpOf(x.value_mtk, totalValue), volume_share_bp: bpOf(x.qty_base, famTotal(x.base_unit)) }))
      .sort((a, b) => b.value_mtk - a.value_mtk || a.segment_code.localeCompare(b.segment_code));
    return send(200, { ...common, total_value_mtk: totalValue, families, segments } satisfies Schemas["NationalSegmentShare"]), true;
  }
  if (name === "login-submit") {
    const routes = Math.round(6933 * (zs.length / ZONES.length)), logged = Math.round(routes * 0.998), sub = date === today ? Math.round(logged * 0.0009) : Math.round(logged * 0.97);
    return send(200, { ...common, target_routes: routes, successful_login: logged, login_pct_bp: bp(logged, routes), submitted: sub, submit_pct_bp: bp(sub, logged) }), true;
  }
  if (name === "geo-fencing") {
    const total = sc(200_000), ok = Math.round(total * 0.9796);
    return send(200, { ...common, total_calls: total, geo_validated: ok, geo_pct_bp: bp(ok, total) }), true;
  }
  if (name === "breakdown") {
    const measure = body?.measure === "memo" ? "memo" : "std";
    const by = body?.dimension === "segment" ? "segment" : body?.dimension === "variant" ? "variant" : "brand";
    const onlyCat = typeof body?.category_code === "string" ? body.category_code : measure === "std" ? "cigarette" : null; // STD is limited to one category (UI-WEB-09)
    const pool = bs.filter((b) => !onlyCat || measure === "memo" ? true : catOfSeg(b[2]) === onlyCat);
    const val = (b: (typeof BRANDS)[number]) => Math.round((measure === "std" ? b[3] : b[4]) * f);
    const items =
      by === "brand" ? pool.map((b) => ({ code: b[0], name: b[1], value: val(b) }))
      : by === "segment" ? SEGS.filter((s) => pool.some((b) => b[2] === s[0])).map((s) => ({ code: s[0], name: s[1], value: pool.filter((b) => b[2] === s[0]).reduce((a, b) => a + val(b), 0) }))
      : pool.flatMap((b) => [["std", "Standard", 0.62], ["lt", "Light", 0.38]].map(([k, l, sh]) => ({ code: `${b[0]}-${k}`, name: `${b[1]} ${l}`, value: Math.round(val(b) * (sh as number)) })));
    return send(200, { ...common, measure, dimension: by, ...(measure === "std" ? { category_code: onlyCat, unit: C[onlyCat as keyof typeof C] ?? "stick" } : {}), items }), true;
  }
  if (name === "ff-geo/pins") {
    const pins = pinsFor(zs).map((p) => ({ route_id: p.route_id, lat: Math.round(p.lat * 1e5) / 1e5, lng: Math.round(p.lng * 1e5) / 1e5, color: colorOf(p.geo_pct), geo_pct_bp: Math.round(p.geo_pct * 100), position_at: `${date}T0${6 + (p.n % 3)}:${10 + p.n}:00.000Z` }));
    const count = (c: string) => pins.filter((p) => p.color === c).length;
    return send(200, { ...common, pins, counts: { red: count("red"), amber: count("amber"), green: count("green") }, routes_without_position: 0 }), true;
  }
  const pop = /^ff-geo\/routes\/(\d+)$/.exec(name);
  if (pop) {
    const id = Number(pop[1]);
    const pin = pinsFor(ZONES).find((p) => p.route_id === id);
    if (!pin) return send(404, problem(404, "ERR_NOT_FOUND")), true;
    const t = TERRS.find((tt) => tt.id === pin.zone.territory)!;
    return send(200, { ...common, route: { id, code: `R${id}`, name: `Route ${pin.n + 1}` }, sr_name: `SR ${id}`, contact: x.piiOpen ? `01700${String(id).padStart(6, "0")}` : null, territory: { id: t.id, code: String(1300 + t.id), name: t.name }, zone: { id: pin.zone.id, code: `Z${pin.zone.id}`, name: pin.zone.name }, position_at: `${date}T0${6 + (pin.n % 3)}:${10 + pin.n}:00.000Z`, miss_pct_bp: Math.round((100 - pin.geo_pct) * 100) }), true;
  }
  const rm = mapPage?.[1] === "route-map" ? Number(mapPage[2]) : null;
  if (rm !== null) {
    const pin = pinsFor(ZONES).find((p) => p.route_id === rm);
    if (!pin) return send(404, problem(404, "ERR_NOT_FOUND")), true;
    const r = rng(rm);
    const t = TERRS.find((tt) => tt.id === pin.zone.territory)!;
    const outlets = Array.from({ length: 50 }, (_, i) => {
      const called = i < 15;
      return { outlet_id: rm * 1000 + i, code: String(3_000_000 + rm * 10 + i), name: `Outlet ${i + 1}`, contact: x.piiOpen ? `0171${String(rm * 100 + i).padStart(7, "0")}` : null, cluster_name: `Cluster ${1 + (i % 4)}`, lat: pin.lat + (r() - 0.5) * 0.02, lng: pin.lng + (r() - 0.5) * 0.02, state: !called ? "not_called" : i % 5 === 4 ? "photo_validation" : "geo_validated", call_time: called ? `${date}T0${3 + Math.floor(i / 6)}:${String(10 + i * 3).padStart(2, "0")}:00.000Z` : null, sales: called ? [{ category_code: "cigarette", base_unit: "stick", qty: 120 + i * 7 }, { category_code: "bidi", base_unit: "stick", qty: i % 3 === 0 ? 200 : 0 }, { category_code: "lighter", base_unit: "piece", qty: i % 4 === 0 ? 2 : 0 }, { category_code: "match", base_unit: "dozen", qty: i % 2 ? 1 : 0 }] : [] };
    });
    const called = outlets.filter((o) => o.call_time);
    const path = called.map((o) => ({ lat: o.lat, lng: o.lng, at: o.call_time! }));
    const secs = 9180;
    return send(200, {
      ...common, route: { id: rm, code: `R${rm}`, name: `Route ${pin.n + 1}` }, sr_name: `SR ${rm}`, territory: { id: t.id, code: String(1300 + t.id), name: t.name }, zone: { id: pin.zone.id, code: `Z${pin.zone.id}`, name: pin.zone.name },
      sale_start: path[0] ?? null, sr_last_position: path[path.length - 1] ?? null, outlets, path,
      target_vs_achievement: [["cigarette", 26], ["bidi", 0], ["lighter", 60], ["match", 68]].map(([c, p]) => ({ category_code: c, base_unit: C[c as keyof typeof C], daily_target: "1000.000", sales: Number(p) * 10, achievement_pct_bp: Number(p) * 100 })),
      last_sale_place: "Rampasha Bazar",
      cpr: { target_outlets: 50, successful_calls: called.length, geo_validated: called.filter((o) => o.state === "geo_validated").length, cpr_pct_bp: bp(called.length, 50) },
      time_card: { first_sale_time: called[0]?.call_time ?? null, last_sale_time: called[called.length - 1]?.call_time ?? null, total_sale_seconds: secs, average_sale_seconds: Math.round(secs / called.length), distance_covered_m: 1196.97 },
    }), true;
  }
  const spId = mapPage?.[1] === "sale-position" ? Number(mapPage[2]) : null;
  if (spId !== null) {
    const r = rng(spId);
    const baseLat = 23.76 + r() * 0.1, baseLng = 90.35 + r() * 0.1;
    return send(200, { ...common, zone: { id: 1, code: "Z1", name: "Kuakata" }, route: { id: 1, code: "R1", name: "Mohepur" }, outlet_code: String(3_000_000 + spId), radius_m: 50, base: { lat: baseLat, lng: baseLng }, header_distance_m: 81.83, sales: [
      { memo_uuid: "00000000-0000-4000-8000-000000000001", kind: "new", lat: baseLat - 0.0004, lng: baseLng - 0.0007, at: `${date}T05:41:01.000Z`, distance_m: 81.83, within_fence: false, status: "New Sale", photo_media_uuid: "00000000-0000-4000-8000-0000000000a1" },
      { memo_uuid: "00000000-0000-4000-8000-000000000002", kind: "repeat", lat: baseLat + 0.0001, lng: baseLng + 0.0001, at: `${date}T07:02:11.000Z`, distance_m: 15.4, within_fence: true, status: "Repeat Sale", photo_media_uuid: null },
      { memo_uuid: "00000000-0000-4000-8000-000000000003", kind: "edited", lat: baseLat + 0.0001, lng: baseLng - 0.0001, at: `${date}T08:12:40.000Z`, distance_m: 17.2, within_fence: true, status: "Edited Sale", photo_media_uuid: null },
    ] }), true;
  }
  return send(404, problem(404, "ERR_NOT_FOUND")), true;
}
