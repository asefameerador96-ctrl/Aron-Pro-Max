// Mock of the master-data operations that are not plain table CRUD: user scope, sales plans, prices (preview + publish).
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Ctx, Row } from "./tables";

export interface CustomState {
  tables: Record<string, Row[]>;
  scopes: Record<number, { version: number; nodes: { node_type: string; node_id: number; valid_from: string; valid_to: string | null }[] }>;
  salesPlans: Record<number, { valid_from: string; sku_ids: number[] }>;
  /** Wave 4 grants of a user: module levels (ADMIN) and brand ids (BRAND), with the If-Match version. */
  access: Record<number, { version: number; modules: Record<string, string>; brand_ids: number[] }>;
  /** Wave 4 route monthly targets by route code, SKU code and month; batches seen (idempotent by batch_uuid). */
  routeTargets: { route_code: string; sku_code: string; month: string; qty_base: number }[];
  routeTargetBatches: Map<string, { hash: string; rows: number }>;
  outletImportBatches: Map<string, { hash: string; rows: number }>;
  priceBatches: Map<string, { hash: string; status: "applied" | "pending_approval"; valid_from: string }>;
  prices: { id: number; sku_id: number; price_type: string; amount_mtk: number; per_base_qty: number; valid_from: string; valid_to: string | null }[];
}

export function seedCustom(): Pick<CustomState, "scopes" | "salesPlans" | "priceBatches" | "prices" | "access" | "routeTargets" | "routeTargetBatches" | "outletImportBatches"> {
  return {
    outletImportBatches: new Map(),
    routeTargets: [{ route_code: "R-334-01", sku_code: "MaxR-20S", month: thisMonth(), qty_base: 2400 }],
    routeTargetBatches: new Map(),
    access: { 3001: { version: 2, modules: { outlets: "w", reports: "r" }, brand_ids: [] }, 5002: { version: 1, modules: {}, brand_ids: [5] } },
    scopes: { 2001: { version: 7, nodes: [{ node_type: "territory", node_id: 6, valid_from: "2026-09-01", valid_to: null }] }, 1001: { version: 1, nodes: [] } },
    salesPlans: { 14: { valid_from: "2026-09-01", sku_ids: [1] } },
    priceBatches: new Map(),
    prices: [
      { id: 1, sku_id: 1, price_type: "outlet", amount_mtk: 1_200_000, per_base_qty: 1, valid_from: "2026-09-01", valid_to: null },
      { id: 2, sku_id: 1, price_type: "cc", amount_mtk: 1_150_000, per_base_qty: 1, valid_from: "2026-09-01", valid_to: null },
      { id: 3, sku_id: 2, price_type: "outlet", amount_mtk: 2_400_000, per_base_qty: 1, valid_from: "2026-09-01", valid_to: null },
    ],
  };
}

const thisMonth = () => new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Dhaka", year: "numeric", month: "2-digit" }).format(new Date()).slice(0, 7);
const today = () => new Intl.DateTimeFormat("en-CA", { timeZone: "Asia/Dhaka", year: "numeric", month: "2-digit", day: "2-digit" }).format(new Date());

export async function handleCustom(st: CustomState, ctx: Ctx, canWrite: boolean, method: string, url: URL, req: IncomingMessage, res: ServerResponse): Promise<boolean> {
  const path = url.pathname;
  const reasonOk = (r: unknown) => typeof r === "string" && Array.from(r).length >= 10 && Array.from(r).length <= 500;

  const scope = /^\/v1\/admin\/users\/(\d+)\/scope$/.exec(path);
  if (scope) {
    const id = Number(scope[1]);
    const user = st.tables.users!.find((u) => u.id === id);
    if (!user) return ctx.send(res, 404, ctx.problem(404, "ERR_NOT_FOUND")), true;
    const cur = st.scopes[id] ?? { version: 1, nodes: [] };
    if (method === "GET") return ctx.send(res, 200, { user_id: id, scope_version: cur.version, nodes: cur.nodes }), true;
    if (method === "PUT") {
      if (!canWrite) return ctx.send(res, 403, ctx.problem(403, "ERR_FORBIDDEN")), true;
      const b = (await ctx.readJson(req)) as { valid_from?: string; nodes?: { node_type: string; node_id: number; valid_to?: string | null }[]; change_reason?: string } | undefined;
      if (!b || !Array.isArray(b.nodes) || typeof b.valid_from !== "string" || !reasonOk(b.change_reason)) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION")), true;
      if (b.nodes.some((n) => n.valid_to != null && n.valid_to <= b.valid_from!)) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION", { errors: [{ pointer: "/nodes", code: "invalid" }] })), true;
      st.scopes[id] = { version: cur.version + 1, nodes: b.nodes.map((n) => ({ node_type: n.node_type, node_id: n.node_id, valid_from: b.valid_from!, valid_to: n.valid_to ?? null })) };
      ctx.audit("user_scope", id, "user_scope.put", { nodes: cur.nodes.length }, { nodes: b.nodes.length, valid_from: b.valid_from }, b.change_reason!);
      return ctx.send(res, 200, { user_id: id, scope_version: st.scopes[id]!.version, nodes: st.scopes[id]!.nodes }), true;
    }
  }

  const acc = /^\/v1\/admin\/users\/(\d+)\/access$/.exec(path);
  if (acc) {
    const id = Number(acc[1]);
    const user = st.tables.users!.find((u) => u.id === id);
    if (!user) return ctx.send(res, 404, ctx.problem(404, "ERR_NOT_FOUND")), true;
    const cur = st.access[id] ?? { version: 1, modules: {}, brand_ids: [] };
    const role = String(user.role);
    if (method === "GET") return ctx.send(res, 200, { user_id: id, role, version: cur.version, modules: cur.modules, brand_ids: cur.brand_ids }, { ETag: `"${cur.version}"` }), true;
    if (method === "PUT") {
      if (!canWrite) return ctx.send(res, 403, ctx.problem(403, "ERR_FORBIDDEN")), true;
      if (req.headers["if-match"] !== `"${cur.version}"`) return ctx.send(res, 412, ctx.problem(412, "ERR_PRECONDITION_FAILED")), true;
      const b = (await ctx.readJson(req)) as { modules?: Record<string, string>; brand_ids?: number[]; change_reason?: string } | undefined;
      if (!b || !reasonOk(b.change_reason)) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION")), true;
      // The server decides what a role may hold: modules for ADMIN, brands for BRAND, nothing else (and no write for SR and AMO).
      const wantsModules = b.modules !== undefined;
      const wantsBrands = b.brand_ids !== undefined;
      if ((wantsModules && role !== "ADMIN") || (wantsBrands && role !== "BRAND") || wantsModules === wantsBrands) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION", { errors: [{ pointer: wantsModules ? "/modules" : "/brand_ids", code: "role_mismatch" }] })), true;
      const brands = new Set(st.tables.product!.filter((n) => n.level === "brand").map((n) => Number(n.id)));
      if (wantsBrands && (b.brand_ids!.length === 0 || b.brand_ids!.some((x) => !brands.has(x)))) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION", { errors: [{ pointer: "/brand_ids", code: b.brand_ids!.length === 0 ? "required" : "unknown" }] })), true;
      const next = { version: cur.version + 1, modules: wantsModules ? { ...b.modules } : cur.modules, brand_ids: wantsBrands ? [...b.brand_ids!] : cur.brand_ids };
      st.access[id] = next;
      ctx.audit("user_access", id, "user_access.put", { modules: JSON.stringify(cur.modules), brand_ids: JSON.stringify(cur.brand_ids) }, { modules: JSON.stringify(next.modules), brand_ids: JSON.stringify(next.brand_ids) }, b.change_reason!);
      return ctx.send(res, 200, { user_id: id, role, version: next.version, modules: next.modules, brand_ids: next.brand_ids }, { ETag: `"${next.version}"` }), true;
    }
  }

  if (path === "/v1/admin/route-targets") {
    if (method === "GET") {
      const month = url.searchParams.get("month");
      const routes = st.tables.routes!;
      let items = st.routeTargets.filter((r) => !month || r.month === month);
      items = [...items].sort((a, b) => (a.route_code + a.sku_code).localeCompare(b.route_code + b.sku_code));
      return ctx.send(res, 200, { items: items.map((r) => ({ ...r, route_name: String(routes.find((x) => x.code === r.route_code)?.name ?? "") })), next_cursor: null }), true;
    }
    if (method === "PUT") {
      if (!canWrite) return ctx.send(res, 403, ctx.problem(403, "ERR_FORBIDDEN")), true;
      const b = (await ctx.readJson(req)) as { batch_uuid?: string; rows?: { route_code: string; sku_code: string; month: string; qty_base: number }[]; dry_run?: boolean; change_reason?: string } | undefined;
      if (!b || typeof b.batch_uuid !== "string" || !Array.isArray(b.rows) || b.rows.length < 1 || typeof b.dry_run !== "boolean") return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION")), true;
      if (!b.dry_run && !reasonOk(b.change_reason)) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION", { errors: [{ pointer: "/change_reason", code: "too_short" }] })), true;
      const routes = st.tables.routes!;
      const skus = st.tables.skus!;
      const errors: { row: number; column: string; code: string }[] = [];
      b.rows.forEach((r, i) => {
        const route = routes.find((x) => x.code === r.route_code);
        if (!route) errors.push({ row: i + 1, column: "route", code: "unknown_route" });
        else if (route.kind !== "sr") errors.push({ row: i + 1, column: "route", code: "not_selling_route" });
        if (!skus.some((x) => x.code === r.sku_code && x.status !== "inactive")) errors.push({ row: i + 1, column: "sku", code: "unknown_sku" });
        if (r.month < thisMonth()) errors.push({ row: i + 1, column: "month", code: "past_month" });
      });
      const months = [...new Set(b.rows.map((r) => r.month))].sort();
      const replaces = st.routeTargets.filter((t) => months.includes(t.month) && b.rows!.some((r) => r.route_code === t.route_code && r.sku_code === t.sku_code && r.month === t.month)).length;
      const summary = { batch_uuid: b.batch_uuid, rows: b.rows.length, routes: new Set(b.rows.map((r) => r.route_code)).size, skus: new Set(b.rows.map((r) => r.sku_code)).size, months, total_qty_base: b.rows.reduce((a, r) => a + r.qty_base, 0), replaces, errors, errors_truncated: false };
      if (b.dry_run) return ctx.send(res, 200, summary), true;
      if (errors.length) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION", { errors: errors.map((e) => ({ pointer: `body.rows/${e.row - 1}/${({ route: "route_code", sku: "sku_code", month: "month", quantity: "qty_base" } as Record<string, string>)[e.column] ?? e.column}`, code: e.code })) })), true;
      const hash = JSON.stringify(b.rows);
      const seen = st.routeTargetBatches.get(b.batch_uuid);
      if (seen) return seen.hash === hash ? (ctx.send(res, 200, { batch_uuid: b.batch_uuid, rows: seen.rows, replayed: true }), true) : (ctx.send(res, 409, ctx.problem(409, "ERR_CONFLICT")), true);
      st.routeTargetBatches.set(b.batch_uuid, { hash, rows: b.rows.length });
      for (const r of b.rows) {
        const cur = st.routeTargets.find((t) => t.route_code === r.route_code && t.sku_code === r.sku_code && t.month === r.month);
        if (cur) cur.qty_base = r.qty_base;
        else st.routeTargets.push({ ...r });
      }
      ctx.audit("route_target", b.batch_uuid, "route_target.commit", { replaced: replaces }, { rows: b.rows.length, total_qty_base: summary.total_qty_base }, b.change_reason!);
      return ctx.send(res, 200, { batch_uuid: b.batch_uuid, rows: b.rows.length, replayed: false }), true;
    }
  }

  // Houses of one territory whose names match after case, spacing and punctuation (listHouseDuplicates) and their merge (mergeHouses).
  if (path === "/v1/admin/geo/house/duplicates" && method === "GET") {
    const key = (n: string) => n.normalize("NFKC").toLowerCase().replace(/[^\p{L}\p{M}\p{N}]+/gu, "");
    const by = new Map<string, Row[]>();
    for (const r of st.tables.geo!.filter((g) => g.level === "house")) {
      const k = `${r.parent_id}\u0000${key(String(r.name))}`;
      if (key(String(r.name)) !== "") by.set(k, [...(by.get(k) ?? []), r]);
    }
    const groups = [...by.values()].filter((m) => m.length > 1).map((m) => {
      const houses = [...m].sort((a, b) => Number(a.id) - Number(b.id));
      return {
        territory_id: Number(houses[0]!.parent_id),
        name_key: key(String(houses[0]!.name)),
        houses: houses.map((h) => ({ id: Number(h.id), code: String(h.code), name: String(h.name), status: h.status, is_active: h.status === "active", zone_count: st.tables.geo!.filter((z) => z.level === "zone" && z.parent_id === h.id).length })),
      };
    });
    return ctx.send(res, 200, { groups }), true;
  }
  if (path === "/v1/admin/geo/house/merge" && method === "POST") {
    if (!canWrite) return ctx.send(res, 403, ctx.problem(403, "ERR_FORBIDDEN")), true;
    const b = (await ctx.readJson(req)) as { keep_id?: number; merge_ids?: number[]; change_reason?: string } | undefined;
    if (!b || !Number.isSafeInteger(b.keep_id) || !Array.isArray(b.merge_ids) || b.merge_ids.length < 1 || b.merge_ids.length > 20 || !reasonOk(b.change_reason)) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION")), true;
    const houses = st.tables.geo!.filter((g) => g.level === "house");
    const keep = houses.find((h) => h.id === b.keep_id);
    const others = b.merge_ids.map((id) => houses.find((h) => h.id === id));
    if (!keep || others.some((h) => !h)) return ctx.send(res, 404, ctx.problem(404, "ERR_NOT_FOUND")), true;
    if (String(req.headers["if-match"] ?? "").replace(/"/g, "") !== String(keep.version)) return ctx.send(res, 412, ctx.problem(412, "ERR_PRECONDITION_FAILED")), true;
    const key = (n: string) => n.normalize("NFKC").toLowerCase().replace(/[^\p{L}\p{M}\p{N}]+/gu, "");
    if (b.merge_ids.includes(keep.id as number) || others.some((h) => h!.parent_id !== keep.parent_id || key(String(h!.name)) !== key(String(keep.name)))) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION", { errors: [{ pointer: "/merge_ids", code: "not_duplicates" }] })), true;
    const item = (h: Row) => ({ id: Number(h.id), code: String(h.code), name: String(h.name), status: h.status, is_active: h.status === "active", zone_count: st.tables.geo!.filter((z) => z.level === "zone" && z.parent_id === h.id).length });
    const merged = others.map((h) => {
      const zones = st.tables.geo!.filter((z) => z.level === "zone" && z.parent_id === h!.id);
      for (const z of zones) z.parent_id = keep.id;
      h!.status = "inactive";
      h!.version = Number(h!.version) + 1;
      return { house_id: Number(h!.id), zones_moved: zones.length };
    });
    keep.version = Number(keep.version) + 1;
    ctx.audit("geo_house", Number(keep.id), "geo_house.merge", { merged: b.merge_ids.join(",") }, { kept: String(keep.id) }, b.change_reason!);
    return ctx.send(res, 200, { kept: item(keep), merged }), true;
  }

  if (path === "/v1/admin/outlets/import" && method === "POST") {
    if (!canWrite) return ctx.send(res, 403, ctx.problem(403, "ERR_FORBIDDEN")), true;
    type R = { code: string | null; name: string; owner_name: string; zone_code: string; cluster: string; channel: string; outlet_kind: string; route_code: string | null; lat: number | null; lng: number | null; contact_number: string | null; address: string | null };
    const b = (await ctx.readJson(req)) as { batch_uuid?: string; rows?: R[]; dry_run?: boolean; change_reason?: string } | undefined;
    if (!b || typeof b.batch_uuid !== "string" || !Array.isArray(b.rows) || b.rows.length < 1 || typeof b.dry_run !== "boolean") return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION")), true;
    if (!b.dry_run && !reasonOk(b.change_reason)) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION", { errors: [{ pointer: "/change_reason", code: "too_short" }] })), true;
    const channels = ["GT", "DCC", "Astha", "RCC", "MT", "HoReCa"];
    const errors: { row: number; column: string; code: string }[] = [];
    const resolved: { r: R; zone: number; cluster: number; route: number | null; existing: Record<string, unknown> | undefined }[] = [];
    b.rows.forEach((r, i) => {
      if (!channels.includes(r.channel)) return void errors.push({ row: i + 1, column: "channel", code: r.channel ? "unknown_channel" : "required" });
      const zone = st.tables.geo!.find((g) => g.level === "zone" && g.code === r.zone_code && g.status === "active");
      if (!zone) return void errors.push({ row: i + 1, column: "zone_code", code: "unknown_zone" });
      const cluster = st.tables.clusters!.find((c) => c.zone_id === zone.id && c.name === r.cluster);
      if (!cluster) errors.push({ row: i + 1, column: "cluster", code: "unknown_cluster" });
      const route = r.route_code ? st.tables.routes!.find((x) => x.code === r.route_code && x.zone_id === zone.id) : null;
      if (r.route_code && !route) errors.push({ row: i + 1, column: "route_code", code: "unknown_route" });
      const existing = r.code ? st.tables.outlets!.find((o) => o.code === r.code) : undefined;
      if (existing && existing.zone_id !== zone.id) errors.push({ row: i + 1, column: "code", code: "code_taken" });
      if (cluster) resolved.push({ r, zone: Number(zone.id), cluster: Number(cluster.id), route: route ? Number(route.id) : null, existing });
    });
    const by_channel: Record<string, number> = {};
    for (const r of b.rows) by_channel[r.channel] = (by_channel[r.channel] ?? 0) + 1;
    const updates = resolved.filter((x) => x.existing).length;
    const summary = { batch_uuid: b.batch_uuid, rows: b.rows.length, creates: b.rows.length - updates, updates, by_channel, errors, errors_truncated: false };
    if (b.dry_run) return ctx.send(res, 200, summary), true;
    if (errors.length) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION", { errors: errors.map((e) => ({ pointer: `body.rows/${e.row - 1}/${e.column}`, code: e.code })) })), true;
    const hash = JSON.stringify(b.rows);
    const seen = st.outletImportBatches.get(b.batch_uuid);
    if (seen) return seen.hash === hash ? (ctx.send(res, 200, { batch_uuid: b.batch_uuid, rows: seen.rows, replayed: true }), true) : (ctx.send(res, 409, ctx.problem(409, "ERR_CONFLICT")), true);
    st.outletImportBatches.set(b.batch_uuid, { hash, rows: b.rows.length });
    const now = new Date().toISOString();
    let nextId = Math.max(0, ...st.tables.outlets!.map((o) => Number(o.id))) + 1;
    for (const { r, zone, cluster, route, existing } of resolved) {
      const fields = { name: r.name, owner_name: r.owner_name, contact_number: r.contact_number, address: r.address, zone_id: zone, route_id: route, cluster_id: cluster, channel: r.channel, outlet_kind: r.outlet_kind, lat: r.lat, lng: r.lng };
      if (existing) {
        Object.assign(existing, fields);
        existing.version = Number(existing.version) + 1;
        existing.updated_at = now;
      } else st.tables.outlets!.push({ id: nextId, version: 1, created_at: now, updated_at: now, status: "active", code: r.code ?? `OUT-${nextId}`, name_bn: null, sub_channel_id: null, geo_class: null, location_confirmed: false, price_type: r.outlet_kind === "wholesale" ? "wholesale" : "regular", visit_sequence: null, external_ref: null, ...fields });
      if (!existing) nextId++;
    }
    ctx.audit("outlet_import", b.batch_uuid, "outlet_import.commit", { rows: 0 }, { rows: b.rows.length, creates: summary.creates, updates }, b.change_reason!);
    return ctx.send(res, 200, { batch_uuid: b.batch_uuid, rows: b.rows.length, replayed: false }), true;
  }

  const plan = /^\/v1\/admin\/sales-plans\/(\d+)$/.exec(path);
  if (plan) {
    const zone = Number(plan[1]);
    if (!st.tables.geo!.some((g) => g.id === zone && g.level === "zone")) return ctx.send(res, 404, ctx.problem(404, "ERR_NOT_FOUND")), true;
    const cur = st.salesPlans[zone] ?? { valid_from: "2026-01-01", sku_ids: [] };
    if (method === "GET") return ctx.send(res, 200, { zone_id: zone, valid_from: cur.valid_from, valid_to: null, sku_ids: cur.sku_ids }), true;
    if (method === "PUT") {
      if (!canWrite) return ctx.send(res, 403, ctx.problem(403, "ERR_FORBIDDEN")), true;
      const b = (await ctx.readJson(req)) as { valid_from?: string; sku_ids?: number[]; change_reason?: string } | undefined;
      if (!b || !Array.isArray(b.sku_ids) || typeof b.valid_from !== "string" || !reasonOk(b.change_reason)) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION")), true;
      if (b.valid_from < today()) return ctx.send(res, 400, ctx.problem(400, "ERR_MASTER_EFFECTIVE_DATE_PAST")), true;
      if (b.sku_ids.some((s) => !st.tables.skus!.some((k) => k.id === s))) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION", { errors: [{ pointer: "/sku_ids", code: "unknown" }] })), true;
      st.salesPlans[zone] = { valid_from: b.valid_from, sku_ids: [...b.sku_ids] };
      ctx.audit("sales_plan", zone, "sales_plan.put", { skus: cur.sku_ids.length }, { skus: b.sku_ids.length, valid_from: b.valid_from }, b.change_reason!);
      return ctx.send(res, 200, { zone_id: zone, valid_from: b.valid_from, valid_to: null, sku_ids: b.sku_ids }), true;
    }
  }

  if (path === "/v1/admin/prices" && method === "GET") {
    const q = url.searchParams;
    const on = q.get("valid_on");
    // valid_on: the rows in force that day (valid_to is exclusive); without it, the open rows.
    let items = on ? st.prices.filter((p) => p.valid_from <= on && (p.valid_to === null || p.valid_to > on)) : st.prices.filter((p) => !p.valid_to);
    if (q.get("sku_id")) items = items.filter((p) => p.sku_id === Number(q.get("sku_id")));
    if (q.get("price_type")) items = items.filter((p) => p.price_type === q.get("price_type"));
    return ctx.send(res, 200, { items, next_cursor: null }), true;
  }
  if ((path === "/v1/admin/prices/preview" || path === "/v1/admin/prices") && method === "POST") {
    if (!canWrite) return ctx.send(res, 403, ctx.problem(403, "ERR_FORBIDDEN")), true;
    const b = (await ctx.readJson(req)) as { batch_uuid?: string; valid_from?: string; prices?: { sku_id: number; price_type: string; amount_mtk: number; per_base_qty?: number }[]; change_reason?: string } | undefined;
    if (!b || !b.batch_uuid || !Array.isArray(b.prices) || b.prices.length < 1 || typeof b.valid_from !== "string" || !reasonOk(b.change_reason)) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION")), true;
    if (b.valid_from < today()) return ctx.send(res, 400, ctx.problem(400, "ERR_MASTER_EFFECTIVE_DATE_PAST")), true;
    const changes = b.prices.map((p) => {
      const cur = st.prices.find((x) => x.sku_id === p.sku_id && x.price_type === p.price_type && !x.valid_to);
      return cur ? (cur.amount_mtk === 0 ? (p.amount_mtk === 0 ? 0 : 1) : Math.abs(p.amount_mtk - cur.amount_mtk) / cur.amount_mtk) : 0;
    });
    const maxPct = Math.round(Math.max(0, ...changes) * 10000) / 100;
    if (path.endsWith("/preview")) {
      return ctx.send(res, 200, { batch_uuid: b.batch_uuid, skus_affected: new Set(b.prices.map((p) => p.sku_id)).size, price_types: [...new Set(b.prices.map((p) => p.price_type))], outlets_affected: 120, devices_affected: 8, max_change_pct: maxPct, approval_required: maxPct > 10 }), true;
    }
    const hash = JSON.stringify([b.valid_from, b.prices]);
    const seen = st.priceBatches.get(b.batch_uuid);
    // A retry of the same batch answers the same result; the same id with other prices is a conflict (contract: 409).
    if (seen) return seen.hash === hash ? (ctx.send(res, 201, { items: [], price_list_version: st.priceBatches.size, batch_uuid: b.batch_uuid, status: seen.status, replayed: true }), true) : (ctx.send(res, 409, ctx.problem(409, "ERR_CONFLICT")), true);
    const status = maxPct > 10 ? "pending_approval" : "applied";
    st.priceBatches.set(b.batch_uuid, { hash, status, valid_from: b.valid_from });
    const before: Record<string, number> = {};
    const after: Record<string, number> = {};
    const created: CustomState["prices"] = [];
    for (const p of b.prices) {
      const k = `${p.sku_id}:${p.price_type}`;
      const cur = st.prices.find((x) => x.sku_id === p.sku_id && x.price_type === p.price_type && !x.valid_to);
      if (cur) before[k] = cur.amount_mtk;
      after[k] = p.amount_mtk;
      if (status === "pending_approval") continue; // waits for a second approver: nothing is in force yet
      for (const old of st.prices.filter((x) => x.sku_id === p.sku_id && x.price_type === p.price_type && !x.valid_to)) old.valid_to = b.valid_from;
      const row = { id: st.prices.length + 1, sku_id: p.sku_id, price_type: p.price_type, amount_mtk: p.amount_mtk, per_base_qty: p.per_base_qty ?? 1, valid_from: b.valid_from, valid_to: null };
      st.prices.push(row);
      created.push(row);
    }
    ctx.audit("price_batch", b.batch_uuid, "price_batch.publish", { ...before, status: "none" }, { ...after, status, valid_from: b.valid_from }, b.change_reason!);
    return ctx.send(res, 201, { items: created, price_list_version: st.priceBatches.size, batch_uuid: b.batch_uuid, status, replayed: false }), true;
  }
  return false;
}
