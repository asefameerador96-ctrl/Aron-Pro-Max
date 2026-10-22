// Per-operation consistency rules the proxy applies before calling the API (the API validates again).
import { isRealDate } from "@/components/admin/crud/validation";
import { businessDate } from "@/lib/i18n";
import { accessShapeRules } from "./access-grants";
import { outletImportRules } from "./outlet-import";
import { routeTargetRules } from "./route-targets";
import { containsMask } from "./mask";

export type FieldProblem = { pointer: string; code: string };

/** |amount| <= 10^13 milli-taka (10 billion taka): the server answers 400 above_max beyond it. */
export const MAX_ADJUSTMENT_MTK = 10_000_000_000_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const PAPER_WINDOW_DAYS = 7;
const shiftDate = (d: string, days: number): string => new Date(Date.parse(`${d}T00:00:00Z`) + days * 86_400_000).toISOString().slice(0, 10);
const isId = (v: unknown) => typeof v === "number" && Number.isSafeInteger(v) && v >= 1;

export function opRules(op: string, body: Record<string, unknown>): FieldProblem[] {
  if (op === "house.merge") {
    const out: FieldProblem[] = [];
    const ids = body.merge_ids;
    if (!isId(body.keep_id)) out.push({ pointer: "/keep_id", code: "invalid" });
    if (!Array.isArray(ids) || ids.length < 1 || ids.length > 20 || !ids.every(isId) || new Set(ids).size !== ids.length) out.push({ pointer: "/merge_ids", code: "invalid" });
    else if (ids.includes(body.keep_id)) out.push({ pointer: "/merge_ids", code: "not_duplicates" });
    for (const k of Object.keys(body)) if (!["keep_id", "merge_ids", "change_reason"].includes(k)) out.push({ pointer: `/${k}`, code: "unknown_member" });
    return out;
  }
  if (op === "user-access.put") return accessShapeRules(body);
  if (op === "outlet-import.validate" || op === "outlet-import.commit") return outletImportRules(op === "outlet-import.validate" ? "validate" : "commit", body);
  if (op === "route-targets.validate" || op === "route-targets.commit") return routeTargetRules(op === "route-targets.validate" ? "validate" : "commit", body);
  if (op === "quarantine.resolve") {
    const out: FieldProblem[] = [];
    const fix = body.fixed_record;
    const has = fix !== undefined && fix !== null;
    if (body.action === "accept_with_fix" && !has) out.push({ pointer: "/fixed_record", code: "required" });
    if (body.action !== "accept_with_fix" && has) out.push({ pointer: "/fixed_record", code: "invalid" });
    if (has && (typeof fix !== "object" || Array.isArray(fix) || containsMask(fix))) out.push({ pointer: "/fixed_record", code: "invalid" });
    return out;
  }
  if (op === "dues.create") {
    const out: FieldProblem[] = [];
    const a = body.amount_mtk;
    if (typeof a !== "number" || !Number.isSafeInteger(a)) out.push({ pointer: "/amount_mtk", code: "invalid" });
    else if (a === 0) out.push({ pointer: "/amount_mtk", code: "zero" });
    else if (Math.abs(a) > MAX_ADJUSTMENT_MTK) out.push({ pointer: "/amount_mtk", code: "above_max" });
    else if (body.kind === "write_off" && a > 0) out.push({ pointer: "/amount_mtk", code: "write_off_must_be_negative" }); // a write-off only lowers a due
    const memo = body.memo_client_uuid;
    if (memo !== undefined && memo !== null && (typeof memo !== "string" || !UUID.test(memo))) out.push({ pointer: "/memo_client_uuid", code: "invalid" });
    if (!isId(body.outlet_id)) out.push({ pointer: "/outlet_id", code: "invalid" });
    return out;
  }
  if (op === "paper-backfill.create") {
    const out: FieldProblem[] = [];
    if (typeof body.memo_no !== "string" || !/^[a-z][a-z0-9]{3,31}-\d{6}-\d{3,4}$/.test(body.memo_no)) out.push({ pointer: "/memo_no", code: "invalid" });
    const d = body.business_date;
    if (typeof d !== "string" || !isRealDate(d)) out.push({ pointer: "/business_date", code: "invalid" });
    else if (d > businessDate() || d < shiftDate(businessDate(), -PAPER_WINDOW_DAYS)) out.push({ pointer: "/business_date", code: "window" }); // cfg.entry.paper_backfill_window_days (7)
    for (const k of ["user_id", "route_id", "outlet_id"]) if (!isId(body[k])) out.push({ pointer: `/${k}`, code: "invalid" });
    if (body.discount_mtk !== undefined && body.discount_mtk !== 0) out.push({ pointer: "/discount_mtk", code: "invalid" }); // discount programmes are deferred (docs/27): the offer-discount line stays zero
    for (const k of ["paid_mtk"]) {
      const v = body[k];
      if (typeof v !== "number" || !Number.isSafeInteger(v) || v < 0) out.push({ pointer: `/${k}`, code: "invalid" });
      else if (v > MAX_ADJUSTMENT_MTK) out.push({ pointer: `/${k}`, code: "above_max" });
    }
    const lines = body.lines;
    if (!Array.isArray(lines) || lines.length < 1 || lines.length > 60) out.push({ pointer: "/lines", code: "invalid" });
    else
      lines.forEach((l, i) => {
        const ok = typeof l === "object" && l !== null && isId((l as Record<string, unknown>).sku_id) && Number.isInteger((l as Record<string, unknown>).qty_base) && (l as { qty_base: number }).qty_base >= 1 && (l as { qty_base: number }).qty_base <= 10_000_000 && ["sale"].includes(String((l as Record<string, unknown>).line_kind));
        if (!ok) out.push({ pointer: `/lines/${i}`, code: "invalid" });
      });
    return out;
  }
  return [];
}
