// Per-operation consistency rules the proxy applies before calling the API (the API validates again).
import { containsMask } from "./mask";

export type FieldProblem = { pointer: string; code: string };

const isId = (v: unknown) => typeof v === "number" && Number.isSafeInteger(v) && v >= 1;

export function opRules(op: string, body: Record<string, unknown>): FieldProblem[] {
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
    if (typeof a !== "number" || !Number.isSafeInteger(a) || a === 0) out.push({ pointer: "/amount_mtk", code: "invalid" });
    else if (body.kind === "write_off" && a > 0) out.push({ pointer: "/amount_mtk", code: "invalid" }); // a write-off only lowers a due
    if (!isId(body.outlet_id)) out.push({ pointer: "/outlet_id", code: "invalid" });
    return out;
  }
  return [];
}
