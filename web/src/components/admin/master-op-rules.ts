// Server-side rules of the master operations, beyond what the contract's schemas say (role-node consistency, past dates, money).
import type { Role } from "@/contract/types";
import { rawRequest } from "@/lib/api/raw";
import { scopeNodeAllowed } from "@/lib/admin/scope-rules";
import type { MasterOpKey } from "@/lib/admin/master-ops";
import { businessDate } from "@/lib/i18n";
import { isRealDate } from "./crud/validation";

export interface RuleError {
  pointer: string;
  code: string;
}

const isObj = (v: unknown): v is Record<string, unknown> => typeof v === "object" && v !== null && !Array.isArray(v);
const isId = (v: unknown): v is number => typeof v === "number" && Number.isInteger(v) && v >= 1 && v <= Number.MAX_SAFE_INTEGER;
const futureDate = (v: unknown, pointer: string, out: RuleError[]) => {
  if (typeof v !== "string" || !isRealDate(v)) out.push({ pointer, code: "invalid" });
  else if (v < businessDate()) out.push({ pointer, code: "past" }); // nothing historic is rewritten
};

export async function masterOpRules(op: MasterOpKey, body: Record<string, unknown>, params: Record<string, unknown>, token: string, actor: Role): Promise<RuleError[]> {
  const out: RuleError[] = [];
  const strict = (allowed: string[]) => {
    for (const k of Object.keys(body)) if (!allowed.includes(k)) out.push({ pointer: `/body/${k}`, code: "unknown_member" });
  };
  switch (op) {
    case "sales-plan.put": {
      strict(["valid_from", "sku_ids", "change_reason"]);
      futureDate(body.valid_from, "/body/valid_from", out);
      const ids = body.sku_ids;
      if (!Array.isArray(ids) || ids.length > 500 || !ids.every(isId) || new Set(ids).size !== ids.length) out.push({ pointer: "/body/sku_ids", code: "invalid" });
      break;
    }
    case "user-scope.put": {
      strict(["valid_from", "nodes", "change_reason"]);
      futureDate(body.valid_from, "/body/valid_from", out);
      const nodes = body.nodes;
      if (!Array.isArray(nodes) || nodes.length > 100) {
        out.push({ pointer: "/body/nodes", code: "invalid" });
        break;
      }
      // Role-node consistency needs the user's role: read it from the API, never from the browser.
      const u = await rawRequest<{ role?: Role }>({ method: "GET", path: `/v1/admin/users/${encodeURIComponent(String(params.id))}`, token });
      if (!u.ok || !u.data.role) {
        out.push({ pointer: "/params/id", code: !u.ok && u.status === 404 ? "not_found" : "invalid" });
        break;
      }
      // Only SUPERADMIN writes ADMIN-role users (docs/24 s8.5).
      if ((u.data.role === "ADMIN" || u.data.role === "SUPERADMIN") && actor !== "SUPERADMIN") out.push({ pointer: "/params/id", code: "forbidden_target" });
      nodes.forEach((n, i) => {
        if (!isObj(n) || Object.keys(n).some((k) => k !== "node_type" && k !== "node_id")) out.push({ pointer: `/body/nodes/${i}`, code: "invalid" });
        else if (typeof n.node_type !== "string" || !scopeNodeAllowed(u.data.role!, n.node_type)) out.push({ pointer: `/body/nodes/${i}/node_type`, code: "role_node_mismatch" });
        else if (typeof n.node_id !== "number" || !Number.isSafeInteger(n.node_id) || n.node_id < (n.node_type === "national" ? 0 : 1)) out.push({ pointer: `/body/nodes/${i}/node_id`, code: "invalid" });
      });
      const seen = new Set<string>();
      nodes.forEach((n, i) => {
        if (!isObj(n)) return;
        const k = `${String(n.node_type)}:${String(n.node_id)}`;
        if (seen.has(k)) out.push({ pointer: `/body/nodes/${i}`, code: "duplicate" });
        seen.add(k);
      });
      if (nodes.length === 0 && u.data.role !== "SR") out.push({ pointer: "/body/nodes", code: "required" });
      break;
    }
    case "price.preview":
    case "price.publish": {
      strict(["batch_uuid", "valid_from", "prices", "change_reason"]);
      if (typeof body.batch_uuid !== "string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(body.batch_uuid)) out.push({ pointer: "/body/batch_uuid", code: "invalid" });
      futureDate(body.valid_from, "/body/valid_from", out);
      if (body.valid_from === businessDate()) out.push({ pointer: "/body/valid_from", code: "past" }); // contract: from a FUTURE Dhaka date
      const prices = body.prices;
      if (!Array.isArray(prices) || prices.length < 1 || prices.length > 1000) {
        out.push({ pointer: "/body/prices", code: "invalid" });
        break;
      }
      const seen = new Set<string>();
      prices.forEach((p, i) => {
        if (!isObj(p) || Object.keys(p).some((k) => !["sku_id", "price_type", "amount_mtk", "per_base_qty"].includes(k))) return void out.push({ pointer: `/body/prices/${i}`, code: "invalid" });
        if (!isId(p.sku_id)) out.push({ pointer: `/body/prices/${i}/sku_id`, code: "invalid" });
        if (!["outlet", "cc", "distributor", "reporting", "nto"].includes(String(p.price_type))) out.push({ pointer: `/body/prices/${i}/price_type`, code: "invalid" });
        // Money is an integer number of milli-taka (never a float): three decimals of a taka.
        if (typeof p.amount_mtk !== "number" || !Number.isSafeInteger(p.amount_mtk) || p.amount_mtk < 0) out.push({ pointer: `/body/prices/${i}/amount_mtk`, code: "invalid" });
        if (p.per_base_qty !== undefined && (!Number.isInteger(p.per_base_qty) || (p.per_base_qty as number) < 1 || (p.per_base_qty as number) > 1000)) out.push({ pointer: `/body/prices/${i}/per_base_qty`, code: "invalid" });
        const k = `${p.sku_id}:${p.price_type}`;
        if (seen.has(k)) out.push({ pointer: `/body/prices/${i}`, code: "duplicate" });
        seen.add(k);
      });
      break;
    }
    case "assignment.create": {
      strict(["route_id", "user_id", "kind", "valid_from", "valid_to", "reason"]);
      if (!isId(body.route_id)) out.push({ pointer: "/body/route_id", code: "invalid" });
      if (!isId(body.user_id)) out.push({ pointer: "/body/user_id", code: "invalid" });
      if (body.kind !== "primary" && body.kind !== "cover") out.push({ pointer: "/body/kind", code: "invalid" });
      futureDate(body.valid_from, "/body/valid_from", out);
      if (body.valid_to !== undefined && body.valid_to !== null) futureDate(body.valid_to, "/body/valid_to", out);
      if (typeof body.valid_from === "string" && typeof body.valid_to === "string" && body.valid_to <= body.valid_from) out.push({ pointer: "/body/valid_to", code: "before_start" });
      break;
    }
    case "credential.manage": {
      strict(["action", "reason"]);
      // Only the two actions of the row; force logout and MFA reset stay with SUPPORT and the administrators.
      const allowed = actor === "TSO" ? ["reset_password", "unlock"] : ["reset_password", "unlock", "force_logout", "reset_mfa"];
      if (typeof body.action !== "string" || !allowed.includes(body.action)) out.push({ pointer: "/body/action", code: "invalid" });
      if (actor === "TSO") {
        // The target must be an SR or AMO (the API also checks the zone); a TSO never touches another TSO or an administrator.
        const u = await rawRequest<{ role?: Role }>({ method: "GET", path: `/v1/admin/users/${encodeURIComponent(String(params.id))}`, token });
        if (!u.ok) out.push({ pointer: "/params/id", code: u.status === 404 ? "not_found" : "invalid" });
        else if (u.data.role !== "SR" && u.data.role !== "AMO") out.push({ pointer: "/params/id", code: "forbidden_target" });
      }
      break;
    }
    case "assignment.end": {
      strict(["valid_to", "reason"]);
      futureDate(body.valid_to, "/body/valid_to", out);
      break;
    }
  }
  return out;
}
