// Mock of the master-data operations that are not plain table CRUD: user scope, sales plans, prices (preview + publish).
import type { IncomingMessage, ServerResponse } from "node:http";
import type { Ctx, Row } from "./tables";

export interface CustomState {
  tables: Record<string, Row[]>;
  scopes: Record<number, { version: number; nodes: { node_type: string; node_id: number; valid_from: string; valid_to: string | null }[] }>;
  salesPlans: Record<number, { valid_from: string; sku_ids: number[] }>;
  priceBatches: Map<string, { hash: string; status: "applied" | "pending_approval"; valid_from: string }>;
  prices: { id: number; sku_id: number; price_type: string; amount_mtk: number; per_base_qty: number; valid_from: string; valid_to: string | null }[];
}

export function seedCustom(): Pick<CustomState, "scopes" | "salesPlans" | "priceBatches" | "prices"> {
  return {
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
      const b = (await ctx.readJson(req)) as { valid_from?: string; nodes?: { node_type: string; node_id: number }[]; change_reason?: string } | undefined;
      if (!b || !Array.isArray(b.nodes) || typeof b.valid_from !== "string" || !reasonOk(b.change_reason)) return ctx.send(res, 400, ctx.problem(400, "ERR_VALIDATION")), true;
      st.scopes[id] = { version: cur.version + 1, nodes: b.nodes.map((n) => ({ node_type: n.node_type, node_id: n.node_id, valid_from: b.valid_from!, valid_to: null })) };
      ctx.audit("user_scope", id, "user_scope.put", { nodes: cur.nodes.length }, { nodes: b.nodes.length, valid_from: b.valid_from }, b.change_reason!);
      return ctx.send(res, 200, { user_id: id, scope_version: st.scopes[id]!.version, nodes: st.scopes[id]!.nodes }), true;
    }
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
